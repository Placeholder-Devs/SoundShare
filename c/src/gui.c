#define _POSIX_C_SOURCE 200809L
#include "soundshare_time.h"
#include "stream_receiver.h"

#include <gtk/gtk.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <math.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

/* ========================================================================= */
/* CSS Styling                                                               */
/* ========================================================================= */
static const char *APP_CSS =
    "window { background-color: #0f1115; font-family: 'Inter', 'Segoe UI', 'Ubuntu', sans-serif; }\n"
    "notebook { background-color: #0f1115; border: none; }\n"
    "notebook tab { background-color: #16181f; color: #9ca3af; padding: 10px 20px; font-weight: bold; border-radius: 8px 8px 0 0; }\n"
    "notebook tab:checked { background-color: #1f232b; color: #38bdf8; border-bottom: 3px solid #38bdf8; }\n"
    ".card { background-color: #181b22; border: 1px solid #272c38; border-radius: 10px; padding: 14px; margin: 4px; }\n"
    ".now-playing-card { background: linear-gradient(135deg, #1e2530 0%, #171d27 100%); border: 1px solid #2d3748; border-radius: 12px; padding: 16px; margin: 4px; }\n"
    ".metric-card { background-color: #1e222b; border: 1px solid #2a313d; border-radius: 8px; padding: 10px; margin: 2px; }\n"
    ".metric-title { font-size: 11px; font-weight: bold; color: #94a3b8; text-transform: uppercase; }\n"
    ".metric-value { font-size: 18px; font-weight: bold; color: #38bdf8; font-family: monospace; }\n"
    ".clock-local { font-size: 20px; font-weight: bold; color: #22c55e; font-family: monospace; }\n"
    ".clock-remote { font-size: 20px; font-weight: bold; color: #38bdf8; font-family: monospace; }\n"
    ".clock-offset { font-size: 18px; font-weight: bold; color: #f59e0b; font-family: monospace; }\n"
    ".song-title { font-size: 18px; font-weight: bold; color: #ffffff; }\n"
    ".song-artist { font-size: 14px; color: #94a3b8; }\n"
    ".stream-info { font-size: 12px; font-weight: bold; color: #38bdf8; font-family: monospace; }\n"
    ".badge-disconnected { background-color: #374151; color: #9ca3af; font-weight: bold; font-size: 12px; border-radius: 12px; padding: 3px 10px; }\n"
    ".badge-calibrating { background-color: #854d0e; color: #fef08a; font-weight: bold; font-size: 12px; border-radius: 12px; padding: 3px 10px; }\n"
    ".badge-synced { background-color: #065f46; color: #6ee7b7; font-weight: bold; font-size: 12px; border-radius: 12px; padding: 3px 10px; }\n"
    ".badge-playing { background-color: #1e3a8a; color: #93c5fd; font-weight: bold; font-size: 12px; border-radius: 12px; padding: 3px 10px; }\n"
    "button.btn-connect { background: #059669; color: white; font-weight: bold; border-radius: 6px; padding: 8px 16px; border: none; }\n"
    "button.btn-connect:hover { background: #10b981; }\n"
    "button.btn-disconnect { background: #dc2626; color: white; font-weight: bold; border-radius: 6px; padding: 8px 16px; border: none; }\n"
    "button.btn-disconnect:hover { background: #ef4444; }\n"
    "button.btn-control { background: #2d3748; color: white; font-weight: bold; border-radius: 20px; min-width: 44px; min-height: 44px; border: none; font-size: 15px; }\n"
    "button.btn-control:hover { background: #38bdf8; color: #0f172a; }\n"
    "button.btn-add-song { background: #0284c7; color: white; font-weight: bold; border-radius: 6px; padding: 6px 12px; border: none; }\n"
    "button.btn-add-song:hover { background: #38bdf8; }\n"
    "button.btn-lock { background: #065f46; color: #a7f3d0; font-weight: bold; border-radius: 6px; padding: 6px 12px; border: 1px solid #059669; }\n"
    "button.btn-lock:hover { background: #047857; }\n"
    "button.btn-unlock { background: #991b1b; color: #fecaca; font-weight: bold; border-radius: 6px; padding: 6px 12px; border: 1px solid #dc2626; }\n"
    "button.btn-unlock:hover { background: #b91c1c; }\n"
    "button.btn-nudge { background: #1e293b; color: #38bdf8; font-weight: bold; border-radius: 6px; padding: 5px 10px; border: 1px solid #334155; font-size: 12px; }\n"
    "button.btn-nudge:hover { background: #334155; color: #ffffff; border-color: #38bdf8; }\n"
    ".nudge-badge { font-size: 13px; font-weight: bold; color: #facc15; font-family: monospace; padding: 4px 8px; background-color: #1e222b; border-radius: 4px; border: 1px solid #374151; }\n"
    "progressbar.vu-level trough { background-color: #1a1d24; border-radius: 4px; min-height: 8px; border: 1px solid #2d3748; }\n"
    "progressbar.vu-level progress { background: linear-gradient(90deg, #22c55e 0%, #38bdf8 65%, #f59e0b 85%, #ef4444 100%); border-radius: 3px; min-height: 8px; }\n"
    ".timeline-card { background: linear-gradient(135deg, #0b1120 0%, #1e1b4b 60%, #172554 100%); border: 2px solid #38bdf8; border-radius: 12px; padding: 18px; margin: 4px; }\n"
    ".timeline-clock { font-size: 36px; font-weight: 900; color: #38bdf8; font-family: monospace; letter-spacing: 2px; }\n"
    ".timeline-meta { font-size: 13px; color: #94a3b8; font-family: monospace; }\n"
    ".timeline-status { font-size: 13px; font-weight: bold; color: #6ee7b7; }\n"
    "progressbar.timeline-bar trough { background-color: #0f172a; border-radius: 6px; min-height: 12px; border: 1px solid #334155; }\n"
    "progressbar.timeline-bar progress { background: linear-gradient(90deg, #0284c7 0%, #38bdf8 50%, #818cf8 100%); border-radius: 5px; min-height: 12px; }\n"
    "entry { background-color: #1e222b; color: #f3f4f6; border: 1px solid #374151; border-radius: 6px; padding: 6px 10px; }\n"
    "entry:focus { border-color: #38bdf8; }\n"
    "textview text { background-color: #111318; color: #94a3b8; font-family: monospace; font-size: 12px; }\n"
    "treeview { background-color: #14171e; color: #e2e8f0; font-size: 13px; }\n"
    "treeview:selected { background-color: #0369a1; color: #ffffff; }\n"
    "button.btn-scan { background: #4338ca; color: white; font-weight: bold; border-radius: 6px; padding: 8px 14px; border: none; }\n"
    "button.btn-scan:hover { background: #6366f1; }\n"
    ".transfer-card { background: linear-gradient(135deg, #111827 0%, #1e293b 100%); border: 1px solid #3b82f6; border-radius: 10px; padding: 12px; margin: 4px; }\n"
    "progressbar.transfer-bar trough { background-color: #0f172a; border-radius: 4px; min-height: 8px; border: 1px solid #334155; }\n"
    "progressbar.transfer-bar progress { background: linear-gradient(90deg, #0284c7 0%, #10b981 100%); border-radius: 3px; min-height: 8px; }\n";

/* ========================================================================= */
/* Global GUI State                                                          */
/* ========================================================================= */
typedef struct {
    GtkWidget *window;
    GtkWidget *notebook;
    GtkWidget *lbl_top_clock;

    /* --- Client Widgets --- */
    GtkWidget *entry_client_ip;
    GtkWidget *entry_client_audio_port;
    GtkWidget *entry_client_sync_port;
    GtkWidget *entry_client_ping_port;
    GtkWidget *btn_client_toggle;
    GtkWidget *btn_client_scan;
    GtkWidget *lbl_client_status_badge;

    /* Clocks & Metrics */
    GtkWidget *lbl_client_clock_local;
    GtkWidget *lbl_client_clock_host;
    GtkWidget *lbl_client_clock_offset;
    GtkWidget *lbl_metric_rtt;
    GtkWidget *lbl_metric_latency;
    GtkWidget *lbl_metric_drift;

    /* Timeline Sync Widgets */
    GtkWidget *lbl_timeline_clock;
    GtkWidget *lbl_timeline_start_time;
    GtkWidget *lbl_timeline_drift;
    GtkWidget *lbl_timeline_status;
    GtkWidget *progress_timeline;
    GtkWidget *btn_timeline_pick_song;
    GtkWidget *btn_timeline_resync;
    GtkWidget *btn_timeline_test;
    GtkWidget *btn_timeline_lock;
    GtkWidget *lbl_nudge_display;

    /* File Transfer Card */
    GtkWidget *card_transfer;
    GtkWidget *lbl_transfer_status;
    GtkWidget *lbl_transfer_file;
    GtkWidget *progress_transfer;
    GtkWidget *lbl_transfer_percent;
    GtkWidget *lbl_transfer_speed;

    /* Live Stream & VU Meter */
    GtkWidget *lbl_stream_info;
    GtkWidget *progress_vu;

    /* Now Playing (Client) */
    GtkWidget *lbl_song_title;
    GtkWidget *lbl_song_artist;
    GtkWidget *lbl_song_time;
    GtkWidget *progress_song;
    GtkWidget *btn_play_pause;
    GtkWidget *btn_skip_prev;
    GtkWidget *btn_skip_next;
    GtkWidget *lbl_sync_accuracy;

    /* Playlist Queue (Client) */
    GtkWidget *btn_client_add_song;
    GtkListStore *store_queue;
    GtkWidget *tree_queue;
    GtkTextBuffer *buf_client_log;

    /* Synced Timeline State */
    volatile int64_t synced_start_time_host;
    volatile int64_t synced_start_time_local;
    volatile int64_t synced_timeline_pos_ms;
    volatile bool synced_is_playing;
    volatile int64_t synced_drift_ms;
    volatile bool sync_time_established;
    volatile bool sync_time_locked;
    volatile int64_t manual_sync_nudge_ms;
    char loaded_song_path[4096];
    char loaded_song_title[256];
    int64_t loaded_song_duration_ms;

    /* --- Server Widgets --- */
    GtkWidget *entry_srv_sync_port;
    GtkWidget *entry_srv_ping_port;
    GtkWidget *entry_srv_interval;
    GtkWidget *btn_srv_toggle;
    GtkWidget *lbl_srv_status_badge;
    GtkWidget *lbl_srv_timeline;
    GtkWidget *lbl_srv_clients;
    GtkWidget *lbl_srv_now_playing;
    GtkWidget *btn_srv_play_pause;
    GtkWidget *btn_srv_prev;
    GtkWidget *btn_srv_next;
    GtkListStore *store_srv_queue;
    GtkWidget *tree_srv_queue;
    GtkTextBuffer *buf_srv_log;

    /* Client Sessions & Playback State */
    volatile bool client_active;
    pthread_t client_sync_thread;
    pthread_t client_ping_thread;
    pthread_t client_meta_thread;
    int client_sync_fd;
    int client_ping_fd;
    int client_meta_fd;
    int64_t client_offset_ms;
    bool client_calibrated;
    int64_t client_smoothed_latency;
    int64_t client_last_rtt;

    /* Live Audio Stream Stats */
    volatile bool live_stream_running;
    volatile double live_peak_level;
    volatile int64_t live_kbps;
    volatile int64_t live_total_bytes;
    char live_format_desc[128];
    pthread_mutex_t live_stats_mutex;

    /* Song Queue */
    song_item_t client_queue[MAX_SONG_QUEUE];
    int client_queue_count;
    int client_playing_index;
    pthread_mutex_t client_queue_mutex;

    /* Server State */
    volatile bool server_active;
    pthread_t server_thread;
    int server_sync_fd;
    int server_ping_fd;
    int64_t server_start_time_ms;
    int server_connected_clients;
    song_item_t server_queue[MAX_SONG_QUEUE];
    int server_queue_count;
    int server_playing_index;
    pthread_mutex_t server_queue_mutex;
} app_state_t;

static app_state_t g_app;

enum {
    COL_INDEX = 0,
    COL_TITLE,
    COL_ARTIST,
    COL_DURATION,
    COL_STATUS,
    NUM_COLS
};

/* ========================================================================= */
/* Logging Helper (Thread-Safe)                                              */
/* ========================================================================= */
typedef struct {
    GtkTextBuffer *buffer;
    char *text;
} log_msg_t;

static gboolean idle_append_log(gpointer user_data) {
    log_msg_t *msg = (log_msg_t *)user_data;
    if (msg && msg->buffer && msg->text) {
        GtkTextIter end;
        gtk_text_buffer_get_end_iter(msg->buffer, &end);
        gtk_text_buffer_insert(msg->buffer, &end, msg->text, -1);
        gtk_text_buffer_insert(msg->buffer, &end, "\n", -1);
    }
    if (msg) {
        g_free(msg->text);
        g_free(msg);
    }
    return G_SOURCE_REMOVE;
}

