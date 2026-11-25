#ifndef SOUNDSHARE_TIME_H
#define SOUNDSHARE_TIME_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Default Ports matching SoundShare Android */
#define SOUNDSHARE_AUDIO_PORT     5000
#define SOUNDSHARE_PING_PORT      5001
#define SOUNDSHARE_CHAT_PORT      5002
#define SOUNDSHARE_FILE_PORT      5005
#define SOUNDSHARE_SYNC_CTRL_PORT 5006
#define SOUNDSHARE_DISCOVERY_PORT 5007

#define SS_DISCOVER_FILE "SS_DISCOVER_FILE"
#define SS_HOST_FILE_PREFIX "SS_HOST_FILE:"

/* Port 5001: Ping / Latency & Remote Control Protocol Commands */
#define PING_REQUEST   ((uint8_t)0x01)
#define PING_RESPONSE  ((uint8_t)0x02)
#define LATENCY_REPORT ((uint8_t)0x03)
#define CTRL_PAUSE     ((uint8_t)0x20)
#define CTRL_RESUME    ((uint8_t)0x21)
#define CTRL_SKIP_FWD  ((uint8_t)0x22)
#define CTRL_SKIP_BACK ((uint8_t)0x23)

/* Port 5002: Chat & Live Media Metadata */
#define CHAT_MSG       ((uint8_t)0x10)
#define CHAT_METADATA  ((uint8_t)0x11)

/* Port 5006: Clock Synchronization & Time Pulse Protocol Commands */
#define SYNC_CLOCK_REQ  ((uint8_t)0x30)
#define SYNC_CLOCK_RSP  ((uint8_t)0x31)
#define SYNC_PLAY       ((uint8_t)0x32)
#define SYNC_PAUSE_CMD  ((uint8_t)0x33)
#define SYNC_SEEK       ((uint8_t)0x34)
#define FILE_READY      ((uint8_t)0x36)
#define SYNC_FILE_INCOMING ((uint8_t)0x37)
#define SYNC_QUEUE_ADD  ((uint8_t)0x38)
#define SYNC_TRACK_CHANGE ((uint8_t)0x39)
#define SYNC_QUEUE_REMOVE ((uint8_t)0x3A)
#define SYNC_QUEUE_REORDER ((uint8_t)0x3B)
#define SYNC_TIME_PULSE ((uint8_t)0x3C)
#define SYNC_INIT_REQ   ((uint8_t)0x3D)

/* Number of NTP samples used for clock offset calibration */
#define DEFAULT_NTP_SAMPLES 8

/* Configuration for Server */
typedef struct {
    int sync_port;           /* Default 5006 */
    int ping_port;           /* Default 5001 */
    int pulse_interval_ms;   /* Time pulse broadcast interval (default: 500ms) */
    bool verbose;
} server_config_t;

/* Configuration for Client */
typedef struct {
    const char *server_ip;
    const char *audio_file;   /* Optional local audio file to play in sync */
    int sync_port;           /* Default 5006 */
    int ping_port;           /* Default 5001 */
    int ntp_samples;         /* Default 8 */
    int ping_interval_ms;    /* Ping interval (default: 500ms) */
    bool verbose;
} client_config_t;

/* Clock synchronization result */
typedef struct {
    int64_t clock_offset_ms; /* Server time = Client time + clock_offset_ms */
    int64_t min_rtt_ms;
    int64_t avg_rtt_ms;
    int64_t max_rtt_ms;
    int sample_count;
} ntp_sync_result_t;

/* Time utility functions */
int64_t time_now_ms(void);
int64_t time_now_ns(void);
void time_sleep_ms(int ms);
void format_time_str(int64_t epoch_ms, char *buf, size_t buf_size);

/* Network utility functions */
int net_create_tcp_listener(int port);
int net_connect_tcp(const char *host, int port, int timeout_sec);
int net_set_tcp_nodelay(int fd, bool enable);
int net_set_recv_timeout(int fd, int timeout_sec);
ssize_t net_write_all(int fd, const void *buf, size_t count);
ssize_t net_read_exact(int fd, void *buf, size_t count);

/* Protocol I/O helpers (Big-Endian network order) */
int send_u8(int fd, uint8_t val);
int send_u16(int fd, uint16_t val);
int send_u32(int fd, uint32_t val);
int send_u64(int fd, uint64_t val);

int recv_u8(int fd, uint8_t *val);
int recv_u16(int fd, uint16_t *val);
int recv_u32(int fd, uint32_t *val);
int recv_u64(int fd, uint64_t *val);

int send_string(int fd, const char *str);
int recv_string(int fd, char *buf, size_t max_len);

/* UDP Host Discovery helpers */
const char *net_get_local_ip(void);
int net_discover_host(int port, const char *query, const char *expected_prefix, char *out_ip, size_t max_len, int timeout_ms);
int net_start_discovery_server(int port, const char *expected_query, const char *reply_prefix);
void net_stop_discovery_server(void);

/* Server & Client runners */
int run_server(const server_config_t *cfg);
int run_client(const client_config_t *cfg);

#include "audio_player.h"
#include "song_transfer.h"

/* Server control & management APIs */
typedef void (*server_status_cb)(const char *msg);
typedef void (*server_client_cb)(int client_count);
typedef void (*server_queue_cb)(void);

int server_start(const server_config_t *cfg);
void server_stop(void);
bool server_is_running(void);
int server_get_client_count(void);
int server_add_track(const char *filepath, const char *title, const char *artist);
void server_play_track(int index);
void server_play_pause(void);
void server_seek(int64_t pos_ms);
void server_skip_next(void);
void server_skip_prev(void);
void server_set_callbacks(server_status_cb on_status, server_client_cb on_clients, server_queue_cb on_queue);
const song_item_t *server_get_queue(int *out_count);
int server_get_playing_index(void);

#ifdef __cplusplus
}
#endif

#endif /* SOUNDSHARE_TIME_H */
