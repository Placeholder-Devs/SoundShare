package com.example.soundshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SoundShare";
    private static final int REQ_AUDIO = 2001;
    private static final int PORT = 5000;
    private static final int PING_PORT = 5001;
    private static final int CHAT_PORT = 5002;

    private static final byte PING_REQUEST = 0x01;
    private static final byte PING_RESPONSE = 0x02;
    private static final byte LATENCY_REPORT = 0x03;
    private static final byte CHAT_MSG = 0x10;

    private static final String[] QUALITY_NAMES = {
            "Ultra Low - 44.1kHz Mono 16-bit",
            "Low Latency - 44.1kHz Stereo 16-bit",
            "Balanced - 48kHz Stereo 16-bit",
            "High Quality - 48kHz Stereo 24-bit",
            "Maximum - 48kHz Stereo 24-bit"
    };

    private static final int[][] QUALITY_PRESETS = {
            {44100, 1, 128, 16},
            {44100, 2, 192, 16},
            {48000, 2, 240, 16},
            {48000, 2, 480, 24},
            {48000, 2, 9600, 24},
    };

    private static final int HEADER_MAGIC = 0x53534844;
    private static final int HEADER_VERSION = 2;
    private static final int HEADER_BASE_SIZE = 28;

    private volatile byte[] flacCSD = null;

    // ===== AUDIO CONFIG =====
    private static class AudioConfig {
        final int sampleRate, bitsPerSample, channels, bytesPerSample, encoding;
        final int channelInMask, channelOutMask, framesPerBuffer, chunkSize;
        final int socketBufferSize, presetIndex;

        AudioConfig(int[] preset, int presetIndex) {
            this.presetIndex = presetIndex;
            this.sampleRate = preset[0];
            this.channels = preset[1];
            this.framesPerBuffer = preset[2];
            this.bitsPerSample = preset[3];
            if (bitsPerSample == 24) {
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
            this.channelInMask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
            this.channelOutMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            this.chunkSize = framesPerBuffer * channels * bytesPerSample;
            this.socketBufferSize = chunkSize * (presetIndex <= 1 ? 2 : 4);
        }

        static AudioConfig fromHeader(int sr, int ch, int fpb, int bps) {
            return new AudioConfig(new int[]{sr, ch, fpb, bps}, 2);
        }

        double bufferLatencyMs() { return (framesPerBuffer * 1000.0) / sampleRate; }

        String formatString() {
            long rawBps = (long) sampleRate * channels * bitsPerSample;
            return String.format(Locale.US, "PCM %d-bit %s (%.1f Mbps)",
                    bitsPerSample, channels == 1 ? "Mono" : "Stereo", rawBps / 1_000_000.0);
        }

        String channelString() { return channels == 1 ? "Mono" : "Stereo"; }
    }

    private final AtomicReference<AudioConfig> activeConfig =
            new AtomicReference<>(new AudioConfig(QUALITY_PRESETS[2], 2));
    private volatile int selectedPreset = 2;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private int projectionResultCode;
    private Intent projectionResultData;

    // UI
    private TextView status, tvDeviceIp;
    private EditText ipInput;
    private Button btnSend, btnReceive;
    private CardView statsCard, chatCard;
    private TextView tvBitrate, tvTotalSent, tvDuration;
    private TextView tvSampleRate, tvFormat, tvConnectionStatus;
    private TextView tvReceiverCount, tvLatency;
    private Spinner qualitySpinner;
    private RecyclerView rvChat;
    private EditText etChatInput;
    private ImageButton btnChatSend;
    private TextView tvChatStatus;
    private ChatAdapter chatAdapter;
    private ScrollView scrollTop;
    private View statusDot, chatStatusDot;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Notifications
    private ChatNotificationHelper notificationHelper;
    private boolean isAppInForeground = true;

    // Stats
    private final AtomicLong bytesSentThisSecond = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong lastTimeMs = new AtomicLong(0);
    private final AtomicLong streamStartTime = new AtomicLong(0);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);
    private final AtomicLong currentLatencyMs = new AtomicLong(-1);

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
                        updateStatus("Requires Android 10+"); return;
                    }
                    projectionResultCode = result.getResultCode();
                    projectionResultData = result.getData();
                    Intent svc = new Intent(this, AudioCaptureService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
                    else startService(svc);
                    mainHandler.postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        mediaProjection = projectionManager.getMediaProjection(projectionResultCode, projectionResultData);
                        if (mediaProjection != null) { registerProjectionCallback(); startStreaming(); }
                        else { updateStatus("Failed to get media projection"); stopForegroundService(); }
                    }, 500);
                } else updateStatus("Screen capture permission denied");
            });

    private void registerProjectionCallback() {
        if (mediaProjection == null) return;
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                mainHandler.post(() -> {
                    if (!isFinishing()) { stopStreaming(); updateStatus("Media projection stopped"); }
                });
            }
        }, mainHandler);
    }

    // ---------- LIFECYCLE ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeViews();
        setupChat();
        setupKeyboardListener();
        setupQualitySpinner();
        setupClickListeners();
        setupNotifications();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        showDeviceIp();
    }

    private void setupNotifications() {
        notificationHelper = new ChatNotificationHelper(this);
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
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        if (intent.getBooleanExtra("from_notification", false)
                || intent.getBooleanExtra("mark_read", false)) {
            if (notificationHelper != null) notificationHelper.clearAllNotifications();
            if (chatCard != null && chatCard.getVisibility() == View.VISIBLE) {
                scrollTop.post(() -> scrollTop.smoothScrollTo(0, chatCard.getBottom()));
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
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
        chatCard = findViewById(R.id.chatCard);
        rvChat = findViewById(R.id.rvChat);
        etChatInput = findViewById(R.id.etChatInput);
        btnChatSend = findViewById(R.id.btnChatSend);
        tvChatStatus = findViewById(R.id.tvChatStatus);
        scrollTop = findViewById(R.id.scrollTop);
        statusDot = findViewById(R.id.statusDot);
        chatStatusDot = findViewById(R.id.chatStatusDot);
    }

    private void setupChat() {
        chatAdapter = new ChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvChat.setLayoutManager(lm);
        rvChat.setAdapter(chatAdapter);
        btnChatSend.setOnClickListener(v -> sendChatMessage());
        etChatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChatMessage(); return true; }
            return false;
        });
    }

    private void setupKeyboardListener() {
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootView.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            if (keypadHeight > screenHeight * 0.15) {
                mainHandler.postDelayed(() -> {
                    if (chatCard.getVisibility() == View.VISIBLE) {
                        scrollTop.post(() -> scrollTop.smoothScrollTo(0, chatCard.getBottom()));
                    }
                }, 100);
            }
        });
    }

    private void setupQualitySpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, QUALITY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        qualitySpinner.setSelection(2);
        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedPreset = pos; showPresetInfo(pos);
                if (v instanceof TextView) ((TextView) v).setTextColor(
                        ContextCompat.getColor(MainActivity.this, R.color.text_primary));
            }
            @Override public void onNothingSelected(AdapterView<?> p) { selectedPreset = 2; }
        });
    }

    private void setupClickListeners() {
        findViewById(R.id.ipCardContainer).setOnClickListener(v -> {
            String ip = tvDeviceIp.getText().toString();
            if (!ip.equals("Not connected") && !ip.equals("Loading...")) copyToClipboard(ip);
        });

        btnSend.setOnClickListener(v -> {
            if (isStreaming.get()) { stopStreaming(); return; }
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
            captureResultLauncher.launch(projectionManager.createScreenCaptureIntent());
        });

        btnReceive.setOnClickListener(v -> {
            if (isReceiving.get()) { stopReceiving(); return; }
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) { updateStatus("Enter streamer IP address"); return; }
            startReceiving(ip);
        });
    }

    private void showPresetInfo(int idx) {
        int[] p = QUALITY_PRESETS[idx];
        double lat = (p[2] * 1000.0) / p[0];
        long bps = (long) p[0] * p[1] * p[3];
        updateStatus(String.format(Locale.US, "Buffer: ~%.1fms | %s | %d-bit | %.1f Mbps",
                lat, p[1] == 1 ? "Mono" : "Stereo", p[3], bps / 1_000_000.0));
    }

    @Override protected void onResume() {
        super.onResume(); showDeviceIp();
        isAppInForeground = true;
        if (notificationHelper != null) {
            notificationHelper.setAppInForeground(true);
            notificationHelper.clearAllNotifications();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        isAppInForeground = false;
        if (notificationHelper != null) notificationHelper.setAppInForeground(false);
    }

    @Override protected void onStop() {
        super.onStop();
        isAppInForeground = false;
        if (notificationHelper != null) notificationHelper.setAppInForeground(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming(); stopReceiving();
        stopForegroundService(); stopReceiverService();
        if (notificationHelper != null) notificationHelper.clearAllNotifications();
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ---------- PERMISSIONS ----------
    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            updateStatus("Permission granted. Tap again.");
        else updateStatus("Permission denied");
    }

    // ---------- PROTOCOL HEADER ----------
    private void writeFormatHeader(OutputStream out, AudioConfig config) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.putInt(HEADER_MAGIC); buf.putInt(config.sampleRate);
        buf.putShort((short) config.bitsPerSample); buf.putShort((short) config.channels);
        buf.putInt(config.framesPerBuffer); buf.putInt(config.chunkSize);
        out.write(header); out.flush();
    }

    private AudioConfig readFormatHeader(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != HEADER_MAGIC) return null;
        int sr = dis.readInt(); int bps = dis.readShort() & 0xFFFF;
        int ch = dis.readShort() & 0xFFFF; int fpb = dis.readInt(); int cs = dis.readInt();
        if ((sr != 44100 && sr != 48000) || (bps != 16 && bps != 24) || (ch != 1 && ch != 2)) return null;
        Log.i(TAG, "Header: " + sr + "Hz " + ch + "ch " + bps + "bit " + fpb + "fpb chunk=" + cs);
        return AudioConfig.fromHeader(sr, ch, fpb, bps);
    }

    // ---------- IP ----------
    private void showDeviceIp() {
        String ip = getDeviceIpAddress();
        tvDeviceIp.setText(ip);
        tvDeviceIp.setTextColor(ip.equals("Not connected")
                ? ContextCompat.getColor(this, R.color.accent_red)
                : ContextCompat.getColor(this, R.color.accent_green));
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
                                    String h = a.getHostAddress(); if (h != null) return h;
                                }
                            }
                        }
                    }
                }
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0)
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            ipInt & 0xff, (ipInt >> 8) & 0xff, (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        String h = a.getHostAddress(); if (h != null) return h;
                    }
        } catch (Exception e) { Log.e(TAG, "IP error", e); }
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
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) status.setText(msg); });
    }

    private void updateReceiverCount() {
        int count = receiverCount.get();
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            tvReceiverCount.setText(String.valueOf(count));
            if (count == 0) {
                tvConnectionStatus.setText("Waiting for receivers...");
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            } else {
                StringBuilder sb = new StringBuilder().append(count).append(" connected");
                for (ClientConnection c : connectedClients)
                    if (c.isConnected()) sb.append("\n").append(c.clientIp);
                tvConnectionStatus.setText(sb.toString());
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            }
        });
    }

    private void updateLatencyUI(long latencyMs) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            tvLatency.setText(latencyMs < 0 ? "--" : latencyMs + " ms");
        });
    }

    private void showStreamingUI(AudioConfig config) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            statsCard.setVisibility(View.VISIBLE);
            chatCard.setVisibility(View.VISIBLE);
            btnSend.setText("Stop Streaming");
            btnSend.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_button_red));
            btnSend.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
            btnReceive.setEnabled(false); btnReceive.setAlpha(0.4f);
            ipInput.setEnabled(false); qualitySpinner.setEnabled(false);
            tvSampleRate.setText(config.sampleRate + " Hz"); tvFormat.setText(config.formatString());
            tvLatency.setText("--"); tvBitrate.setText("0 kbps");
            tvTotalSent.setText("0 KB"); tvDuration.setText("00:00"); tvReceiverCount.setText("0");
            tvConnectionStatus.setText("Waiting for receivers...");
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            tvChatStatus.setText("Listening");
            tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
            chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            chatAdapter.clear();
            addSystemChatMessage("Chat server started");
        });
    }

    private void showReceivingUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            statsCard.setVisibility(View.VISIBLE);
            chatCard.setVisibility(View.VISIBLE);
            btnReceive.setText("Stop Receiving");
            btnReceive.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_button_red));
            btnReceive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
            btnSend.setEnabled(false); btnSend.setAlpha(0.4f);
            ipInput.setEnabled(false); qualitySpinner.setEnabled(false);
            tvSampleRate.setText("Detecting..."); tvFormat.setText("Detecting...");
            tvLatency.setText("--"); tvBitrate.setText("--"); tvTotalSent.setText("--");
            tvDuration.setText("00:00"); tvReceiverCount.setText("--");
            tvConnectionStatus.setText("Connecting...");
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            tvChatStatus.setText("Connecting");
            tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
            chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            chatAdapter.clear();
        });
    }

    private void updateReceivingUI(AudioConfig config, String ip) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            tvConnectionStatus.setText("Connected to " + ip);
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            tvReceiverCount.setText("--");
            tvSampleRate.setText(config.sampleRate + " Hz");
            tvFormat.setText(config.formatString());
        });
    }

    private void resetUI() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            statsCard.setVisibility(View.GONE);
            chatCard.setVisibility(View.GONE);
            btnSend.setText("Start Streaming");
            btnSend.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_button_green));
            btnSend.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stream, 0, 0, 0);
            btnSend.setEnabled(true); btnSend.setAlpha(1f);
            btnReceive.setText("Start Receiving");
            btnReceive.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_button_blue));
            btnReceive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_receive, 0, 0, 0);
            btnReceive.setEnabled(true); btnReceive.setAlpha(1f);
            ipInput.setEnabled(true); qualitySpinner.setEnabled(true);
        });
    }

    private void updateStats(long kbps, long totalBytes, long elapsedMs) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            tvBitrate.setText(kbps > 1000 ? String.format(Locale.US, "%.1f Mbps", kbps / 1000.0) : kbps + " kbps");
            if (totalBytes < 1024) tvTotalSent.setText(totalBytes + " B");
            else if (totalBytes < 1024 * 1024) tvTotalSent.setText(String.format(Locale.US, "%.1f KB", totalBytes / 1024.0));
            else tvTotalSent.setText(String.format(Locale.US, "%.2f MB", totalBytes / (1024.0 * 1024.0)));
            long sec = elapsedMs / 1000;
            tvDuration.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
        });
    }

    // ==========================================================
    //  CHAT — COMMON
    // ==========================================================

    private void sendChatMessage() {
        String text = etChatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etChatInput.setText("");
        addSentChatMessage(text);
        if (isStreaming.get()) sendChatToAllClients(text);
        else if (isReceiving.get()) sendChatToServer(text);
    }

    private void addSentChatMessage(String text) {
        runOnUiThread(() -> {
            chatAdapter.addMessage(new ChatMessage("You", text, ChatMessage.TYPE_SENT));
            rvChat.scrollToPosition(chatAdapter.getLastPosition());
        });
    }

    private void addReceivedChatMessage(String sender, String text) {
        if (notificationHelper != null) notificationHelper.showChatNotification(sender, text);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            chatAdapter.addMessage(new ChatMessage(sender, text, ChatMessage.TYPE_RECEIVED));
            rvChat.scrollToPosition(chatAdapter.getLastPosition());
        });
    }

    private void addSystemChatMessage(String text) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            chatAdapter.addMessage(new ChatMessage("", text, ChatMessage.TYPE_SYSTEM));
            rvChat.scrollToPosition(chatAdapter.getLastPosition());
        });
    }

    private void writeChatMessage(DataOutputStream out, String sender, String message) throws IOException {
        byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.writeByte(CHAT_MSG);
            out.writeShort(senderBytes.length); out.write(senderBytes);
            out.writeShort(msgBytes.length); out.write(msgBytes);
            out.flush();
        }
    }

    private String[] readChatMessage(DataInputStream in) throws IOException {
        int senderLen = in.readUnsignedShort();
        if (senderLen > 1024) throw new IOException("Bad sender length");
        byte[] senderBytes = new byte[senderLen]; in.readFully(senderBytes);
        int msgLen = in.readUnsignedShort();
        if (msgLen > 65000) throw new IOException("Bad message length");
        byte[] msgBytes = new byte[msgLen]; in.readFully(msgBytes);
        return new String[]{new String(senderBytes, StandardCharsets.UTF_8),
                new String(msgBytes, StandardCharsets.UTF_8)};
    }

    // ==========================================================
    //  CHAT SERVER (SENDER)
    // ==========================================================

    private void startChatServer() {
        chatServerThread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true); ss.bind(new InetSocketAddress(CHAT_PORT));
                ss.setSoTimeout(1000); chatServerSocket = ss;
                while (isStreaming.get()) {
                    try {
                        Socket client = ss.accept();
                        client.setTcpNoDelay(true); client.setSoTimeout(0);
                        ChatClient cc = new ChatClient(client);
                        chatClients.add(cc);
                        addSystemChatMessage(cc.ip + " joined the chat");
                        updateChatStatus();
                        new Thread(() -> handleChatClientReads(cc), "ChatRead-" + cc.ip).start();
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) {
                if (isStreaming.get()) Log.e(TAG, "Chat server error", e);
            } finally {
                closeQuietly(chatServerSocket); chatServerSocket = null;
                for (ChatClient cc : chatClients) cc.close(); chatClients.clear();
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
            if (isStreaming.get() && cc.connected.get()) Log.w(TAG, "Chat client disconnected: " + cc.ip);
        } finally {
            cc.close(); chatClients.remove(cc);
            addSystemChatMessage(cc.ip + " left the chat"); updateChatStatus();
        }
    }

    private void sendChatToAllClients(String text) {
        new Thread(() -> {
            for (ChatClient cc : chatClients) {
                if (cc.connected.get()) {
                    try { writeChatMessage(cc.out, "Host", text); }
                    catch (IOException e) { cc.close(); chatClients.remove(cc); }
                }
            }
        }, "ChatBroadcast").start();
    }

    private void broadcastChatExcept(ChatClient exclude, String sender, String message) {
        for (ChatClient cc : chatClients) {
            if (cc != exclude && cc.connected.get()) {
                try { writeChatMessage(cc.out, sender, message); }
                catch (IOException e) { cc.close(); chatClients.remove(cc); }
            }
        }
    }

    private void stopChatServer() {
        closeQuietly(chatServerSocket); chatServerSocket = null;
        for (ChatClient cc : chatClients) cc.close(); chatClients.clear();
    }

    private void updateChatStatus() {
        int count = 0;
        for (ChatClient cc : chatClients) if (cc.connected.get()) count++;
        final int c = count;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (c == 0) {
                tvChatStatus.setText("Listening");
                tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange));
                chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
            } else {
                tvChatStatus.setText(c + " online");
                tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
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
                sock = new Socket(); sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(serverIp, CHAT_PORT), 5000);
                chatSocket = sock;
                DataInputStream din = new DataInputStream(sock.getInputStream());
                DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvChatStatus.setText("Connected");
                    tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                    chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                });
                addSystemChatMessage("Connected to chat");
                chatClientOut = dout;
                while (isReceiving.get()) {
                    byte cmd = din.readByte();
                    if (cmd == CHAT_MSG) {
                        String[] parts = readChatMessage(din);
                        addReceivedChatMessage(parts[0], parts[1]);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (isReceiving.get()) Log.w(TAG, "Chat client error: " + e.getMessage());
            } finally {
                chatClientOut = null; closeQuietly(sock); chatSocket = null;
                addSystemChatMessage("Chat disconnected");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvChatStatus.setText("Disconnected");
                    tvChatStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red));
                    chatStatusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                });
            }
        }, "ChatClientThread");
        chatClientThread.start();
    }

    private void sendChatToServer(String text) {
        DataOutputStream out = chatClientOut;
        if (out == null) { Toast.makeText(this, "Chat not connected", Toast.LENGTH_SHORT).show(); return; }
        String senderName = getDeviceIpAddress();
        new Thread(() -> {
            try { writeChatMessage(out, senderName, text); }
            catch (IOException e) {
                Log.w(TAG, "Failed to send chat: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show());
            }
        }, "ChatSend").start();
    }

    private void stopChatClient() {
        chatClientOut = null; closeQuietly(chatSocket); chatSocket = null;
    }

    // ==========================================================
    //  PING SERVER
    // ==========================================================

    private void startPingServer() {
        pingServerThread = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(); ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PING_PORT)); ss.setSoTimeout(1000);
                pingServerSocket = ss;
                while (isStreaming.get()) {
                    try {
                        Socket client = ss.accept(); client.setTcpNoDelay(true); client.setSoTimeout(3000);
                        new Thread(() -> handlePingClient(client), "PingHandler").start();
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception e) { if (isStreaming.get()) Log.e(TAG, "Ping server error", e); }
            finally { closeQuietly(pingServerSocket); pingServerSocket = null; }
        }, "PingServerThread");
        pingServerThread.start();
    }

    private void handlePingClient(Socket client) {
        try {
            DataInputStream din = new DataInputStream(client.getInputStream());
            DataOutputStream dout = new DataOutputStream(client.getOutputStream());
            while (isStreaming.get()) {
                byte cmd = din.readByte();
                if (cmd == PING_REQUEST) { dout.writeByte(PING_RESPONSE); dout.flush(); }
                else if (cmd == LATENCY_REPORT) {
                    long ms = din.readLong(); currentLatencyMs.set(ms); updateLatencyUI(ms);
                }
            }
        } catch (IOException ignored) {} finally { closeQuietly(client); }
    }

    private void stopPingServer() { closeQuietly(pingServerSocket); pingServerSocket = null; }

    // ==========================================================
    //  PING CLIENT
    // ==========================================================

    private void startPingClient(String serverIp) {
        pingClientThread = new Thread(() -> {
            Socket sock = null;
            try {
                Thread.sleep(300);
                sock = new Socket(); sock.setTcpNoDelay(true); sock.setSoTimeout(2000);
                sock.connect(new InetSocketAddress(serverIp, PING_PORT), 3000);
                pingSocket = sock;
                DataOutputStream dout = new DataOutputStream(sock.getOutputStream());
                DataInputStream din = new DataInputStream(sock.getInputStream());
                long smoothedMs = -1;
                while (isReceiving.get()) {
                    try {
                        long startNs = System.nanoTime();
                        dout.writeByte(PING_REQUEST); dout.flush();
                        byte resp = din.readByte();
                        if (resp == PING_RESPONSE) {
                            long rttMs = (System.nanoTime() - startNs) / 1_000_000;
                            AudioConfig config = activeConfig.get();
                            long totalMs = rttMs / 2 + (long) config.bufferLatencyMs();
                            smoothedMs = smoothedMs < 0 ? totalMs : (long) (smoothedMs * 0.7 + totalMs * 0.3);
                            currentLatencyMs.set(smoothedMs); updateLatencyUI(smoothedMs);
                            try { dout.writeByte(LATENCY_REPORT); dout.writeLong(smoothedMs); dout.flush(); }
                            catch (IOException ignored) {}
                        }
                    } catch (SocketTimeoutException e) { currentLatencyMs.set(999); updateLatencyUI(999); }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) { if (isReceiving.get()) Log.w(TAG, "Ping error: " + e.getMessage()); }
            finally { closeQuietly(sock); pingSocket = null; }
        }, "PingClientThread");
        pingClientThread.start();
    }

    private void stopPingClient() { closeQuietly(pingSocket); pingSocket = null; }

    // ---------- STREAMING ----------

    @SuppressLint("MissingPermission")
    private void startStreaming() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { updateStatus("Requires Android 10+"); return; }
        final AudioConfig config = new AudioConfig(QUALITY_PRESETS[selectedPreset], selectedPreset);
        activeConfig.set(config);
        isStreaming.set(true); totalBytesSent.set(0); receiverCount.set(0);
        connectedClients.clear(); currentLatencyMs.set(-1);
        showStreamingUI(config);
        updateStatus(String.format(Locale.US, "Starting stream (%dHz %s %d-bit)...",
                config.sampleRate, config.channelString(), config.bitsPerSample));
        startPingServer(); startChatServer();
        final CountDownLatch acceptStopped = new CountDownLatch(1);

        acceptThread = new Thread(() -> {
            try {
                ServerSocket ls = new ServerSocket(); ls.setReuseAddress(true);
                try { ls.bind(new InetSocketAddress(PORT)); }
                catch (java.net.BindException e) { updateStatus("Port " + PORT + " in use"); isStreaming.set(false); resetUI(); return; }
                ls.setSoTimeout(1000); serverSocket = ls;
                updateStatus("Waiting on " + getDeviceIpAddress() + ":" + PORT);
                while (isStreaming.get()) {
                    try {
                        Socket client = ls.accept(); client.setTcpNoDelay(true);
                        client.setSendBufferSize(config.socketBufferSize);
                        client.setKeepAlive(true); client.setTrafficClass(0x10);
                        ClientConnection conn = new ClientConnection(client);
                        writeFormatHeader(conn.outputStream, config);
                        connectedClients.add(conn); receiverCount.incrementAndGet();
                        updateReceiverCount(); updateStatus("Streaming to " + receiverCount.get() + " receiver(s)");
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) { if (isStreaming.get()) Log.e(TAG, "Accept error", e); }
                }
            } catch (Exception e) {
                if (isStreaming.get()) { Log.e(TAG, "Server error", e); updateStatus("Server error: " + e.getMessage()); }
            } finally { acceptStopped.countDown(); }
        }, "AcceptThread");
        acceptThread.start();

        streamThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord lr = null;
            try {
                Thread.sleep(200); if (!isStreaming.get()) return;
                AudioPlaybackCaptureConfiguration cc = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                AudioFormat fmt = new AudioFormat.Builder().setEncoding(config.encoding)
                        .setSampleRate(config.sampleRate).setChannelMask(config.channelInMask).build();
                int minBuf = AudioRecord.getMinBufferSize(config.sampleRate, config.channelInMask, config.encoding);
                int recBuf = Math.max(minBuf, config.chunkSize * (config.presetIndex <= 1 ? 2 : 4));
                lr = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(recBuf)
                        .setAudioPlaybackCaptureConfig(cc).build();
                recorder = lr;
                if (lr.getState() != AudioRecord.STATE_INITIALIZED) { updateStatus("AudioRecord init failed"); isStreaming.set(false); return; }
                lr.startRecording();
                byte[] buffer = new byte[config.chunkSize];
                streamStartTime.set(System.currentTimeMillis()); lastTimeMs.set(System.currentTimeMillis());
                bytesSentThisSecond.set(0);
                while (isStreaming.get()) {
                    int read = lr.read(buffer, 0, buffer.length);
                    if (read > 0 && !connectedClients.isEmpty()) {
                        List<ClientConnection> failed = null;
                        for (ClientConnection cl : connectedClients) {
                            try { if (cl.isConnected()) cl.outputStream.write(buffer, 0, read); }
                            catch (IOException e) { cl.close(); if (failed == null) failed = new ArrayList<>(); failed.add(cl); }
                        }
                        if (failed != null) {
                            connectedClients.removeAll(failed); receiverCount.addAndGet(-failed.size());
                            updateReceiverCount(); updateStatus("Streaming to " + receiverCount.get() + " receiver(s)");
                            if (connectedClients.isEmpty()) { currentLatencyMs.set(-1); updateLatencyUI(-1); }
                        }
                        int sent = read * Math.max(1, connectedClients.size());
                        bytesSentThisSecond.addAndGet(sent); totalBytesSent.addAndGet(sent);
                        long now = System.currentTimeMillis(); long diff = now - lastTimeMs.get();
                        if (diff >= 1000) { long bytes = bytesSentThisSecond.getAndSet(0); long kbps = (bytes * 8) / diff;
                            lastTimeMs.set(now); updateStats(kbps, totalBytesSent.get(), now - streamStartTime.get()); }
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) { if (isStreaming.get()) { Log.e(TAG, "Stream error", e); updateStatus("Stream error: " + e.getMessage()); } }
            finally {
                isStreaming.set(false); stopPingServer(); stopChatServer();
                closeQuietly(serverSocket); serverSocket = null;
                try { acceptStopped.await(); } catch (InterruptedException ignored) {}
                cleanupRecorder(lr); recorder = null;
                for (ClientConnection c : connectedClients) c.close(); connectedClients.clear(); receiverCount.set(0);
                releaseProjection(); stopForegroundService(); resetUI(); updateStatus("Streaming stopped");
            }
        }, "AudioStreamThread");
        streamThread.start();
    }

    private void stopStreaming() {
        if (!isStreaming.compareAndSet(true, false)) return;
        updateStatus("Stopping..."); stopPingServer(); stopChatServer();
        closeQuietly(serverSocket); serverSocket = null;
        for (ClientConnection c : connectedClients) c.close();
    }

    // ---------- RECEIVING ----------

    private void startReceiving(String streamerIp) {
        isReceiving.set(true); totalBytesSent.set(0); bytesSentThisSecond.set(0);
        currentLatencyMs.set(-1); showReceivingUI();
        updateStatus("Connecting to " + streamerIp + "...");
        startReceiverService();

        receiveThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Socket socket = null; InputStream in = null; AudioTrack lt = null;
            try {
                socket = new Socket(); socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(8192); socket.setTrafficClass(0x10);
                socket.connect(new InetSocketAddress(streamerIp, PORT), 5000);
                activeSocket = socket;
                in = new BufferedInputStream(socket.getInputStream(), 8192);
                AudioConfig config = readFormatHeader(in);
                if (config == null) { updateStatus("Invalid stream format"); return; }
                activeConfig.set(config); updateReceivingUI(config, streamerIp);
                startPingClient(streamerIp); startChatClient(streamerIp);
                updateStatus(String.format(Locale.US, "Receiving (%dHz %s %d-bit)",
                        config.sampleRate, config.channelString(), config.bitsPerSample));
                int minBuf = AudioTrack.getMinBufferSize(config.sampleRate, config.channelOutMask, config.encoding);
                int playBuf = Math.max(minBuf, config.chunkSize * 2);
                lt = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(config.encoding)
                                .setSampleRate(config.sampleRate).setChannelMask(config.channelOutMask).build())
                        .setBufferSizeInBytes(playBuf).setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                audioTrack = lt; lt.play();
                byte[] buffer = new byte[config.chunkSize];
                streamStartTime.set(System.currentTimeMillis()); lastTimeMs.set(System.currentTimeMillis());
                while (isReceiving.get()) {
                    int totalRead = 0;
                    while (totalRead < buffer.length && isReceiving.get()) {
                        int read = in.read(buffer, totalRead, buffer.length - totalRead);
                        if (read == -1) { if (isReceiving.get()) updateStatus("Streamer disconnected"); isReceiving.set(false); break; }
                        totalRead += read;
                    }
                    if (totalRead == buffer.length && isReceiving.get()) {
                        lt.write(buffer, 0, totalRead);
                        totalBytesSent.addAndGet(totalRead); bytesSentThisSecond.addAndGet(totalRead);
                        long now = System.currentTimeMillis(); long diff = now - lastTimeMs.get();
                        if (diff >= 1000) { long bytes = bytesSentThisSecond.getAndSet(0); long kbps = (bytes * 8) / diff;
                            lastTimeMs.set(now); updateStats(kbps, totalBytesSent.get(), now - streamStartTime.get()); }
                    }
                }
            } catch (java.net.ConnectException e) { updateStatus("Cannot connect to " + streamerIp); }
            catch (SocketTimeoutException e) { updateStatus("Connection timed out"); }
            catch (IOException e) { if (isReceiving.get()) { Log.e(TAG, "Receive error", e); updateStatus("Receive error: " + e.getMessage()); } }
            catch (Exception e) { if (isReceiving.get()) { Log.e(TAG, "Error", e); updateStatus("Error: " + e.getMessage()); } }
            finally {
                isReceiving.set(false); stopPingClient(); stopChatClient();
                cleanupTrack(lt); audioTrack = null;
                closeQuietly(in); closeQuietly(socket); activeSocket = null;
                stopReceiverService(); resetUI(); updateStatus("Receiving stopped");
            }
        }, "ReceiveThread");
        receiveThread.start();
    }

    private void stopReceiving() {
        if (!isReceiving.compareAndSet(true, false)) return;
        updateStatus("Stopping..."); stopPingClient(); stopChatClient();
        Socket s = activeSocket; if (s != null) { closeQuietly(s); activeSocket = null; }
        stopReceiverService();
    }

    // ---------- SERVICE HELPERS ----------
    private void startReceiverService() {
        try {
            Intent svc = new Intent(this, AudioReceiveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Exception e) { Log.w(TAG, "Failed to start receiver service: " + e.getMessage()); }
    }

    private void stopReceiverService() {
        try { stopService(new Intent(this, AudioReceiveService.class)); } catch (Exception ignored) {}
    }

    private void stopForegroundService() {
        try { stopService(new Intent(this, AudioCaptureService.class)); } catch (Exception ignored) {}
    }

    // ---------- CLEANUP ----------
    private void closeQuietly(AutoCloseable c) { if (c != null) { try { c.close(); } catch (Exception ignored) {} } }
    private void cleanupRecorder(AudioRecord r) { if (r != null) { try { r.stop(); } catch (Exception ignored) {} try { r.release(); } catch (Exception ignored) {} } }
    private void cleanupTrack(AudioTrack t) { if (t != null) { try { t.stop(); } catch (Exception ignored) {} try { t.release(); } catch (Exception ignored) {} } }
    private void releaseProjection() { MediaProjection p = mediaProjection; mediaProjection = null; if (p != null) { try { p.stop(); } catch (Exception ignored) {} } }
}