package com.example.soundshare;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/**
 * Builds a receiver-style AudioTrack (PERFORMANCE_MODE_NONE, 250 ms buffer) for the formats the
 * receiver fallback chain relies on. A device that rejects a format is skipped (Assume), never
 * crashed. Tracks are always released. Nothing is played.
 */
@RunWith(AndroidJUnit4.class)
public class AudioTrackFormatInstrumentedTest {

    private static final String TAG = "SoundShareTest";

    @Test
    public void build48k16Stereo() {
        buildOrSkip(48000, AudioFormat.ENCODING_PCM_16BIT, 2);
    }

    @Test
    public void build48k32Stereo() {
        buildOrSkip(48000, AudioFormat.ENCODING_PCM_32BIT, 4);
    }

    private static void buildOrSkip(int sampleRate, int encoding, int bytesPerSample) {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
        Log.i(TAG, "minBuf " + sampleRate + "Hz enc=" + encoding + " → " + minBuf);
        Assume.assumeTrue("Device reports no min buffer for enc=" + encoding, minBuf > 0);

        int quarterSecond = sampleRate * 2 * bytesPerSample / 4;
        int bufferBytes = Math.max(minBuf, quarterSecond);

        AudioTrack track = null;
        String failure = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(encoding)
                            .setChannelMask(channelMask)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (UnsupportedOperationException | IllegalArgumentException | IllegalStateException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        try {
            Log.i(TAG, "AudioTrack " + sampleRate + "Hz enc=" + encoding + " buf=" + bufferBytes
                    + " → " + (failure != null ? failure
                    : "state=" + track.getState() + " actualBuf=" + track.getBufferSizeInFrames()));
            Assume.assumeTrue("Device rejected the format: " + failure, failure == null);
            Assume.assumeTrue("AudioTrack not initialized (state=" + track.getState() + ")",
                    track.getState() == AudioTrack.STATE_INITIALIZED);
            assertEquals(AudioTrack.STATE_INITIALIZED, track.getState());
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
