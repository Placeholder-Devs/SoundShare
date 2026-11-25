#include "soundshare_time.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <endian.h>
#include <pthread.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>

int net_create_tcp_listener(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("socket");
        return -1;
    }

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
#ifdef SO_REUSEPORT
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));
#endif

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons((uint16_t)port);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "Failed to bind port %d: %s\n", port, strerror(errno));
        close(fd);
        return -1;
    }

    if (listen(fd, 16) < 0) {
        fprintf(stderr, "Failed to listen on port %d: %s\n", port, strerror(errno));
        close(fd);
        return -1;
    }

    return fd;
}

int net_set_tcp_nodelay(int fd, bool enable) {
    int flag = enable ? 1 : 0;
    return setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, (char *)&flag, sizeof(int));
}

int net_set_recv_timeout(int fd, int timeout_sec) {
    struct timeval tv;
    tv.tv_sec = timeout_sec;
    tv.tv_usec = 0;
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
}

int net_connect_tcp(const char *host, int port, int timeout_sec) {
    struct addrinfo hints, *res, *p;
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    int err = getaddrinfo(host, port_str, &hints, &res);
    if (err != 0) {
        fprintf(stderr, "getaddrinfo failed for %s: %s\n", host, gai_strerror(err));
        return -1;
    }

    int fd = -1;
    for (p = res; p != NULL; p = p->ai_next) {
        fd = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (fd < 0) continue;

        net_set_tcp_nodelay(fd, true);

        if (timeout_sec > 0) {
            /* Set socket non-blocking for connect timeout */
            int flags = fcntl(fd, F_GETFL, 0);
            fcntl(fd, F_SETFL, flags | O_NONBLOCK);

            int rc = connect(fd, p->ai_addr, p->ai_addrlen);
            if (rc < 0) {
                if (errno == EINPROGRESS) {
                    fd_set wset;
                    FD_ZERO(&wset);
                    FD_SET(fd, &wset);
                    struct timeval tv;
                    tv.tv_sec = timeout_sec;
                    tv.tv_usec = 0;
                    rc = select(fd + 1, NULL, &wset, NULL, &tv);
                    if (rc > 0) {
                        int so_err = 0;
                        socklen_t len = sizeof(so_err);
                        getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &len);
                        if (so_err == 0) {
                            /* Connected */
                            fcntl(fd, F_SETFL, flags);
                            break;
                        }
                    }
                }
                close(fd);
                fd = -1;
                continue;
            } else {
                fcntl(fd, F_SETFL, flags);
                break;
            }
        } else {
            if (connect(fd, p->ai_addr, p->ai_addrlen) == 0) {
                break;
            }
            close(fd);
            fd = -1;
        }
    }

    freeaddrinfo(res);
    return fd;
}

ssize_t net_write_all(int fd, const void *buf, size_t count) {
    size_t written = 0;
    const uint8_t *p = (const uint8_t *)buf;
    while (written < count) {
        ssize_t n = write(fd, p + written, count - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;
        written += (size_t)n;
    }
    return (ssize_t)written;
}

ssize_t net_read_exact(int fd, void *buf, size_t count) {
    size_t read_bytes = 0;
    uint8_t *p = (uint8_t *)buf;
    while (read_bytes < count) {
        ssize_t n = read(fd, p + read_bytes, count - read_bytes);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) {
            /* EOF reached before expected bytes */
            return (read_bytes == 0) ? 0 : -1;
        }
        read_bytes += (size_t)n;
    }
    return (ssize_t)read_bytes;
}

int send_u8(int fd, uint8_t val) {
    return (net_write_all(fd, &val, 1) == 1) ? 0 : -1;
}

int send_u16(int fd, uint16_t val) {
    uint16_t be = htobe16(val);
    return (net_write_all(fd, &be, sizeof(be)) == sizeof(be)) ? 0 : -1;
}

int send_u32(int fd, uint32_t val) {
    uint32_t be = htobe32(val);
    return (net_write_all(fd, &be, sizeof(be)) == sizeof(be)) ? 0 : -1;
}

int send_u64(int fd, uint64_t val) {
    uint64_t be = htobe64(val);
    return (net_write_all(fd, &be, sizeof(be)) == sizeof(be)) ? 0 : -1;
}

int recv_u8(int fd, uint8_t *val) {
    return (net_read_exact(fd, val, 1) == 1) ? 0 : -1;
}

int recv_u16(int fd, uint16_t *val) {
    uint16_t be;
    if (net_read_exact(fd, &be, sizeof(be)) != sizeof(be)) return -1;
    *val = be16toh(be);
    return 0;
}

int recv_u32(int fd, uint32_t *val) {
    uint32_t be;
    if (net_read_exact(fd, &be, sizeof(be)) != sizeof(be)) return -1;
    *val = be32toh(be);
    return 0;
}

