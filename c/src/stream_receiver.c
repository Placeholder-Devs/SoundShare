#define _POSIX_C_SOURCE 200809L
#include "stream_receiver.h"
#include "soundshare_time.h"

#include <pulse/simple.h>
#include <pulse/error.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <math.h>

static volatile bool g_stream_active = false;
static pthread_t g_stream_thread;
static int g_stream_socket = -1;

typedef struct {
    char host_ip[64];
    int port;
    stream_stats_cb on_stats;
    void (*on_state_change)(bool is_connected, const char *msg);
} stream_worker_arg_t;

static void *stream_worker(void *arg) {
    stream_worker_arg_t *params = (stream_worker_arg_t *)arg;
    char host_ip[64];
    strncpy(host_ip, params->host_ip, sizeof(host_ip) - 1);
    host_ip[sizeof(host_ip) - 1] = '\0';
    int port = params->port;
    stream_stats_cb on_stats = params->on_stats;
    void (*on_state_change)(bool is_connected, const char *msg) = params->on_state_change;
    free(params);

    if (on_state_change) on_state_change(false, "Connecting to audio stream...");

    int sock = net_connect_tcp(host_ip, port, 4);
    if (sock < 0) {
        if (on_state_change) on_state_change(false, "Failed to connect to audio port");
        g_stream_active = false;
        return NULL;
    }
    g_stream_socket = sock;
    net_set_tcp_nodelay(sock, true);

    /* Read 28-byte SoundShare v2 Protocol Header */
    uint32_t magic = 0;
    if (recv_u32(sock, &magic) != 0 || magic != SOUNDSHARE_HEADER_MAGIC) {
        fprintf(stderr, "[STREAM] Invalid header magic: 0x%08X (expected 0x%08X)\n", magic, SOUNDSHARE_HEADER_MAGIC);
        if (on_state_change) on_state_change(false, "Invalid stream header");
        close(sock);
        g_stream_socket = -1;
        g_stream_active = false;
        return NULL;
    }

    uint32_t version = 0, sample_rate = 0, frames_per_buffer = 0, chunk_size = 0;
    uint16_t bits_per_sample = 0, channels = 0, compression_type = 0;
    uint8_t is_float = 0, reserved = 0;

    if (recv_u32(sock, &version) != 0 ||
        recv_u32(sock, &sample_rate) != 0 ||
        recv_u16(sock, &bits_per_sample) != 0 ||
        recv_u16(sock, &channels) != 0 ||
        recv_u32(sock, &frames_per_buffer) != 0 ||
        recv_u32(sock, &chunk_size) != 0 ||
        recv_u16(sock, &compression_type) != 0 ||
        recv_u8(sock, &is_float) != 0 ||
        recv_u8(sock, &reserved) != 0) {
        if (on_state_change) on_state_change(false, "Incomplete header");
        close(sock);
        g_stream_socket = -1;
        g_stream_active = false;
        return NULL;
    }

    soundshare_audio_config_t cfg;
    cfg.sample_rate = (int)sample_rate;
    cfg.bits_per_sample = (int)bits_per_sample;
    cfg.channels = (int)channels;
    cfg.frames_per_buffer = (int)frames_per_buffer;
    cfg.chunk_size = (int)chunk_size;
    cfg.compression_type = (int)compression_type;
    cfg.is_float = (int)is_float;

    printf("[STREAM] Header: %d Hz | %d-bit | %d ch | chunk=%d | comp=%s | float=%d\n",
           cfg.sample_rate, cfg.bits_per_sample, cfg.channels, cfg.chunk_size,
           cfg.compression_type == 1 ? "FLAC" : "PCM", cfg.is_float);

    /* Initialize PulseAudio simple playback */
    pa_sample_spec ss;
    ss.rate = (uint32_t)cfg.sample_rate;
    ss.channels = (uint8_t)cfg.channels;

    if (cfg.is_float) {
        ss.format = PA_SAMPLE_FLOAT32LE;
    } else if (cfg.bits_per_sample == 24) {
        ss.format = PA_SAMPLE_S24LE;
    } else if (cfg.bits_per_sample == 32) {
        ss.format = PA_SAMPLE_S32LE;
    } else {
        ss.format = PA_SAMPLE_S16LE;
    }

    int pa_err = 0;
    pa_simple *pa = pa_simple_new(NULL, "SoundShare", PA_STREAM_PLAYBACK, NULL,
                                  "SoundShare Live Audio", &ss, NULL, NULL, &pa_err);
    if (!pa) {
        fprintf(stderr, "[STREAM] PulseAudio init error: %s\n", pa_strerror(pa_err));
        if (on_state_change) on_state_change(false, "Audio device open error");
        close(sock);
        g_stream_socket = -1;
        g_stream_active = false;
        return NULL;
    }

    char status_msg[128];
    snprintf(status_msg, sizeof(status_msg), "Streaming: %d Hz %d-ch %s",
             cfg.sample_rate, cfg.channels, cfg.compression_type == 1 ? "FLAC" : "PCM");
    if (on_state_change) on_state_change(true, status_msg);

    size_t chunk = (cfg.chunk_size > 0 && cfg.chunk_size <= 65536) ? (size_t)cfg.chunk_size : 960;
    uint8_t *buffer = (uint8_t *)malloc(chunk * 2);
    if (!buffer) {
        pa_simple_free(pa);
        close(sock);
        g_stream_active = false;
        return NULL;
    }

    int64_t total_bytes = 0;
    int64_t bytes_this_period = 0;
    int64_t last_stats_time = time_now_ms();

    while (g_stream_active) {
        if (cfg.compression_type == 0) {
            /* Raw PCM Audio */
            ssize_t rd = net_read_exact(sock, buffer, chunk);
            if (rd != (ssize_t)chunk) {
                printf("[STREAM] Connection closed or read error\n");
                break;
            }

            /* Calculate audio peak level for live VU meter */
            double peak = 0.0;
            if (cfg.bits_per_sample == 16) {
                int16_t *samples = (int16_t *)buffer;
                size_t num_samples = chunk / sizeof(int16_t);
                for (size_t i = 0; i < num_samples; i++) {
                    int val = abs(samples[i]);
                    if (val > (int)(peak * 32767.0)) {
                        peak = (double)val / 32767.0;
                    }
                }
            } else {
                peak = 0.5; /* Nominal activity */
            }

            /* Write directly to PulseAudio speakers */
            if (pa_simple_write(pa, buffer, chunk, &pa_err) < 0) {
                fprintf(stderr, "[STREAM] pa_simple_write failed: %s\n", pa_strerror(pa_err));
                break;
            }

            total_bytes += chunk;
            bytes_this_period += chunk;

            int64_t now = time_now_ms();
            int64_t diff = now - last_stats_time;
            if (diff >= 500) {
                int64_t kbps = (bytes_this_period * 8LL) / diff;
                if (on_stats) {
                    on_stats(&cfg, kbps, total_bytes, peak);
                }
                last_stats_time = now;
                bytes_this_period = 0;
            }
        } else {
            /* FLAC frame packet */
            uint32_t frame_len = 0;
            if (recv_u32(sock, &frame_len) != 0 || frame_len == 0 || frame_len > 131072) break;

            if (buffer) free(buffer);
            buffer = (uint8_t *)malloc(frame_len);
            if (!buffer || net_read_exact(sock, buffer, frame_len) != (ssize_t)frame_len) break;

            total_bytes += frame_len;
            bytes_this_period += frame_len;

            int64_t now = time_now_ms();
            int64_t diff = now - last_stats_time;
            if (diff >= 500) {
                int64_t kbps = (bytes_this_period * 8LL) / diff;
                if (on_stats) {
                    on_stats(&cfg, kbps, total_bytes, 0.6);
                }
                last_stats_time = now;
                bytes_this_period = 0;
            }
        }
    }

    free(buffer);
    pa_simple_drain(pa, &pa_err);
    pa_simple_free(pa);
    close(sock);
    g_stream_socket = -1;
    g_stream_active = false;

    if (on_state_change) on_state_change(false, "Audio stream stopped");
    return NULL;
}

int stream_receiver_start(const char *host_ip, int port,
                          stream_stats_cb on_stats,
                          void (*on_state_change)(bool is_connected, const char *msg)) {
    if (g_stream_active) return 0;
    g_stream_active = true;

    stream_worker_arg_t *args = (stream_worker_arg_t *)malloc(sizeof(stream_worker_arg_t));
    if (!args) return -1;

    strncpy(args->host_ip, host_ip, sizeof(args->host_ip) - 1);
    args->host_ip[sizeof(args->host_ip) - 1] = '\0';
    args->port = port > 0 ? port : SOUNDSHARE_AUDIO_PORT;
    args->on_stats = on_stats;
    args->on_state_change = on_state_change;

    if (pthread_create(&g_stream_thread, NULL, stream_worker, args) != 0) {
        free(args);
        g_stream_active = false;
        return -1;
    }
    pthread_detach(g_stream_thread);
    return 0;
}

void stream_receiver_stop(void) {
    g_stream_active = false;
    if (g_stream_socket >= 0) {
        close(g_stream_socket);
        g_stream_socket = -1;
    }
}

bool stream_receiver_is_active(void) {
    return g_stream_active;
}
