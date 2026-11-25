#define _POSIX_C_SOURCE 200809L
#include "soundshare_time.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <getopt.h>
#include <unistd.h>
#include <libgen.h>

static void sig_handler(int sig) {
    (void)sig;
    printf("\n[SHUTDOWN] Terminating gracefully...\n");
    exit(0);
}

static void print_usage(const char *prog_name) {
    printf("SoundShare Time - Linux Network Clock & Latency Sync Tool (C Edition)\n\n");
    printf("Usage:\n");
    printf("  %s gui                 Launch Graphical User Interface (GTK3)\n", prog_name);
    printf("  %s server [options]    Run as Time Host (listens on ports 5006 & 5001)\n", prog_name);
    printf("  %s client <ip> [opts]  Connect to SoundShare Host (Android or Linux)\n\n", prog_name);
    printf("Commands:\n");
    printf("  gui                    Launch desktop GUI\n");
    printf("  server                 Run headless time server\n");
    printf("  client <server_ip>     Run headless client and calibrate clock\n\n");
    printf("Options:\n");
    printf("  -p, --port <port>      Clock Sync Port (default: %d)\n", SOUNDSHARE_SYNC_CTRL_PORT);
    printf("  -l, --ping-port <port> Ping / Latency Port (default: %d)\n", SOUNDSHARE_PING_PORT);
    printf("  -i, --interval <ms>    Pulse / Ping Interval in ms (default: 500)\n");
    printf("  -s, --samples <num>    NTP Calibration Sample Count (default: %d)\n", DEFAULT_NTP_SAMPLES);
    printf("  -v, --verbose          Enable verbose output\n");
    printf("  -h, --help             Display this help message\n\n");
    printf("Examples:\n");
    printf("  %s gui\n", prog_name);
    printf("  %s server\n", prog_name);
    printf("  %s client 192.168.1.50 -v --interval 250\n", prog_name);
}

int main(int argc, char *argv[]) {
    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);

    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    if (strcmp(argv[1], "-h") == 0 || strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "help") == 0) {
        print_usage(argv[0]);
        return 0;
    }

    if (strcmp(argv[1], "gui") == 0 || strcmp(argv[1], "--gui") == 0) {
        char exe_dir[1024];
        char gui_path[1024];
        ssize_t len = readlink("/proc/self/exe", exe_dir, sizeof(exe_dir) - 1);
        if (len > 0) {
            exe_dir[len] = '\0';
            char *dir = dirname(exe_dir);
            snprintf(gui_path, sizeof(gui_path), "%s/soundshare_time_gui", dir);
        } else {
            snprintf(gui_path, sizeof(gui_path), "./bin/soundshare_time_gui");
        }
        argv[1] = gui_path;
        execv(gui_path, &argv[1]);
        /* Fallback */
        perror("Failed to launch GUI binary");
        return 1;
    }

    bool is_server = false;
    bool is_client = false;
    const char *server_ip = NULL;

    if (strcmp(argv[1], "server") == 0) {
        is_server = true;
    } else if (strcmp(argv[1], "client") == 0) {
        is_client = true;
        if (argc < 3 || argv[2][0] == '-') {
            fprintf(stderr, "Error: 'client' mode requires <server_ip>\n\n");
            print_usage(argv[0]);
            return 1;
        }
        server_ip = argv[2];
    } else {
        fprintf(stderr, "Error: Unknown command '%s'\n\n", argv[1]);
        print_usage(argv[0]);
        return 1;
    }

    const char *audio_file = NULL;
    int opt_sync_port = SOUNDSHARE_SYNC_CTRL_PORT;
    int opt_ping_port = SOUNDSHARE_PING_PORT;
    int opt_interval = 500;
    int opt_samples = DEFAULT_NTP_SAMPLES;
    bool opt_verbose = false;

    if (is_client && argc > 3 && argv[3][0] != '-') {
        audio_file = argv[3];
        optind = 4;
    } else {
        optind = is_client ? 3 : 2;
    }

    static struct option long_options[] = {
        {"port",      required_argument, 0, 'p'},
        {"ping-port", required_argument, 0, 'l'},
        {"interval",  required_argument, 0, 'i'},
        {"samples",   required_argument, 0, 's'},
        {"file",      required_argument, 0, 'f'},
        {"verbose",   no_argument,       0, 'v'},
        {"help",      no_argument,       0, 'h'},
        {0, 0, 0, 0}
    };

    int c;
    while ((c = getopt_long(argc, argv, "p:l:i:s:f:vh", long_options, NULL)) != -1) {
        switch (c) {
            case 'p':
                opt_sync_port = atoi(optarg);
                break;
            case 'l':
                opt_ping_port = atoi(optarg);
                break;
            case 'i':
                opt_interval = atoi(optarg);
                break;
            case 's':
                opt_samples = atoi(optarg);
                break;
            case 'f':
                audio_file = optarg;
                break;
            case 'v':
                opt_verbose = true;
                break;
            case 'h':
                print_usage(argv[0]);
                return 0;
            default:
                print_usage(argv[0]);
                return 1;
        }
    }

    if (is_server) {
        server_config_t cfg;
        cfg.sync_port = opt_sync_port;
        cfg.ping_port = opt_ping_port;
        cfg.pulse_interval_ms = opt_interval;
        cfg.verbose = opt_verbose;
        return run_server(&cfg);
    } else if (is_client) {
        client_config_t cfg;
        cfg.server_ip = server_ip;
        cfg.audio_file = audio_file;
        cfg.sync_port = opt_sync_port;
        cfg.ping_port = opt_ping_port;
        cfg.ping_interval_ms = opt_interval;
        cfg.ntp_samples = opt_samples;
        cfg.verbose = opt_verbose;
        return run_client(&cfg);
    }

    return 0;
}
