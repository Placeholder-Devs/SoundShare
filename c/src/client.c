#define _POSIX_C_SOURCE 200809L
#include "soundshare_time.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <math.h>

static volatile bool g_client_running = true;
static int64_t g_calibrated_offset_ms = 0;
static bool g_offset_ready = false;

/* Song Queue for Client */
static song_item_t g_client_queue[MAX_SONG_QUEUE];
static int g_client_queue_count = 0;
static int g_current_playing_index = -1;
static pthread_mutex_t g_queue_lock = PTHREAD_MUTEX_INITIALIZER;

/* Synced Start Time & Timeline Tracking */
static volatile int64_t g_synced_start_time_host = 0;
static volatile int64_t g_synced_start_time_local = 0;
static volatile bool g_synced_timeline_active = false;
static volatile bool g_sync_time_established = false;
static volatile bool g_sync_time_locked = true;

static void apply_synced_start_time(int64_t host_start_time, bool verbose) {
    g_synced_start_time_host = host_start_time;
    g_synced_start_time_local = host_start_time - g_calibrated_offset_ms;
    g_synced_timeline_active = true;
    g_sync_time_established = true;

    int64_t now = time_now_ms();
    int64_t timeline_pos = now - g_synced_start_time_local;

    printf("[TIMELINE] ✓ Established Sync Time: Host T₀ = %ld ms | Local T₀ = %ld ms [🔒 LOCKED]\n",
           (long)host_start_time, (long)g_synced_start_time_local);

    if (audio_player_is_loaded()) {
        if (timeline_pos < 0) {
            printf("[TIMELINE] Track scheduled in future: starts in %ld ms (at %+ld ms local time)\n",
                   (long)(-timeline_pos), (long)g_synced_start_time_local);
            audio_player_schedule_play(0, g_synced_start_time_local);
        } else {
            if (!audio_player_is_playing()) {
                printf("[TIMELINE] Calculated Playing Timeline: %ld ms (%02ld:%02ld.%03ld) -> Playing in sync!\n",
                       (long)timeline_pos, (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));
                audio_player_schedule_play(timeline_pos, now);
            } else {
                int64_t local_pos = audio_player_get_position_ms();
                int64_t signed_drift = timeline_pos - local_pos;
                int64_t abs_drift = llabs(signed_drift);
                if (abs_drift > 400) {
                    audio_player_seek(timeline_pos);
                    audio_player_adjust_speed(1.0);
                    printf("[TIMELINE] Hard sync seek to %ld ms (drift was %+ld ms)\n", (long)timeline_pos, (long)signed_drift);
                } else if (abs_drift > 25) {
                    audio_player_adjust_speed(signed_drift > 0 ? 1.02 : 0.98);
                } else {
                    audio_player_adjust_speed(1.0);
                }
                if (verbose) {
                    printf("[TIMELINE] Expected: %02ld:%02ld.%03ld | Player: %02ld:%02ld.%03ld | Drift: %+ld ms\n",
                           (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000),
                           (long)(local_pos/60000), (long)((local_pos%60000)/1000), (long)(local_pos%1000),
                           (long)signed_drift);
                }
            }
        }
    } else {
        printf("[TIMELINE] Calculated Playing Timeline: %ld ms (%02ld:%02ld.%03ld) [No song file loaded]\n",
               (long)timeline_pos, (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));
    }
}

/* Comparison function for qsort */
static int compare_int64(const void *a, const void *b) {
    int64_t arg1 = *(const int64_t *)a;
    int64_t arg2 = *(const int64_t *)b;
    if (arg1 < arg2) return -1;
    if (arg1 > arg2) return 1;
    return 0;
}

