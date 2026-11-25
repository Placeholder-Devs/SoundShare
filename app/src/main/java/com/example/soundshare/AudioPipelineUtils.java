package com.example.soundshare;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java helpers for the audio pipeline (no android.util.Log, no Context) so they can be
 * unit-tested on the JVM. Contract: FIX_PLAN.md §3.
 */
final class AudioPipelineUtils {

    /** Protocol v2 header constants (must match MainActivity). */
    static final int HEADER_MAGIC = 0x53534844; // "SSHD"
    static final int HEADER_VERSION = 2;
    static final int HEADER_SIZE = 28;

    private AudioPipelineUtils() {}

    /** 16-bit → 2 bytes; 24-bit (carried in 32-bit containers), 32-bit and float → 4 bytes. */
    static int bytesPerSample(int bitsPerSample, boolean isFloat) {
        if (isFloat) return 4;
        return bitsPerSample <= 16 ? 2 : 4;
    }

    static int bytesPerFrame(int bitsPerSample, int channels, boolean isFloat) {
        return bytesPerSample(bitsPerSample, isFloat) * channels;
    }

    /** Guards against a bogus header value: fpb<=0 or fpb>2 s → 20 ms. */
    static int sanitizeFramesPerBuffer(int fpb, int sampleRate) {
        if (fpb <= 0 || (long) fpb > (long) sampleRate * 2L) {
            return sampleRate / 50;
        }
        return fpb;
    }

