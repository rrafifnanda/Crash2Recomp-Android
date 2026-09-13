#include <jni.h>
#include <stdint.h>

#include <SDL3/SDL_system.h>

static void call_activity(const char *method, int first, int second) {
    JNIEnv *env = SDL_GetAndroidJNIEnv();
    jobject activity = SDL_GetAndroidActivity();
    if (!env || !activity) return;
    jclass type = (*env)->GetObjectClass(env, activity);
    jmethodID callback = (*env)->GetMethodID(env, type, method, "(II)V");
    if (callback) (*env)->CallVoidMethod(env, activity, callback, first, second);
    (*env)->DeleteLocalRef(env, type);
    (*env)->DeleteLocalRef(env, activity);
}

void android_input_rumble(uint8_t small, uint8_t large) {
    call_activity("onNativeRumble", small, large);
}

void android_input_haptic(int milliseconds) {
    call_activity("onNativeHaptic", milliseconds, 0);
}

void android_input_ui_sound(void) {}
