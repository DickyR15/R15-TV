package com.fongmi.android.tv.ai.subtitle;

import java.io.File;

/** Thin JNI wrapper for whisper.cpp. Model files live in app-private storage, not in the APK. */
final class WhisperCpp {
    static {
        System.loadLibrary("r15whisper");
    }

    private WhisperCpp() { }

    static long createContext(File modelFile) {
        if (modelFile == null || !modelFile.isFile() || modelFile.length() < 100L * 1024L * 1024L) {
            throw new IllegalStateException("Whisper Base 模型不存在或檔案不完整");
        }
        return nativeCreateContext(modelFile.getAbsolutePath());
    }

    static String transcribe(long context, float[] audio, String language, int threads) {
        if (context == 0L) throw new IllegalStateException("Whisper 尚未初始化");
        return nativeTranscribe(context, audio, language, threads);
    }

    static void freeContext(long context) {
        if (context != 0L) nativeFreeContext(context);
    }

    private static native long nativeCreateContext(String modelPath);
    private static native String nativeTranscribe(
            long context, float[] audio, String language, int threads);
    private static native void nativeFreeContext(long context);
}
