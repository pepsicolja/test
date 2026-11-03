/* SPDX-License-Identifier: GPL-2.0-or-later
 * Output-only Android audio backend. Uses public AAudio; no /dev/snd access.
 */
#include "qemu/osdep.h"
#include "qemu-common.h"
#include "qemu/timer.h"
#include "audio.h"
#define AUDIO_CAP "vine-aaudio"
#include "audio_int.h"
#include <aaudio/AAudio.h>

typedef struct VineAudioOut {
    HWVoiceOut hw;
    AAudioStream *stream;
    int64_t last_tick;
    bool failed;
} VineAudioOut;

static int init_out(HWVoiceOut *hw, struct audsettings *requested, void *opaque)
{
    (void)opaque;
    VineAudioOut *voice = (VineAudioOut *)hw;
    AAudioStreamBuilder *builder = NULL;
    if (requested->nchannels < 1 || requested->nchannels > 2) return -1;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return -1;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, requested->nchannels);
    AAudioStreamBuilder_setSampleRate(builder, requested->freq);
    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &voice->stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        fprintf(stderr, "VineOS AAudio initialization failed: %s\n", AAudio_convertResultToText(result));
        return -1;
    }
    struct audsettings actual = *requested;
    actual.freq = AAudioStream_getSampleRate(voice->stream);
    actual.nchannels = AAudioStream_getChannelCount(voice->stream);
    actual.fmt = AUD_FMT_S16;
    actual.endianness = 0;
    if (actual.nchannels < 1 || actual.nchannels > 2 || actual.freq <= 0) {
        AAudioStream_close(voice->stream); voice->stream = NULL; return -1;
    }
    audio_pcm_init_info(&hw->info, &actual);
    hw->samples = 4096;
    voice->last_tick = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
    return 0;
}

static int run_out(HWVoiceOut *hw, int live)
{
    VineAudioOut *voice = (VineAudioOut *)hw;
    if (voice->failed) {
        int64_t now = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
        int64_t frames = (now - voice->last_tick) * hw->info.freq / 1000000000;
        voice->last_tick = now;
        int count = frames > live ? live : frames < 0 ? 0 : frames;
        hw->rpos = (hw->rpos + count) % hw->samples;
        return count;
    }
    int consumed = 0;
    while (live > 0) {
        int count = audio_MIN(live, audio_MIN(1024, hw->samples - hw->rpos));
        int16_t pcm[2048];
        hw->clip(pcm, hw->mix_buf + hw->rpos, count);
        aaudio_result_t n = AAudioStream_write(voice->stream, pcm, count, 0);
        if (n < 0) {
            fprintf(stderr, "VineOS AAudio output failed: %s\n", AAudio_convertResultToText(n));
            voice->failed = true;
            voice->last_tick = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
            break;
        }
        if (n > count) n = count;
        hw->rpos = (hw->rpos + n) % hw->samples;
        consumed += n;
        live -= n;
        if (n < count) break;
    }
    return consumed;
}

static int write_out(SWVoiceOut *sw, void *buf, int len) { return audio_pcm_sw_write(sw, buf, len); }
static void fini_out(HWVoiceOut *hw)
{
    VineAudioOut *voice = (VineAudioOut *)hw;
    if (voice->stream) { AAudioStream_close(voice->stream); voice->stream = NULL; }
}
static int ctl_out(HWVoiceOut *hw, int command, ...)
{
    VineAudioOut *voice = (VineAudioOut *)hw;
    if (!voice->stream || voice->failed) return 0;
    if (command == VOICE_ENABLE) {
        voice->last_tick = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL);
        return AAudioStream_requestStart(voice->stream) == AAUDIO_OK ? 0 : -1;
    }
    if (command == VOICE_DISABLE) AAudioStream_requestPause(voice->stream);
    return 0;
}
static void *init_audio(void) { static int token; return &token; }
static void fini_audio(void *opaque) { (void)opaque; }
static struct audio_pcm_ops pcm_ops = {
    .init_out = init_out, .fini_out = fini_out, .run_out = run_out,
    .write = write_out, .ctl_out = ctl_out,
};
static struct audio_driver driver = {
    .name = "aaudio", .descr = "VineOS Android AAudio output", .options = NULL,
    .init = init_audio, .fini = fini_audio, .pcm_ops = &pcm_ops,
    .can_be_default = 1, .max_voices_out = 8, .max_voices_in = 0,
    .voice_size_out = sizeof(VineAudioOut), .voice_size_in = sizeof(HWVoiceIn),
};
static void register_driver(void) { audio_driver_register(&driver); }
type_init(register_driver);
