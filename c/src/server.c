#define _POSIX_C_SOURCE 200809L
#include "soundshare_time.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdarg.h>
#include <strings.h>

#define MAX_PEERS 64

typedef struct {
    int fd;
    char ip[INET_ADDRSTRLEN];
    int port;
    int64_t connected_at;
    pthread_mutex_t send_lock;
    bool active;
    bool initialized;
} sync_peer_t;

static sync_peer_t g_sync_peers[MAX_PEERS];
static pthread_mutex_t g_peers_mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile bool g_running = false;
static int g_sync_listener = -1;
static int g_ping_listener = -1;
static uint32_t g_pulse_index = 0;
static int64_t g_server_start_time_ms = 0;
static pthread_t g_sync_accept_thread;
static pthread_t g_ping_accept_thread;
static pthread_t g_pulse_thread;
static server_config_t g_srv_cfg;

/* Server Playlist Queue */
static song_item_t g_server_queue[MAX_SONG_QUEUE];
static int g_server_queue_count = 0;
static int g_server_playing_index = -1;
static volatile bool g_server_is_playing = false;
static pthread_mutex_t g_server_queue_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Callbacks for GUI / CLI notification */
static server_status_cb g_status_cb = NULL;
static server_client_cb g_client_cb = NULL;
static server_queue_cb g_queue_cb = NULL;

static void log_status(const char *fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    printf("%s\n", buf);
    if (g_status_cb) g_status_cb(buf);
}

static void peer_lock_send(sync_peer_t *peer) {
    pthread_mutex_lock(&peer->send_lock);
}

static void peer_unlock_send(sync_peer_t *peer) {
    pthread_mutex_unlock(&peer->send_lock);
}

int server_get_client_count(void) {
    pthread_mutex_lock(&g_peers_mutex);
    int count = 0;
    for (int i = 0; i < MAX_PEERS; i++) {
        if (g_sync_peers[i].active) count++;
    }
    pthread_mutex_unlock(&g_peers_mutex);
    return count;
}

static void notify_clients_changed(void) {
    int count = server_get_client_count();
    if (g_client_cb) g_client_cb(count);
}

static int add_sync_peer(int fd, const char *ip, int port) {
    pthread_mutex_lock(&g_peers_mutex);
    int slot = -1;
    for (int i = 0; i < MAX_PEERS; i++) {
        if (!g_sync_peers[i].active) {
            slot = i;
            break;
        }
    }

    if (slot >= 0) {
        g_sync_peers[slot].fd = fd;
        snprintf(g_sync_peers[slot].ip, sizeof(g_sync_peers[slot].ip), "%s", ip);
        g_sync_peers[slot].port = port;
        g_sync_peers[slot].connected_at = time_now_ms();
        pthread_mutex_init(&g_sync_peers[slot].send_lock, NULL);
        g_sync_peers[slot].active = true;
        g_sync_peers[slot].initialized = false;
    }
    pthread_mutex_unlock(&g_peers_mutex);

    if (slot >= 0) notify_clients_changed();
    return slot;
}

static void remove_sync_peer(int slot) {
    if (slot < 0 || slot >= MAX_PEERS) return;

    pthread_mutex_lock(&g_peers_mutex);
    if (g_sync_peers[slot].active) {
        g_sync_peers[slot].active = false;
        g_sync_peers[slot].initialized = false;
        close(g_sync_peers[slot].fd);
        g_sync_peers[slot].fd = -1;
        pthread_mutex_destroy(&g_sync_peers[slot].send_lock);
    }
    pthread_mutex_unlock(&g_peers_mutex);

    notify_clients_changed();
}

/* ------------------------------------------------------------------------- */
/* Peer I/O Protocol Helpers                                                 */
/* ------------------------------------------------------------------------- */

static int peer_send_queue_add(sync_peer_t *peer, const song_item_t *item) {
    peer_lock_send(peer);
    int rc = 0;
    if (send_u8(peer->fd, SYNC_QUEUE_ADD) != 0 ||
        send_string(peer->fd, item->id) != 0 ||
        send_string(peer->fd, item->file_name) != 0 ||
        send_string(peer->fd, item->artist) != 0 ||
        send_string(peer->fd, item->album) != 0 ||
        send_u64(peer->fd, (uint64_t)item->file_size) != 0 ||
        send_string(peer->fd, item->mime_type) != 0 ||
        send_u32(peer->fd, (uint32_t)item->duration_ms) != 0 ||
        send_string(peer->fd, item->duration_str) != 0 ||
        send_string(peer->fd, item->format_desc) != 0) {
        rc = -1;
    }
    peer_unlock_send(peer);
    return rc;
}