static void log_msg(GtkTextBuffer *buffer, const char *fmt, ...) {
    char temp[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(temp, sizeof(temp), fmt, args);
    va_end(args);

    char time_str[32];
    format_time_str(time_now_ms(), time_str, sizeof(time_str));

    char full[600];
    snprintf(full, sizeof(full), "[%s] %s", time_str, temp);

    log_msg_t *m = g_new0(log_msg_t, 1);
    m->buffer = buffer;
    m->text = g_strdup(full);
    g_idle_add(idle_append_log, m);
}

/* ========================================================================= */
/* UI Update Dispatchers for Songs & Playback                                */
/* ========================================================================= */
typedef struct {
    int index;
    char title[256];
    char artist[128];
    char duration[32];
    char status[64];
} queue_row_update_t;

static gboolean idle_update_queue_ui(gpointer user_data) {
    queue_row_update_t *r = (queue_row_update_t *)user_data;
    GtkTreeIter iter;

    bool found = false;
    GtkTreeModel *model = GTK_TREE_MODEL(g_app.store_queue);
    if (gtk_tree_model_get_iter_first(model, &iter)) {
        do {
            int idx = -1;
            gtk_tree_model_get(model, &iter, COL_INDEX, &idx, -1);
            if (idx == r->index) {
                found = true;
                gtk_list_store_set(g_app.store_queue, &iter,
                                   COL_TITLE, r->title,
                                   COL_ARTIST, r->artist,
                                   COL_DURATION, r->duration,
                                   COL_STATUS, r->status, -1);
                break;
            }
        } while (gtk_tree_model_iter_next(model, &iter));
    }

    if (!found) {
        gtk_list_store_append(g_app.store_queue, &iter);
        gtk_list_store_set(g_app.store_queue, &iter,
                           COL_INDEX, r->index,
                           COL_TITLE, r->title,
                           COL_ARTIST, r->artist,
                           COL_DURATION, r->duration,
                           COL_STATUS, r->status, -1);
    }

    g_free(r);
    return G_SOURCE_REMOVE;
}

static void update_client_queue_row(int index, const char *status) {
    if (index < 0 || index >= g_app.client_queue_count) return;
    queue_row_update_t *r = g_new0(queue_row_update_t, 1);
    r->index = index;
    snprintf(r->title, sizeof(r->title), "%s", g_app.client_queue[index].file_name);
    snprintf(r->artist, sizeof(r->artist), "%s", g_app.client_queue[index].artist);
    snprintf(r->duration, sizeof(r->duration), "%s", g_app.client_queue[index].duration_str);
    snprintf(r->status, sizeof(r->status), "%s", status);
    g_idle_add(idle_update_queue_ui, r);
}

typedef struct {
    char title[256];
    char artist[256];
    bool is_playing;
} now_playing_ui_t;

static gboolean idle_update_now_playing(gpointer user_data) {
    now_playing_ui_t *np = (now_playing_ui_t *)user_data;
    gtk_label_set_text(GTK_LABEL(g_app.lbl_song_title), np->title);
    gtk_label_set_text(GTK_LABEL(g_app.lbl_song_artist), np->artist);

    if (np->is_playing) {
        gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "⏸ Pause");
    } else {
        gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "▶ Play");
    }

    g_free(np);
    return G_SOURCE_REMOVE;
}

static void post_now_playing(const char *title, const char *artist, bool is_playing) {
    now_playing_ui_t *np = g_new0(now_playing_ui_t, 1);
    snprintf(np->title, sizeof(np->title), "%s", title);
    snprintf(np->artist, sizeof(np->artist), "%s", artist);
    np->is_playing = is_playing;
    g_idle_add(idle_update_now_playing, np);
}

/* ========================================================================= */
/* Live Stream Audio Callbacks (PulseAudio / Port 5000)                      */
/* ========================================================================= */
static void on_stream_stats_cb(const soundshare_audio_config_t *cfg,
                               int64_t kbps, int64_t total_bytes, double peak_level) {
    pthread_mutex_lock(&g_app.live_stats_mutex);
    g_app.live_kbps = kbps;
    g_app.live_total_bytes = total_bytes;
    g_app.live_peak_level = peak_level;
    if (cfg) {
        snprintf(g_app.live_format_desc, sizeof(g_app.live_format_desc),
                 "%d Hz | %d-bit | %s | %s",
                 cfg->sample_rate, cfg->bits_per_sample,
                 cfg->channels == 2 ? "Stereo" : "Mono",
                 cfg->compression_type == 1 ? "FLAC" : "PCM");
    }
    pthread_mutex_unlock(&g_app.live_stats_mutex);
}

typedef struct {
    bool is_connected;
    char msg[128];
} stream_state_ui_t;

static gboolean idle_update_stream_state(gpointer user_data) {
    stream_state_ui_t *st = (stream_state_ui_t *)user_data;
    if (st) {
        if (st->is_connected) {
            g_app.live_stream_running = true;
            gtk_label_set_text(GTK_LABEL(g_app.lbl_client_status_badge), "● Live Audio Streaming");
            GtkStyleContext *sc = gtk_widget_get_style_context(g_app.lbl_client_status_badge);
            gtk_style_context_remove_class(sc, "badge-disconnected");
            gtk_style_context_remove_class(sc, "badge-calibrating");
            gtk_style_context_add_class(sc, "badge-synced");
        } else {
            g_app.live_stream_running = false;
        }
        g_free(st);
    }
    return G_SOURCE_REMOVE;
}

static void on_stream_state_cb(bool is_connected, const char *msg) {
    stream_state_ui_t *st = g_new0(stream_state_ui_t, 1);
    st->is_connected = is_connected;
    snprintf(st->msg, sizeof(st->msg), "%s", msg ? msg : "");
    g_idle_add(idle_update_stream_state, st);
    log_msg(g_app.buf_client_log, "[AUDIO STREAM] %s", msg ? msg : "");
}

/* ========================================================================= */
/* Remote Transport Control Helper                                           */
/* ========================================================================= */
static void send_client_control(uint8_t ctrl_code) {
    if (g_app.client_ping_fd >= 0) {
        send_u8(g_app.client_ping_fd, ctrl_code);
    }
}

/* ========================================================================= */
/* Ping & Latency Worker Thread (Port 5001)                                  */
/* ========================================================================= */
static void *client_ping_worker(void *arg) {
    char host_ip[64];
    strncpy(host_ip, (const char *)arg, sizeof(host_ip) - 1);
    host_ip[sizeof(host_ip) - 1] = '\0';
    free(arg);

    int ping_port = atoi(gtk_entry_get_text(GTK_ENTRY(g_app.entry_client_ping_port)));
    if (ping_port <= 0) ping_port = SOUNDSHARE_PING_PORT;

    int ping_fd = net_connect_tcp(host_ip, ping_port, 2);
    if (ping_fd < 0) {
        return NULL;
    }
    g_app.client_ping_fd = ping_fd;
    net_set_tcp_nodelay(ping_fd, true);

    while (g_app.client_active) {
        int64_t s_ns = time_now_ns();
        if (send_u8(ping_fd, PING_REQUEST) != 0) break;

        uint8_t pong = 0;
        if (recv_u8(ping_fd, &pong) != 0 || pong != PING_RESPONSE) break;

        int64_t rtt_ms = (time_now_ns() - s_ns) / 1000000LL;
        int64_t one_way = rtt_ms / 2;
        g_app.client_last_rtt = rtt_ms;
        g_app.client_smoothed_latency = (g_app.client_smoothed_latency < 0) ?
            one_way : (int64_t)(g_app.client_smoothed_latency * 0.7 + one_way * 0.3);

        send_u8(ping_fd, LATENCY_REPORT);
        send_u64(ping_fd, (uint64_t)g_app.client_smoothed_latency);

        time_sleep_ms(500);
    }

    close(ping_fd);
    g_app.client_ping_fd = -1;
    return NULL;
}

/* ========================================================================= */
/* Metadata Worker Thread (Port 5002)                                        */
/* ========================================================================= */
static void *client_meta_worker(void *arg) {
    char host_ip[64];
    strncpy(host_ip, (const char *)arg, sizeof(host_ip) - 1);
    host_ip[sizeof(host_ip) - 1] = '\0';
    free(arg);

    int meta_fd = net_connect_tcp(host_ip, SOUNDSHARE_CHAT_PORT, 2);
    if (meta_fd < 0) {
        return NULL;
    }
    g_app.client_meta_fd = meta_fd;

    while (g_app.client_active) {
        uint8_t cmd = 0;
        if (recv_u8(meta_fd, &cmd) != 0) break;

        if (cmd == CHAT_METADATA) {
            uint16_t t_len = 0;
            if (recv_u16(meta_fd, &t_len) != 0) break;
            char title[256];
            size_t rd_t = t_len < sizeof(title) ? t_len : sizeof(title) - 1;
            if (net_read_exact(meta_fd, (uint8_t *)title, rd_t) != (ssize_t)rd_t) break;
            title[rd_t] = '\0';
            if (t_len > rd_t) {
                size_t rem = t_len - rd_t;
                uint8_t junk[128];
                while (rem > 0) {
                    size_t chunk = rem < sizeof(junk) ? rem : sizeof(junk);
                    if (net_read_exact(meta_fd, junk, chunk) != (ssize_t)chunk) break;
                    rem -= chunk;
                }
            }

            uint16_t a_len = 0;
            if (recv_u16(meta_fd, &a_len) != 0) break;
            char artist[256];
            size_t rd_a = a_len < sizeof(artist) ? a_len : sizeof(artist) - 1;
            if (net_read_exact(meta_fd, (uint8_t *)artist, rd_a) != (ssize_t)rd_a) break;
            artist[rd_a] = '\0';
            if (a_len > rd_a) {
                size_t rem = a_len - rd_a;
                uint8_t junk[128];
                while (rem > 0) {
                    size_t chunk = rem < sizeof(junk) ? rem : sizeof(junk);
                    if (net_read_exact(meta_fd, junk, chunk) != (ssize_t)chunk) break;
                    rem -= chunk;
                }
            }

            uint16_t al_len = 0;
            if (recv_u16(meta_fd, &al_len) != 0) break;
            char album[256];
            size_t rd_al = al_len < sizeof(album) ? al_len : sizeof(album) - 1;
            if (net_read_exact(meta_fd, (uint8_t *)album, rd_al) != (ssize_t)rd_al) break;
            album[rd_al] = '\0';
            if (al_len > rd_al) {
                size_t rem = al_len - rd_al;
                uint8_t junk[128];
                while (rem > 0) {
                    size_t chunk = rem < sizeof(junk) ? rem : sizeof(junk);
                    if (net_read_exact(meta_fd, junk, chunk) != (ssize_t)chunk) break;
                    rem -= chunk;
                }
            }

            uint64_t dur_ms = 0, pos_ms = 0;
            if (recv_u64(meta_fd, &dur_ms) != 0 || recv_u64(meta_fd, &pos_ms) != 0) break;

            uint32_t art_len = 0;
            if (recv_u32(meta_fd, &art_len) != 0) break;
            if (art_len > 0 && art_len <= 10485760) {
                uint8_t *art_buf = (uint8_t *)malloc(art_len);
                if (art_buf) {
                    net_read_exact(meta_fd, art_buf, art_len);
                    free(art_buf);
                }
            }

            log_msg(g_app.buf_client_log, "[METADATA] Now Playing: '%s' by '%s' (%s)",
                    title, artist, album);
            post_now_playing(title, artist, true);
        } else if (cmd == CHAT_MSG) {
            uint16_t msg_len = 0;
            if (recv_u16(meta_fd, &msg_len) != 0) break;
            char msg_txt[1024];
            size_t rd_m = msg_len < sizeof(msg_txt) ? msg_len : sizeof(msg_txt) - 1;
            net_read_exact(meta_fd, (uint8_t *)msg_txt, rd_m);
            msg_txt[rd_m] = '\0';
            log_msg(g_app.buf_client_log, "[CHAT] %s", msg_txt);
        }
    }

    close(meta_fd);
    g_app.client_meta_fd = -1;
    return NULL;
}

/* ========================================================================= */
/* UDP Host Auto-Discovery & File Transfer UI                                */
/* ========================================================================= */
typedef struct {
    char found_ip[64];
    bool success;
} scan_ui_t;

static gboolean on_scan_done_ui(gpointer data) {
    scan_ui_t *s = (scan_ui_t *)data;
    if (s->success) {
        gtk_entry_set_text(GTK_ENTRY(g_app.entry_client_ip), s->found_ip);
        log_msg(g_app.buf_client_log, "✓ Auto-discovered SoundShare Host at %s (UDP %d)", s->found_ip, SOUNDSHARE_DISCOVERY_PORT);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_client_status_badge), "● Host Found");
    } else {
        log_msg(g_app.buf_client_log, "⚠ No host responded on UDP %d. Ensure Host is running, or enter IP manually.", SOUNDSHARE_DISCOVERY_PORT);
    }
    if (g_app.btn_client_scan) {
        gtk_widget_set_sensitive(g_app.btn_client_scan, TRUE);
        gtk_button_set_label(GTK_BUTTON(g_app.btn_client_scan), "🔍 Auto-Scan Host");
    }
    free(s);
    return G_SOURCE_REMOVE;
}

static void *scan_thread_worker(void *arg) {
    (void)arg;
    char out_ip[64] = "";
    int rc = net_discover_host(SOUNDSHARE_DISCOVERY_PORT, SS_DISCOVER_FILE, SS_HOST_FILE_PREFIX, out_ip, sizeof(out_ip), 2500);
    scan_ui_t *s = g_new0(scan_ui_t, 1);
    if (rc == 0 && out_ip[0] != '\0') {
        snprintf(s->found_ip, sizeof(s->found_ip), "%s", out_ip);
        s->success = true;
    } else {
        s->success = false;
    }
    g_idle_add(on_scan_done_ui, s);
    return NULL;
}

static void on_client_scan_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    if (g_app.btn_client_scan) {
        gtk_widget_set_sensitive(g_app.btn_client_scan, FALSE);
        gtk_button_set_label(GTK_BUTTON(g_app.btn_client_scan), "Scanning...");
    }
    log_msg(g_app.buf_client_log, "Broadcasting UDP query 'SS_DISCOVER_FILE' on Port %d...", SOUNDSHARE_DISCOVERY_PORT);

    pthread_t th;
    pthread_create(&th, NULL, scan_thread_worker, NULL);
    pthread_detach(th);
}

typedef struct {
    char track_id[128];
    int percent;
    double speed_mbps;
    char file_name[256];
    bool is_complete;
} transfer_ui_event_t;

