#define _POSIX_C_SOURCE 200809L
#include "audio_player.h"
#include "soundshare_time.h"

#include <gst/gst.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

static GstElement *g_pipeline = NULL;
static pthread_mutex_t g_player_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile bool g_is_playing = false;
static double g_current_rate = 1.0;

/* Scheduled play thread state */
static pthread_t g_schedule_thread;
static volatile bool g_schedule_active = false;
static volatile int64_t g_target_play_at = 0;
static volatile int64_t g_target_pos_ms = 0;

int audio_player_init(void) {
    static bool initialized = false;
    if (initialized) return 0;

    gst_init(NULL, NULL);
    initialized = true;
    return 0;
}

int audio_player_load(const char *filepath) {
    if (!filepath || filepath[0] == '\0') return -1;
    pthread_mutex_lock(&g_player_lock);
    audio_player_init();

    if (g_pipeline) {
        gst_element_set_state(g_pipeline, GST_STATE_NULL);
        gst_object_unref(g_pipeline);
        g_pipeline = NULL;
    }

    g_pipeline = gst_element_factory_make("playbin", "soundshare_player");
    if (!g_pipeline) {
        fprintf(stderr, "[PLAYER] Failed to create GStreamer playbin element\n");
        pthread_mutex_unlock(&g_player_lock);
        return -1;
    }

    char *uri = NULL;
    char abs_path[4096];
    if (realpath(filepath, abs_path)) {
        uri = g_filename_to_uri(abs_path, NULL, NULL);
    } else {
        if (filepath[0] == '/') {
            uri = g_filename_to_uri(filepath, NULL, NULL);
        } else {
            char cwd[4096];
            if (getcwd(cwd, sizeof(cwd))) {
                char full[8192];
                snprintf(full, sizeof(full), "%s/%s", cwd, filepath);
                uri = g_filename_to_uri(full, NULL, NULL);
            }
        }
    }
    if (!uri) {
        char tmp_uri[8192];
        snprintf(tmp_uri, sizeof(tmp_uri), "file://%s", filepath[0] == '/' ? filepath : abs_path);
        uri = g_strdup(tmp_uri);
    }

    g_object_set(G_OBJECT(g_pipeline), "uri", uri, NULL);
    printf("[PLAYER] Loading audio: %s (URI: %s)\n", filepath, uri);
    g_free(uri);

    /* Preroll to PAUSED state so audio decoders and buffers are primed */
    GstStateChangeReturn ret = gst_element_set_state(g_pipeline, GST_STATE_PAUSED);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        fprintf(stderr, "[PLAYER] Preroll failed for: %s\n", filepath);
        gst_element_set_state(g_pipeline, GST_STATE_NULL);
        gst_object_unref(g_pipeline);
        g_pipeline = NULL;
        pthread_mutex_unlock(&g_player_lock);
        return -1;
    }

    g_is_playing = false;
    g_current_rate = 1.0;

    pthread_mutex_unlock(&g_player_lock);
    return 0;
}

static void *schedule_play_worker(void *arg) {
    (void)arg;
    int64_t play_at = g_target_play_at;
    int64_t pos_ms = g_target_pos_ms;

    int64_t now = time_now_ms();
    int64_t delay_ms = play_at - now;

    if (delay_ms > 0) {
        time_sleep_ms((int)delay_ms);
    }

    pthread_mutex_lock(&g_player_lock);
    if (g_schedule_active && g_pipeline) {
        now = time_now_ms();
        int64_t compensated_pos = pos_ms;
        if (now > play_at) {
            compensated_pos += (now - play_at);
        }

        if (compensated_pos > 0) {
            gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                                   GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                                   (gint64)compensated_pos * GST_MSECOND);
        }

        gst_element_set_state(g_pipeline, GST_STATE_PLAYING);
        g_is_playing = true;
    }
    g_schedule_active = false;
    pthread_mutex_unlock(&g_player_lock);
    return NULL;
}

void audio_player_schedule_play(int64_t pos_ms, int64_t play_at_ms) {
    pthread_mutex_lock(&g_player_lock);
    if (!g_pipeline) {
        pthread_mutex_unlock(&g_player_lock);
        return;
    }

    g_schedule_active = false;
    int64_t now = time_now_ms();

    if (now >= play_at_ms) {
        /* Play timestamp is right now or slightly in the past: start immediately with compensation */
        int64_t elapsed = now - play_at_ms;
        int64_t compensated_pos = pos_ms + elapsed;

        gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                               GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                               (gint64)compensated_pos * GST_MSECOND);
        gst_element_set_state(g_pipeline, GST_STATE_PLAYING);
        g_is_playing = true;
        pthread_mutex_unlock(&g_player_lock);
    } else {
        /* Seek to initial pos in paused state, then schedule exact trigger */
        gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                               GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                               (gint64)pos_ms * GST_MSECOND);
        gst_element_set_state(g_pipeline, GST_STATE_PAUSED);

        g_target_play_at = play_at_ms;
        g_target_pos_ms = pos_ms;
        g_schedule_active = true;
        pthread_mutex_unlock(&g_player_lock);

        pthread_create(&g_schedule_thread, NULL, schedule_play_worker, NULL);
        pthread_detach(g_schedule_thread);
    }
}