static int peer_send_track_change(sync_peer_t *peer, int index, int64_t play_at) {
    peer_lock_send(peer);
    int rc = 0;
    if (send_u8(peer->fd, SYNC_TRACK_CHANGE) != 0 ||
        send_u32(peer->fd, (uint32_t)index) != 0 ||
        send_u64(peer->fd, (uint64_t)play_at) != 0) {
        rc = -1;
    }
    peer_unlock_send(peer);
    return rc;
}

static int peer_send_sync_cmd(sync_peer_t *peer, uint8_t cmd, int64_t pos, int64_t target_at) {
    peer_lock_send(peer);
    int rc = 0;
    if (send_u8(peer->fd, cmd) != 0 ||
        send_u64(peer->fd, (uint64_t)pos) != 0 ||
        send_u64(peer->fd, (uint64_t)target_at) != 0) {
        rc = -1;
    }
    peer_unlock_send(peer);
    return rc;
}

static int peer_send_file_incoming(sync_peer_t *peer, const song_item_t *item, int port) {
    peer_lock_send(peer);
    int rc = 0;
    if (send_u8(peer->fd, SYNC_FILE_INCOMING) != 0 ||
        send_string(peer->fd, item->id) != 0 ||
        send_string(peer->fd, item->file_name) != 0 ||
        send_u64(peer->fd, (uint64_t)item->file_size) != 0 ||
        send_string(peer->fd, item->mime_type) != 0 ||
        send_u32(peer->fd, (uint32_t)port) != 0) {
        rc = -1;
    }
    peer_unlock_send(peer);
    return rc;
}

static int peer_send_file_ready(sync_peer_t *peer, const char *track_id) {
    peer_lock_send(peer);
    int rc = 0;
    if (send_u8(peer->fd, FILE_READY) != 0 ||
        send_string(peer->fd, track_id) != 0) {
        rc = -1;
    }
    peer_unlock_send(peer);
    return rc;
}

static void broadcast_queue_add(const song_item_t *item, int skip_slot) {
    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (i != skip_slot && g_sync_peers[i].active && g_sync_peers[i].initialized) {
            peer_send_queue_add(&g_sync_peers[i], item);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);
}

static void broadcast_track_change(int index, int64_t play_at, int skip_slot) {
    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (i != skip_slot && g_sync_peers[i].active && g_sync_peers[i].initialized) {
            peer_send_track_change(&g_sync_peers[i], index, play_at);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);
}

static void broadcast_sync_cmd(uint8_t cmd, int64_t pos, int64_t target_at, int skip_slot) {
    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (i != skip_slot && g_sync_peers[i].active && g_sync_peers[i].initialized) {
            peer_send_sync_cmd(&g_sync_peers[i], cmd, pos, target_at);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);
}

static void broadcast_file_ready(const char *track_id, int skip_slot) {
    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (i != skip_slot && g_sync_peers[i].active && g_sync_peers[i].initialized) {
            peer_send_file_ready(&g_sync_peers[i], track_id);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);
}

/* ------------------------------------------------------------------------- */
/* Song Transfer Helpers (Host -> Peer)                                      */
/* ------------------------------------------------------------------------- */

typedef struct {
    int slot;
    song_item_t item;
} peer_transfer_arg_t;

static void *transfer_single_track_thread(void *arg) {
    peer_transfer_arg_t *p = (peer_transfer_arg_t *)arg;
    int slot = p->slot;
    song_item_t item = p->item;
    free(p);

    if (slot < 0 || slot >= MAX_PEERS || !g_sync_peers[slot].active) return NULL;

    int dynamic_port = 0;
    if (song_transfer_send_async(&item, &dynamic_port, NULL) == 0) {
        peer_send_file_incoming(&g_sync_peers[slot], &item, dynamic_port);
    }
    return NULL;
}

static void transfer_track_to_peer(int slot, const song_item_t *item) {
    peer_transfer_arg_t *arg = (peer_transfer_arg_t *)malloc(sizeof(peer_transfer_arg_t));
    if (!arg) return;
    arg->slot = slot;
    arg->item = *item;

    pthread_t th;
    if (pthread_create(&th, NULL, transfer_single_track_thread, arg) == 0) {
        pthread_detach(th);
    } else {
        free(arg);
    }
}

typedef struct {
    int slot;
} peer_all_tracks_arg_t;

