#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "SimpleConverter.hpp"

namespace {
std::string ToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowJava(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fongmi_android_tv_ai_subtitle_TaiwanTraditionalConverter_nativeCreate(
        JNIEnv* env, jclass, jstring configFile, jstring dictionaryDirectory) {
    try {
        const std::string config = ToUtf8(env, configFile);
        const std::string dictionary = ToUtf8(env, dictionaryDirectory);
        if (config.empty() || dictionary.empty()) {
            ThrowJava(env, "OpenCC 1.4.2 config/dictionary path is empty");
            return 0;
        }
        std::vector<std::string> paths = {dictionary, config.substr(0, config.find_last_of('/'))};
        auto converter = std::make_unique<opencc::SimpleConverter>(config, paths);
        return reinterpret_cast<jlong>(converter.release());
    } catch (const std::exception& error) {
        ThrowJava(env, error.what());
        return 0;
    } catch (...) {
        ThrowJava(env, "OpenCC 1.4.2 initialization failed");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fongmi_android_tv_ai_subtitle_TaiwanTraditionalConverter_nativeConvert(
        JNIEnv* env, jclass, jlong handle, jstring input) {
    if (handle == 0) {
        ThrowJava(env, "OpenCC converter is not initialized");
        return nullptr;
    }
    try {
        auto* converter = reinterpret_cast<opencc::SimpleConverter*>(handle);
        const std::string text = ToUtf8(env, input);
        const std::string output = converter->Convert(text);
        return env->NewStringUTF(output.c_str());
    } catch (const std::exception& error) {
        ThrowJava(env, error.what());
        return nullptr;
    } catch (...) {
        ThrowJava(env, "OpenCC 1.4.2 conversion failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_android_tv_ai_subtitle_TaiwanTraditionalConverter_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<opencc::SimpleConverter*>(handle);
}