    /**
     * Capture formats to try, in order: the requested preset, then 48 kHz/16-bit, then 44.1 kHz/16-bit
     * (same channel count). Entries whose (rate, bits, channels) equal an earlier entry are dropped.
     * Rows use the QUALITY_PRESETS layout {sampleRate, channels, framesPerBuffer, bits, compression, isFloat}.
     */
    static int[][] captureFallbackChain(int[] preset) {
        int ch = preset[1];
        int[][] candidates = {
                preset.clone(),
                {48000, ch, 960, 16, 0, 0},
                {44100, ch, 882, 16, 0, 0}
        };
        List<int[]> out = new ArrayList<>();
        for (int[] c : candidates) {
            boolean dup = false;
            for (int[] prev : out) {
                if (prev[0] == c[0] && prev[3] == c[3] && prev[1] == c[1]) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(c);
        }
        return out.toArray(new int[0][]);
    }

    /** SO_SNDBUF for a sender client: at least 256 KB, or 500 ms of audio if larger. */
    static int senderSocketBufferBytes(int sampleRate, int channels, int bytesPerSample) {
        long halfSecond = (long) sampleRate * channels * bytesPerSample / 2L;
        long v = Math.max(256L * 1024L, halfSecond);
        return (int) Math.min(Integer.MAX_VALUE, v);
    }

    /** User buffer setting (in frames) converted with the real frame size; can only raise the minimum. */
    static int userBufferBytes(int userFrames, int bytesPerFrame, int defaultMinBuf) {
        if (userFrames > 0) {
            long userBytes = (long) userFrames * bytesPerFrame;
            return (int) Math.max(Math.min(userBytes, Integer.MAX_VALUE), defaultMinBuf);
        }
        return defaultMinBuf;
    }

    /**
     * In-place gain + channel routing for little-endian PCM.
     * channelMode: 0 none, 1 mono downmix, 2 swap L/R (routing only when channels == 2).
     * bytesPerSample 2 = int16, 4 = int32 container. For (2, 2) this is byte-identical to the
     * original SettingsFragment.applyDspProcessing.
     */
    static void applyDsp(byte[] buf, int off, int len, float gain, int channelMode,
                         int bytesPerSample, int channels) {
        if (buf == null || len <= 0 || (gain == 1.0f && channelMode == 0)) {
            return;
        }
        if (channels < 1) return;
        boolean route = channels == 2 && (channelMode == 1 || channelMode == 2);

        if (bytesPerSample == 2) {
            int frameBytes = 2 * channels;
            int frameCount = len / frameBytes;
            for (int f = 0; f < frameCount; f++) {
                int base = off + f * frameBytes;
                if (channels == 2) {
                    int l = (short) ((buf[base] & 0xFF) | (buf[base + 1] << 8));
                    int r = (short) ((buf[base + 2] & 0xFF) | (buf[base + 3] << 8));
                    if (route) {
                        if (channelMode == 1) {
                            int mono = (l + r) / 2;
                            l = mono;
                            r = mono;
                        } else {
                            int t = l;
                            l = r;
                            r = t;
                        }
                    }
                    if (gain != 1.0f) {
                        l = Math.round(l * gain);
                        r = Math.round(r * gain);
                        l = Math.max(-32768, Math.min(32767, l));
                        r = Math.max(-32768, Math.min(32767, r));
                    }
                    buf[base] = (byte) (l & 0xFF);
                    buf[base + 1] = (byte) ((l >> 8) & 0xFF);
                    buf[base + 2] = (byte) (r & 0xFF);
                    buf[base + 3] = (byte) ((r >> 8) & 0xFF);
                } else {
                    if (gain == 1.0f) continue;
                    for (int c = 0; c < channels; c++) {
                        int p = base + c * 2;
                        int s = (short) ((buf[p] & 0xFF) | (buf[p + 1] << 8));
                        s = Math.round(s * gain);
                        s = Math.max(-32768, Math.min(32767, s));
                        buf[p] = (byte) (s & 0xFF);
                        buf[p + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                }
            }
        } else if (bytesPerSample == 4) {
            int frameBytes = 4 * channels;
            int frameCount = len / frameBytes;
            for (int f = 0; f < frameCount; f++) {
                int base = off + f * frameBytes;
                if (channels == 2) {
                    long l = readI32(buf, base);
                    long r = readI32(buf, base + 4);
                    if (route) {
                        if (channelMode == 1) {
                            long mono = (l + r) / 2L;
                            l = mono;
                            r = mono;
                        } else {
                            long t = l;
                            l = r;
                            r = t;
                        }
                    }
                    if (gain != 1.0f) {
                        l = clamp32(Math.round(l * (double) gain));
                        r = clamp32(Math.round(r * (double) gain));
                    }
                    writeI32(buf, base, (int) l);
                    writeI32(buf, base + 4, (int) r);
                } else {
                    if (gain == 1.0f) continue;
                    for (int c = 0; c < channels; c++) {
                        int p = base + c * 4;
                        long s = clamp32(Math.round(readI32(buf, p) * (double) gain));
                        writeI32(buf, p, (int) s);
                    }
                }
            }
        }
        // Other sample sizes (e.g. float) are left untouched.
    }

    /** Takes the high 16 bits of each little-endian int32; returns the number of bytes written. */
    static int convert32to16(byte[] src, int off, int len, byte[] dst) {
        int frames = len / 4;
        int o = 0;
        for (int i = 0; i < frames; i++) {
            int p = off + i * 4;
            dst[o++] = src[p + 2];
            dst[o++] = src[p + 3];
        }
        return o;
    }

    /** True if every byte in the range is zero. */
    static boolean isSilent(byte[] buf, int off, int len) {
        if (buf == null) return true;
        int end = off + len;
        for (int i = off; i < end; i++) {
            if (buf[i] != 0) return false;
        }
        return true;
    }

    /** 28-byte big-endian protocol v2 header (layout identical to the original writeFormatHeader). */
    static byte[] encodeHeaderV2(int sampleRate, int bitsPerSample, int channels, int framesPerBuffer,
                                 int chunkSize, int compressionType, boolean isFloat) {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer b = ByteBuffer.wrap(header);
        b.putInt(HEADER_MAGIC);                    // 0-3
        b.putInt(HEADER_VERSION);                  // 4-7
        b.putInt(sampleRate);                      // 8-11
        b.putShort((short) bitsPerSample);         // 12-13
        b.putShort((short) channels);              // 14-15
        b.putInt(framesPerBuffer);                 // 16-19
        b.putInt(chunkSize);                       // 20-23
        b.putShort((short) compressionType);       // 24-25
        b.put((byte) (isFloat ? 1 : 0));           // 26
        b.put((byte) 0);                           // 27 reserved
        return header;
    }

    private static long readI32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
    }

    private static void writeI32(byte[] b, int p, int v) {
        b[p] = (byte) (v & 0xFF);
        b[p + 1] = (byte) ((v >> 8) & 0xFF);
        b[p + 2] = (byte) ((v >> 16) & 0xFF);
        b[p + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static long clamp32(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return v;
    }
}
