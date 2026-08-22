package com.example.soundshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SoundShare";
    private static final int REQ_AUDIO = 2001;
    private static final int PORT = 5000;

    // ===== QUALITY PRESETS (ALL 16-BIT FOR COMPATIBILITY) =====
    private static final String[] QUALITY_NAMES = {
            "🚀 Ultra Low Latency – 44.1kHz Mono (~2.9ms)",
            "⚡ Low Latency – 44.1kHz Stereo (~4.4ms)",
            "⚖️ Balanced – 48kHz Stereo (~5ms)",
            "🎵 High Quality – 48kHz Stereo (~10ms)",
            "💎 Maximum Quality – 48kHz Stereo (~20ms)"
    };

    private static final int[][] QUALITY_PRESETS = {
            {44100, 1, 128},
            {44100, 2, 192},
            {48000, 2, 240},
            {48000, 2, 480},
            {48000, 2, 960},
    };

    // ===== PROTOCOL HEADER (16 bytes) =====
    private static final int HEADER_MAGIC = 0x53534844;
    private static final int HEADER_SIZE = 16;

    // ===== IMMUTABLE AUDIO CONFIG =====
    private static class AudioConfig {
        final int sampleRate;
        final int bitsPerSample;
        final int channels;
        final int bytesPerSample;
        final int encoding;
        final int channelInMask;
        final int channelOutMask;
        final int framesPerBuffer;
        final int chunkSize;
        final int socketBufferSize;
        final int presetIndex;

        AudioConfig(int[] preset, int presetIndex) {
            this.presetIndex = presetIndex;
            this.sampleRate = preset[0];
            this.channels = preset[1];
            this.framesPerBuffer = preset[2];
            this.bitsPerSample = 16;
            this.bytesPerSample = 2;
            this.encoding = AudioFormat.ENCODING_PCM_16BIT;
            this.channelInMask = channels == 1
                    ? AudioFormat.CHANNEL_IN_MONO
                    : AudioFormat.CHANNEL_IN_STEREO;
            this.channelOutMask = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;
            this.chunkSize = framesPerBuffer * channels * bytesPerSample;
            this.socketBufferSize = chunkSize * (presetIndex <= 1 ? 2 : 4);
        }

        static AudioConfig fromHeader(int sampleRate, int channels, int framesPerBuffer) {
            return new AudioConfig(
                    new int[]{sampleRate, channels, framesPerBuffer}, 2
            );
        }

        double latencyMs() {
            return (framesPerBuffer * 1000.0) / sampleRate;
        }

        String formatString() {
            int rawBitrate = sampleRate * channels * bitsPerSample;
            String chanStr = channels == 1 ? "Mono" : "Stereo";
            return String.format(Locale.US, "PCM 16-bit %s (%.1f Mbps)",
                    chanStr, rawBitrate / 1_000_000.0);
        }

        String channelString() {
            return channels == 1 ? "Mono" : "Stereo";
        }
    }

    // Atomic reference for thread-safe config swapping
    private final AtomicReference<AudioConfig> activeConfig =
            new AtomicReference<>(new AudioConfig(QUALITY_PRESETS[2], 2));

    private volatile int selectedPreset = 2;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;

    private int projectionResultCode;
    private Intent projectionResultData;

    // UI Elements
    private TextView status;
    private TextView tvDeviceIp;
    private EditText ipInput;
    private Button btnSend, btnReceive;
    private CardView statsCard;
    private TextView tvBitrate, tvTotalSent, tvDuration;
    private TextView tvSampleRate, tvFormat, tvConnectionStatus;
    private TextView tvReceiverCount, tvLatency;
    private Spinner qualitySpinner;

    // Handler for UI updates
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Thread-safe tracking
    private final AtomicLong bytesSentThisSecond = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong lastTimeMs = new AtomicLong(0);
    private final AtomicLong streamStartTime = new AtomicLong(0);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);

    // Multiple receivers support
    private final CopyOnWriteArrayList<ClientConnection> connectedClients = new CopyOnWriteArrayList<>();
    private final AtomicInteger receiverCount = new AtomicInteger(0);

    // Thread references for proper shutdown
    private volatile Thread acceptThread;
    private volatile Thread streamThread;
    private volatile Thread receiveThread;

    // Resources
    private volatile ServerSocket serverSocket;
    private volatile Socket activeSocket;
    private volatile AudioRecord recorder;
    private volatile AudioTrack audioTrack;

    // ---------- CLIENT CONNECTION CLASS ----------

    private static class ClientConnection {
        final Socket socket;
        final OutputStream outputStream;
        final String clientIp;
        final AtomicBoolean connected = new AtomicBoolean(true);

        ClientConnection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.outputStream = out;
            this.clientIp = socket.getInetAddress().getHostAddress();
        }

        boolean isConnected() {
            return connected.get();
        }

        void close() {
            if (connected.compareAndSet(true, false)) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- ACTIVITY RESULT LAUNCHER ----------

    private final ActivityResultLauncher<Intent> captureResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {

                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                updateStatus("Requires Android 10+");
                                return;
                            }

                            projectionResultCode = result.getResultCode();
                            projectionResultData = result.getData();

                            Intent serviceIntent = new Intent(this, AudioCaptureService.class);

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(serviceIntent);
                            } else {
                                startService(serviceIntent);
                            }

                            // Use a latch-based approach instead of arbitrary delay
                            mainHandler.postDelayed(() -> {
                                if (isFinishing() || isDestroyed()) return;

                                mediaProjection = projectionManager.getMediaProjection(
                                        projectionResultCode,
                                        projectionResultData
                                );

                                if (mediaProjection != null) {
                                    registerProjectionCallback();
                                    startStreaming();
                                } else {
                                    updateStatus("Failed to get media projection");
                                    stopForegroundService();
                                }
                            }, 500);
                        } else {
                            updateStatus("Screen capture permission denied");
                        }
                    }
            );

    // ---------- MEDIA PROJECTION CALLBACK ----------

    private void registerProjectionCallback() {
        if (mediaProjection == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires callback
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection revoked by system");
                    mainHandler.post(() -> {
                        if (!isFinishing()) {
                            stopStreaming();
                            updateStatus("Media projection was stopped by system");
                        }
                    });
                }
            }, mainHandler);
        } else {
            // Pre-Android 14: callback optional but recommended
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped");
                    mainHandler.post(() -> {
                        if (!isFinishing()) {
                            stopStreaming();
                            updateStatus("Media projection stopped");
                        }
                    });
                }
            }, mainHandler);
        }
    }

    // ---------- LIFECYCLE ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        setupQualitySpinner();
        setupClickListeners();

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        showDeviceIp();
    }

    private void initializeViews() {
        status = findViewById(R.id.status);
        tvDeviceIp = findViewById(R.id.tvDeviceIp);
        ipInput = findViewById(R.id.ipInput);
        btnSend = findViewById(R.id.btnSend);
        btnReceive = findViewById(R.id.btnReceive);
        statsCard = findViewById(R.id.statsCard);
        tvBitrate = findViewById(R.id.tvBitrate);
        tvTotalSent = findViewById(R.id.tvTotalSent);
        tvDuration = findViewById(R.id.tvDuration);
        tvSampleRate = findViewById(R.id.tvSampleRate);
        tvFormat = findViewById(R.id.tvFormat);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvReceiverCount = findViewById(R.id.tvReceiverCount);
        tvLatency = findViewById(R.id.tvLatency);
        qualitySpinner = findViewById(R.id.qualitySpinner);
    }

    private void setupQualitySpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                QUALITY_NAMES
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        qualitySpinner.setSelection(2);

        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPreset = position;
                showPresetInfo(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPreset = 2;
            }
        });
    }

    private void setupClickListeners() {
        tvDeviceIp.setOnClickListener(v -> {
            String ip = tvDeviceIp.getText().toString();
            if (!ip.equals("Not connected") && !ip.equals("Loading...")) {
                copyToClipboard(ip);
            }
        });

        btnSend.setOnClickListener(v -> {
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
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
                    return;
                }
            }

            Intent intent = projectionManager.createScreenCaptureIntent();
            captureResultLauncher.launch(intent);
        });

        btnReceive.setOnClickListener(v -> {
            if (isReceiving.get()) {
                stopReceiving();
                return;
            }

            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                updateStatus("Enter streamer IP address");
                return;
            }

            startReceiving(ip);
        });
    }

    private void showPresetInfo(int presetIndex) {
        int[] preset = QUALITY_PRESETS[presetIndex];
        int sampleRate = preset[0];
        int channels = preset[1];
        int framesPerBuffer = preset[2];

        double latencyMs = (framesPerBuffer * 1000.0) / sampleRate;
        int bitrate = sampleRate * channels * 16;

        String info = String.format(Locale.US,
                "Latency: ~%.1fms • %s • %.1f Mbps",
                latencyMs,
                channels == 1 ? "Mono" : "Stereo",
                bitrate / 1_000_000.0);

        updateStatus(info);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showDeviceIp();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        stopReceiving();
        stopForegroundService();
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ---------- PERMISSIONS ----------

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_AUDIO) {
                updateStatus("Audio permission granted. Tap Stream again.");
            } else if (requestCode == 3001) {
                updateStatus("Notification permission granted. Tap Stream again.");
            }
        } else {
            updateStatus("Permission denied");
        }
    }

    // ---------- PROTOCOL HEADER ----------

    private void writeFormatHeader(OutputStream out, AudioConfig config) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.putInt(HEADER_MAGIC);
        buf.putInt(config.sampleRate);
        buf.putShort((short) config.bitsPerSample);
        buf.putShort((short) config.channels);
        buf.putInt(config.framesPerBuffer);
        out.write(header);
        out.flush();
    }

    private AudioConfig readFormatHeader(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != HEADER_MAGIC) {
            Log.e(TAG, "Invalid header magic: 0x" + Integer.toHexString(magic));
            return null;
        }

        int sampleRate = dis.readInt();
        int bitsPerSample = dis.readShort() & 0xFFFF;
        int channels = dis.readShort() & 0xFFFF;
        int framesPerBuffer = dis.readInt();

        // Validate
        if (sampleRate != 44100 && sampleRate != 48000) {
            Log.e(TAG, "Unsupported sample rate: " + sampleRate);
            return null;
        }
        if (bitsPerSample != 16) {
            Log.e(TAG, "Unsupported bit depth: " + bitsPerSample);
            return null;
        }
        if (channels != 1 && channels != 2) {
            Log.e(TAG, "Unsupported channel count: " + channels);
            return null;
        }

        AudioConfig config = AudioConfig.fromHeader(sampleRate, channels, framesPerBuffer);

        Log.i(TAG, "Header received: " + config.sampleRate + "Hz, " + config.channels + "ch, "
                + config.framesPerBuffer + " frames/buffer");

        return config;
    }

    // ---------- IP ADDRESS ----------

    private void showDeviceIp() {
        String ip = getDeviceIpAddress();
        tvDeviceIp.setText(ip);
        tvDeviceIp.setTextColor(ip.equals("Not connected")
                ? ContextCompat.getColor(this, android.R.color.holo_red_light)
                : ContextCompat.getColor(this, android.R.color.holo_green_dark));
    }

    @SuppressLint("deprecation")
    private String getDeviceIpAddress() {
        try {
            // Modern approach for Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        LinkProperties props = cm.getLinkProperties(activeNetwork);
                        if (props != null) {
                            for (LinkAddress addr : props.getLinkAddresses()) {
                                InetAddress inetAddr = addr.getAddress();
                                if (inetAddr instanceof Inet4Address
                                        && !inetAddr.isLoopbackAddress()) {
                                    String hostAddr = inetAddr.getHostAddress();
                                    if (hostAddr != null) return hostAddr;
                                }
                            }
                        }
                    }
                }
            }

            // Fallback for older APIs
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();

                if (ipInt != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                }
            }

            // Last resort: iterate network interfaces
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                List<InetAddress> addresses = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addresses) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String hostAddr = addr.getHostAddress();
                        if (hostAddr != null) return hostAddr;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP", e);
        }
        return "Not connected";
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Device IP", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "IP copied: " + text, Toast.LENGTH_SHORT).show();
    }

    // ---------- UI HELPERS (ALL ON MAIN THREAD) ----------

    private void updateStatus(String message) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                status.setText(message);
            }
        });
    }

    private void updateReceiverCount() {
        int count = receiverCount.get();
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            tvReceiverCount.setText(String.valueOf(count));

            if (count == 0) {
                tvConnectionStatus.setText("Waiting for receivers...");
                tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(count).append(" connected ✓");
                for (ClientConnection client : connectedClients) {
                    if (client.isConnected()) {
                        sb.append("\n  • ").append(client.clientIp);
                    }
                }
                tvConnectionStatus.setText(sb.toString());
                tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_green_dark));
            }
        });
    }

    private void showStreamingUI(AudioConfig config) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            statsCard.setVisibility(View.VISIBLE);
            btnSend.setText("⏹ Stop Streaming");
            btnSend.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
            btnReceive.setEnabled(false);
            ipInput.setEnabled(false);
            qualitySpinner.setEnabled(false);

            tvSampleRate.setText(config.sampleRate + " Hz");
            tvFormat.setText(config.formatString());
            tvLatency.setText(String.format(Locale.US, "~%.1f ms", config.latencyMs()));
            tvConnectionStatus.setText("Waiting for receivers...");
            tvConnectionStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            tvBitrate.setText("0 kbps");
            tvTotalSent.setText("0 KB");
            tvDuration.setText("00:00");
            tvReceiverCount.setText("0");
        });
    }

    private void showReceivingUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            statsCard.setVisibility(View.VISIBLE);
            btnReceive.setText("⏹ Stop Receiving");
            btnReceive.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
            btnSend.setEnabled(false);
            ipInput.setEnabled(false);
            qualitySpinner.setEnabled(false);

            tvSampleRate.setText("Detecting...");
            tvFormat.setText("Detecting...");
            tvLatency.setText("—");
            tvConnectionStatus.setText("Connecting...");
            tvConnectionStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            tvBitrate.setText("—");
            tvTotalSent.setText("—");
            tvDuration.setText("00:00");
            tvReceiverCount.setText("—");
        });
    }

    private void updateReceivingUI(AudioConfig config, String streamerIp) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            tvConnectionStatus.setText("Connected to " + streamerIp + " ✓");
            tvConnectionStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark));
            tvReceiverCount.setText("—");
            tvSampleRate.setText(config.sampleRate + " Hz");
            tvFormat.setText(config.formatString());
            tvLatency.setText(String.format(Locale.US, "~%.1f ms", config.latencyMs()));
        });
    }

    private void resetUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            statsCard.setVisibility(View.GONE);

            btnSend.setText("▶ Start Streaming");
            btnSend.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, android.R.color.holo_green_dark));
            btnSend.setEnabled(true);

            btnReceive.setText("📥 Start Receiving");
            btnReceive.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark));
            btnReceive.setEnabled(true);

            ipInput.setEnabled(true);
            qualitySpinner.setEnabled(true);
        });
    }

    private void updateStats(long kbps, long totalBytes, long elapsedMs) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            if (kbps > 1000) {
                tvBitrate.setText(String.format(Locale.US, "%.1f Mbps", kbps / 1000.0));
            } else {
                tvBitrate.setText(kbps + " kbps");
            }

            if (totalBytes < 1024) {
                tvTotalSent.setText(totalBytes + " B");
            } else if (totalBytes < 1024 * 1024) {
                tvTotalSent.setText(String.format(Locale.US, "%.1f KB", totalBytes / 1024.0));
            } else {
                tvTotalSent.setText(String.format(Locale.US, "%.2f MB",
                        totalBytes / (1024.0 * 1024.0)));
            }

            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            tvDuration.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
        });
    }

    // ---------- STREAMING ----------

    @SuppressLint("MissingPermission")
   private void startStreaming() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus("Requires Android 10+");
            return;
        }

        // Snapshot the selected preset atomically
        final int presetIdx = selectedPreset;
        final AudioConfig config = new AudioConfig(QUALITY_PRESETS[presetIdx], presetIdx);
        activeConfig.set(config);

        isStreaming.set(true);
        totalBytesSent.set(0);
        receiverCount.set(0);
        connectedClients.clear();
        showStreamingUI(config);

        updateStatus(String.format(Locale.US, "Starting stream (%dHz %s, ~%.1fms)...",
                config.sampleRate, config.channelString(), config.latencyMs()));

        // Latch to coordinate accept thread shutdown
        final CountDownLatch acceptStoppedLatch = new CountDownLatch(1);

        // Thread 1: Accept clients
        acceptThread = new Thread(() -> {
            try {
                ServerSocket localServer = new ServerSocket();
                localServer.setReuseAddress(true);

                try {
                    localServer.bind(new InetSocketAddress(PORT));
                } catch (java.net.BindException e) {
                    updateStatus("Port " + PORT + " already in use. Close other apps and retry.");
                    isStreaming.set(false);
                    resetUI();
                    return;
                }

                localServer.setSoTimeout(1000);
                serverSocket = localServer;

                String myIp = getDeviceIpAddress();
                updateStatus("Waiting on " + myIp + ":" + PORT);

                while (isStreaming.get()) {
                    try {
                        Socket client = localServer.accept();

                        // Low latency socket config
                        client.setTcpNoDelay(true);
                        client.setSendBufferSize(config.socketBufferSize);
                        client.setKeepAlive(true);
                        client.setTrafficClass(0x10);

                        OutputStream out = client.getOutputStream();

                        // Write header as single atomic operation
                        writeFormatHeader(out, config);

                        ClientConnection connection = new ClientConnection(client, out);
                        connectedClients.add(connection);
                        receiverCount.incrementAndGet();

                        Log.i(TAG, "New receiver: " + connection.clientIp);
                        updateReceiverCount();
                        updateStatus("Streaming to " + receiverCount.get() + " receiver(s)");

                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        if (isStreaming.get()) {
                            Log.e(TAG, "Accept error", e);
                        }
                    }
                }
            } catch (Exception e) {
                if (isStreaming.get()) {
                    Log.e(TAG, "Server error", e);
                    updateStatus("Server error: " + e.getMessage());
                }
            } finally {
                acceptStoppedLatch.countDown();
            }
        }, "AcceptThread");
        acceptThread.start();

        // Thread 2: Capture and stream audio
        streamThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            AudioRecord localRecorder = null;

            try {
                Thread.sleep(200);

                if (!isStreaming.get()) return;

                AudioPlaybackCaptureConfiguration captureConfig =
                        new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(config.encoding)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(config.channelInMask)
                        .build();

                int minBufferSize = AudioRecord.getMinBufferSize(
                        config.sampleRate, config.channelInMask, config.encoding);
                int recordBufferSize = Math.max(minBufferSize,
                        config.chunkSize * (config.presetIndex <= 1 ? 2 : 4));

                localRecorder = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(recordBufferSize)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build();

                recorder = localRecorder;

                if (localRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    updateStatus("AudioRecord failed to initialize");
                    isStreaming.set(false);
                    return;
                }

                localRecorder.startRecording();

                byte[] buffer = new byte[config.chunkSize];
                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());
                bytesSentThisSecond.set(0);

                Log.i(TAG, "Streaming: " + config.sampleRate + "Hz, " + config.channels + "ch, "
                        + "Chunk: " + config.chunkSize + "B");

                while (isStreaming.get()) {
                    int read = localRecorder.read(buffer, 0, buffer.length);

                    if (read > 0 && !connectedClients.isEmpty()) {
                        // Collect failed clients to remove after iteration
                        List<ClientConnection> failed = null;

                        for (ClientConnection client : connectedClients) {
                            try {
                                if (client.isConnected()) {
                                    client.outputStream.write(buffer, 0, read);
                                    // No flush() needed - setTcpNoDelay(true) ensures immediate send
                                }
                            } catch (IOException e) {
                                Log.i(TAG, "Receiver disconnected: " + client.clientIp);
                                client.close();
                                if (failed == null) failed = new ArrayList<>();
                                failed.add(client);
                            }
                        }

                        // Remove failed clients outside iteration
                        if (failed != null) {
                            connectedClients.removeAll(failed);
                            receiverCount.addAndGet(-failed.size());
                            updateReceiverCount();
                            updateStatus("Streaming to " + receiverCount.get() + " receiver(s)");
                        }

                        int activeClients = Math.max(1, connectedClients.size());
                        int totalSent = read * activeClients;
                        bytesSentThisSecond.addAndGet(totalSent);
                        totalBytesSent.addAndGet(totalSent);

                        long now = System.currentTimeMillis();
                        long diff = now - lastTimeMs.get();

                        if (diff >= 1000) {
                            long bytes = bytesSentThisSecond.getAndSet(0);
                            long kbps = (bytes * 8) / diff;
                            long elapsed = now - streamStartTime.get();
                            lastTimeMs.set(now);
                            updateStats(kbps, totalBytesSent.get(), elapsed);
                        }
                    }
                }

            } catch (InterruptedException e) {
                Log.i(TAG, "Stream thread interrupted");
            } catch (Exception e) {
                if (isStreaming.get()) {
                    Log.e(TAG, "Streaming error", e);
                    updateStatus("Stream error: " + e.getMessage());
                }
            } finally {
                isStreaming.set(false);

                // Wait for accept thread to finish
                closeQuietly(serverSocket);
                serverSocket = null;
                try {
                    acceptStoppedLatch.await();
                } catch (InterruptedException ignored) {
                }

                // Now safe to clean up all shared state
                cleanupRecorder(localRecorder);
                recorder = null;

                for (ClientConnection client : connectedClients) {
                    client.close();
                }
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

        // Close server socket to unblock accept()
        closeQuietly(serverSocket);
        serverSocket = null;

        // Close all client connections to unblock write()
        for (ClientConnection client : connectedClients) {
            client.close();
        }
    }

    // ---------- RECEIVING ----------

    private void startReceiving(String streamerIp) {
        isReceiving.set(true);
        totalBytesSent.set(0);
        bytesSentThisSecond.set(0);
        showReceivingUI();
        updateStatus("Connecting to " + streamerIp + "...");

        receiveThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            Socket socket = null;
            InputStream in = null;
            AudioTrack localTrack = null;

            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(8192);
                socket.setTrafficClass(0x10);
                socket.connect(new InetSocketAddress(streamerIp, PORT), 5000);
                // No socket timeout - we use isReceiving flag and socket close to stop

                activeSocket = socket;
                in = new BufferedInputStream(socket.getInputStream(), 8192);

                // Read and validate format header
                AudioConfig config = readFormatHeader(in);
                if (config == null) {
                    updateStatus("Invalid stream format from " + streamerIp);
                    return;
                }

                activeConfig.set(config);
                updateReceivingUI(config, streamerIp);

                updateStatus(String.format(Locale.US, "Receiving (%dHz %s, ~%.1fms)",
                        config.sampleRate, config.channelString(), config.latencyMs()));

                int minPlayBuffer = AudioTrack.getMinBufferSize(
                        config.sampleRate, config.channelOutMask, config.encoding);
                int playBufferSize = Math.max(minPlayBuffer, config.chunkSize * 2);

                AudioAttributes audioAttrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        // Removed deprecated FLAG_LOW_LATENCY
                        .build();

                AudioFormat audioFormat = new AudioFormat.Builder()
                        .setEncoding(config.encoding)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(config.channelOutMask)
                        .build();

                localTrack = new AudioTrack.Builder()
                        .setAudioAttributes(audioAttrs)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(playBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();

                audioTrack = localTrack;
                localTrack.play();

                Log.i(TAG, "Receiving: " + config.sampleRate + "Hz, " + config.channels
                        + "ch, Buffer: " + playBufferSize);

                byte[] buffer = new byte[config.chunkSize];
                streamStartTime.set(System.currentTimeMillis());
                lastTimeMs.set(System.currentTimeMillis());

                while (isReceiving.get()) {
                    // Read exact chunk to avoid partial frame writes
                    int totalRead = 0;
                    while (totalRead < buffer.length && isReceiving.get()) {
                        int read = in.read(buffer, totalRead, buffer.length - totalRead);
                        if (read == -1) {
                            if (isReceiving.get()) {
                                updateStatus("Streamer disconnected");
                            }
                            isReceiving.set(false);
                            break;
                        }
                        totalRead += read;
                    }

                    if (totalRead == buffer.length && isReceiving.get()) {
                        localTrack.write(buffer, 0, totalRead);

                        totalBytesSent.addAndGet(totalRead);
                        bytesSentThisSecond.addAndGet(totalRead);

                        long now = System.currentTimeMillis();
                        long diff = now - lastTimeMs.get();

                        if (diff >= 1000) {
                            long bytes = bytesSentThisSecond.getAndSet(0);
                            long kbps = (bytes * 8) / diff;
                            long elapsed = now - streamStartTime.get();
                            lastTimeMs.set(now);
                            updateStats(kbps, totalBytesSent.get(), elapsed);
                        }
                    }
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
                    Log.e(TAG, "Unexpected receive error", e);
                    updateStatus("Error: " + e.getMessage());
                }
            } finally {
                isReceiving.set(false);
                cleanupTrack(localTrack);
                audioTrack = null;
                closeQuietly(in);
                closeQuietly(socket);
                activeSocket = null;
                resetUI();
                updateStatus("Receiving stopped");
            }
        }, "ReceiveThread");
        receiveThread.start();
    }

    private void stopReceiving() {
        if (!isReceiving.compareAndSet(true, false)) return;
        updateStatus("Stopping...");

        // Close socket to unblock read()
        Socket sock = activeSocket;
        if (sock != null) {
            closeQuietly(sock);
            activeSocket = null;
        }
    }

    // ---------- CLEANUP ----------

    private void stopForegroundService() {
        try {
            stopService(new Intent(this, AudioCaptureService.class));
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void cleanupRecorder(AudioRecord rec) {
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void cleanupTrack(AudioTrack track) {
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseProjection() {
        MediaProjection proj = mediaProjection;
        mediaProjection = null;
        if (proj != null) {
            try {
                proj.stop();
            } catch (Exception ignored) {
            }
        }
    }
}
