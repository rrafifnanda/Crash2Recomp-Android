package io.github.crash2recomp;

final class NativeProbe {
    static {
        System.loadLibrary("crash2-probe-jni");
    }

    private NativeProbe() {}

    static native String run(String nativeLibraryDir, String filesDir);
}