static gboolean on_transfer_ui_update(gpointer data) {
    transfer_ui_event_t *ev = (transfer_ui_event_t *)data;
    if (!ev) return G_SOURCE_REMOVE;

    if (g_app.progress_transfer && g_app.lbl_transfer_percent && g_app.lbl_transfer_speed && g_app.lbl_transfer_file) {
        if (ev->is_complete) {
            gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(g_app.progress_transfer), 1.0);
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_percent), "100%");
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_speed), "✓ Ready");
            char buf[256];
            snprintf(buf, sizeof(buf), "✓ All songs synced: %s", ev->file_name);
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_file), buf);
            if (g_app.lbl_transfer_status) gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_status), "Transfer Complete");
        } else {
            double frac = (double)ev->percent / 100.0;
            if (frac < 0.0) frac = 0.0;
            if (frac > 1.0) frac = 1.0;
            gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(g_app.progress_transfer), frac);

            char pbuf[32];
            snprintf(pbuf, sizeof(pbuf), "%d%%", ev->percent);
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_percent), pbuf);

            char sbuf[32];
            if (ev->speed_mbps > 0.01) snprintf(sbuf, sizeof(sbuf), "%.1f MB/s", ev->speed_mbps);
            else sbuf[0] = '\0';
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_speed), sbuf);

            char fbuf[256];
            snprintf(fbuf, sizeof(fbuf), "Downloading: %s", ev->file_name);
            gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_file), fbuf);
            if (g_app.lbl_transfer_status) gtk_label_set_text(GTK_LABEL(g_app.lbl_transfer_status), "Transferring Song...");
        }
    }

    free(ev);
    return G_SOURCE_REMOVE;
}

static void on_dl_progress_cb(const char *track_id, int percent, double speed_mbps) {
    transfer_ui_event_t *ev = g_new0(transfer_ui_event_t, 1);
    snprintf(ev->track_id, sizeof(ev->track_id), "%s", track_id);
    ev->percent = percent;
    ev->speed_mbps = speed_mbps;
    ev->is_complete = (percent >= 100);
    g_idle_add(on_transfer_ui_update, ev);
}

/* ========================================================================= */
/* Song File Download Worker                                                 */
/* ========================================================================= */
typedef struct {
    char ip[64];
    int port;
    char id[128];
    char name[256];
    int64_t size;
    int sync_fd;
} client_dl_job_t;

static void *client_song_dl_thread(void *arg) {
    client_dl_job_t *job = (client_dl_job_t *)arg;
    log_msg(g_app.buf_client_log, "Downloading '%s' from host transfer port %d...", job->name, job->port);

    pthread_mutex_lock(&g_app.client_queue_mutex);
    int target_idx = -1;
    for (int i = 0; i < g_app.client_queue_count; i++) {
        if (strcmp(g_app.client_queue[i].id, job->id) == 0) {
            target_idx = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_app.client_queue_mutex);

    if (target_idx >= 0) {
        update_client_queue_row(target_idx, "⬇ Downloading...");
    }

    /* Start transfer UI display */
    transfer_ui_event_t *init_ev = g_new0(transfer_ui_event_t, 1);
    snprintf(init_ev->track_id, sizeof(init_ev->track_id), "%s", job->id);
    snprintf(init_ev->file_name, sizeof(init_ev->file_name), "%s", job->name);
    init_ev->percent = 0;
    init_ev->is_complete = false;
    g_idle_add(on_transfer_ui_update, init_ev);

    int rc = song_transfer_receive(job->ip, job->port, job->id, job->name, job->size, on_dl_progress_cb, NULL);

    if (rc == 0) {
        char local_path[2048];
        snprintf(local_path, sizeof(local_path), "received_songs/%s_%s", job->id, job->name);
        log_msg(g_app.buf_client_log, "✓ Download complete: %s", job->name);

        pthread_mutex_lock(&g_app.client_queue_mutex);
        if (target_idx >= 0) {
            snprintf(g_app.client_queue[target_idx].local_path, sizeof(g_app.client_queue[target_idx].local_path), "%s", local_path);
            g_app.client_queue[target_idx].is_ready = true;
            update_client_queue_row(target_idx, "✓ Ready");

            if (g_app.client_playing_index == target_idx && !audio_player_is_playing()) {
                audio_player_load(local_path);
                audio_player_play();
                post_now_playing(g_app.client_queue[target_idx].file_name, g_app.client_queue[target_idx].artist, true);
            }
        }
        pthread_mutex_unlock(&g_app.client_queue_mutex);

        transfer_ui_event_t *comp_ev = g_new0(transfer_ui_event_t, 1);
        snprintf(comp_ev->track_id, sizeof(comp_ev->track_id), "%s", job->id);
        snprintf(comp_ev->file_name, sizeof(comp_ev->file_name), "%s", job->name);
        comp_ev->percent = 100;
        comp_ev->is_complete = true;
        g_idle_add(on_transfer_ui_update, comp_ev);

        if (job->sync_fd >= 0) {
            send_u8(job->sync_fd, FILE_READY);
            send_string(job->sync_fd, job->id);
        }
    } else {
        log_msg(g_app.buf_client_log, "✗ Download failed for %s", job->name);
        if (target_idx >= 0) update_client_queue_row(target_idx, "✗ Failed");
    }

    free(job);
    return NULL;
}

/* ========================================================================= */
/* Timeline Calculation & Sync Dispatcher                                    */
/* ========================================================================= */
static void apply_client_synced_start_time(int64_t host_start_time) {
    g_app.synced_start_time_host = host_start_time;
    g_app.synced_start_time_local = host_start_time - g_app.client_offset_ms + g_app.manual_sync_nudge_ms;
    g_app.synced_is_playing = true;
    g_app.sync_time_established = true;

    int64_t now = time_now_ms();
    int64_t timeline_pos = now - g_app.synced_start_time_local;
    g_app.synced_timeline_pos_ms = timeline_pos;

    log_msg(g_app.buf_client_log, "[TIMELINE] ✓ Established Sync Time: Host T₀: %ld ms | Local T₀: %ld ms [🔒 LOCKED] | Timeline: %02ld:%02ld.%03ld",
            (long)host_start_time, (long)g_app.synced_start_time_local,
            (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));

    if (audio_player_is_loaded()) {
        if (timeline_pos < 0) {
            audio_player_schedule_play(0, g_app.synced_start_time_local);
            log_msg(g_app.buf_client_log, "[TIMELINE] Track starts in %ld ms at %+ld ms local time",
                    (long)(-timeline_pos), (long)g_app.synced_start_time_local);
        } else {
            if (!audio_player_is_playing()) {
                audio_player_schedule_play(timeline_pos, now);
                log_msg(g_app.buf_client_log, "[TIMELINE] Started audio player at calculated timeline %02ld:%02ld.%03ld",
                        (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));
            } else {
                int64_t actual_pos = audio_player_get_position_ms();
                int64_t drift = timeline_pos - actual_pos;
                g_app.synced_drift_ms = drift;
                if (llabs(drift) > 400) {
                    audio_player_seek(timeline_pos);
                    audio_player_adjust_speed(1.0);
                    log_msg(g_app.buf_client_log, "[TIMELINE] Hard sync seek to %ld ms (drift %+ld ms)", (long)timeline_pos, (long)drift);
                } else if (llabs(drift) > 25) {
                    audio_player_adjust_speed(drift > 0 ? 1.02 : 0.98);
                } else {
                    audio_player_adjust_speed(1.0);
                }
            }
        }
    } else {
        log_msg(g_app.buf_client_log, "[TIMELINE] Timeline calculated: %02ld:%02ld.%03ld. Click 'Select Local Song File' to play in sync!",
                (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));
    }
}

/* ========================================================================= */
/* Time-Sync Control Worker Thread (Port 5006)                               */
/* ========================================================================= */
static void *client_sync_worker(void *arg) {
    char host_ip[64];
    strncpy(host_ip, (const char *)arg, sizeof(host_ip) - 1);
    host_ip[sizeof(host_ip) - 1] = '\0';
    free(arg);

    int sync_port = atoi(gtk_entry_get_text(GTK_ENTRY(g_app.entry_client_sync_port)));
    if (sync_port <= 0) sync_port = SOUNDSHARE_SYNC_CTRL_PORT;

    int sync_fd = net_connect_tcp(host_ip, sync_port, 3);
    if (sync_fd < 0) {
        log_msg(g_app.buf_client_log, "Sync port %d not active on host (Standard Streamer mode active)", sync_port);
        return NULL;
    }
    g_app.client_sync_fd = sync_fd;
    net_set_tcp_nodelay(sync_fd, true);

    log_msg(g_app.buf_client_log, "Connected to Sync Control port %d. Running NTP calibration...", sync_port);

    /* NTP Calibration */
    int samples = 8;
    int64_t *offsets = (int64_t *)malloc(sizeof(int64_t) * (size_t)samples);
    int valid = 0;
    int64_t min_rtt = 9999999, total_rtt = 0;

    for (int i = 0; i < samples && g_app.client_active; i++) {
        int64_t t1 = time_now_ms();
        if (send_u8(sync_fd, SYNC_CLOCK_REQ) != 0 || send_u64(sync_fd, (uint64_t)t1) != 0) break;

        uint8_t rsp;
        uint64_t orig_t1, t2_u64, t3_u64;
        if (recv_u8(sync_fd, &rsp) != 0 || rsp != SYNC_CLOCK_RSP ||
            recv_u64(sync_fd, &orig_t1) != 0 ||
            recv_u64(sync_fd, &t2_u64) != 0 ||
            recv_u64(sync_fd, &t3_u64) != 0) break;

        int64_t t4 = time_now_ms();
        int64_t t2 = (int64_t)t2_u64;
        int64_t t3 = (int64_t)t3_u64;

        int64_t offset = ((t2 - t1) + (t3 - t4)) / 2;
        int64_t rtt = (t4 - t1) - (t3 - t2);
        if (rtt < 0) rtt = t4 - t1;

        offsets[valid++] = offset;
        total_rtt += rtt;
        if (rtt < min_rtt) min_rtt = rtt;

        log_msg(g_app.buf_client_log, "  NTP Sample #%d: offset=%+ld ms, delay=%ld ms", i + 1, (long)offset, (long)rtt);
        time_sleep_ms(25);
    }

    if (valid == 0 || !g_app.client_active) {
        free(offsets);
        close(sync_fd);
        g_app.client_sync_fd = -1;
        return NULL;
    }

    /* Outlier rejection: sort and median */
    for (int i = 0; i < valid - 1; i++) {
        for (int j = i + 1; j < valid; j++) {
            if (offsets[i] > offsets[j]) {
                int64_t tmp = offsets[i];
                offsets[i] = offsets[j];
                offsets[j] = tmp;
            }
        }
    }
    int64_t calib_offset = (valid >= 4) ? (offsets[valid/2 - 1] + offsets[valid/2]) / 2 : offsets[valid/2];
    free(offsets);

    g_app.client_offset_ms = calib_offset;
    g_app.client_calibrated = true;

    log_msg(g_app.buf_client_log, "✓ Clock Synchronized! Offset: %+ld ms | Avg RTT: %ld ms",
            (long)calib_offset, (long)(total_rtt / valid));

    /* Request initial playlist queue */
    send_u8(sync_fd, SYNC_INIT_REQ);

    while (g_app.client_active) {
        uint8_t cmd;
        if (recv_u8(sync_fd, &cmd) != 0) break;

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
                    recv_string(sync_fd, q_fmt, sizeof(q_fmt)) != 0) goto exit_sync;

                pthread_mutex_lock(&g_app.client_queue_mutex);
                int idx = -1;
                for (int i = 0; i < g_app.client_queue_count; i++) {
                    if (strcmp(g_app.client_queue[i].id, q_id) == 0) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0 && g_app.client_queue_count < MAX_SONG_QUEUE) {
                    idx = g_app.client_queue_count++;
                    song_item_t *item = &g_app.client_queue[idx];
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

                    log_msg(g_app.buf_client_log, "Added to playlist: '%s' by '%s'", q_name, q_artist);
                    update_client_queue_row(idx, "Pending Download");
                }
                pthread_mutex_unlock(&g_app.client_queue_mutex);
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
                    recv_u32(sync_fd, &tr_port) != 0) goto exit_sync;

                client_dl_job_t *job = g_new0(client_dl_job_t, 1);
                snprintf(job->ip, sizeof(job->ip), "%s", host_ip);
                job->port = (int)tr_port;
                snprintf(job->id, sizeof(job->id), "%s", tr_id);
                snprintf(job->name, sizeof(job->name), "%s", tr_name);
                job->size = (int64_t)tr_size;
                job->sync_fd = sync_fd;

                pthread_t dl_th;
                pthread_create(&dl_th, NULL, client_song_dl_thread, job);
                pthread_detach(dl_th);
                break;
            }

            case FILE_READY: {
                char r_id[128];
                if (recv_string(sync_fd, r_id, sizeof(r_id)) == 0) {
                    pthread_mutex_lock(&g_app.client_queue_mutex);
                    for (int i = 0; i < g_app.client_queue_count; i++) {
                        if (strcmp(g_app.client_queue[i].id, r_id) == 0) {
                            g_app.client_queue[i].is_ready = true;
                            update_client_queue_row(i, "✓ Ready");
                            break;
                        }
                    }
                    pthread_mutex_unlock(&g_app.client_queue_mutex);
                }
                break;
            }

            case SYNC_TRACK_CHANGE: {
                uint32_t track_idx;
                uint64_t play_at_u64;
                if (recv_u32(sync_fd, &track_idx) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_sync;

                int64_t play_at = (int64_t)play_at_u64;
                int idx = (int)track_idx;

                pthread_mutex_lock(&g_app.client_queue_mutex);
                g_app.client_playing_index = idx;
                if (idx >= 0 && idx < g_app.client_queue_count && g_app.client_queue[idx].is_ready) {
                    audio_player_load(g_app.client_queue[idx].local_path);
                    post_now_playing(g_app.client_queue[idx].file_name, g_app.client_queue[idx].artist, true);
                    update_client_queue_row(idx, "▶ Playing in Sync");
                }
                pthread_mutex_unlock(&g_app.client_queue_mutex);

                g_app.sync_time_established = false;
                apply_client_synced_start_time(play_at);
                break;
            }

            case SYNC_PLAY: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_sync;
                int64_t host_start_time = (int64_t)play_at_u64 - (int64_t)pos_u64;
                g_app.sync_time_established = false;
                apply_client_synced_start_time(host_start_time);
                break;
            }

            case SYNC_PAUSE_CMD: {
                uint64_t pos_u64, pause_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &pause_at_u64) != 0) goto exit_sync;
                g_app.synced_is_playing = false;
                g_app.synced_timeline_pos_ms = (int64_t)pos_u64;
                audio_player_pause();
                log_msg(g_app.buf_client_log, "[TIMELINE] Host paused playback at timeline pos %ld ms", (long)pos_u64);
                break;
            }

            case SYNC_SEEK: {
                uint64_t pos_u64, play_at_u64;
                if (recv_u64(sync_fd, &pos_u64) != 0 || recv_u64(sync_fd, &play_at_u64) != 0) goto exit_sync;
                int64_t host_start_time = (int64_t)play_at_u64 - (int64_t)pos_u64;
                g_app.sync_time_established = false;
                apply_client_synced_start_time(host_start_time);
                break;
            }

            case SYNC_TIME_PULSE: {
                uint32_t pulse_idx;
                uint64_t host_pos_u64, host_time_u64;
                if (recv_u32(sync_fd, &pulse_idx) != 0 ||
                    recv_u64(sync_fd, &host_pos_u64) != 0 ||
                    recv_u64(sync_fd, &host_time_u64) != 0) goto exit_sync;

                int p_idx = (int)pulse_idx;
                pthread_mutex_lock(&g_app.client_queue_mutex);
                if (p_idx >= 0 && p_idx < g_app.client_queue_count && g_app.client_queue[p_idx].is_ready) {
                    if (!audio_player_is_loaded() || g_app.client_playing_index != p_idx) {
                        g_app.client_playing_index = p_idx;
                        audio_player_load(g_app.client_queue[p_idx].local_path);
                        post_now_playing(g_app.client_queue[p_idx].file_name, g_app.client_queue[p_idx].artist, true);
                        update_client_queue_row(p_idx, "▶ Playing in Sync");
                        g_app.sync_time_established = false;
                    }
                }
                pthread_mutex_unlock(&g_app.client_queue_mutex);

                int64_t host_start_time = (int64_t)host_time_u64 - (int64_t)host_pos_u64;

                if (!g_app.sync_time_established || !g_app.sync_time_locked) {
                    /* First sync pulse or user unlocked: establish/update sync time */
                    apply_client_synced_start_time(host_start_time);
                } else {
                    /* Sync time is established and locked: LEAVE IT UNCHANGED! */
                    int64_t now = time_now_ms();
                    int64_t timeline_pos = now - g_app.synced_start_time_local;
                    g_app.synced_timeline_pos_ms = timeline_pos;

                    if (audio_player_is_loaded() && audio_player_is_playing()) {
                        int64_t actual_pos = audio_player_get_position_ms();
                        int64_t drift = timeline_pos - actual_pos;
                        g_app.synced_drift_ms = drift;
                        if (llabs(drift) > 400) {
                            audio_player_seek(timeline_pos);
                            audio_player_adjust_speed(1.0);
                            log_msg(g_app.buf_client_log, "[TIMELINE] Audio drift seek: %ld ms (drift %+ld ms)", (long)timeline_pos, (long)drift);
                        } else if (llabs(drift) > 35) {
                            audio_player_adjust_speed(drift > 0 ? 1.02 : 0.98);
                        } else {
                            audio_player_adjust_speed(1.0);
                        }
                    }
                }
                break;
            }

            default:
                break;
        }
    }

