package com.example.soundshare;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

public class HiResAudioHelper {

    private static final String TAG = "HiResAudio";

    public static class Capabilities {
        public final boolean supports96kHz;
        public final boolean supports192kHz;
        public final boolean supports24bit;
        public final boolean supports32bit;
        public final boolean supportsFloat;
        public final boolean supportsFlacEncode;
        public final boolean supportsFlacDecode;
        public final int maxSampleRate;
        public final int maxBitDepth;

        Capabilities(boolean s96, boolean s192, boolean s24, boolean s32,
                     boolean sFloat, boolean sFlacEnc, boolean sFlacDec) {
            this.supports96kHz = s96;
            this.supports192kHz = s192;
            this.supports24bit = s24;
            this.supports32bit = s32;
            this.supportsFloat = sFloat;
            this.supportsFlacEncode = sFlacEnc;
            this.supportsFlacDecode = sFlacDec;

            if (s192) maxSampleRate = 192000;
            else if (s96) maxSampleRate = 96000;
            else maxSampleRate = 48000;

            if (s32) maxBitDepth = 32;
            else if (s24) maxBitDepth = 24;
            else maxBitDepth = 16;
        }

        public boolean isHiResCapable() {
            return supports96kHz && supports24bit;
        }

        @Override
        public String toString() {
            return String.format(
                    "HiRes[96k=%b 192k=%b 24bit=%b 32bit=%b float=%b " +
                            "flacEnc=%b flacDec=%b max=%dkHz/%dbit]",
                    supports96kHz, supports192kHz, supports24bit,
                    supports32bit, supportsFloat,
                    supportsFlacEncode, supportsFlacDecode,
                    maxSampleRate / 1000, maxBitDepth);
        }
    }

    public static Capabilities detectCapabilities() {
        boolean s96 = canPlaybackAtRate(96000);
        boolean s192 = canPlaybackAtRate(192000);
        boolean s24 = canPlaybackBitDepth(24);
        boolean s32 = canPlaybackBitDepth(32);
        boolean sFloat = canPlaybackFloat();
        boolean sFlacEnc = FlacCodec.isFlacEncoderAvailable();
        boolean sFlacDec = FlacCodec.isFlacDecoderAvailable();

        Capabilities caps = new Capabilities(
                s96, s192, s24, s32, sFloat, sFlacEnc, sFlacDec);
        Log.i(TAG, "Device capabilities: " + caps);
        return caps;
    }

    private static boolean canPlaybackAtRate(int sampleRate) {
        try {
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, encoding);
            if (minBuf <= 0) return false;

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            boolean ok = track.getState() == AudioTrack.STATE_INITIALIZED;
            track.release();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canPlaybackBitDepth(int bits) {
        try {
            int encoding;
            if (bits == 32) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    encoding = AudioFormat.ENCODING_PCM_32BIT;
                } else {
                    return false;
                }
            } else if (bits == 24) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    encoding = AudioFormat.ENCODING_PCM_32BIT;
                } else {
                    encoding = AudioFormat.ENCODING_PCM_FLOAT;
                }
            } else {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }

            int minBuf = AudioTrack.getMinBufferSize(
                    48000, AudioFormat.CHANNEL_OUT_STEREO, encoding);
            if (minBuf <= 0) return false;

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            boolean ok = track.getState() == AudioTrack.STATE_INITIALIZED;
            track.release();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canPlaybackFloat() {
        try {
            int encoding = AudioFormat.ENCODING_PCM_FLOAT;
            int minBuf = AudioTrack.getMinBufferSize(
                    48000, AudioFormat.CHANNEL_OUT_STEREO, encoding);
            if (minBuf <= 0) return false;

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            boolean ok = track.getState() == AudioTrack.STATE_INITIALIZED;
            track.release();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}