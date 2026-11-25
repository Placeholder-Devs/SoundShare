package com.example.soundshare;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link AudioPipelineUtils}, written against the FIX_PLAN.md §3 contract.
 */
public class AudioPipelineUtilsTest {

    // ------------------------------------------------------------------ encodeHeaderV2

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    public void encodeHeaderV2_goldenVector_48k16Stereo() {
        byte[] expected = bytes(
                0x53, 0x53, 0x48, 0x44,       // magic "SSHD"
                0x00, 0x00, 0x00, 0x02,       // version 2
                0x00, 0x00, 0xBB, 0x80,       // 48000
                0x00, 0x10,                   // 16 bits
                0x00, 0x02,                   // 2 channels
                0x00, 0x00, 0x13, 0x88,       // fpb 5000
                0x00, 0x00, 0x4E, 0x20,       // chunk 20000
                0x00, 0x00,                   // compression 0
                0x00,                         // isFloat false
                0x00);                        // reserved
        assertEquals(28, expected.length);
        byte[] actual = AudioPipelineUtils.encodeHeaderV2(48000, 16, 2, 5000, 20000, 0, false);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void encodeHeaderV2_goldenVector_96k24Stereo() {
        byte[] expected = bytes(
                0x53, 0x53, 0x48, 0x44,       // magic
                0x00, 0x00, 0x00, 0x02,       // version 2
                0x00, 0x01, 0x77, 0x00,       // 96000
                0x00, 0x18,                   // 24 bits
                0x00, 0x02,                   // 2 channels
                0x00, 0x00, 0x07, 0x80,       // fpb 1920
                0x00, 0x00, 0x3C, 0x00,       // chunk 15360 (1920 * 2ch * 4B)
                0x00, 0x00,                   // compression 0
                0x00,                         // isFloat false
                0x00);                        // reserved
        byte[] actual = AudioPipelineUtils.encodeHeaderV2(96000, 24, 2, 1920, 15360, 0, false);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void encodeHeaderV2_floatAndCompressionBytes() {
        byte[] h = AudioPipelineUtils.encodeHeaderV2(44100, 32, 1, 441, 1764, 1, true);
        assertEquals(28, h.length);
        assertEquals(0x00, h[24]);
        assertEquals(0x01, h[25]);   // compression 1, big-endian short
        assertEquals(0x01, h[26]);   // isFloat
        assertEquals(0x00, h[27]);   // reserved
    }

    /** Copy of the original MainActivity.writeFormatHeader body (minus the stream write). */
    private static byte[] referenceHeader(int sr, int bits, int ch, int fpb, int chunk, int comp, boolean isFloat) {
        byte[] header = new byte[28];
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.putInt(0x53534844);
        buf.putInt(2);
        buf.putInt(sr);
        buf.putShort((short) bits);
        buf.putShort((short) ch);
        buf.putInt(fpb);
        buf.putInt(chunk);
        buf.putShort((short) comp);
        buf.put((byte) (isFloat ? 1 : 0));
        buf.put((byte) 0);
        return header;
    }

    @Test
    public void encodeHeaderV2_matchesOriginalWriterForAllPresets() {
        // Old QUALITY_PRESETS plus the new fpb values from fix #6.
        int[][] presets = {
                {44100, 1, 32, 16}, {44100, 2, 32, 16}, {48000, 2, 5000, 16}, {48000, 2, 4800, 24},
                {48000, 2, 9600, 24}, {96000, 2, 192000, 24}, {192000, 2, 192000, 24},
                {44100, 1, 441, 16}, {44100, 2, 441, 16}, {96000, 2, 1920, 24}, {192000, 2, 3840, 24},
        };
        for (int[] p : presets) {
            int bps = p[3] == 16 ? 2 : 4;
            int chunk = p[2] * p[1] * bps;
            for (int comp = 0; comp <= 1; comp++) {
                for (int f = 0; f <= 1; f++) {
                    boolean isFloat = f == 1;
                    assertArrayEquals(Arrays.toString(p),
                            referenceHeader(p[0], p[3], p[1], p[2], chunk, comp, isFloat),
                            AudioPipelineUtils.encodeHeaderV2(p[0], p[3], p[1], p[2], chunk, comp, isFloat));
                }
            }
        }
    }

    // ------------------------------------------------------------------ sample/frame sizes

    @Test
    public void bytesPerSample() {
        assertEquals(2, AudioPipelineUtils.bytesPerSample(16, false));
        assertEquals(4, AudioPipelineUtils.bytesPerSample(24, false));
        assertEquals(4, AudioPipelineUtils.bytesPerSample(32, false));
        assertEquals(4, AudioPipelineUtils.bytesPerSample(32, true));
        assertEquals(4, AudioPipelineUtils.bytesPerSample(16, true)); // float always 4
    }

    @Test
    public void bytesPerFrame() {
        assertEquals(2, AudioPipelineUtils.bytesPerFrame(16, 1, false));
        assertEquals(4, AudioPipelineUtils.bytesPerFrame(16, 2, false));
        assertEquals(8, AudioPipelineUtils.bytesPerFrame(24, 2, false));
        assertEquals(4, AudioPipelineUtils.bytesPerFrame(24, 1, false));
        assertEquals(8, AudioPipelineUtils.bytesPerFrame(32, 2, true));
    }

    // ------------------------------------------------------------------ sanitizeFramesPerBuffer

    @Test
    public void sanitizeFramesPerBuffer_zeroAndNegative() {
        assertEquals(960, AudioPipelineUtils.sanitizeFramesPerBuffer(0, 48000));
        assertEquals(960, AudioPipelineUtils.sanitizeFramesPerBuffer(-1, 48000));
        assertEquals(882, AudioPipelineUtils.sanitizeFramesPerBuffer(Integer.MIN_VALUE, 44100));
    }

    @Test
    public void sanitizeFramesPerBuffer_huge() {
        assertEquals(960, AudioPipelineUtils.sanitizeFramesPerBuffer(96001, 48000));
        assertEquals(960, AudioPipelineUtils.sanitizeFramesPerBuffer(Integer.MAX_VALUE, 48000));
        assertEquals(1920, AudioPipelineUtils.sanitizeFramesPerBuffer(192001, 96000));
    }

    @Test
    public void sanitizeFramesPerBuffer_normalAndBoundary() {
        assertEquals(5000, AudioPipelineUtils.sanitizeFramesPerBuffer(5000, 48000));
        assertEquals(32, AudioPipelineUtils.sanitizeFramesPerBuffer(32, 44100));
        assertEquals(1, AudioPipelineUtils.sanitizeFramesPerBuffer(1, 48000));
        // exactly 2 s is still allowed (rule is fpb > sampleRate * 2)
        assertEquals(96000, AudioPipelineUtils.sanitizeFramesPerBuffer(96000, 48000));
        assertEquals(192000, AudioPipelineUtils.sanitizeFramesPerBuffer(192000, 96000));
    }

    // ------------------------------------------------------------------ captureFallbackChain

    @Test
    public void captureFallbackChain_hiResPreset_givesRequestedThen48kThen44k() {
        int[] preset = {96000, 2, 1920, 24, 0, 0};
        int[][] chain = AudioPipelineUtils.captureFallbackChain(preset);
        assertEquals(3, chain.length);
        assertArrayEquals(preset, chain[0]);
        assertArrayEquals(new int[]{48000, 2, 960, 16, 0, 0}, chain[1]);
        assertArrayEquals(new int[]{44100, 2, 882, 16, 0, 0}, chain[2]);
    }

    @Test
    public void captureFallbackChain_48k24_keepsAllThree() {
        int[] preset = {48000, 2, 4800, 24, 0, 0};
        int[][] chain = AudioPipelineUtils.captureFallbackChain(preset);
        assertEquals(3, chain.length);
        assertArrayEquals(preset, chain[0]);
        assertEquals(48000, chain[1][0]);
        assertEquals(16, chain[1][3]);
        assertEquals(44100, chain[2][0]);
    }

    @Test
    public void captureFallbackChain_48k16_dedupes() {
        int[] preset = {48000, 2, 5000, 16, 0, 0};
        int[][] chain = AudioPipelineUtils.captureFallbackChain(preset);
        assertEquals(2, chain.length);
        assertArrayEquals(preset, chain[0]);   // the preset row (its own fpb) is kept
        assertArrayEquals(new int[]{44100, 2, 882, 16, 0, 0}, chain[1]);
    }

    @Test
    public void captureFallbackChain_preservesChannelCount() {
        int[] mono = {44100, 1, 441, 16, 0, 0};
        int[][] chain = AudioPipelineUtils.captureFallbackChain(mono);
        assertEquals(2, chain.length);           // 44.1k/16/mono equals the preset → dropped
        assertArrayEquals(mono, chain[0]);
        assertArrayEquals(new int[]{48000, 1, 960, 16, 0, 0}, chain[1]);
        for (int[] row : chain) assertEquals(1, row[1]);

        int[][] stereo = AudioPipelineUtils.captureFallbackChain(new int[]{192000, 2, 3840, 24, 0, 0});
        for (int[] row : stereo) assertEquals(2, row[1]);
    }

    // ------------------------------------------------------------------ buffer math

    @Test
    public void senderSocketBufferBytes_floorIs256KiB() {
        assertEquals(256 * 1024, AudioPipelineUtils.senderSocketBufferBytes(44100, 1, 2));
        assertEquals(256 * 1024, AudioPipelineUtils.senderSocketBufferBytes(48000, 2, 2));
        assertEquals(256 * 1024, AudioPipelineUtils.senderSocketBufferBytes(48000, 2, 4)); // 192000 < 262144
    }

    @Test
    public void senderSocketBufferBytes_halfSecondRule() {
        assertEquals(384000, AudioPipelineUtils.senderSocketBufferBytes(96000, 2, 4));
        assertEquals(768000, AudioPipelineUtils.senderSocketBufferBytes(192000, 2, 4));
        // exactly at the floor and just above
        assertEquals(262144, AudioPipelineUtils.senderSocketBufferBytes(131072, 2, 2));
        assertEquals(262146, AudioPipelineUtils.senderSocketBufferBytes(131073, 2, 2));
    }

    @Test
    public void userBufferBytes() {
        assertEquals(8000, AudioPipelineUtils.userBufferBytes(0, 4, 8000));
        assertEquals(8000, AudioPipelineUtils.userBufferBytes(-10, 4, 8000));
        assertEquals(8000, AudioPipelineUtils.userBufferBytes(1000, 4, 8000));   // 4000 < min
        assertEquals(32768, AudioPipelineUtils.userBufferBytes(4096, 8, 8000));
        assertEquals(8000, AudioPipelineUtils.userBufferBytes(2000, 4, 8000));   // equal
    }

    // ------------------------------------------------------------------ applyDsp (16-bit stereo equivalence)

    /**
     * Verbatim copy of the ORIGINAL SettingsFragment.applyDspProcessing (16-bit stereo only),
     * taken from _backup/app/src/main/java/com/example/soundshare/SettingsFragment.java.
     */
    private static void referenceApplyDsp16Stereo(byte[] buffer, int offset, int length,
                                                  float gainMultiplier, int channelMode) {
        if (buffer == null || length <= 0 || (gainMultiplier == 1.0f && channelMode == 0)) {
            return;
        }
        int frameCount = length / 4;
        for (int i = 0; i < frameCount; i++) {
            int frameOffset = offset + (i * 4);

            int leftSample = (short) ((buffer[frameOffset] & 0xFF) | (buffer[frameOffset + 1] << 8));
            int rightSample = (short) ((buffer[frameOffset + 2] & 0xFF) | (buffer[frameOffset + 3] << 8));

            if (channelMode == 1) {
                int mono = (leftSample + rightSample) / 2;
                leftSample = mono;
                rightSample = mono;
            } else if (channelMode == 2) {
                int temp = leftSample;
                leftSample = rightSample;
                rightSample = temp;
            }

            if (gainMultiplier != 1.0f) {
                leftSample = (int) Math.round(leftSample * gainMultiplier);
                rightSample = (int) Math.round(rightSample * gainMultiplier);
                leftSample = Math.max(-32768, Math.min(32767, leftSample));
                rightSample = Math.max(-32768, Math.min(32767, rightSample));
            }

            buffer[frameOffset] = (byte) (leftSample & 0xFF);
            buffer[frameOffset + 1] = (byte) ((leftSample >> 8) & 0xFF);
            buffer[frameOffset + 2] = (byte) (rightSample & 0xFF);
            buffer[frameOffset + 3] = (byte) ((rightSample >> 8) & 0xFF);
        }
    }

    @Test
    public void applyDsp_16bitStereo_byteIdenticalToOriginal() {
        // dB slider values the settings UI can produce (10^(dB/20)), plus edge cases.
        float[] gains = {1.0f, 0.0f, 0.5f, 0.70794576f, 1.4125376f, 2.0f, 3.9810717f, 10.0f, -1.0f};
        int[] modes = {0, 1, 2};
        Random rnd = new Random(0x5053L);
        for (float gain : gains) {
            for (int mode : modes) {
                for (int iter = 0; iter < 40; iter++) {
                    int size = 64 + rnd.nextInt(4096);
                    byte[] original = new byte[size];
                    rnd.nextBytes(original);
                    if (iter % 5 == 0) {
                        // full-scale samples to exercise clamping
                        for (int k = 0; k + 1 < size; k += 2) {
                            boolean pos = rnd.nextBoolean();
                            original[k] = (byte) (pos ? 0xFF : 0x00);
                            original[k + 1] = (byte) (pos ? 0x7F : 0x80);
                        }
                    }
                    int off = rnd.nextInt(8);
                    int len = rnd.nextInt(size - off + 1);   // includes 0 and non-multiples of 4

                    byte[] expected = original.clone();
                    byte[] actual = original.clone();
                    referenceApplyDsp16Stereo(expected, off, len, gain, mode);
                    AudioPipelineUtils.applyDsp(actual, off, len, gain, mode, 2, 2);
                    assertArrayEquals("gain=" + gain + " mode=" + mode + " off=" + off + " len=" + len,
                            expected, actual);
                }
            }
        }
    }

    // ------------------------------------------------------------------ applyDsp (other formats)

    private static byte[] i32le(int... samples) {
        ByteBuffer b = ByteBuffer.allocate(samples.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int s : samples) b.putInt(s);
        return b.array();
    }

    private static int[] readI32le(byte[] buf) {
        ByteBuffer b = ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[buf.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = b.getInt();
        return out;
    }

    private static byte[] i16le(int... samples) {
        ByteBuffer b = ByteBuffer.allocate(samples.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int s : samples) b.putShort((short) s);
        return b.array();
    }

    @Test
    public void applyDsp_32bit_gainDoublesAndClamps() {
        byte[] buf = i32le(1000, -1000, Integer.MAX_VALUE / 2 + 10, Integer.MIN_VALUE / 2 - 10,
                Integer.MAX_VALUE, Integer.MIN_VALUE);
        AudioPipelineUtils.applyDsp(buf, 0, buf.length, 2.0f, 0, 4, 2);
        assertArrayEquals(new int[]{2000, -2000, Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE}, readI32le(buf));
    }

    @Test
    public void applyDsp_32bit_monoDownmixAverages() {
        byte[] buf = i32le(1000, 3000, -4000, 2000, Integer.MAX_VALUE, Integer.MAX_VALUE - 2);
        AudioPipelineUtils.applyDsp(buf, 0, buf.length, 1.0f, 1, 4, 2);
        assertArrayEquals(new int[]{2000, 2000, -1000, -1000,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1}, readI32le(buf));
    }

    @Test
    public void applyDsp_32bit_swapSwaps() {
        byte[] buf = i32le(1, -2, 123456789, -987654321);
        AudioPipelineUtils.applyDsp(buf, 0, buf.length, 1.0f, 2, 4, 2);
        assertArrayEquals(new int[]{-2, 1, -987654321, 123456789}, readI32le(buf));
    }

    @Test
    public void applyDsp_32bit_respectsOffsetAndLength() {
        byte[] buf = i32le(7, 7, 100, 200, 9, 9);
        AudioPipelineUtils.applyDsp(buf, 8, 8, 1.0f, 2, 4, 2);
        assertArrayEquals(new int[]{7, 7, 200, 100, 9, 9}, readI32le(buf));
    }

    @Test
    public void applyDsp_mono_ignoresRouting() {
        byte[] b32 = i32le(10, 20, 30, 40);
        byte[] b32copy = b32.clone();
        AudioPipelineUtils.applyDsp(b32, 0, b32.length, 1.0f, 1, 4, 1);
        assertArrayEquals(b32copy, b32);
        AudioPipelineUtils.applyDsp(b32, 0, b32.length, 1.0f, 2, 4, 1);
        assertArrayEquals(b32copy, b32);

        byte[] b16 = i16le(10, 20, 30, 40);
        byte[] b16copy = b16.clone();
        AudioPipelineUtils.applyDsp(b16, 0, b16.length, 1.0f, 1, 2, 1);
        assertArrayEquals(b16copy, b16);
        AudioPipelineUtils.applyDsp(b16, 0, b16.length, 1.0f, 2, 2, 1);
        assertArrayEquals(b16copy, b16);
    }

    @Test
    public void applyDsp_mono_gainStillApplied() {
        byte[] b32 = i32le(10, -20, Integer.MAX_VALUE - 5);
        AudioPipelineUtils.applyDsp(b32, 0, b32.length, 2.0f, 2, 4, 1);  // routing ignored
        assertArrayEquals(new int[]{20, -40, Integer.MAX_VALUE}, readI32le(b32));

        byte[] b16 = i16le(10, -20, 30000);
        AudioPipelineUtils.applyDsp(b16, 0, b16.length, 2.0f, 1, 2, 1);
        assertArrayEquals(i16le(20, -40, 32767), b16);
    }

    @Test
    public void applyDsp_noOpLeavesBufferUnchanged() {
        Random rnd = new Random(7L);
        int[][] formats = {{2, 1}, {2, 2}, {4, 1}, {4, 2}};
        for (int[] f : formats) {
            byte[] buf = new byte[256];
            rnd.nextBytes(buf);
            byte[] copy = buf.clone();
            AudioPipelineUtils.applyDsp(buf, 0, buf.length, 1.0f, 0, f[0], f[1]);
            assertArrayEquals(copy, buf);
            AudioPipelineUtils.applyDsp(buf, 0, 0, 2.0f, 1, f[0], f[1]);       // len 0
            assertArrayEquals(copy, buf);
            AudioPipelineUtils.applyDsp(buf, 0, -4, 2.0f, 1, f[0], f[1]);      // negative len
            assertArrayEquals(copy, buf);
        }
        AudioPipelineUtils.applyDsp(null, 0, 16, 2.0f, 1, 2, 2);               // must not throw
    }

    // ------------------------------------------------------------------ convert32to16 / isSilent

    @Test
    public void convert32to16_takesHigh16BitsLittleEndian() {
        byte[] src = i32le(0x12345678, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x0000FFFF, 0x00010000);
        byte[] dst = new byte[src.length / 2];
        int written = AudioPipelineUtils.convert32to16(src, 0, src.length, dst);
        assertEquals(src.length / 2, written);
        assertArrayEquals(i16le(0x1234, -1, (short) 0x8000, 0x7FFF, 0x0000, 0x0001), dst);
    }

    @Test
    public void convert32to16_withOffset() {
        byte[] body = i32le(0x7FFF0000, 0x00020003);
        byte[] src = new byte[4 + body.length];
        src[0] = 1; src[1] = 2; src[2] = 3; src[3] = 4;
        System.arraycopy(body, 0, src, 4, body.length);
        byte[] dst = new byte[4];
        assertEquals(4, AudioPipelineUtils.convert32to16(src, 4, body.length, dst));
        assertArrayEquals(i16le(0x7FFF, 0x0002), dst);
    }

    @Test
    public void isSilent() {
        byte[] z = new byte[64];
        assertTrue(AudioPipelineUtils.isSilent(z, 0, z.length));
        assertTrue(AudioPipelineUtils.isSilent(z, 10, 0));
        z[20] = 1;
        assertFalse(AudioPipelineUtils.isSilent(z, 0, z.length));
        assertTrue(AudioPipelineUtils.isSilent(z, 21, 43));   // non-zero byte outside the range
        assertTrue(AudioPipelineUtils.isSilent(z, 0, 20));
        assertFalse(AudioPipelineUtils.isSilent(z, 20, 1));
        z[63] = (byte) 0x80;
        assertFalse(AudioPipelineUtils.isSilent(z, 21, 43));
    }
}