exit_sync:
    close(sync_fd);
    g_app.client_sync_fd = -1;
    return NULL;
}

/* ========================================================================= */
/* Client Connection Toggle                                                  */
/* ========================================================================= */
static void on_client_connect_toggle(GtkButton *btn, gpointer data) {
    (void)data;
    if (!g_app.client_active) {
        const char *server_ip = gtk_entry_get_text(GTK_ENTRY(g_app.entry_client_ip));
        if (!server_ip || server_ip[0] == '\0') {
            log_msg(g_app.buf_client_log, "Please enter host IP address!");
            return;
        }

        g_app.client_active = true;
        g_app.sync_time_established = false;
        g_app.sync_time_locked = true;
        g_app.manual_sync_nudge_ms = 0;
        if (g_app.lbl_nudge_display) gtk_label_set_text(GTK_LABEL(g_app.lbl_nudge_display), "Nudge: 0 ms");
        if (g_app.btn_timeline_lock) {
            gtk_button_set_label(GTK_BUTTON(g_app.btn_timeline_lock), "🔒 Sync Time: Locked");
            GtkStyleContext *lsc = gtk_widget_get_style_context(g_app.btn_timeline_lock);
            gtk_style_context_remove_class(lsc, "btn-unlock");
            gtk_style_context_add_class(lsc, "btn-lock");
        }
        gtk_button_set_label(btn, "Disconnect");
        GtkStyleContext *sc = gtk_widget_get_style_context(GTK_WIDGET(btn));
        gtk_style_context_remove_class(sc, "btn-connect");
        gtk_style_context_add_class(sc, "btn-disconnect");

        gtk_label_set_text(GTK_LABEL(g_app.lbl_client_status_badge), "● Connecting...");
        GtkStyleContext *bsc = gtk_widget_get_style_context(g_app.lbl_client_status_badge);
        gtk_style_context_remove_class(bsc, "badge-disconnected");
        gtk_style_context_add_class(bsc, "badge-calibrating");

        int audio_port = atoi(gtk_entry_get_text(GTK_ENTRY(g_app.entry_client_audio_port)));
        if (audio_port <= 0) audio_port = SOUNDSHARE_AUDIO_PORT;

        log_msg(g_app.buf_client_log, "Connecting to host at %s...", server_ip);

        /* 1. Start live audio streaming receiver (Port 5000 / PulseAudio output) */
        stream_receiver_start(server_ip, audio_port, on_stream_stats_cb, on_stream_state_cb);

        /* 2. Start Ping & Latency monitoring client (Port 5001) */
        pthread_create(&g_app.client_ping_thread, NULL, client_ping_worker, g_strdup(server_ip));
        pthread_detach(g_app.client_ping_thread);

        /* 3. Start Chat & Metadata client (Port 5002) */
        pthread_create(&g_app.client_meta_thread, NULL, client_meta_worker, g_strdup(server_ip));
        pthread_detach(g_app.client_meta_thread);

        /* 4. Start Time Sync & File Queue client (Port 5006) */
        pthread_create(&g_app.client_sync_thread, NULL, client_sync_worker, g_strdup(server_ip));
        pthread_detach(g_app.client_sync_thread);

    } else {
        g_app.client_active = false;
        g_app.sync_time_established = false;
        g_app.manual_sync_nudge_ms = 0;
        if (g_app.lbl_nudge_display) gtk_label_set_text(GTK_LABEL(g_app.lbl_nudge_display), "Nudge: 0 ms");
        stream_receiver_stop();
        if (g_app.client_sync_fd >= 0) close(g_app.client_sync_fd);
        if (g_app.client_ping_fd >= 0) close(g_app.client_ping_fd);
        if (g_app.client_meta_fd >= 0) close(g_app.client_meta_fd);
        g_app.client_sync_fd = -1;
        g_app.client_ping_fd = -1;
        g_app.client_meta_fd = -1;
        audio_player_stop();

        g_app.live_stream_running = false;
        g_app.client_calibrated = false;

        gtk_label_set_text(GTK_LABEL(g_app.lbl_client_status_badge), "● Disconnected");
        GtkStyleContext *bsc = gtk_widget_get_style_context(g_app.lbl_client_status_badge);
        gtk_style_context_remove_class(bsc, "badge-synced");
        gtk_style_context_remove_class(bsc, "badge-calibrating");
        gtk_style_context_add_class(bsc, "badge-disconnected");

        gtk_button_set_label(btn, "Connect & Sync");
        GtkStyleContext *sc = gtk_widget_get_style_context(GTK_WIDGET(btn));
        gtk_style_context_remove_class(sc, "btn-disconnect");
        gtk_style_context_add_class(sc, "btn-connect");

        log_msg(g_app.buf_client_log, "Disconnected from host.");
    }
}

/* ========================================================================= */
/* Transport Controls Callbacks                                              */
/* ========================================================================= */
static void on_play_pause_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    if (audio_player_is_playing()) {
        audio_player_pause();
        send_client_control(CTRL_PAUSE);
        if (g_app.client_sync_fd >= 0) {
            send_u8(g_app.client_sync_fd, SYNC_PAUSE_CMD);
            send_u64(g_app.client_sync_fd, (uint64_t)audio_player_get_position_ms());
            send_u64(g_app.client_sync_fd, (uint64_t)(time_now_ms() + g_app.client_offset_ms));
        }
        gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "▶ Play");
    } else {
        if (audio_player_is_loaded()) {
            audio_player_play();
            send_client_control(CTRL_RESUME);
            if (g_app.client_sync_fd >= 0) {
                send_u8(g_app.client_sync_fd, SYNC_PLAY);
                send_u64(g_app.client_sync_fd, (uint64_t)audio_player_get_position_ms());
                send_u64(g_app.client_sync_fd, (uint64_t)(time_now_ms() + g_app.client_offset_ms));
            }
            gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "⏸ Pause");
        } else if (g_app.client_queue_count > 0) {
            int target_idx = g_app.client_playing_index >= 0 ? g_app.client_playing_index : 0;
            if (g_app.client_queue[target_idx].is_ready) {
                audio_player_load(g_app.client_queue[target_idx].local_path);
                audio_player_play();
                post_now_playing(g_app.client_queue[target_idx].file_name,
                                 g_app.client_queue[target_idx].artist, true);
                gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "⏸ Pause");
            } else {
                send_client_control(CTRL_RESUME);
            }
        } else {
            send_client_control(CTRL_RESUME);
        }
    }
}

typedef struct {
    song_item_t item;
    int sync_fd;
} client_upload_arg_t;

static void *client_upload_worker(void *arg) {
    client_upload_arg_t *u = (client_upload_arg_t *)arg;
    int dynamic_port = 0;
    if (song_transfer_send_async(&u->item, &dynamic_port, on_dl_progress_cb) == 0) {
        send_u8(u->sync_fd, SYNC_FILE_INCOMING);
        send_string(u->sync_fd, u->item.id);
        send_string(u->sync_fd, u->item.file_name);
        send_u64(u->sync_fd, (uint64_t)u->item.file_size);
        send_string(u->sync_fd, u->item.mime_type);
        send_u32(u->sync_fd, (uint32_t)dynamic_port);
    }
    free(u);
    return NULL;
}

static void on_stop_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    audio_player_stop();
    send_client_control(CTRL_PAUSE);
    if (g_app.client_sync_fd >= 0) {
        send_u8(g_app.client_sync_fd, SYNC_PAUSE_CMD);
        send_u64(g_app.client_sync_fd, 0);
        send_u64(g_app.client_sync_fd, (uint64_t)(time_now_ms() + g_app.client_offset_ms));
    }
    gtk_button_set_label(GTK_BUTTON(g_app.btn_play_pause), "▶ Play");
}

static void on_skip_prev_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    send_client_control(CTRL_SKIP_BACK);
    if (g_app.client_sync_fd >= 0 && g_app.client_queue_count > 0) {
        int prev = (g_app.client_playing_index - 1 + g_app.client_queue_count) % g_app.client_queue_count;
        int64_t play_at = time_now_ms() + g_app.client_offset_ms + 650;
        send_u8(g_app.client_sync_fd, SYNC_TRACK_CHANGE);
        send_u32(g_app.client_sync_fd, (uint32_t)prev);
        send_u64(g_app.client_sync_fd, (uint64_t)play_at);
        log_msg(g_app.buf_client_log, "Sent remote: Skip Previous (track #%d)", prev);
    } else {
        log_msg(g_app.buf_client_log, "Sent remote: Skip Previous");
    }
}

static void on_skip_next_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    send_client_control(CTRL_SKIP_FWD);
    if (g_app.client_sync_fd >= 0 && g_app.client_queue_count > 0) {
        int next = (g_app.client_playing_index + 1) % g_app.client_queue_count;
        int64_t play_at = time_now_ms() + g_app.client_offset_ms + 650;
        send_u8(g_app.client_sync_fd, SYNC_TRACK_CHANGE);
        send_u32(g_app.client_sync_fd, (uint32_t)next);
        send_u64(g_app.client_sync_fd, (uint64_t)play_at);
        log_msg(g_app.buf_client_log, "Sent remote: Skip Next (track #%d)", next);
    } else {
        log_msg(g_app.buf_client_log, "Sent remote: Skip Next");
    }
}

/* Double-click to play any song row in client table */
static void on_client_row_activated(GtkTreeView *tree, GtkTreePath *path, GtkTreeViewColumn *col, gpointer data) {
    (void)tree; (void)col; (void)data;
    GtkTreeIter iter;
    if (gtk_tree_model_get_iter(GTK_TREE_MODEL(g_app.store_queue), &iter, path)) {
        int idx = -1;
        gtk_tree_model_get(GTK_TREE_MODEL(g_app.store_queue), &iter, COL_INDEX, &idx, -1);
        pthread_mutex_lock(&g_app.client_queue_mutex);
        if (idx >= 0 && idx < g_app.client_queue_count) {
            if (g_app.client_sync_fd >= 0) {
                int64_t play_at = time_now_ms() + g_app.client_offset_ms + 650;
                send_u8(g_app.client_sync_fd, SYNC_TRACK_CHANGE);
                send_u32(g_app.client_sync_fd, (uint32_t)idx);
                send_u64(g_app.client_sync_fd, (uint64_t)play_at);
                log_msg(g_app.buf_client_log, "Requested host to switch to track #%d: '%s'", idx, g_app.client_queue[idx].file_name);
            } else if (g_app.client_queue[idx].is_ready) {
                g_app.client_playing_index = idx;
                audio_player_load(g_app.client_queue[idx].local_path);
                audio_player_play();
                post_now_playing(g_app.client_queue[idx].file_name, g_app.client_queue[idx].artist, true);
                update_client_queue_row(idx, "▶ Playing");
                log_msg(g_app.buf_client_log, "Started playing track #%d: '%s'", idx, g_app.client_queue[idx].file_name);
            }
        }
        pthread_mutex_unlock(&g_app.client_queue_mutex);
    }
}

