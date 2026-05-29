// JNI bridge for whisper.cpp on Android.
//   - nativeVersion()             : verifies linking, returns whisper_print_system_info
//   - nativeInitFromFile(path,gpu): loads a GGUF model, returns opaque handle
//   - nativeFree(handle)          : releases context
//   - nativeFullTranscribe(...)   : runs whisper_full on 16 kHz mono Float32 PCM,
//                                   returns Segment[] {startMs, endMs, text}

#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG_TAG "AisWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeVersion(JNIEnv *env, jclass /*clazz*/) {
    const char *v = whisper_print_system_info();
    return env->NewStringUTF(v ? v : "");
}

JNIEXPORT jlong JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeInitFromFile(
        JNIEnv *env, jclass /*clazz*/, jstring modelPath, jboolean useGpu) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu ? true : false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    LOGI("whisper context initialised: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeFree(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    whisper_free(reinterpret_cast<struct whisper_context *>(handle));
}

/// Runs whisper_full and returns an Object[] where every triple is
/// [Long startMs, Long endMs, String text]. Easier than constructing
/// a custom Java class array via JNI.
JNIEXPORT jobjectArray JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeFullTranscribe(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jfloatArray pcm,
        jstring language,    // nullable
        jint nThreads) {

    if (handle == 0 || pcm == nullptr) {
        LOGE("nativeFullTranscribe: handle=0 or null pcm");
        return nullptr;
    }
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(handle);

    jsize n_samples = env->GetArrayLength(pcm);
    if (n_samples <= 0) {
        LOGE("nativeFullTranscribe: empty PCM");
        return nullptr;
    }

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_special    = false;
    params.print_timestamps = true;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.n_threads        = nThreads > 0 ? nThreads : 4;

    std::string lang_str;
    if (language != nullptr) {
        const char *lang = env->GetStringUTFChars(language, nullptr);
        lang_str = lang ? lang : "auto";
        env->ReleaseStringUTFChars(language, lang);
    } else {
        lang_str = "auto";
    }
    params.language       = lang_str.c_str();
    params.detect_language = lang_str == "auto";

    int rc = whisper_full(ctx, params, samples, (int)n_samples);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full rc=%d", rc);
        return nullptr;
    }

    int n_segments = whisper_full_n_segments(ctx);
    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(n_segments * 3, objCls, nullptr);

    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID longCtor = env->GetMethodID(longCls, "<init>", "(J)V");

    for (int i = 0; i < n_segments; i++) {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);

        jobject jStart = env->NewObject(longCls, longCtor, (jlong)t0);
        jobject jEnd   = env->NewObject(longCls, longCtor, (jlong)t1);
        jstring jText  = env->NewStringUTF(text ? text : "");

        env->SetObjectArrayElement(result, i * 3 + 0, jStart);
        env->SetObjectArrayElement(result, i * 3 + 1, jEnd);
        env->SetObjectArrayElement(result, i * 3 + 2, jText);

        env->DeleteLocalRef(jStart);
        env->DeleteLocalRef(jEnd);
        env->DeleteLocalRef(jText);
    }
    return result;
}

} // extern "C"
