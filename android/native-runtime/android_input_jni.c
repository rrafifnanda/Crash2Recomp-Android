#include <jni.h>
#include <stdint.h>

#include "android_input.h"

JNIEXPORT void JNICALL
Java_io_github_crash2recomp_Crash2SDLActivity_nativeSetGamepad(
        JNIEnv *env, jclass type, jint active_low_word) {
    (void)env;
    (void)type;
    android_input_set_pad_word((uint16_t)active_low_word);
}

JNIEXPORT void JNICALL
Java_io_github_crash2recomp_Crash2SDLActivity_nativeSetSticks(
        JNIEnv *env, jclass type, jint lx, jint ly, jint rx, jint ry) {
    (void)env;
    (void)type;
    android_input_set_sticks((uint8_t)lx, (uint8_t)ly, (uint8_t)rx, (uint8_t)ry);
}
