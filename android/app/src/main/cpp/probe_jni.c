#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(JNIEnv *env, const char *operation) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(errno));
    jclass type = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, type, message);
}

static int copy_file(const char *source, const char *target) {
    int in = open(source, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (in < 0) return -1;
    int out = open(target, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (out < 0) {
        close(in);
        return -1;
    }

    char buffer[16384];
    ssize_t count;
    while ((count = read(in, buffer, sizeof(buffer))) > 0) {
        char *cursor = buffer;
        while (count > 0) {
            ssize_t written = write(out, cursor, (size_t) count);
            if (written < 0) {
                close(in);
                close(out);
                return -1;
            }
            cursor += written;
            count -= written;
        }
    }
    int saved = errno;
    close(in);
    if (close(out) != 0 || count < 0) {
        errno = saved;
        return -1;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_io_github_crash2recomp_NativeProbe_run(
        JNIEnv *env, jclass type, jstring native_dir_java, jstring files_dir_java) {
    (void) type;
    const char *native_dir = (*env)->GetStringUTFChars(env, native_dir_java, NULL);
    const char *files_dir = (*env)->GetStringUTFChars(env, files_dir_java, NULL);
    char tool[PATH_MAX];
    char payload[PATH_MAX];
    char copied_payload[PATH_MAX];
    snprintf(tool, sizeof(tool), "%s/libcrash2-probe.so", native_dir);
    snprintf(payload, sizeof(payload), "%s/libcrash2-probe-payload.so", native_dir);
    snprintf(copied_payload, sizeof(copied_payload), "%s/libcrash2-probe-payload.so", files_dir);

    int output[2];
    if (pipe(output) != 0) {
        fail(env, "pipe");
        goto done;
    }
    pid_t pid = fork();
    if (pid == 0) {
        close(output[0]);
        dup2(output[1], STDOUT_FILENO);
        close(output[1]);
        char *const argv[] = {tool, NULL};
        execv(tool, argv);
        _exit(127);
    }
    close(output[1]);
    if (pid < 0) {
        close(output[0]);
        fail(env, "fork");
        goto done;
    }

    char child_output[64] = {0};
    ssize_t bytes = read(output[0], child_output, sizeof(child_output) - 1);
    close(output[0]);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        fail(env, "waitpid");
        goto done;
    }
    if (bytes < 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0 ||
            strcmp(child_output, "probe-ok\n") != 0) {
        errno = ENOEXEC;
        fail(env, "execute packaged tool");
        goto done;
    }

    if (copy_file(payload, copied_payload) != 0) {
        fail(env, "copy probe payload");
        goto done;
    }
    void *module = dlopen(copied_payload, RTLD_NOW | RTLD_LOCAL);
    if (!module) {
        jclass error_type = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, error_type, dlerror());
        goto done;
    }
    int (*probe_value)(void) = (int (*)(void)) dlsym(module, "crash2_probe_value");
    if (!probe_value || probe_value() != 94154) {
        dlclose(module);
        errno = ELIBBAD;
        fail(env, "load private module");
        goto done;
    }
    dlclose(module);

    (*env)->ReleaseStringUTFChars(env, native_dir_java, native_dir);
    (*env)->ReleaseStringUTFChars(env, files_dir_java, files_dir);
    return (*env)->NewStringUTF(env, "ok:0:probe-ok");

done:
    (*env)->ReleaseStringUTFChars(env, native_dir_java, native_dir);
    (*env)->ReleaseStringUTFChars(env, files_dir_java, files_dir);
    return NULL;
}
