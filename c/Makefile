CC ?= gcc
CFLAGS ?= -Wall -Wextra -pedantic -Wno-overlength-strings -std=c11 -O2 -Iinclude -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE
LDFLAGS ?= -pthread -lm

GST_CFLAGS ?= $(shell pkg-config --cflags gstreamer-1.0 2>/dev/null)
GST_LIBS ?= $(shell pkg-config --libs gstreamer-1.0 2>/dev/null)

GTK_CFLAGS ?= $(shell pkg-config --cflags gtk+-3.0 2>/dev/null)
GTK_LIBS ?= $(shell pkg-config --libs gtk+-3.0 2>/dev/null)

PULSE_CFLAGS ?= $(shell pkg-config --cflags libpulse-simple 2>/dev/null)
PULSE_LIBS ?= $(shell pkg-config --libs libpulse-simple 2>/dev/null)

CFLAGS += $(GST_CFLAGS) $(PULSE_CFLAGS)
LDFLAGS += $(GST_LIBS) $(PULSE_LIBS)

SRC_DIR = src
INC_DIR = include
BUILD_DIR = build
BIN_DIR = bin

TARGET_CLI = $(BIN_DIR)/soundshare_time
TARGET_GUI = $(BIN_DIR)/soundshare_time_gui

COMMON_SRCS = $(SRC_DIR)/server.c \
              $(SRC_DIR)/client.c \
              $(SRC_DIR)/net_util.c \
              $(SRC_DIR)/time_util.c \
              $(SRC_DIR)/audio_player.c \
              $(SRC_DIR)/song_transfer.c \
              $(SRC_DIR)/stream_receiver.c

COMMON_OBJS = $(patsubst $(SRC_DIR)/%.c, $(BUILD_DIR)/%.o, $(COMMON_SRCS))

CLI_OBJS = $(BUILD_DIR)/main.o $(COMMON_OBJS)
GUI_OBJS = $(BUILD_DIR)/gui.o $(COMMON_OBJS)

.PHONY: all clean test cli gui

all: $(TARGET_CLI) $(TARGET_GUI)

cli: $(TARGET_CLI)
gui: $(TARGET_GUI)

# CLI Binary
$(TARGET_CLI): $(CLI_OBJS) | $(BIN_DIR)
	$(CC) $(CLI_OBJS) -o $@ $(LDFLAGS)
	@echo "Build successful (CLI): $@"

# GUI Binary
$(TARGET_GUI): $(GUI_OBJS) | $(BIN_DIR)
	$(CC) $(GUI_OBJS) -o $@ $(LDFLAGS) $(GTK_LIBS)
	@echo "Build successful (GUI): $@"

$(BUILD_DIR)/gui.o: $(SRC_DIR)/gui.c | $(BUILD_DIR)
	$(CC) $(CFLAGS) $(GTK_CFLAGS) -c $< -o $@

$(BUILD_DIR)/%.o: $(SRC_DIR)/%.c | $(BUILD_DIR)
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

clean:
	rm -rf $(BUILD_DIR) $(BIN_DIR)
	@echo "Clean completed."

test: $(TARGET_CLI)
	@echo "Running sanity tests..."
	./$(TARGET_CLI) --help
	@echo "All sanity tests passed!"