/* Open Audio File dialog for Receiver Tab */
static void on_client_add_song_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn; (void)user_data;
    GtkWidget *dialog = gtk_file_chooser_dialog_new("Choose Audio File to Play / Share",
                                                    GTK_WINDOW(g_app.window),
                                                    GTK_FILE_CHOOSER_ACTION_OPEN,
                                                    "_Cancel", GTK_RESPONSE_CANCEL,
                                                    "_Open Song", GTK_RESPONSE_ACCEPT,
                                                    NULL);

    GtkFileFilter *filter = gtk_file_filter_new();
    gtk_file_filter_set_name(filter, "Audio Files (*.flac, *.mp3, *.wav, *.m4a, *.ogg)");
    gtk_file_filter_add_pattern(filter, "*.flac");
    gtk_file_filter_add_pattern(filter, "*.mp3");
    gtk_file_filter_add_pattern(filter, "*.wav");
    gtk_file_filter_add_pattern(filter, "*.m4a");
    gtk_file_filter_add_pattern(filter, "*.ogg");
    gtk_file_chooser_add_filter(GTK_FILE_CHOOSER(dialog), filter);

    if (gtk_dialog_run(GTK_DIALOG(dialog)) == GTK_RESPONSE_ACCEPT) {
        char *filename = gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dialog));
        if (filename) {
            log_msg(g_app.buf_client_log, "Opened audio file: %s", filename);
            pthread_mutex_lock(&g_app.client_queue_mutex);
            if (g_app.client_queue_count < MAX_SONG_QUEUE) {
                int idx = g_app.client_queue_count++;
                song_item_t *item = &g_app.client_queue[idx];
                snprintf(item->id, sizeof(item->id), "trk_c%d_%ld", idx, (long)time_now_ms());
                char *bname = g_path_get_basename(filename);
                snprintf(item->file_name, sizeof(item->file_name), "%s", bname);
                g_free(bname);
                snprintf(item->artist, sizeof(item->artist), "Receiver Peer");
                snprintf(item->album, sizeof(item->album), "SoundShare");
                snprintf(item->local_path, sizeof(item->local_path), "%s", filename);

                item->file_size = 0;
                FILE *fp = fopen(filename, "rb");
                if (fp) {
                    fseek(fp, 0, SEEK_END);
                    item->file_size = (int64_t)ftell(fp);
                    fclose(fp);
                }

                const char *ext = strrchr(filename, '.');
                if (ext && strcasecmp(ext, ".mp3") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/mpeg");
                else if (ext && strcasecmp(ext, ".wav") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/wav");
                else if (ext && strcasecmp(ext, ".ogg") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/ogg");
                else if (ext && strcasecmp(ext, ".m4a") == 0) snprintf(item->mime_type, sizeof(item->mime_type), "audio/mp4");
                else snprintf(item->mime_type, sizeof(item->mime_type), "audio/*");

                item->duration_ms = 180000;
                snprintf(item->duration_str, sizeof(item->duration_str), "03:00");
                snprintf(item->format_desc, sizeof(item->format_desc), "Hi-Res Audio");
                item->is_ready = true;

                update_client_queue_row(idx, "✓ Ready");

                if (g_app.client_sync_fd >= 0) {
                    send_u8(g_app.client_sync_fd, SYNC_QUEUE_ADD);
                    send_string(g_app.client_sync_fd, item->id);
                    send_string(g_app.client_sync_fd, item->file_name);
                    send_string(g_app.client_sync_fd, item->artist);
                    send_string(g_app.client_sync_fd, item->album);
                    send_u64(g_app.client_sync_fd, (uint64_t)item->file_size);
                    send_string(g_app.client_sync_fd, item->mime_type);
                    send_u32(g_app.client_sync_fd, (uint32_t)item->duration_ms);
                    send_string(g_app.client_sync_fd, item->duration_str);
                    send_string(g_app.client_sync_fd, item->format_desc);

                    client_upload_arg_t *u = (client_upload_arg_t *)malloc(sizeof(client_upload_arg_t));
                    if (u) {
                        u->item = *item;
                        u->sync_fd = g_app.client_sync_fd;
                        pthread_t up_th;
                        pthread_create(&up_th, NULL, client_upload_worker, u);
                        pthread_detach(up_th);
                    }
                    log_msg(g_app.buf_client_log, "Uploaded '%s' to Host playlist", item->file_name);
                } else {
                    g_app.client_playing_index = idx;
                    audio_player_load(filename);
                    audio_player_play();
                    post_now_playing(item->file_name, item->artist, true);
                }
            }
            pthread_mutex_unlock(&g_app.client_queue_mutex);
            g_free(filename);
        }
    }
    gtk_widget_destroy(dialog);
}

static void on_timeline_resync_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    g_app.sync_time_established = false;
    log_msg(g_app.buf_client_log, "[TIMELINE] 🔄 Re-sync requested! Armed to capture fresh sync time from host.");

    if (g_app.synced_start_time_local > 0 && audio_player_is_loaded()) {
        int64_t now = time_now_ms();
        int64_t timeline_pos = now - g_app.synced_start_time_local;
        if (timeline_pos < 0) {
            audio_player_schedule_play(0, g_app.synced_start_time_local);
            log_msg(g_app.buf_client_log, "[TIMELINE] Rescheduled to start at T₀ in %ld ms", (long)(-timeline_pos));
        } else {
            audio_player_schedule_play(timeline_pos, now);
            log_msg(g_app.buf_client_log, "[TIMELINE] Resynced to current timeline position: %02ld:%02ld.%03ld",
                    (long)(timeline_pos/60000), (long)((timeline_pos%60000)/1000), (long)(timeline_pos%1000));
        }
    } else if (!audio_player_is_loaded()) {
        log_msg(g_app.buf_client_log, "[TIMELINE] Re-sync armed. When host sends next pulse, fresh sync time will be locked.");
    }
}

static void apply_sync_time_nudge(int64_t delta_ms) {
    if (g_app.synced_start_time_local <= 0) {
        log_msg(g_app.buf_client_log, "[TIMELINE] Cannot fine-tune: no sync time established yet. Connect to host or click 'Test Start Time (+1s)'.");
        return;
    }

    g_app.manual_sync_nudge_ms += delta_ms;
    /* Advancing timeline moves Local T0 earlier */
    g_app.synced_start_time_local -= delta_ms;

    if (audio_player_is_playing()) {
        int64_t cur_pos = audio_player_get_position_ms();
        int64_t new_pos = cur_pos + delta_ms;
        if (new_pos < 0) new_pos = 0;
        audio_player_seek(new_pos);
    }

    if (g_app.lbl_nudge_display) {
        char buf[32];
        snprintf(buf, sizeof(buf), "Nudge: %+ld ms", (long)g_app.manual_sync_nudge_ms);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_nudge_display), buf);
    }

    log_msg(g_app.buf_client_log, "[TIMELINE] Sync time fine-tuned %+ld ms (Total Nudge: %+ld ms | New Local T₀: %ld ms)",
            (long)delta_ms, (long)g_app.manual_sync_nudge_ms, (long)g_app.synced_start_time_local);
}

static void on_nudge_minus50_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    apply_sync_time_nudge(-50);
}

static void on_nudge_minus10_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    apply_sync_time_nudge(-10);
}

static void on_nudge_plus10_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    apply_sync_time_nudge(10);
}

static void on_nudge_plus50_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    apply_sync_time_nudge(50);
}

static void on_nudge_reset_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    if (g_app.manual_sync_nudge_ms != 0) {
        int64_t revert = -g_app.manual_sync_nudge_ms;
        apply_sync_time_nudge(revert);
        g_app.manual_sync_nudge_ms = 0;
        if (g_app.lbl_nudge_display) {
            gtk_label_set_text(GTK_LABEL(g_app.lbl_nudge_display), "Nudge: 0 ms");
        }
    }
}

static void on_timeline_lock_toggle_clicked(GtkButton *btn, gpointer data) {
    (void)data;
    g_app.sync_time_locked = !g_app.sync_time_locked;
    GtkStyleContext *sc = gtk_widget_get_style_context(GTK_WIDGET(btn));
    if (g_app.sync_time_locked) {
        gtk_button_set_label(btn, "🔒 Sync Time: Locked");
        gtk_style_context_remove_class(sc, "btn-unlock");
        gtk_style_context_add_class(sc, "btn-lock");
        log_msg(g_app.buf_client_log, "[TIMELINE] 🔒 Sync time LOCKED. Routine pulses will NOT overwrite sync time.");
    } else {
        gtk_button_set_label(btn, "🔓 Sync Time: Unlocked");
        gtk_style_context_remove_class(sc, "btn-lock");
        gtk_style_context_add_class(sc, "btn-unlock");
        log_msg(g_app.buf_client_log, "[TIMELINE] 🔓 Sync time UNLOCKED. Host pulses will continuously update timeline.");
    }
}

static void on_timeline_test_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    int64_t now = time_now_ms();
    int64_t test_start = now + 1000;
    g_app.sync_time_established = true;
    g_app.sync_time_locked = true;
    g_app.synced_start_time_host = test_start + g_app.client_offset_ms;
    g_app.synced_start_time_local = test_start;
    g_app.synced_is_playing = true;

    log_msg(g_app.buf_client_log, "[TIMELINE TEST] Generated test Start Time (T₀ = %+ld ms, triggers in 1000 ms) [🔒 LOCKED]", (long)test_start);

    if (audio_player_is_loaded()) {
        audio_player_schedule_play(0, test_start);
        log_msg(g_app.buf_client_log, "[TIMELINE TEST] Audio player primed to trigger at exact T₀!");
    } else {
        log_msg(g_app.buf_client_log, "[TIMELINE TEST] Timeline active! Click 'Select Local Song File' to load a song.");
    }
}

/* ========================================================================= */
/* Server Host Management & Callbacks                                        */
/* ========================================================================= */
static gboolean on_srv_queue_updated_ui(gpointer data) {
    (void)data;
    int count = 0;
    const song_item_t *q = server_get_queue(&count);
    gtk_list_store_clear(g_app.store_srv_queue);

    int playing_idx = server_get_playing_index();

    for (int i = 0; i < count; i++) {
        GtkTreeIter iter;
        gtk_list_store_append(g_app.store_srv_queue, &iter);
        const char *st = (i == playing_idx) ? "▶ Playing in Sync" : (q[i].is_ready ? "✓ Ready" : "⬇ Transferring");
        gtk_list_store_set(g_app.store_srv_queue, &iter,
                           COL_INDEX, i,
                           COL_TITLE, q[i].file_name,
                           COL_ARTIST, q[i].artist,
                           COL_DURATION, q[i].duration_str,
                           COL_STATUS, st, -1);
    }

    if (playing_idx >= 0 && playing_idx < count) {
        gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_now_playing), q[playing_idx].file_name);
    }
    return G_SOURCE_REMOVE;
}

static void on_srv_queue_updated_cb(void) {
    g_idle_add(on_srv_queue_updated_ui, NULL);
}

typedef struct {
    int count;
} srv_client_cnt_ui_t;

static gboolean on_srv_clients_count_ui(gpointer data) {
    srv_client_cnt_ui_t *c = (srv_client_cnt_ui_t *)data;
    char buf[64];
    snprintf(buf, sizeof(buf), "%d receiver(s) connected", c->count);
    if (g_app.lbl_srv_clients) {
        gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_clients), buf);
    }

    if (server_is_running() && g_app.lbl_srv_status_badge) {
        char st_buf[64];
        snprintf(st_buf, sizeof(st_buf), "🚀 Hosting (%d connected)", c->count);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_status_badge), st_buf);
    }
    free(c);
    return G_SOURCE_REMOVE;
}

static void on_srv_client_count_cb(int client_count) {
    srv_client_cnt_ui_t *c = g_new0(srv_client_cnt_ui_t, 1);
    c->count = client_count;
    g_idle_add(on_srv_clients_count_ui, c);
}

static void on_srv_status_cb(const char *msg) {
    log_msg(g_app.buf_srv_log, "%s", msg);
}

static void on_srv_toggle_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    if (!server_is_running()) {
        server_config_t cfg;
        cfg.sync_port = atoi(gtk_entry_get_text(GTK_ENTRY(g_app.entry_srv_sync_port)));
        cfg.ping_port = atoi(gtk_entry_get_text(GTK_ENTRY(g_app.entry_srv_ping_port)));
        cfg.pulse_interval_ms = 500;
        cfg.verbose = true;

        server_set_callbacks(on_srv_status_cb, on_srv_client_count_cb, on_srv_queue_updated_cb);
        if (server_start(&cfg) == 0) {
            gtk_button_set_label(GTK_BUTTON(g_app.btn_srv_toggle), "🛑 Stop Host");
            GtkStyleContext *sc = gtk_widget_get_style_context(g_app.btn_srv_toggle);
            gtk_style_context_remove_class(sc, "btn-connect");
            gtk_style_context_add_class(sc, "btn-disconnect");

            gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_status_badge), "🚀 Hosting (0 connected)");
            GtkStyleContext *bsc = gtk_widget_get_style_context(g_app.lbl_srv_status_badge);
            gtk_style_context_remove_class(bsc, "badge-disconnected");
            gtk_style_context_add_class(bsc, "badge-synced");
        }
    } else {
        server_stop();
        gtk_button_set_label(GTK_BUTTON(g_app.btn_srv_toggle), "🚀 Start Host");
        GtkStyleContext *sc = gtk_widget_get_style_context(g_app.btn_srv_toggle);
        gtk_style_context_remove_class(sc, "btn-disconnect");
        gtk_style_context_add_class(sc, "btn-connect");

        gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_status_badge), "● Stopped");
        GtkStyleContext *bsc = gtk_widget_get_style_context(g_app.lbl_srv_status_badge);
        gtk_style_context_remove_class(bsc, "badge-synced");
        gtk_style_context_add_class(bsc, "badge-disconnected");
        if (g_app.lbl_srv_clients) gtk_label_set_text(GTK_LABEL(g_app.lbl_srv_clients), "0 receiver(s) connected");
    }
}