/* Run high-precision NTP clock offset calibration matching SoundShare */
static int calibrate_clock(int sync_fd, int samples_count, ntp_sync_result_t *res, bool verbose) {
    if (samples_count <= 0) samples_count = DEFAULT_NTP_SAMPLES;

    int64_t *offsets = (int64_t *)malloc(sizeof(int64_t) * (size_t)samples_count);
    int64_t *rtts = (int64_t *)malloc(sizeof(int64_t) * (size_t)samples_count);
    if (!offsets || !rtts) {
        free(offsets);
        free(rtts);
        return -1;
    }

    printf("[SYNC] Calibrating clock with %d NTP samples...\n", samples_count);

    int valid_samples = 0;
    int64_t total_rtt = 0;
    int64_t min_rtt = 999999999;
    int64_t max_rtt = -1;

    for (int i = 0; i < samples_count; i++) {
        int64_t t1 = time_now_ms();

        if (send_u8(sync_fd, SYNC_CLOCK_REQ) != 0 ||
            send_u64(sync_fd, (uint64_t)t1) != 0) {
            fprintf(stderr, "[SYNC] Failed to send SYNC_CLOCK_REQ (sample %d)\n", i);
            break;
        }

        uint8_t rsp;
        uint64_t orig_t1, t2_u64, t3_u64;
        if (recv_u8(sync_fd, &rsp) != 0 || rsp != SYNC_CLOCK_RSP ||
            recv_u64(sync_fd, &orig_t1) != 0 ||
            recv_u64(sync_fd, &t2_u64) != 0 ||
            recv_u64(sync_fd, &t3_u64) != 0) {
            fprintf(stderr, "[SYNC] Failed to receive SYNC_CLOCK_RSP (sample %d)\n", i);
            break;
        }

        int64_t t4 = time_now_ms();
        int64_t t2 = (int64_t)t2_u64;
        int64_t t3 = (int64_t)t3_u64;

        /* NTP formula: offset = ((t2 - t1) + (t3 - t4)) / 2 */
        int64_t offset = ((t2 - t1) + (t3 - t4)) / 2;
        /* Round trip delay: delay = (t4 - t1) - (t3 - t2) */
        int64_t rtt = (t4 - t1) - (t3 - t2);
        if (rtt < 0) rtt = t4 - t1;

        offsets[valid_samples] = offset;
        rtts[valid_samples] = rtt;
        valid_samples++;

        total_rtt += rtt;
        if (rtt < min_rtt) min_rtt = rtt;
        if (rtt > max_rtt) max_rtt = rtt;

        if (verbose) {
            printf("  Sample #%d: offset=%+ld ms, delay=%ld ms (t1=%ld, t2=%ld, t3=%ld, t4=%ld)\n",
                   i + 1, (long)offset, (long)rtt, (long)t1, (long)t2, (long)t3, (long)t4);
        }

        time_sleep_ms(25); /* 25ms delay matching SoundShare Android client */
    }

    if (valid_samples == 0) {
        free(offsets);
        free(rtts);
        return -1;
    }

    /* Outlier rejection: sort samples and pick median */
    qsort(offsets, (size_t)valid_samples, sizeof(int64_t), compare_int64);

    int64_t median_offset;
    if (valid_samples >= 4) {
        /* Discard outer quartiles, average middle elements (same as SoundShare Java) */
        int m1 = valid_samples / 2 - 1;
        int m2 = valid_samples / 2;
        median_offset = (offsets[m1] + offsets[m2]) / 2;
    } else {
        median_offset = offsets[valid_samples / 2];
    }

    res->clock_offset_ms = median_offset;
    res->min_rtt_ms = min_rtt;
    res->max_rtt_ms = max_rtt;
    res->avg_rtt_ms = total_rtt / valid_samples;
    res->sample_count = valid_samples;

    free(offsets);
    free(rtts);
    return 0;
}

