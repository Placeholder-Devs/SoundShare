#define _POSIX_C_SOURCE 200809L
#include "soundshare_time.h"

#include <time.h>
#include <stdio.h>

int64_t time_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (ts.tv_nsec / 1000000LL);
}

int64_t time_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

void time_sleep_ms(int ms) {
    if (ms <= 0) return;
    struct timespec req;
    req.tv_sec = ms / 1000;
    req.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&req, NULL);
}

void format_time_str(int64_t epoch_ms, char *buf, size_t buf_size) {
    if (!buf || buf_size == 0) return;
    time_t sec = (time_t)(epoch_ms / 1000LL);
    int millis = (int)(epoch_ms % 1000LL);
    if (millis < 0) millis += 1000;

    struct tm tm_info;
    localtime_r(&sec, &tm_info);

    char temp[64];
    strftime(temp, sizeof(temp), "%Y-%m-%d %H:%M:%S", &tm_info);
    snprintf(buf, buf_size, "%s.%03d", temp, millis);
}