static void *transfer_all_tracks_worker(void *arg) {
    peer_all_tracks_arg_t *pa = (peer_all_tracks_arg_t *)arg;
    int slot = pa->slot;
    free(pa);

    if (slot < 0 || slot >= MAX_PEERS || !g_sync_peers[slot].active) return NULL;

    pthread_mutex_lock(&g_server_queue_mutex);
    int total = g_server_queue_count;
    int playing_idx = g_server_playing_index;
    song_item_t queue_copy[MAX_SONG_QUEUE];
    for (int i = 0; i < total; i++) {
        queue_copy[i] = g_server_queue[i];
    }
    pthread_mutex_unlock(&g_server_queue_mutex);

    if (total == 0) return NULL;

    /* 1. Prioritize active playing track so receiver starts immediately */
    if (playing_idx >= 0 && playing_idx < total) {
        if (queue_copy[playing_idx].local_path[0] != '\0') {
            int dynamic_port = 0;
            if (song_transfer_send_async(&queue_copy[playing_idx], &dynamic_port, NULL) == 0) {
                peer_send_file_incoming(&g_sync_peers[slot], &queue_copy[playing_idx], dynamic_port);
            }
            time_sleep_ms(350);
        }
    }

    /* 2. Transfer remaining playlist items */
    for (int i = 0; i < total; i++) {
        if (i == playing_idx) continue;
        if (!g_running || !g_sync_peers[slot].active) break;
        if (queue_copy[i].local_path[0] != '\0') {
            int dynamic_port = 0;
            if (song_transfer_send_async(&queue_copy[i], &dynamic_port, NULL) == 0) {
                peer_send_file_incoming(&g_sync_peers[slot], &queue_copy[i], dynamic_port);
            }
            time_sleep_ms(350);
        }
    }

    return NULL;
}

static void transfer_all_tracks_to_peer(int slot) {
    peer_all_tracks_arg_t *arg = (peer_all_tracks_arg_t *)malloc(sizeof(peer_all_tracks_arg_t));
    if (!arg) return;
    arg->slot = slot;

    pthread_t th;
    if (pthread_create(&th, NULL, transfer_all_tracks_worker, arg) == 0) {
        pthread_detach(th);
    } else {
        free(arg);
    }
}

/* ------------------------------------------------------------------------- */
/* Incoming File Transfer Worker (Peer -> Host)                              */
/* ------------------------------------------------------------------------- */

typedef struct {
    char ip[64];
    int port;
    char id[128];
    char name[256];
    int64_t size;
    int sender_slot;
} host_dl_job_t;