static void on_srv_play_pause_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    server_play_pause();
    if (g_app.btn_srv_play_pause) {
        if (audio_player_is_playing()) {
            gtk_button_set_label(GTK_BUTTON(g_app.btn_srv_play_pause), "⏸ Pause");
        } else {
            gtk_button_set_label(GTK_BUTTON(g_app.btn_srv_play_pause), "▶ Play");
        }
    }
}

static void on_srv_skip_prev_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    server_skip_prev();
}

static void on_srv_skip_next_clicked(GtkButton *btn, gpointer data) {
    (void)btn; (void)data;
    server_skip_next();
}

/* File Chooser Dialog for Server Host */
static void on_add_song_clicked(GtkButton *btn, gpointer user_data) {
    (void)btn; (void)user_data;
    GtkWidget *dialog = gtk_file_chooser_dialog_new("Choose Audio File to Share in Sync",
                                                    GTK_WINDOW(g_app.window),
                                                    GTK_FILE_CHOOSER_ACTION_OPEN,
                                                    "_Cancel", GTK_RESPONSE_CANCEL,
                                                    "_Add Song", GTK_RESPONSE_ACCEPT,
                                                    NULL);

    GtkFileFilter *filter = gtk_file_filter_new();
    gtk_file_filter_set_name(filter, "Audio Files (*.flac, *.mp3, *.wav, *.m4a, *.ogg)");
    gtk_file_filter_add_pattern(filter, "*.flac");
    gtk_file_filter_add_pattern(filter, "*.mp3");
    gtk_file_filter_add_pattern(filter, "*.wav");
    gtk_file_filter_add_pattern(filter, "*.m4a");
    gtk_file_filter_add_pattern(filter, "*.ogg");
    gtk_file_chooser_add_filter(GTK_FILE_CHOOSER(dialog), filter);

    if (gtk_dialog_run(GTK_DIALOG(dialog)) == GTK_RESPONSE_ACCEPT) {
        char *filename = gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dialog));
        if (filename) {
            char *bname = g_path_get_basename(filename);
            int idx = server_add_track(filename, bname, "Linux Host");
            log_msg(g_app.buf_srv_log, "Added track #%d: '%s' (transferring to connected receivers)", idx, bname);
            g_free(bname);
            g_free(filename);
            on_srv_queue_updated_cb();
        }
    }
    gtk_widget_destroy(dialog);
}

/* Double-click to play track in server playlist */
static void on_srv_row_activated(GtkTreeView *tree, GtkTreePath *path, GtkTreeViewColumn *col, gpointer data) {
    (void)tree; (void)col; (void)data;
    GtkTreeIter iter;
    if (gtk_tree_model_get_iter(GTK_TREE_MODEL(g_app.store_srv_queue), &iter, path)) {
        int idx = -1;
        gtk_tree_model_get(GTK_TREE_MODEL(g_app.store_srv_queue), &iter, COL_INDEX, &idx, -1);
        if (idx >= 0) {
            server_play_track(idx);
        }
    }
}

/* ========================================================================= */
/* 25ms Ticker: Smooth Progress Bar, Real-time VU meter, Clocks              */
/* ========================================================================= */
static gboolean on_main_ui_ticker(gpointer data) {
    (void)data;
    int64_t now = time_now_ms();

    char local_str[32];
    format_time_str(now, local_str, sizeof(local_str));
    gtk_label_set_text(GTK_LABEL(g_app.lbl_top_clock), local_str);
    gtk_label_set_text(GTK_LABEL(g_app.lbl_client_clock_local), local_str);

    if (g_app.client_calibrated) {
        char host_str[32];
        format_time_str(now + g_app.client_offset_ms, host_str, sizeof(host_str));
        gtk_label_set_text(GTK_LABEL(g_app.lbl_client_clock_host), host_str);

        char offset_str[32];
        snprintf(offset_str, sizeof(offset_str), "%+ld ms", (long)g_app.client_offset_ms);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_client_clock_offset), offset_str);
    }

    /* Latency & RTT metrics */
    if (g_app.client_smoothed_latency >= 0) {
        char lat_str[32];
        snprintf(lat_str, sizeof(lat_str), "%ld ms", (long)g_app.client_smoothed_latency);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_metric_latency), lat_str);
    }
    if (g_app.client_last_rtt >= 0) {
        char rtt_str[32];
        snprintf(rtt_str, sizeof(rtt_str), "%ld ms", (long)g_app.client_last_rtt);
        gtk_label_set_text(GTK_LABEL(g_app.lbl_metric_rtt), rtt_str);
    }

    /* Update Live Audio VU Meter and Stream Info */
    pthread_mutex_lock(&g_app.live_stats_mutex);
    double peak = g_app.live_peak_level;
    g_app.live_peak_level *= 0.88;
    if (g_app.live_peak_level < 0.01) g_app.live_peak_level = 0.0;

    if (g_app.live_stream_running) {
        char info_str[256];
        snprintf(info_str, sizeof(info_str), "🔊 Live Audio Stream: %s | %ld kbps | %ld KB",
                 g_app.live_format_desc[0] ? g_app.live_format_desc : "Active",
                 (long)g_app.live_kbps, (long)(g_app.live_total_bytes / 1024));
        gtk_label_set_text(GTK_LABEL(g_app.lbl_stream_info), info_str);
    }
    pthread_mutex_unlock(&g_app.live_stats_mutex);

    gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(g_app.progress_vu), peak > 1.0 ? 1.0 : peak);

    /* Update song playback progress bar */
    if (audio_player_is_playing()) {
        int64_t pos_ms = audio_player_get_position_ms();
        int64_t dur_ms = audio_player_get_duration_ms();

        if (dur_ms > 0) {
            double fraction = (double)pos_ms / (double)dur_ms;
            if (fraction > 1.0) fraction = 1.0;
            gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(g_app.progress_song), fraction);

            int p_min = (int)(pos_ms / 60000);
            int p_sec = (int)((pos_ms % 60000) / 1000);
            int d_min = (int)(dur_ms / 60000);
            int d_sec = (int)((dur_ms % 60000) / 1000);

            char time_lbl[64];
            snprintf(time_lbl, sizeof(time_lbl), "%02d:%02d / %02d:%02d", p_min, p_sec, d_min, d_sec);
            gtk_label_set_text(GTK_LABEL(g_app.lbl_song_time), time_lbl);
        }
    }

    /* Update Calculated Playing Timeline */
    if (g_app.synced_start_time_local > 0) {
        int64_t timeline_pos = 0;
        if (g_app.synced_is_playing) {
            timeline_pos = now - g_app.synced_start_time_local;
            g_app.synced_timeline_pos_ms = timeline_pos;
        } else {
            timeline_pos = g_app.synced_timeline_pos_ms;
        }

        char clock_str[64];
        if (timeline_pos < 0) {
            int64_t wait_ms = -timeline_pos;
            snprintf(clock_str, sizeof(clock_str), "T - %02ld:%02ld.%03ld",
                     (long)(wait_ms / 60000), (long)((wait_ms % 60000) / 1000), (long)(wait_ms % 1000));
            if (g_app.lbl_timeline_status) {
                gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_status), "⏳ Scheduled to start");
            }
        } else {
            snprintf(clock_str, sizeof(clock_str), "%02ld:%02ld.%03ld",
                     (long)(timeline_pos / 60000), (long)((timeline_pos % 60000) / 1000), (long)(timeline_pos % 1000));
            if (g_app.lbl_timeline_status) {
                if (audio_player_is_playing()) {
                    gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_status), "● PLAYING IN SYNC");
                } else if (audio_player_is_loaded()) {
                    gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_status), "⏸ PAUSED / READY");
                } else {
                    gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_status), "● TIMELINE ACTIVE (Select Song)");
                }
            }
        }

        if (g_app.lbl_timeline_clock) {
            gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_clock), clock_str);
        }

        int64_t dur = audio_player_get_duration_ms();
        if (dur <= 0 && g_app.loaded_song_duration_ms > 0) dur = g_app.loaded_song_duration_ms;

        if (dur > 0 && g_app.progress_timeline) {
            double fraction = (double)timeline_pos / (double)dur;
            if (fraction < 0.0) fraction = 0.0;
            if (fraction > 1.0) fraction = 1.0;
            gtk_progress_bar_set_fraction(GTK_PROGRESS_BAR(g_app.progress_timeline), fraction);
        }

        if (g_app.lbl_timeline_start_time) {
            char start_meta[256];
            snprintf(start_meta, sizeof(start_meta),
                     "Host T₀: %ld ms  |  Local T₀: %ld ms [%s]  |  Elapsed: %ld ms",
                     (long)g_app.synced_start_time_host,
                     (long)g_app.synced_start_time_local,
                     g_app.sync_time_locked ? "🔒 LOCKED" : "🔓 UNLOCKED",
                     (long)(now - g_app.synced_start_time_local));
            gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_start_time), start_meta);
        }

        if (g_app.lbl_timeline_drift) {
            char drift_meta[128];
            if (audio_player_is_playing()) {
                int64_t player_pos = audio_player_get_position_ms();
                int64_t drift = timeline_pos - player_pos;
                snprintf(drift_meta, sizeof(drift_meta), "Drift: %+ld ms (%s)",
                         (long)drift, llabs(drift) < 30 ? "Synchronized" : "Correcting");
            } else {
                snprintf(drift_meta, sizeof(drift_meta), "Drift: -- ms (Idle)");
            }
            gtk_label_set_text(GTK_LABEL(g_app.lbl_timeline_drift), drift_meta);
        }
    }

    return G_SOURCE_CONTINUE;
}

