// fluidsynth_bridge.cpp
// JNI bridge between Android Kotlin and FluidSynth C library
// Provides SF2-based audio synthesis for Yamaha XG drum maps

#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>

#define LOG_TAG "FluidSynthBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

// FluidSynth forward declarations (linked from libfluidsynth.so)
typedef void* fluid_settings_t;
typedef void* fluid_synth_t;
typedef void* fluid_audio_driver_t;

// If FluidSynth is not available, these will be no-ops
#ifdef HAVE_FLUIDSYNTH
#include <fluidsynth.h>
#else
// Stub types for compilation without FluidSynth
typedef struct { int dummy; } fluid_settings_stub;
typedef struct { int dummy; } fluid_synth_stub;

struct SynthContext {
    fluid_settings_t* settings = nullptr;
    fluid_synth_t* synth = nullptr;
    fluid_audio_driver_t* driver = nullptr;
    int sfont_id = -1;
    bool valid = false;
};
#endif

// ─── Native: Create Synth ─────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeCreateSynth(
        JNIEnv* env, jobject /* this */) {
#ifdef HAVE_FLUIDSYNTH
    auto* ctx = new SynthContext();

    ctx->settings = new_fluid_settings();
    if (!ctx->settings) {
        LOGE("Failed to create FluidSynth settings");
        delete ctx;
        return 0L;
    }

    // Configure for low-latency Android audio
    fluid_settings_setstr(ctx->settings, "audio.driver", "oboe");
    fluid_settings_setint(ctx->settings, "audio.period-size", 64);
    fluid_settings_setint(ctx->settings, "audio.periods", 2);
    fluid_settings_setnum(ctx->settings, "synth.sample-rate", 44100.0);
    fluid_settings_setint(ctx->settings, "synth.polyphony", 64);
    fluid_settings_setint(ctx->settings, "synth.reverb.active", 1);
    fluid_settings_setint(ctx->settings, "synth.chorus.active", 0);

    ctx->synth = new_fluid_synth(ctx->settings);
    if (!ctx->synth) {
        LOGE("Failed to create FluidSynth synth");
        delete_fluid_settings(ctx->settings);
        delete ctx;
        return 0L;
    }

    ctx->driver = new_fluid_audio_driver(ctx->settings, ctx->synth);
    if (!ctx->driver) {
        LOGW("Audio driver init failed, trying OpenSLES fallback");
        fluid_settings_setstr(ctx->settings, "audio.driver", "opensles");
        ctx->driver = new_fluid_audio_driver(ctx->settings, ctx->synth);
    }

    ctx->valid = (ctx->driver != nullptr);
    LOGI("FluidSynth synth created, valid=%d", ctx->valid);

    return reinterpret_cast<jlong>(ctx);
#else
    LOGW("FluidSynth not compiled in, returning stub handle");
    return 1L; // Non-zero stub handle
#endif
}

// ─── Native: Load SoundFont ───────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeLoadSoundFont(
        JNIEnv* env, jobject /* this */, jlong handle, jstring path) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return -1;

    auto* ctx = reinterpret_cast<SynthContext*>(handle);
    const char* sf2Path = env->GetStringUTFChars(path, nullptr);

    int sfont_id = fluid_synth_sfload(ctx->synth, sf2Path, 1);
    env->ReleaseStringUTFChars(path, sf2Path);

    if (sfont_id == FLUID_FAILED) {
        LOGE("Failed to load SoundFont");
        return -1;
    }

    ctx->sfont_id = sfont_id;
    LOGI("SoundFont loaded: id=%d", sfont_id);

    // Configure percussion channel (Channel 10 = index 9)
    fluid_synth_set_channel_type(ctx->synth, 9, CHANNEL_TYPE_DRUM);
    fluid_synth_program_reset(ctx->synth);

    return sfont_id;
#else
    return 0; // Stub success
#endif
}

// ─── Native: Note On ─────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeNoteOn(
        JNIEnv* /* env */, jobject /* this */,
        jlong handle, jint channel, jint note, jint velocity) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<SynthContext*>(handle);
    if (!ctx->valid) return;
    fluid_synth_noteon(ctx->synth, channel, note, velocity);
#endif
}

// ─── Native: Note Off ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeNoteOff(
        JNIEnv* /* env */, jobject /* this */,
        jlong handle, jint channel, jint note) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<SynthContext*>(handle);
    if (!ctx->valid) return;
    fluid_synth_noteoff(ctx->synth, channel, note);
#endif
}

// ─── Native: Control Change ───────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeCC(
        JNIEnv* /* env */, jobject /* this */,
        jlong handle, jint channel, jint cc, jint value) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<SynthContext*>(handle);
    if (!ctx->valid) return;
    fluid_synth_cc(ctx->synth, channel, cc, value);
#endif
}

// ─── Native: All Notes Off (CC 123) ─────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeAllNotesOff(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<SynthContext*>(handle);
    if (!ctx->valid) return;

    // Send All Notes Off (CC 123) + All Sound Off (CC 120) to all channels
    for (int ch = 0; ch < 16; ch++) {
        fluid_synth_cc(ctx->synth, ch, 123, 0); // All Notes Off
        fluid_synth_cc(ctx->synth, ch, 120, 0); // All Sound Off
    }
    LOGI("All notes off sent");
#endif
}

// ─── Native: Destroy Synth ───────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_yamaha_sxstyleplayer_engine_MidiEngine_nativeDestroySynth(
        JNIEnv* /* env */, jobject /* this */, jlong handle) {
#ifdef HAVE_FLUIDSYNTH
    if (handle == 0L) return;
    auto* ctx = reinterpret_cast<SynthContext*>(handle);

    if (ctx->driver)   delete_fluid_audio_driver(ctx->driver);
    if (ctx->synth)    delete_fluid_synth(ctx->synth);
    if (ctx->settings) delete_fluid_settings(ctx->settings);

    delete ctx;
    LOGI("FluidSynth destroyed");
#endif
}

#ifdef __cplusplus
}
#endif