static void *host_receive_song_worker(void *arg) {
    host_dl_job_t *job = (host_dl_job_t *)arg;
    log_status("[HOST] Receiving song '%s' (ID: %s) from peer %s:%d...", job->name, job->id, job->ip, job->port);

    int rc = song_transfer_receive(job->ip, job->port, job->id, job->name, job->size, NULL, NULL);
    if (rc == 0) {
        char local_path[2048];
        snprintf(local_path, sizeof(local_path), "received_songs/%s_%s", job->id, job->name);
        log_status("[HOST] ✓ Download complete from peer: %s", local_path);

        pthread_mutex_lock(&g_server_queue_mutex);
        for (int i = 0; i < g_server_queue_count; i++) {
            if (strcmp(g_server_queue[i].id, job->id) == 0) {
                snprintf(g_server_queue[i].local_path, sizeof(g_server_queue[i].local_path), "%s", local_path);
                g_server_queue[i].is_ready = true;
                break;
            }
        }
        pthread_mutex_unlock(&g_server_queue_mutex);

        broadcast_file_ready(job->id, -1);
        if (g_queue_cb) g_queue_cb();
    } else {
        log_status("[HOST] ✗ Failed to receive song '%s' from peer %s", job->name, job->ip);
    }

    free(job);
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* SYNC_CTRL_PORT (5006) Peer Worker Thread                                  */
/* ------------------------------------------------------------------------- */

static void *sync_peer_worker(void *arg) {
    int slot = (int)(intptr_t)arg;
    sync_peer_t *peer = &g_sync_peers[slot];
    int fd = peer->fd;
    char ip[INET_ADDRSTRLEN];
    snprintf(ip, sizeof(ip), "%s", peer->ip);

    log_status("[SYNC] New client connected: %s:%d (slot %d)", ip, peer->port, slot);

    while (g_running && peer->active) {
        uint8_t cmd;
        if (recv_u8(fd, &cmd) != 0) break;

        switch (cmd) {
            case SYNC_CLOCK_REQ: {
                uint64_t t1;
                if (recv_u64(fd, &t1) != 0) goto disconnect;

                int64_t t2 = time_now_ms();
                int64_t t3 = time_now_ms();

                peer_lock_send(peer);
                if (send_u8(fd, SYNC_CLOCK_RSP) != 0 ||
                    send_u64(fd, t1) != 0 ||
                    send_u64(fd, (uint64_t)t2) != 0 ||
                    send_u64(fd, (uint64_t)t3) != 0) {
                    peer_unlock_send(peer);
                    goto disconnect;
                }
                peer_unlock_send(peer);
                break;
            }

            case SYNC_INIT_REQ: {
                log_status("[SYNC] Client %s calibrated clock. Initializing playlist sync session...", ip);

                pthread_mutex_lock(&g_peers_mutex);
                peer->initialized = true;
                pthread_mutex_unlock(&g_peers_mutex);

                /* Send current playlist queue to this newly calibrated peer */
                pthread_mutex_lock(&g_server_queue_mutex);
                for (int i = 0; i < g_server_queue_count; i++) {
                    peer_send_queue_add(peer, &g_server_queue[i]);
                }

                /* If host is currently playing, send active track index and play timeline */
                if (g_server_playing_index >= 0 && g_server_playing_index < g_server_queue_count) {
                    int64_t play_at = time_now_ms() + 650;
                    int64_t current_pos = audio_player_get_position_ms();
                    peer_send_track_change(peer, g_server_playing_index, play_at);
                    if (g_server_is_playing) {
                        peer_send_sync_cmd(peer, SYNC_PLAY, current_pos, play_at);
                    }
                }
                pthread_mutex_unlock(&g_server_queue_mutex);

                /* Start background track transfer for all songs */
                transfer_all_tracks_to_peer(slot);
                break;
            }

            case SYNC_QUEUE_ADD: {
                char q_id[128], q_name[256], q_artist[128], q_album[128], q_mime[64], q_dur_str[32], q_fmt[64];
                uint64_t q_size;
                uint32_t q_dur_ms;

                if (recv_string(fd, q_id, sizeof(q_id)) != 0 ||
                    recv_string(fd, q_name, sizeof(q_name)) != 0 ||
                    recv_string(fd, q_artist, sizeof(q_artist)) != 0 ||
                    recv_string(fd, q_album, sizeof(q_album)) != 0 ||
                    recv_u64(fd, &q_size) != 0 ||
                    recv_string(fd, q_mime, sizeof(q_mime)) != 0 ||
                    recv_u32(fd, &q_dur_ms) != 0 ||
                    recv_string(fd, q_dur_str, sizeof(q_dur_str)) != 0 ||
                    recv_string(fd, q_fmt, sizeof(q_fmt)) != 0) {
                    goto disconnect;
                }

                pthread_mutex_lock(&g_server_queue_mutex);
                bool exists = false;
                for (int i = 0; i < g_server_queue_count; i++) {
                    if (strcmp(g_server_queue[i].id, q_id) == 0) {
                        exists = true;
                        break;
                    }
                }

                if (!exists && g_server_queue_count < MAX_SONG_QUEUE) {
                    song_item_t *item = &g_server_queue[g_server_queue_count++];
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

                    log_status("[QUEUE] Peer %s added track: '%s' by '%s'", ip, q_name, q_artist);
                    broadcast_queue_add(item, slot);
                    if (g_queue_cb) g_queue_cb();
                }
                pthread_mutex_unlock(&g_server_queue_mutex);
                break;
            }

            case SYNC_FILE_INCOMING: {
                char tr_id[128], tr_name[256], tr_mime[64];
                uint64_t tr_size;
                uint32_t tr_port;

                if (recv_string(fd, tr_id, sizeof(tr_id)) != 0 ||
                    recv_string(fd, tr_name, sizeof(tr_name)) != 0 ||
                    recv_u64(fd, &tr_size) != 0 ||
                    recv_string(fd, tr_mime, sizeof(tr_mime)) != 0 ||
                    recv_u32(fd, &tr_port) != 0) {
                    goto disconnect;
                }

                host_dl_job_t *job = (host_dl_job_t *)malloc(sizeof(host_dl_job_t));
                if (job) {
                    snprintf(job->ip, sizeof(job->ip), "%s", ip);
                    job->port = (int)tr_port;
                    snprintf(job->id, sizeof(job->id), "%s", tr_id);
                    snprintf(job->name, sizeof(job->name), "%s", tr_name);
                    job->size = (int64_t)tr_size;
                    job->sender_slot = slot;

                    pthread_t h_th;
                    pthread_create(&h_th, NULL, host_receive_song_worker, job);
                    pthread_detach(h_th);
                }
                break;
            }

            case FILE_READY: {
                char r_id[128];
                if (recv_string(fd, r_id, sizeof(r_id)) != 0) goto disconnect;

                pthread_mutex_lock(&g_server_queue_mutex);
                for (int i = 0; i < g_server_queue_count; i++) {
                    if (strcmp(g_server_queue[i].id, r_id) == 0) {
                        g_server_queue[i].is_ready = true;
                        log_status("[QUEUE] Track %s marked ready by peer %s", g_server_queue[i].file_name, ip);
                        break;
                    }
                }

                /* If host is actively playing this track, send live catch-up play packet to peer */
                if (g_server_is_playing && g_server_playing_index >= 0 && g_server_playing_index < g_server_queue_count) {
                    if (strcmp(g_server_queue[g_server_playing_index].id, r_id) == 0) {
                        int64_t play_at = time_now_ms() + 650;
                        int64_t cur_pos = audio_player_get_position_ms();
                        peer_send_track_change(peer, g_server_playing_index, play_at);
                        peer_send_sync_cmd(peer, SYNC_PLAY, cur_pos, play_at);
                    }
                }
                pthread_mutex_unlock(&g_server_queue_mutex);

                broadcast_file_ready(r_id, slot);
                if (g_queue_cb) g_queue_cb();
                break;
            }

            case SYNC_TRACK_CHANGE: {
                uint32_t new_idx;
                uint64_t play_at_u64;
                if (recv_u32(fd, &new_idx) != 0 || recv_u64(fd, &play_at_u64) != 0) goto disconnect;

                int idx = (int)new_idx;
                int64_t play_at = (int64_t)play_at_u64;

                pthread_mutex_lock(&g_server_queue_mutex);
                if (idx >= 0 && idx < g_server_queue_count) {
                    g_server_playing_index = idx;
                    g_server_is_playing = true;
                    if (g_server_queue[idx].local_path[0] != '\0') {
                        audio_player_load(g_server_queue[idx].local_path);
                        audio_player_schedule_play(0, play_at);
                    }
                    log_status("[SYNC_PLAY] Peer %s changed track to #%d: '%s'", ip, idx, g_server_queue[idx].file_name);
                }
                pthread_mutex_unlock(&g_server_queue_mutex);

                broadcast_track_change(idx, play_at, slot);
                broadcast_sync_cmd(SYNC_PLAY, 0, play_at, slot);
                if (g_queue_cb) g_queue_cb();
                break;
            }

            case SYNC_PLAY: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(fd, &pos_u64) != 0 || recv_u64(fd, &play_at_u64) != 0) goto disconnect;

                int64_t pos = (int64_t)pos_u64;
                int64_t play_at = (int64_t)play_at_u64;

                g_server_is_playing = true;
                if (audio_player_is_loaded()) {
                    audio_player_schedule_play(pos, play_at);
                }
                log_status("[SYNC] Peer %s resumed synchronized playback at %ld ms", ip, (long)pos);
                broadcast_sync_cmd(SYNC_PLAY, pos, play_at, slot);
                break;
            }

            case SYNC_PAUSE_CMD: {
                uint64_t pos_u64, pause_at_u64;
                if (recv_u64(fd, &pos_u64) != 0 || recv_u64(fd, &pause_at_u64) != 0) goto disconnect;

                int64_t pos = (int64_t)pos_u64;
                int64_t pause_at = (int64_t)pause_at_u64;

                g_server_is_playing = false;
                if (audio_player_is_loaded()) {
                    audio_player_pause();
                }
                log_status("[SYNC] Peer %s paused playback at %ld ms", ip, (long)pos);
                broadcast_sync_cmd(SYNC_PAUSE_CMD, pos, pause_at, slot);
                break;
            }

            case SYNC_SEEK: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(fd, &pos_u64) != 0 || recv_u64(fd, &play_at_u64) != 0) goto disconnect;

                int64_t pos = (int64_t)pos_u64;
                int64_t play_at = (int64_t)play_at_u64;

                g_server_is_playing = true;
                if (audio_player_is_loaded()) {
                    audio_player_schedule_play(pos, play_at);
                }
                log_status("[SYNC] Peer %s seeked playback to %ld ms", ip, (long)pos);
                broadcast_sync_cmd(SYNC_SEEK, pos, play_at, slot);
                break;
            }

            default:
                break;
        }
    }