/* ========================================================================= */
/* Build UI Layout                                                           */
/* ========================================================================= */
static GtkWidget *build_client_view(void) {
    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_container_set_border_width(GTK_CONTAINER(vbox), 12);

    /* Connection Card */
    GtkWidget *conn_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_style_context_add_class(gtk_widget_get_style_context(conn_card), "card");

    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_column_spacing(GTK_GRID(grid), 8);
    gtk_grid_set_row_spacing(GTK_GRID(grid), 6);

    GtkWidget *lbl_ip = gtk_label_new("Host IP:");
    g_app.entry_client_ip = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_client_ip), "192.168.1.50");
    gtk_widget_set_hexpand(g_app.entry_client_ip, TRUE);

    g_app.btn_client_scan = gtk_button_new_with_label("🔍 Auto-Scan Host");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_client_scan), "btn-scan");
    g_signal_connect(g_app.btn_client_scan, "clicked", G_CALLBACK(on_client_scan_clicked), NULL);

    GtkWidget *lbl_ap = gtk_label_new("Audio Port:");
    g_app.entry_client_audio_port = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_client_audio_port), "5000");

    GtkWidget *lbl_sp = gtk_label_new("Sync Port:");
    g_app.entry_client_sync_port = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_client_sync_port), "5006");

    GtkWidget *lbl_pp = gtk_label_new("Ping Port:");
    g_app.entry_client_ping_port = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_client_ping_port), "5001");

    g_app.btn_client_toggle = gtk_button_new_with_label("Connect & Sync");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_client_toggle), "btn-connect");
    g_signal_connect(g_app.btn_client_toggle, "clicked", G_CALLBACK(on_client_connect_toggle), NULL);

    g_app.lbl_client_status_badge = gtk_label_new("● Disconnected");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_client_status_badge), "badge-disconnected");

    gtk_grid_attach(GTK_GRID(grid), lbl_ip, 0, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_client_ip, 1, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.btn_client_scan, 2, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), lbl_ap, 3, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_client_audio_port, 4, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), lbl_sp, 5, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_client_sync_port, 6, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), lbl_pp, 7, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_client_ping_port, 8, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.btn_client_toggle, 9, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.lbl_client_status_badge, 10, 0, 1, 1);

    gtk_box_pack_start(GTK_BOX(conn_card), grid, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), conn_card, FALSE, FALSE, 0);

    /* ========================================================================= */
    /* PLAYING TIMELINE CARD (Receive Start Time & Calculate Timeline)           */
    /* ========================================================================= */
    GtkWidget *tl_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 10);
    gtk_style_context_add_class(gtk_widget_get_style_context(tl_card), "timeline-card");

    GtkWidget *tl_hdr = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget *tl_badge = gtk_label_new("⏱ TIME-SYNCHRONIZED PLAYING TIMELINE");
    gtk_style_context_add_class(gtk_widget_get_style_context(tl_badge), "metric-title");
    gtk_box_pack_start(GTK_BOX(tl_hdr), tl_badge, FALSE, FALSE, 0);

    g_app.lbl_timeline_status = gtk_label_new("● Awaiting Start Time");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_timeline_status), "timeline-status");
    gtk_box_pack_end(GTK_BOX(tl_hdr), g_app.lbl_timeline_status, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(tl_card), tl_hdr, FALSE, FALSE, 0);

    /* Big Digital Clock */
    g_app.lbl_timeline_clock = gtk_label_new("00:00.000");
    gtk_widget_set_halign(g_app.lbl_timeline_clock, GTK_ALIGN_CENTER);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_timeline_clock), "timeline-clock");
    gtk_box_pack_start(GTK_BOX(tl_card), g_app.lbl_timeline_clock, FALSE, FALSE, 4);

    /* Dedicated Timeline Bar */
    g_app.progress_timeline = gtk_progress_bar_new();
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.progress_timeline), "timeline-bar");
    gtk_box_pack_start(GTK_BOX(tl_card), g_app.progress_timeline, FALSE, FALSE, 2);

    /* Metadata / Start Time Row */
    GtkWidget *meta_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    g_app.lbl_timeline_start_time = gtk_label_new("Host T₀: --  |  Local T₀: --  |  Elapsed: --");
    gtk_widget_set_halign(g_app.lbl_timeline_start_time, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_timeline_start_time), "timeline-meta");
    gtk_box_pack_start(GTK_BOX(meta_row), g_app.lbl_timeline_start_time, TRUE, TRUE, 0);

    g_app.lbl_timeline_drift = gtk_label_new("Drift: -- ms");
    gtk_widget_set_halign(g_app.lbl_timeline_drift, GTK_ALIGN_END);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_timeline_drift), "timeline-meta");
    gtk_box_pack_end(GTK_BOX(meta_row), g_app.lbl_timeline_drift, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(tl_card), meta_row, FALSE, FALSE, 2);

    /* Actions Row */
    GtkWidget *act_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 10);
    gtk_widget_set_halign(act_row, GTK_ALIGN_CENTER);

    g_app.btn_timeline_pick_song = gtk_button_new_with_label("📂 Select Local Song File...");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_timeline_pick_song), "btn-add-song");
    g_signal_connect(g_app.btn_timeline_pick_song, "clicked", G_CALLBACK(on_client_add_song_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(act_row), g_app.btn_timeline_pick_song, FALSE, FALSE, 0);

    g_app.btn_timeline_resync = gtk_button_new_with_label("🔄 Re-sync from Host Now");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_timeline_resync), "btn-add-song");
    g_signal_connect(g_app.btn_timeline_resync, "clicked", G_CALLBACK(on_timeline_resync_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(act_row), g_app.btn_timeline_resync, FALSE, FALSE, 0);

    g_app.btn_timeline_test = gtk_button_new_with_label("⏱ Test Start Time (+1s)");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_timeline_test), "btn-control");
    g_signal_connect(g_app.btn_timeline_test, "clicked", G_CALLBACK(on_timeline_test_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(act_row), g_app.btn_timeline_test, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(tl_card), act_row, FALSE, FALSE, 3);

    /* Sync Lock & Fine-Tuning Controls Row ("if want change") */
    GtkWidget *tune_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_widget_set_halign(tune_row, GTK_ALIGN_CENTER);

    g_app.btn_timeline_lock = gtk_button_new_with_label("🔒 Sync Time: Locked");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_timeline_lock), "btn-lock");
    g_signal_connect(g_app.btn_timeline_lock, "clicked", G_CALLBACK(on_timeline_lock_toggle_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), g_app.btn_timeline_lock, FALSE, FALSE, 0);

    GtkWidget *lbl_tune = gtk_label_new("Fine Tune:");
    gtk_style_context_add_class(gtk_widget_get_style_context(lbl_tune), "timeline-meta");
    gtk_box_pack_start(GTK_BOX(tune_row), lbl_tune, FALSE, FALSE, 2);

    GtkWidget *btn_nudge_m50 = gtk_button_new_with_label("⏪ -50ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_nudge_m50), "btn-nudge");
    g_signal_connect(btn_nudge_m50, "clicked", G_CALLBACK(on_nudge_minus50_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), btn_nudge_m50, FALSE, FALSE, 0);

    GtkWidget *btn_nudge_m10 = gtk_button_new_with_label("◀ -10ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_nudge_m10), "btn-nudge");
    g_signal_connect(btn_nudge_m10, "clicked", G_CALLBACK(on_nudge_minus10_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), btn_nudge_m10, FALSE, FALSE, 0);

    g_app.lbl_nudge_display = gtk_label_new("Nudge: 0 ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_nudge_display), "nudge-badge");
    gtk_box_pack_start(GTK_BOX(tune_row), g_app.lbl_nudge_display, FALSE, FALSE, 2);

    GtkWidget *btn_nudge_p10 = gtk_button_new_with_label("+10ms ▶");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_nudge_p10), "btn-nudge");
    g_signal_connect(btn_nudge_p10, "clicked", G_CALLBACK(on_nudge_plus10_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), btn_nudge_p10, FALSE, FALSE, 0);

    GtkWidget *btn_nudge_p50 = gtk_button_new_with_label("+50ms ⏩");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_nudge_p50), "btn-nudge");
    g_signal_connect(btn_nudge_p50, "clicked", G_CALLBACK(on_nudge_plus50_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), btn_nudge_p50, FALSE, FALSE, 0);

    GtkWidget *btn_nudge_rst = gtk_button_new_with_label("↺ Reset");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_nudge_rst), "btn-nudge");
    g_signal_connect(btn_nudge_rst, "clicked", G_CALLBACK(on_nudge_reset_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(tune_row), btn_nudge_rst, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(tl_card), tune_row, FALSE, FALSE, 3);
    gtk_box_pack_start(GTK_BOX(vbox), tl_card, FALSE, FALSE, 0);

    /* ========================================================================= */
    /* ACTIVE FILE TRANSFER CARD (Matching Android FileShare)                    */
    /* ========================================================================= */
    g_app.card_transfer = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.card_transfer), "transfer-card");

    GtkWidget *tr_hdr = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget *tr_title = gtk_label_new("⚡ ACTIVE FILE TRANSFER");
    gtk_style_context_add_class(gtk_widget_get_style_context(tr_title), "metric-title");
    gtk_box_pack_start(GTK_BOX(tr_hdr), tr_title, FALSE, FALSE, 0);

    g_app.lbl_transfer_status = gtk_label_new("Idle / All songs ready");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_transfer_status), "timeline-meta");
    gtk_box_pack_end(GTK_BOX(tr_hdr), g_app.lbl_transfer_status, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(g_app.card_transfer), tr_hdr, FALSE, FALSE, 0);

    g_app.lbl_transfer_file = gtk_label_new("No active file transfer");
    gtk_widget_set_halign(g_app.lbl_transfer_file, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_transfer_file), "metric-title");
    gtk_box_pack_start(GTK_BOX(g_app.card_transfer), g_app.lbl_transfer_file, FALSE, FALSE, 0);

    g_app.progress_transfer = gtk_progress_bar_new();
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.progress_transfer), "transfer-bar");
    gtk_box_pack_start(GTK_BOX(g_app.card_transfer), g_app.progress_transfer, FALSE, FALSE, 2);

    GtkWidget *tr_meta = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    g_app.lbl_transfer_percent = gtk_label_new("0%");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_transfer_percent), "metric-value");
    gtk_box_pack_start(GTK_BOX(tr_meta), g_app.lbl_transfer_percent, FALSE, FALSE, 0);

    g_app.lbl_transfer_speed = gtk_label_new("");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_transfer_speed), "clock-remote");
    gtk_box_pack_end(GTK_BOX(tr_meta), g_app.lbl_transfer_speed, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(g_app.card_transfer), tr_meta, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(vbox), g_app.card_transfer, FALSE, FALSE, 0);

    /* NOW PLAYING CARD (Time Synchronized Audio Player + Live Stream) */
    GtkWidget *np_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_style_context_add_class(gtk_widget_get_style_context(np_card), "now-playing-card");

    GtkWidget *np_hdr = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget *np_badge = gtk_label_new("TIME-SYNCHRONIZED AUDIO PLAYBACK");
    gtk_style_context_add_class(gtk_widget_get_style_context(np_badge), "metric-title");
    gtk_box_pack_start(GTK_BOX(np_hdr), np_badge, TRUE, TRUE, 0);

    g_app.lbl_sync_accuracy = gtk_label_new("Sync Drift: ±0 ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_sync_accuracy), "badge-synced");
    gtk_box_pack_start(GTK_BOX(np_hdr), g_app.lbl_sync_accuracy, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(np_card), np_hdr, FALSE, FALSE, 0);

    /* Song Title & Artist */
    g_app.lbl_song_title = gtk_label_new("Waiting for song from host / ready to play...");
    gtk_widget_set_halign(g_app.lbl_song_title, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_song_title), "song-title");
    gtk_box_pack_start(GTK_BOX(np_card), g_app.lbl_song_title, FALSE, FALSE, 0);

    g_app.lbl_song_artist = gtk_label_new("SoundShare Receiver Ready (Plays direct to speakers)");
    gtk_widget_set_halign(g_app.lbl_song_artist, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_song_artist), "song-artist");
    gtk_box_pack_start(GTK_BOX(np_card), g_app.lbl_song_artist, FALSE, FALSE, 0);

    /* Live VU meter & stream info */
    GtkWidget *vu_box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 4);
    g_app.lbl_stream_info = gtk_label_new("Live Audio Stream: Idle (Connect to hear audio from host)");
    gtk_widget_set_halign(g_app.lbl_stream_info, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_stream_info), "stream-info");
    gtk_box_pack_start(GTK_BOX(vu_box), g_app.lbl_stream_info, FALSE, FALSE, 0);

    g_app.progress_vu = gtk_progress_bar_new();
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.progress_vu), "vu-level");
    gtk_box_pack_start(GTK_BOX(vu_box), g_app.progress_vu, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(np_card), vu_box, FALSE, FALSE, 4);

    /* Progress bar & time */
    g_app.progress_song = gtk_progress_bar_new();
    gtk_box_pack_start(GTK_BOX(np_card), g_app.progress_song, FALSE, FALSE, 2);

    GtkWidget *time_box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 0);
    g_app.lbl_song_time = gtk_label_new("00:00 / 00:00");
    gtk_widget_set_halign(g_app.lbl_song_time, GTK_ALIGN_END);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_song_time), "metric-title");
    gtk_box_pack_start(GTK_BOX(time_box), g_app.lbl_song_time, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(np_card), time_box, FALSE, FALSE, 0);

    /* Transport Controls */
    GtkWidget *ctrl_box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_widget_set_halign(ctrl_box, GTK_ALIGN_CENTER);

    g_app.btn_skip_prev = gtk_button_new_with_label("⏮ Prev");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_skip_prev), "btn-control");
    g_signal_connect(g_app.btn_skip_prev, "clicked", G_CALLBACK(on_skip_prev_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(ctrl_box), g_app.btn_skip_prev, FALSE, FALSE, 0);

    g_app.btn_play_pause = gtk_button_new_with_label("▶ Play");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_play_pause), "btn-control");
    g_signal_connect(g_app.btn_play_pause, "clicked", G_CALLBACK(on_play_pause_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(ctrl_box), g_app.btn_play_pause, FALSE, FALSE, 0);

    GtkWidget *btn_stop = gtk_button_new_with_label("⏹ Stop");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_stop), "btn-control");
    g_signal_connect(btn_stop, "clicked", G_CALLBACK(on_stop_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(ctrl_box), btn_stop, FALSE, FALSE, 0);

    g_app.btn_skip_next = gtk_button_new_with_label("⏭ Next");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_skip_next), "btn-control");
    g_signal_connect(g_app.btn_skip_next, "clicked", G_CALLBACK(on_skip_next_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(ctrl_box), g_app.btn_skip_next, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(np_card), ctrl_box, FALSE, FALSE, 4);
    gtk_box_pack_start(GTK_BOX(vbox), np_card, FALSE, FALSE, 0);

    /* Clocks Comparison Banner */
    GtkWidget *clock_card = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 16);
    gtk_style_context_add_class(gtk_widget_get_style_context(clock_card), "card");
    gtk_box_set_homogeneous(GTK_BOX(clock_card), TRUE);

    GtkWidget *c1 = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l1 = gtk_label_new("Local Clock (Linux)");
    gtk_style_context_add_class(gtk_widget_get_style_context(l1), "metric-title");
    g_app.lbl_client_clock_local = gtk_label_new("00:00:00.000");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_client_clock_local), "clock-local");
    gtk_box_pack_start(GTK_BOX(c1), l1, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(c1), g_app.lbl_client_clock_local, FALSE, FALSE, 0);

    GtkWidget *c2 = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l2 = gtk_label_new("Synced Host Clock");
    gtk_style_context_add_class(gtk_widget_get_style_context(l2), "metric-title");
    g_app.lbl_client_clock_host = gtk_label_new("--:--:--.---");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_client_clock_host), "clock-remote");
    gtk_box_pack_start(GTK_BOX(c2), l2, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(c2), g_app.lbl_client_clock_host, FALSE, FALSE, 0);

    GtkWidget *c3 = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l3 = gtk_label_new("Clock Offset");
    gtk_style_context_add_class(gtk_widget_get_style_context(l3), "metric-title");
    g_app.lbl_client_clock_offset = gtk_label_new("-- ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_client_clock_offset), "clock-offset");
    gtk_box_pack_start(GTK_BOX(c3), l3, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(c3), g_app.lbl_client_clock_offset, FALSE, FALSE, 0);

    GtkWidget *c4 = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l4 = gtk_label_new("Network Latency");
    gtk_style_context_add_class(gtk_widget_get_style_context(l4), "metric-title");
    g_app.lbl_metric_latency = gtk_label_new("-- ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_metric_latency), "metric-value");
    gtk_box_pack_start(GTK_BOX(c4), l4, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(c4), g_app.lbl_metric_latency, FALSE, FALSE, 0);

    GtkWidget *c5 = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l5 = gtk_label_new("Round-Trip RTT");
    gtk_style_context_add_class(gtk_widget_get_style_context(l5), "metric-title");
    g_app.lbl_metric_rtt = gtk_label_new("-- ms");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_metric_rtt), "metric-value");
    gtk_box_pack_start(GTK_BOX(c5), l5, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(c5), g_app.lbl_metric_rtt, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(clock_card), c1, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(clock_card), c2, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(clock_card), c3, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(clock_card), c4, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(clock_card), c5, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), clock_card, FALSE, FALSE, 0);

    /* Playlist / Received Songs Table */
    GtkWidget *queue_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_style_context_add_class(gtk_widget_get_style_context(queue_card), "card");
    gtk_widget_set_vexpand(queue_card, TRUE);

    GtkWidget *q_hdr = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget *q_title = gtk_label_new("Received Songs & Playback Queue (Double-click to play)");
    gtk_widget_set_halign(q_title, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(q_title), "metric-title");
    gtk_box_pack_start(GTK_BOX(q_hdr), q_title, TRUE, TRUE, 0);

    g_app.btn_client_add_song = gtk_button_new_with_label("➕ Open Song File...");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_client_add_song), "btn-add-song");
    g_signal_connect(g_app.btn_client_add_song, "clicked", G_CALLBACK(on_client_add_song_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(q_hdr), g_app.btn_client_add_song, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(queue_card), q_hdr, FALSE, FALSE, 0);

    g_app.store_queue = gtk_list_store_new(NUM_COLS, G_TYPE_INT, G_TYPE_STRING, G_TYPE_STRING, G_TYPE_STRING, G_TYPE_STRING);
    g_app.tree_queue = gtk_tree_view_new_with_model(GTK_TREE_MODEL(g_app.store_queue));
    g_signal_connect(g_app.tree_queue, "row-activated", G_CALLBACK(on_client_row_activated), NULL);

    GtkCellRenderer *r_txt = gtk_cell_renderer_text_new();
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_queue), -1, "#", r_txt, "text", COL_INDEX, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_queue), -1, "Song Title", r_txt, "text", COL_TITLE, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_queue), -1, "Artist", r_txt, "text", COL_ARTIST, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_queue), -1, "Duration", r_txt, "text", COL_DURATION, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_queue), -1, "Status", r_txt, "text", COL_STATUS, NULL);

    GtkWidget *scroll_q = gtk_scrolled_window_new(NULL, NULL);
    gtk_container_add(GTK_CONTAINER(scroll_q), g_app.tree_queue);
    gtk_box_pack_start(GTK_BOX(queue_card), scroll_q, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), queue_card, TRUE, TRUE, 0);

    /* Event Log Scroller */
    GtkWidget *scroll_log = gtk_scrolled_window_new(NULL, NULL);
    gtk_widget_set_size_request(scroll_log, -1, 100);
    GtkWidget *tv_log = gtk_text_view_new();
    gtk_text_view_set_editable(GTK_TEXT_VIEW(tv_log), FALSE);
    g_app.buf_client_log = gtk_text_view_get_buffer(GTK_TEXT_VIEW(tv_log));
    gtk_container_add(GTK_CONTAINER(scroll_log), tv_log);
    gtk_box_pack_start(GTK_BOX(vbox), scroll_log, FALSE, FALSE, 0);

    return vbox;
}

