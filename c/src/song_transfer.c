#define _POSIX_C_SOURCE 200809L
#include "song_transfer.h"
#include "soundshare_time.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define FILE_SHARE_MAGIC 0x53534644 /* "SSFD" */

static const char *get_received_songs_dir(void) {
    static char dir_path[1024];
    static bool created = false;
    if (!created) {
        snprintf(dir_path, sizeof(dir_path), "received_songs");
        mkdir(dir_path, 0755);
        created = true;
    }
    return dir_path;
}

int song_transfer_receive(const char *target_ip, int target_port, const char *track_id,
                          const char *file_name, int64_t file_size,
                          transfer_progress_cb on_progress,
                          transfer_complete_cb on_complete) {
    (void)file_size;
    int sock = -1;
    for (int retry = 0; retry < 5; retry++) {
        sock = net_connect_tcp(target_ip, target_port, 3);
        if (sock >= 0) break;
        time_sleep_ms(150);
    }
    if (sock < 0) {
        fprintf(stderr, "[TRANSFER] Failed to connect to sender on %s:%d\n", target_ip, target_port);
        return -1;
    }

    uint32_t magic;
    if (recv_u32(sock, &magic) != 0 || magic != (uint32_t)FILE_SHARE_MAGIC) {
        fprintf(stderr, "[TRANSFER] Header mismatch (magic=0x%08X)\n", magic);
        close(sock);
        return -1;
    }

    uint16_t id_len;
    if (recv_u16(sock, &id_len) != 0) {
        close(sock);
        return -1;
    }

    char rec_id[256];
    if (id_len >= sizeof(rec_id)) id_len = sizeof(rec_id) - 1;
    if (net_read_exact(sock, rec_id, id_len) != (ssize_t)id_len) {
        close(sock);
        return -1;
    }
    rec_id[id_len] = '\0';

    uint64_t actual_size;
    if (recv_u64(sock, &actual_size) != 0) {
        close(sock);
        return -1;
    }

    /* Clean file name */
    char clean_name[256];
    strncpy(clean_name, file_name, sizeof(clean_name) - 1);
    clean_name[sizeof(clean_name) - 1] = '\0';
    for (size_t i = 0; i < strlen(clean_name); i++) {
        if (clean_name[i] == '/' || clean_name[i] == '\\' || clean_name[i] == ':') {
            clean_name[i] = '_';
        }
    }

    char out_path[2048];
    snprintf(out_path, sizeof(out_path), "%s/%s_%s", get_received_songs_dir(), track_id, clean_name);

    FILE *fp = fopen(out_path, "wb");
    if (!fp) {
        perror("fopen received song");
        close(sock);
        return -1;
    }

    uint8_t buf[8192];
    uint64_t received = 0;
    int64_t last_update = time_now_ms();
    uint64_t last_recv_bytes = 0;

    while (received < actual_size) {
        size_t to_read = sizeof(buf);
        if (actual_size - received < (uint64_t)to_read) {
            to_read = (size_t)(actual_size - received);
        }

        ssize_t rd = read(sock, buf, to_read);
        if (rd <= 0) break;

        fwrite(buf, 1, (size_t)rd, fp);
        received += (uint64_t)rd;

        int64_t now = time_now_ms();
        if (now - last_update >= 200) {
            int64_t elapsed = now - last_update;
            uint64_t bytes_since = received - last_recv_bytes;
            double speed_mbps = (elapsed > 0) ? ((double)bytes_since / (1024.0 * 1024.0)) / ((double)elapsed / 1000.0) : 0;
            int percent = actual_size > 0 ? (int)((received * 100) / actual_size) : 0;

            if (on_progress) {
                on_progress(track_id, percent, speed_mbps);
            }
            last_update = now;
            last_recv_bytes = received;
        }
    }

    fclose(fp);
    close(sock);

    if (received >= actual_size) {
        if (on_progress) on_progress(track_id, 100, 0.0);
        if (on_complete) on_complete(track_id, out_path);
        return 0;
    }

    return -1;
}

typedef struct {
    song_item_t item;
    int server_fd;
    transfer_progress_cb on_progress;
} send_worker_arg_t;

static void *send_worker_thread(void *arg) {
    send_worker_arg_t *sdata = (send_worker_arg_t *)arg;
    int server_fd = sdata->server_fd;
    song_item_t item = sdata->item;
    transfer_progress_cb on_progress = sdata->on_progress;
    free(sdata);

    struct sockaddr_in client_addr;
    socklen_t addr_len = sizeof(client_addr);
    int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &addr_len);
    close(server_fd);

    if (client_fd < 0) {
        return NULL;
    }
    net_set_tcp_nodelay(client_fd, true);

    FILE *fp = fopen(item.local_path, "rb");
    if (!fp) {
        close(client_fd);
        return NULL;
    }

    /* Send header */
    send_u32(client_fd, (uint32_t)FILE_SHARE_MAGIC);
    uint16_t id_len = (uint16_t)strlen(item.id);
    send_u16(client_fd, id_len);
    net_write_all(client_fd, item.id, id_len);
    send_u64(client_fd, (uint64_t)item.file_size);

    uint8_t buf[8192];
    uint64_t sent = 0;
    int64_t last_update = time_now_ms();
    uint64_t last_sent_bytes = 0;

    size_t rd;
    while ((rd = fread(buf, 1, sizeof(buf), fp)) > 0) {
        if (net_write_all(client_fd, buf, rd) != (ssize_t)rd) break;
        sent += rd;

        int64_t now = time_now_ms();
        if (now - last_update >= 200) {
            int64_t elapsed = now - last_update;
            uint64_t bytes_since = sent - last_sent_bytes;
            double speed = (elapsed > 0) ? ((double)bytes_since / (1024.0 * 1024.0)) / ((double)elapsed / 1000.0) : 0;
            int pct = item.file_size > 0 ? (int)((sent * 100) / item.file_size) : 0;
            if (on_progress) on_progress(item.id, pct, speed);
            last_update = now;
            last_sent_bytes = sent;
        }
    }

    fclose(fp);
    close(client_fd);
    if (on_progress) on_progress(item.id, 100, 0.0);
    return NULL;
}

int song_transfer_send_async(const song_item_t *item, int *out_port,
                             transfer_progress_cb on_progress) {
    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) return -1;

    int opt = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(0); /* Dynamic port */

    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sfd);
        return -1;
    }

    socklen_t len = sizeof(addr);
    if (getsockname(sfd, (struct sockaddr *)&addr, &len) < 0) {
        close(sfd);
        return -1;
    }

    int port = ntohs(addr.sin_port);
    *out_port = port;

    if (listen(sfd, 4) < 0) {
        close(sfd);
        return -1;
    }

    send_worker_arg_t *arg = (send_worker_arg_t *)malloc(sizeof(send_worker_arg_t));
    if (!arg) {
        close(sfd);
        return -1;
    }
    arg->item = *item;
    arg->server_fd = sfd;
    arg->on_progress = on_progress;

    pthread_t th;
    if (pthread_create(&th, NULL, send_worker_thread, arg) != 0) {
        free(arg);
        close(sfd);
        return -1;
    }
    pthread_detach(th);
    return 0;
}
