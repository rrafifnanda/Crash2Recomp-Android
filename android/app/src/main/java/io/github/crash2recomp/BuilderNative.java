package io.github.crash2recomp;

final class BuilderNative {
    static {
        System.loadLibrary("builder-jni");
    }

    private BuilderNative() {}

    static native int run(String[] argv, String cwd, String logPath, String[] environment);
    static native void cancel();
    static native String verifyModule(String hostPath, String modulePath);
}