disconnect:
    log_status("[SYNC] Client disconnected: %s (slot %d)", ip, slot);
    remove_sync_peer(slot);
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* Time Pulse Broadcaster (Every 500ms)                                      */
/* ------------------------------------------------------------------------- */

static void *time_pulse_broadcaster(void *arg) {
    const server_config_t *cfg = (const server_config_t *)arg;
    int interval_ms = cfg->pulse_interval_ms > 0 ? cfg->pulse_interval_ms : 500;

    while (g_running) {
        time_sleep_ms(interval_ms);
        if (!g_running) break;

        int64_t now_ms = time_now_ms();
        int64_t host_pos = 0;
        uint32_t track_idx = 0;

        pthread_mutex_lock(&g_server_queue_mutex);
        if (g_server_playing_index >= 0) {
            track_idx = (uint32_t)g_server_playing_index;
            host_pos = audio_player_is_loaded() ? audio_player_get_position_ms() : (now_ms - g_server_start_time_ms);
        } else {
            track_idx = ++g_pulse_index;
            host_pos = now_ms - g_server_start_time_ms;
        }
        pthread_mutex_unlock(&g_server_queue_mutex);

        pthread_mutex_lock(&g_peers_mutex);
        for (int i = 0; i < MAX_PEERS; i++) {
            if (g_sync_peers[i].active && g_sync_peers[i].initialized) {
                peer_lock_send(&g_sync_peers[i]);
                if (send_u8(g_sync_peers[i].fd, SYNC_TIME_PULSE) != 0 ||
                    send_u32(g_sync_peers[i].fd, track_idx) != 0 ||
                    send_u64(g_sync_peers[i].fd, (uint64_t)host_pos) != 0 ||
                    send_u64(g_sync_peers[i].fd, (uint64_t)now_ms) != 0) {
                    g_sync_peers[i].active = false;
                    close(g_sync_peers[i].fd);
                    g_sync_peers[i].fd = -1;
                }
                peer_unlock_send(&g_sync_peers[i]);
            }
        }
        pthread_mutex_unlock(&g_peers_mutex);
    }
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* Ping / Latency Port 5001 Server                                           */
/* ------------------------------------------------------------------------- */

static void *ping_client_worker(void *arg) {
    int fd = (int)(intptr_t)arg;
    struct sockaddr_in addr;
    socklen_t addrlen = sizeof(addr);
    char ip[INET_ADDRSTRLEN] = "unknown";
    if (getpeername(fd, (struct sockaddr *)&addr, &addrlen) == 0) {
        inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
    }

    while (g_running) {
        uint8_t cmd;
        if (recv_u8(fd, &cmd) != 0) break;

        if (cmd == PING_REQUEST) {
            if (send_u8(fd, PING_RESPONSE) != 0) break;
        } else if (cmd == LATENCY_REPORT) {
            uint64_t lat_ms;
            if (recv_u64(fd, &lat_ms) != 0) break;
        } else if (cmd >= 0x20 && cmd <= 0x23) {
            /* Remote control commands forwarded */
            if (cmd == CTRL_PAUSE) server_play_pause();
            else if (cmd == CTRL_RESUME) server_play_pause();
            else if (cmd == CTRL_SKIP_FWD) server_skip_next();
            else if (cmd == CTRL_SKIP_BACK) server_skip_prev();
        }
    }

    close(fd);
    return NULL;
}

static void *ping_server_listener(void *arg) {
    const server_config_t *cfg = (const server_config_t *)arg;
    g_ping_listener = net_create_tcp_listener(cfg->ping_port);
    if (g_ping_listener < 0) return NULL;

    while (g_running) {
        struct sockaddr_in addr;
        socklen_t len = sizeof(addr);
        int client_fd = accept(g_ping_listener, (struct sockaddr *)&addr, &len);
        if (client_fd < 0) {
            if (!g_running) break;
            if (errno == EINTR) continue;
            time_sleep_ms(100);
            continue;
        }

        net_set_tcp_nodelay(client_fd, true);
        pthread_t th;
        if (pthread_create(&th, NULL, ping_client_worker, (void *)(intptr_t)client_fd) == 0) {
            pthread_detach(th);
        } else {
            close(client_fd);
        }
    }

    if (g_ping_listener >= 0) close(g_ping_listener);
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* SYNC_CTRL_PORT Accept Thread                                              */
/* ------------------------------------------------------------------------- */

static void *sync_server_listener(void *arg) {
    (void)arg;
    while (g_running && g_sync_listener >= 0) {
        struct sockaddr_in addr;
        socklen_t len = sizeof(addr);
        int client_fd = accept(g_sync_listener, (struct sockaddr *)&addr, &len);
        if (client_fd < 0) {
            if (!g_running) break;
            if (errno == EINTR) continue;
            time_sleep_ms(100);
            continue;
        }

        net_set_tcp_nodelay(client_fd, true);
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
        int port = ntohs(addr.sin_port);

        int slot = add_sync_peer(client_fd, ip, port);
        if (slot < 0) {
            log_status("[SYNC] Max peers reached, rejecting %s:%d", ip, port);
            close(client_fd);
            continue;
        }

        pthread_t peer_th;
        if (pthread_create(&peer_th, NULL, sync_peer_worker, (void *)(intptr_t)slot) == 0) {
            pthread_detach(peer_th);
        } else {
            remove_sync_peer(slot);
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* Public Server Management APIs                                             */
/* ------------------------------------------------------------------------- */

bool server_is_running(void) {
    return g_running;
}

int server_start(const server_config_t *cfg) {
    if (g_running) return 0;

    g_srv_cfg = *cfg;
    if (g_srv_cfg.sync_port <= 0) g_srv_cfg.sync_port = SOUNDSHARE_SYNC_CTRL_PORT;
    if (g_srv_cfg.ping_port <= 0) g_srv_cfg.ping_port = SOUNDSHARE_PING_PORT;
    if (g_srv_cfg.pulse_interval_ms <= 0) g_srv_cfg.pulse_interval_ms = 500;

    g_running = true;
    g_server_start_time_ms = time_now_ms();
    audio_player_init();

    for (int i = 0; i < MAX_PEERS; i++) {
        g_sync_peers[i].active = false;
        g_sync_peers[i].fd = -1;
    }

    /* 1. Start UDP Auto-Discovery responder on Port 5007 */
    net_start_discovery_server(SOUNDSHARE_DISCOVERY_PORT, SS_DISCOVER_FILE, SS_HOST_FILE_PREFIX);

    /* 2. Start Ping Listener on Port 5001 */
    if (pthread_create(&g_ping_accept_thread, NULL, ping_server_listener, &g_srv_cfg) != 0) {
        log_status("[SERVER] Failed to start ping server");
    }

    /* 3. Start Time Pulse Broadcaster */
    if (pthread_create(&g_pulse_thread, NULL, time_pulse_broadcaster, &g_srv_cfg) != 0) {
        log_status("[SERVER] Failed to start time pulse broadcaster");
    }

    /* 4. Start Sync Control Listener on Port 5006 */
    g_sync_listener = net_create_tcp_listener(g_srv_cfg.sync_port);
    if (g_sync_listener < 0) {
        log_status("[SERVER] Failed to create sync listener on port %d: %s", g_srv_cfg.sync_port, strerror(errno));
        server_stop();
        return -1;
    }

    if (pthread_create(&g_sync_accept_thread, NULL, sync_server_listener, NULL) != 0) {
        log_status("[SERVER] Failed to start sync accept thread");
        server_stop();
        return -1;
    }

    log_status("🚀 Server started on %s | Sync: %d | Ping: %d | Discovery: %d",
               net_get_local_ip(), g_srv_cfg.sync_port, g_srv_cfg.ping_port, SOUNDSHARE_DISCOVERY_PORT);
    return 0;
}

void server_stop(void) {
    if (!g_running) return;
    g_running = false;

    net_stop_discovery_server();

    if (g_sync_listener >= 0) {
        close(g_sync_listener);
        g_sync_listener = -1;
    }
    if (g_ping_listener >= 0) {
        close(g_ping_listener);
        g_ping_listener = -1;
    }

    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (g_sync_peers[i].active) {
            g_sync_peers[i].active = false;
            close(g_sync_peers[i].fd);
            g_sync_peers[i].fd = -1;
            pthread_mutex_destroy(&g_sync_peers[i].send_lock);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);

    audio_player_stop();
    notify_clients_changed();
    log_status("🛑 Server stopped.");
}

int server_add_track(const char *filepath, const char *title, const char *artist) {
    if (!filepath || filepath[0] == '\0') return -1;

    pthread_mutex_lock(&g_server_queue_mutex);
    if (g_server_queue_count >= MAX_SONG_QUEUE) {
        pthread_mutex_unlock(&g_server_queue_mutex);
        return -1;
    }

    int idx = g_server_queue_count++;
    song_item_t *item = &g_server_queue[idx];
    snprintf(item->id, sizeof(item->id), "trk_%d_%ld", idx, (long)time_now_ms());

    if (title && title[0] != '\0') {
        snprintf(item->file_name, sizeof(item->file_name), "%s", title);
    } else {
        const char *slash = strrchr(filepath, '/');
        snprintf(item->file_name, sizeof(item->file_name), "%s", slash ? slash + 1 : filepath);
    }

    snprintf(item->artist, sizeof(item->artist), "%s", (artist && artist[0] != '\0') ? artist : "Linux Host");
    snprintf(item->album, sizeof(item->album), "SoundShare Sync");
    snprintf(item->local_path, sizeof(item->local_path), "%s", filepath);

    item->file_size = 0;
    FILE *fp = fopen(filepath, "rb");
    if (fp) {
        fseek(fp, 0, SEEK_END);
        item->file_size = (int64_t)ftell(fp);
        fclose(fp);
    }

    const char *ext = strrchr(filepath, '.');
    if (ext && strcasecmp(ext, ".mp3") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/mpeg");
    else if (ext && strcasecmp(ext, ".wav") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/wav");
    else if (ext && strcasecmp(ext, ".ogg") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/ogg");
    else if (ext && strcasecmp(ext, ".m4a") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/mp4");
    else snprintf(item->mime_type, sizeof(item->mime_type), "audio/*");

    item->duration_ms = 180000;
    snprintf(item->duration_str, sizeof(item->duration_str), "03:00");
    snprintf(item->format_desc, sizeof(item->format_desc), "Hi-Res Audio");
    item->is_ready = true;

    pthread_mutex_unlock(&g_server_queue_mutex);

    log_status("[QUEUE] Host added track #%d: '%s' (%ld bytes)", idx, item->file_name, (long)item->file_size);

    /* Broadcast SYNC_QUEUE_ADD to all peers */
    broadcast_queue_add(item, -1);

    /* Start transfer of this track to all currently connected peers */
    pthread_mutex_lock(&g_peers_mutex);
    for (int i = 0; i < MAX_PEERS; i++) {
        if (g_sync_peers[i].active) {
            transfer_track_to_peer(i, item);
        }
    }
    pthread_mutex_unlock(&g_peers_mutex);

    if (g_queue_cb) g_queue_cb();
    return idx;
}

void server_play_track(int index) {
    pthread_mutex_lock(&g_server_queue_mutex);
    if (index < 0 || index >= g_server_queue_count) {
        pthread_mutex_unlock(&g_server_queue_mutex);
        return;
    }

    g_server_playing_index = index;
    g_server_is_playing = true;
    song_item_t item = g_server_queue[index];
    pthread_mutex_unlock(&g_server_queue_mutex);

    int64_t play_at = time_now_ms() + 650;

    if (item.local_path[0] != '\0') {
        audio_player_load(item.local_path);
        audio_player_schedule_play(0, play_at);
    }

    log_status("[SYNC_PLAY] Broadcasting Track #%d: '%s' (starts at +650ms)", index, item.file_name);

    broadcast_track_change(index, play_at, -1);
    broadcast_sync_cmd(SYNC_PLAY, 0, play_at, -1);

    if (g_queue_cb) g_queue_cb();
}

void server_play_pause(void) {
    if (!audio_player_is_loaded()) {
        if (g_server_queue_count > 0) {
            server_play_track(g_server_playing_index >= 0 ? g_server_playing_index : 0);
        }
        return;
    }

    if (g_server_is_playing) {
        int64_t pos = audio_player_get_position_ms();
        int64_t now = time_now_ms();
        audio_player_pause();
        g_server_is_playing = false;
        log_status("[SYNC] Pausing synchronized playback at %ld ms", (long)pos);
        broadcast_sync_cmd(SYNC_PAUSE_CMD, pos, now, -1);
    } else {
        int64_t pos = audio_player_get_position_ms();
        int64_t play_at = time_now_ms() + 650;
        audio_player_schedule_play(pos, play_at);
        g_server_is_playing = true;
        log_status("[SYNC] Resuming synchronized playback from %ld ms (playAt: +650ms)", (long)pos);
        broadcast_sync_cmd(SYNC_PLAY, pos, play_at, -1);
    }
}

void server_seek(int64_t pos_ms) {
    if (!audio_player_is_loaded()) return;

    int64_t play_at = time_now_ms() + 650;
    audio_player_schedule_play(pos_ms, play_at);
    g_server_is_playing = true;
    log_status("[SYNC] Seek to %ld ms across all devices", (long)pos_ms);
    broadcast_sync_cmd(SYNC_SEEK, pos_ms, play_at, -1);
}

void server_skip_next(void) {
    pthread_mutex_lock(&g_server_queue_mutex);
    if (g_server_queue_count == 0) {
        pthread_mutex_unlock(&g_server_queue_mutex);
        return;
    }
    int next = (g_server_playing_index + 1) % g_server_queue_count;
    pthread_mutex_unlock(&g_server_queue_mutex);
    server_play_track(next);
}

void server_skip_prev(void) {
    pthread_mutex_lock(&g_server_queue_mutex);
    if (g_server_queue_count == 0) {
        pthread_mutex_unlock(&g_server_queue_mutex);
        return;
    }
    int prev = (g_server_playing_index - 1 + g_server_queue_count) % g_server_queue_count;
    pthread_mutex_unlock(&g_server_queue_mutex);
    server_play_track(prev);
}

void server_set_callbacks(server_status_cb on_status, server_client_cb on_clients, server_queue_cb on_queue) {
    g_status_cb = on_status;
    g_client_cb = on_clients;
    g_queue_cb = on_queue;
}

const song_item_t *server_get_queue(int *out_count) {
    pthread_mutex_lock(&g_server_queue_mutex);
    if (out_count) *out_count = g_server_queue_count;
    pthread_mutex_unlock(&g_server_queue_mutex);
    return g_server_queue;
}

int server_get_playing_index(void) {
    return g_server_playing_index;
}

int run_server(const server_config_t *cfg) {
    if (server_start(cfg) != 0) return 1;

    printf("=================================================================\n");
    printf(" SoundShare File Share & Time Sync Server running\n");
    printf(" Local IP        : %s\n", net_get_local_ip());
    printf(" Clock Sync Port : %d\n", cfg->sync_port);
    printf(" Ping Port       : %d\n", cfg->ping_port);
    printf(" Discovery Port  : %d (SS_DISCOVER_FILE)\n", SOUNDSHARE_DISCOVERY_PORT);
    printf(" Ready for Android SoundShare & Linux peers (Ctrl+C to stop)\n");
    printf("=================================================================\n");

    while (g_running) {
        time_sleep_ms(250);
    }
    return 0;
}