int recv_u64(int fd, uint64_t *val) {
    uint64_t be;
    if (net_read_exact(fd, &be, sizeof(be)) != sizeof(be)) return -1;
    *val = be64toh(be);
    return 0;
}

int send_string(int fd, const char *str) {
    uint16_t len = (uint16_t)(str ? strlen(str) : 0);
    if (send_u16(fd, len) != 0) return -1;
    if (len > 0) {
        if (net_write_all(fd, str, len) != (ssize_t)len) return -1;
    }
    return 0;
}

int recv_string(int fd, char *buf, size_t max_len) {
    if (!buf || max_len == 0) return -1;
    uint16_t len = 0;
    if (recv_u16(fd, &len) != 0) return -1;
    size_t to_read = len;
    if (to_read >= max_len) to_read = max_len - 1;
    if (to_read > 0) {
        if (net_read_exact(fd, buf, to_read) != (ssize_t)to_read) return -1;
    }
    buf[to_read] = '\0';
    if (len > to_read) {
        size_t excess = len - to_read;
        char dummy[128];
        while (excess > 0) {
            size_t rd = excess < sizeof(dummy) ? excess : sizeof(dummy);
            if (net_read_exact(fd, dummy, rd) != (ssize_t)rd) return -1;
            excess -= rd;
        }
    }
    return 0;
}

const char *net_get_local_ip(void) {
    static char ip_buf[INET_ADDRSTRLEN] = "127.0.0.1";
    struct ifaddrs *ifaddr, *ifa;
    if (getifaddrs(&ifaddr) == -1) {
        return ip_buf;
    }
    for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
        if (ifa->ifa_flags & IFF_LOOPBACK) continue;
        if (!(ifa->ifa_flags & IFF_UP)) continue;

        struct sockaddr_in *p = (struct sockaddr_in *)ifa->ifa_addr;
        inet_ntop(AF_INET, &p->sin_addr, ip_buf, sizeof(ip_buf));
        break;
    }
    freeifaddrs(ifaddr);
    return ip_buf;
}

int net_discover_host(int port, const char *query, const char *expected_prefix, char *out_ip, size_t max_len, int timeout_ms) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return -1;

    int broadcast = 1;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));

    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in baddr;
    memset(&baddr, 0, sizeof(baddr));
    baddr.sin_family = AF_INET;
    baddr.sin_port = htons(port);
    baddr.sin_addr.s_addr = inet_addr("255.255.255.255");

    sendto(sock, query, strlen(query), 0, (struct sockaddr *)&baddr, sizeof(baddr));

    char buf[256];
    struct sockaddr_in from;
    socklen_t fromlen = sizeof(from);
    ssize_t n = recvfrom(sock, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&from, &fromlen);
    close(sock);

    if (n > 0) {
        buf[n] = '\0';
        if (strncmp(buf, expected_prefix, strlen(expected_prefix)) == 0) {
            inet_ntop(AF_INET, &from.sin_addr, out_ip, max_len);
            return 0;
        }
    }
    return -1;
}

static volatile bool g_discovery_running = false;
static pthread_t g_discovery_thread;
static int g_discovery_sock = -1;

static void *discovery_server_worker(void *arg) {
    (void)arg;
    char buf[256];
    while (g_discovery_running && g_discovery_sock >= 0) {
        struct sockaddr_in client_addr;
        socklen_t addrlen = sizeof(client_addr);
        ssize_t n = recvfrom(g_discovery_sock, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&client_addr, &addrlen);
        if (n <= 0) {
            if (!g_discovery_running) break;
            continue;
        }
        buf[n] = '\0';
        if (strcmp(buf, SS_DISCOVER_FILE) == 0 || strcmp(buf, "SS_DISCOVER_ALL") == 0) {
            char reply[128];
            snprintf(reply, sizeof(reply), "SS_HOST_FILE:%s", net_get_local_ip());
            sendto(g_discovery_sock, reply, strlen(reply), 0, (struct sockaddr *)&client_addr, addrlen);
        }
    }
    return NULL;
}

int net_start_discovery_server(int port, const char *expected_query, const char *reply_prefix) {
    (void)expected_query; (void)reply_prefix;
    if (g_discovery_running) return 0;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return -1;

    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#ifdef SO_REUSEPORT
    setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &reuse, sizeof(reuse));
#endif

    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sock);
        return -1;
    }

    g_discovery_sock = sock;
    g_discovery_running = true;
    if (pthread_create(&g_discovery_thread, NULL, discovery_server_worker, NULL) != 0) {
        g_discovery_running = false;
        close(sock);
        g_discovery_sock = -1;
        return -1;
    }
    return 0;
}

void net_stop_discovery_server(void) {
    if (!g_discovery_running) return;
    g_discovery_running = false;
    if (g_discovery_sock >= 0) {
        close(g_discovery_sock);
        g_discovery_sock = -1;
    }
    pthread_join(g_discovery_thread, NULL);
}