static GtkWidget *build_server_view(void) {
    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_container_set_border_width(GTK_CONTAINER(vbox), 12);

    /* Server Settings & Control Card */
    GtkWidget *card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_style_context_add_class(gtk_widget_get_style_context(card), "card");

    GtkWidget *hdr_box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    char ip_txt[128];
    snprintf(ip_txt, sizeof(ip_txt), "Host IP: %s (UDP Discovery on Port %d)", net_get_local_ip(), SOUNDSHARE_DISCOVERY_PORT);
    GtkWidget *lbl_host_ip = gtk_label_new(ip_txt);
    gtk_style_context_add_class(gtk_widget_get_style_context(lbl_host_ip), "song-artist");
    gtk_box_pack_start(GTK_BOX(hdr_box), lbl_host_ip, TRUE, TRUE, 0);

    g_app.lbl_srv_status_badge = gtk_label_new("● Stopped");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_srv_status_badge), "badge-disconnected");
    gtk_box_pack_end(GTK_BOX(hdr_box), g_app.lbl_srv_status_badge, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(card), hdr_box, FALSE, FALSE, 0);

    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_column_spacing(GTK_GRID(grid), 12);
    gtk_grid_set_row_spacing(GTK_GRID(grid), 6);

    GtkWidget *l_sp = gtk_label_new("Clock Sync Port:");
    g_app.entry_srv_sync_port = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_srv_sync_port), "5006");

    GtkWidget *l_pp = gtk_label_new("Ping Port:");
    g_app.entry_srv_ping_port = gtk_entry_new();
    gtk_entry_set_text(GTK_ENTRY(g_app.entry_srv_ping_port), "5001");

    g_app.btn_srv_toggle = gtk_button_new_with_label("🚀 Start Host");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_srv_toggle), "btn-connect");
    g_signal_connect(g_app.btn_srv_toggle, "clicked", G_CALLBACK(on_srv_toggle_clicked), NULL);

    g_app.lbl_srv_clients = gtk_label_new("0 receiver(s) connected");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_srv_clients), "metric-value");

    GtkWidget *btn_add_song = gtk_button_new_with_label("➕ Add Song File...");
    gtk_style_context_add_class(gtk_widget_get_style_context(btn_add_song), "btn-add-song");
    g_signal_connect(btn_add_song, "clicked", G_CALLBACK(on_add_song_clicked), NULL);

    gtk_grid_attach(GTK_GRID(grid), l_sp, 0, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_srv_sync_port, 1, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), l_pp, 2, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.entry_srv_ping_port, 3, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.btn_srv_toggle, 4, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), g_app.lbl_srv_clients, 5, 0, 1, 1);
    gtk_grid_attach(GTK_GRID(grid), btn_add_song, 6, 0, 1, 1);

    gtk_box_pack_start(GTK_BOX(card), grid, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), card, FALSE, FALSE, 0);

    /* Server Playlist / Queue Table */
    GtkWidget *q_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_style_context_add_class(gtk_widget_get_style_context(q_card), "card");
    gtk_widget_set_vexpand(q_card, TRUE);

    GtkWidget *lbl_hint = gtk_label_new("Host Playlist & Sync Queue (Double-click any track to broadcast & play across all devices)");
    gtk_widget_set_halign(lbl_hint, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(lbl_hint), "metric-title");
    gtk_box_pack_start(GTK_BOX(q_card), lbl_hint, FALSE, FALSE, 0);

    g_app.store_srv_queue = gtk_list_store_new(NUM_COLS, G_TYPE_INT, G_TYPE_STRING, G_TYPE_STRING, G_TYPE_STRING, G_TYPE_STRING);
    g_app.tree_srv_queue = gtk_tree_view_new_with_model(GTK_TREE_MODEL(g_app.store_srv_queue));
    g_signal_connect(g_app.tree_srv_queue, "row-activated", G_CALLBACK(on_srv_row_activated), NULL);

    GtkCellRenderer *r_txt = gtk_cell_renderer_text_new();
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_srv_queue), -1, "#", r_txt, "text", COL_INDEX, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_srv_queue), -1, "Song Title", r_txt, "text", COL_TITLE, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_srv_queue), -1, "Artist", r_txt, "text", COL_ARTIST, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_srv_queue), -1, "Duration", r_txt, "text", COL_DURATION, NULL);
    gtk_tree_view_insert_column_with_attributes(GTK_TREE_VIEW(g_app.tree_srv_queue), -1, "Status", r_txt, "text", COL_STATUS, NULL);

    GtkWidget *scroll_sq = gtk_scrolled_window_new(NULL, NULL);
    gtk_container_add(GTK_CONTAINER(scroll_sq), g_app.tree_srv_queue);
    gtk_box_pack_start(GTK_BOX(q_card), scroll_sq, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(vbox), q_card, TRUE, TRUE, 0);

    /* Server Status & Transport Controls */
    GtkWidget *st_card = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    gtk_style_context_add_class(gtk_widget_get_style_context(st_card), "card");

    GtkWidget *np_row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    GtkWidget *l_np = gtk_label_new("Host Broadcasting:");
    gtk_style_context_add_class(gtk_widget_get_style_context(l_np), "metric-title");
    g_app.lbl_srv_now_playing = gtk_label_new("No track selected");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_srv_now_playing), "song-title");
    gtk_box_pack_start(GTK_BOX(np_row), l_np, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(np_row), g_app.lbl_srv_now_playing, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(st_card), np_row, FALSE, FALSE, 0);

    /* Transport Controls */
    GtkWidget *srv_ctrl_box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_widget_set_halign(srv_ctrl_box, GTK_ALIGN_CENTER);

    g_app.btn_srv_prev = gtk_button_new_with_label("⏮ Prev");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_srv_prev), "btn-control");
    g_signal_connect(g_app.btn_srv_prev, "clicked", G_CALLBACK(on_srv_skip_prev_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(srv_ctrl_box), g_app.btn_srv_prev, FALSE, FALSE, 0);

    g_app.btn_srv_play_pause = gtk_button_new_with_label("▶ Play");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_srv_play_pause), "btn-control");
    g_signal_connect(g_app.btn_srv_play_pause, "clicked", G_CALLBACK(on_srv_play_pause_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(srv_ctrl_box), g_app.btn_srv_play_pause, FALSE, FALSE, 0);

    g_app.btn_srv_next = gtk_button_new_with_label("⏭ Next");
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.btn_srv_next), "btn-control");
    g_signal_connect(g_app.btn_srv_next, "clicked", G_CALLBACK(on_srv_skip_next_clicked), NULL);
    gtk_box_pack_start(GTK_BOX(srv_ctrl_box), g_app.btn_srv_next, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(st_card), srv_ctrl_box, FALSE, FALSE, 4);
    gtk_box_pack_start(GTK_BOX(vbox), st_card, FALSE, FALSE, 0);

    /* Server Log */
    GtkWidget *scroll_slog = gtk_scrolled_window_new(NULL, NULL);
    gtk_widget_set_size_request(scroll_slog, -1, 100);
    GtkWidget *tv_slog = gtk_text_view_new();
    gtk_text_view_set_editable(GTK_TEXT_VIEW(tv_slog), FALSE);
    g_app.buf_srv_log = gtk_text_view_get_buffer(GTK_TEXT_VIEW(tv_slog));
    gtk_container_add(GTK_CONTAINER(scroll_slog), tv_slog);
    gtk_box_pack_start(GTK_BOX(vbox), scroll_slog, FALSE, FALSE, 0);

    return vbox;
}

int main(int argc, char *argv[]) {
    gtk_init(&argc, &argv);
    audio_player_init();

    GtkCssProvider *css_provider = gtk_css_provider_new();
    gtk_css_provider_load_from_data(css_provider, APP_CSS, -1, NULL);
    gtk_style_context_add_provider_for_screen(gdk_screen_get_default(),
                                              GTK_STYLE_PROVIDER(css_provider),
                                              GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);

    memset(&g_app, 0, sizeof(g_app));
    g_app.sync_time_locked = true;
    g_app.sync_time_established = false;
    g_app.manual_sync_nudge_ms = 0;
    g_app.client_sync_fd = -1;
    g_app.client_ping_fd = -1;
    g_app.client_meta_fd = -1;
    g_app.client_smoothed_latency = -1;
    g_app.client_last_rtt = -1;
    g_app.client_playing_index = -1;
    g_app.server_playing_index = -1;
    pthread_mutex_init(&g_app.client_queue_mutex, NULL);
    pthread_mutex_init(&g_app.server_queue_mutex, NULL);
    pthread_mutex_init(&g_app.live_stats_mutex, NULL);

    g_app.window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(g_app.window), "SoundShare Time - Synchronized Song Player (Linux)");
    gtk_window_set_default_size(GTK_WINDOW(g_app.window), 920, 780);
    gtk_window_set_position(GTK_WINDOW(g_app.window), GTK_WIN_POS_CENTER);
    g_signal_connect(g_app.window, "destroy", G_CALLBACK(gtk_main_quit), NULL);

    GtkWidget *root_vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_container_add(GTK_CONTAINER(g_app.window), root_vbox);

    /* Top Header */
    GtkWidget *top_bar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(top_bar), 12);

    GtkWidget *hdr_title_box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *app_title = gtk_label_new("SoundShare Time");
    gtk_widget_set_halign(app_title, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(app_title), "song-title");
    GtkWidget *app_sub = gtk_label_new("Time-Synchronized Song Player & Latency Synchronization (Linux C)");
    gtk_widget_set_halign(app_sub, GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(app_sub), "song-artist");
    gtk_box_pack_start(GTK_BOX(hdr_title_box), app_title, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(hdr_title_box), app_sub, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(top_bar), hdr_title_box, TRUE, TRUE, 0);

    /* Top Clock */
    GtkWidget *clock_box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *lbl_sys = gtk_label_new("SYSTEM TIME");
    gtk_widget_set_halign(lbl_sys, GTK_ALIGN_END);
    gtk_style_context_add_class(gtk_widget_get_style_context(lbl_sys), "metric-title");
    g_app.lbl_top_clock = gtk_label_new("00:00:00.000");
    gtk_widget_set_halign(g_app.lbl_top_clock, GTK_ALIGN_END);
    gtk_style_context_add_class(gtk_widget_get_style_context(g_app.lbl_top_clock), "clock-local");
    gtk_box_pack_start(GTK_BOX(clock_box), lbl_sys, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(clock_box), g_app.lbl_top_clock, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(top_bar), clock_box, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(root_vbox), top_bar, FALSE, FALSE, 0);

    /* Notebook Tabs */
    g_app.notebook = gtk_notebook_new();
    GtkWidget *tab_client = build_client_view();
    gtk_notebook_append_page(GTK_NOTEBOOK(g_app.notebook), tab_client, gtk_label_new("🎵 Receiver & Sync Player"));

    GtkWidget *tab_server = build_server_view();
    gtk_notebook_append_page(GTK_NOTEBOOK(g_app.notebook), tab_server, gtk_label_new("📡 Host Streamer"));

    gtk_box_pack_start(GTK_BOX(root_vbox), g_app.notebook, TRUE, TRUE, 0);

    /* 25ms timer for smooth playback progress, VU meter, and clock display */
    g_timeout_add(25, on_main_ui_ticker, NULL);

    gtk_widget_show_all(g_app.window);
    gtk_main();

    stream_receiver_stop();
    audio_player_stop();
    return 0;
}