/* Worker thread for Port 5001 (Ping / Latency measurement) */
static void *ping_client_thread(void *arg) {
    const client_config_t *cfg = (const client_config_t *)arg;
    int interval_ms = cfg->ping_interval_ms > 0 ? cfg->ping_interval_ms : 500;

    int ping_fd = net_connect_tcp(cfg->server_ip, cfg->ping_port, 3);
    if (ping_fd < 0) {
        return NULL;
    }
    net_set_recv_timeout(ping_fd, 2);

    int64_t smoothed_ms = -1;

    while (g_client_running) {
        int64_t start_ns = time_now_ns();
        if (send_u8(ping_fd, PING_REQUEST) != 0) break;

        uint8_t resp = 0;
        if (recv_u8(ping_fd, &resp) != 0 || resp != PING_RESPONSE) break;

        int64_t end_ns = time_now_ns();
        int64_t rtt_ms = (end_ns - start_ns) / 1000000LL;
        int64_t one_way_ms = rtt_ms / 2;

        /* Exponential Moving Average (70% prev + 30% new) */
        smoothed_ms = (smoothed_ms < 0) ? one_way_ms
                                        : (int64_t)(smoothed_ms * 0.7 + (double)one_way_ms * 0.3);

        send_u8(ping_fd, LATENCY_REPORT);
        send_u64(ping_fd, (uint64_t)smoothed_ms);

        time_sleep_ms(interval_ms);
    }

    close(ping_fd);
    return NULL;
}

typedef struct {
    char server_ip[64];
    int port;
    char id[128];
    char name[256];
    int64_t size;
    int sync_fd;
} dl_worker_arg_t;

static void *client_download_worker(void *arg) {
    dl_worker_arg_t *d = (dl_worker_arg_t *)arg;
    printf("[DOWNLOAD] Receiving track '%s' (ID: %s, size: %ld bytes) from %s:%d...\n",
           d->name, d->id, (long)d->size, d->server_ip, d->port);

    char local_file[2048] = "";
    int rc = song_transfer_receive(d->server_ip, d->port, d->id, d->name, d->size, NULL, NULL);

    if (rc == 0) {
        snprintf(local_file, sizeof(local_file), "received_songs/%s_%s", d->id, d->name);
        printf("[DOWNLOAD] ✓ Download completed: %s\n", local_file);

        pthread_mutex_lock(&g_queue_lock);
        for (int i = 0; i < g_client_queue_count; i++) {
            if (strcmp(g_client_queue[i].id, d->id) == 0) {
                snprintf(g_client_queue[i].local_path, sizeof(g_client_queue[i].local_path), "%s", local_file);
                g_client_queue[i].is_ready = true;

                if (g_current_playing_index == i && !audio_player_is_playing()) {
                    printf("[SYNC_PLAY] Loading and starting pending track: %s\n", g_client_queue[i].file_name);
                    audio_player_load(local_file);
                    audio_player_play();
                }
                break;
            }
        }
        pthread_mutex_unlock(&g_queue_lock);

        /* Notify host FILE_READY */
        if (d->sync_fd >= 0) {
            send_u8(d->sync_fd, FILE_READY);
            send_string(d->sync_fd, d->id);
        }
    } else {
        fprintf(stderr, "[DOWNLOAD] ✗ Download failed for track: %s\n", d->name);
    }

    free(d);
    return NULL;
}

