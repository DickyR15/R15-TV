#include <jni.h>
#include <algorithm>
#include <chrono>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define R15_WHISPER_LOG_TAG "R15Whisper"

namespace {
void throwIllegalState(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fongmi_android_tv_ai_subtitle_WhisperCpp_nativeCreateContext(
        JNIEnv *env, jclass, jstring model_path) {
    if (model_path == nullptr) {
        throwIllegalState(env, "Whisper 模型路徑為空");
        return 0;
    }

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    const auto load_started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, R15_WHISPER_LOG_TAG, "model_load_start");
    whisper_context *context = whisper_init_from_file_with_params(path, context_params);
    const auto load_elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - load_started).count();
    __android_log_print(ANDROID_LOG_INFO, R15_WHISPER_LOG_TAG,
            "model_load_done ok=%d elapsed_ms=%lld",
            context != nullptr, static_cast<long long>(load_elapsed));
    env->ReleaseStringUTFChars(model_path, path);

    if (context == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, R15_WHISPER_LOG_TAG, "model_load_failed");
        throwIllegalState(env, "Whisper 無法載入模型；請重新下載 Whisper Base");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fongmi_android_tv_ai_subtitle_WhisperCpp_nativeTranscribe(
        JNIEnv *env, jclass, jlong context_ptr, jfloatArray audio_data,
        jstring language, jint num_threads) {
    auto *context = reinterpret_cast<whisper_context *>(context_ptr);
    if (context == nullptr || audio_data == nullptr) {
        throwIllegalState(env, "Whisper 尚未初始化");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(audio_data);
    if (sample_count < 16000) {
        return env->NewStringUTF("");
    }

    jboolean is_copy = JNI_FALSE;
    jfloat *samples = env->GetFloatArrayElements(audio_data, &is_copy);
    if (samples == nullptr) return nullptr;

    const char *language_chars = language == nullptr
            ? nullptr
            : env->GetStringUTFChars(language, nullptr);
    const bool auto_language = language_chars == nullptr
            || language_chars[0] == '\0'
            || std::string(language_chars) == "auto";

    whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(4, static_cast<int>(num_threads)));
    params.greedy.best_of = 1; // Do not score multiple candidates on this low-core TV box.
    params.max_tokens = 64;    // Bound decode work for each short live-caption window.
    params.single_segment = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.translate = false; // ML Kit performs English-to-Chinese translation.
    params.no_context = true;
    params.no_timestamps = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.single_segment = false;
    params.language = auto_language ? nullptr : language_chars;
    params.detect_language = auto_language;

    const auto inference_started = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, R15_WHISPER_LOG_TAG,
            "decode_start samples=%d seconds=%.2f language=%s threads=%d",
            static_cast<int>(sample_count), sample_count / 16000.0f,
            auto_language ? "auto" : language_chars, params.n_threads);
    const int result = whisper_full(context, params, samples, sample_count);
    const auto inference_elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - inference_started).count();
    __android_log_print(ANDROID_LOG_INFO, R15_WHISPER_LOG_TAG,
            "decode_done rc=%d elapsed_ms=%lld segments=%d",
            result, static_cast<long long>(inference_elapsed),
            result == 0 ? whisper_full_n_segments(context) : 0);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    if (language_chars != nullptr) {
        env->ReleaseStringUTFChars(language, language_chars);
    }

    if (result != 0) {
        const std::string message = "Whisper 推論失敗，錯誤碼：" + std::to_string(result);
        throwIllegalState(env, message.c_str());
        return nullptr;
    }

    std::string text;
    const int segment_count = whisper_full_n_segments(context);
    for (int i = 0; i < segment_count; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment == nullptr) continue;
        if (!text.empty()) text.push_back(' ');
        text.append(segment);
    }

    const char *detected_language = whisper_lang_str(whisper_full_lang_id(context));
    std::string payload = (detected_language == nullptr || detected_language[0] == '\0')
            ? "und" : detected_language;
    payload.push_back('\n');
    payload.append(text);
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_android_tv_ai_subtitle_WhisperCpp_nativeFreeContext(
        JNIEnv *, jclass, jlong context_ptr) {
    auto *context = reinterpret_cast<whisper_context *>(context_ptr);
    if (context != nullptr) whisper_free(context);
}
