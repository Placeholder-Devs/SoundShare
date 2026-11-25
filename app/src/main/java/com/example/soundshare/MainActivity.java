package com.example.soundshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.fragment.app.Fragment;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity
        implements StreamFragment.StreamCallback, ReceiveFragment.ReceiveCallback,
                   DuplexFragment.DuplexCallback, FileShareFragment.FileShareCallback {

    private static final String TAG = "SoundShare";
    private static final int REQ_AUDIO = 2001;
    private static final int PORT = 5000;
    private static final int PING_PORT = 5001;
    private static final int CHAT_PORT = 5002;
    private static final int DUPLEX_PORT = 5003;
    private static final int DUPLEX_CHAT_PORT = 5004;

    private static final byte PING_REQUEST = 0x01;
    private static final byte PING_RESPONSE = 0x02;
    private static final byte LATENCY_REPORT = 0x03;
    private static final byte CHAT_MSG = 0x10;
    private static final byte CHAT_METADATA = 0x11;  // server→client: media metadata

    // Control protocol messages (sent via ping channel)
    private static final byte CTRL_PAUSE = 0x20;
    private static final byte CTRL_RESUME = 0x21;
    private static final byte CTRL_SKIP_FWD = 0x22;
    private static final byte CTRL_SKIP_BACK = 0x23;

    // File Share ports & protocol
    private static final int FILE_SHARE_PORT = 5005;
    private static final int SYNC_CTRL_PORT = 5006;
    private static final int FILE_DISCOVERY_PORT = 5007;
    private static final int FILE_SHARE_MAGIC = 0x53534644; // "SSFD"

    private static final byte SYNC_CLOCK_REQ = 0x30;
    private static final byte SYNC_CLOCK_RSP = 0x31;
    private static final byte SYNC_PLAY = 0x32;
    private static final byte SYNC_PAUSE_CMD = 0x33;
    private static final byte SYNC_SEEK = 0x34;
    private static final byte FILE_READY = 0x36;
    private static final byte SYNC_FILE_INCOMING = 0x37;
    private static final byte SYNC_QUEUE_ADD = 0x38;
    private static final byte SYNC_TRACK_CHANGE = 0x39;
    private static final byte SYNC_QUEUE_REMOVE = 0x3A;
    private static final byte SYNC_QUEUE_REORDER = 0x3B;
    private static final byte SYNC_TIME_PULSE = 0x3C;
    private static final byte SYNC_INIT_REQ = 0x3D;

    private static final String[] QUALITY_NAMES = {
            " Ultra Low – 44.1kHz Mono 16-bit",
            " Low Latency – 44.1kHz Stereo 16-bit",
            "️ Balanced – 48kHz Stereo 16-bit",
            " High Quality – 48kHz Stereo 24-bit",
            " Maximum – 48kHz Stereo 24-bit",
            " Hi-Res – 96kHz Stereo 24-bit",
            " Hi-Res Ultra – 192kHz Stereo 24-bit"
    };

    // {sampleRate, channels, framesPerBuffer, bitsPerSample, compression, isFloat}
    private static final int[][] QUALITY_PRESETS = {
            // fpb: presets 0/1 = 10 ms, presets 5/6 = 20 ms (header values only; layout unchanged)
            {44100, 1, 441, 16, 0, 0},
            {44100, 2, 441, 16, 0, 0},
            {48000, 2, 5000, 16, 0, 0},
            {48000, 2, 4800, 24, 0, 0},
            {48000, 2, 9600, 24, 0, 0},
            {96000, 2, 1920, 24, 0, 0},
            {192000, 2, 3840, 24, 0, 0},
    };

    // Protocol v2: 28 byte header
    private static final int HEADER_MAGIC = 0x53534844;
    private static final int HEADER_VERSION = 2;
    private static final int HEADER_SIZE = 28;

    // ===== AUDIO CONFIG =====
    private static class AudioConfig {
        final int sampleRate, bitsPerSample, channels, bytesPerSample, encoding;
        final int channelInMask, channelOutMask, framesPerBuffer, chunkSize;
        final int socketBufferSize, presetIndex;
        final boolean useFLAC;
        final boolean isFloat;
        final boolean isHiRes;
        final int compressionType;

        AudioConfig(int[] preset, int presetIndex) {
            this.presetIndex = presetIndex;
            this.sampleRate = preset[0];
            this.channels = preset[1];
            this.framesPerBuffer = preset[2];
            this.bitsPerSample = preset[3];
            this.compressionType = preset.length > 4 ? preset[4] : 0;
            this.isFloat = preset.length > 5 && preset[5] == 1;
            this.useFLAC = compressionType == 1;
            this.isHiRes = sampleRate > 48000 || bitsPerSample > 24
                    || (bitsPerSample == 24 && sampleRate >= 96000);

            if (isFloat) {
                this.encoding = AudioFormat.ENCODING_PCM_FLOAT;
                this.bytesPerSample = 4;
            } else if (bitsPerSample == 32) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.encoding = AudioFormat.ENCODING_PCM_32BIT;
                    this.bytesPerSample = 4;
                } else {
                    this.encoding = AudioFormat.ENCODING_PCM_FLOAT;
                    this.bytesPerSample = 4;
                }
            } else if (bitsPerSample == 24) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.encoding = AudioFormat.ENCODING_PCM_32BIT;
                    this.bytesPerSample = 4;
                } else {
                    this.encoding = AudioFormat.ENCODING_PCM_FLOAT;
                    this.bytesPerSample = 4;
                }
            } else {
                this.encoding = AudioFormat.ENCODING_PCM_16BIT;
                this.bytesPerSample = 2;
            }

            this.channelInMask = channels == 1
                    ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
            this.channelOutMask = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            this.chunkSize = framesPerBuffer * channels * bytesPerSample;

            if (isHiRes) {
                this.socketBufferSize = chunkSize * 4;
            } else if (useFLAC) {
                this.socketBufferSize = chunkSize * 2;
            } else {
                this.socketBufferSize = chunkSize * (presetIndex <= 1 ? 2 : 4);
            }
        }

        static AudioConfig fromHeader(int sr, int ch, int fpb, int bps,
                                      int comp, int floatFlag) {
            return new AudioConfig(
                    new int[]{sr, ch, fpb, bps, comp, floatFlag},
                    comp == 1 ? 7 : (sr > 48000 ? 8 : 2));
        }

        double bufferLatencyMs() {
            return (framesPerBuffer * 1000.0) / sampleRate;
        }

        long rawBitrateKbps() {
            return ((long) sampleRate * channels * bitsPerSample) / 1000;
        }

        String formatString() {
            long rawBps = (long) sampleRate * channels * bitsPerSample;
            String codec = useFLAC ? "FLAC Lossless" : "PCM";
            String floatStr = isFloat ? " Float" : "";
            String hiresStr = isHiRes ? " [Hi-Res]" : "";
            return String.format(Locale.US, "%s %d-bit%s %s (%.1f Mbps raw)%s",
                    codec, bitsPerSample, floatStr,
                    channels == 1 ? "Mono" : "Stereo",
                    rawBps / 1_000_000.0, hiresStr);
        }

        String channelString() {
            return channels == 1 ? "Mono" : "Stereo";
        }

        String compressionString() {
            if (useFLAC && isHiRes) return "Hi-Res FLAC Lossless";
            if (useFLAC) return "FLAC Lossless";
            if (isHiRes) return "Hi-Res PCM";
            return "Uncompressed PCM";
        }

        String sampleRateString() {
            if (sampleRate >= 1000) {
                return String.format(Locale.US, "%.1f kHz", sampleRate / 1000.0);
            }
            return sampleRate + " Hz";
        }
    }

    private final AtomicReference<AudioConfig> activeConfig =
            new AtomicReference<>(new AudioConfig(QUALITY_PRESETS[2], 2));
    private volatile int selectedPreset = 2;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private int projectionResultCode;
    private Intent projectionResultData;

    // Fragments
    private StreamFragment streamFragment;
    private ReceiveFragment receiveFragment;
    private SettingsFragment settingsFragment;
    private DuplexFragment duplexFragment;
    private FileShareFragment fileShareFragment;
    private BottomNavigationView bottomNav;
    private Fragment activeFragment;
    private String cachedDeviceIp = "Loading...";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Notification
    private ChatNotificationHelper notificationHelper;
    private boolean isAppInForeground = true;

    // Capability detection
    private volatile HiResAudioHelper.Capabilities deviceCaps; // written by the CapCheck thread
    private volatile int[] availablePresetIndices;

    // Stats
    private final AtomicLong bytesSentThisSecond = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong lastTimeMs = new AtomicLong(0);
    private final AtomicLong streamStartTime = new AtomicLong(0);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicLong currentLatencyMs = new AtomicLong(-1);

    // Cached metadata received from host (for receiver-side notification)
    private volatile String receivedMetaTitle = null;
    private volatile String receivedMetaArtist = null;
    private volatile Bitmap receivedAlbumArt = null;
    private volatile long receivedDurationMs = 0;
    private volatile long receivedPositionMs = 0;

    // Clients
    private final CopyOnWriteArrayList<ClientConnection> connectedClients = new CopyOnWriteArrayList<>();
    private final AtomicInteger receiverCount = new AtomicInteger(0);
    private final CopyOnWriteArrayList<ChatClient> chatClients = new CopyOnWriteArrayList<>();

    // Threads
    private volatile Thread acceptThread, streamThread, receiveThread;
    private volatile Thread pingServerThread, pingClientThread;
    private volatile Thread chatServerThread, chatClientThread;

    // Resources
    private volatile ServerSocket serverSocket, pingServerSocket, chatServerSocket;
    private volatile Socket activeSocket, pingSocket, chatSocket;
    private volatile AudioRecord recorder;
    private volatile AudioTrack audioTrack;
    private volatile DataOutputStream chatClientOut;

    // ===== FILE SHARE STATE =====
    private final AtomicBoolean isFileShareActive = new AtomicBoolean(false);
    private final AtomicBoolean isSyncPlaying = new AtomicBoolean(false);
    private final AtomicBoolean peerReady = new AtomicBoolean(false);
    private volatile boolean isFileShareHost = false;
    private volatile android.net.Uri fileShareUri;
    private volatile String fileShareFileName;
    private volatile long fileShareFileSize;
    private volatile String fileShareMimeType;
    private volatile String receivedFilePath;
    private volatile long clockOffset = 0;
    private volatile String connectedPeerIp = null;
    private final List<FileQueueItem> fileShareQueue = new CopyOnWriteArrayList<>();
    private volatile int currentPlayingQueueIndex = -1;
    private volatile int pendingPlayIndex = -1;
    private volatile int currentStereoMode = 0; // 0=Stereo, 1=Left, 2=Right
    private volatile String activeDiscoveryHostMode = null; // "STREAM", "DUPLEX", "FILE"

    // File share networking
    private volatile Thread fileShareServerThread, fileShareClientThread, syncControlThread, discoveryThread;
    private volatile ServerSocket fileShareServerSocket, syncControlServerSocket;
    private volatile Socket fileShareSocket, syncControlSocket;
    private volatile DatagramSocket discoverySocket;
    private WifiManager.MulticastLock discoveryMulticastLock;   // M3: held while answering discovery

    // ---- M3: LAN network tracking (FIX_PLAN #2) ----
    /** Wi-Fi/Ethernet network, tracked even when it is not validated and not the default. */
    private volatile Network lanNetwork;
    private ConnectivityManager.NetworkCallback lanNetworkCallback;
    private volatile boolean vpnHintShown = false;

    // ---- M3: shared ping-channel writer (FIX_PLAN #17) ----
    private volatile DataOutputStream pingOut;
    private final Object pingWriteLock = new Object();
    private static final int PING_READ_TIMEOUT_MS = 1000;
    private static final long PING_IDLE_DROP_MS = 15_000;

    // ---- M3: metadata broadcast throttling (FIX_PLAN #15) ----
    private final java.util.concurrent.ExecutorService metaExecutor = newMetaExecutor();

    /** One worker (FIFO, no pile-up); the idle thread exits after 30 s so nothing leaks. */
    private static java.util.concurrent.ExecutorService newMetaExecutor() {
        java.util.concurrent.ThreadPoolExecutor ex = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "MetaBroadcast");
                    t.setDaemon(true);
                    return t;
                });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }
    private volatile String lastMetaKey;
    private volatile long lastMetaSentMs;
    private static final long META_REFRESH_MS = 15_000;
    private static final int META_ART_MAX_SIDE = 512;
    private static final int META_ART_JPEG_QUALITY = 80;
    private static final int META_ART_MAX_BYTES = 1_500_000;

    private volatile DataOutputStream syncControlOut;
    private volatile android.media.MediaPlayer syncMediaPlayer;
    private volatile Runnable playbackUpdateRunnable;
    private volatile Runnable pendingSchedulePlayRunnable;
    private FileShareNotificationHelper fileShareNotifHelper;
    private volatile Bitmap currentFileAlbumArt = null;

    // Broadcast receiver for notification media controls
    private final BroadcastReceiver mediaControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case AudioReceiveService.BROADCAST_TOGGLE:
                case AudioCaptureService.BROADCAST_TOGGLE:
                    togglePlayPause();
                    break;
                case AudioReceiveService.BROADCAST_SKIP_NEXT:
                case AudioCaptureService.BROADCAST_SKIP_NEXT:
                    onSkipNext();
                    break;
                case AudioReceiveService.BROADCAST_SKIP_PREV:
                case AudioCaptureService.BROADCAST_SKIP_PREV:
                    onSkipPrevious();
                    break;
                case AudioReceiveService.BROADCAST_STOP:
                    if (isReceiving.get()) stopReceiving();
                    break;
                case AudioCaptureService.BROADCAST_STOP:
                    if (isStreaming.get()) stopStreaming();
                    else if (isDuplexActive.get()) onDuplexStop();
                    break;
                case FileShareNotificationHelper.BROADCAST_FILE_PLAY_PAUSE:
                    onSyncPlayPause();
                    break;
                case FileShareNotificationHelper.BROADCAST_FILE_NEXT:
                    onSyncSkipNext();
                    break;
                case FileShareNotificationHelper.BROADCAST_FILE_PREV:
                    onSyncSkipPrev();
                    break;
                case FileShareNotificationHelper.BROADCAST_FILE_STOP:
                    stopFileShare();
                    break;
                case android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                    if (SettingsFragment.isAutoPauseOnHeadset(MainActivity.this)) {
                        if (!isPaused.get() && (isStreaming.get() || isReceiving.get() || isDuplexActive.get())) {
                            togglePlayPause();
                            Toast.makeText(MainActivity.this, "Headphones disconnected — stream paused", Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;
            }
        }
    };

    // ---------- CLIENT CONNECTION ----------
    private static class ClientConnection {
        final Socket socket;
        final OutputStream outputStream;
        final String clientIp;
        final AtomicBoolean connected = new AtomicBoolean(true);

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
            this.clientIp = socket.getInetAddress().getHostAddress();
        }

        boolean isConnected() { return connected.get(); }

        void close() {
            if (connected.compareAndSet(true, false)) {
                try { outputStream.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------- CHAT CLIENT WRAPPER ----------
    private static class ChatClient {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final String ip;
        final AtomicBoolean connected = new AtomicBoolean(true);

        ChatClient(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
            this.ip = socket.getInetAddress().getHostAddress();
        }

        void close() {
            if (connected.compareAndSet(true, false)) {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------- ACTIVITY RESULT LAUNCHER ----------
    private final ActivityResultLauncher<Intent> captureResultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        updateStatus("Requires Android 10+");
                        return;
                    }
                    projectionResultCode = result.getResultCode();
                    projectionResultData = result.getData();

                    AudioCaptureService.setOnServiceReady(() -> {
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                stopForegroundService(); // don't leave a zombie FGS (services F10c)
                                return;
                            }
                            try {
                                mediaProjection = projectionManager.getMediaProjection(
                                        projectionResultCode, projectionResultData);
                                if (mediaProjection != null) {
                                    registerProjectionCallback();
                                    startStreaming();
                                } else {
                                    updateStatus("Failed to get media projection");
                                    stopForegroundService();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "MediaProjection error: " + e.getMessage(), e);
                                updateStatus("Error: " + e.getMessage());
                                stopForegroundService();
                            }
                        });
                    });

                    if (!AudioCaptureService.start(this)) {   // deferred-stop guard (review M2)
                        AudioCaptureService.setOnServiceReady(null);
                        updateStatus("Could not start the capture service. Try again");
                    }
                } else {
                    updateStatus("Screen capture permission denied");
                }
            });

    // ---------- DUPLEX RESULT LAUNCHER ----------
    private volatile String duplexPendingMode = null;  // "host" or "join"
    private volatile String duplexPendingIp = null;
    private volatile MediaProjection duplexProjection;

    private final ActivityResultLauncher<Intent> duplexResultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        duplexFragment.updateStatus("Requires Android 10+");
                        return;
                    }
                    int code = result.getResultCode();
                    Intent data = result.getData();

                    Runnable startDuplex = () -> {
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                duplexProjection = projectionManager.getMediaProjection(code, data);
                                if (duplexProjection != null) {
                                    registerDuplexProjectionCallback(duplexProjection);
                                    if ("host".equals(duplexPendingMode)) {
                                        startDuplexHostWithProjection();
                                    } else if ("join".equals(duplexPendingMode)) {
                                        startDuplexJoinWithProjection(duplexPendingIp);
                                    }
                                } else {
                                    duplexFragment.updateStatus("Failed to get media projection");
                                    onDuplexStop();
                                }
                            } catch (Exception e) {
                                duplexFragment.updateStatus("Error: " + e.getMessage());
                                onDuplexStop();
                            }
                        });
                    };

                    if (AudioCaptureService.isServiceRunning()) {
                        // Service already running — no need to wait for onServiceReady
                        startDuplex.run();
                    } else {
                        AudioCaptureService.setOnServiceReady(startDuplex);
                        if (!AudioCaptureService.start(this)) {   // deferred-stop guard (review M2)
                            AudioCaptureService.setOnServiceReady(null);
                            duplexFragment.updateStatus("Could not start the capture service. Try again");
                            onDuplexStop();
                        }
                    }
                } else {
                    duplexFragment.updateStatus("Screen capture permission denied");
                    onDuplexStop();
                }
            });

    // ---------- FILE PICKER LAUNCHERS ----------
    private final ActivityResultLauncher<String> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handlePickedMultipleFiles(Collections.singletonList(uri));
                }
            });

    private final ActivityResultLauncher<String> multipleFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    handlePickedMultipleFiles(uris);
                }
            });

    private void registerProjectionCallback() {
        if (mediaProjection == null) return;
        final MediaProjection proj = mediaProjection;
        proj.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    // releaseProjection(p) clears the field before p.stop(): a self-initiated
                    // stop must not overwrite the real stop reason in the status line.
                    if (mediaProjection != proj) return;
                    boolean locked = isKeyguardLockedNow();
                    PipelineLog.w("PROJ_STOP", "stream projection stopped by system, keyguardLocked=" + locked);
                    String msg = projectionStoppedMessage(locked, "Media projection stopped");
                    // Record it as the session's failure reason, otherwise the stream thread's finally
                    // overwrites it with "Streaming stopped" (phase5 review M1).
                    StreamSession cur = currentStreamSession.get();
                    if (cur != null && cur.failReason == null) {
                        // the finally prints "Stopped: <reason>", so drop the "Capture stopped: " prefix
                        cur.failReason = msg.startsWith("Capture stopped: ")
                                ? msg.substring("Capture stopped: ".length()) : msg;
                    }
                    stopStreaming();
                    updateStatus(msg);
                });
            }
        }, mainHandler);
    }

    /** FIX_PLAN #10: the duplex projection was never monitored (services F9). */
    private void registerDuplexProjectionCallback(final MediaProjection proj) {
        if (proj == null) return;
        try {
            proj.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        // onDuplexStop() clears isDuplexActive before proj.stop(): skip our own stop.
                        if (duplexProjection != proj || !isDuplexActive.get()) return;
                        boolean locked = isKeyguardLockedNow();
                        PipelineLog.w("PROJ_STOP", "duplex projection stopped by system, keyguardLocked=" + locked);
                        onDuplexStop();
                        final String msg = projectionStoppedMessage(locked, "Media projection stopped");
                        // onDuplexStop() posts "Stopped — Ready…"; post after it so this reason stays
                        // visible (phase5 review M1).
                        mainHandler.post(() -> {
                            if (duplexFragment != null && duplexFragment.isAdded()) {
                                duplexFragment.updateStatus(msg);
                            }
                        });
                    });
                }
            }, mainHandler);
        } catch (Exception e) {
            PipelineLog.e("PROJ_STOP", "duplex projection callback registration failed", e);
        }
    }

    private boolean isKeyguardLockedNow() {
        try {
            android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** Android 15 QPR1+ stops MediaProjection when the screen locks (no opt-out). */
    private static String projectionStoppedMessage(boolean keyguardLocked, String generic) {
        return keyguardLocked
                ? "Capture stopped: Android 15+ stops audio capture when the screen locks. Keep the screen on while streaming."
                : generic;
    }

    // ---------- LIFECYCLE ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        setContentView(R.layout.activity_main);
        enableMaxRefreshRate();

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setupNotifications();

        // Setup fragments. After a recreation (process restore, theme-overlay change, a config
        // change not in configChanges) super.onCreate() has already restored the previous
        // fragments: reuse them by tag instead of adding a duplicate set (FIX_PLAN #11).
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        final String[] fragTags = {"settings", "fileshare", "duplex", "receive", "stream"};
        Fragment[] restored = new Fragment[fragTags.length];
        if (savedInstanceState != null) {
            for (int i = 0; i < fragTags.length; i++) restored[i] = fm.findFragmentByTag(fragTags[i]);
        }
        settingsFragment = restored[0] instanceof SettingsFragment
                ? (SettingsFragment) restored[0] : new SettingsFragment();
        fileShareFragment = restored[1] instanceof FileShareFragment
                ? (FileShareFragment) restored[1] : new FileShareFragment();
        duplexFragment = restored[2] instanceof DuplexFragment
                ? (DuplexFragment) restored[2] : new DuplexFragment();
        receiveFragment = restored[3] instanceof ReceiveFragment
                ? (ReceiveFragment) restored[3] : new ReceiveFragment();
        streamFragment = restored[4] instanceof StreamFragment
                ? (StreamFragment) restored[4] : new StreamFragment();
        Fragment[] allFrags = {settingsFragment, fileShareFragment, duplexFragment,
                receiveFragment, streamFragment};

        // Setup bottom navigation
        bottomNav = findViewById(R.id.bottomNav);

        // Active tab: the restored fragment that was visible, otherwise Stream.
        activeFragment = streamFragment;
        for (int i = 0; i < allFrags.length; i++) {
            if (restored[i] == allFrags[i] && !allFrags[i].isHidden()) {
                activeFragment = allFrags[i];
                break;
            }
        }

        // Add missing fragments once (show/hide pattern preserves state); exactly one is shown.
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        for (int i = 0; i < allFrags.length; i++) {
            if (restored[i] != allFrags[i]) ft.add(R.id.fragmentContainer, allFrags[i], fragTags[i]);
            if (allFrags[i] == activeFragment) ft.show(allFrags[i]);
            else ft.hide(allFrags[i]);
        }
        ft.commit();

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment target = null;
            if (id == R.id.nav_stream) target = streamFragment;
            else if (id == R.id.nav_receive) target = receiveFragment;
            else if (id == R.id.nav_duplex) target = duplexFragment;
            else if (id == R.id.nav_fileshare) target = fileShareFragment;
            else if (id == R.id.nav_settings) target = settingsFragment;

            if (target != null && target != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(target)
                        .commit();
                activeFragment = target;
            }
            return target != null;
        });
        // Keep the bottom nav in sync with the restored visible tab (listener is a no-op here
        // because target == activeFragment).
        if (activeFragment != streamFragment) {
            int navId = activeFragment == receiveFragment ? R.id.nav_receive
                    : activeFragment == duplexFragment ? R.id.nav_duplex
                    : activeFragment == fileShareFragment ? R.id.nav_fileshare
                    : R.id.nav_settings;
            bottomNav.setSelectedItemId(navId);
        }

        // Track Wi-Fi/Ethernet LAN network for socket binding and IP display (FIX_PLAN #2, M3).
        registerLanNetworkCallback();

        // Detect capabilities and device IP
        setupQualitySpinner();
        showDeviceIp();

        // Register broadcast receiver for notification media controls
        IntentFilter mediaFilter = new IntentFilter();
        mediaFilter.addAction(AudioReceiveService.BROADCAST_TOGGLE);
        mediaFilter.addAction(AudioReceiveService.BROADCAST_SKIP_NEXT);
        mediaFilter.addAction(AudioReceiveService.BROADCAST_SKIP_PREV);
        mediaFilter.addAction(AudioReceiveService.BROADCAST_STOP);
        mediaFilter.addAction(AudioCaptureService.BROADCAST_TOGGLE);
        mediaFilter.addAction(AudioCaptureService.BROADCAST_SKIP_NEXT);
        mediaFilter.addAction(AudioCaptureService.BROADCAST_SKIP_PREV);
        mediaFilter.addAction(AudioCaptureService.BROADCAST_STOP);
        mediaFilter.addAction(FileShareNotificationHelper.BROADCAST_FILE_PLAY_PAUSE);
        mediaFilter.addAction(FileShareNotificationHelper.BROADCAST_FILE_NEXT);
        mediaFilter.addAction(FileShareNotificationHelper.BROADCAST_FILE_PREV);
        mediaFilter.addAction(FileShareNotificationHelper.BROADCAST_FILE_STOP);
        mediaFilter.addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        ContextCompat.registerReceiver(this, mediaControlReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        fileShareNotifHelper = new FileShareNotificationHelper(this);

        // Check NotificationListener permission and prompt if needed
        checkNotificationListenerPermission();

        // Request storage permission for album art access. Fresh start only (not after a
        // recreation); the result (code 4001) is handled silently in onRequestPermissionsResult.
        if (savedInstanceState != null) {
            Log.d(TAG, "Recreated: skipping storage permission prompt");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_MEDIA_AUDIO}, 4001);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 4001);
            }
        }

        SettingsFragment.setOnSettingsChangedListener(key -> {
            if (SettingsFragment.KEY_KEEP_SCREEN_ON.equals(key) || "reset".equals(key)) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    applyKeepScreenOn(isStreaming.get() || isDuplexActive.get());
                });
            }
        });
    }

    /**
     * FLAG_KEEP_SCREEN_ON = user setting OR (API 35+ and a capture session is active).
     * Android 15 QPR1+ stops MediaProjection when the screen locks (FIX_PLAN #10, R8), so
     * the screen is kept on while Stream/Duplex capture runs. Call on the UI thread.
     */
    private void applyKeepScreenOn(boolean captureActive) {
        boolean keep = SettingsFragment.isKeepScreenOn(this)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && captureActive);
        if (keep) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private int getAudioPerformanceMode() {
        int mode = SettingsFragment.getPerfMode(this);
        if (mode == 1) return AudioTrack.PERFORMANCE_MODE_NONE;
        if (mode == 2) return AudioTrack.PERFORMANCE_MODE_POWER_SAVING;
        return AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
    }

    private int getCalculatedBufferSize(int defaultMinBuf) {
        // Legacy callers (duplex: 16-bit stereo) — 4 bytes/frame
        return getCalculatedBufferSize(defaultMinBuf, 4);
    }

    private int getCalculatedBufferSize(int defaultMinBuf, int bytesPerFrame) {
        int userFrames = SettingsFragment.getBufferSize(this);
        return AudioPipelineUtils.userBufferBytes(userFrames, bytesPerFrame, defaultMinBuf);
    }

    private int getActiveStreamPort() {
        return SettingsFragment.getCustomStreamPort(this);
    }

    private void enableMaxRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display display = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display = getDisplay();
                } else {
                    WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) display = wm.getDefaultDisplay();
                }
                if (display != null) {
                    Display.Mode[] modes = display.getSupportedModes();
                    if (modes != null && modes.length > 0) {
                        Display.Mode maxMode = modes[0];
                        for (Display.Mode mode : modes) {
                            if (mode.getRefreshRate() > maxMode.getRefreshRate()) {
                                maxMode = mode;
                            }
                        }
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = maxMode.getModeId();
                        getWindow().setAttributes(lp);
                        Log.d(TAG, "Auto-configured display refresh rate to " + maxMode.getRefreshRate() + "Hz (120fps mode " + maxMode.getModeId() + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not set max refresh rate: " + e.getMessage());
        }
    }

    /** The Notification Access prompt is shown at most once per process (not on every onCreate). */
    private static boolean nlPromptShownThisProcess = false;

    private void checkNotificationListenerPermission() {
        String pkgName = getPackageName();
        java.util.Set<String> enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this);
        if (enabled.contains(pkgName)) return; // Already granted
        if (nlPromptShownThisProcess) return;  // "Later" (or dismissed) is remembered for this session
        nlPromptShownThisProcess = true;

        new android.app.AlertDialog.Builder(this)
                .setTitle("🎵 Notification Access Required")
                .setMessage("SoundShare needs Notification Access to:\n\n"
                        + "• Show the current song name & album art\n"
                        + "• Control your music player (Poweramp, Spotify, etc.)\n\n"
                        + "Grant permission in the next screen.")
                .setPositiveButton("Grant", (dialog, which) -> {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private void setupNotifications() {
        notificationHelper = new ChatNotificationHelper(this);
        try {
            ProcessLifecycleOwner.get().getLifecycle().addObserver(
                    (LifecycleEventObserver) (source, event) -> {
                        if (event == Lifecycle.Event.ON_START) {
                            isAppInForeground = true;
                            notificationHelper.setAppInForeground(true);
                        } else if (event == Lifecycle.Event.ON_STOP) {
                            isAppInForeground = false;
                            notificationHelper.setAppInForeground(false);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "ProcessLifecycleOwner error: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        if (intent.getBooleanExtra("from_notification", false)
                || intent.getBooleanExtra("mark_read", false)) {
            if (notificationHelper != null) notificationHelper.clearAllNotifications();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is in configChanges so a dark/light switch can't kill a running session (FIX_PLAN #11).
        // When nothing is running or stopping, re-theme by recreating (fragments are reused by tag),
        // so the UI doesn't keep stale colors until restart (phase5 review services L4).
        int night = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != appliedNightMode) {
            if (activeOtherMode(null) == null && !isFinishing()) {
                appliedNightMode = night;
                recreate();
            }
            // else: keep the old colors for now; the next idle change or restart re-themes
        }
    }

    /** Night bits of the configuration the current views were inflated with. */
    private int appliedNightMode = -1;

    // ===== FRAGMENT CALLBACK IMPLEMENTATIONS =====

    private static final int REQ_POST_NOTIFICATIONS = 3001;
    private static final int REQ_STORAGE = 4001;
    /** Start flow to resume after a REQ_AUDIO grant: "stream", "duplex_host", "duplex_join" or null. */
    private String pendingAudioPermAction;
    private String pendingDuplexJoinIp;
    /** POST_NOTIFICATIONS is asked at most once per process and never blocks the start (FIX_PLAN #7). */
    private static boolean notifPermAskedThisProcess = false;

    /** Display name of an active mode other than {@code self}, or null (FIX_PLAN #23). */
    private String activeOtherMode(String self) {
        // A stopped session's finally still resets shared state (discovery, capture FGS, UI) until it
        // ends, so a mode counts as busy while it is stopping too (phase5 review M2). A Stream restart
        // is also refused while the old stream is stopping (its finally would stop the new capture FGS);
        // a Receive restart is session-safe.
        boolean streamStopping = !isStreaming.get() && currentStreamSession.get() != null;
        if ((!"Stream".equals(self) && isStreaming.get()) || streamStopping) return "Stream";
        if (!"Receive".equals(self) && (isReceiving.get() || currentReceiveSession.get() != null)) return "Receive";
        if (!"Duplex".equals(self) && isDuplexActive.get()) return "Duplex";
        if (!"File Share".equals(self) && isFileShareActive.get()) return "File Share";
        return null;
    }

    private String modeBusyMessage(String other) {
        boolean stopping = ("Stream".equals(other) && !isStreaming.get())
                || ("Receive".equals(other) && !isReceiving.get());
        if (stopping) return other + " is still stopping. Try again in a moment";
        return other + " is active. Stop " + other + " first (only one mode can run at a time)";
    }

    @Override
    public void onStartStreaming() {
        if (isStreaming.get()) {
            stopStreaming();
            return;
        }
        String other = activeOtherMode("Stream");
        if (other != null) {
            updateStatus(modeBusyMessage(other));
            return;
        }
        if (!hasRecordAudioPermission()) {
            pendingAudioPermAction = "stream";
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return; // continues in onRequestPermissionsResult (no "tap again")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !notifPermAskedThisProcess
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPermAskedThisProcess = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
            return; // continues to capture consent in onRequestPermissionsResult, granted or not
        }
        launchStreamCaptureConsent();
    }

    private void launchStreamCaptureConsent() {
        if (isStreaming.get()) return;
        String other = activeOtherMode("Stream");
        if (other != null) {
            updateStatus(modeBusyMessage(other));
            return;
        }
        try {
            captureResultLauncher.launch(projectionManager.createScreenCaptureIntent());
        } catch (Exception e) {
            Log.e(TAG, "Cannot launch screen capture consent", e);
            updateStatus("Cannot request screen capture: " + e.getMessage());
        }
    }

    @Override
    public void onStopStreaming() {
        stopStreaming();
    }

    @Override
    public void onStreamTogglePlayPause() {
        togglePlayPause();
    }

    @Override
    public void onStartReceiving(String ip) {
        if (isReceiving.get()) {
            stopReceiving();
            return;
        }
        if (ip.isEmpty()) {
            updateStatus("Enter streamer IP address");
            return;
        }
        String other = activeOtherMode("Receive");
        if (other != null) {
            updateStatus(modeBusyMessage(other));
            return;
        }
        // One-time hint to exempt SoundShare from battery optimization (FIX_PLAN #31, M6 helper).
        BatteryOptHelper.maybeShowHint(this);
        startReceiving(ip);
    }

    @Override
    public void onStopReceiving() {
        stopReceiving();
    }

    @Override
    public void onReceiveTogglePlayPause() {
        togglePlayPause();
    }

    @Override
    public void onReceiveSkipPrev() {
        onSkipPrevious();
    }

    @Override
    public void onReceiveSkipNext() {
        onSkipNext();
    }

    @Override
    public void onSetupQualitySpinner(Spinner spinner) {
        setupSpinnerForFragment(spinner);
    }

    @Override
    public String getDeviceIp() {
        return cachedDeviceIp;
    }

    @Override
    public void onCopyIp() {
        if (!cachedDeviceIp.equals("Not connected") && !cachedDeviceIp.equals("Loading...")) {
            copyToClipboard(cachedDeviceIp);
        }
    }

    /** DuplexFragment.setActive() hook: keep the screen on while Duplex captures (API 35+). */
    @Override
    public void onDuplexActiveChanged(boolean active) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            applyKeepScreenOn(isStreaming.get() || (active && isDuplexActive.get()));
        });
    }

    private void setupSpinnerForFragment(Spinner spinner) {
        if (spinner == null) return;
        String[] names = new String[QUALITY_NAMES.length];
        System.arraycopy(QUALITY_NAMES, 0, names, 0, QUALITY_NAMES.length);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(2);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (availablePresetIndices != null && pos < availablePresetIndices.length) {
                    selectedPreset = availablePresetIndices[pos];
                } else {
                    selectedPreset = Math.min(pos, QUALITY_PRESETS.length - 1);
                }
                showPresetInfo(selectedPreset);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(
                            ContextCompat.getColor(MainActivity.this, R.color.text_primary));
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> p) { selectedPreset = 2; }
        });

        // Update spinner when capabilities are detected
        if (deviceCaps != null) {
            filterPresetsForSpinner(spinner);
        }
    }

    private void filterPresetsForSpinner(Spinner spinner) {
        if (deviceCaps == null || spinner == null) return;
        List<String> filteredNames = new ArrayList<>();
        List<Integer> filteredIndices = new ArrayList<>();
        for (int i = 0; i < QUALITY_PRESETS.length; i++) {
            int[] preset = QUALITY_PRESETS[i];
            int sr = preset[0]; int bps = preset[3];
            int comp = preset.length > 4 ? preset[4] : 0;
            int isFloatFlag = preset.length > 5 ? preset[5] : 0;
            boolean supported = true;
            if (sr == 96000 && !deviceCaps.supports96kHz) supported = false;
            if (sr == 192000 && !deviceCaps.supports192kHz) supported = false;
            if (bps == 24 && !deviceCaps.supports24bit) supported = false;
            if (bps == 32 && !deviceCaps.supports32bit
                    && !(isFloatFlag == 1 && deviceCaps.supportsFloat)) supported = false;
            if (isFloatFlag == 1 && !deviceCaps.supportsFloat) supported = false;
            if (comp == 1 && (!deviceCaps.supportsFlacEncode
                    || !deviceCaps.supportsFlacDecode)) supported = false;
            if (supported) {
                long rawBps = (long) sr * preset[1] * bps;
                String suffix = comp == 1
                        ? String.format(Locale.US, " (~%.1f Mbps)", rawBps * 0.55 / 1_000_000.0)
                        : String.format(Locale.US, " (%.1f Mbps)", rawBps / 1_000_000.0);
                filteredNames.add(QUALITY_NAMES[i] + suffix);
                filteredIndices.add(i);
            }
        }
        availablePresetIndices = new int[filteredIndices.size()];
        for (int i = 0; i < filteredIndices.size(); i++)
            availablePresetIndices[i] = filteredIndices.get(i);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                filteredNames.toArray(new String[0]));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // Keep the user's current preset if it survived the filter, else Balanced (2).
        int defaultPos = -1;
        int keep = selectedPreset;
        for (int i = 0; i < availablePresetIndices.length; i++) {
            if (availablePresetIndices[i] == keep) { defaultPos = i; break; }
        }
        if (defaultPos < 0) {
            defaultPos = 0;
            for (int i = 0; i < availablePresetIndices.length; i++) {
                if (availablePresetIndices[i] == 2) { defaultPos = i; break; }
            }
        }
        if (!filteredNames.isEmpty()) {
            spinner.setSelection(Math.min(defaultPos, filteredNames.size() - 1));
        }
    }

    // ===== SMART QUALITY SPINNER =====
    private void setupQualitySpinner() {
        availablePresetIndices = new int[QUALITY_PRESETS.length];
        for (int i = 0; i < QUALITY_PRESETS.length; i++) {
            availablePresetIndices[i] = i;
        }

        // Detect capabilities in background
        new Thread(() -> {
            deviceCaps = HiResAudioHelper.detectCapabilities();
            Log.i(TAG, "Audio caps: " + deviceCaps);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Caps usually arrive after StreamFragment set up its spinner (deviceCaps was
                // still null then), so re-filter it now (FIX_PLAN #22). Not while streaming:
                // the running session already uses its preset.
                if (streamFragment != null && streamFragment.isAdded() && !isStreaming.get()) {
                    Spinner qs = streamFragment.getQualitySpinner();
                    if (qs != null) filterPresetsForSpinner(qs);
                }
                showDeviceCapabilities();
            });
        }, "CapCheck").start();
    }

    private void showDeviceCapabilities() {
        if (deviceCaps != null) {
            String msg = deviceCaps.isHiResCapable()
                    ? "✅ Hi-Res Audio supported • Max "
                    + (deviceCaps.maxSampleRate / 1000) + "kHz / "
                    + deviceCaps.maxBitDepth + "-bit"
                    : "Standard audio • Max "
                    + (deviceCaps.maxSampleRate / 1000) + "kHz / "
                    + deviceCaps.maxBitDepth + "-bit";
            updateStatus(msg);
        }
    }

    // setupClickListeners removed — handled by fragment callbacks

    // ===== TRANSPORT CONTROLS =====

    private void togglePlayPause() {
        if (isReceiving.get()) {
            AudioTrack at = audioTrack;
            if (isPaused.get()) {
                isPaused.set(false);
                if (at != null) {
                    try {
                        at.flush();
                        at.play();
                    } catch (Exception ignored) {}
                }
                updatePlayPauseButton(false);
                updateNowPlayingState("Live", R.color.accent_green);
                sendControlMessage(CTRL_RESUME);
                // No local media-key fallback here: the receiver controls the host only over
                // the network (a local key would loop back into our own session — FIX_PLAN #9)
                updateReceiverNotification();
            } else {
                isPaused.set(true);
                if (at != null) {
                    try {
                        at.pause();
                    } catch (Exception ignored) {}
                }
                updatePlayPauseButton(true);
                updateNowPlayingState("Paused", R.color.accent_orange);
                sendControlMessage(CTRL_PAUSE);
                updateReceiverNotification();
            }
        } else if (isStreaming.get()) {
            if (isPaused.get()) {
                // A remote CTRL_PAUSE stops the AudioRecord; a local resume must restart it
                AudioRecord ar = recorder;
                if (ar != null) {
                    try {
                        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            ar.startRecording();
                        }
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "Recorder restart on resume failed", e);
                    }
                }
                isPaused.set(false);
                updatePlayPauseButton(false);
                updateNowPlayingState("Live", R.color.accent_green);
                updateSenderNotification();
                broadcastControlToClients(CTRL_RESUME);
                MediaMetadataHelper.sendPlay(this);
            } else {
                isPaused.set(true);
                updatePlayPauseButton(true);
                updateNowPlayingState("Paused", R.color.accent_orange);
                updateSenderNotification();
                broadcastControlToClients(CTRL_PAUSE);
                MediaMetadataHelper.sendPause(this);
            }
        } else if (isDuplexActive.get()) {
            if (isPaused.get()) {
                isPaused.set(false);
                AudioTrack dt = duplexTrack;
                if (dt != null) {
                    try {
                        dt.flush();
                        dt.play();
                    } catch (Exception ignored) {}
                }
                updatePlayPauseButton(false);
                sendDuplexControlMessage(CTRL_RESUME);
                MediaMetadataHelper.sendPlay(this, false); // no media-key fallback (FIX_PLAN #9)
            } else {
                isPaused.set(true);
                AudioTrack dt = duplexTrack;
                if (dt != null) {
                    try {
                        dt.pause();
                    } catch (Exception ignored) {}
                }
                updatePlayPauseButton(true);
                sendDuplexControlMessage(CTRL_PAUSE);
                MediaMetadataHelper.sendPause(this, false); // no media-key fallback (FIX_PLAN #9)
            }
            // Always update notification with current state
            updateDuplexNotificationNow();
        }
    }

    private void onSkipPrevious() {
        if (isStreaming.get()) {
            // Sender side: control the host media player directly
            MediaMetadataHelper.sendSkipPrevious(this);
            Toast.makeText(this, "⏮ Previous track", Toast.LENGTH_SHORT).show();
        } else if (isReceiving.get()) {
            // Receiver side: send control message to sender over network
            sendControlMessage(CTRL_SKIP_BACK);
            Toast.makeText(this, "⏮ Skip back", Toast.LENGTH_SHORT).show();
        } else if (isDuplexActive.get()) {
            sendDuplexControlMessage(CTRL_SKIP_BACK);
            Toast.makeText(this, "⏮ Previous track", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSkipNext() {
        if (isStreaming.get()) {
            // Sender side: control the host media player directly
            MediaMetadataHelper.sendSkipNext(this);
            Toast.makeText(this, "⏭ Next track", Toast.LENGTH_SHORT).show();
        } else if (isReceiving.get()) {
            // Receiver side: send control message to sender over network
            sendControlMessage(CTRL_SKIP_FWD);
            Toast.makeText(this, "⏭ Skip forward", Toast.LENGTH_SHORT).show();
        } else if (isDuplexActive.get()) {
            sendDuplexControlMessage(CTRL_SKIP_FWD);
            Toast.makeText(this, "⏭ Next track", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendControlMessage(byte ctrl) {
        Socket ps = pingSocket;
        if (ps == null || ps.isClosed()) return;
        new Thread(() -> {
            try {
                // Shared writer + lock with the ping thread, so a CTRL byte can never land
                // between LATENCY_REPORT and its 8-byte value (FIX_PLAN #17).
                synchronized (pingWriteLock) {
                    DataOutputStream dout = pingOut;
                    if (dout == null) return;
                    dout.writeByte(ctrl);
                    dout.flush();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to send control: " + e.getMessage());
            }
        }, "CtrlSend").start();
    }

    /** Server broadcasts a control message (pause/resume) to all chat clients */
    private void broadcastControlToClients(byte ctrl) {
        new Thread(() -> {
            for (ChatClient cc : chatClients) {
                if (cc.connected.get()) {
                    try {
                        synchronized (cc.out) {
                            cc.out.writeByte(ctrl);
                            cc.out.flush();
                        }
                    } catch (IOException e) {
                        cc.close();
                        chatClients.remove(cc);
                        lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
                    }
                }
            }
        }, "CtrlBroadcast").start();
    }

    /** Server broadcasts current media metadata to all chat clients */
    /** Server broadcasts current media metadata to all chat clients with hi-res art */
    private void broadcastMetadataToClients() {
        MediaMetadataHelper.NowPlayingInfo media =
                MediaMetadataHelper.getCurrentMedia(this);
        if (media == null) return;

        String title = media.title != null ? media.title : "SoundShare Stream";
        String artist = media.artist != null ? media.artist : "";
        String album = media.album != null ? media.album : "";
        long durationMs = media.durationMs;
        long positionMs = media.positionMs;
        final Bitmap srcArt = media.albumArt;

        // FIX_PLAN #15: send only when the track/art or the set of chat clients changes,
        // otherwise refresh at most every 15 s (was: a full-size q95 JPEG every second).
        int clientCount = 0;
        for (ChatClient cc : chatClients) if (cc.connected.get()) clientCount++;
        if (clientCount == 0) return;
        String key = title + '\u0000' + artist + '\u0000' + album + '\u0000'
                + (srcArt == null ? "-" : srcArt.getWidth() + "x" + srcArt.getHeight())
                + '\u0000' + clientCount;
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(lastMetaKey) && now - lastMetaSentMs < META_REFRESH_MS) return;
        lastMetaKey = key;
        lastMetaSentMs = now;

        // Encode + write on ONE background thread (no UI-thread JPEG, no thread pile-up).
        try {
            metaExecutor.execute(() -> {
                byte[] art = encodeMetadataArt(srcArt);
                for (ChatClient cc : chatClients) {
                    if (cc.connected.get()) {
                        try {
                            writeMetadataMessage(cc.out, title, artist, album,
                                    art, durationMs, positionMs);
                        } catch (IOException e) {
                            cc.close();
                            chatClients.remove(cc);
                            lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
                        }
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /**
     * Album art for CHAT_METADATA: longest side ≤ 512 px (aspect kept, never upscaled),
     * JPEG q80 — typically 20-60 KB, always below the receivers' 1.5 MB limit.
     * Returns null (sent as artLen 0) when there is no art or encoding fails.
     */
    private static byte[] encodeMetadataArt(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            Bitmap toSend = src;
            int longest = Math.max(w, h);
            if (longest > META_ART_MAX_SIDE) {
                float scale = META_ART_MAX_SIDE / (float) longest;
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                toSend = Bitmap.createScaledBitmap(src, nw, nh, true);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            toSend.compress(Bitmap.CompressFormat.JPEG, META_ART_JPEG_QUALITY, bos);
            if (toSend != src) toSend.recycle();
            byte[] out = bos.toByteArray();
            return out.length < META_ART_MAX_BYTES ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeMetadataMessage(DataOutputStream out, String title,
                                       String artist, String album, byte[] albumArt,
                                       long durationMs, long positionMs) throws IOException {
        byte[] t = title.getBytes(StandardCharsets.UTF_8);
        byte[] a = artist.getBytes(StandardCharsets.UTF_8);
        byte[] al = album.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.writeByte(CHAT_METADATA);
            out.writeShort(t.length);
            out.write(t);
            out.writeShort(a.length);
            out.write(a);
            out.writeShort(al.length);
            out.write(al);
            out.writeLong(durationMs);
            out.writeLong(positionMs);
            // Album art: 4-byte length + bytes (0 = no art)
            if (albumArt != null && albumArt.length > 0) {
                out.writeInt(albumArt.length);
                out.write(albumArt);
            } else {
                out.writeInt(0);
            }
            out.flush();
        }
    }

    private void updatePlayPauseButton(boolean paused) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.setPlayPauseIcon(paused);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.setPlayPauseIcon(paused);
            }
            if (isDuplexActive.get() && duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.setPlayPauseIcon(paused);
            }
        });
    }

    private void updateNowPlayingState(String statusText, int colorResId) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.setNowPlayingState(statusText, colorResId);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.setNowPlayingState(statusText, colorResId);
            }
        });
    }

    private void showPresetInfo(int idx) {
        if (idx < 0 || idx >= QUALITY_PRESETS.length) return;
        int[] p = QUALITY_PRESETS[idx];
        int sr = p[0], ch = p[1], fpb = p[2], bps = p[3];
        int comp = p.length > 4 ? p[4] : 0;
        int isFloatFlag = p.length > 5 ? p[5] : 0;

        double lat = (fpb * 1000.0) / sr;
        long rawBps = (long) sr * ch * bps;
        boolean isHiRes = sr > 48000 || bps > 24;
        boolean isFlac = comp == 1;

        StringBuilder sb = new StringBuilder();

        if (isHiRes && isFlac) sb.append("🏆 HI-RES LOSSLESS • ");
        else if (isHiRes) sb.append("🎧 HI-RES • ");
        else if (isFlac) sb.append("🔷 LOSSLESS • ");

        sb.append(String.format(Locale.US, "%.1fkHz", sr / 1000.0));
        sb.append(ch == 1 ? " Mono" : " Stereo");
        sb.append(" • ").append(bps).append("-bit");
        if (isFloatFlag == 1) sb.append(" Float");
        sb.append(String.format(Locale.US, " • ~%.0fms buffer", lat));

        if (isFlac) {
            sb.append(String.format(Locale.US, " • ~%.1f Mbps (lossless)",
                    rawBps * 0.55 / 1_000_000.0));
        } else {
            sb.append(String.format(Locale.US, " • %.1f Mbps",
                    rawBps / 1_000_000.0));
        }

        if (!isFlac && rawBps > 10_000_000) {
            sb.append("\n⚠️ High bandwidth - use 5GHz WiFi");
        }

        updateStatus(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        showDeviceIp();
        isAppInForeground = true;
        if (notificationHelper != null) notificationHelper.setAppInForeground(true);

        applyKeepScreenOn(isStreaming.get() || isDuplexActive.get());
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAppInForeground = false;
        if (notificationHelper != null) notificationHelper.setAppInForeground(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mediaControlReceiver); } catch (Exception ignored) {}
        unregisterLanNetworkCallback(); // FIX_PLAN #2 (M3 helper)
        stopStreaming();
        stopReceiving();
        onDuplexStop();
        stopFileShare();
        stopForegroundService();
        stopReceiverService();
        if (notificationHelper != null) notificationHelper.clearAllNotifications();
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ---------- PERMISSIONS ----------
    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_AUDIO) {
            String action = pendingAudioPermAction;
            String joinIp = pendingDuplexJoinIp;
            pendingAudioPermAction = null;
            pendingDuplexJoinIp = null;
            if (granted || hasRecordAudioPermission()) {
                // Continue the flow the user started; no "tap again".
                if ("stream".equals(action)) onStartStreaming();
                else if ("duplex_host".equals(action)) onDuplexHost();
                else if ("duplex_join".equals(action) && joinIp != null) onDuplexJoin(joinIp);
                return;
            }
            boolean permanent = results.length > 0 && !ActivityCompat
                    .shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO);
            String msg = results.length == 0
                    ? "Permission request was interrupted. Tap Start again"
                    : permanent
                    ? "Audio permission denied. Enable it in Settings > Apps > SoundShare > Permissions"
                    : "Audio permission denied. It is required to capture audio";
            if (action != null && action.startsWith("duplex")
                    && duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.updateStatus(msg);
            } else {
                updateStatus(msg);
            }
        } else if (req == REQ_POST_NOTIFICATIONS) {
            // Optional: the foreground service runs without it. Never block the stream start.
            if (!granted) Log.i(TAG, "POST_NOTIFICATIONS not granted; continuing without it");
            launchStreamCaptureConsent();
        } else if (req == REQ_STORAGE) {
            // Album-art storage access is optional: log only, never touch the stream status.
            Log.i(TAG, "Storage/media permission " + (granted ? "granted" : "not granted"));
        }
    }

    // ---------- PROTOCOL HEADER (v2 - 28 bytes) ----------
    private void writeFormatHeader(OutputStream out, AudioConfig config) throws IOException {
        // Byte-identical 28-byte v2 header (see AudioPipelineUtils.encodeHeaderV2)
        byte[] header = AudioPipelineUtils.encodeHeaderV2(config.sampleRate, config.bitsPerSample,
                config.channels, config.framesPerBuffer, config.chunkSize,
                config.compressionType, config.isFloat);
        out.write(header);
        out.flush();
        PipelineLog.i("HDR_TX", String.format(Locale.US, "%dHz %dbit %dch fpb=%d chunk=%d",
                config.sampleRate, config.bitsPerSample, config.channels,
                config.framesPerBuffer, config.chunkSize));
    }

    private AudioConfig readFormatHeader(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != HEADER_MAGIC) return null;

        int version = dis.readInt();
        int sr = dis.readInt();
        int bps = dis.readShort() & 0xFFFF;
        int ch = dis.readShort() & 0xFFFF;
        int fpb = dis.readInt();
        int cs = dis.readInt();
        int comp = dis.readShort() & 0xFFFF;
        int floatFlag = dis.readByte() & 0xFF;
        int reserved = dis.readByte() & 0xFF;

        if (!isValidSampleRate(sr)) {
            Log.e(TAG, "Invalid sample rate: " + sr);
            return null;
        }
        if (bps != 16 && bps != 24 && bps != 32) {
            Log.e(TAG, "Invalid bits per sample: " + bps);
            return null;
        }
        if (ch != 1 && ch != 2) {
            Log.e(TAG, "Invalid channels: " + ch);
            return null;
        }
        if (comp != 0 && comp != 1) {
            Log.e(TAG, "Invalid compression: " + comp);
            return null;
        }

        int safeFpb = AudioPipelineUtils.sanitizeFramesPerBuffer(fpb, sr);
        if (safeFpb != fpb) {
            PipelineLog.w("HDR_RX", "Bogus framesPerBuffer " + fpb + " → " + safeFpb);
        }
        if (version != HEADER_VERSION) {
            PipelineLog.w("HDR_RX", "Unexpected header version " + version + " (expected "
                    + HEADER_VERSION + "), continuing");
        }
        Log.i(TAG, String.format(Locale.US,
                "Header: v%d %dHz %dch %dbit comp=%d float=%d",
                version, sr, ch, bps, comp, floatFlag));
        PipelineLog.i("HDR_RX", String.format(Locale.US,
                "v%d %dHz %dch %dbit fpb=%d comp=%d float=%d",
                version, sr, ch, bps, safeFpb, comp, floatFlag));

        return AudioConfig.fromHeader(sr, ch, safeFpb, bps, comp, floatFlag);
    }

    private boolean isValidSampleRate(int sr) {
        return sr == 44100 || sr == 48000 || sr == 88200
                || sr == 96000 || sr == 176400 || sr == 192000;
    }

    // ---------- IP ----------
    private void showDeviceIp() {
        cachedDeviceIp = getDeviceIpAddress();
        runOnUiThread(() -> {
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.setDeviceIp(cachedDeviceIp);
            }
            if (duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.setDeviceIp(cachedDeviceIp);
            }
            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                fileShareFragment.setDeviceIp(cachedDeviceIp);
            }
            maybeShowVpnHint();
        });
    }

    /** One-time hint when a VPN is the active network (LAN traffic may be tunnelled or blocked). */
    private void maybeShowVpnHint() {
        if (vpnHintShown || isFinishing() || isDestroyed()) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            Network active = cm.getActiveNetwork();
            if (active == null) return;
            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
            boolean visible = (streamFragment != null && streamFragment.isAdded())
                    || (receiveFragment != null && receiveFragment.isAdded());
            if (!visible) return;   // try again on the next refresh
            vpnHintShown = true;
            PipelineLog.w("NET_LAN", "VPN is the active network");
            updateStatus("VPN active — LAN streaming may be blocked. "
                    + "Exclude SoundShare or allow LAN access in the VPN app.");
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "VPN check failed: " + e);
        }
    }

    /**
     * The IP other devices on the LAN should use to reach this phone.
     * 1) IPv4 of the tracked Wi-Fi/Ethernet network (even if unvalidated / not default);
     * 2) else an interface walk (up, non-loopback) ranked by NetUtils (wlan/ap/... first,
     *    cellular/VPN/CLAT dropped) — covers the phone-as-hotspot case;
     * 3) else "Not connected".
     * Previously this used getActiveNetwork(), which shows the cellular or VPN address
     * whenever Wi-Fi isn't the default network (FIX_PLAN R1 / #2).
     */
    private String getDeviceIpAddress() {
        try {
            Network lan = lanNetwork;
            if (lan != null) {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                LinkProperties props = cm != null ? cm.getLinkProperties(lan) : null;
                if (props != null) {
                    for (LinkAddress la : props.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()
                                && !a.isLinkLocalAddress()) {
                            String h = a.getHostAddress();
                            if (h != null) return h;
                        }
                    }
                }
            }
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "LAN LinkProperties read failed: " + e);
        }
        try {
            List<NetUtils.Candidate> cands = new ArrayList<>();
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                for (NetworkInterface ni : Collections.list(ifs)) {
                    try {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                    } catch (Exception ignored) {
                        continue;
                    }
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            String h = a.getHostAddress();
                            if (h != null) cands.add(new NetUtils.Candidate(ni.getName(), h));
                        }
                    }
                }
            }
            List<String> ranked = NetUtils.rankIpv4Candidates(cands);
            if (!ranked.isEmpty()) return ranked.get(0);
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "Interface walk failed: " + e);
        }
        return "Not connected";
    }

    // ---------- LAN network tracking (FIX_PLAN #2, M3) ----------
    private final java.util.Set<Network> lanNetworks =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Tracks Wi-Fi/Ethernet networks WITHOUT requiring INTERNET/VALIDATED, so LAN sockets can be
     * bound to Wi-Fi even when cellular stays the default (no-internet Wi-Fi, OEM "smart switch").
     * Called from onCreate (M5).
     */
    private void registerLanNetworkCallback() {
        if (lanNetworkCallback != null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) {
                lanNetworks.add(n);
                lanNetwork = n;
                PipelineLog.i("NET_LAN", "LAN network available");
                mainHandler.post(MainActivity.this::showDeviceIp);
            }

            @Override public void onLost(@NonNull Network n) {
                lanNetworks.remove(n);
                if (n.equals(lanNetwork)) {
                    java.util.Iterator<Network> it = lanNetworks.iterator();
                    lanNetwork = it.hasNext() ? it.next() : null;
                }
                PipelineLog.i("NET_LAN", "LAN network lost; "
                        + (lanNetwork != null ? "another LAN network remains" : "no LAN network"));
                mainHandler.post(MainActivity.this::showDeviceIp);
            }

            @Override public void onLinkPropertiesChanged(@NonNull Network n,
                                                          @NonNull LinkProperties lp) {
                if (n.equals(lanNetwork)) mainHandler.post(MainActivity.this::showDeviceIp);
            }
        };
        try {
            cm.registerNetworkCallback(req, cb);
            lanNetworkCallback = cb;
        } catch (Exception e) {
            // SecurityException / TooManyRequestsException: fall back to unbound sockets.
            PipelineLog.e("NET_LAN", "registerNetworkCallback failed", e);
        }
    }

    /** Called from onDestroy (M5). */
    private void unregisterLanNetworkCallback() {
        ConnectivityManager.NetworkCallback cb = lanNetworkCallback;
        lanNetworkCallback = null;
        if (cb == null) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "unregisterNetworkCallback failed: " + e);
        }
        lanNetworks.clear();
        lanNetwork = null;
    }

    /**
     * Routes an unconnected client socket over the LAN network instead of the default network.
     * No-op when no Wi-Fi/Ethernet network is tracked (e.g. phone is the hotspot: tethering
     * routes already work unbound). Failures (VPN lockdown → EPERM) are logged and ignored.
     */
    void bindToLan(Socket s) {
        Network n = lanNetwork;
        if (n == null || s == null) return;
        try {
            n.bindSocket(s);
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "bindSocket(TCP) failed, using default route: " + e);
        }
    }

    void bindToLan(DatagramSocket s) {
        Network n = lanNetwork;
        if (n == null || s == null) return;
        try {
            n.bindSocket(s);
        } catch (Exception e) {
            PipelineLog.w("NET_LAN", "bindSocket(UDP) failed, using default route: " + e);
        }
    }

    /**
     * Like {@link #bindToLan(Socket)}, but leaves the socket unbound when {@code targetIp} is on
     * the subnet of a DIFFERENT local interface than the LAN network (phone is a hotspot host
     * while also connected to Wi-Fi as a client): those peers are only reachable unbound.
     */
    private void bindToLan(Socket s, String targetIp) {
        if (lanNetwork == null || s == null) return;
        if (isOnNonLanLocalSubnet(targetIp)) {
            PipelineLog.i("NET_LAN", "Target on a non-LAN local subnet; not binding");
            return;
        }
        bindToLan(s);
    }

    private boolean isOnNonLanLocalSubnet(String targetIp) {
        try {
            if (targetIp == null || !android.net.InetAddresses.isNumericAddress(targetIp)) {
                return false;
            }
            InetAddress target = android.net.InetAddresses.parseNumericAddress(targetIp);
            if (!(target instanceof Inet4Address)) return false;
            String lanIface = null;
            Network lan = lanNetwork;
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (lan != null && cm != null) {
                LinkProperties lp = cm.getLinkProperties(lan);
                if (lp != null) lanIface = lp.getInterfaceName();
            }
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return false;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (sameIpv4Subnet(a, target, ia.getNetworkPrefixLength())) {
                        return lanIface != null && !lanIface.equals(ni.getName());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean sameIpv4Subnet(InetAddress a, InetAddress b, int prefixLen) {
        if (prefixLen <= 0 || prefixLen > 32) return false;
        byte[] x = a.getAddress(), y = b.getAddress();
        if (x.length != 4 || y.length != 4) return false;
        int xi = ((x[0] & 0xff) << 24) | ((x[1] & 0xff) << 16) | ((x[2] & 0xff) << 8) | (x[3] & 0xff);
        int yi = ((y[0] & 0xff) << 24) | ((y[1] & 0xff) << 16) | ((y[2] & 0xff) << 8) | (y[3] & 0xff);
        int mask = prefixLen == 32 ? -1 : ~(-1 >>> prefixLen);
        return (xi & mask) == (yi & mask);
    }

    private void copyToClipboard(String text) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Device IP", text));
        Toast.makeText(this, "IP copied: " + text, Toast.LENGTH_SHORT).show();
    }

    // ---------- UI HELPERS ----------
    private void updateStatus(String msg) {
        PipelineLog.status(msg); // FIX_PLAN #21: status text also lands in logcat (IPs redacted)
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.setStatus(msg);
            }
            if (receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.setStatus(msg);
            }
        });
    }

    private void updateReceiverCount() {
        int count = receiverCount.get();
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.updateReceiverCount(String.valueOf(count));
                if (count == 0) {
                    streamFragment.updateConnectionStatus(
                            "Waiting for receivers...", R.color.accent_orange);
                } else {
                    StringBuilder sb = new StringBuilder().append(count).append(" connected");
                    for (ClientConnection c : connectedClients)
                        if (c.isConnected()) sb.append("\n  • ").append(c.clientIp);
                    streamFragment.updateConnectionStatus(
                            sb.toString(), R.color.accent_green);
                }
            }
        });
    }

    private void updateLatencyUI(long latencyMs) {
        if (isFinishing() || isDestroyed()) return;
        String lat = latencyMs < 0 ? "Waiting..." : latencyMs + " ms";
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded()) streamFragment.updateLatency(lat);
            if (receiveFragment != null && receiveFragment.isAdded()) receiveFragment.updateLatency(lat);
        });
    }

    private void showStreamingUI(AudioConfig config) {
        if (isFinishing() || isDestroyed()) return;
        String srText = config.sampleRateString();
        if (config.isHiRes) srText += " 🎧";
        final String sr = srText;
        final String fmt = config.formatString();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.showStreamingUI(sr, fmt);
            }
            isPaused.set(false);
            applyKeepScreenOn(true); // API 35+: screen lock would stop the projection (FIX_PLAN #10)
            showNowPlayingUI(config, "Streaming from this device");
        });
    }

    private void showReceivingUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.showReceivingUI();
            }
            isPaused.set(false);
        });
    }

    private void updateReceivingUI(AudioConfig config, String ip) {
        if (isFinishing() || isDestroyed()) return;
        String srText = config.sampleRateString();
        if (config.isHiRes) srText += " 🎧";
        final String sr = srText;
        final String fmt = config.formatString();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.updateReceivingUI(sr, fmt, ip);
            }
            showNowPlayingUI(config, "Connected to " + ip);
        });
    }

    private void resetUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            isPaused.set(false);
            // Stream/receive ended: drop the capture keep-screen-on unless the user setting is
            // on or a capture session is (still/again) running — isStreaming is already false
            // on every stop path, so this only keeps it for a newer session or Duplex.
            applyKeepScreenOn(isStreaming.get() || isDuplexActive.get());
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.resetUI();
            }
            if (receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.resetUI();
            }
        });
    }

    // ===== NOW PLAYING HELPERS =====

    private void showNowPlayingUI(AudioConfig config, String deviceInfo) {
        if (isStreaming.get()) {
            MediaMetadataHelper.NowPlayingInfo media = MediaMetadataHelper.getCurrentMedia(this);
            String title;
            String subtitle;
            if (media != null && !"Unknown".equals(media.title)) {
                title = media.title;
                subtitle = media.getSubtitle();
                if (subtitle.isEmpty()) {
                    subtitle = config.sampleRateString() + " · "
                            + config.channelString() + " · "
                            + config.bitsPerSample + "-bit "
                            + config.compressionString();
                }
            } else {
                title = config.isHiRes ? "🎧 Hi-Res Stream" : "SoundShare Stream";
                subtitle = config.sampleRateString() + " · "
                        + config.channelString() + " · "
                        + config.bitsPerSample + "-bit "
                        + config.compressionString();
            }

            final String t = title, s = subtitle;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (streamFragment != null && streamFragment.isAdded()) {
                    streamFragment.showNowPlaying(t, s, deviceInfo);
                }
            });
        } else if (isReceiving.get()) {
            String title = (receivedMetaTitle != null && !receivedMetaTitle.isEmpty())
                    ? receivedMetaTitle : (config.isHiRes ? "🎧 Hi-Res Stream" : "SoundShare Stream");
            String subtitle = (receivedMetaArtist != null && !receivedMetaArtist.isEmpty())
                    ? receivedMetaArtist : (config.sampleRateString() + " · " + config.channelString());
            final String t = title, s = subtitle;
            final Bitmap art = receivedAlbumArt;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (receiveFragment != null && receiveFragment.isAdded()) {
                    receiveFragment.showNowPlaying(t, s, deviceInfo);
                    receiveFragment.updateNowPlayingMeta(t, s, art);
                }
            });
        }
    }

    /** Called periodically from updateStats to refresh Now Playing with latest track info */
    private void refreshNowPlayingMetadata() {
        if (!isStreaming.get()) return;

        MediaMetadataHelper.NowPlayingInfo media = MediaMetadataHelper.getCurrentMedia(this);
        if (media == null || "Unknown".equals(media.title)) return;

        AudioConfig config = activeConfig.get();
        String title = media.title;
        String subtitle = media.getSubtitle();
        if (subtitle.isEmpty() && config != null) {
            subtitle = config.sampleRateString() + " · " + config.channelString();
        }

        final String t = title, s = subtitle;
        final Bitmap art = media.albumArt;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.updateNowPlayingMeta(t, s, art);
            }
        });

        // Also broadcast metadata to connected clients
        broadcastMetadataToClients();
    }

    private void updateNowPlayingElapsed(long elapsedMs) {
        if (isFinishing() || isDestroyed()) return;
        long sec = elapsedMs / 1000;
        String time;
        if (sec >= 3600) {
            time = String.format(Locale.US, "%d:%02d:%02d",
                    sec / 3600, (sec % 3600) / 60, sec % 60);
        } else {
            time = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        }
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (streamFragment != null && streamFragment.isAdded())
                streamFragment.updateNowPlayingElapsed(time);
            if (receiveFragment != null && receiveFragment.isAdded())
                receiveFragment.updateNowPlayingElapsed(time);
        });
    }

    private void updateReceiverNotification() {
        AudioConfig config = activeConfig.get();
        if (config == null) return;
        boolean paused = isPaused.get();

        String title;
        String artist;

        // 1. Use metadata received from host
        if (receivedMetaTitle != null && !receivedMetaTitle.isEmpty()
                && !"Unknown".equals(receivedMetaTitle)) {
            title = receivedMetaTitle;
            artist = receivedMetaArtist != null ? receivedMetaArtist : "Audio Stream";
        }
        // 2. Fallback: show quality info
        else {
            if (config.isHiRes) {
                title = "🎧 " + config.sampleRateString() + " Hi-Res Stream";
            } else {
                title = config.sampleRateString() + " Stream";
            }
            artist = config.channelString() + " · " + config.bitsPerSample
                    + "-bit " + config.compressionString();
        }

        AudioReceiveService.updateNotification(
                this, title, artist, receivedAlbumArt, paused,
                receivedDurationMs, receivedPositionMs);
    }

    private void updateSenderNotification() {
        AudioConfig config = activeConfig.get();
        if (config == null) return;
        boolean paused = isPaused.get();

        MediaMetadataHelper.NowPlayingInfo media =
                MediaMetadataHelper.getCurrentMedia(this);
        String title;
        String artist;
        Bitmap albumArt = null;
        long durationMs = 0;
        long positionMs = 0;

        if (media != null && !"Unknown".equals(media.title)) {
            title = media.title;
            artist = media.getSubtitle();
            albumArt = media.albumArt;
            durationMs = media.durationMs;
            positionMs = media.positionMs;
        } else {
            // Fallback: show quality info instead of generic text
            int count = receiverCount.get();
            if (config.isHiRes) {
                title = "🎧 " + config.sampleRateString() + " Hi-Res Stream";
            } else {
                title = config.sampleRateString() + " Stream";
            }
            artist = config.channelString() + " · " + config.bitsPerSample
                    + "-bit · " + count + " receiver(s)";
        }

        AudioCaptureService.updateNotification(
                this, title, artist, albumArt, paused, durationMs, positionMs);
    }

    private void updateStats(long kbps, long totalBytes, long elapsedMs) {
        if (isFinishing() || isDestroyed()) return;
        AudioConfig config = activeConfig.get();

        // Build strings on calling thread
        String bitrateText;
        if (kbps > 1000) {
            bitrateText = String.format(Locale.US, "%.1f Mbps", kbps / 1000.0);
        } else {
            bitrateText = kbps + " kbps";
        }
        if (config != null && config.useFLAC && elapsedMs > 2000) {
            long rawKbps = config.rawBitrateKbps();
            if (rawKbps > 0 && kbps > 0) {
                double saved = 100.0 - ((double) kbps / rawKbps * 100.0);
                bitrateText += String.format(Locale.US, " (%.0f%% saved)", saved);
            }
        }
        if (config != null && config.isHiRes) bitrateText += " 🎧";

        String totalText;
        if (totalBytes < 1024) totalText = totalBytes + " B";
        else if (totalBytes < 1024 * 1024) totalText = String.format(Locale.US, "%.1f KB", totalBytes / 1024.0);
        else if (totalBytes < 1024L * 1024 * 1024) totalText = String.format(Locale.US, "%.2f MB", totalBytes / (1024.0 * 1024.0));
        else totalText = String.format(Locale.US, "%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0));

        long sec = elapsedMs / 1000;
        String durationText = sec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                : String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);

        final String br = bitrateText;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.updateStats(br, totalText, durationText, null, null, null, null);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.updateStats(br, totalText, durationText, null);
            }
            updateNowPlayingElapsed(elapsedMs);
            refreshNowPlayingMetadata();
        });
    }

    // ==========================================================
    //  CHAT
    // ==========================================================

    // Called by fragment callbacks
    @Override
    public void onChatSend(String text) {
        if (text.isEmpty()) return;
        addSentChatMessage(text);
        if (isStreaming.get()) sendChatToAllClients(text);
        else if (isReceiving.get()) sendChatToServer(text);
    }

    // kept for internal use
    private void sendChatMessage() {
        // No longer used directly; fragments call onChatSend
    }

    private void addSentChatMessage(String text) {
        ChatMessage msg = new ChatMessage("You", text, ChatMessage.TYPE_SENT);
        runOnUiThread(() -> {
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.addChatMessage(msg);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.addChatMessage(msg);
            }
        });
    }

    private void addReceivedChatMessage(String sender, String text) {
        if (notificationHelper != null) {
            notificationHelper.showChatNotification(sender, text);
        }
        ChatMessage msg = new ChatMessage(sender, text, ChatMessage.TYPE_RECEIVED);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.addChatMessage(msg);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.addChatMessage(msg);
            }
        });
    }

    private void addSystemChatMessage(String text) {
        runOnUiThread(() -> {
            if (isStreaming.get() && streamFragment != null && streamFragment.isAdded()) {
                streamFragment.addSystemMessage(text);
            }
            if (isReceiving.get() && receiveFragment != null && receiveFragment.isAdded()) {
                receiveFragment.addSystemMessage(text);
            }
        });
    }

    private void writeChatMessage(DataOutputStream out, String sender,
                                  String message) throws IOException {
        byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.writeByte(CHAT_MSG);
            out.writeShort(senderBytes.length);
            out.write(senderBytes);
            out.writeShort(msgBytes.length);
            out.write(msgBytes);
            out.flush();
        }
    }

    private String[] readChatMessage(DataInputStream in) throws IOException {
        int senderLen = in.readUnsignedShort();
        if (senderLen > 1024) throw new IOException("Bad sender length");
        byte[] senderBytes = new byte[senderLen];
        in.readFully(senderBytes);
        int msgLen = in.readUnsignedShort();
        if (msgLen > 65000) throw new IOException("Bad message length");
        byte[] msgBytes = new byte[msgLen];
        in.readFully(msgBytes);
        return new String[]{
                new String(senderBytes, StandardCharsets.UTF_8),
                new String(msgBytes, StandardCharsets.UTF_8)
        };
    }

    /** Reads and discards exactly {@code n} bytes (works on every API level). */
    private static void skipFully(DataInputStream in, long n) throws IOException {
        byte[] scratch = new byte[(int) Math.min(8192, Math.max(1, n))];
        while (n > 0) {
            int r = in.read(scratch, 0, (int) Math.min(scratch.length, n));
            if (r < 0) throw new java.io.EOFException("EOF while skipping " + n + " bytes");
            n -= r;
        }
    }

    private String readUtfField(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > 4096) throw new IOException("Field too long");
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==========================================================
    //  CHAT SERVER (SENDER)
    // ==========================================================

    private void startChatServer() {
        chatServerThread = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(CHAT_PORT));
                ss.setSoTimeout(1000);
                chatServerSocket = ss;
                while (isStreaming.get()) {
                    try {
                        Socket client = ss.accept();
                        client.setTcpNoDelay(true);
                        client.setSoTimeout(0);
                        ChatClient cc = new ChatClient(client);
                        chatClients.add(cc);
                        lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
                        addSystemChatMessage(cc.ip + " joined the chat");
                        updateChatStatus();
                        new Thread(() -> handleChatClientReads(cc),
                                "ChatRead-" + cc.ip).start();
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (isStreaming.get()) Log.e(TAG, "Chat server error", e);
            } finally {
                // FIX_PLAN #12: close OUR socket; only touch shared state if a newer session
                // hasn't replaced it (Stop→Start race).
                closeQuietly(ss);
                if (chatServerSocket == ss) {
                    chatServerSocket = null;
                    for (ChatClient cc : chatClients) cc.close();
                    chatClients.clear();
                }
            }
        }, "ChatServerThread");
        chatServerThread.start();
    }

    private void handleChatClientReads(ChatClient cc) {
        try {
            while (isStreaming.get() && cc.connected.get()) {
                byte cmd = cc.in.readByte();
                if (cmd == CHAT_MSG) {
                    String[] parts = readChatMessage(cc.in);
                    addReceivedChatMessage(parts[0], parts[1]);
                    broadcastChatExcept(cc, parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            if (isStreaming.get() && cc.connected.get())
                Log.w(TAG, "Chat client disconnected: " + cc.ip);
        } finally {
            cc.close();
            chatClients.remove(cc);
            lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
            addSystemChatMessage(cc.ip + " left the chat");
            updateChatStatus();
        }
    }

    private void sendChatToAllClients(String text) {
        new Thread(() -> {
            for (ChatClient cc : chatClients) {
                if (cc.connected.get()) {
                    try {
                        writeChatMessage(cc.out, SettingsFragment.getClientName(this), text);
                    } catch (IOException e) {
                        cc.close();
                        chatClients.remove(cc);
                        lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
                    }
                }
            }
        }, "ChatBroadcast").start();
    }

    private void broadcastChatExcept(ChatClient exclude, String sender,
                                     String message) {
        for (ChatClient cc : chatClients) {
            if (cc != exclude && cc.connected.get()) {
                try {
                    writeChatMessage(cc.out, sender, message);
                } catch (IOException e) {
                    cc.close();
                    chatClients.remove(cc);
                    lastMetaKey = null;   // push metadata to a joining/replacing client now (phase5 review L1)
                }
            }
        }
    }

    private void stopChatServer() {
        closeQuietly(chatServerSocket);
        chatServerSocket = null;
        for (ChatClient cc : chatClients) cc.close();
        chatClients.clear();
    }

    private void updateChatStatus() {
        int count = 0;
        for (ChatClient cc : chatClients) if (cc.connected.get()) count++;
        final int c = count;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            String text = c == 0 ? "Listening..." : c + " in chat";
            int color = c == 0 ? R.color.accent_orange : R.color.accent_green;
            if (streamFragment != null && streamFragment.isAdded()) {
                streamFragment.updateChatStatus(text, color);
            }
        });
    }

    // ==========================================================
    //  CHAT CLIENT (RECEIVER)
    // ==========================================================

    /** True while {@code s} is the live receive session (phase5 review M3: clients are session-bound). */
    private boolean isCurrentReceive(ReceiveSession s) {
        return s != null && s.running.get() && currentReceiveSession.get() == s && isReceiving.get();
    }

    private void startChatClient(String serverIp, final ReceiveSession rs) {
        chatClientThread = new Thread(() -> {
            Socket sock = null;
            try {
                Thread.sleep(500);
                if (!isCurrentReceive(rs)) return;
                sock = new Socket();
                bindToLan(sock, serverIp);
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(serverIp, CHAT_PORT), 5000);
                if (!isCurrentReceive(rs)) return;   // a newer session owns the chat channel
                chatSocket = sock;
                DataInputStream din = new DataInputStream(sock.getInputStream());
                DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (receiveFragment != null && receiveFragment.isAdded()) {
                        receiveFragment.updateChatStatus("Connected", R.color.accent_green);
                    }
                });
                addSystemChatMessage("Connected to chat");
                chatClientOut = dout;
                while (isCurrentReceive(rs)) {
                    byte cmd = din.readByte();
                    if (cmd == CHAT_MSG) {
                        String[] parts = readChatMessage(din);
                        addReceivedChatMessage(parts[0], parts[1]);
                    } else if (cmd == CHAT_METADATA) {
                        // Server sent media metadata — update Now Playing + notification
                        String title = readUtfField(din);
                        String artist = readUtfField(din);
                        String album = readUtfField(din);
                        long durMs = din.readLong();
                        long posMs = din.readLong();
                        // Read album art bytes
                        int artLen = din.readInt();
                        Bitmap art = null;
                        if (artLen < 0) throw new IOException("Bad art length");
                        if (artLen > 0 && artLen < META_ART_MAX_BYTES) {
                            byte[] artBytes = new byte[artLen];
                            din.readFully(artBytes);
                            art = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artLen);
                        } else if (artLen > 0) {
                            // Oversized art: consume it so the chat stream stays in sync (FIX_PLAN #15).
                            skipFully(din, artLen);
                        }
                        String subtitle = artist;
                        if (!album.isEmpty()) {
                            subtitle = subtitle.isEmpty() ? album : subtitle + " — " + album;
                        }
                        // Cache for notification use
                        receivedMetaTitle = title;
                        receivedMetaArtist = subtitle.isEmpty() ? "Audio Stream" : subtitle;
                        receivedAlbumArt = art;
                        receivedDurationMs = durMs;
                        receivedPositionMs = posMs;
                        final String t = title, s = receivedMetaArtist;
                        final Bitmap finalArt = art;
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (receiveFragment != null && receiveFragment.isAdded()) {
                                receiveFragment.updateNowPlayingMeta(t, s, finalArt);
                            }
                            updateReceiverNotification();
                        });
                    } else if (cmd == CTRL_PAUSE) {
                        // Server paused — pause playback on client too
                        runOnUiThread(() -> {
                            AudioTrack at = audioTrack;
                            if (at != null && !isPaused.get()) {
                                // Set the flag first so the receive thread stops writing (F-08),
                                // and tolerate a track released/recreated concurrently (FIX_PLAN #13).
                                isPaused.set(true);
                                try { at.pause(); } catch (IllegalStateException ignored) {}
                                updatePlayPauseButton(true);
                                updateNowPlayingState("Paused", R.color.accent_orange);
                                updateReceiverNotification();
                            }
                        });
                        addSystemChatMessage("Host paused playback");
                    } else if (cmd == CTRL_RESUME) {
                        // Server resumed — resume playback on client too
                        runOnUiThread(() -> {
                            AudioTrack at = audioTrack;
                            if (at != null && isPaused.get()) {
                                try { at.play(); } catch (IllegalStateException ignored) {}
                                isPaused.set(false);
                                updatePlayPauseButton(false);
                                updateNowPlayingState("Live", R.color.accent_green);
                                updateReceiverNotification();
                            }
                        });
                        addSystemChatMessage("Host resumed playback");
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (isReceiving.get())
                    Log.w(TAG, "Chat client error: " + e.getMessage());
            } finally {
                closeQuietly(sock);
                boolean wasOurs = sock != null && chatSocket == sock;
                if (wasOurs) {   // don't clear a newer session's chat (FIX_PLAN #12)
                    chatSocket = null;
                    chatClientOut = null;
                }
                // A superseded session must not print "Disconnected" over the new session's chat
                if (wasOurs || currentReceiveSession.get() == rs) {
                addSystemChatMessage("Chat disconnected");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (receiveFragment != null && receiveFragment.isAdded()) {
                        receiveFragment.updateChatStatus("Disconnected", R.color.accent_red);
                    }
                });
                }
            }
        }, "ChatClientThread");
        chatClientThread.start();
    }

    private void sendChatToServer(String text) {
        DataOutputStream out = chatClientOut;
        if (out == null) {
            Toast.makeText(this, "Chat not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        String senderName = SettingsFragment.getClientName(this);
        new Thread(() -> {
            try {
                writeChatMessage(out, senderName, text);
            } catch (IOException e) {
                Log.w(TAG, "Failed to send chat: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this,
                        "Failed to send message", Toast.LENGTH_SHORT).show());
            }
        }, "ChatSend").start();
    }

    private void stopChatClient() {
        chatClientOut = null;
        closeQuietly(chatSocket);
        chatSocket = null;
    }

    // ==========================================================
    //  PING SERVER
    // ==========================================================

    private void startPingServer() {
        pingServerThread = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PING_PORT));
                ss.setSoTimeout(1000);
                pingServerSocket = ss;
                while (isStreaming.get()) {
                    try {
                        Socket client = ss.accept();
                        client.setTcpNoDelay(true);
                        // Short per-read timeout; handlePingClient only drops after ~15 s idle.
                        client.setSoTimeout(PING_READ_TIMEOUT_MS);
                        new Thread(() -> handlePingClient(client),
                                "PingHandler").start();
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (isStreaming.get()) Log.e(TAG, "Ping server error", e);
            } finally {
                closeQuietly(ss);                                // FIX_PLAN #12
                if (pingServerSocket == ss) pingServerSocket = null;
            }
        }, "PingServerThread");
        pingServerThread.start();
    }

    private void handlePingClient(Socket client) {
        try {
            DataInputStream din = new DataInputStream(client.getInputStream());
            DataOutputStream dout = new DataOutputStream(client.getOutputStream());
            long lastRxMs = android.os.SystemClock.elapsedRealtime();
            while (isStreaming.get()) {
                byte cmd;
                try {
                    cmd = din.readByte();
                    lastRxMs = android.os.SystemClock.elapsedRealtime();
                } catch (SocketTimeoutException t) {
                    // FIX_PLAN #17: a Wi-Fi power-save stall/roam must not kill the control
                    // channel for the rest of the session; drop only after ~15 s of silence.
                    if (android.os.SystemClock.elapsedRealtime() - lastRxMs > PING_IDLE_DROP_MS) {
                        PipelineLog.w("CONNECT", "Ping client idle > "
                                + PING_IDLE_DROP_MS / 1000 + " s, dropping");
                        break;
                    }
                    continue;
                }
                if (cmd == PING_REQUEST) {
                    dout.writeByte(PING_RESPONSE);
                    dout.flush();
                } else if (cmd == LATENCY_REPORT) {
                    long ms = din.readLong();
                    currentLatencyMs.set(ms);
                    updateLatencyUI(ms);
                } else if (cmd == CTRL_PAUSE) {
                    // Remote client paused — pause capture and host media player
                    runOnUiThread(() -> {
                        AudioRecord ar = recorder;
                        if (ar != null && !isPaused.get()) {
                            try {
                                ar.stop();
                            } catch (IllegalStateException ise) {   // FIX_PLAN #28
                                Log.w(TAG, "Remote pause: recorder.stop failed: " + ise.getMessage());
                            }
                            isPaused.set(true);
                            updatePlayPauseButton(true);
                            updateNowPlayingState("Paused", R.color.accent_orange);
                            updateSenderNotification();
                        }
                    });
                    // Also pause the host media player (e.g. Poweramp)
                    MediaMetadataHelper.sendPause(MainActivity.this);
                    addSystemChatMessage("Remote client paused playback");
                } else if (cmd == CTRL_RESUME) {
                    // Remote client resumed — resume capture and host media player
                    runOnUiThread(() -> {
                        AudioRecord ar = recorder;
                        if (ar != null && isPaused.get()) {
                            try {
                                ar.startRecording();
                            } catch (IllegalStateException ise) {   // FIX_PLAN #28
                                Log.w(TAG, "Remote resume: startRecording failed: " + ise.getMessage());
                            }
                            isPaused.set(false);
                            updatePlayPauseButton(false);
                            updateNowPlayingState("Live", R.color.accent_green);
                            updateSenderNotification();
                        }
                    });
                    // Also resume the host media player (e.g. Poweramp)
                    MediaMetadataHelper.sendPlay(MainActivity.this);
                    addSystemChatMessage("Remote client resumed playback");
                } else if (cmd == CTRL_SKIP_FWD) {
                    // Remote client asked to skip forward — control the host media player
                    MediaMetadataHelper.sendSkipNext(MainActivity.this);
                    addSystemChatMessage("Remote client skipped forward");
                } else if (cmd == CTRL_SKIP_BACK) {
                    // Remote client asked to skip back — control the host media player
                    MediaMetadataHelper.sendSkipPrevious(MainActivity.this);
                    addSystemChatMessage("Remote client skipped back");
                }
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    private void stopPingServer() {
        closeQuietly(pingServerSocket);
        pingServerSocket = null;
    }

    // ==========================================================
    //  PING CLIENT
    // ==========================================================

    private void startPingClient(String serverIp, final ReceiveSession rs) {
        pingClientThread = new Thread(() -> {
            Socket sock = null;
            try {
                Thread.sleep(300);
                if (!isCurrentReceive(rs)) return;
                sock = new Socket();
                bindToLan(sock, serverIp);
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(2000);
                sock.connect(new InetSocketAddress(serverIp, PING_PORT), 3000);
                PipelineLog.i("CONNECT", "Ping channel connected to "
                        + PipelineLog.redactIps(serverIp));
                DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
                DataInputStream din = new DataInputStream(sock.getInputStream());
                if (!isCurrentReceive(rs)) return;   // a newer session owns the ping channel
                // ONE writer shared with sendControlMessage, guarded by pingWriteLock (FIX_PLAN #17)
                synchronized (pingWriteLock) {
                    pingOut = dout;
                }
                pingSocket = sock;
                long smoothedMs = -1;
                while (isCurrentReceive(rs)) {
                    try {
                        long startNs = System.nanoTime();
                        synchronized (pingWriteLock) {
                            dout.writeByte(PING_REQUEST);
                            dout.flush();
                        }
                        byte resp = din.readByte();
                        if (resp == PING_RESPONSE) {
                            long rttMs = (System.nanoTime() - startNs) / 1_000_000;
                            AudioConfig config = activeConfig.get();
                            long totalMs = rttMs / 2 + (long) config.bufferLatencyMs();
                            smoothedMs = smoothedMs < 0 ? totalMs
                                    : (long) (smoothedMs * 0.7 + totalMs * 0.3);
                            currentLatencyMs.set(smoothedMs);
                            updateLatencyUI(smoothedMs);
                            try {
                                synchronized (pingWriteLock) {   // byte + long atomically
                                    dout.writeByte(LATENCY_REPORT);
                                    dout.writeLong(smoothedMs);
                                    dout.flush();
                                }
                            } catch (IOException ignored) {
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        currentLatencyMs.set(999);
                        updateLatencyUI(999);
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (isReceiving.get())
                    Log.w(TAG, "Ping error: " + e.getMessage());
            } finally {
                closeQuietly(sock);
                // sock != null: a thread that exits before connecting must not clear a newer
                // session's pingOut, which is published before pingSocket
                if (sock != null && pingSocket == sock) {   // don't clear a newer session's channel (FIX_PLAN #12)
                    pingSocket = null;
                    pingOut = null;         // volatile; no lock (a stalled writer must not block us)
                }
            }
        }, "PingClientThread");
        pingClientThread.start();
    }

    private void stopPingClient() {
        closeQuietly(pingSocket);
        pingSocket = null;
        pingOut = null;
    }

    // ==========================================================
    //  STREAMING (with FLAC + Hi-Res support)
    // ==========================================================

    // ===== Session ownership (FIX_PLAN #12) =====
    // Each Stream/Receive start creates its own session object. A thread's finally block only
    // resets shared state (flags, fields, service, UI) if its session is still the current one,
    // so a slow old session can no longer tear down a newer one.

    /** Per-session state of one Stream (sender) session. */
    private static final class StreamSession {
        final AtomicBoolean running = new AtomicBoolean(true);
        /** Released once the capture format is final (or capture failed). */
        final CountDownLatch configReady = new CountDownLatch(1);
        final AtomicReference<AudioConfig> effectiveConfig = new AtomicReference<>();
        volatile ServerSocket server;
        volatile AudioRecord record;
        volatile String failReason;
    }

    private final AtomicReference<StreamSession> currentStreamSession = new AtomicReference<>();

    /** Waits until the session's capture format is known. Returns null if capture failed or the session ended. */
    private AudioConfig awaitEffectiveConfig(StreamSession s) throws InterruptedException {
        while (s.running.get()) {
            if (s.configReady.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return s.effectiveConfig.get();
            }
        }
        return null;
    }

    /** Stops (not releases) the session's AudioRecord so a blocked read() returns. */
    private void stopSessionRecorder(StreamSession s) {
        AudioRecord r = s.record;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * Builds and starts a playback-capture AudioRecord for {@code c}. Returns null (and logs) if the
     * device rejects that format, so the caller can try the next entry of the fallback chain.
     */
    @SuppressLint("MissingPermission")
    private AudioRecord openCaptureRecord(AudioConfig c, MediaProjection proj) {
        String desc = String.format(Locale.US, "%dHz %dbit %dch", c.sampleRate, c.bitsPerSample, c.channels);
        AudioRecord r = null;
        try {
            AudioPlaybackCaptureConfiguration cc =
                    new AudioPlaybackCaptureConfiguration.Builder(proj)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(c.encoding)
                    .setSampleRate(c.sampleRate)
                    .setChannelMask(c.channelInMask)
                    .build();

            int bytesPerFrame = c.channels * c.bytesPerSample;
            int minBuf = AudioRecord.getMinBufferSize(c.sampleRate, c.channelInMask, c.encoding);
            // ≥ the old chunk-based size, and ≥ ~250 ms so short network stalls don't overflow it
            int recBuf = Math.max(Math.max(minBuf, 0),
                    Math.max(c.chunkSize * (c.presetIndex <= 1 ? 2 : 4),
                            c.sampleRate * bytesPerFrame / 4));
            recBuf = getCalculatedBufferSize(recBuf, bytesPerFrame);

            r = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(recBuf)
                    .setAudioPlaybackCaptureConfig(cc)
                    .build();

            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                PipelineLog.w("CAPTURE_OPEN", desc + " not initialized (minBuf=" + minBuf + ")");
            } else {
                r.startRecording();
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    PipelineLog.i("CAPTURE_OPEN", desc + " ok, buffer=" + recBuf + " B");
                    return r;
                }
                PipelineLog.w("CAPTURE_OPEN", desc + " startRecording did not start");
            }
        } catch (UnsupportedOperationException | IllegalArgumentException | IllegalStateException e) {
            PipelineLog.w("CAPTURE_OPEN", desc + " rejected: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        if (r != null) {
            try { r.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Stops a specific MediaProjection instance; clears the field only if it still holds that instance. */
    private void releaseProjection(MediaProjection p) {
        if (p == null) return;
        if (mediaProjection == p) mediaProjection = null;
        try { p.stop(); } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void startStreaming() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus("Requires Android 10+");
            return;
        }

        // This session's projection instance (FIX_PLAN #12): finally stops this one, not the field
        final MediaProjection proj = mediaProjection;
        if (proj == null) {
            updateStatus("Screen capture permission missing — tap Start again");
            stopForegroundService();
            return;
        }

        final int presetIdx = selectedPreset;
        final AudioConfig config = new AudioConfig(
                QUALITY_PRESETS[presetIdx], presetIdx);
        activeConfig.set(config);

        if (config.useFLAC && !FlacCodec.isFlacEncoderAvailable()) {
            updateStatus("FLAC encoder not available on this device");
            return;
        }

        final StreamSession session = new StreamSession();
        StreamSession previous = currentStreamSession.getAndSet(session);
        if (previous != null) {
            previous.running.set(false);
            stopSessionRecorder(previous);
        }

        isStreaming.set(true);
        lastMetaKey = null;   // phase5 review L1
        totalBytesSent.set(0);
        receiverCount.set(0);
        connectedClients.clear();
        currentLatencyMs.set(-1);

        showStreamingUI(config);
        updateStatus(String.format(Locale.US, "Starting %s stream (%s %s %d-bit)...",
                config.compressionString(), config.sampleRateString(),
                config.channelString(), config.bitsPerSample));

        startPingServer();
        startChatServer();
        startUniversalDiscoveryServer("STREAM");

        final CountDownLatch acceptStopped = new CountDownLatch(1);

        // Accept thread: may accept early, but writes the header only once the capture
        // format is final (FIX_PLAN #5), so a fallback never sends a wrong header.
        acceptThread = new Thread(() -> {
            ServerSocket ls = null;
            try {
                ls = new ServerSocket();
                ls.setReuseAddress(true);
                int streamPort = getActiveStreamPort();
                try {
                    ls.bind(new InetSocketAddress(streamPort));
                } catch (java.net.BindException e) {
                    session.failReason = "Port " + streamPort + " in use";
                    updateStatus(session.failReason);
                    session.running.set(false);
                    stopSessionRecorder(session);   // the stream thread's finally cleans up
                    return;
                }
                ls.setSoTimeout(1000);
                session.server = ls;
                if (currentStreamSession.get() == session) serverSocket = ls;
                if (!session.running.get()) return;
                updateStatus("Waiting on " + getDeviceIpAddress() + ":" + streamPort);

                while (session.running.get() && isStreaming.get()) {
                    Socket client = null;
                    try {
                        client = ls.accept();
                        client.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                        client.setKeepAlive(true);
                        client.setTrafficClass(0x10);

                        AudioConfig eff = awaitEffectiveConfig(session);
                        if (eff == null || !session.running.get()) {
                            closeQuietly(client);
                            continue;
                        }
                        client.setSendBufferSize(AudioPipelineUtils.senderSocketBufferBytes(
                                eff.sampleRate, eff.channels, eff.bytesPerSample));

                        ClientConnection conn = new ClientConnection(client);
                        writeFormatHeader(conn.outputStream, eff);
                        connectedClients.add(conn);
                        receiverCount.incrementAndGet();
                        updateReceiverCount();
                        updateStatus("Streaming to " + receiverCount.get()
                                + " receiver(s)");
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        closeQuietly(client);
                        if (session.running.get()) Log.e(TAG, "Accept error", e);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (session.running.get()) {
                    Log.e(TAG, "Server error", e);
                    updateStatus("Server error: " + e.getMessage());
                }
            } finally {
                closeQuietly(ls);
                if (ls != null && serverSocket == ls) serverSocket = null;
                acceptStopped.countDown();
            }
        }, "AcceptThread");
        acceptThread.start();

        // Stream thread
        streamThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord lr = null;
            FlacCodec.Encoder flacEncoder = null;
            AudioConfig eff = null;

            try {
                Thread.sleep(200);
                if (!session.running.get()) return;

                // Open the capture BEFORE any header is sent: requested → 48k/16 → 44.1k/16 (FIX_PLAN #5)
                int[][] chain = AudioPipelineUtils.captureFallbackChain(QUALITY_PRESETS[presetIdx]);
                for (int i = 0; i < chain.length && lr == null && session.running.get(); i++) {
                    AudioConfig cand = (i == 0) ? config : new AudioConfig(chain[i], 2);
                    lr = openCaptureRecord(cand, proj);
                    if (lr != null) eff = cand;
                }
                if (lr == null || eff == null) {
                    if (session.running.get()) {
                        session.failReason = "This phone can't capture audio in any supported format";
                        PipelineLog.e("CAPTURE_OPEN", "capture fallback chain exhausted", null);
                    }
                    return;
                }
                session.record = lr;
                if (currentStreamSession.get() == session) recorder = lr;
                if (!session.running.get()) return;

                if (eff != config) {
                    PipelineLog.w("CAPTURE_FALLBACK", String.format(Locale.US,
                            "requested %dHz/%dbit, using %dHz/%dbit",
                            config.sampleRate, config.bitsPerSample,
                            eff.sampleRate, eff.bitsPerSample));
                }
                activeConfig.set(eff);
                session.effectiveConfig.set(eff);
                session.configReady.countDown();   // AcceptThread may now send headers
                if (eff != config) {
                    showStreamingUI(eff);
                    updateStatus(String.format(Locale.US,
                            "%s capture not supported on this phone — streaming %s/%d-bit",
                            config.isHiRes || config.bitsPerSample > 16 ? "Hi-Res" : "This format",
                            eff.sampleRateString(), eff.bitsPerSample));
                }

                // Initialize FLAC encoder if needed
                if (eff.useFLAC) {
                    flacEncoder = new FlacCodec.Encoder(
                            eff.sampleRate, eff.channels,
                            eff.bitsPerSample, eff.framesPerBuffer);
                    if (!flacEncoder.start()) {
                        session.failReason = "FLAC encoder failed to start";
                        return;
                    }
                    Log.i(TAG, "FLAC lossless encoding enabled");
                }

                byte[] pcmBuffer = new byte[eff.chunkSize];
                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());
                bytesSentThisSecond.set(0);

                int errorReads = 0;
                int deadReads = 0;
                long silentSinceMs = 0;
                boolean silenceHintShown = false;

                while (session.running.get() && isStreaming.get()) {
                    if (isPaused.get()) {
                        silentSinceMs = 0;
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }
                    int read = lr.read(pcmBuffer, 0, pcmBuffer.length);
                    if (!session.running.get()) break;
                    if (read <= 0) {
                        // Never spin on error codes (FIX_PLAN #13 / F-06)
                        if (read < 0) {
                            if (errorReads++ % 100 == 0) {
                                PipelineLog.w("READ_ERR", "AudioRecord.read=" + read
                                        + " (count " + errorReads + ")");
                            }
                            if (read == AudioRecord.ERROR_DEAD_OBJECT && ++deadReads >= 5) {
                                session.failReason = "Audio capture lost (audio system error)";
                                PipelineLog.e("READ_ERR", "AudioRecord dead, giving up", null);
                                break;
                            }
                        }
                        Thread.sleep(10);
                        continue;
                    }
                    deadReads = 0;
                    if (connectedClients.isEmpty()) {
                        silentSinceMs = 0;
                        continue;
                    }

                    // Silent-capture hint (FIX_PLAN #20): all-zero capture for ≥5 s with clients connected
                    if (!silenceHintShown) {
                        if (AudioPipelineUtils.isSilent(pcmBuffer, 0, read)) {
                            long nowMs = System.currentTimeMillis();
                            if (silentSinceMs == 0) {
                                silentSinceMs = nowMs;
                            } else if (nowMs - silentSinceMs >= 5000) {
                                silenceHintShown = true;
                                PipelineLog.w("SILENCE", "all-zero capture for 5 s with clients connected");
                                updateStatus("Capturing silence — the playing app may block audio capture");
                            }
                        } else {
                            silentSinceMs = 0;
                        }
                    }

                    byte[] sendData;
                    int sendLength;

                    if (eff.useFLAC && flacEncoder != null) {
                        byte[] compressed = flacEncoder.encode(pcmBuffer, read);
                        if (compressed == null || compressed.length == 0) continue;
                        sendData = compressed;
                        sendLength = compressed.length;
                    } else {
                        sendData = pcmBuffer;
                        sendLength = read;
                    }

                    // For FLAC: frame with length prefix
                    byte[] packet;
                    if (eff.useFLAC) {
                        packet = new byte[4 + sendLength];
                        packet[0] = (byte) ((sendLength >> 24) & 0xFF);
                        packet[1] = (byte) ((sendLength >> 16) & 0xFF);
                        packet[2] = (byte) ((sendLength >> 8) & 0xFF);
                        packet[3] = (byte) (sendLength & 0xFF);
                        System.arraycopy(sendData, 0, packet, 4, sendLength);
                    } else {
                        packet = null;
                    }

                    List<ClientConnection> failed = null;
                    for (ClientConnection cl : connectedClients) {
                        try {
                            if (cl.isConnected()) {
                                if (packet != null) {
                                    cl.outputStream.write(packet);
                                } else {
                                    cl.outputStream.write(sendData, 0, sendLength);
                                }
                            }
                        } catch (IOException e) {
                            cl.close();
                            if (failed == null) failed = new ArrayList<>();
                            failed.add(cl);
                        }
                    }

                    if (failed != null) {
                        connectedClients.removeAll(failed);
                        receiverCount.addAndGet(-failed.size());
                        updateReceiverCount();
                        updateStatus("Streaming to " + receiverCount.get()
                                + " receiver(s)");
                        if (connectedClients.isEmpty()) {
                            currentLatencyMs.set(-1);
                            updateLatencyUI(-1);
                        }
                    }

                    int bytes = sendLength * Math.max(1, connectedClients.size());
                    bytesSentThisSecond.addAndGet(bytes);
                    totalBytesSent.addAndGet(bytes);

                    long now = System.currentTimeMillis();
                    long diff = now - lastTimeMs.get();
                    if (diff >= 1000) {
                        long b = bytesSentThisSecond.getAndSet(0);
                        long kbps = (b * 8) / diff;
                        lastTimeMs.set(now);
                        updateStats(kbps, totalBytesSent.get(),
                                now - streamStartTime.get());
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (session.running.get()) {
                    Log.e(TAG, "Stream error", e);
                    session.failReason = "Stream error: " + e.getMessage();
                }
            } finally {
                session.running.set(false);
                session.configReady.countDown();   // never leave the AcceptThread waiting
                // Only the session that is still current may reset shared state (FIX_PLAN #12)
                boolean owner = currentStreamSession.compareAndSet(session, null);
                if (owner) {
                    isStreaming.set(false);
                    stopPingServer();
                    stopChatServer();
                    stopDiscoveryServer();
                }
                ServerSocket ss = session.server;
                closeQuietly(ss);
                if (ss != null && serverSocket == ss) serverSocket = null;
                try {
                    acceptStopped.await(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }

                if (flacEncoder != null) flacEncoder.release();

                if (lr != null && recorder == lr) recorder = null;
                cleanupRecorder(lr);
                // Stop THIS session's projection instance, never a newer one
                releaseProjection(proj);
                if (owner) {
                    for (ClientConnection c : connectedClients) c.close();
                    connectedClients.clear();
                    receiverCount.set(0);
                    stopForegroundService();
                    resetUI();
                    String reason = session.failReason;
                    updateStatus(reason != null ? "Stopped: " + reason : "Streaming stopped");
                }
            }
        }, "AudioStreamThread");
        streamThread.start();
    }

    private void stopStreaming() {
        if (!isStreaming.compareAndSet(true, false)) return;
        updateStatus("Stopping...");
        StreamSession s = currentStreamSession.get();
        if (s != null) {
            s.running.set(false);
            stopSessionRecorder(s);      // unblocks a pending AudioRecord.read()
            closeQuietly(s.server);
        }
        AudioRecord r = recorder;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        stopPingServer();
        stopChatServer();
        stopDiscoveryServer();
        closeQuietly(serverSocket);
        serverSocket = null;
        for (ClientConnection c : connectedClients) c.close();
        // Reset UI immediately instead of waiting for blocking read to complete
        resetUI();
    }

    // ==========================================================
    //  RECEIVING (with FLAC + Hi-Res support)
    // ==========================================================

    /** Per-session state of one Receive session (FIX_PLAN #12/#13/#16). */
    private static final class ReceiveSession {
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile Socket socket;
        volatile AudioTrack track;
        volatile String failReason;
        /** Track was built as PCM_16BIT for a 32-bit stream; convert in software. */
        boolean convert32to16;
        boolean trackRecreated;
        int playBuf;
        int readTimeouts;
        byte[] convBuf;
    }

    private final AtomicReference<ReceiveSession> currentReceiveSession = new AtomicReference<>();

    private AudioTrack tryBuildTrack(int encoding, int sampleRate, int channelMask,
                                     int bufBytes, int perfMode, String step) {
        AudioTrack t = null;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(perfMode)
                    .build();
            if (t.getState() == AudioTrack.STATE_INITIALIZED) {
                PipelineLog.i("TRACK_BUILD", String.format(Locale.US,
                        "%s: %dHz enc=%d buf=%d perf=%d", step, sampleRate, encoding, bufBytes, perfMode));
                return t;
            }
            PipelineLog.w("TRACK_FALLBACK", step + ": not initialized");
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            PipelineLog.w("TRACK_FALLBACK", step + " rejected: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        if (t != null) {
            try { t.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Receiver AudioTrack fallback chain (FIX_PLAN #13): requested perf mode → PERFORMANCE_MODE_NONE →
     * smaller buffer → (32-bit streams only) PCM_16BIT with software conversion. Returns null if all fail.
     */
    private AudioTrack buildReceiveTrack(AudioConfig c, ReceiveSession s) {
        int perf = getAudioPerformanceMode();
        int none = AudioTrack.PERFORMANCE_MODE_NONE;
        s.convert32to16 = false;
        AudioTrack t = tryBuildTrack(c.encoding, c.sampleRate, c.channelOutMask, s.playBuf, perf, "requested");
        if (t == null && perf != none) {
            t = tryBuildTrack(c.encoding, c.sampleRate, c.channelOutMask, s.playBuf, none, "perf NONE");
        }
        if (t == null) {
            int minBuf = AudioTrack.getMinBufferSize(c.sampleRate, c.channelOutMask, c.encoding);
            int small = Math.max(minBuf * 2, 32768);
            t = tryBuildTrack(c.encoding, c.sampleRate, c.channelOutMask, small, none, "small buffer");
        }
        if (t == null && c.bytesPerSample == 4 && !c.isFloat) {
            int min16 = AudioTrack.getMinBufferSize(c.sampleRate, c.channelOutMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buf16 = Math.max(Math.max(min16 * 2, 32768), s.playBuf / 2);
            t = tryBuildTrack(AudioFormat.ENCODING_PCM_16BIT, c.sampleRate, c.channelOutMask,
                    buf16, none, "32->16-bit conversion");
            if (t != null) s.convert32to16 = true;
        }
        return t;
    }

    /** True while the ping channel to the streamer is connected and answering. */
    private boolean isPingChannelAlive() {
        Socket ps = pingSocket;
        long lat = currentLatencyMs.get();
        return ps != null && ps.isConnected() && !ps.isClosed() && lat >= 0 && lat < 999;
    }

    /** Audio socket read timed out: keep going while paused or while the ping channel is alive (FIX_PLAN #16). */
    private boolean tolerateReadTimeout(ReceiveSession s) {
        if (isPaused.get() || isPingChannelAlive()) {
            s.readTimeouts = 0;
            return true;
        }
        s.readTimeouts++;
        PipelineLog.w("READ_ERR", "audio socket read timeout #" + s.readTimeouts);
        if (s.readTimeouts >= 3) {
            s.failReason = "Streamer not responding";
            return false;
        }
        return true;
    }

    /**
     * Writes one PCM chunk to the session's track: optional 32→16-bit conversion, format-aware DSP,
     * write-error handling with one track recreation. Returns false if the session must end.
     */
    private boolean writeToReceiveTrack(ReceiveSession s, AudioConfig config, byte[] data, int len) {
        AudioTrack t = s.track;
        if (t == null) return false;
        byte[] out = data;
        int outLen = len;
        int bps = config.bytesPerSample;
        if (s.convert32to16) {
            int need = len / 2;
            if (s.convBuf == null || s.convBuf.length < need) s.convBuf = new byte[need];
            outLen = AudioPipelineUtils.convert32to16(data, 0, len, s.convBuf);
            out = s.convBuf;
            bps = 2;
        }
        AudioPipelineUtils.applyDsp(out, 0, outLen,
                SettingsFragment.getAudioGainMultiplier(MainActivity.this),
                SettingsFragment.getChannelMode(MainActivity.this),
                bps, config.channels);
        int w = t.write(out, 0, outLen);
        if (w >= 0) return true;
        // stop()/pause() from another thread can make a write fail; that is not an output error
        if (!s.running.get() || isPaused.get()) return true;

        PipelineLog.w("WRITE_ERR", "AudioTrack.write=" + w);
        if (s.trackRecreated) {
            s.failReason = "Audio output failed (error " + w + ")";
            return false;
        }
        s.trackRecreated = true;
        boolean owner = currentReceiveSession.get() == s;
        if (owner && audioTrack == t) audioTrack = null;
        s.track = null;
        cleanupTrack(t);
        AudioTrack nt = buildReceiveTrack(config, s);
        if (nt == null) {
            s.failReason = "Audio output lost and could not be recreated";
            return false;
        }
        s.track = nt;
        if (currentReceiveSession.get() == s) audioTrack = nt;
        try {
            nt.play();
        } catch (IllegalStateException e) {
            s.failReason = "Audio output could not restart";
            return false;
        }
        PipelineLog.i("TRACK_BUILD", "AudioTrack recreated after write error " + w);
        return true;
    }

    private void startReceiving(String streamerIp) {
        final ReceiveSession session = new ReceiveSession();
        ReceiveSession previous = currentReceiveSession.getAndSet(session);
        if (previous != null) {
            previous.running.set(false);
            closeQuietly(previous.socket);
            stopTrackQuietly(previous.track);
        }

        isReceiving.set(true);
        totalBytesSent.set(0);
        bytesSentThisSecond.set(0);
        currentLatencyMs.set(-1);
        showReceivingUI();
        updateStatus("Connecting to " + streamerIp + "...");
        startReceiverService();

        receiveThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Socket socket = null;
            InputStream rawIn = null;
            DataInputStream dataIn = null;
            AudioTrack lt = null;
            FlacCodec.Decoder flacDecoder = null;

            try {
                socket = new Socket();
                // Published before connect() so stopReceiving() can abort a pending connect (FIX_PLAN #12)
                session.socket = socket;
                activeSocket = socket;
                // Same LAN binding rule as M3's ping/chat clients (skips hotspot-subnet targets)
                bindToLan(socket, streamerIp);
                socket.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                int bufMul = SettingsFragment.getSocketMultiplier(MainActivity.this);
                // SO_RCVBUF deliberately not set: keep Linux receive-window autotuning (FIX_PLAN #16)
                socket.setTrafficClass(0x10);
                if (!session.running.get()) return;
                int port = getActiveStreamPort();
                PipelineLog.i("CONNECT", "connecting to "
                        + PipelineLog.redactIps(streamerIp) + ":" + port);
                socket.connect(new InetSocketAddress(streamerIp, port),
                        SettingsFragment.getConnTimeoutMs(MainActivity.this));
                socket.setSoTimeout(5000);
                PipelineLog.i("CONNECT", "connected");
                if (!session.running.get()) return;

                rawIn = new BufferedInputStream(socket.getInputStream(), Math.max(16384, 8192 * bufMul));
                AudioConfig config = readFormatHeader(rawIn);
                if (config == null) {
                    session.failReason = "Invalid stream format";
                    PipelineLog.w("HDR_RX", "invalid header");
                    return;
                }
                activeConfig.set(config);

                if (config.useFLAC && !FlacCodec.isFlacDecoderAvailable()) {
                    session.failReason = "FLAC decoder not available on this device";
                    return;
                }

                if (!session.running.get()) return;
                updateReceivingUI(config, streamerIp);
                updateReceiverNotification();
                startPingClient(streamerIp, session);
                startChatClient(streamerIp, session);

                updateStatus(String.format(Locale.US,
                        "Receiving %s (%s %s %d-bit)",
                        config.compressionString(), config.sampleRateString(),
                        config.channelString(), config.bitsPerSample));

                // Initialize FLAC decoder if needed
                if (config.useFLAC) {
                    flacDecoder = new FlacCodec.Decoder(
                            config.sampleRate, config.channels,
                            config.bitsPerSample);
                    if (!flacDecoder.start()) {
                        session.failReason = "FLAC decoder failed to start";
                        return;
                    }
                    Log.i(TAG, "FLAC lossless decoding enabled");
                    dataIn = new DataInputStream(rawIn);
                }

                int bytesPerFrame = config.channels * config.bytesPerSample;
                int minBuf = AudioTrack.getMinBufferSize(
                        config.sampleRate, config.channelOutMask, config.encoding);
                int playBuf = minBuf > 0
                        ? Math.max(minBuf * bufMul, config.chunkSize * 2)
                        : Math.max(config.chunkSize * 2, 32768);
                playBuf = getCalculatedBufferSize(playBuf, bytesPerFrame);
                session.playBuf = playBuf;

                lt = buildReceiveTrack(config, session);
                if (lt == null) {
                    session.failReason = String.format(Locale.US,
                            "This phone can't play %s/%d-bit audio",
                            config.sampleRateString(), config.bitsPerSample);
                    PipelineLog.e("TRACK_BUILD", "AudioTrack fallback chain exhausted", null);
                    return;
                }
                session.track = lt;
                if (currentReceiveSession.get() == session) audioTrack = lt;
                if (!session.running.get()) return;
                lt.play();

                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());

                if (config.useFLAC) {
                    receiveFlacLoop(dataIn, session, flacDecoder, config);
                } else {
                    receivePcmLoop(rawIn, session, config);
                }

            } catch (java.net.ConnectException e) {
                if (session.running.get()) {
                    session.failReason = "Cannot connect to " + streamerIp;
                    PipelineLog.w("CONNECT", "connect refused/failed: " + e.getClass().getSimpleName());
                }
            } catch (SocketTimeoutException e) {
                if (session.running.get()) {
                    session.failReason = "Connection timed out";
                    PipelineLog.w("CONNECT", "connect/header timeout");
                }
            } catch (java.io.EOFException e) {
                if (session.running.get()) session.failReason = "Streamer closed the connection";
            } catch (IOException e) {
                if (session.running.get()) {
                    Log.e(TAG, "Receive error", e);
                    session.failReason = "Receive error: " + e.getMessage();
                }
            } catch (Exception e) {
                if (session.running.get()) {
                    Log.e(TAG, "Error", e);
                    session.failReason = "Error: " + e.getMessage();
                }
            } finally {
                session.running.set(false);
                if (flacDecoder != null) flacDecoder.release();
                AudioTrack st = session.track;
                // Clear the shared field first so UI-thread handlers don't touch a released track
                if (st != null && audioTrack == st) audioTrack = null;
                if (lt != null && audioTrack == lt) audioTrack = null;
                cleanupTrack(st);
                if (lt != null && lt != st) cleanupTrack(lt);
                closeQuietly(rawIn);
                closeQuietly(socket);
                if (activeSocket == socket) activeSocket = null;
                // Only the session that is still current may reset shared state (FIX_PLAN #12)
                if (currentReceiveSession.compareAndSet(session, null)) {
                    isReceiving.set(false);
                    stopPingClient();
                    stopChatClient();
                    stopReceiverService();
                    resetUI();
                    String reason = session.failReason;
                    updateStatus(reason != null ? "Stopped: " + reason : "Receiving stopped");
                }
            }
        }, "ReceiveThread");
        receiveThread.start();
    }

    private void receiveFlacLoop(DataInputStream dataIn, ReceiveSession s,
                                 FlacCodec.Decoder decoder, AudioConfig config)
            throws IOException {

        byte[] compressedBuf = new byte[config.chunkSize];

        while (s.running.get() && isReceiving.get()) {
            int frameLen;
            try {
                frameLen = dataIn.readInt();
            } catch (SocketTimeoutException te) {
                if (tolerateReadTimeout(s)) continue;
                break;
            }
            s.readTimeouts = 0;
            if (frameLen <= 0 || frameLen > config.chunkSize * 2) {
                // No resync marker in the stream: continuing would desync forever
                Log.w(TAG, "Invalid FLAC frame length: " + frameLen);
                s.failReason = "Corrupt FLAC stream";
                break;
            }

            if (compressedBuf.length < frameLen) {
                compressedBuf = new byte[frameLen];
            }

            dataIn.readFully(compressedBuf, 0, frameLen);

            byte[] pcmData = decoder.decode(compressedBuf, frameLen);
            if (pcmData != null && pcmData.length > 0 && !isPaused.get() && s.running.get()) {
                if (!writeToReceiveTrack(s, config, pcmData, pcmData.length)) break;
            }

            totalBytesSent.addAndGet(frameLen);
            bytesSentThisSecond.addAndGet(frameLen);

            long now = System.currentTimeMillis();
            long diff = now - lastTimeMs.get();
            if (diff >= 1000) {
                long bytes = bytesSentThisSecond.getAndSet(0);
                long kbps = (bytes * 8) / diff;
                lastTimeMs.set(now);
                updateStats(kbps, totalBytesSent.get(),
                        now - streamStartTime.get());
            }
        }
    }

    private void receivePcmLoop(InputStream in, ReceiveSession s,
                                AudioConfig config) throws IOException {

        byte[] buffer = new byte[config.chunkSize];

        while (s.running.get() && isReceiving.get()) {
            int totalRead = 0;
            boolean end = false;
            while (totalRead < buffer.length && s.running.get()) {
                int read;
                try {
                    read = in.read(buffer, totalRead,
                            buffer.length - totalRead);
                } catch (SocketTimeoutException te) {
                    // 5 s without data: fine while paused / ping alive, else give up (FIX_PLAN #16)
                    if (tolerateReadTimeout(s)) continue;
                    end = true;
                    break;
                }
                if (read == -1) {
                    if (s.running.get()) s.failReason = "Streamer disconnected";
                    end = true;
                    break;
                }
                s.readTimeouts = 0;
                totalRead += read;
            }
            if (end) break;

            if (totalRead == buffer.length && s.running.get()) {
                if (!isPaused.get()) {
                    if (!writeToReceiveTrack(s, config, buffer, totalRead)) break;
                }
                totalBytesSent.addAndGet(totalRead);
                bytesSentThisSecond.addAndGet(totalRead);

                long now = System.currentTimeMillis();
                long diff = now - lastTimeMs.get();
                if (diff >= 1000) {
                    long bytes = bytesSentThisSecond.getAndSet(0);
                    long kbps = (bytes * 8) / diff;
                    lastTimeMs.set(now);
                    updateStats(kbps, totalBytesSent.get(),
                            now - streamStartTime.get());
                }
            }
        }
    }

    private void stopReceiving() {
        if (!isReceiving.compareAndSet(true, false)) return;
        updateStatus("Stopping...");
        ReceiveSession rs = currentReceiveSession.get();
        if (rs != null) {
            rs.running.set(false);
            closeQuietly(rs.socket);           // also aborts a pending connect()
            stopTrackQuietly(rs.track);        // unblocks a pending AudioTrack.write()
        }
        stopTrackQuietly(audioTrack);
        stopPingClient();
        stopChatClient();
        Socket s = activeSocket;
        if (s != null) {
            closeQuietly(s);
            activeSocket = null;
        }
        stopReceiverService();
    }

    /** pause+flush+stop without releasing; used to unblock a writer thread. */
    private void stopTrackQuietly(AudioTrack t) {
        if (t == null) return;
        try { t.pause(); } catch (Exception ignored) {}
        try { t.flush(); } catch (Exception ignored) {}
        try { t.stop(); } catch (Exception ignored) {}
    }

    // ---------- SERVICE HELPERS ----------

    // AudioReceiveService.start/stop defer a stop that races an in-flight startForegroundService
    // (FIX_PLAN #8); stop(ctx, mode) only stops the service if it runs in that mode.
    private void startReceiverService() {
        if (!AudioReceiveService.start(this, AudioReceiveService.MODE_RECEIVE)) {
            Log.w(TAG, "Failed to start receiver service");
        }
    }

    private void stopReceiverService() {
        AudioReceiveService.stop(this, AudioReceiveService.MODE_RECEIVE);
    }

    /** FIX_PLAN #14: keep File Share sync playback in a mediaPlayback FGS. Call from a user tap. */
    private void startFileShareService() {
        if (!AudioReceiveService.start(this, AudioReceiveService.MODE_FILESHARE)) {
            Log.w(TAG, "Failed to start file share service");
        }
    }

    private void stopFileShareService() {
        AudioReceiveService.stop(this, AudioReceiveService.MODE_FILESHARE);
    }

    // ---------- CLEANUP ----------
    private void stopForegroundService() {
        try {
            if (AudioCaptureService.stop(this)) {   // deferred while a start is in flight
                PipelineLog.i("FGS_STOP", "capture service stop requested");
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void cleanupRecorder(AudioRecord r) {
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void cleanupTrack(AudioTrack t) {
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void releaseProjection() {
        MediaProjection p = mediaProjection;
        mediaProjection = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
        }
    }

    // ===== FULL DUPLEX =====

    private final AtomicBoolean isDuplexActive = new AtomicBoolean(false);
    private volatile ServerSocket duplexServerSocket;
    private volatile Socket duplexSocket;
    private volatile Thread duplexAcceptThread, duplexSendThread, duplexRecvThread;
    private volatile AudioRecord duplexRecorder;
    private volatile AudioTrack duplexTrack;
    private final AtomicLong duplexStartTime = new AtomicLong(0);
    private final AtomicLong duplexBytesSent = new AtomicLong(0);
    private final AtomicLong duplexBytesRecv = new AtomicLong(0);
    private volatile int duplexPresetIndex = 2; // default Balanced
    private volatile Socket duplexChatSocket;
    private volatile ServerSocket duplexChatServerSocket;
    private volatile DataOutputStream duplexChatOut;
    private volatile DataInputStream duplexChatIn;
    private volatile Thread duplexChatThread;
    private volatile Thread duplexMetadataThread;
    // Remote peer metadata received over duplex chat
    private volatile String duplexRemoteTitle = null;
    private volatile String duplexRemoteArtist = null;
    private volatile Bitmap duplexRemoteArt = null;
    private volatile long duplexRemoteDurationMs = 0;
    private volatile long duplexRemotePositionMs = 0;

    private final Handler duplexNotifHandler = new Handler(Looper.getMainLooper());
    private final Runnable duplexNotifRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDuplexActive.get()) return;
            updateDuplexNotificationNow();
            duplexNotifHandler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onDuplexQualityChanged(int presetIndex) {
        // Duplex ALWAYS uses 48kHz Stereo 16-bit
        // Android AudioPlaybackCapture only works at 48kHz (system mixer limit)
        // This callback is now a no-op since spinner is removed
    }

    @Override
    public void onDuplexTogglePlayPause() {
        togglePlayPause();
    }

    @Override
    public void onDuplexSkipPrev() {
        onSkipPrevious();
    }

    @Override
    public void onDuplexSkipNext() {
        onSkipNext();
    }

    @Override
    public void onDuplexChatSend(String message) {
        if (!isDuplexActive.get() || duplexChatOut == null) return;
        String senderName = SettingsFragment.getClientName(this);
        // Show in local UI
        runOnUiThread(() -> {
            if (duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.addChatMessage(
                    new ChatMessage("You", message, ChatMessage.TYPE_SENT));
            }
        });
        // Send over network
        new Thread(() -> {
            try {
                writeChatMessage(duplexChatOut, senderName, message);
            } catch (Exception e) {
                Log.w(TAG, "Duplex chat send error", e);
            }
        }, "DuplexChatSend").start();
    }

    @Override
    public void onDuplexHost() {
        if (isDuplexActive.get()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            duplexFragment.updateStatus("Requires Android 10+");
            return;
        }
        String other = activeOtherMode("Duplex");
        if (other != null) {
            duplexFragment.updateStatus(modeBusyMessage(other));
            return;
        }
        if (!hasRecordAudioPermission()) {
            pendingAudioPermAction = "duplex_host";
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return; // continues in onRequestPermissionsResult
        }
        isDuplexActive.set(true);
        duplexFragment.setActive(true);
        duplexFragment.updateStatus("Requesting audio capture permission...");
        duplexFragment.updateConnectionStatus("Starting host...", R.color.accent_orange);
        duplexPendingMode = "host";
        duplexPendingIp = null;
        duplexResultLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    @Override
    public void onDuplexJoin(String ip) {
        if (isDuplexActive.get()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            duplexFragment.updateStatus("Requires Android 10+");
            return;
        }
        String other = activeOtherMode("Duplex");
        if (other != null) {
            duplexFragment.updateStatus(modeBusyMessage(other));
            return;
        }
        if (!hasRecordAudioPermission()) {
            pendingAudioPermAction = "duplex_join";
            pendingDuplexJoinIp = ip;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return; // continues in onRequestPermissionsResult
        }
        isDuplexActive.set(true);
        duplexFragment.setActive(true);
        duplexFragment.updateStatus("Requesting audio capture permission...");
        duplexFragment.updateConnectionStatus("Connecting...", R.color.accent_orange);
        duplexPendingMode = "join";
        duplexPendingIp = ip;
        duplexResultLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void startDuplexHostWithProjection() {
        // Update notification immediately with initial metadata
        updateDuplexNotificationNow();

        duplexFragment.updateStatus("Waiting for peer on port " + DUPLEX_PORT + "...");
        startUniversalDiscoveryServer("DUPLEX");
        duplexAcceptThread = new Thread(() -> {
            try {
                duplexServerSocket = new ServerSocket(DUPLEX_PORT);
                duplexServerSocket.setSoTimeout(0);
                Socket client = duplexServerSocket.accept();
                client.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                String peerIp = client.getInetAddress().getHostAddress();
                mainHandler.post(() -> {
                    duplexFragment.updateStatus("Connected to " + peerIp);
                    duplexFragment.updateConnectionStatus("Connected \u2014 Full Duplex",
                            R.color.accent_green);
                    updateDuplexNotificationNow();
                    duplexNotifHandler.postDelayed(duplexNotifRunnable, 2000);
                });
                startDuplexStreams(client);
                startDuplexChat(true, null);
            } catch (IOException e) {
                if (isDuplexActive.get()) {
                    mainHandler.post(() -> {
                        duplexFragment.updateStatus("Host error: " + e.getMessage());
                        onDuplexStop();
                    });
                }
            }
        }, "DuplexHost");
        duplexAcceptThread.start();
    }

    private void startDuplexJoinWithProjection(String ip) {
        // Update notification immediately
        updateDuplexNotificationNow();

        duplexFragment.updateStatus("Connecting to " + ip + "...");
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                bindToLan(socket, ip);
                socket.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                socket.connect(new InetSocketAddress(ip, DUPLEX_PORT),
                        SettingsFragment.getConnTimeoutMs(this));
                mainHandler.post(() -> {
                    duplexFragment.updateStatus("Connected to " + ip);
                    duplexFragment.updateConnectionStatus("Connected \u2014 Full Duplex",
                            R.color.accent_green);
                    updateDuplexNotificationNow();
                    duplexNotifHandler.postDelayed(duplexNotifRunnable, 2000);
                });
                startDuplexStreams(socket);
                startDuplexChat(false, ip);
            } catch (Exception e) {
                mainHandler.post(() -> {
                    duplexFragment.updateStatus("Connection failed: " + e.getMessage());
                    onDuplexStop();
                });
            }
        }, "DuplexJoin").start();
    }

    @SuppressLint({"MissingPermission", "WrongConstant"})
    private void startDuplexStreams(Socket socket) {
        duplexSocket = socket;
        duplexStartTime.set(System.currentTimeMillis());
        duplexBytesSent.set(0);
        duplexBytesRecv.set(0);

        // AudioPlaybackCapture ONLY works at 48kHz on Android.
        // The system audio mixer always runs at 48kHz internally.
        // Requesting 96/192kHz produces noise/garbage.
        int sampleRate = 48000;
        int channels = 2;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int channelInMask = AudioFormat.CHANNEL_IN_STEREO;
        int channelOutMask = AudioFormat.CHANNEL_OUT_STEREO;
        int bytesPerSample = 2;
        int bytesPerSecond = sampleRate * channels * bytesPerSample;

        int minRecBuf = AudioRecord.getMinBufferSize(sampleRate, channelInMask, encoding);
        int minPlayBuf = AudioTrack.getMinBufferSize(sampleRate, channelOutMask, encoding);
        int bufMul = SettingsFragment.getSocketMultiplier(this);

        // Larger buffer = more stable audio, less dropouts
        int halfSecBuf = bytesPerSecond / 2;
        int recBuf = Math.max(Math.max(minRecBuf * bufMul, halfSecBuf), 4096);
        int playBuf = Math.max(Math.max(minPlayBuf * bufMul, halfSecBuf), 4096);

        try {
            socket.setTcpNoDelay(true);  // Low latency for duplex
            socket.setSendBufferSize(halfSecBuf * 2);
            socket.setReceiveBufferSize(halfSecBuf * 2);
        } catch (Exception ignored) {}

        // Send thread — capture system audio to socket
        duplexSendThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord rec = null;
            try {
                Thread.sleep(200);
                if (!isDuplexActive.get() || socket.isClosed()) return;

                // Send the 6-byte header FIRST (format is fixed), so the peer never blocks on it
                // if our capture fails (FIX_PLAN #24): sampleRate(4) + channels(1) + encoding(1)
                OutputStream out = socket.getOutputStream();
                byte[] header = new byte[6];
                header[0] = (byte) ((sampleRate >> 24) & 0xFF);
                header[1] = (byte) ((sampleRate >> 16) & 0xFF);
                header[2] = (byte) ((sampleRate >> 8) & 0xFF);
                header[3] = (byte) (sampleRate & 0xFF);
                header[4] = (byte) channels;
                header[5] = (byte) encoding;
                out.write(header);
                out.flush();

                AudioPlaybackCaptureConfiguration captureConfig =
                        new AudioPlaybackCaptureConfiguration.Builder(duplexProjection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();

                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelInMask)
                        .build();

                try {
                    rec = new AudioRecord.Builder()
                            .setAudioFormat(fmt)
                            .setBufferSizeInBytes(recBuf)
                            .setAudioPlaybackCaptureConfig(captureConfig)
                            .build();
                } catch (UnsupportedOperationException | IllegalArgumentException e) {
                    PipelineLog.w("CAPTURE_OPEN", "duplex 48000Hz 16bit 2ch rejected: "
                            + e.getClass().getSimpleName());
                    rec = null;
                }
                if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    PipelineLog.w("CAPTURE_OPEN", "duplex AudioRecord init failed");
                    // Connection stays up: we still hear the peer, the peer just gets no audio from us
                    mainHandler.post(() -> duplexFragment.updateStatus(
                            "Capture failed on this phone — you can still hear the other side"));
                    return;
                }
                duplexRecorder = rec;
                rec.startRecording();
                PipelineLog.i("CAPTURE_OPEN", "duplex 48000Hz 16bit 2ch ok");

                int bytesPerFrame = channels * bytesPerSample;
                int chunkSize = Math.max(minRecBuf, 4096);
                chunkSize = (chunkSize / bytesPerFrame) * bytesPerFrame;
                byte[] buffer = new byte[chunkSize];
                int errorReads = 0;
                int deadReads = 0;
                while (isDuplexActive.get() && !socket.isClosed()) {
                    int read = rec.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        deadReads = 0;
                        out.write(buffer, 0, read);
                        duplexBytesSent.addAndGet(read);
                    } else {
                        // Never spin on error codes (FIX_PLAN #24)
                        if (read < 0) {
                            if (errorReads++ % 100 == 0) {
                                PipelineLog.w("READ_ERR", "duplex AudioRecord.read=" + read);
                            }
                            if (read == AudioRecord.ERROR_DEAD_OBJECT && ++deadReads >= 5) {
                                mainHandler.post(() -> duplexFragment.updateStatus(
                                        "Audio capture lost (audio system error)"));
                                break;
                            }
                        }
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                if (isDuplexActive.get()) {
                    mainHandler.post(() ->
                            duplexFragment.updateStatus("Send error: " + e.getMessage()));
                }
            } finally {
                if (rec != null && duplexRecorder == rec) duplexRecorder = null;
                cleanupRecorder(rec);
            }
        }, "DuplexSend");

        // Receive thread — socket to speaker
        duplexRecvThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            try {
                InputStream in = socket.getInputStream();
                byte[] header = new byte[6];
                int hRead = 0;
                while (hRead < 6) {
                    int r = in.read(header, hRead, 6 - hRead);
                    if (r < 0) throw new IOException("EOF reading header");
                    hRead += r;
                }
                int remoteSR = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                int remoteCh = header[4] & 0xFF;
                int remoteEnc = header[5] & 0xFF;

                int remoteBPS = (remoteEnc == AudioFormat.ENCODING_PCM_FLOAT) ? 4
                        : (remoteEnc == AudioFormat.ENCODING_PCM_8BIT) ? 1 : 2;
                int bytesPerSec = remoteSR * remoteCh * remoteBPS;
                int remoteOutMask = (remoteCh == 1)
                        ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                int remoteMinBuf = AudioTrack.getMinBufferSize(
                        remoteSR, remoteOutMask, remoteEnc);
                int halfSec = bytesPerSec / 2;
                int remotePlayBuf = Math.max(
                        Math.max(remoteMinBuf * bufMul, halfSec), 4096);
                remotePlayBuf = getCalculatedBufferSize(remotePlayBuf);

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setAllowedCapturePolicy(
                                        AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(remoteSR)
                                .setChannelMask(remoteOutMask)
                                .setEncoding(remoteEnc)
                                .build())
                        .setBufferSizeInBytes(remotePlayBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(getAudioPerformanceMode())
                        .build();
                duplexTrack = track;
                track.play();

                String srStr = String.format(Locale.US, "%.1f kHz",
                        remoteSR / 1000.0);
                String chStr = remoteCh == 1 ? "Mono" : "Stereo";

                int bytesPerFrame = remoteCh * remoteBPS;
                int recvChunkSize = Math.max(remoteMinBuf, 4096);
                recvChunkSize = (recvChunkSize / bytesPerFrame) * bytesPerFrame;
                byte[] buffer = new byte[recvChunkSize];

                while (isDuplexActive.get() && !socket.isClosed()) {
                    int totalRead = 0;
                    while (totalRead < buffer.length && isDuplexActive.get() && !socket.isClosed()) {
                        int read = in.read(buffer, totalRead, buffer.length - totalRead);
                        if (read < 0) {
                            break;
                        }
                        totalRead += read;
                    }
                    if (totalRead <= 0) break;

                    int alignedBytes = (totalRead / bytesPerFrame) * bytesPerFrame;
                    if (alignedBytes > 0) {
                        if (!isPaused.get()) {
                            if (remoteEnc != AudioFormat.ENCODING_PCM_FLOAT) {
                                AudioPipelineUtils.applyDsp(buffer, 0, alignedBytes,
                                        SettingsFragment.getAudioGainMultiplier(MainActivity.this),
                                        SettingsFragment.getChannelMode(MainActivity.this),
                                        remoteBPS, remoteCh);
                            }
                            int w = track.write(buffer, 0, alignedBytes);
                            if (w < 0 && isDuplexActive.get() && !isPaused.get()) {
                                PipelineLog.w("WRITE_ERR", "duplex AudioTrack.write=" + w);
                            }
                        }
                        duplexBytesRecv.addAndGet(alignedBytes);
                    }

                    // Update stats periodically
                    long elapsed = System.currentTimeMillis() - duplexStartTime.get();
                    if (elapsed > 0 && (duplexBytesRecv.get() % (remotePlayBuf * 4L)) < alignedBytes) {
                        long sentKbps = (duplexBytesSent.get() * 8) / elapsed;
                        long recvKbps = (duplexBytesRecv.get() * 8) / elapsed;
                        long secs = elapsed / 1000;
                        String dur = String.format(Locale.US, "%02d:%02d",
                                secs / 60, secs % 60);
                        long latencyMs = bytesPerSec > 0
                                ? ((long) remotePlayBuf * 1000L) / bytesPerSec : 0;
                        String pingStr = latencyMs + " ms";

                        srStr = String.format(Locale.US, "%.1f kHz",
                                remoteSR / 1000.0);
                        chStr = remoteCh == 1 ? "Mono" : "Stereo";

                        // Get metadata — ONLY show remote peer's metadata, never local media
                        String npTitle, npSub;
                        Bitmap npArt;
                        long durMs, posMs;

                        if (duplexRemoteTitle != null && !duplexRemoteTitle.isEmpty()
                                && !"Unknown".equals(duplexRemoteTitle)) {
                            npTitle = duplexRemoteTitle;
                            npSub = duplexRemoteArtist != null
                                    ? duplexRemoteArtist : "Full Duplex";
                            npArt = duplexRemoteArt;
                            durMs = duplexRemoteDurationMs;
                            posMs = duplexRemotePositionMs;
                        } else {
                            npTitle = srStr + " Duplex Stream";
                            npSub = chStr + " · 16-bit · Full Duplex";
                            npArt = null;
                            durMs = 0;
                            posMs = 0;
                        }

                        final String fT = npTitle, fS = npSub;
                        final Bitmap fA = npArt;
                        final long fD = durMs, fP = posMs;
                        final String fSr = srStr;
                        final String fCh = chStr;

                        mainHandler.post(() -> {
                            duplexFragment.updateStats(
                                    sentKbps + " kbps",
                                    recvKbps + " kbps", dur);
                            duplexFragment.updateDetailedStats(
                                    fSr, pingStr, fCh);
                            duplexFragment.updateConnectionStatus(
                                    "Connected — Full Duplex",
                                    R.color.accent_green);
                            duplexFragment.updateNowPlaying(fT, fS, fA);
                            duplexFragment.updateNowPlayingElapsed(dur);
                            AudioCaptureService.updateNotification(
                                    MainActivity.this, fT, fS, fA,
                                    isPaused.get(), fD, fP);
                        });
                    }
                }
            } catch (Exception e) {
                if (isDuplexActive.get()) {
                    mainHandler.post(() ->
                            duplexFragment.updateStatus(
                                    "Receive error: " + e.getMessage()));
                }
            } finally {
                cleanupTrack(duplexTrack);
                duplexTrack = null;
                if (isDuplexActive.get()) {
                    mainHandler.post(() -> onDuplexStop());
                }
            }
        }, "DuplexRecv");

        duplexSendThread.start();
        duplexRecvThread.start();
    }

    @Override
    public void onDuplexStop() {
        isDuplexActive.set(false);
        stopDiscoveryServer();
        duplexNotifHandler.removeCallbacks(duplexNotifRunnable);

        closeQuietly(duplexServerSocket);
        duplexServerSocket = null;
        closeQuietly(duplexSocket);
        duplexSocket = null;
        cleanupRecorder(duplexRecorder);
        duplexRecorder = null;
        cleanupTrack(duplexTrack);
        duplexTrack = null;
        if (duplexAcceptThread != null) duplexAcceptThread.interrupt();
        duplexAcceptThread = null;
        duplexSendThread = null;
        duplexRecvThread = null;
        if (duplexMetadataThread != null) duplexMetadataThread.interrupt();
        duplexMetadataThread = null;
        duplexRemoteTitle = null;
        duplexRemoteArtist = null;
        duplexRemoteArt = null;
        duplexRemoteDurationMs = 0;
        duplexRemotePositionMs = 0;
        if (duplexProjection != null) {
            try { duplexProjection.stop(); } catch (Exception ignored) {}
            duplexProjection = null;
        }
        duplexPendingMode = null;
        duplexPendingIp = null;
        closeQuietly(duplexChatSocket);
        duplexChatSocket = null;
        closeQuietly(duplexChatServerSocket);
        duplexChatServerSocket = null;
        duplexChatOut = null;
        duplexChatIn = null;
        if (duplexChatThread != null) duplexChatThread.interrupt();
        duplexChatThread = null;
        stopForegroundService();
        mainHandler.post(() -> {
            if (duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.setActive(false);
                duplexFragment.updateStatus("Stopped \u2014 Ready to host or join");
            }
        });
    }

    /** Immediately update notification with current media metadata for duplex */
    private void updateDuplexNotificationNow() {
        String title, subtitle;
        Bitmap art = null;
        long durMs = 0, posMs = 0;

        // ONLY show remote peer's metadata, never local media
        if (duplexRemoteTitle != null && !duplexRemoteTitle.isEmpty()
                && !"Unknown".equals(duplexRemoteTitle)) {
            title = duplexRemoteTitle;
            subtitle = duplexRemoteArtist != null ? duplexRemoteArtist : "Full Duplex";
            art = duplexRemoteArt;
            durMs = duplexRemoteDurationMs;
            posMs = duplexRemotePositionMs;
        } else {
            title = "Full Duplex Stream";
            subtitle = "48.0 kHz · Stereo · 16-bit";
            art = null;
            durMs = 0;
            posMs = 0;
        }

        AudioCaptureService.updateNotification(
                this, title, subtitle, art, isPaused.get(), durMs, posMs);

        final String fT = title, fS = subtitle;
        final Bitmap fA = art;
        runOnUiThread(() -> {
            if (duplexFragment != null && duplexFragment.isAdded()) {
                duplexFragment.updateNowPlaying(fT, fS, fA);
            }
        });
    }

    /** Sends a transport control byte to the duplex peer over the chat channel */
    private void sendDuplexControlMessage(byte ctrl) {
        DataOutputStream out = duplexChatOut;
        if (out == null) return;
        new Thread(() -> {
            try {
                synchronized (out) {
                    out.writeByte(ctrl);
                    out.flush();
                }
            } catch (IOException e) {
                Log.w(TAG, "Duplex control send error: " + e.getMessage());
            }
        }, "DuplexCtrlSend").start();
    }

    /** Periodically broadcasts local media metadata to the duplex peer */
    private void startDuplexMetadataBroadcast() {
        duplexMetadataThread = new Thread(() -> {
            try {
                // FIX_PLAN #15: poll every 3 s, but write only on change or every 15 s.
                // Art is encoded once per change (≤512 px, q80) and re-sent with every
                // message, because artLen 0 clears the peer's art.
                String lastKey = null;
                long lastSentMs = 0;
                byte[] cachedArt = null;
                while (isDuplexActive.get()) {
                    DataOutputStream out = duplexChatOut;
                    if (out == null || !isDuplexActive.get()) break;

                    MediaMetadataHelper.NowPlayingInfo media =
                            MediaMetadataHelper.getCurrentMedia(
                                    MainActivity.this);
                    if (media != null) {
                        String title = media.title != null
                                ? media.title : "SoundShare Stream";
                        String artist = media.artist != null
                                ? media.artist : "";
                        String album = media.album != null
                                ? media.album : "";
                        long durationMs = media.durationMs;
                        long positionMs = media.positionMs;

                        Bitmap srcArt = media.albumArt;
                        String key = title + '\u0000' + artist + '\u0000' + album + '\u0000'
                                + (srcArt == null ? "-" : srcArt.getWidth() + "x" + srcArt.getHeight());
                        long now = android.os.SystemClock.elapsedRealtime();
                        boolean changed = !key.equals(lastKey);
                        if (!changed && now - lastSentMs < META_REFRESH_MS) {
                            Thread.sleep(3000);
                            continue;
                        }
                        if (changed) cachedArt = encodeMetadataArt(srcArt);
                        byte[] artBytes = cachedArt;

                        try {
                            writeMetadataMessage(out, title, artist, album,
                                    artBytes, durationMs, positionMs);
                            lastKey = key;
                            lastSentMs = now;
                        } catch (IOException e) {
                            Log.w(TAG, "Duplex metadata error: "
                                    + e.getMessage());
                            break;
                        }
                    }

                    Thread.sleep(3000);
                }
            } catch (InterruptedException ignored) {
            }
        }, "DuplexMetaBroadcast");
        duplexMetadataThread.start();
    }

    private void startDuplexChat(boolean isHost, String peerIp) {
        duplexChatThread = new Thread(() -> {
            try {
                Socket chatSock;
                if (isHost) {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(DUPLEX_CHAT_PORT));
                    duplexChatServerSocket = ss;
                    chatSock = ss.accept();
                } else {
                    // Wait for host to start chat server
                    Thread.sleep(1500);
                    chatSock = null;
                    for (int retry = 0; retry < 3 && isDuplexActive.get(); retry++) {
                        try {
                            chatSock = new Socket();
                            bindToLan(chatSock, peerIp);
                            chatSock.connect(new InetSocketAddress(peerIp, DUPLEX_CHAT_PORT),
                                    SettingsFragment.getConnTimeoutMs(this));
                            break;
                        } catch (Exception e) {
                            closeQuietly(chatSock);
                            chatSock = null;
                            if (retry < 2) Thread.sleep(1000);
                        }
                    }
                    if (chatSock == null) throw new IOException("Chat connect failed after retries");
                }
                chatSock.setTcpNoDelay(true);
                duplexChatSocket = chatSock;
                duplexChatOut = new DataOutputStream(
                        new java.io.BufferedOutputStream(chatSock.getOutputStream()));
                duplexChatIn = new DataInputStream(
                        new java.io.BufferedInputStream(chatSock.getInputStream()));

                mainHandler.post(() -> {
                    if (duplexFragment != null && duplexFragment.isAdded()) {
                        duplexFragment.updateChatStatus("Connected", R.color.accent_green);
                        duplexFragment.addSystemMessage("Chat connected");
                    }
                });

                startDuplexMetadataBroadcast();

                while (isDuplexActive.get() && !chatSock.isClosed()) {
                    byte cmd = duplexChatIn.readByte();
                    if (cmd == CHAT_MSG) {
                        String[] parts = readChatMessage(duplexChatIn);
                        mainHandler.post(() -> {
                            if (duplexFragment != null && duplexFragment.isAdded()) {
                                duplexFragment.addChatMessage(
                                    new ChatMessage(parts[0], parts[1],
                                            ChatMessage.TYPE_RECEIVED));
                            }
                        });
                    } else if (cmd == CHAT_METADATA) {
                        String metaTitle = readUtfField(duplexChatIn);
                        String artist = readUtfField(duplexChatIn);
                        String album = readUtfField(duplexChatIn);
                        long durMs = duplexChatIn.readLong();
                        long posMs = duplexChatIn.readLong();
                        int artLen = duplexChatIn.readInt();
                        Bitmap art = null;
                        if (artLen < 0) throw new IOException("Bad art length");
                        if (artLen > 0 && artLen < META_ART_MAX_BYTES) {
                            byte[] artBytes = new byte[artLen];
                            duplexChatIn.readFully(artBytes);
                            art = android.graphics.BitmapFactory.decodeByteArray(
                                    artBytes, 0, artLen);
                        } else if (artLen > 0) {
                            // Oversized art: consume it so the chat stream stays in sync (FIX_PLAN #15).
                            skipFully(duplexChatIn, artLen);
                        }
                        String metaSub = artist;
                        if (album != null && !album.isEmpty()) {
                            metaSub = metaSub.isEmpty() ? album : metaSub + " — " + album;
                        }
                        duplexRemoteTitle = metaTitle;
                        duplexRemoteArtist = metaSub.isEmpty() ? "Full Duplex" : metaSub;
                        duplexRemoteArt = art;
                        duplexRemoteDurationMs = durMs;
                        duplexRemotePositionMs = posMs;

                        final String fT = metaTitle;
                        final String fS = duplexRemoteArtist;
                        final Bitmap fA = art;
                        mainHandler.post(() -> {
                            if (duplexFragment != null && duplexFragment.isAdded()) {
                                duplexFragment.updateNowPlaying(fT, fS, fA);
                            }
                            AudioCaptureService.updateNotification(
                                    MainActivity.this, fT, fS, fA,
                                    isPaused.get(), durMs, posMs);
                        });
                    } else if (cmd == CTRL_PAUSE) {
                        mainHandler.post(() -> {
                            isPaused.set(true);
                            updatePlayPauseButton(true);
                            AudioTrack dt = duplexTrack;
                            if (dt != null) { try { dt.pause(); } catch (Exception ignored) {} }
                            // No media-key fallback in duplex: it would loop back into our own
                            // session (FIX_PLAN #9, request from M4).
                            MediaMetadataHelper.sendPause(MainActivity.this, false);
                            updateDuplexNotificationNow();
                        });
                    } else if (cmd == CTRL_RESUME) {
                        mainHandler.post(() -> {
                            isPaused.set(false);
                            updatePlayPauseButton(false);
                            AudioTrack dt = duplexTrack;
                            if (dt != null) {
                                try {
                                    dt.flush();
                                    dt.play();
                                } catch (Exception ignored) {}
                            }
                            MediaMetadataHelper.sendPlay(MainActivity.this, false);   // FIX_PLAN #9
                            updateDuplexNotificationNow();
                        });
                    } else if (cmd == CTRL_SKIP_FWD) {
                        MediaMetadataHelper.sendSkipNext(MainActivity.this);
                    } else if (cmd == CTRL_SKIP_BACK) {
                        MediaMetadataHelper.sendSkipPrevious(MainActivity.this);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (isDuplexActive.get()) {
                    mainHandler.post(() -> {
                        if (duplexFragment != null && duplexFragment.isAdded()) {
                            duplexFragment.updateChatStatus("Disconnected",
                                    R.color.accent_red);
                        }
                    });
                }
            } finally {
                closeQuietly(duplexChatSocket);
                duplexChatSocket = null;
                closeQuietly(duplexChatServerSocket);
                duplexChatServerSocket = null;
            }
        }, "DuplexChat");
        duplexChatThread.start();
    }

    // ==========================================================================
    // ==================== AUTO DISCOVERY FOR ALL MODES ========================
    // ==========================================================================

    @Override
    public void onScanForStreamHost() {
        if (receiveFragment != null) receiveFragment.setStatus("Scanning for stream host on network...");
        scanForHost("SS_DISCOVER_STREAM", "SS_HOST_STREAM:", hostIp -> {
            mainHandler.post(() -> {
                if (receiveFragment != null && receiveFragment.isAdded()) {
                    receiveFragment.setIpInput(hostIp);
                    receiveFragment.setStatus("Found stream host at " + hostIp);
                }
            });
        }, () -> {
            mainHandler.post(() -> {
                if (receiveFragment != null && receiveFragment.isAdded()) {
                    receiveFragment.setStatus("No stream host found. Enter IP manually.");
                }
            });
        });
    }

    @Override
    public void onScanForDuplexHost() {
        if (duplexFragment != null) duplexFragment.updateStatus("Scanning for duplex host on network...");
        scanForHost("SS_DISCOVER_DUPLEX", "SS_HOST_DUPLEX:", hostIp -> {
            mainHandler.post(() -> {
                if (duplexFragment != null && duplexFragment.isAdded()) {
                    duplexFragment.setIpInput(hostIp);
                    duplexFragment.updateStatus("Found duplex host at " + hostIp);
                }
            });
        }, () -> {
            mainHandler.post(() -> {
                if (duplexFragment != null && duplexFragment.isAdded()) {
                    duplexFragment.updateStatus("No duplex host found. Enter IP manually.");
                }
            });
        });
    }

    @Override
    public void onScanForHost() {
        if (fileShareFragment != null) fileShareFragment.setStatus("Scanning for file share host on network...");
        scanForHost("SS_DISCOVER_FILE", "SS_HOST_FILE:", hostIp -> {
            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.setPeerIp(hostIp);
                    fileShareFragment.setStatus("Found host at " + hostIp);
                }
            });
        }, () -> {
            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.setStatus("No host found. Enter IP manually.");
                }
            });
        });
    }

    private void scanForHost(String queryMsg, String expectedPrefix,
                             java.util.function.Consumer<String> onFound, Runnable onNotFound) {
        // FIX_PLAN #4: 4 rounds to 255.255.255.255 + every up IPv4 subnet broadcast, socket
        // bound to the LAN network, replies drained (non-matching ones ignored) until ~3 s.
        // Wire format unchanged: same query strings, port 5007, host = reply source address.
        new Thread(() -> {
            List<DatagramSocket> socks = new ArrayList<>();
            try {
                byte[] reqBytes = queryMsg.getBytes(StandardCharsets.UTF_8);
                List<InetAddress> lanTargets = new ArrayList<>();
                List<InetAddress> otherTargets = new ArrayList<>();
                lanTargets.add(InetAddress.getByName("255.255.255.255"));
                collectSubnetBroadcasts(lanTargets, otherTargets);

                DatagramSocket primary = new DatagramSocket();
                socks.add(primary);
                primary.setBroadcast(true);
                bindToLan(primary);
                DatagramSocket secondary = null;
                if (lanNetwork != null && !otherTargets.isEmpty()) {
                    // e.g. hotspot (ap0) subnet while also on Wi-Fi: only reachable unbound.
                    secondary = new DatagramSocket();
                    socks.add(secondary);
                    secondary.setBroadcast(true);
                } else {
                    lanTargets.addAll(otherTargets);
                }
                for (DatagramSocket s : socks) s.setSoTimeout(100);
                PipelineLog.i("DISCOVERY", queryMsg + ": " + (lanTargets.size() + otherTargets.size())
                        + " target(s), bound=" + (lanNetwork != null));

                byte[] recvBuf = new byte[256];
                long start = android.os.SystemClock.elapsedRealtime();
                long deadline = start + 3000;
                int rounds = 0;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (rounds < 4 && android.os.SystemClock.elapsedRealtime() >= start + rounds * 700L) {
                        sendDiscoveryProbes(primary, reqBytes, lanTargets);
                        if (secondary != null) sendDiscoveryProbes(secondary, reqBytes, otherTargets);
                        rounds++;
                    }
                    for (DatagramSocket s : socks) {
                        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
                        try {
                            s.receive(recvPacket);
                        } catch (SocketTimeoutException t) {
                            continue;
                        }
                        String resp = new String(recvPacket.getData(), 0,
                                recvPacket.getLength(), StandardCharsets.UTF_8);
                        if (resp.startsWith(expectedPrefix)) {
                            String hostIp = recvPacket.getAddress().getHostAddress();
                            PipelineLog.i("DISCOVERY", "Found host " + PipelineLog.redactIps(hostIp)
                                    + " after " + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
                            if (onFound != null) onFound.accept(hostIp);
                            return;
                        }
                        // Stray/other-mode reply: ignore and keep waiting.
                    }
                }
                PipelineLog.w("DISCOVERY", queryMsg + ": no host found after " + rounds + " round(s)");
                if (onNotFound != null) onNotFound.run();
            } catch (Exception e) {
                PipelineLog.e("DISCOVERY", "Scan error", e);
                if (onNotFound != null) onNotFound.run();
            } finally {
                for (DatagramSocket s : socks) {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        }, "UniversalScanner").start();
    }

    private void sendDiscoveryProbes(DatagramSocket s, byte[] req, List<InetAddress> targets) {
        for (InetAddress t : targets) {
            try {
                s.send(new DatagramPacket(req, req.length, t, FILE_DISCOVERY_PORT));
            } catch (IOException e) {
                PipelineLog.d("DISCOVERY", "send to " + PipelineLog.redactIps(t.getHostAddress())
                        + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * Subnet broadcast addresses of up IPv4 interfaces that NetUtils considers LAN-capable
     * (cellular/VPN/CLAT excluded). The tracked LAN interface's broadcast goes to
     * {@code lanOut}, every other one (e.g. hotspot ap0/swlan0) to {@code otherOut}.
     */
    private void collectSubnetBroadcasts(List<InetAddress> lanOut, List<InetAddress> otherOut) {
        String lanIface = null;
        try {
            Network lan = lanNetwork;
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (lan != null && cm != null) {
                LinkProperties lp = cm.getLinkProperties(lan);
                if (lp != null) lanIface = lp.getInterfaceName();
            }
        } catch (Exception ignored) {
        }
        try {
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    InetAddress bc = ia.getBroadcast();
                    if (!(a instanceof Inet4Address) || bc == null) continue;
                    List<NetUtils.Candidate> one = Collections.singletonList(
                            new NetUtils.Candidate(ni.getName(), a.getHostAddress()));
                    if (NetUtils.rankIpv4Candidates(one).isEmpty()) continue;   // not a LAN iface
                    List<InetAddress> dst = (lanIface == null || lanIface.equals(ni.getName()))
                            ? lanOut : otherOut;
                    if (!dst.contains(bc)) dst.add(bc);
                }
            }
        } catch (Exception e) {
            PipelineLog.w("DISCOVERY", "Interface enumeration failed: " + e);
        }
    }

    private void startUniversalDiscoveryServer(String mode) {
        activeDiscoveryHostMode = mode;
        acquireDiscoveryMulticastLock();   // FIX_PLAN #4: otherwise Wi-Fi firmware drops the broadcasts
        // stopDiscoveryServer() nulls discoveryThread, so a just-stopped (still exiting)
        // thread no longer prevents a restart.
        Thread existing = discoveryThread;
        if (existing != null && existing.isAlive()) return;

        discoveryThread = new Thread(() -> {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(FILE_DISCOVERY_PORT));
                if (discoveryThread != Thread.currentThread()) return;   // superseded/stopped
                discoverySocket = ds;
                PipelineLog.i("DISCOVERY", "Discovery server listening on " + FILE_DISCOVERY_PORT);
                byte[] buffer = new byte[256];
                while (activeDiscoveryHostMode != null && discoveryThread == Thread.currentThread()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    ds.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    String hostMode = activeDiscoveryHostMode;
                    if (hostMode == null || !msg.startsWith("SS_DISCOVER_")) continue;
                    // Payload IP is informational (parsers use the packet source); use the
                    // LAN-aware address instead of a possibly stale "Loading..." cache.
                    String myIp = getDeviceIpAddress();
                    String replyMsg = null;

                    if ("SS_DISCOVER_STREAM".equals(msg) && "STREAM".equals(hostMode)) {
                        replyMsg = "SS_HOST_STREAM:" + myIp;
                    } else if ("SS_DISCOVER_DUPLEX".equals(msg) && "DUPLEX".equals(hostMode)) {
                        replyMsg = "SS_HOST_DUPLEX:" + myIp;
                    } else if ("SS_DISCOVER_FILE".equals(msg) && "FILE".equals(hostMode)) {
                        replyMsg = "SS_HOST_FILE:" + myIp;
                    } else if ("SS_DISCOVER_ALL".equals(msg)) {
                        replyMsg = "SS_HOST_" + hostMode + ":" + myIp;
                    }

                    if (replyMsg != null) {
                        byte[] respBytes = replyMsg.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket reply = new DatagramPacket(
                                respBytes, respBytes.length,
                                packet.getAddress(), packet.getPort());
                        ds.send(reply);
                        PipelineLog.d("DISCOVERY", "Answered " + msg + " from "
                                + PipelineLog.redactIps(packet.getAddress().getHostAddress()));
                    }
                }
            } catch (Exception e) {
                if (activeDiscoveryHostMode != null && discoveryThread == Thread.currentThread()) {
                    PipelineLog.w("DISCOVERY", "Discovery server ended: " + e);
                } else {
                    Log.d(TAG, "Discovery server ended: " + e.getMessage());
                }
            } finally {
                // FIX_PLAN #12: close OUR socket; never null a newer server's socket.
                if (ds != null) {
                    try { ds.close(); } catch (Exception ignored) {}
                }
                if (discoverySocket == ds) discoverySocket = null;
            }
        }, "UniversalDiscoveryServer");
        discoveryThread.start();
    }

    private void stopDiscoveryServer() {
        activeDiscoveryHostMode = null;
        discoveryThread = null;
        DatagramSocket ds = discoverySocket;
        discoverySocket = null;
        if (ds != null) {
            try { ds.close(); } catch (Exception ignored) {}
        }
        releaseDiscoveryMulticastLock();
    }

    private void acquireDiscoveryMulticastLock() {
        try {
            if (discoveryMulticastLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                discoveryMulticastLock = wm.createMulticastLock("soundshare:discovery");
                discoveryMulticastLock.setReferenceCounted(false);
            }
            if (!discoveryMulticastLock.isHeld()) discoveryMulticastLock.acquire();
        } catch (Exception e) {
            // SecurityException if CHANGE_WIFI_MULTICAST_STATE is missing: discovery still
            // works on chipsets that don't filter broadcasts.
            PipelineLog.w("DISCOVERY", "MulticastLock acquire failed: " + e);
        }
    }

    private void releaseDiscoveryMulticastLock() {
        try {
            WifiManager.MulticastLock l = discoveryMulticastLock;
            if (l != null && l.isHeld()) l.release();
        } catch (Exception e) {
            PipelineLog.w("DISCOVERY", "MulticastLock release failed: " + e);
        }
    }

    // ==========================================================================
    // ==================== FILE SHARE & SYNC PLAY ==============================
    // ==========================================================================

    static class FileSharePeer {
        final Socket socket;
        final DataInputStream dis;
        final DataOutputStream dos;
        final String ip;
        volatile long clockOffset = 0;
        /**
         * Set when the peer sends SYNC_INIT_REQ (clock calibration finished). Until then host
         * broadcasts skip it, so a pulse/queue frame can't land inside the joiner's
         * SYNC_CLOCK_RSP reads (FIX_PLAN #26). INIT sends the full queue snapshot anyway.
         */
        volatile boolean initialized = false;

        FileSharePeer(Socket socket, DataInputStream dis, DataOutputStream dos, String ip) {
            this.socket = socket;
            this.dis = dis;
            this.dos = dos;
            this.ip = ip;
        }

        void close() {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private final List<FileSharePeer> connectedFileSharePeers = new CopyOnWriteArrayList<>();

    @Override
    public void onPickAndAddSongs() {
        multipleFilePickerLauncher.launch("audio/*");
    }

    @Override
    public void onFileShareHost() {
        String other = activeOtherMode("File Share");   // FIX_PLAN #23 (M5 helper)
        if (other != null) {
            Toast.makeText(this, modeBusyMessage(other), Toast.LENGTH_SHORT).show();
            return;
        }
        isFileShareHost = true;
        isFileShareActive.set(true);
        connectedFileSharePeers.clear();
        fileShareFragment.showConnectedUI();
        fileShareFragment.setStatus("Host waiting for receivers on port " + SYNC_CTRL_PORT + "...");
        startUniversalDiscoveryServer("FILE");
        startSyncControlServer();
        startFileShareService();               // user tap → foreground start allowed (FIX_PLAN #14)
        BatteryOptHelper.maybeShowHint(this);  // FIX_PLAN #31
    }

    @Override
    public void onFileShareJoin(String ip) {
        String other = activeOtherMode("File Share");   // FIX_PLAN #23 (M5 helper)
        if (other != null) {
            Toast.makeText(this, modeBusyMessage(other), Toast.LENGTH_SHORT).show();
            return;
        }
        isFileShareHost = false;
        isFileShareActive.set(true);
        connectedPeerIp = ip;
        fileShareFragment.showConnectedUI();
        fileShareFragment.setStatus("Connecting to host " + ip + "...");
        startSyncControlClient(ip);
        startFileShareService();               // user tap → foreground start allowed (FIX_PLAN #14)
        BatteryOptHelper.maybeShowHint(this);  // FIX_PLAN #31
    }

    @Override
    public void onFileShareStop() {
        stopFileShare();
    }

    @Override
    public void onStereoModeChanged(int mode) {
        currentStereoMode = mode;
        applyStereoVolume();
    }

    private void applyStereoVolume() {
        if (syncMediaPlayer == null) return;
        try {
            if (currentStereoMode == 0) {
                syncMediaPlayer.setVolume(1.0f, 1.0f); // Both Left & Right
            } else if (currentStereoMode == 1) {
                syncMediaPlayer.setVolume(1.0f, 0.0f); // Left Channel Speaker only
            } else if (currentStereoMode == 2) {
                syncMediaPlayer.setVolume(0.0f, 1.0f); // Right Channel Speaker only
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSyncPlayPause() {
        if (syncMediaPlayer == null) {
            if (!fileShareQueue.isEmpty()) {
                int targetIndex = currentPlayingQueueIndex >= 0 ? currentPlayingQueueIndex : 0;
                changeAndPlayTrack(targetIndex);
            }
            return;
        }

        boolean playing;
        try {
            playing = syncMediaPlayer.isPlaying();
        } catch (Exception e) {
            playing = isSyncPlaying.get();
        }

        if (playing) {
            int pos = 0;
            try { pos = syncMediaPlayer.getCurrentPosition(); } catch (Exception ignored) {}
            long pauseAt;
            if (isFileShareHost) {
                pauseAt = System.currentTimeMillis() + 60;
                broadcastSyncCommand(SYNC_PAUSE_CMD, pos, pauseAt, null);
                pauseLocalAt(pos, pauseAt);
            } else {
                long localPauseAt = System.currentTimeMillis() + 60;
                pauseAt = localPauseAt + clockOffset;
                sendSyncCommand(SYNC_PAUSE_CMD, pos, pauseAt);
                pauseLocalAt(pos, localPauseAt);
            }
        } else {
            int pos = 0;
            try { pos = syncMediaPlayer.getCurrentPosition(); } catch (Exception ignored) {}
            long playAt;
            if (isFileShareHost) {
                playAt = System.currentTimeMillis() + 450;
                broadcastSyncCommand(SYNC_PLAY, pos, playAt, null);
                scheduleLocalPlay(pos, playAt);
            } else {
                long localPlayAt = System.currentTimeMillis() + 450;
                playAt = localPlayAt + clockOffset;
                sendSyncCommand(SYNC_PLAY, pos, playAt);
                scheduleLocalPlay(pos, localPlayAt);
            }
        }
    }

    @Override
    public void onSyncSeek(int positionMs) {
        if (syncMediaPlayer == null) return;
        boolean wasPlaying = false;
        try {
            wasPlaying = syncMediaPlayer.isPlaying() || isSyncPlaying.get();
        } catch (Exception ignored) {
            wasPlaying = isSyncPlaying.get();
        }

        if (wasPlaying) {
            long playAt;
            if (isFileShareHost) {
                playAt = System.currentTimeMillis() + 450;
                broadcastSyncCommand(SYNC_SEEK, positionMs, playAt, null);
                scheduleLocalPlay(positionMs, playAt);
            } else {
                long localPlayAt = System.currentTimeMillis() + 450;
                playAt = localPlayAt + clockOffset;
                sendSyncCommand(SYNC_SEEK, positionMs, playAt);
                scheduleLocalPlay(positionMs, localPlayAt);
            }
        } else {
            try { syncMediaPlayer.seekTo(positionMs); } catch (Exception ignored) {}
            long pauseAt;
            if (isFileShareHost) {
                pauseAt = System.currentTimeMillis() + 40;
                broadcastSyncCommand(SYNC_PAUSE_CMD, positionMs, pauseAt, null);
                pauseLocalAt(positionMs, pauseAt);
            } else {
                long localPauseAt = System.currentTimeMillis() + 40;
                pauseAt = localPauseAt + clockOffset;
                sendSyncCommand(SYNC_PAUSE_CMD, positionMs, pauseAt);
                pauseLocalAt(positionMs, localPauseAt);
            }
        }
    }

    @Override
    public void onSyncSkipNext() {
        if (fileShareQueue.isEmpty()) return;
        int nextIndex = currentPlayingQueueIndex + 1;
        if (nextIndex >= fileShareQueue.size()) nextIndex = 0;
        changeAndPlayTrack(nextIndex);
    }

    @Override
    public void onSyncSkipPrev() {
        if (fileShareQueue.isEmpty()) return;
        if (syncMediaPlayer != null && syncMediaPlayer.getCurrentPosition() > 3000) {
            onSyncSeek(0);
            return;
        }
        int prevIndex = currentPlayingQueueIndex - 1;
        if (prevIndex < 0) prevIndex = fileShareQueue.size() - 1;
        changeAndPlayTrack(prevIndex);
    }

    @Override
    public void onTrackSelected(int position, FileQueueItem item) {
        changeAndPlayTrack(position);
    }

    @Override
    public void onTrackRemoved(int position, FileQueueItem item) {
        if (position >= 0 && position < fileShareQueue.size()) {
            fileShareQueue.remove(position);
            fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);

            if (isFileShareHost) {
                broadcastQueueRemove(item.getId(), null);
            } else if (syncControlOut != null) {
                new Thread(() -> {
                    try {
                        DataOutputStream out = syncControlOut;
                        if (out != null) {
                            synchronized (out) {
                                out.writeByte(SYNC_QUEUE_REMOVE);
                                byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                                out.writeShort(idBytes.length);
                                out.write(idBytes);
                                out.flush();
                            }
                        }
                    } catch (Exception ignored) {}
                }, "SyncQueueRemove").start();
            }

            if (currentPlayingQueueIndex == position) {
                if (!fileShareQueue.isEmpty()) {
                    int nextIdx = Math.min(position, fileShareQueue.size() - 1);
                    changeAndPlayTrack(nextIdx);
                } else {
                    stopPlayback();
                }
            } else if (currentPlayingQueueIndex > position) {
                currentPlayingQueueIndex--;
                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
            }
        }
    }

    @Override
    public void onTrackMoved(int fromPosition, int toPosition) {
        if (fromPosition < 0 || fromPosition >= fileShareQueue.size() ||
            toPosition < 0 || toPosition >= fileShareQueue.size()) return;

        FileQueueItem movedItem = fileShareQueue.remove(fromPosition);
        fileShareQueue.add(toPosition, movedItem);

        if (currentPlayingQueueIndex == fromPosition) {
            currentPlayingQueueIndex = toPosition;
        } else if (fromPosition < currentPlayingQueueIndex && toPosition >= currentPlayingQueueIndex) {
            currentPlayingQueueIndex--;
        } else if (fromPosition > currentPlayingQueueIndex && toPosition <= currentPlayingQueueIndex) {
            currentPlayingQueueIndex++;
        }

        fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);

        if (isFileShareHost) {
            broadcastQueueReorder(fromPosition, toPosition, null);
        } else if (syncControlOut != null) {
            new Thread(() -> {
                try {
                    DataOutputStream out = syncControlOut;
                    if (out != null) {
                        synchronized (out) {
                            out.writeByte(SYNC_QUEUE_REORDER);
                            out.writeInt(fromPosition);
                            out.writeInt(toPosition);
                            out.flush();
                        }
                    }
                } catch (Exception ignored) {}
            }, "SyncQueueReorder").start();
        }
    }

    @Override
    public void onSaveCurrentTrack() {
        if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < fileShareQueue.size()) {
            FileQueueItem item = fileShareQueue.get(currentPlayingQueueIndex);
            saveAudioTrackToLocal(item);
        } else {
            Toast.makeText(this, "No track is currently playing", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTrackDownload(int position, FileQueueItem item) {
        saveAudioTrackToLocal(item);
    }

    private void saveAudioTrackToLocal(FileQueueItem item) {
        if (item == null) return;
        new Thread(() -> {
            try {
                InputStream inStream = null;
                if (item.getLocalUri() != null) {
                    inStream = getContentResolver().openInputStream(item.getLocalUri());
                } else if (item.getLocalFilePath() != null) {
                    inStream = new FileInputStream(item.getLocalFilePath());
                }

                if (inStream == null) {
                    mainHandler.post(() -> Toast.makeText(this, "Audio file is not ready or downloading", Toast.LENGTH_SHORT).show());
                    return;
                }

                final String finalFileName = (item.getFileName() != null && !item.getFileName().isEmpty())
                        ? item.getFileName() : ("track_" + System.currentTimeMillis() + ".mp3");
                final String finalMime = (item.getMimeType() != null && !item.getMimeType().isEmpty())
                        ? item.getMimeType() : "audio/*";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName);
                    values.put(MediaStore.Audio.Media.TITLE, LyricsHelper.sanitizeTitle(finalFileName));
                    values.put(MediaStore.Audio.Media.ARTIST, item.getArtist());
                    values.put(MediaStore.Audio.Media.ALBUM, item.getAlbum());
                    values.put(MediaStore.Audio.Media.MIME_TYPE, finalMime);
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SoundShare");
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                    Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri savedUri = getContentResolver().insert(collection, values);
                    if (savedUri != null) {
                        try (OutputStream outStream = getContentResolver().openOutputStream(savedUri)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = inStream.read(buf)) != -1) {
                                outStream.write(buf, 0, len);
                            }
                            outStream.flush();
                        }
                        values.clear();
                        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                        getContentResolver().update(savedUri, values, null, null);
                    }
                } else {
                    File musicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "SoundShare");
                    if (!musicDir.exists()) musicDir.mkdirs();
                    File destFile = new File(musicDir, finalFileName);
                    try (OutputStream outStream = new FileOutputStream(destFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = inStream.read(buf)) != -1) {
                            outStream.write(buf, 0, len);
                        }
                        outStream.flush();
                    }
                    MediaScannerConnection.scanFile(this, new String[]{destFile.getAbsolutePath()}, null, null);
                }

                inStream.close();
                mainHandler.post(() -> {
                    Toast.makeText(this, "Saved to Music/SoundShare/" + finalFileName, Toast.LENGTH_LONG).show();
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setStatus("Saved: " + finalFileName);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "saveAudioTrackToLocal error: " + e.getMessage(), e);
                mainHandler.post(() -> Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "SaveAudioTrackThread").start();
    }

    private void changeAndPlayTrack(int index) {
        if (index < 0 || index >= fileShareQueue.size()) return;
        FileQueueItem item = fileShareQueue.get(index);

        // Check if track is available locally on this device
        if (item.getLocalUri() == null && item.getLocalFilePath() == null) {
            pendingPlayIndex = index;
            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.setStatus("Downloading " + item.getFileName() + "... Will play when ready");
                }
            });
            return;
        }

        // If on Host with connected peers, ensure the file is transferred before triggering play
        if (isFileShareHost && !connectedFileSharePeers.isEmpty() && !item.isReady()) {
            pendingPlayIndex = index;
            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.setStatus("Uploading " + item.getFileName() + "... Will play when transfer completes");
                }
            });
            return;
        }

        pendingPlayIndex = -1;
        long playAt;
        if (isFileShareHost) {
            playAt = System.currentTimeMillis() + 750;
            broadcastTrackChange(index, playAt, null);
            playQueueTrack(index, playAt);
        } else {
            long localPlayAt = System.currentTimeMillis() + 750;
            playAt = localPlayAt + clockOffset;
            sendTrackChange(index, playAt);
            playQueueTrack(index, localPlayAt);
        }
    }

    private void sendTrackChange(int index, long playAt) {
        new Thread(() -> {
            try {
                DataOutputStream out = syncControlOut;
                if (out != null) {
                    synchronized (out) {
                        out.writeByte(SYNC_TRACK_CHANGE);
                        out.writeInt(index);
                        out.writeLong(playAt);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {}
        }, "SyncTrackChange").start();
    }

    // ---------- MULTI-FILE PICKER HANDLER ----------

    private void handlePickedMultipleFiles(List<android.net.Uri> uris) {
        // Size probing may copy the file to cache (FIX_PLAN #27): keep that off the UI thread.
        // Everything below already posts its UI work to mainHandler, and changeAndPlayTrack
        // is also invoked from network threads elsewhere.
        final List<android.net.Uri> picked = new ArrayList<>(uris);
        new Thread(() -> handlePickedMultipleFilesBlocking(picked), "PickedFiles").start();
    }

    private void handlePickedMultipleFilesBlocking(List<android.net.Uri> uris) {
        for (android.net.Uri uri : uris) {
            String name = "Audio Track";
            long size = 0;
            android.database.Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (nameIdx >= 0 && cursor.getString(nameIdx) != null) name = cursor.getString(nameIdx);
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                }
            } catch (Exception e) {
                Log.w(TAG, "Picked file query failed: " + e.getMessage());
            } finally {
                if (cursor != null) cursor.close();
            }

            // FIX_PLAN #27: OpenableColumns.SIZE is 0/unknown/stale for many providers, and it is
            // the length announced on the wire. Use the real length; if the provider can't tell,
            // copy to cache and send that file instead.
            String trackIdEarly = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 10000);
            android.net.Uri sourceUri = uri;
            String sourcePath = null;
            long realSize = queryAssetLength(uri);
            if (realSize > 0) {
                size = realSize;
            } else {
                File copy = copyUriToCache(uri, "fileshare_src_" + NetUtils.sanitizeFileName(trackIdEarly)
                        + "_" + NetUtils.sanitizeFileName(name));
                if (copy != null && copy.length() > 0) {
                    sourceUri = null;
                    sourcePath = copy.getAbsolutePath();
                    size = copy.length();
                }
            }

            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null) mimeType = "audio/*";

            int durMs = 0;
            String durationStr = "";
            String artist = "Unknown Artist";
            String album = "";
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(this, uri);
                String art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (art != null && !art.isEmpty()) artist = art;
                String alb = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                if (alb != null && !alb.isEmpty()) album = alb;
                String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durStr != null) {
                    durMs = Integer.parseInt(durStr);
                    long min = (durMs / 1000) / 60;
                    long sec = (durMs / 1000) % 60;
                    durationStr = String.format(Locale.US, "%d:%02d", min, sec);
                }
                mmr.release();
            } catch (Exception ignored) {}

            String formatDesc = getAudioFormatDescription(name, mimeType);
            String trackId = trackIdEarly;

            FileQueueItem queueItem = new FileQueueItem(
                    trackId, name, artist, album, size, mimeType, durMs, durationStr, formatDesc,
                    sourceUri, sourcePath, true);

            fileShareQueue.add(queueItem);

            if (isFileShareHost) {
                broadcastQueueAdd(queueItem, null);
                transferTrackToAllPeers(queueItem);
            } else if (syncControlOut != null) {
                sendQueueAddOverStream(syncControlOut, queueItem);
                transferTrackToPeer(queueItem);
            }
        }

        mainHandler.post(() -> {
            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                fileShareFragment.setStatus("Added " + uris.size() + " track(s) to queue");
            }
        });

        if (currentPlayingQueueIndex == -1 && !fileShareQueue.isEmpty()) {
            if (isFileShareHost && connectedFileSharePeers.isEmpty()) {
                changeAndPlayTrack(0);
            } else {
                pendingPlayIndex = 0;
                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setStatus("Uploading audio... Playback will start when ready");
                    }
                });
            }
        }
    }

    /** Real byte length of a content Uri, or -1 when the provider can't tell (FIX_PLAN #27). */
    private long queryAssetLength(android.net.Uri uri) {
        try (android.content.res.AssetFileDescriptor afd =
                     getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd == null) return -1;
            long len = afd.getLength();
            if (len == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH || len <= 0) {
                // UNKNOWN_LENGTH = "to end of file": a plain file descriptor may still know it.
                long st = afd.getParcelFileDescriptor() != null
                        ? afd.getParcelFileDescriptor().getStatSize() - afd.getStartOffset() : -1;
                return st > 0 ? st : -1;
            }
            return len;
        } catch (Exception e) {
            Log.w(TAG, "openAssetFileDescriptor failed: " + e.getMessage());
            return -1;
        }
    }

    /** Copies a content Uri into the cache dir; returns null on failure. {@code fileName} must be sanitized. */
    private File copyUriToCache(android.net.Uri uri, String fileName) {
        File out = new File(getCacheDir(), fileName);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IOException("openInputStream returned null");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) fos.write(buf, 0, r);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "Copy to cache failed: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }

    /** Opens the bytes to transfer for a queue item: its content Uri, else its local file; null if neither. */
    private InputStream openTransferSource(FileQueueItem item) throws IOException {
        if (item.getLocalUri() != null) return getContentResolver().openInputStream(item.getLocalUri());
        if (item.getLocalFilePath() != null) return new FileInputStream(item.getLocalFilePath());
        return null;
    }

    private void sendQueueAddOverStream(DataOutputStream out, FileQueueItem item) {
        new Thread(() -> {
            try {
                if (out != null) {
                    synchronized (out) {
                        out.writeByte(SYNC_QUEUE_ADD);
                        byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(idBytes.length);
                        out.write(idBytes);

                        byte[] nameBytes = item.getFileName().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(nameBytes.length);
                        out.write(nameBytes);

                        byte[] artistBytes = item.getArtist().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(artistBytes.length);
                        out.write(artistBytes);

                        byte[] albumBytes = item.getAlbum().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(albumBytes.length);
                        out.write(albumBytes);

                        out.writeLong(item.getFileSize());

                        byte[] mimeBytes = item.getMimeType().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(mimeBytes.length);
                        out.write(mimeBytes);

                        out.writeInt(item.getDurationMs());

                        byte[] durBytes = item.getDurationStr().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(durBytes.length);
                        out.write(durBytes);

                        byte[] fmtBytes = item.getFormatDesc().getBytes(StandardCharsets.UTF_8);
                        out.writeShort(fmtBytes.length);
                        out.write(fmtBytes);

                        out.flush();
                    }
                }
            } catch (Exception ignored) {}
        }, "SendQueueAdd").start();
    }

    // ---------- MULTI-RECEIVER BROADCAST METHODS (HOST) ----------

    private void broadcastSyncCommand(byte cmd, long position, long playAt, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (excludePeer != null && peer == excludePeer) continue;
            if (!peer.initialized) continue;   // still calibrating (FIX_PLAN #26)
            new Thread(() -> {
                try {
                    synchronized (peer.dos) {
                        peer.dos.writeByte(cmd);
                        peer.dos.writeLong(position);
                        if (cmd == SYNC_PLAY || cmd == SYNC_SEEK || cmd == SYNC_PAUSE_CMD) {
                            peer.dos.writeLong(playAt);
                        }
                        peer.dos.flush();
                    }
                } catch (Exception e) {
                    removeFileSharePeer(peer);
                }
            }, "BroadcastSyncCmd").start();
        }
    }

    private void broadcastSyncPulse(int index, long pos, long hostTime) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (!peer.initialized) continue;   // still calibrating (FIX_PLAN #26)
            new Thread(() -> {
                try {
                    synchronized (peer.dos) {
                        peer.dos.writeByte(SYNC_TIME_PULSE);
                        peer.dos.writeInt(index);
                        peer.dos.writeLong(pos);
                        peer.dos.writeLong(hostTime);
                        peer.dos.flush();
                    }
                } catch (Exception e) {
                    removeFileSharePeer(peer);
                }
            }, "SyncPulse").start();
        }
    }

    private void broadcastQueueAdd(FileQueueItem item, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (peer == excludePeer) continue;
            if (!peer.initialized) continue;   // INIT will send the full queue (FIX_PLAN #26)
            sendQueueAddOverStream(peer.dos, item);
        }
    }

    private void broadcastTrackChange(int index, long playAt, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (peer == excludePeer) continue;
            if (!peer.initialized) continue;   // INIT sends the current track (FIX_PLAN #26)
            new Thread(() -> {
                try {
                    synchronized (peer.dos) {
                        peer.dos.writeByte(SYNC_TRACK_CHANGE);
                        peer.dos.writeInt(index);
                        peer.dos.writeLong(playAt);
                        peer.dos.flush();
                    }
                } catch (Exception e) {
                    removeFileSharePeer(peer);
                }
            }, "BroadcastTrackChange").start();
        }
    }

    private void broadcastQueueReorder(int fromPos, int toPos, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (peer == excludePeer) continue;
            if (!peer.initialized) continue;   // FIX_PLAN #26
            new Thread(() -> {
                try {
                    synchronized (peer.dos) {
                        peer.dos.writeByte(SYNC_QUEUE_REORDER);
                        peer.dos.writeInt(fromPos);
                        peer.dos.writeInt(toPos);
                        peer.dos.flush();
                    }
                } catch (Exception e) {
                    removeFileSharePeer(peer);
                }
            }, "BroadcastQueueReorder").start();
        }
    }

    private void broadcastQueueRemove(String trackId, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (peer == excludePeer) continue;
            if (!peer.initialized) continue;   // FIX_PLAN #26
            new Thread(() -> {
                try {
                    synchronized (peer.dos) {
                        peer.dos.writeByte(SYNC_QUEUE_REMOVE);
                        byte[] idBytes = trackId.getBytes(StandardCharsets.UTF_8);
                        peer.dos.writeShort(idBytes.length);
                        peer.dos.write(idBytes);
                        peer.dos.flush();
                    }
                } catch (Exception e) {
                    removeFileSharePeer(peer);
                }
            }, "BroadcastQueueRemove").start();
        }
    }

    private void removeFileSharePeer(FileSharePeer peer) {
        connectedFileSharePeers.remove(peer);
        peer.close();
        mainHandler.post(() -> {
            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                fileShareFragment.setStatus("Host (" + connectedFileSharePeers.size() + " receiver(s) connected)");
            }
        });
    }

    // ---------- SYMMETRIC FILE TRANSFER ENGINE ----------

    private void transferTrackToAllPeers(FileQueueItem item) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            // SYNC_FILE_INCOMING on the control stream would break calibration; INIT
            // transfers every available track anyway (FIX_PLAN #26).
            if (!peer.initialized) continue;
            transferTrackToSpecificPeer(item, peer);
        }
    }

    private void transferTrackToSpecificPeer(FileQueueItem item, FileSharePeer peer) {
        new Thread(() -> transferTrackToSpecificPeerBlocking(item, peer), "PeerTrackSender").start();
    }

    private void transferAllAvailableTracksToPeer(FileSharePeer peer) {
        new Thread(() -> {
            List<FileQueueItem> queueSnapshot = new ArrayList<>(fileShareQueue);
            if (queueSnapshot.isEmpty()) return;

            // Prioritize the currently active playing track so late joiner starts playing immediately!
            if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < queueSnapshot.size()) {
                FileQueueItem currentPlayingItem = queueSnapshot.get(currentPlayingQueueIndex);
                if (currentPlayingItem.getLocalUri() != null || currentPlayingItem.getLocalFilePath() != null) {
                    transferTrackToSpecificPeerBlocking(currentPlayingItem, peer);
                }
            }

            // Transfer all remaining tracks
            for (int i = 0; i < queueSnapshot.size(); i++) {
                if (i == currentPlayingQueueIndex) continue; // already transferred first
                if (!isFileShareActive.get() || peer.socket.isClosed() || !peer.socket.isConnected()) break;
                FileQueueItem item = queueSnapshot.get(i);
                if (item.getLocalUri() != null || item.getLocalFilePath() != null) {
                    transferTrackToSpecificPeerBlocking(item, peer);
                }
            }
        }, "PeerAllTracksSender-" + peer.ip).start();
    }

    private void transferTrackToSpecificPeerBlocking(FileQueueItem item, FileSharePeer peer) {
        ServerSocket transferServer = null;
        Socket transferClient = null;
        try {
            transferServer = new ServerSocket(0);
            int dynamicPort = transferServer.getLocalPort();

            DataOutputStream ctrlOut = peer.dos;
            synchronized (ctrlOut) {
                ctrlOut.writeByte(SYNC_FILE_INCOMING);
                byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                ctrlOut.writeShort(idBytes.length);
                ctrlOut.write(idBytes);

                byte[] nameBytes = item.getFileName().getBytes(StandardCharsets.UTF_8);
                ctrlOut.writeShort(nameBytes.length);
                ctrlOut.write(nameBytes);

                ctrlOut.writeLong(item.getFileSize());

                byte[] mimeBytes = item.getMimeType().getBytes(StandardCharsets.UTF_8);
                ctrlOut.writeShort(mimeBytes.length);
                ctrlOut.write(mimeBytes);

                ctrlOut.writeInt(dynamicPort);
                ctrlOut.flush();
            }

            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.showTransferProgress(true);
                }
            });

            transferServer.setSoTimeout(15000);
            transferClient = transferServer.accept();
            transferClient.setTcpNoDelay(true);
            transferClient.setSendBufferSize(65536);

            DataOutputStream dos = new DataOutputStream(transferClient.getOutputStream());

            dos.writeInt(FILE_SHARE_MAGIC);
            byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
            dos.writeShort(idBytes.length);
            dos.write(idBytes);
            dos.writeLong(item.getFileSize());
            dos.flush();

            // try-with-resources: the stream no longer leaks on errors (FIX_PLAN #27)
            try (InputStream fileIn = openTransferSource(item)) {
            if (fileIn == null) return;

            byte[] buffer = new byte[8192];
            long sent = 0;
            long lastUpdate = System.currentTimeMillis();
            long lastSentForSpeed = 0;
            long fileSize = item.getFileSize();

            int read;
            // Never send more than the size announced in the SSFD header.
            while (sent < fileSize
                    && (read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, fileSize - sent))) != -1
                    && isFileShareActive.get()) {
                dos.write(buffer, 0, read);
                sent += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 200) {
                    long elapsed = now - lastUpdate;
                    long bytesSince = sent - lastSentForSpeed;
                    double speedMBps = (elapsed > 0)
                            ? (bytesSince / 1024.0 / 1024.0) / (elapsed / 1000.0) : 0;
                    int percent = fileSize > 0 ? (int) ((sent * 100) / fileSize) : 0;
                    String speedStr = String.format(Locale.US, "%.1f MB/s", speedMBps);
                    final int p = percent;
                    mainHandler.post(() -> {
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.updateTransferProgress(p, speedStr);
                        }
                    });
                    lastUpdate = now;
                    lastSentForSpeed = sent;
                }
            }
            dos.flush();
            }

            mainHandler.post(() -> {
                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                    fileShareFragment.setTransferComplete();
                }
            });

        } catch (Exception e) {
            if (isFileShareActive.get()) {
                Log.e(TAG, "Transfer to peer error: " + e.getMessage());
            }
        } finally {
            closeQuietly(transferClient);
            closeQuietly(transferServer);
        }
    }

    private void transferTrackToPeer(FileQueueItem item) {
        new Thread(() -> {
            ServerSocket transferServer = null;
            Socket transferClient = null;
            try {
                transferServer = new ServerSocket(0);
                int dynamicPort = transferServer.getLocalPort();

                DataOutputStream ctrlOut = syncControlOut;
                if (ctrlOut != null) {
                    synchronized (ctrlOut) {
                        ctrlOut.writeByte(SYNC_FILE_INCOMING);
                        byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                        ctrlOut.writeShort(idBytes.length);
                        ctrlOut.write(idBytes);

                        byte[] nameBytes = item.getFileName().getBytes(StandardCharsets.UTF_8);
                        ctrlOut.writeShort(nameBytes.length);
                        ctrlOut.write(nameBytes);

                        ctrlOut.writeLong(item.getFileSize());

                        byte[] mimeBytes = item.getMimeType().getBytes(StandardCharsets.UTF_8);
                        ctrlOut.writeShort(mimeBytes.length);
                        ctrlOut.write(mimeBytes);

                        ctrlOut.writeInt(dynamicPort);
                        ctrlOut.flush();
                    }
                }

                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.showTransferProgress(true);
                    }
                });

                transferServer.setSoTimeout(15000);
                transferClient = transferServer.accept();
                transferClient.setTcpNoDelay(true);
                transferClient.setSendBufferSize(65536);

                DataOutputStream dos = new DataOutputStream(transferClient.getOutputStream());

                dos.writeInt(FILE_SHARE_MAGIC);
                byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(idBytes.length);
                dos.write(idBytes);
                dos.writeLong(item.getFileSize());
                dos.flush();

                // try-with-resources (FIX_PLAN #27); the source may be a content Uri or a
                // cache copy made when the provider didn't report a size.
                try (InputStream fileIn = openTransferSource(item)) {
                if (fileIn == null) throw new IOException("Cannot open local audio stream");

                byte[] buffer = new byte[8192];
                long sent = 0;
                long lastUpdate = System.currentTimeMillis();
                long lastSentForSpeed = 0;
                long fileSize = item.getFileSize();

                int read;
                // Never send more than the size announced in the SSFD header.
                while (sent < fileSize
                        && (read = fileIn.read(buffer, 0, (int) Math.min(buffer.length, fileSize - sent))) != -1
                        && isFileShareActive.get()) {
                    dos.write(buffer, 0, read);
                    sent += read;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 200) {
                        long elapsed = now - lastUpdate;
                        long bytesSince = sent - lastSentForSpeed;
                        double speedMBps = (elapsed > 0)
                                ? (bytesSince / 1024.0 / 1024.0) / (elapsed / 1000.0) : 0;
                        int percent = fileSize > 0 ? (int) ((sent * 100) / fileSize) : 0;
                        String speedStr = String.format(Locale.US, "%.1f MB/s", speedMBps);
                        final int p = percent;
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.updateTransferProgress(p, speedStr);
                            }
                        });
                        lastUpdate = now;
                        lastSentForSpeed = sent;
                    }
                }
                dos.flush();
                }

                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setTransferComplete();
                    }
                });

            } catch (Exception e) {
                if (isFileShareActive.get()) {
                    Log.e(TAG, "Transfer sender error: " + e.getMessage(), e);
                }
            } finally {
                closeQuietly(transferClient);
                closeQuietly(transferServer);
            }
        }, "TrackSenderThread").start();
    }

    private void receiveTrackFromPeer(String targetIp, int targetPort, String trackId,
                                      String fileName, long fileSize, String mimeType) {
        new Thread(() -> {
            Socket receiverSocket = null;
            try {
                boolean connected = false;
                for (int i = 0; i < 5 && !connected && isFileShareActive.get(); i++) {
                    try {
                        receiverSocket = new Socket();
                        bindToLan(receiverSocket, targetIp);
                        receiverSocket.connect(new InetSocketAddress(targetIp, targetPort), 3000);
                        connected = true;
                    } catch (Exception e) {
                        closeQuietly(receiverSocket);
                        receiverSocket = null;
                        Thread.sleep(150);
                    }
                }
                if (!connected) throw new IOException("Failed to connect to sender transfer socket on " + targetIp + ":" + targetPort);

                receiverSocket.setTcpNoDelay(true);
                receiverSocket.setReceiveBufferSize(65536);
                // A stalled sender must not hang this thread forever (FIX_PLAN #27).
                receiverSocket.setSoTimeout(15000);

                DataInputStream dis = new DataInputStream(
                        new BufferedInputStream(receiverSocket.getInputStream()));

                int magic = dis.readInt();
                if (magic != FILE_SHARE_MAGIC) throw new IOException("Transfer header mismatch");

                int idLen = dis.readShort() & 0xFFFF;
                byte[] idBytes = new byte[idLen];
                dis.readFully(idBytes);

                long actualSize = dis.readLong();

                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.showTransferProgress(true);
                        fileShareFragment.setStatus("Receiving " + fileName + "...");
                    }
                });

                // trackId and fileName come from the network: sanitize so a "/" or "../"
                // can't escape the cache dir (FIX_PLAN #27, path traversal).
                File outFile = new File(getCacheDir(), "fileshare_"
                        + NetUtils.sanitizeFileName(trackId) + "_"
                        + NetUtils.sanitizeFileName(fileName));
                byte[] buffer = new byte[8192];
                long received = 0;
                long lastUpdate = System.currentTimeMillis();
                long lastRecvForSpeed = 0;
                boolean complete = false;

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                while (received < actualSize && isFileShareActive.get()) {
                    int toRead = (int) Math.min(buffer.length, actualSize - received);
                    int rd = dis.read(buffer, 0, toRead);
                    if (rd == -1) break;
                    fos.write(buffer, 0, rd);
                    received += rd;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 200) {
                        long elapsed = now - lastUpdate;
                        long bytesSince = received - lastRecvForSpeed;
                        double speedMBps = (elapsed > 0)
                                ? (bytesSince / 1024.0 / 1024.0) / (elapsed / 1000.0) : 0;
                        int percent = actualSize > 0 ? (int) ((received * 100) / actualSize) : 0;
                        String speedStr = String.format(Locale.US, "%.1f MB/s", speedMBps);
                        final int p = percent;
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.updateTransferProgress(p, speedStr);
                            }
                        });
                        lastUpdate = now;
                        lastRecvForSpeed = received;
                    }
                }
                complete = received == actualSize;
                } finally {
                    if (!complete) {
                        // Truncated/aborted: never mark a partial file ready (FIX_PLAN #27).
                        //noinspection ResultOfMethodCallIgnored
                        outFile.delete();
                    }
                }
                if (!complete) {
                    final long got = received, want = actualSize;
                    PipelineLog.w("CONNECT", "File transfer incomplete: " + got + "/" + want + " bytes");
                    if (isFileShareActive.get()) {
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.showTransferProgress(false);
                                fileShareFragment.setStatus("Transfer of " + fileName
                                        + " incomplete (" + got + "/" + want + " bytes)");
                            }
                        });
                    }
                    return;
                }

                for (FileQueueItem qItem : fileShareQueue) {
                    if (qItem.getId().equals(trackId)) {
                        qItem.setLocalFilePath(outFile.getAbsolutePath());
                        qItem.setReady(true);
                        break;
                    }
                }

                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setTransferComplete();
                        fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                    }
                });

                if (isFileShareHost) {
                    for (FileQueueItem item : fileShareQueue) {
                        if (item.getId().equals(trackId)) {
                            transferTrackToAllPeers(item);
                            break;
                        }
                    }
                }

                DataOutputStream ctrlOut = syncControlOut;
                if (ctrlOut != null) {
                    synchronized (ctrlOut) {
                        ctrlOut.writeByte(FILE_READY);
                        byte[] readyIdBytes = trackId.getBytes(StandardCharsets.UTF_8);
                        ctrlOut.writeShort(readyIdBytes.length);
                        ctrlOut.write(readyIdBytes);
                        ctrlOut.flush();
                    }
                }

                // If receiver was waiting for this download to start playing:
                if (pendingPlayIndex >= 0 && pendingPlayIndex < fileShareQueue.size()) {
                    FileQueueItem pendingItem = fileShareQueue.get(pendingPlayIndex);
                    if (pendingItem.getId().equals(trackId) || pendingItem.isReady()) {
                        int playIdx = pendingPlayIndex;
                        pendingPlayIndex = -1;
                        changeAndPlayTrack(playIdx);
                    }
                }

            } catch (Exception e) {
                if (isFileShareActive.get()) {
                    Log.e(TAG, "Transfer receiver error: " + e.getMessage(), e);
                }
            } finally {
                closeQuietly(receiverSocket);
            }
        }, "TrackReceiverThread").start();
    }

    // ---------- MULTI-CLIENT SYNC CONTROL SERVER (HOST, PORT 5006) ----------

    private void startSyncControlServer() {
        syncControlThread = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(SYNC_CTRL_PORT);
                syncControlServerSocket = ss;
                while (isFileShareActive.get()) {
                    Socket client = ss.accept();
                    client.setTcpNoDelay(true);
                    String clientIp = client.getInetAddress().getHostAddress();

                    DataInputStream dis = new DataInputStream(client.getInputStream());
                    DataOutputStream dos = new DataOutputStream(client.getOutputStream());

                    FileSharePeer peer = new FileSharePeer(client, dis, dos, clientIp);
                    connectedFileSharePeers.add(peer);

                    mainHandler.post(() -> {
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.setStatus("Host (" + connectedFileSharePeers.size() + " receiver(s) connected)");
                        }
                    });

                    new Thread(() -> handleFileSharePeer(peer), "PeerHandler_" + clientIp).start();
                }
            } catch (Exception e) {
                // Our server (bind failed, or our socket is still the published one) died while the
                // host is active: nobody can reach this host, so end File Share instead of leaving
                // its foreground service running (phase5 review L2). A superseded thread does nothing.
                final boolean ours = ss == null || syncControlServerSocket == ss;
                if (isFileShareActive.get() && ours) {
                    Log.e(TAG, "SyncControlServer error: " + e.getMessage());
                    final String why = ss == null
                            ? "Port " + SYNC_CTRL_PORT + " is in use. File Share host stopped"
                            : "File Share host stopped (network error)";
                    mainHandler.post(() -> {
                        if (!isFileShareActive.get()) return;
                        stopFileShare();   // posts resetUI(); show the reason after it
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.setStatus(why);
                            }
                        });
                    });
                }
            } finally {
                closeQuietly(ss);   // FIX_PLAN #12: our socket only; keep a newer session's
                if (syncControlServerSocket == ss) syncControlServerSocket = null;
            }
        }, "SyncControlServer");
        syncControlThread.start();
    }

    private void handleFileSharePeer(FileSharePeer peer) {
        try {
            while (isFileShareActive.get()) {
                byte cmd = peer.dis.readByte();

                switch (cmd) {
                    case SYNC_CLOCK_REQ: {
                        long t1 = peer.dis.readLong();
                        long t2 = System.currentTimeMillis();
                        synchronized (peer.dos) {
                            peer.dos.writeByte(SYNC_CLOCK_RSP);
                            peer.dos.writeLong(t1);
                            peer.dos.writeLong(t2);
                            peer.dos.writeLong(System.currentTimeMillis());
                            peer.dos.flush();
                        }
                        break;
                    }
                    case SYNC_INIT_REQ: {
                        // Late joiner or new joiner is now fully calibrated and waiting for playlist!
                        // From now on it receives broadcasts (FIX_PLAN #26). Set before the
                        // snapshot so nothing added concurrently is missed.
                        peer.initialized = true;
                        for (FileQueueItem item : new ArrayList<>(fileShareQueue)) {
                            synchronized (peer.dos) {
                                peer.dos.writeByte(SYNC_QUEUE_ADD);
                                byte[] idBytes = item.getId().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(idBytes.length);
                                peer.dos.write(idBytes);

                                byte[] nameBytes = item.getFileName().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(nameBytes.length);
                                peer.dos.write(nameBytes);

                                byte[] artistBytes = item.getArtist().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(artistBytes.length);
                                peer.dos.write(artistBytes);

                                byte[] albumBytes = item.getAlbum().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(albumBytes.length);
                                peer.dos.write(albumBytes);

                                peer.dos.writeLong(item.getFileSize());

                                byte[] mimeBytes = item.getMimeType().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(mimeBytes.length);
                                peer.dos.write(mimeBytes);

                                peer.dos.writeInt(item.getDurationMs());

                                byte[] durBytes = item.getDurationStr().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(durBytes.length);
                                peer.dos.write(durBytes);

                                byte[] fmtBytes = item.getFormatDesc().getBytes(StandardCharsets.UTF_8);
                                peer.dos.writeShort(fmtBytes.length);
                                peer.dos.write(fmtBytes);

                                peer.dos.flush();
                            }
                        }

                        // Send active track index if host is playing
                        if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < fileShareQueue.size()) {
                            long playAt = System.currentTimeMillis() + 650;
                            synchronized (peer.dos) {
                                peer.dos.writeByte(SYNC_TRACK_CHANGE);
                                peer.dos.writeInt(currentPlayingQueueIndex);
                                peer.dos.writeLong(playAt);
                                peer.dos.flush();
                            }
                        }

                        // Upload all audio files in the playlist sequentially to this peer (active song first)
                        transferAllAvailableTracksToPeer(peer);
                        break;
                    }
                    case SYNC_QUEUE_ADD: {
                        int idLen = peer.dis.readShort() & 0xFFFF;
                        byte[] idBytes = new byte[idLen];
                        peer.dis.readFully(idBytes);
                        String qId = new String(idBytes, StandardCharsets.UTF_8);

                        int nameLen = peer.dis.readShort() & 0xFFFF;
                        byte[] nameBytes = new byte[nameLen];
                        peer.dis.readFully(nameBytes);
                        String qName = new String(nameBytes, StandardCharsets.UTF_8);

                        int artistLen = peer.dis.readShort() & 0xFFFF;
                        byte[] artistBytes = new byte[artistLen];
                        peer.dis.readFully(artistBytes);
                        String qArtist = new String(artistBytes, StandardCharsets.UTF_8);

                        int albumLen = peer.dis.readShort() & 0xFFFF;
                        byte[] albumBytes = new byte[albumLen];
                        peer.dis.readFully(albumBytes);
                        String qAlbum = new String(albumBytes, StandardCharsets.UTF_8);

                        long qSize = peer.dis.readLong();

                        int mimeLen = peer.dis.readShort() & 0xFFFF;
                        byte[] mimeBytes = new byte[mimeLen];
                        peer.dis.readFully(mimeBytes);
                        String qMime = new String(mimeBytes, StandardCharsets.UTF_8);

                        int qDurMs = peer.dis.readInt();

                        int durLen = peer.dis.readShort() & 0xFFFF;
                        byte[] durBytes = new byte[durLen];
                        peer.dis.readFully(durBytes);
                        String qDurStr = new String(durBytes, StandardCharsets.UTF_8);

                        int fmtLen = peer.dis.readShort() & 0xFFFF;
                        byte[] fmtBytes = new byte[fmtLen];
                        peer.dis.readFully(fmtBytes);
                        String qFmt = new String(fmtBytes, StandardCharsets.UTF_8);

                        FileQueueItem newItem = new FileQueueItem(
                                qId, qName, qArtist, qAlbum, qSize, qMime, qDurMs, qDurStr, qFmt,
                                null, null, false);
                        fileShareQueue.add(newItem);

                        broadcastQueueAdd(newItem, peer);

                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                fileShareFragment.setStatus("Receiver added: " + qName);
                            }
                        });
                        break;
                    }
                    case SYNC_FILE_INCOMING: {
                        int idLen = peer.dis.readShort() & 0xFFFF;
                        byte[] idBytes = new byte[idLen];
                        peer.dis.readFully(idBytes);
                        String trId = new String(idBytes, StandardCharsets.UTF_8);

                        int nameLen = peer.dis.readShort() & 0xFFFF;
                        byte[] nameBytes = new byte[nameLen];
                        peer.dis.readFully(nameBytes);
                        String trName = new String(nameBytes, StandardCharsets.UTF_8);

                        long trSize = peer.dis.readLong();

                        int mimeLen = peer.dis.readShort() & 0xFFFF;
                        byte[] mimeBytes = new byte[mimeLen];
                        peer.dis.readFully(mimeBytes);
                        String trMime = new String(mimeBytes, StandardCharsets.UTF_8);

                        int trPort = peer.dis.readInt();

                        receiveTrackFromPeer(peer.ip, trPort, trId, trName, trSize, trMime);
                        break;
                    }
                    case FILE_READY: {
                        int idLen = peer.dis.readShort() & 0xFFFF;
                        byte[] idBytes = new byte[idLen];
                        peer.dis.readFully(idBytes);
                        String rId = new String(idBytes, StandardCharsets.UTF_8);

                        for (FileQueueItem item : fileShareQueue) {
                            if (item.getId().equals(rId)) {
                                item.setReady(true);
                                break;
                            }
                        }
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                            }
                        });

                        // If pending playback was waiting for upload/transfer to complete:
                        if (pendingPlayIndex >= 0 && pendingPlayIndex < fileShareQueue.size()) {
                            FileQueueItem pendingItem = fileShareQueue.get(pendingPlayIndex);
                            if (pendingItem.getId().equals(rId) || pendingItem.isReady()) {
                                int playIdx = pendingPlayIndex;
                                pendingPlayIndex = -1;
                                changeAndPlayTrack(playIdx);
                            }
                        } else if ((isSyncPlaying.get() || (syncMediaPlayer != null && syncMediaPlayer.isPlaying())) && currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < fileShareQueue.size()) {
                            // If Host is actively playing, send live catch-up play packet to the receiver!
                            FileQueueItem currentItem = fileShareQueue.get(currentPlayingQueueIndex);
                            if (currentItem.getId().equals(rId)) {
                                int currentPos = (syncMediaPlayer != null) ? syncMediaPlayer.getCurrentPosition() : 0;
                                long playAt = System.currentTimeMillis() + 650;
                                synchronized (peer.dos) {
                                    peer.dos.writeByte(SYNC_TRACK_CHANGE);
                                    peer.dos.writeInt(currentPlayingQueueIndex);
                                    peer.dos.writeLong(playAt);

                                    peer.dos.writeByte(SYNC_PLAY);
                                    peer.dos.writeLong(currentPos);
                                    peer.dos.writeLong(playAt);
                                    peer.dos.flush();
                                }
                            }
                        }
                        break;
                    }
                    case SYNC_TRACK_CHANGE: {
                        int newIndex = peer.dis.readInt();
                        long playAt = peer.dis.readLong();
                        playQueueTrack(newIndex, playAt);
                        broadcastTrackChange(newIndex, playAt, peer);
                        break;
                    }
                    case SYNC_QUEUE_REORDER: {
                        int fromPos = peer.dis.readInt();
                        int toPos = peer.dis.readInt();
                        if (fromPos >= 0 && fromPos < fileShareQueue.size() &&
                            toPos >= 0 && toPos < fileShareQueue.size()) {
                            FileQueueItem movedItem = fileShareQueue.remove(fromPos);
                            fileShareQueue.add(toPos, movedItem);

                            if (currentPlayingQueueIndex == fromPos) {
                                currentPlayingQueueIndex = toPos;
                            } else if (fromPos < currentPlayingQueueIndex && toPos >= currentPlayingQueueIndex) {
                                currentPlayingQueueIndex--;
                            } else if (fromPos > currentPlayingQueueIndex && toPos <= currentPlayingQueueIndex) {
                                currentPlayingQueueIndex++;
                            }

                            mainHandler.post(() -> {
                                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                    fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                }
                            });

                            broadcastQueueReorder(fromPos, toPos, peer);
                        }
                        break;
                    }
                    case SYNC_QUEUE_REMOVE: {
                        int idLen = peer.dis.readShort() & 0xFFFF;
                        byte[] idBytes = new byte[idLen];
                        peer.dis.readFully(idBytes);
                        String rmId = new String(idBytes, StandardCharsets.UTF_8);

                        for (int i = 0; i < fileShareQueue.size(); i++) {
                            if (fileShareQueue.get(i).getId().equals(rmId)) {
                                fileShareQueue.remove(i);
                                break;
                            }
                        }
                        mainHandler.post(() -> {
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                            }
                        });
                        broadcastQueueRemove(rmId, peer);
                        break;
                    }
                    case SYNC_PLAY: {
                        long pos = peer.dis.readLong();
                        long playAt = peer.dis.readLong();
                        scheduleLocalPlay(pos, playAt);
                        broadcastSyncCommand(SYNC_PLAY, pos, playAt, peer);
                        break;
                    }
                    case SYNC_PAUSE_CMD: {
                        long pos = peer.dis.readLong();
                        long pauseAt = peer.dis.readLong();
                        pauseLocalAt(pos, pauseAt);
                        broadcastSyncCommand(SYNC_PAUSE_CMD, pos, pauseAt, peer);
                        break;
                    }
                    case SYNC_SEEK: {
                        long pos = peer.dis.readLong();
                        long playAt = peer.dis.readLong();
                        scheduleLocalPlay(pos, playAt);
                        broadcastSyncCommand(SYNC_SEEK, pos, playAt, peer);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            removeFileSharePeer(peer);
        }
    }

    // ---------- SYNC CONTROL CLIENT (JOINER, PORT 5006) ----------

    private void startSyncControlClient(String hostIp) {
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                bindToLan(socket, hostIp);
                socket.connect(new InetSocketAddress(hostIp, SYNC_CTRL_PORT), 5000);
                PipelineLog.i("CONNECT", "Sync control connected to " + PipelineLog.redactIps(hostIp));
                syncControlSocket = socket;
                socket.setTcpNoDelay(true);

                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                syncControlOut = dos;

                // High-precision NTP 8-sample clock sync with outlier rejection
                int syncCount = 8;
                List<Long> offsetSamples = new ArrayList<>();
                for (int i = 0; i < syncCount; i++) {
                    long t1 = System.currentTimeMillis();
                    synchronized (dos) {
                        dos.writeByte(SYNC_CLOCK_REQ);
                        dos.writeLong(t1);
                        dos.flush();
                    }

                    byte rsp = dis.readByte();
                    if (rsp != SYNC_CLOCK_RSP) {
                        // FIX_PLAN #26: anything else here means the stream is out of sync;
                        // fail loudly instead of parsing a bogus offset and desyncing.
                        throw new IOException(String.format(Locale.US,
                                "Unexpected 0x%02X during clock calibration", rsp & 0xFF));
                    }
                    long origT1 = dis.readLong();
                    long t2 = dis.readLong();
                    long t3 = dis.readLong();
                    long t4 = System.currentTimeMillis();

                    long offset = ((t2 - t1) + (t3 - t4)) / 2;
                    offsetSamples.add(offset);
                    Thread.sleep(25);
                }
                Collections.sort(offsetSamples);
                clockOffset = (offsetSamples.get(3) + offsetSamples.get(4)) / 2;
                Log.d(TAG, "Calibrated clock sync offset: " + clockOffset + "ms");

                mainHandler.post(() -> {
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setStatus("Connected to host (sync latency: " + Math.abs(clockOffset) + "ms)");
                    }
                });

                // Request initial playlist queue and file transfers from host!
                synchronized (dos) {
                    dos.writeByte(SYNC_INIT_REQ);
                    dos.flush();
                }

                while (isFileShareActive.get()) {
                    byte cmd = dis.readByte();

                    switch (cmd) {
                        case SYNC_QUEUE_ADD: {
                            int idLen = dis.readShort() & 0xFFFF;
                            byte[] idBytes = new byte[idLen];
                            dis.readFully(idBytes);
                            String qId = new String(idBytes, StandardCharsets.UTF_8);

                            int nameLen = dis.readShort() & 0xFFFF;
                            byte[] nameBytes = new byte[nameLen];
                            dis.readFully(nameBytes);
                            String qName = new String(nameBytes, StandardCharsets.UTF_8);

                            int artistLen = dis.readShort() & 0xFFFF;
                            byte[] artistBytes = new byte[artistLen];
                            dis.readFully(artistBytes);
                            String qArtist = new String(artistBytes, StandardCharsets.UTF_8);

                            int albumLen = dis.readShort() & 0xFFFF;
                            byte[] albumBytes = new byte[albumLen];
                            dis.readFully(albumBytes);
                            String qAlbum = new String(albumBytes, StandardCharsets.UTF_8);

                            long qSize = dis.readLong();

                            int mimeLen = dis.readShort() & 0xFFFF;
                            byte[] mimeBytes = new byte[mimeLen];
                            dis.readFully(mimeBytes);
                            String qMime = new String(mimeBytes, StandardCharsets.UTF_8);

                            int qDurMs = dis.readInt();

                            int durLen = dis.readShort() & 0xFFFF;
                            byte[] durBytes = new byte[durLen];
                            dis.readFully(durBytes);
                            String qDurStr = new String(durBytes, StandardCharsets.UTF_8);

                            int fmtLen = dis.readShort() & 0xFFFF;
                            byte[] fmtBytes = new byte[fmtLen];
                            dis.readFully(fmtBytes);
                            String qFmt = new String(fmtBytes, StandardCharsets.UTF_8);

                            boolean exists = false;
                            for (FileQueueItem item : fileShareQueue) {
                                if (item.getId().equals(qId)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                FileQueueItem newItem = new FileQueueItem(
                                        qId, qName, qArtist, qAlbum, qSize, qMime, qDurMs, qDurStr, qFmt,
                                        null, null, false);
                                fileShareQueue.add(newItem);

                                mainHandler.post(() -> {
                                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                        fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                        fileShareFragment.setStatus("Host added: " + qName);
                                    }
                                });
                            }
                            break;
                        }
                        case SYNC_FILE_INCOMING: {
                            int idLen = dis.readShort() & 0xFFFF;
                            byte[] idBytes = new byte[idLen];
                            dis.readFully(idBytes);
                            String trId = new String(idBytes, StandardCharsets.UTF_8);

                            int nameLen = dis.readShort() & 0xFFFF;
                            byte[] nameBytes = new byte[nameLen];
                            dis.readFully(nameBytes);
                            String trName = new String(nameBytes, StandardCharsets.UTF_8);

                            long trSize = dis.readLong();

                            int mimeLen = dis.readShort() & 0xFFFF;
                            byte[] mimeBytes = new byte[mimeLen];
                            dis.readFully(mimeBytes);
                            String trMime = new String(mimeBytes, StandardCharsets.UTF_8);

                            int trPort = dis.readInt();

                            receiveTrackFromPeer(hostIp, trPort, trId, trName, trSize, trMime);
                            break;
                        }
                        case FILE_READY: {
                            int idLen = dis.readShort() & 0xFFFF;
                            byte[] idBytes = new byte[idLen];
                            dis.readFully(idBytes);
                            String rId = new String(idBytes, StandardCharsets.UTF_8);

                            for (FileQueueItem item : fileShareQueue) {
                                if (item.getId().equals(rId)) {
                                    item.setReady(true);
                                    break;
                                }
                            }
                            mainHandler.post(() -> {
                                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                    fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                }
                            });
                            break;
                        }
                        case SYNC_TRACK_CHANGE: {
                            int newIndex = dis.readInt();
                            long playAt = dis.readLong();
                            long localPlayAt = playAt - clockOffset;
                            playQueueTrack(newIndex, localPlayAt);
                            break;
                        }
                        case SYNC_QUEUE_REORDER: {
                            int fromPos = dis.readInt();
                            int toPos = dis.readInt();
                            if (fromPos >= 0 && fromPos < fileShareQueue.size() &&
                                toPos >= 0 && toPos < fileShareQueue.size()) {
                                FileQueueItem movedItem = fileShareQueue.remove(fromPos);
                                fileShareQueue.add(toPos, movedItem);

                                if (currentPlayingQueueIndex == fromPos) {
                                    currentPlayingQueueIndex = toPos;
                                } else if (fromPos < currentPlayingQueueIndex && toPos >= currentPlayingQueueIndex) {
                                    currentPlayingQueueIndex--;
                                } else if (fromPos > currentPlayingQueueIndex && toPos <= currentPlayingQueueIndex) {
                                    currentPlayingQueueIndex++;
                                }

                                mainHandler.post(() -> {
                                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                        fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                    }
                                });
                            }
                            break;
                        }
                        case SYNC_QUEUE_REMOVE: {
                            int idLen = dis.readShort() & 0xFFFF;
                            byte[] idBytes = new byte[idLen];
                            dis.readFully(idBytes);
                            String rmId = new String(idBytes, StandardCharsets.UTF_8);

                            for (int i = 0; i < fileShareQueue.size(); i++) {
                                if (fileShareQueue.get(i).getId().equals(rmId)) {
                                    fileShareQueue.remove(i);
                                    break;
                                }
                            }
                            mainHandler.post(() -> {
                                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                    fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);
                                }
                            });
                            break;
                        }
                        case SYNC_PLAY: {
                            long pos = dis.readLong();
                            long playAt = dis.readLong();
                            long localPlayAt = playAt - clockOffset;
                            scheduleLocalPlay(pos, localPlayAt);
                            break;
                        }
                        case SYNC_PAUSE_CMD: {
                            long pos = dis.readLong();
                            long pauseAt = dis.readLong();
                            long localPauseAt = pauseAt - clockOffset;
                            pauseLocalAt(pos, localPauseAt);
                            break;
                        }
                        case SYNC_SEEK: {
                            long pos = dis.readLong();
                            long playAt = dis.readLong();
                            long localPlayAt = playAt - clockOffset;
                            scheduleLocalPlay(pos, localPlayAt);
                            break;
                        }
                        case SYNC_TIME_PULSE: {
                            final int pulseIdx = dis.readInt();
                            final long hostPos = dis.readLong();
                            final long hostTime = dis.readLong();
                            // All MediaPlayer work happens on the main thread with a local player
                            // reference, so a concurrent release can't throw here and end the
                            // whole FileShare session (FIX_PLAN #14 / F-12)
                            mainHandler.post(() -> {
                                try {
                                    if (!isFileShareActive.get()) return;
                                    if (pulseIdx < 0 || pulseIdx >= fileShareQueue.size()) return;
                                    FileQueueItem item = fileShareQueue.get(pulseIdx);
                                    if (item.getLocalFilePath() == null || !item.isReady()) return;
                                    long now = System.currentTimeMillis();
                                    long hostTimeInClientClock = hostTime - clockOffset;
                                    long expectedPos = hostPos + (now - hostTimeInClientClock);
                                    android.media.MediaPlayer mp = syncMediaPlayer;

                                    if (mp == null || currentPlayingQueueIndex != pulseIdx) {
                                        // Auto-catchup (late join): one schedule that starts at the
                                        // host's position at playAt (no second scheduleLocalPlay)
                                        currentPlayingQueueIndex = pulseIdx;
                                        playQueueTrack(pulseIdx, now + 50, expectedPos + 50);
                                    } else if (!mp.isPlaying() || !isSyncPlaying.get()) {
                                        // Receiver player is initialized but paused/stalled: resume it in sync!
                                        scheduleLocalPlay(expectedPos, now);
                                    } else {
                                        // Player is actively playing: seamless drift micro-correction
                                        long localPos = mp.getCurrentPosition();
                                        long signedDrift = expectedPos - localPos;
                                        long absDrift = Math.abs(signedDrift);
                                        if (absDrift > 600) {
                                            mp.seekTo((int) expectedPos);
                                            adjustPlaybackSpeed(1.0f);
                                        } else if (absDrift > 35) {
                                            adjustPlaybackSpeed(signedDrift > 0 ? 1.02f : 0.98f);
                                        } else {
                                            adjustPlaybackSpeed(1.0f);
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Sync pulse handling failed: " + e.getClass().getSimpleName());
                                }
                            });
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                if (isFileShareActive.get()) {
                    Log.d(TAG, "SyncControlClient disconnected / host offline: " + e.getMessage());
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Host disconnected", Toast.LENGTH_SHORT).show();
                    });
                    // stopFileShare() touches views and the MediaPlayer: run it on the main thread
                    // (phase5 review H1: CalledFromWrongThreadException killed the process here).
                    mainHandler.post(() -> {
                        if (isFileShareActive.get()) stopFileShare();
                    });
                }
            } finally {
                closeQuietly(syncControlSocket);
                syncControlSocket = null;
                syncControlOut = null;
            }
        }, "SyncControlClient").start();
    }

    // ---------- PLAYBACK QUEUE PLAYER ----------

    private void playQueueTrack(int index, long playAtMs) {
        playQueueTrack(index, playAtMs, 0);
    }

    /** Incremented per playQueueTrack; album-art results for an older track are dropped. Main thread only. */
    private volatile int albumArtRequestId = 0;
    /** Speed requested while the player was not playing (never applied implicitly — FIX_PLAN #14). */
    private volatile float pendingPlaybackSpeed = 1.0f;
    private volatile Runnable pendingSchedulePauseRunnable;

    /** Decodes embedded cover art with inSampleSize so the longest side is ≤ maxSide px. */
    private static Bitmap decodeBoundedArt(byte[] bytes, int maxSide) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth > 0 && bounds.outHeight > 0
                && (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide)) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    /**
     * @param startPosMs position to start from at playAtMs (late join); 0 = from the beginning.
     */
    private void playQueueTrack(int index, long playAtMs, long startPosMs) {
        mainHandler.post(() -> {
            if (index < 0 || index >= fileShareQueue.size()) return;
            FileQueueItem item = fileShareQueue.get(index);
            currentPlayingQueueIndex = index;
            if (pendingSchedulePauseRunnable != null) {
                mainHandler.removeCallbacks(pendingSchedulePauseRunnable);
                pendingSchedulePauseRunnable = null;
            }

            try {
                if (syncMediaPlayer != null) {
                    try { syncMediaPlayer.stop(); } catch (Exception ignored) {}
                    try { syncMediaPlayer.release(); } catch (Exception ignored) {}
                    syncMediaPlayer = null;
                }

                syncMediaPlayer = new android.media.MediaPlayer();
                syncMediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build());
                // Keep the CPU awake while playing with the screen off (FIX_PLAN #14)
                syncMediaPlayer.setWakeMode(getApplicationContext(),
                        android.os.PowerManager.PARTIAL_WAKE_LOCK);
                // Without an error listener MediaPlayer calls onCompletion after an error,
                // which made the host auto-advance the whole group (F-10)
                syncMediaPlayer.setOnErrorListener((errMp, what, extra) -> {
                    PipelineLog.e("MP_ERROR", "MediaPlayer error what=" + what + " extra=" + extra, null);
                    if (errMp == syncMediaPlayer) {
                        isSyncPlaying.set(false);
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.setStatus("Playback error (" + what + "/" + extra + ")");
                            fileShareFragment.setPlayPauseIcon(false);
                        }
                    }
                    return true;
                });

                if (item.getLocalUri() != null) {
                    syncMediaPlayer.setDataSource(this, item.getLocalUri());
                } else if (item.getLocalFilePath() != null) {
                    syncMediaPlayer.setDataSource(item.getLocalFilePath());
                } else {
                    fileShareFragment.setStatus("Waiting for track download: " + item.getFileName());
                    return;
                }

                syncMediaPlayer.prepare();
                applyStereoVolume();

                // Extract embedded album artwork off the UI thread (FIX_PLAN #14 / F-13);
                // the result is posted back and ignored if the track changed meanwhile.
                currentFileAlbumArt = null;
                final int artRequest = ++albumArtRequestId;
                final android.net.Uri artUri = item.getLocalUri();
                final String artPath = item.getLocalFilePath();
                final android.media.MediaPlayer artPlayer = syncMediaPlayer;
                final Context appCtx = getApplicationContext();
                new Thread(() -> {
                    Bitmap art = null;
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    try {
                        if (artUri != null) {
                            mmr.setDataSource(appCtx, artUri);
                        } else if (artPath != null) {
                            mmr.setDataSource(artPath);
                        }
                        art = decodeBoundedArt(mmr.getEmbeddedPicture(), 512);
                    } catch (Exception ignored) {
                    } finally {
                        try { mmr.release(); } catch (Exception ignored) {}
                    }
                    if (art == null) return;
                    final Bitmap finalArt = art;
                    mainHandler.post(() -> {
                        if (artRequest != albumArtRequestId || syncMediaPlayer != artPlayer
                                || currentPlayingQueueIndex != index) {
                            return;   // stale result for an older track
                        }
                        currentFileAlbumArt = finalArt;
                        if (fileShareFragment != null && fileShareFragment.isAdded()
                                && fileShareFragment.getView() != null) {
                            android.widget.ImageView iv = fileShareFragment.getView()
                                    .findViewById(R.id.ivPlaybackAlbumArt);
                            if (iv != null) {
                                iv.setImageBitmap(finalArt);
                                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                                iv.setPadding(0, 0, 0, 0);
                            }
                        }
                        try { updateFileNotification(isSyncPlaying.get()); } catch (Exception ignored) {}
                    });
                }, "AlbumArtDecode").start();

                int duration = syncMediaPlayer.getDuration();
                fileShareFragment.showPlaybackControls(true);
                fileShareFragment.setCurrentPlayingTrack(
                        index, item.getFileName(), item.getArtist(), item.getAlbum(),
                        item.getFormatDesc(), currentFileAlbumArt, duration);
                fileShareFragment.updateQueue(fileShareQueue, currentPlayingQueueIndex);

                // Fetch embedded lyrics or online lyrics from LRCLIB/lyrics.ovh
                fileShareFragment.setLyricsLoading();
                LyricsHelper.fetchLyrics(this, item, new LyricsHelper.LyricsCallback() {
                    @Override
                    public void onLyricsLoaded(LyricsHelper.ParsedLyrics lyrics) {
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.setLyrics(lyrics);
                        }
                    }

                    @Override
                    public void onLyricsError(String message) {
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.setLyrics(null);
                        }
                    }
                });

                updateFileNotification(true);

                // Auto-advance to next song on completion
                syncMediaPlayer.setOnCompletionListener(mp -> {
                    mainHandler.post(() -> {
                        if (isFileShareHost && !fileShareQueue.isEmpty()) {
                            int nextIdx = currentPlayingQueueIndex + 1;
                            if (nextIdx < fileShareQueue.size()) {
                                changeAndPlayTrack(nextIdx);
                            }
                        }
                    });
                });

                startPlaybackUpdateLoop();
                scheduleLocalPlay(Math.max(0, startPosMs), playAtMs);

            } catch (Exception e) {
                Log.e(TAG, "playQueueTrack error: " + e.getMessage(), e);
                fileShareFragment.setStatus("Playback error: " + e.getMessage());
            }
        });
    }

    private void updateFileNotification(boolean isPlaying) {
        if (fileShareNotifHelper == null) return;
        if (currentPlayingQueueIndex >= 0 && currentPlayingQueueIndex < fileShareQueue.size()) {
            FileQueueItem item = fileShareQueue.get(currentPlayingQueueIndex);
            int dur = (syncMediaPlayer != null) ? syncMediaPlayer.getDuration() : item.getDurationMs();
            int pos = (syncMediaPlayer != null) ? syncMediaPlayer.getCurrentPosition() : 0;
            fileShareNotifHelper.updateNotification(
                    item.getFileName(),
                    item.getArtist(),
                    item.getFormatDesc(),
                    currentFileAlbumArt,
                    isPlaying,
                    dur,
                    pos
            );
        }
    }

    private void stopPlayback() {
        if (pendingSchedulePlayRunnable != null) {
            mainHandler.removeCallbacks(pendingSchedulePlayRunnable);
            pendingSchedulePlayRunnable = null;
        }
        if (pendingSchedulePauseRunnable != null) {
            mainHandler.removeCallbacks(pendingSchedulePauseRunnable);
            pendingSchedulePauseRunnable = null;
        }
        albumArtRequestId++;   // drop any album-art decode still in flight
        if (syncMediaPlayer != null) {
            try { syncMediaPlayer.stop(); } catch (Exception ignored) {}
            try { syncMediaPlayer.release(); } catch (Exception ignored) {}
            syncMediaPlayer = null;
        }
        currentPlayingQueueIndex = -1;
        if (fileShareFragment != null && fileShareFragment.isAdded()) {
            fileShareFragment.showPlaybackControls(false);
            fileShareFragment.setPlayPauseIcon(false);
        }
        if (fileShareNotifHelper != null) {
            fileShareNotifHelper.clearNotification();
        }
    }

    private volatile long lastSyncPulseTime = 0;

    private void startPlaybackUpdateLoop() {
        if (playbackUpdateRunnable != null) {
            mainHandler.removeCallbacks(playbackUpdateRunnable);
        }
        playbackUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (syncMediaPlayer != null && isFileShareActive.get()) {
                    try {
                        boolean playing = syncMediaPlayer.isPlaying();
                        int pos = syncMediaPlayer.getCurrentPosition();
                        int dur = syncMediaPlayer.getDuration();
                        if (fileShareFragment != null && fileShareFragment.isAdded()) {
                            fileShareFragment.updatePlayback(pos, dur, playing);
                        }

                        // Host transmits sync pulses every 3 seconds to eliminate any playback drift
                        if (isFileShareHost && playing && System.currentTimeMillis() - lastSyncPulseTime >= 3000) {
                            lastSyncPulseTime = System.currentTimeMillis();
                            broadcastSyncPulse(currentPlayingQueueIndex, pos, System.currentTimeMillis());
                        }

                    } catch (Exception ignored) {}
                    mainHandler.postDelayed(this, 50);
                }
            }
        };
        mainHandler.post(playbackUpdateRunnable);
    }

    // ---------- SYNCHRONIZED PLAYBACK TIMING ----------

    /**
     * Changes the playback speed only while the player is actually playing. On a prepared/paused
     * player setPlaybackParams(speed≠0) is documented to START playback, which made peers start
     * early (F-11); in that case the speed is only remembered.
     */
    private void adjustPlaybackSpeed(float targetSpeed) {
        android.media.MediaPlayer mp = syncMediaPlayer;
        if (mp == null) return;
        try {
            if (!mp.isPlaying()) {
                pendingPlaybackSpeed = targetSpeed;
                return;
            }
            PlaybackParams current = null;
            try {
                current = mp.getPlaybackParams();
            } catch (Exception ignored) {}
            if (current == null) {
                current = new PlaybackParams().setSpeed(1.0f);
            }
            float currentSpeed = current.getSpeed();
            if (Math.abs(currentSpeed - targetSpeed) > 0.005f) {
                current.setSpeed(targetSpeed);
                mp.setPlaybackParams(current);
            }
        } catch (Exception ignored) {}
    }

    private void scheduleLocalPlay(long positionMs, long playAtMs) {
        mainHandler.post(() -> {
            if (pendingSchedulePlayRunnable != null) {
                mainHandler.removeCallbacks(pendingSchedulePlayRunnable);
                pendingSchedulePlayRunnable = null;
            }
            if (pendingSchedulePauseRunnable != null) {
                mainHandler.removeCallbacks(pendingSchedulePauseRunnable);
                pendingSchedulePauseRunnable = null;
            }
            final android.media.MediaPlayer mp = syncMediaPlayer;
            if (mp == null) return;
            try {
                // No speed change before start(): on a paused player it would start playback now
                long now = System.currentTimeMillis();
                if (now >= playAtMs) {
                    // Start time was in the past (e.g. preparation lag): seek once and start immediately
                    long timePassed = now - playAtMs;
                    long compensatedPos = positionMs + timePassed;
                    int dur = mp.getDuration();
                    if (dur > 0 && compensatedPos >= dur) compensatedPos = dur;
                    mp.seekTo((int) compensatedPos);
                    mp.start();
                    pendingPlaybackSpeed = 1.0f;
                    adjustPlaybackSpeed(1.0f);   // reset any speed remembered from before the pause
                    isSyncPlaying.set(true);
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setPlayPauseIcon(true);
                    }
                    updateFileNotification(true);
                } else {
                    mp.seekTo((int) positionMs);
                    long delay = playAtMs - now;
                    pendingSchedulePlayRunnable = () -> {
                        if (syncMediaPlayer == mp && isFileShareActive.get()) {
                            try {
                                mp.start();
                                pendingPlaybackSpeed = 1.0f;
                                adjustPlaybackSpeed(1.0f);
                                isSyncPlaying.set(true);
                                if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                    fileShareFragment.setPlayPauseIcon(true);
                                }
                                updateFileNotification(true);
                            } catch (Exception ignored) {}
                        }
                    };
                    mainHandler.postDelayed(pendingSchedulePlayRunnable, delay);
                }
            } catch (Exception e) {
                Log.e(TAG, "scheduleLocalPlay error: " + e.getMessage());
            }
        });
    }

    private void pauseLocalAt(long positionMs, long pauseAtMs) {
        mainHandler.post(() -> {
            final android.media.MediaPlayer mp = syncMediaPlayer;
            if (mp == null) return;
            if (pendingSchedulePauseRunnable != null) {
                mainHandler.removeCallbacks(pendingSchedulePauseRunnable);
                pendingSchedulePauseRunnable = null;
            }
            long now = System.currentTimeMillis();
            if (now >= pauseAtMs) {
                try {
                    // Speed back to 1.0 in the same main-thread step as pause(), while still
                    // playing, so no drift correction can slip in between (F-11)
                    adjustPlaybackSpeed(1.0f);
                    mp.pause();
                    pendingPlaybackSpeed = 1.0f;
                    isSyncPlaying.set(false);
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setPlayPauseIcon(false);
                    }
                    updateFileNotification(false);
                } catch (Exception ignored) {}
            } else {
                long delay = pauseAtMs - now;
                pendingSchedulePauseRunnable = () -> {
                    pendingSchedulePauseRunnable = null;
                    if (syncMediaPlayer == mp) {
                        try {
                            adjustPlaybackSpeed(1.0f);
                            mp.pause();
                            pendingPlaybackSpeed = 1.0f;
                            isSyncPlaying.set(false);
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.setPlayPauseIcon(false);
                            }
                            updateFileNotification(false);
                        } catch (Exception ignored) {}
                    }
                };
                mainHandler.postDelayed(pendingSchedulePauseRunnable, delay);
            }
        });
    }

    private void sendSyncCommand(byte cmd, long position, long playAt) {
        new Thread(() -> {
            try {
                DataOutputStream out = syncControlOut;
                if (out == null) return;
                synchronized (out) {
                    out.writeByte(cmd);
                    out.writeLong(position);
                    if (cmd == SYNC_PLAY || cmd == SYNC_SEEK || cmd == SYNC_PAUSE_CMD) {
                        out.writeLong(playAt);
                    }
                    out.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSyncCommand error: " + e.getMessage());
            }
        }, "SyncCmdSend").start();
    }

    // ---------- FILE SHARE CLEANUP ----------

    private void stopFileShare() {
        isFileShareActive.set(false);
        isSyncPlaying.set(false);
        stopPlayback();
        stopDiscoveryServer();

        if (playbackUpdateRunnable != null) {
            mainHandler.removeCallbacks(playbackUpdateRunnable);
            playbackUpdateRunnable = null;
        }

        if (pendingSchedulePlayRunnable != null) {
            mainHandler.removeCallbacks(pendingSchedulePlayRunnable);
            pendingSchedulePlayRunnable = null;
        }

        if (fileShareNotifHelper != null) {
            fileShareNotifHelper.clearNotification();
        }
        stopFileShareService();   // FIX_PLAN #14 (no-op unless the service runs in fileshare mode)

        for (FileSharePeer peer : connectedFileSharePeers) {
            peer.close();
        }
        connectedFileSharePeers.clear();

        closeQuietly(fileShareSocket);
        closeQuietly(fileShareServerSocket);
        closeQuietly(syncControlSocket);
        closeQuietly(syncControlServerSocket);
        fileShareSocket = null;
        fileShareServerSocket = null;
        syncControlSocket = null;
        syncControlServerSocket = null;
        syncControlOut = null;
        connectedPeerIp = null;

        peerReady.set(false);
        clockOffset = 0;

        for (FileQueueItem item : fileShareQueue) {
            if (item.getLocalFilePath() != null) {
                try { new File(item.getLocalFilePath()).delete(); } catch (Exception ignored) {}
            }
        }
        fileShareQueue.clear();
        currentPlayingQueueIndex = -1;
        pendingPlayIndex = -1;

        mainHandler.post(() -> {
            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                fileShareFragment.resetUI();
            }
        });

        Log.d(TAG, "File share session stopped");
    }

    // ---------- UTILITY METHODS ----------

    private String getAudioFormatDescription(String fileName, String mimeType) {
        String ext = getFileExtension(fileName).toLowerCase();
        if ("flac".equals(ext) || (mimeType != null && mimeType.contains("flac"))) {
            return "FLAC Lossless";
        } else if ("mp3".equals(ext) || (mimeType != null && mimeType.contains("mpeg"))) {
            return "MP3 Audio";
        } else if ("m4a".equals(ext) || "mp4".equals(ext) || "aac".equals(ext) || (mimeType != null && mimeType.contains("mp4"))) {
            return "AAC / M4A Audio";
        } else if ("wav".equals(ext) || (mimeType != null && mimeType.contains("wav"))) {
            return "WAV Lossless PCM";
        } else if ("ogg".equals(ext) || "opus".equals(ext) || (mimeType != null && (mimeType.contains("ogg") || mimeType.contains("opus")))) {
            return "OGG / OPUS Audio";
        }
        return ext.toUpperCase() + " Audio";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}