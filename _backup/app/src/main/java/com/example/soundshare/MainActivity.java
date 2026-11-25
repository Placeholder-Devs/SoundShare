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
            {44100, 1, 32, 16, 0, 0},
            {44100, 2, 32, 16, 0, 0},
            {48000, 2, 5000, 16, 0, 0},
            {48000, 2, 4800, 24, 0, 0},
            {48000, 2, 9600, 24, 0, 0},
            {96000, 2, 192000, 24, 0, 0},
            {192000, 2, 192000, 24, 0, 0},
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
    private HiResAudioHelper.Capabilities deviceCaps;
    private int[] availablePresetIndices;

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
                            if (isFinishing() || isDestroyed()) return;
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

                    Intent svc = new Intent(this, AudioCaptureService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
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
                        Intent svc = new Intent(this, AudioCaptureService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(svc);
                        } else {
                            startService(svc);
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
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        stopStreaming();
                        updateStatus("Media projection stopped");
                    }
                });
            }
        }, mainHandler);
    }

    // ---------- LIFECYCLE ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enableMaxRefreshRate();

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setupNotifications();

        // Setup fragments
        streamFragment = new StreamFragment();
        receiveFragment = new ReceiveFragment();
        settingsFragment = new SettingsFragment();
        duplexFragment = new DuplexFragment();
        fileShareFragment = new FileShareFragment();

        // Setup bottom navigation
        bottomNav = findViewById(R.id.bottomNav);

        // Add all fragments once (show/hide pattern preserves state)
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings")
                .hide(settingsFragment)
                .add(R.id.fragmentContainer, fileShareFragment, "fileshare")
                .hide(fileShareFragment)
                .add(R.id.fragmentContainer, duplexFragment, "duplex")
                .hide(duplexFragment)
                .add(R.id.fragmentContainer, receiveFragment, "receive")
                .hide(receiveFragment)
                .add(R.id.fragmentContainer, streamFragment, "stream")
                .commit();
        activeFragment = streamFragment;

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

        // Request storage permission for album art access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                    if (SettingsFragment.isKeepScreenOn(MainActivity.this)) {
                        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
            }
        });
    }

    private int getAudioPerformanceMode() {
        int mode = SettingsFragment.getPerfMode(this);
        if (mode == 1) return AudioTrack.PERFORMANCE_MODE_NONE;
        if (mode == 2) return AudioTrack.PERFORMANCE_MODE_POWER_SAVING;
        return AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
    }

    private int getCalculatedBufferSize(int defaultMinBuf) {
        int userFrames = SettingsFragment.getBufferSize(this);
        if (userFrames > 0) {
            int userBytes = userFrames * 4; // 16-bit Stereo PCM = 4 bytes/frame
            return Math.max(userBytes, defaultMinBuf);
        }
        return defaultMinBuf;
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

    private void checkNotificationListenerPermission() {
        String pkgName = getPackageName();
        java.util.Set<String> enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this);
        if (enabled.contains(pkgName)) return; // Already granted

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
    }

    // ===== FRAGMENT CALLBACK IMPLEMENTATIONS =====

    @Override
    public void onStartStreaming() {
        if (isStreaming.get()) {
            stopStreaming();
            return;
        }
        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
                return;
            }
        }
        captureResultLauncher.launch(projectionManager.createScreenCaptureIntent());
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
        int defaultPos = 0;
        for (int i = 0; i < availablePresetIndices.length; i++) {
            if (availablePresetIndices[i] == 2) { defaultPos = i; break; }
        }
        spinner.setSelection(Math.min(defaultPos, filteredNames.size() - 1));
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
                // Update spinner in the stream fragment if visible
                if (streamFragment != null && streamFragment.isAdded()) {
                    // Fragment will request spinner setup via callback
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
                MediaMetadataHelper.sendPlay(this);
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
                MediaMetadataHelper.sendPause(this);
                updateReceiverNotification();
            }
        } else if (isStreaming.get()) {
            if (isPaused.get()) {
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
                MediaMetadataHelper.sendPlay(this);
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
                MediaMetadataHelper.sendPause(this);
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
                DataOutputStream dout = new DataOutputStream(ps.getOutputStream());
                synchronized (dout) {
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

        // Send HIGH QUALITY album art — 1024px JPEG quality 95
        // JPEG at 95 quality stays under 500KB while looking sharp
        byte[] artBytes = null;
        if (media.albumArt != null) {
            try {
                int origW = media.albumArt.getWidth();
                int origH = media.albumArt.getHeight();
                Bitmap toSend;

                if (origW >= 1024 && origH >= 1024) {
                    // Already large — scale to exactly 1024 to control size
                    toSend = Bitmap.createScaledBitmap(
                            media.albumArt, 1024, 1024, true);
                } else if (origW >= 512) {
                    // Medium — send at original size
                    toSend = media.albumArt;
                } else {
                    // Small — scale up to 512
                    toSend = Bitmap.createScaledBitmap(
                            media.albumArt, 512, 512, true);
                }

                java.io.ByteArrayOutputStream bos =
                        new java.io.ByteArrayOutputStream();
                toSend.compress(Bitmap.CompressFormat.JPEG, 95, bos);
                artBytes = bos.toByteArray();
            } catch (Exception ignored) {}
        }
        final byte[] art = artBytes;

        new Thread(() -> {
            for (ChatClient cc : chatClients) {
                if (cc.connected.get()) {
                    try {
                        writeMetadataMessage(cc.out, title, artist, album,
                                art, durationMs, positionMs);
                    } catch (IOException e) {
                        cc.close();
                        chatClients.remove(cc);
                    }
                }
            }
        }, "MetaBroadcast").start();
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

        if (SettingsFragment.isKeepScreenOn(this)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
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
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            updateStatus("Permission granted. Tap again.");
        else updateStatus("Permission denied");
    }

    // ---------- PROTOCOL HEADER (v2 - 28 bytes) ----------
    private void writeFormatHeader(OutputStream out, AudioConfig config) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.putInt(HEADER_MAGIC);                          // 0-3
        buf.putInt(HEADER_VERSION);                        // 4-7
        buf.putInt(config.sampleRate);                     // 8-11
        buf.putShort((short) config.bitsPerSample);       // 12-13
        buf.putShort((short) config.channels);            // 14-15
        buf.putInt(config.framesPerBuffer);                // 16-19
        buf.putInt(config.chunkSize);                      // 20-23
        buf.putShort((short) config.compressionType);     // 24-25
        buf.put((byte) (config.isFloat ? 1 : 0));          // 26
        buf.put((byte) 0);                                 // 27 reserved
        out.write(header);
        out.flush();
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

        Log.i(TAG, String.format(Locale.US,
                "Header: v%d %dHz %dch %dbit comp=%d float=%d",
                version, sr, ch, bps, comp, floatFlag));

        return AudioConfig.fromHeader(sr, ch, fpb, bps, comp, floatFlag);
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
        });
    }

    @SuppressLint("deprecation")
    private String getDeviceIpAddress() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) {
                    Network net = cm.getActiveNetwork();
                    if (net != null) {
                        LinkProperties props = cm.getLinkProperties(net);
                        if (props != null) {
                            for (LinkAddress la : props.getLinkAddresses()) {
                                InetAddress a = la.getAddress();
                                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                                    String h = a.getHostAddress();
                                    if (h != null) return h;
                                }
                            }
                        }
                    }
                }
            }
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0)
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            ipInt & 0xff, (ipInt >> 8) & 0xff,
                            (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        String h = a.getHostAddress();
                        if (h != null) return h;
                    }
        } catch (Exception e) {
            Log.e(TAG, "IP error", e);
        }
        return "Not connected";
    }

    private void copyToClipboard(String text) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Device IP", text));
        Toast.makeText(this, "IP copied: " + text, Toast.LENGTH_SHORT).show();
    }

    // ---------- UI HELPERS ----------
    private void updateStatus(String msg) {
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
            try {
                ServerSocket ss = new ServerSocket();
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
                closeQuietly(chatServerSocket);
                chatServerSocket = null;
                for (ChatClient cc : chatClients) cc.close();
                chatClients.clear();
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

    private void startChatClient(String serverIp) {
        chatClientThread = new Thread(() -> {
            Socket sock = null;
            try {
                Thread.sleep(500);
                sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(serverIp, CHAT_PORT), 5000);
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
                while (isReceiving.get()) {
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
                        if (artLen > 0 && artLen < 1_500_000) {
                            byte[] artBytes = new byte[artLen];
                            din.readFully(artBytes);
                            art = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artLen);
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
                                at.pause();
                                isPaused.set(true);
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
                                at.play();
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
                chatClientOut = null;
                closeQuietly(sock);
                chatSocket = null;
                addSystemChatMessage("Chat disconnected");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (receiveFragment != null && receiveFragment.isAdded()) {
                        receiveFragment.updateChatStatus("Disconnected", R.color.accent_red);
                    }
                });
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
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PING_PORT));
                ss.setSoTimeout(1000);
                pingServerSocket = ss;
                while (isStreaming.get()) {
                    try {
                        Socket client = ss.accept();
                        client.setTcpNoDelay(true);
                        client.setSoTimeout(3000);
                        new Thread(() -> handlePingClient(client),
                                "PingHandler").start();
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (isStreaming.get()) Log.e(TAG, "Ping server error", e);
            } finally {
                closeQuietly(pingServerSocket);
                pingServerSocket = null;
            }
        }, "PingServerThread");
        pingServerThread.start();
    }

    private void handlePingClient(Socket client) {
        try {
            DataInputStream din = new DataInputStream(client.getInputStream());
            DataOutputStream dout = new DataOutputStream(client.getOutputStream());
            while (isStreaming.get()) {
                byte cmd = din.readByte();
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
                            ar.stop();
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
                            ar.startRecording();
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

    private void startPingClient(String serverIp) {
        pingClientThread = new Thread(() -> {
            Socket sock = null;
            try {
                Thread.sleep(300);
                sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.setSoTimeout(2000);
                sock.connect(new InetSocketAddress(serverIp, PING_PORT), 3000);
                pingSocket = sock;
                DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
                DataInputStream din = new DataInputStream(sock.getInputStream());
                long smoothedMs = -1;
                while (isReceiving.get()) {
                    try {
                        long startNs = System.nanoTime();
                        dout.writeByte(PING_REQUEST);
                        dout.flush();
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
                                dout.writeByte(LATENCY_REPORT);
                                dout.writeLong(smoothedMs);
                                dout.flush();
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
                pingSocket = null;
            }
        }, "PingClientThread");
        pingClientThread.start();
    }

    private void stopPingClient() {
        closeQuietly(pingSocket);
        pingSocket = null;
    }

    // ==========================================================
    //  STREAMING (with FLAC + Hi-Res support)
    // ==========================================================

    @SuppressLint("MissingPermission")
    private void startStreaming() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus("Requires Android 10+");
            return;
        }

        final AudioConfig config = new AudioConfig(
                QUALITY_PRESETS[selectedPreset], selectedPreset);
        activeConfig.set(config);

        if (config.useFLAC && !FlacCodec.isFlacEncoderAvailable()) {
            updateStatus("FLAC encoder not available on this device");
            return;
        }

        isStreaming.set(true);
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

        // Accept thread
        acceptThread = new Thread(() -> {
            try {
                ServerSocket ls = new ServerSocket();
                ls.setReuseAddress(true);
                int streamPort = getActiveStreamPort();
                try {
                    ls.bind(new InetSocketAddress(streamPort));
                } catch (java.net.BindException e) {
                    updateStatus("Port " + streamPort + " in use");
                    isStreaming.set(false);
                    resetUI();
                    return;
                }
                ls.setSoTimeout(1000);
                serverSocket = ls;
                updateStatus("Waiting on " + getDeviceIpAddress() + ":" + streamPort);

                while (isStreaming.get()) {
                    try {
                        Socket client = ls.accept();
                        client.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                        client.setSendBufferSize(config.socketBufferSize);
                        client.setKeepAlive(true);
                        client.setTrafficClass(0x10);

                        ClientConnection conn = new ClientConnection(client);
                        writeFormatHeader(conn.outputStream, config);
                        connectedClients.add(conn);
                        receiverCount.incrementAndGet();
                        updateReceiverCount();
                        updateStatus("Streaming to " + receiverCount.get()
                                + " receiver(s)");
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        if (isStreaming.get()) Log.e(TAG, "Accept error", e);
                    }
                }
            } catch (Exception e) {
                if (isStreaming.get()) {
                    Log.e(TAG, "Server error", e);
                    updateStatus("Server error: " + e.getMessage());
                }
            } finally {
                acceptStopped.countDown();
            }
        }, "AcceptThread");
        acceptThread.start();

        // Stream thread
        streamThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord lr = null;
            FlacCodec.Encoder flacEncoder = null;

            try {
                Thread.sleep(200);
                if (!isStreaming.get()) return;

                AudioPlaybackCaptureConfiguration cc =
                        new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();

                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(config.encoding)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(config.channelInMask)
                        .build();

                int minBuf = AudioRecord.getMinBufferSize(
                        config.sampleRate, config.channelInMask, config.encoding);
                int recBuf = Math.max(minBuf,
                        config.chunkSize * (config.presetIndex <= 1 ? 2 : 4));
                recBuf = getCalculatedBufferSize(recBuf);

                lr = new AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(recBuf)
                        +.setAudioPlaybackCaptureConfig(cc)
                        .build();
                recorder = lr;

                if (lr.getState() != AudioRecord.STATE_INITIALIZED) {
                    updateStatus("AudioRecord init failed");
                    isStreaming.set(false);
                    return;
                }

                // Initialize FLAC encoder if needed
                if (config.useFLAC) {
                    flacEncoder = new FlacCodec.Encoder(
                            config.sampleRate, config.channels,
                            config.bitsPerSample, config.framesPerBuffer);
                    if (!flacEncoder.start()) {
                        updateStatus("FLAC encoder failed to start");
                        isStreaming.set(false);
                        return;
                    }
                    Log.i(TAG, "FLAC lossless encoding enabled");
                }

                lr.startRecording();

                byte[] pcmBuffer = new byte[config.chunkSize];
                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());
                bytesSentThisSecond.set(0);

                while (isStreaming.get()) {
                    if (isPaused.get()) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }
                    int read = lr.read(pcmBuffer, 0, pcmBuffer.length);
                    if (read <= 0 || connectedClients.isEmpty()) continue;

                    byte[] sendData;
                    int sendLength;

                    if (config.useFLAC && flacEncoder != null) {
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
                    if (config.useFLAC) {
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
                if (isStreaming.get()) {
                    Log.e(TAG, "Stream error", e);
                    updateStatus("Stream error: " + e.getMessage());
                }
            } finally {
                isStreaming.set(false);
                stopPingServer();
                stopChatServer();
                closeQuietly(serverSocket);
                serverSocket = null;
                try {
                    acceptStopped.await();
                } catch (InterruptedException ignored) {
                }

                if (flacEncoder != null) flacEncoder.release();

                cleanupRecorder(lr);
                recorder = null;
                for (ClientConnection c : connectedClients) c.close();
                connectedClients.clear();
                receiverCount.set(0);
                releaseProjection();
                stopForegroundService();
                resetUI();
                updateStatus("Streaming stopped");
            }
        }, "AudioStreamThread");
        streamThread.start();
    }

    private void stopStreaming() {
        if (!isStreaming.compareAndSet(true, false)) return;
        updateStatus("Stopping...");
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

    private void startReceiving(String streamerIp) {
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
                socket.setTcpNoDelay(SettingsFragment.isTcpNoDelay(MainActivity.this));
                int bufMul = SettingsFragment.getSocketMultiplier(MainActivity.this);
                socket.setReceiveBufferSize(Math.max(16384, 8192 * bufMul));
                socket.setTrafficClass(0x10);
                socket.connect(new InetSocketAddress(streamerIp, getActiveStreamPort()),
                        SettingsFragment.getConnTimeoutMs(MainActivity.this));
                activeSocket = socket;

                rawIn = new BufferedInputStream(socket.getInputStream(), Math.max(16384, 8192 * bufMul));
                AudioConfig config = readFormatHeader(rawIn);
                if (config == null) {
                    updateStatus("Invalid stream format");
                    return;
                }
                activeConfig.set(config);

                if (config.useFLAC && !FlacCodec.isFlacDecoderAvailable()) {
                    updateStatus("FLAC decoder not available on this device");
                    return;
                }

                updateReceivingUI(config, streamerIp);
                updateReceiverNotification();
                startPingClient(streamerIp);
                startChatClient(streamerIp);

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
                        updateStatus("FLAC decoder failed to start");
                        return;
                    }
                    Log.i(TAG, "FLAC lossless decoding enabled");
                    dataIn = new DataInputStream(rawIn);
                }

                int minBuf = AudioTrack.getMinBufferSize(
                        config.sampleRate, config.channelOutMask, config.encoding);
                int playBuf = Math.max(minBuf * bufMul, config.chunkSize * 2);
                playBuf = getCalculatedBufferSize(playBuf);

                lt = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(config.encoding)
                                .setSampleRate(config.sampleRate)
                                .setChannelMask(config.channelOutMask)
                                .build())
                        .setBufferSizeInBytes(playBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(getAudioPerformanceMode())
                        .build();
                audioTrack = lt;
                lt.play();

                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());

                if (config.useFLAC) {
                    receiveFlacLoop(dataIn, lt, flacDecoder, config);
                } else {
                    receivePcmLoop(rawIn, lt, config);
                }

            } catch (java.net.ConnectException e) {
                updateStatus("Cannot connect to " + streamerIp);
            } catch (SocketTimeoutException e) {
                updateStatus("Connection timed out");
            } catch (IOException e) {
                if (isReceiving.get()) {
                    Log.e(TAG, "Receive error", e);
                    updateStatus("Receive error: " + e.getMessage());
                }
            } catch (Exception e) {
                if (isReceiving.get()) {
                    Log.e(TAG, "Error", e);
                    updateStatus("Error: " + e.getMessage());
                }
            } finally {
                isReceiving.set(false);
                stopPingClient();
                stopChatClient();
                if (flacDecoder != null) flacDecoder.release();
                cleanupTrack(lt);
                audioTrack = null;
                closeQuietly(rawIn);
                closeQuietly(socket);
                activeSocket = null;
                stopReceiverService();
                resetUI();
                updateStatus("Receiving stopped");
            }
        }, "ReceiveThread");
        receiveThread.start();
    }

    private void receiveFlacLoop(DataInputStream dataIn, AudioTrack track,
                                 FlacCodec.Decoder decoder, AudioConfig config)
            throws IOException {

        byte[] compressedBuf = new byte[config.chunkSize];

        while (isReceiving.get()) {
            int frameLen = dataIn.readInt();
            if (frameLen <= 0 || frameLen > config.chunkSize * 2) {
                Log.w(TAG, "Invalid FLAC frame length: " + frameLen);
                continue;
            }

            if (compressedBuf.length < frameLen) {
                compressedBuf = new byte[frameLen];
            }

            dataIn.readFully(compressedBuf, 0, frameLen);

            byte[] pcmData = decoder.decode(compressedBuf, frameLen);
            if (pcmData != null && pcmData.length > 0 && !isPaused.get()) {
                SettingsFragment.applyDspProcessing(pcmData, 0, pcmData.length,
                        SettingsFragment.getAudioGainMultiplier(MainActivity.this),
                        SettingsFragment.getChannelMode(MainActivity.this));
                track.write(pcmData, 0, pcmData.length);
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

    private void receivePcmLoop(InputStream in, AudioTrack track,
                                AudioConfig config) throws IOException {

        byte[] buffer = new byte[config.chunkSize];

        while (isReceiving.get()) {
            int totalRead = 0;
            while (totalRead < buffer.length && isReceiving.get()) {
                int read = in.read(buffer, totalRead,
                        buffer.length - totalRead);
                if (read == -1) {
                    if (isReceiving.get()) updateStatus("Streamer disconnected");
                    isReceiving.set(false);
                    break;
                }
                totalRead += read;
            }

            if (totalRead == buffer.length && isReceiving.get()) {
                if (!isPaused.get()) {
                    SettingsFragment.applyDspProcessing(buffer, 0, totalRead,
                            SettingsFragment.getAudioGainMultiplier(MainActivity.this),
                            SettingsFragment.getChannelMode(MainActivity.this));
                    track.write(buffer, 0, totalRead);
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
        stopPingClient();
        stopChatClient();
        Socket s = activeSocket;
        if (s != null) {
            closeQuietly(s);
            activeSocket = null;
        }
        stopReceiverService();
    }

    // ---------- SERVICE HELPERS ----------

    private void startReceiverService() {
        try {
            Intent svc = new Intent(this, AudioReceiveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start receiver service: " + e.getMessage());
        }
    }

    private void stopReceiverService() {
        try {
            stopService(new Intent(this, AudioReceiveService.class));
        } catch (Exception ignored) {
        }
    }

    // ---------- CLEANUP ----------
    private void stopForegroundService() {
        try {
            stopService(new Intent(this, AudioCaptureService.class));
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
        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
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
        if (!hasRecordAudioPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
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
            try {
                Thread.sleep(200);
                if (!isDuplexActive.get() || socket.isClosed()) return;

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

                duplexRecorder = new AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(recBuf)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build();

                if (duplexRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    mainHandler.post(() ->
                            duplexFragment.updateStatus("AudioRecord init failed"));
                    return;
                }
                duplexRecorder.startRecording();

                OutputStream out = socket.getOutputStream();
                // Send header: sampleRate(4) + channels(1) + encoding(1)
                byte[] header = new byte[6];
                header[0] = (byte) ((sampleRate >> 24) & 0xFF);
                header[1] = (byte) ((sampleRate >> 16) & 0xFF);
                header[2] = (byte) ((sampleRate >> 8) & 0xFF);
                header[3] = (byte) (sampleRate & 0xFF);
                header[4] = (byte) channels;
                header[5] = (byte) encoding;
                out.write(header);
                out.flush();

                int bytesPerFrame = channels * bytesPerSample;
                int chunkSize = Math.max(minRecBuf, 4096);
                chunkSize = (chunkSize / bytesPerFrame) * bytesPerFrame;
                byte[] buffer = new byte[chunkSize];
                while (isDuplexActive.get() && !socket.isClosed()) {
                    int read = duplexRecorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        duplexBytesSent.addAndGet(read);
                    }
                }
            } catch (Exception e) {
                if (isDuplexActive.get()) {
                    mainHandler.post(() ->
                            duplexFragment.updateStatus("Send error: " + e.getMessage()));
                }
            } finally {
                cleanupRecorder(duplexRecorder);
                duplexRecorder = null;
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
                            SettingsFragment.applyDspProcessing(buffer, 0, alignedBytes,
                                    SettingsFragment.getAudioGainMultiplier(MainActivity.this),
                                    SettingsFragment.getChannelMode(MainActivity.this));
                            track.write(buffer, 0, alignedBytes);
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
                String lastTitle = "";
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

                        boolean trackChanged = !title.equals(lastTitle);

                        // Only send album art on track change to save bandwidth
                        byte[] artBytes = null;
                        if (media.albumArt != null && trackChanged) {
                            try {
                                int origW = media.albumArt.getWidth();
                                Bitmap toSend;
                                if (origW >= 1024) {
                                    toSend = Bitmap.createScaledBitmap(
                                            media.albumArt, 1024, 1024, true);
                                } else if (origW >= 512) {
                                    toSend = media.albumArt;
                                } else {
                                    toSend = Bitmap.createScaledBitmap(
                                            media.albumArt, 512, 512, true);
                                }
                                java.io.ByteArrayOutputStream bos =
                                        new java.io.ByteArrayOutputStream();
                                toSend.compress(
                                        Bitmap.CompressFormat.JPEG, 95, bos);
                                artBytes = bos.toByteArray();
                            } catch (Exception ignored) {}
                        }

                        try {
                            writeMetadataMessage(out, title, artist, album,
                                    artBytes, durationMs, positionMs);
                            lastTitle = title;
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
                        if (artLen > 0 && artLen < 1_500_000) {
                            byte[] artBytes = new byte[artLen];
                            duplexChatIn.readFully(artBytes);
                            art = android.graphics.BitmapFactory.decodeByteArray(
                                    artBytes, 0, artLen);
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
                            MediaMetadataHelper.sendPause(MainActivity.this);
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
                            MediaMetadataHelper.sendPlay(MainActivity.this);
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
        new Thread(() -> {
            DatagramSocket scanSocket = null;
            try {
                scanSocket = new DatagramSocket();
                scanSocket.setBroadcast(true);
                scanSocket.setSoTimeout(2500);

                byte[] reqBytes = queryMsg.getBytes(StandardCharsets.UTF_8);
                DatagramPacket sendPacket = new DatagramPacket(
                        reqBytes, reqBytes.length,
                        InetAddress.getByName("255.255.255.255"), FILE_DISCOVERY_PORT);
                scanSocket.send(sendPacket);

                byte[] recvBuf = new byte[256];
                DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
                scanSocket.receive(recvPacket);

                String resp = new String(recvPacket.getData(), 0, recvPacket.getLength(), StandardCharsets.UTF_8);
                if (resp.startsWith(expectedPrefix)) {
                    String hostIp = recvPacket.getAddress().getHostAddress();
                    if (onFound != null) onFound.accept(hostIp);
                    return;
                }
                if (onNotFound != null) onNotFound.run();
            } catch (SocketTimeoutException e) {
                if (onNotFound != null) onNotFound.run();
            } catch (Exception e) {
                Log.e(TAG, "Scan error: " + e.getMessage(), e);
                if (onNotFound != null) onNotFound.run();
            } finally {
                if (scanSocket != null) {
                    try { scanSocket.close(); } catch (Exception ignored) {}
                }
            }
        }, "UniversalScanner").start();
    }

    private void startUniversalDiscoveryServer(String mode) {
        activeDiscoveryHostMode = mode;
        if (discoveryThread != null && discoveryThread.isAlive()) return;

        discoveryThread = new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket(FILE_DISCOVERY_PORT);
                byte[] buffer = new byte[256];
                while (activeDiscoveryHostMode != null) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    discoverySocket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    String replyMsg = null;

                    if ("SS_DISCOVER_STREAM".equals(msg) && "STREAM".equals(activeDiscoveryHostMode)) {
                        replyMsg = "SS_HOST_STREAM:" + cachedDeviceIp;
                    } else if ("SS_DISCOVER_DUPLEX".equals(msg) && "DUPLEX".equals(activeDiscoveryHostMode)) {
                        replyMsg = "SS_HOST_DUPLEX:" + cachedDeviceIp;
                    } else if ("SS_DISCOVER_FILE".equals(msg) && "FILE".equals(activeDiscoveryHostMode)) {
                        replyMsg = "SS_HOST_FILE:" + cachedDeviceIp;
                    } else if ("SS_DISCOVER_ALL".equals(msg) && activeDiscoveryHostMode != null) {
                        replyMsg = "SS_HOST_" + activeDiscoveryHostMode + ":" + cachedDeviceIp;
                    }

                    if (replyMsg != null) {
                        byte[] respBytes = replyMsg.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket reply = new DatagramPacket(
                                respBytes, respBytes.length,
                                packet.getAddress(), packet.getPort());
                        discoverySocket.send(reply);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Discovery server ended: " + e.getMessage());
            } finally {
                if (discoverySocket != null) {
                    try { discoverySocket.close(); } catch (Exception ignored) {}
                    discoverySocket = null;
                }
            }
        }, "UniversalDiscoveryServer");
        discoveryThread.start();
    }

    private void stopDiscoveryServer() {
        activeDiscoveryHostMode = null;
        if (discoverySocket != null) {
            try { discoverySocket.close(); } catch (Exception ignored) {}
            discoverySocket = null;
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
        isFileShareHost = true;
        isFileShareActive.set(true);
        connectedFileSharePeers.clear();
        fileShareFragment.showConnectedUI();
        fileShareFragment.setStatus("Host waiting for receivers on port " + SYNC_CTRL_PORT + "...");
        startUniversalDiscoveryServer("FILE");
        startSyncControlServer();
    }

    @Override
    public void onFileShareJoin(String ip) {
        isFileShareHost = false;
        isFileShareActive.set(true);
        connectedPeerIp = ip;
        fileShareFragment.showConnectedUI();
        fileShareFragment.setStatus("Connecting to host " + ip + "...");
        startSyncControlClient(ip);
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
        for (android.net.Uri uri : uris) {
            String name = "Audio Track";
            long size = 0;
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameIdx >= 0) name = cursor.getString(nameIdx);
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
                cursor.close();
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
            String trackId = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 10000);

            FileQueueItem queueItem = new FileQueueItem(
                    trackId, name, artist, album, size, mimeType, durMs, durationStr, formatDesc,
                    uri, null, true);

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
            sendQueueAddOverStream(peer.dos, item);
        }
    }

    private void broadcastTrackChange(int index, long playAt, FileSharePeer excludePeer) {
        for (FileSharePeer peer : connectedFileSharePeers) {
            if (peer == excludePeer) continue;
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

            InputStream fileIn;
            if (item.getLocalUri() != null) {
                fileIn = getContentResolver().openInputStream(item.getLocalUri());
            } else if (item.getLocalFilePath() != null) {
                fileIn = new FileInputStream(item.getLocalFilePath());
            } else {
                return;
            }

            if (fileIn == null) return;

            byte[] buffer = new byte[8192];
            long sent = 0;
            long lastUpdate = System.currentTimeMillis();
            long lastSentForSpeed = 0;
            long fileSize = item.getFileSize();

            int read;
            while ((read = fileIn.read(buffer)) != -1 && isFileShareActive.get()) {
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
            fileIn.close();

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

                InputStream fileIn = getContentResolver().openInputStream(item.getLocalUri());
                if (fileIn == null) throw new IOException("Cannot open local audio stream");

                byte[] buffer = new byte[8192];
                long sent = 0;
                long lastUpdate = System.currentTimeMillis();
                long lastSentForSpeed = 0;
                long fileSize = item.getFileSize();

                int read;
                while ((read = fileIn.read(buffer)) != -1 && isFileShareActive.get()) {
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
                fileIn.close();

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
                        receiverSocket.connect(new InetSocketAddress(targetIp, targetPort), 3000);
                        connected = true;
                    } catch (Exception e) {
                        Thread.sleep(150);
                    }
                }
                if (!connected) throw new IOException("Failed to connect to sender transfer socket on " + targetIp + ":" + targetPort);

                receiverSocket.setTcpNoDelay(true);
                receiverSocket.setReceiveBufferSize(65536);

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

                File outFile = new File(getCacheDir(), "fileshare_" + trackId + "_" + fileName);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buffer = new byte[8192];
                long received = 0;
                long lastUpdate = System.currentTimeMillis();
                long lastRecvForSpeed = 0;

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
                fos.close();

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
            try {
                syncControlServerSocket = new ServerSocket(SYNC_CTRL_PORT);
                while (isFileShareActive.get()) {
                    Socket client = syncControlServerSocket.accept();
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
                if (isFileShareActive.get()) {
                    Log.e(TAG, "SyncControlServer error: " + e.getMessage());
                }
            } finally {
                closeQuietly(syncControlServerSocket);
                syncControlServerSocket = null;
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
                socket.connect(new InetSocketAddress(hostIp, SYNC_CTRL_PORT), 5000);
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
                            int pulseIdx = dis.readInt();
                            long hostPos = dis.readLong();
                            long hostTime = dis.readLong();
                            if (pulseIdx >= 0 && pulseIdx < fileShareQueue.size()) {
                                FileQueueItem item = fileShareQueue.get(pulseIdx);
                                long now = System.currentTimeMillis();
                                long hostTimeInClientClock = hostTime - clockOffset;
                                long expectedPos = hostPos + (now - hostTimeInClientClock);

                                if (item.getLocalFilePath() != null && item.isReady()) {
                                    if (syncMediaPlayer == null || currentPlayingQueueIndex != pulseIdx) {
                                        // Auto-catchup: receiver has file ready but hasn't started playing pulseIdx yet!
                                        currentPlayingQueueIndex = pulseIdx;
                                        playQueueTrack(pulseIdx, now + 50);
                                        scheduleLocalPlay(expectedPos, now + 50);
                                    } else if (!syncMediaPlayer.isPlaying() || !isSyncPlaying.get()) {
                                        // Receiver player is initialized but paused/stalled: resume it in sync!
                                        scheduleLocalPlay(expectedPos, now);
                                    } else {
                                        // Player is actively playing: perform seamless drift micro-correction
                                        try {
                                            long localPos = syncMediaPlayer.getCurrentPosition();
                                            long signedDrift = expectedPos - localPos;
                                            long absDrift = Math.abs(signedDrift);

                                            mainHandler.post(() -> {
                                                if (syncMediaPlayer != null && isSyncPlaying.get()) {
                                                    try {
                                                        if (absDrift > 600) {
                                                            syncMediaPlayer.seekTo((int) expectedPos);
                                                            adjustPlaybackSpeed(1.0f);
                                                        } else if (absDrift > 35) {
                                                            if (signedDrift > 0) {
                                                                adjustPlaybackSpeed(1.02f);
                                                            } else {
                                                                adjustPlaybackSpeed(0.98f);
                                                            }
                                                        } else {
                                                            adjustPlaybackSpeed(1.0f);
                                                        }
                                                    } catch (Exception ignored) {}
                                                }
                                            });
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
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
                    stopFileShare();
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
        mainHandler.post(() -> {
            if (index < 0 || index >= fileShareQueue.size()) return;
            FileQueueItem item = fileShareQueue.get(index);
            currentPlayingQueueIndex = index;

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

                // Extract embedded album artwork
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    if (item.getLocalUri() != null) {
                        mmr.setDataSource(this, item.getLocalUri());
                    } else if (item.getLocalFilePath() != null) {
                        mmr.setDataSource(item.getLocalFilePath());
                    }
                    byte[] artBytes = mmr.getEmbeddedPicture();
                    if (artBytes != null && artBytes.length > 0) {
                        currentFileAlbumArt = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
                    } else {
                        currentFileAlbumArt = null;
                    }
                    mmr.release();
                } catch (Exception ignored) {
                    currentFileAlbumArt = null;
                }

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
                scheduleLocalPlay(0, playAtMs);

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

    private void adjustPlaybackSpeed(float targetSpeed) {
        if (syncMediaPlayer == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PlaybackParams current = null;
                try {
                    current = syncMediaPlayer.getPlaybackParams();
                } catch (Exception ignored) {}
                if (current == null) {
                    current = new PlaybackParams();
                }
                float currentSpeed = current.getSpeed();
                if (Math.abs(currentSpeed - targetSpeed) > 0.005f) {
                    current.setSpeed(targetSpeed);
                    syncMediaPlayer.setPlaybackParams(current);
                }
            }
        } catch (Exception ignored) {}
    }

    private void scheduleLocalPlay(long positionMs, long playAtMs) {
        mainHandler.post(() -> {
            if (pendingSchedulePlayRunnable != null) {
                mainHandler.removeCallbacks(pendingSchedulePlayRunnable);
                pendingSchedulePlayRunnable = null;
            }
            if (syncMediaPlayer == null) return;
            try {
                adjustPlaybackSpeed(1.0f);
                long now = System.currentTimeMillis();
                if (now >= playAtMs) {
                    // Start time was in the past (e.g. preparation lag): seek once and start immediately
                    long timePassed = now - playAtMs;
                    long compensatedPos = positionMs + timePassed;
                    int dur = syncMediaPlayer.getDuration();
                    if (dur > 0 && compensatedPos >= dur) compensatedPos = dur;
                    syncMediaPlayer.seekTo((int) compensatedPos);
                    syncMediaPlayer.start();
                    isSyncPlaying.set(true);
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setPlayPauseIcon(true);
                    }
                    updateFileNotification(true);
                } else {
                    syncMediaPlayer.seekTo((int) positionMs);
                    long delay = playAtMs - now;
                    pendingSchedulePlayRunnable = () -> {
                        if (syncMediaPlayer != null && isFileShareActive.get()) {
                            try {
                                syncMediaPlayer.start();
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
            if (syncMediaPlayer == null) return;
            adjustPlaybackSpeed(1.0f);
            long now = System.currentTimeMillis();
            if (now >= pauseAtMs) {
                try {
                    syncMediaPlayer.pause();
                    isSyncPlaying.set(false);
                    if (fileShareFragment != null && fileShareFragment.isAdded()) {
                        fileShareFragment.setPlayPauseIcon(false);
                    }
                    updateFileNotification(false);
                } catch (Exception ignored) {}
            } else {
                long delay = pauseAtMs - now;
                mainHandler.postDelayed(() -> {
                    if (syncMediaPlayer != null) {
                        try {
                            syncMediaPlayer.pause();
                            isSyncPlaying.set(false);
                            if (fileShareFragment != null && fileShareFragment.isAdded()) {
                                fileShareFragment.setPlayPauseIcon(false);
                            }
                            updateFileNotification(false);
                        } catch (Exception ignored) {}
                    }
                }, delay);
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