int run_client(const client_config_t *cfg) {
    g_client_running = true;
    g_offset_ready = false;
    g_sync_time_established = false;
    g_sync_time_locked = true;
    audio_player_init();

    if (cfg->audio_file && cfg->audio_file[0] != '\0') {
        printf("[PLAYER] Loading specified audio file: %s\n", cfg->audio_file);
        if (audio_player_load(cfg->audio_file) == 0) {
            printf("[PLAYER] ✓ Audio file loaded and ready for timeline sync!\n");
        } else {
            fprintf(stderr, "[PLAYER] ✗ Failed to load audio file: %s\n", cfg->audio_file);
        }
    }

    printf("=================================================================\n");
    printf(" SoundShare Time Client connecting to %s\n", cfg->server_ip);
    printf(" Sync Control Port: %d\n", cfg->sync_port);
    printf(" Ping Port        : %d\n", cfg->ping_port);
    printf("=================================================================\n");

    /* 1. Connect to Sync Control Port 5006 */
    int sync_fd = net_connect_tcp(cfg->server_ip, cfg->sync_port, 5);
    if (sync_fd < 0) {
        fprintf(stderr, "[SYNC] Connection to %s:%d failed: %s\n",
                cfg->server_ip, cfg->sync_port, strerror(errno));
        return 1;
    }
    printf("[SYNC] Connected to sync host at %s:%d\n", cfg->server_ip, cfg->sync_port);

    /* 2. Run NTP calibration handshake */
    ntp_sync_result_t sync_res;
    if (calibrate_clock(sync_fd, cfg->ntp_samples, &sync_res, cfg->verbose) != 0) {
        fprintf(stderr, "[SYNC] Clock calibration failed!\n");
        close(sync_fd);
        return 1;
    }

    g_calibrated_offset_ms = sync_res.clock_offset_ms;
    g_offset_ready = true;

    printf("-----------------------------------------------------------------\n");
    printf(" ✓ CALIBRATION COMPLETE (%d samples)\n", sync_res.sample_count);
    printf("   Clock Offset : %+ld ms (Server time = Local time %+ld ms)\n",
           (long)g_calibrated_offset_ms, (long)g_calibrated_offset_ms);
    printf("   Network RTT  : Min %ld ms | Avg %ld ms | Max %ld ms\n",
           (long)sync_res.min_rtt_ms, (long)sync_res.avg_rtt_ms, (long)sync_res.max_rtt_ms);
    printf("-----------------------------------------------------------------\n");

    /* Send SYNC_INIT_REQ notifying host that client is calibrated */
    send_u8(sync_fd, SYNC_INIT_REQ);

    /* 3. Launch concurrent Ping thread on Port 5001 */
    pthread_t ping_th;
    bool ping_started = false;
    if (pthread_create(&ping_th, NULL, ping_client_thread, (void *)cfg) == 0) {
        ping_started = true;
    }

    /* 4. Listen for continuous sync and song playback packets */
    printf("[TIME_SYNC] Waiting for song transfers & synchronized playback commands (Ctrl+C to stop)...\n");

    while (g_client_running) {
        uint8_t cmd;
        if (recv_u8(sync_fd, &cmd) != 0) {
            printf("[SYNC] Host closed connection\n");
            break;
        }

        switch (cmd) {
            case SYNC_QUEUE_ADD: {
                char q_id[128], q_name[256], q_artist[128], q_album[128], q_mime[64], q_dur_str[32], q_fmt[64];
                uint64_t q_size;
                uint32_t q_dur_ms;

                if (recv_string(sync_fd, q_id, sizeof(q_id)) != 0 ||
                    recv_string(sync_fd, q_name, sizeof(q_name)) != 0 ||
                    recv_string(sync_fd, q_artist, sizeof(q_artist)) != 0 ||
                    recv_string(sync_fd, q_album, sizeof(q_album)) != 0 ||
                    recv_u64(sync_fd, &q_size) != 0 ||
                    recv_string(sync_fd, q_mime, sizeof(q_mime)) != 0 ||
                    recv_u32(sync_fd, &q_dur_ms) != 0 ||
                    recv_string(sync_fd, q_dur_str, sizeof(q_dur_str)) != 0 ||
                    recv_string(sync_fd, q_fmt, sizeof(q_fmt)) != 0) {
                    goto exit_loop;
                }

                pthread_mutex_lock(&g_queue_lock);
                bool exists = false;
                for (int i = 0; i < g_client_queue_count; i++) {
                    if (strcmp(g_client_queue[i].id, q_id) == 0) {
                        exists = true;
                        break;
                    }
                }
                if (!exists && g_client_queue_count < MAX_SONG_QUEUE) {
                    song_item_t *item = &g_client_queue[g_client_queue_count++];
                    snprintf(item->id, sizeof(item->id), "%s", q_id);
                    snprintf(item->file_name, sizeof(item->file_name), "%s", q_name);
                    snprintf(item->artist, sizeof(item->artist), "%s", q_artist);
                    snprintf(item->album, sizeof(item->album), "%s", q_album);
                    item->file_size = (int64_t)q_size;
                    snprintf(item->mime_type, sizeof(item->mime_type), "%s", q_mime);
                    item->duration_ms = (int32_t)q_dur_ms;
                    snprintf(item->duration_str, sizeof(item->duration_str), "%s", q_dur_str);
                    snprintf(item->format_desc, sizeof(item->format_desc), "%s", q_fmt);
                    item->is_ready = false;
                    item->local_path[0] = '\0';

                    printf("[QUEUE] + Added song: '%s' by '%s' [%s]\n", q_name, q_artist, q_dur_str);
                }
                pthread_mutex_unlock(&g_queue_lock);
                break;
            }

            case SYNC_FILE_INCOMING: {
                char tr_id[128], tr_name[256], tr_mime[64];
                uint64_t tr_size;
                uint32_t tr_port;

                if (recv_string(sync_fd, tr_id, sizeof(tr_id)) != 0 ||
                    recv_string(sync_fd, tr_name, sizeof(tr_name)) != 0 ||
                    recv_u64(sync_fd, &tr_size) != 0 ||
                    recv_string(sync_fd, tr_mime, sizeof(tr_mime)) != 0 ||
                    recv_u32(sync_fd, &tr_port) != 0) {
                    goto exit_loop;
                }

                dl_worker_arg_t *d = (dl_worker_arg_t *)malloc(sizeof(dl_worker_arg_t));
                if (d) {
                    snprintf(d->server_ip, sizeof(d->server_ip), "%s", cfg->server_ip);
                    d->port = (int)tr_port;
                    snprintf(d->id, sizeof(d->id), "%s", tr_id);
                    snprintf(d->name, sizeof(d->name), "%s", tr_name);
                    d->size = (int64_t)tr_size;
                    d->sync_fd = sync_fd;

                    pthread_t d_th;
                    pthread_create(&d_th, NULL, client_download_worker, d);
                    pthread_detach(d_th);
                }
                break;
            }

            case FILE_READY: {
                char r_id[128];
                if (recv_string(sync_fd, r_id, sizeof(r_id)) == 0) {
                    pthread_mutex_lock(&g_queue_lock);
                    for (int i = 0; i < g_client_queue_count; i++) {
                        if (strcmp(g_client_queue[i].id, r_id) == 0) {
                            g_client_queue[i].is_ready = true;
                            printf("[QUEUE] Track ready for playback: %s\n", g_client_queue[i].file_name);
                            break;
                        }
                    }
                    pthread_mutex_unlock(&g_queue_lock);
                }
                break;
            }

            case SYNC_TRACK_CHANGE: {
                uint32_t track_idx;
                uint64_t play_at_u64;
                if (recv_u32(sync_fd, &track_idx) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_loop;

                int64_t play_at = (int64_t)play_at_u64;
                int idx = (int)track_idx;

                pthread_mutex_lock(&g_queue_lock);
                g_current_playing_index = idx;
                if (idx >= 0 && idx < g_client_queue_count && g_client_queue[idx].is_ready) {
                    audio_player_load(g_client_queue[idx].local_path);
                }
                pthread_mutex_unlock(&g_queue_lock);

                g_sync_time_established = false;
                apply_synced_start_time(play_at, cfg->verbose);
                break;
            }

            case SYNC_PLAY: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_loop;
                int64_t host_start_time = (int64_t)play_at_u64 - (int64_t)pos_u64;
                g_sync_time_established = false;
                apply_synced_start_time(host_start_time, cfg->verbose);
                break;
            }

            case SYNC_PAUSE_CMD: {
                uint64_t pos_u64, pause_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &pause_at_u64) != 0) goto exit_loop;
                g_synced_timeline_active = false;
                audio_player_pause();
                printf("[TIMELINE] Paused at timeline pos: %ld ms\n", (long)pos_u64);
                break;
            }

            case SYNC_SEEK: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_loop;
                int64_t host_start_time = (int64_t)play_at_u64 - (int64_t)pos_u64;
                g_sync_time_established = false;
                apply_synced_start_time(host_start_time, cfg->verbose);
                break;
            }

            case SYNC_TIME_PULSE: {
                uint32_t pulse_idx;
                uint64_t host_pos_u64, host_time_u64;
                if (recv_u32(sync_fd, &pulse_idx) != 0 ||
                    recv_u64(sync_fd, &host_pos_u64) != 0 ||
                    recv_u64(sync_fd, &host_time_u64) != 0) {
                    goto exit_loop;
                }

                int p_idx = (int)pulse_idx;
                pthread_mutex_lock(&g_queue_lock);
                if (p_idx >= 0 && p_idx < g_client_queue_count && g_client_queue[p_idx].is_ready) {
                    if (!audio_player_is_loaded() || g_current_playing_index != p_idx) {
                        g_current_playing_index = p_idx;
                        audio_player_load(g_client_queue[p_idx].local_path);
                        g_sync_time_established = false;
                    }
                }
                pthread_mutex_unlock(&g_queue_lock);

                int64_t host_start_time = (int64_t)host_time_u64 - (int64_t)host_pos_u64;

                if (!g_sync_time_established || !g_sync_time_locked) {
                    apply_synced_start_time(host_start_time, cfg->verbose);
                } else {
                    /* Sync time is established and locked: LEAVE IT UNCHANGED! */
                    int64_t now = time_now_ms();
                    int64_t timeline_pos = now - g_synced_start_time_local;
                    if (audio_player_is_loaded() && audio_player_is_playing()) {
                        int64_t local_pos = audio_player_get_position_ms();
                        int64_t signed_drift = timeline_pos - local_pos;
                        int64_t abs_drift = llabs(signed_drift);
                        if (abs_drift > 400) {
                            audio_player_seek(timeline_pos);
                            audio_player_adjust_speed(1.0);
                            printf("[TIMELINE] Hard drift seek to %ld ms (drift %+ld ms)\n", (long)timeline_pos, (long)signed_drift);
                        } else if (abs_drift > 35) {
                            audio_player_adjust_speed(signed_drift > 0 ? 1.02 : 0.98);
                        } else {
                            audio_player_adjust_speed(1.0);
                        }
                        if (cfg->verbose && abs_drift > 35) {
                            printf("[TIMELINE] Pos: %02ld:%02ld.%03ld | Drift: %+ld ms\n",
                                   (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000),
                                   (long)(timeline_pos % 1000), (long)signed_drift);
                        }
                    }
                }
                break;
            }

            case SYNC_CLOCK_REQ: {
                uint64_t t1;
                if (recv_u64(sync_fd, &t1) == 0) {
                    int64_t t2 = time_now_ms();
                    int64_t t3 = time_now_ms();
                    send_u8(sync_fd, SYNC_CLOCK_RSP);
                    send_u64(sync_fd, t1);
                    send_u64(sync_fd, (uint64_t)t2);
                    send_u64(sync_fd, (uint64_t)t3);
                }
                break;
            }

            default:
                break;
        }
    }

exit_loop:
    g_client_running = false;
    audio_player_stop();
    close(sync_fd);
    if (ping_started) {
        pthread_join(ping_th, NULL);
    }
    printf("[SYNC] Client stopped.\n");
    return 0;
}
