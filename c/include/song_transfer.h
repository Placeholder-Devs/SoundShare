#ifndef SONG_TRANSFER_H
#define SONG_TRANSFER_H

#include <stdint.h>
#include <stdbool.h>

#define MAX_SONG_QUEUE 128

typedef struct {
    char id[128];
    char file_name[256];
    char artist[128];
    char album[128];
    int64_t file_size;
    char mime_type[64];
    int32_t duration_ms;
    char duration_str[32];
    char format_desc[64];
    char local_path[2048];
    bool is_ready;
} song_item_t;

typedef void (*transfer_progress_cb)(const char *track_id, int percent, double speed_mbps);
typedef void (*transfer_complete_cb)(const char *track_id, const char *local_path);

/* Receive track from sender transfer socket */
int song_transfer_receive(const char *target_ip, int target_port, const char *track_id,
                          const char *file_name, int64_t file_size,
                          transfer_progress_cb on_progress,
                          transfer_complete_cb on_complete);

/* Send track to peer on a dynamically opened port */
int song_transfer_send_async(const song_item_t *item, int *out_port,
                             transfer_progress_cb on_progress);

#endif /* SONG_TRANSFER_H */
