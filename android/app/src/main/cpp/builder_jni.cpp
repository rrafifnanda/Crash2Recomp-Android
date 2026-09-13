#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex child_mutex;
pid_t child_pid = -1;

std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    const jsize count = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        const char* utf8 = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(utf8);
        env->ReleaseStringUTFChars(value, utf8);
        env->DeleteLocalRef(value);
    }
    return result;
}

std::vector<char*> pointers(std::vector<std::string>& values) {
    std::vector<char*> result;
    result.reserve(values.size() + 1);
    for (std::string& value : values) result.push_back(value.data());
    result.push_back(nullptr);
    return result;
}

void throw_error(JNIEnv* env, const char* operation, int error) {
    const std::string message = std::string(operation) + ": " + strerror(error);
    jclass type = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(type, message.c_str());
}
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_crash2recomp_BuilderNative_run(
        JNIEnv* env, jclass, jobjectArray argv_java, jstring cwd_java,
        jstring log_java, jobjectArray environment_java) {
    std::vector<std::string> argv = strings(env, argv_java);
    std::vector<std::string> environment = strings(env, environment_java);
    if (argv.empty()) {
        throw_error(env, "builder argv", EINVAL);
        return -1;
    }
    std::vector<char*> argv_raw = pointers(argv);
    std::vector<char*> environment_raw = pointers(environment);
    const char* cwd = env->GetStringUTFChars(cwd_java, nullptr);
    const char* log = env->GetStringUTFChars(log_java, nullptr);

    {
        std::lock_guard<std::mutex> lock(child_mutex);
        if (child_pid > 0) {
            env->ReleaseStringUTFChars(cwd_java, cwd);
            env->ReleaseStringUTFChars(log_java, log);
            throw_error(env, "builder is already running", EBUSY);
            return -1;
        }
        child_pid = fork();
    }
    if (child_pid == 0) {
        if (chdir(cwd) != 0) _exit(125);
        int output = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (output < 0) _exit(126);
        dup2(output, STDOUT_FILENO);
        dup2(output, STDERR_FILENO);
        close(output);
        execve(argv_raw[0], argv_raw.data(), environment_raw.data());
        _exit(127);
    }
    env->ReleaseStringUTFChars(cwd_java, cwd);
    env->ReleaseStringUTFChars(log_java, log);
    if (child_pid < 0) {
        int error = errno;
        std::lock_guard<std::mutex> lock(child_mutex);
        child_pid = -1;
        throw_error(env, "fork builder", error);
        return -1;
    }

    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(child_pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    {
        std::lock_guard<std::mutex> lock(child_mutex);
        child_pid = -1;
    }
    if (waited < 0) {
        throw_error(env, "wait for builder", errno);
        return -1;
    }
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_crash2recomp_BuilderNative_cancel(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(child_mutex);
    if (child_pid > 0) kill(child_pid, SIGKILL);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_crash2recomp_BuilderNative_verifyModule(
        JNIEnv* env, jclass, jstring host_java, jstring module_java) {
    const char* host_path = env->GetStringUTFChars(host_java, nullptr);
    const char* module_path = env->GetStringUTFChars(module_java, nullptr);
    void* host = dlopen(host_path, RTLD_NOW | RTLD_GLOBAL);
    std::string error;
    if (!host) {
        error = std::string("cannot load Android runtime: ") + dlerror();
    } else {
        void* module = dlopen(module_path, RTLD_NOW | RTLD_LOCAL);
        if (!module) {
            error = std::string("cannot load generated game module: ") + dlerror();
        } else {
            const char* required[] = {
                    "psx_dispatch_game_compiled",
                    "psx_game_address_in_text",
                    "psx_game_is_function_entry"};
            for (const char* symbol : required) {
                if (!dlsym(module, symbol)) {
                    error = std::string("generated game module is missing ") + symbol;
                    break;
                }
            }
            dlclose(module);
        }
    }
    env->ReleaseStringUTFChars(host_java, host_path);
    env->ReleaseStringUTFChars(module_java, module_path);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