void audio_player_schedule_pause(int64_t pos_ms, int64_t pause_at_ms) {
    pthread_mutex_lock(&g_player_lock);
    if (!g_pipeline) {
        pthread_mutex_unlock(&g_player_lock);
        return;
    }

    g_schedule_active = false;
    int64_t now = time_now_ms();

    if (now >= pause_at_ms) {
        gst_element_set_state(g_pipeline, GST_STATE_PAUSED);
        if (pos_ms >= 0) {
            gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                                   GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                                   (gint64)pos_ms * GST_MSECOND);
        }
        g_is_playing = false;
        pthread_mutex_unlock(&g_player_lock);
    } else {
        pthread_mutex_unlock(&g_player_lock);
        int64_t delay = pause_at_ms - now;
        time_sleep_ms((int)delay);
        pthread_mutex_lock(&g_player_lock);
        if (g_pipeline) {
            gst_element_set_state(g_pipeline, GST_STATE_PAUSED);
            if (pos_ms >= 0) {
                gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                                       GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                                       (gint64)pos_ms * GST_MSECOND);
            }
            g_is_playing = false;
        }
        pthread_mutex_unlock(&g_player_lock);
    }
}

void audio_player_pause(void) {
    pthread_mutex_lock(&g_player_lock);
    if (g_pipeline) {
        g_schedule_active = false;
        gst_element_set_state(g_pipeline, GST_STATE_PAUSED);
        g_is_playing = false;
    }
    pthread_mutex_unlock(&g_player_lock);
}

void audio_player_play(void) {
    pthread_mutex_lock(&g_player_lock);
    if (g_pipeline) {
        gst_element_set_state(g_pipeline, GST_STATE_PLAYING);
        g_is_playing = true;
    }
    pthread_mutex_unlock(&g_player_lock);
}

void audio_player_seek(int64_t pos_ms) {
    pthread_mutex_lock(&g_player_lock);
    if (g_pipeline && pos_ms >= 0) {
        gst_element_seek_simple(g_pipeline, GST_FORMAT_TIME,
                               GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                               (gint64)pos_ms * GST_MSECOND);
    }
    pthread_mutex_unlock(&g_player_lock);
}

void audio_player_adjust_speed(double rate) {
    pthread_mutex_lock(&g_player_lock);
    if (g_pipeline && g_is_playing && rate > 0.5 && rate < 2.0) {
        if (rate != g_current_rate) {
            gint64 cur_pos = 0;
            gst_element_query_position(g_pipeline, GST_FORMAT_TIME, &cur_pos);
            gst_element_seek(g_pipeline, rate, GST_FORMAT_TIME,
                             GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
                             GST_SEEK_TYPE_SET, cur_pos,
                             GST_SEEK_TYPE_NONE, GST_CLOCK_TIME_NONE);
            g_current_rate = rate;
        }
    }
    pthread_mutex_unlock(&g_player_lock);
}

int64_t audio_player_get_position_ms(void) {
    pthread_mutex_lock(&g_player_lock);
    int64_t pos_ms = 0;
    if (g_pipeline) {
        gint64 pos_ns = 0;
        if (gst_element_query_position(g_pipeline, GST_FORMAT_TIME, &pos_ns)) {
            pos_ms = (int64_t)(pos_ns / GST_MSECOND);
        }
    }
    pthread_mutex_unlock(&g_player_lock);
    return pos_ms;
}

int64_t audio_player_get_duration_ms(void) {
    pthread_mutex_lock(&g_player_lock);
    int64_t dur_ms = 0;
    if (g_pipeline) {
        gint64 dur_ns = 0;
        if (gst_element_query_duration(g_pipeline, GST_FORMAT_TIME, &dur_ns)) {
            dur_ms = (int64_t)(dur_ns / GST_MSECOND);
        }
    }
    pthread_mutex_unlock(&g_player_lock);
    return dur_ms;
}

bool audio_player_is_loaded(void) {
    pthread_mutex_lock(&g_player_lock);
    bool loaded = (g_pipeline != NULL);
    pthread_mutex_unlock(&g_player_lock);
    return loaded;
}

bool audio_player_is_playing(void) {
    pthread_mutex_lock(&g_player_lock);
    if (!g_pipeline) {
        pthread_mutex_unlock(&g_player_lock);
        return false;
    }
    GstState current = GST_STATE_NULL;
    GstState pending = GST_STATE_NULL;
    gst_element_get_state(g_pipeline, &current, &pending, 0);
    bool playing = (current == GST_STATE_PLAYING || pending == GST_STATE_PLAYING);
    g_is_playing = playing;
    pthread_mutex_unlock(&g_player_lock);
    return playing;
}

void audio_player_stop(void) {
    pthread_mutex_lock(&g_player_lock);
    g_schedule_active = false;
    if (g_pipeline) {
        gst_element_set_state(g_pipeline, GST_STATE_NULL);
        gst_object_unref(g_pipeline);
        g_pipeline = NULL;
    }
    g_is_playing = false;
    g_current_rate = 1.0;
    pthread_mutex_unlock(&g_player_lock);
}
