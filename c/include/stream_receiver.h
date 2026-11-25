#ifndef STREAM_RECEIVER_H
#define STREAM_RECEIVER_H

#include <stdint.h>
#include <stdbool.h>

#define SOUNDSHARE_AUDIO_PORT 5000
#define SOUNDSHARE_HEADER_MAGIC 0x53534844 /* "SSHD" */

typedef struct {
    int sample_rate;
    int bits_per_sample;
    int channels;
    int frames_per_buffer;
    int chunk_size;
    int compression_type; /* 0 = PCM, 1 = FLAC */
    int is_float;
} soundshare_audio_config_t;

typedef void (*stream_stats_cb)(const soundshare_audio_config_t *cfg,
                                int64_t kbps, int64_t total_bytes, double peak_level);

/* Start live audio receiver from Android / Linux SoundShare host */
int stream_receiver_start(const char *host_ip, int port,
                          stream_stats_cb on_stats,
                          void (*on_state_change)(bool is_connected, const char *msg));

/* Stop streaming receiver */
void stream_receiver_stop(void);

/* Check if currently receiving stream */
bool stream_receiver_is_active(void);

#endif /* STREAM_RECEIVER_H */
