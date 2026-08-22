package com.example.soundshare;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class FlacCodec {

    private static final String TAG = "FlacCodec";
    private static final String MIME_FLAC = "audio/flac";
    private static final long TIMEOUT_US = 10_000;

    // ============ ENCODER ============
    public static class Encoder {
        private MediaCodec codec;
        private final int sampleRate;
        private final int channels;
        private final int bitsPerSample;
        private final int pcmEncoding;
        private boolean started = false;
        private long presentationTimeUs = 0;
        private final int framesPerBuffer;

        public Encoder(int sampleRate, int channels, int bitsPerSample,
                       int framesPerBuffer) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.framesPerBuffer = framesPerBuffer;

            if (bitsPerSample == 32) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.pcmEncoding = android.media.AudioFormat.ENCODING_PCM_32BIT;
                } else {
                    this.pcmEncoding = android.media.AudioFormat.ENCODING_PCM_FLOAT;
                }
            } else if (bitsPerSample == 24) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.pcmEncoding = android.media.AudioFormat.ENCODING_PCM_32BIT;
                } else {
                    this.pcmEncoding = android.media.AudioFormat.ENCODING_PCM_FLOAT;
                }
            } else {
                this.pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            }
        }

        public boolean start() {
            try {
                codec = MediaCodec.createEncoderByType(MIME_FLAC);
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, MIME_FLAC);
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);

                int compressionLevel = sampleRate > 48000 ? 0 : 1;
                format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL,
                        compressionLevel);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, pcmEncoding);
                }

                codec.configure(format, null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                started = true;
                Log.i(TAG, String.format("FLAC encoder: %dHz %dch %dbit lvl%d",
                        sampleRate, channels, bitsPerSample, compressionLevel));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "FLAC encoder start failed: " + e.getMessage(), e);
                release();
                return false;
            }
        }

        public byte[] encode(byte[] pcmData, int length) {
            if (!started || codec == null) return null;
            try {
                int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(pcmData, 0,
                                Math.min(length, inputBuffer.remaining()));
                        long pts = presentationTimeUs;
                        int bytesPerSample = bitsPerSample <= 16 ? 2 : 4;
                        int frames = length / (channels * bytesPerSample);
                        presentationTimeUs += (frames * 1_000_000L) / sampleRate;
                        codec.queueInputBuffer(inputIndex, 0, length, pts, 0);
                    }
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                while (true) {
                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo,
                            TIMEOUT_US);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] chunk = new byte[bufferInfo.size];
                            outputBuffer.get(chunk);
                            outputStream.write(chunk);
                        }
                        codec.releaseOutputBuffer(outputIndex, false);
                    } else if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Encoder format: " + codec.getOutputFormat());
                    } else {
                        break;
                    }
                }

                byte[] result = outputStream.toByteArray();
                return result.length > 0 ? result : null;
            } catch (Exception e) {
                Log.e(TAG, "Encode error: " + e.getMessage());
                return null;
            }
        }

        public void release() {
            started = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
    }

    // ============ DECODER ============
    public static class Decoder {
        private MediaCodec codec;
        private final int sampleRate;
        private final int channels;
        private final int bitsPerSample;
        private boolean started = false;
        private long presentationTimeUs = 0;

        public Decoder(int sampleRate, int channels, int bitsPerSample) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }

        public boolean start() {
            try {
                codec = MediaCodec.createDecoderByType(MIME_FLAC);
                MediaFormat format = MediaFormat.createAudioFormat(
                        MIME_FLAC, sampleRate, channels);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    int pcmEnc;
                    if (bitsPerSample == 32) {
                        pcmEnc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? android.media.AudioFormat.ENCODING_PCM_32BIT
                                : android.media.AudioFormat.ENCODING_PCM_FLOAT;
                    } else if (bitsPerSample == 24) {
                        pcmEnc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                ? android.media.AudioFormat.ENCODING_PCM_32BIT
                                : android.media.AudioFormat.ENCODING_PCM_FLOAT;
                    } else {
                        pcmEnc = android.media.AudioFormat.ENCODING_PCM_16BIT;
                    }
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, pcmEnc);
                }

                codec.configure(format, null, null, 0);
                codec.start();
                started = true;
                Log.i(TAG, String.format("FLAC decoder: %dHz %dch %dbit",
                        sampleRate, channels, bitsPerSample));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "FLAC decoder start failed: " + e.getMessage(), e);
                release();
                return false;
            }
        }

        public byte[] decode(byte[] flacData, int length) {
            if (!started || codec == null) return null;
            try {
                int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(flacData, 0,
                                Math.min(length, inputBuffer.remaining()));
                        codec.queueInputBuffer(inputIndex, 0, length,
                                presentationTimeUs, 0);
                        presentationTimeUs += 10_000;
                    }
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                while (true) {
                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo,
                            TIMEOUT_US);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            byte[] chunk = new byte[bufferInfo.size];
                            outputBuffer.get(chunk);
                            outputStream.write(chunk);
                        }
                        codec.releaseOutputBuffer(outputIndex, false);
                    } else if (outputIndex ==
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Decoder format: " + codec.getOutputFormat());
                    } else {
                        break;
                    }
                }

                byte[] result = outputStream.toByteArray();
                return result.length > 0 ? result : null;
            } catch (Exception e) {
                Log.e(TAG, "Decode error: " + e.getMessage());
                return null;
            }
        }

        public void release() {
            started = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
    }

    // ============ UTILITY ============
    public static boolean isFlacEncoderAvailable() {
        try {
            MediaCodec codec = MediaCodec.createEncoderByType(MIME_FLAC);
            codec.release();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isFlacDecoderAvailable() {
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(MIME_FLAC);
            codec.release();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}