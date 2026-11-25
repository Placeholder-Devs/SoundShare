#ifndef AUDIO_PLAYER_H
#define AUDIO_PLAYER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Initialize GStreamer backend */
int audio_player_init(void);

/* Load an audio file (MP3, FLAC, WAV, AAC, OGG, etc.) */
int audio_player_load(const char *filepath);

/* Schedule playback to start at target timestamp (in local clock ms) from position pos_ms */
void audio_player_schedule_play(int64_t pos_ms, int64_t play_at_ms);

/* Schedule playback to pause at target timestamp */
void audio_player_schedule_pause(int64_t pos_ms, int64_t pause_at_ms);

/* Immediate pause */
void audio_player_pause(void);

/* Immediate play */
void audio_player_play(void);

/* Seek to position in ms */
void audio_player_seek(int64_t pos_ms);

/* Adjust playback rate for drift compensation (e.g. 1.02, 0.98, 1.0) */
void audio_player_adjust_speed(double rate);

/* Get current playback position in milliseconds */
int64_t audio_player_get_position_ms(void);

/* Get total duration in milliseconds */
int64_t audio_player_get_duration_ms(void);

/* Check if an audio file is currently loaded in pipeline */
bool audio_player_is_loaded(void);

/* Check if currently playing */
bool audio_player_is_playing(void);

/* Stop playback and release pipeline */
void audio_player_stop(void);

#ifdef __cplusplus
}
#endif

#endif /* AUDIO_PLAYER_H */
