// Thin JNI layer over whisper.cpp: load a model (and optionally the Silero VAD
// model), then turn one finished utterance of 16 kHz mono float PCM into text.
#include <jni.h>

#include <string>
#include <vector>

#include "whisper.h"

namespace {

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_uk_co_jwgardenservices_crm_voice_WhisperCppNative_nativeInit(JNIEnv *env, jclass, jstring model_path) {
    const std::string path = to_string(env, model_path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_uk_co_jwgardenservices_crm_voice_WhisperCppNative_nativeTranscribe(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint threads, jint audio_ctx, jstring vad_path) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || samples == nullptr) return env->NewStringUTF("");

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());

    const std::string vad = to_string(env, vad_path);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    // Without this a doubtful sentence is decoded again at higher temperatures,
    // which doubles the wait for little gain on a short command.
    params.temperature_inc = 0.0f;
    params.audio_ctx = audio_ctx > 0 ? audio_ctx : 0;
    if (!vad.empty()) {
        params.vad = true;
        params.vad_model_path = vad.c_str();
    }

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *piece = whisper_full_get_segment_text(ctx, i);
        if (piece != nullptr) text += piece;
    }
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_uk_co_jwgardenservices_crm_voice_WhisperCppNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
