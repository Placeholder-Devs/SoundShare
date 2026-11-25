package com.example.soundshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    public static final String PREFS_NAME = "soundshare_settings";

    // Keys
    public static final String KEY_BUFFER_INDEX = "buffer_index";
    public static final String KEY_PERF_MODE = "perf_mode";
    public static final String KEY_AUDIO_GAIN = "audio_gain_db";
    public static final String KEY_CHANNEL_MODE = "channel_mode";
    public static final String KEY_AUTO_PAUSE_ON_HEADSET = "auto_pause_headset";

    public static final String KEY_TCP_NODELAY = "tcp_nodelay";
    public static final String KEY_SOCKET_MULTIPLIER = "socket_multiplier";
    public static final String KEY_CONN_TIMEOUT = "conn_timeout";
    public static final String KEY_CUSTOM_PORT = "custom_stream_port";
    public static final String KEY_AUTO_RECONNECT = "auto_reconnect";

    public static final String KEY_CLIENT_NAME = "client_name";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_WAKELOCK_ENABLED = "wakelock_enabled";
    public static final String KEY_HAPTIC_FEEDBACK = "haptic_feedback";

    /** Default audio stream port (must match MainActivity/the C client: 5000). */
    public static final int DEFAULT_STREAM_PORT = 5000;

    // Buffer size options: 0=Auto, then powers of 2 and common sizes
    private static final int[] BUFFER_VALUES = {
            0, 32, 64, 128, 256, 512, 1024, 2048, 4096, 4800, 9600, 19200, 48000
    };
    private static final String[] PERF_MODES = {"Low Latency", "None", "Power Saving"};
    private static final String[] GAIN_LABELS = {"0 dB (Flat)", "+3 dB Boost", "+6 dB Boost", "+9 dB Boost"};
    private static final String[] CHANNEL_MODES = {"Stereo (L/R)", "Mono Downmix", "Inverted (R/L)"};

    private SharedPreferences prefs;

    // UI Views
    private TextView tvBufferValue, tvPerfMode, tvAudioGain, tvChannelMode;
    private TextView tvSocketBuffer, tvConnTimeout, tvAudioCaps, tvDeviceModel;
    private SeekBar seekBufferSize, seekPerfMode, seekAudioGain, seekChannelMode;
    private SeekBar seekSocketBuffer, seekConnTimeout;
    private EditText etClientName, etCustomPort;
    private SwitchCompat switchAutoPauseHeadset, switchTcpNoDelay, switchAutoReconnect;
    private SwitchCompat switchKeepScreenOn, switchCpuWakeLock, switchHapticFeedback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Bind TextViews
        tvBufferValue = v.findViewById(R.id.tvBufferValue);
        tvPerfMode = v.findViewById(R.id.tvPerfMode);
        tvAudioGain = v.findViewById(R.id.tvAudioGain);
        tvChannelMode = v.findViewById(R.id.tvChannelMode);
        tvSocketBuffer = v.findViewById(R.id.tvSocketBuffer);
        tvConnTimeout = v.findViewById(R.id.tvConnTimeout);
        tvAudioCaps = v.findViewById(R.id.tvAudioCaps);
        tvDeviceModel = v.findViewById(R.id.tvDeviceModel);

        // Bind SeekBars
        seekBufferSize = v.findViewById(R.id.seekBufferSize);
        seekPerfMode = v.findViewById(R.id.seekPerfMode);
        seekAudioGain = v.findViewById(R.id.seekAudioGain);
        seekChannelMode = v.findViewById(R.id.seekChannelMode);
        seekSocketBuffer = v.findViewById(R.id.seekSocketBuffer);
        seekConnTimeout = v.findViewById(R.id.seekConnTimeout);

        // Bind EditTexts
        etClientName = v.findViewById(R.id.etClientName);
        etCustomPort = v.findViewById(R.id.etCustomPort);

        // Bind Switches
        switchAutoPauseHeadset = v.findViewById(R.id.switchAutoPauseHeadset);
        switchTcpNoDelay = v.findViewById(R.id.switchTcpNoDelay);
        switchAutoReconnect = v.findViewById(R.id.switchAutoReconnect);
        switchKeepScreenOn = v.findViewById(R.id.switchKeepScreenOn);
        switchCpuWakeLock = v.findViewById(R.id.switchCpuWakeLock);
        switchHapticFeedback = v.findViewById(R.id.switchHapticFeedback);

        Button btnReset = v.findViewById(R.id.btnResetDefaults);

        // Load saved values
        int bufferIdx = clamp(prefs.getInt(KEY_BUFFER_INDEX, 0), 0, BUFFER_VALUES.length - 1);
        int perfMode = clamp(prefs.getInt(KEY_PERF_MODE, 0), 0, PERF_MODES.length - 1);
        int audioGain = prefs.getInt(KEY_AUDIO_GAIN, 0);
        int channelMode = prefs.getInt(KEY_CHANNEL_MODE, 0);
        int socketMul = prefs.getInt(KEY_SOCKET_MULTIPLIER, 4);
        int connTimeout = prefs.getInt(KEY_CONN_TIMEOUT, 3);
        int customPort = getCustomStreamPort(requireContext()); // effective port (default 5000)
        String clientName = prefs.getString(KEY_CLIENT_NAME, "");

        boolean autoPause = prefs.getBoolean(KEY_AUTO_PAUSE_ON_HEADSET, true);
        boolean tcpNoDelay = prefs.getBoolean(KEY_TCP_NODELAY, true);
        boolean autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true);
        boolean keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false);
        boolean cpuWakeLock = prefs.getBoolean(KEY_WAKELOCK_ENABLED, true);
        boolean haptic = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true);

        // Buffer size (range = BUFFER_VALUES indices; layout also sets android:max)
        seekBufferSize.setMax(BUFFER_VALUES.length - 1);
        seekBufferSize.setProgress(bufferIdx);
        updateBufferLabel(bufferIdx);
        seekBufferSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateBufferLabel(progress);
                prefs.edit().putInt(KEY_BUFFER_INDEX, progress).apply();
            }
        });

        // Performance mode (range = PERF_MODES indices; layout also sets android:max)
        seekPerfMode.setMax(PERF_MODES.length - 1);
        seekPerfMode.setProgress(perfMode);
        if (perfMode >= 0 && perfMode < PERF_MODES.length) {
            tvPerfMode.setText(PERF_MODES[perfMode]);
        }
        seekPerfMode.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int p = Math.min(progress, PERF_MODES.length - 1);
                tvPerfMode.setText(PERF_MODES[p]);
                prefs.edit().putInt(KEY_PERF_MODE, p).apply();
            }
        });

        // Audio Gain
        seekAudioGain.setProgress(audioGain);
        if (audioGain >= 0 && audioGain < GAIN_LABELS.length) {
            tvAudioGain.setText(GAIN_LABELS[audioGain]);
        }
        seekAudioGain.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int g = Math.min(progress, GAIN_LABELS.length - 1);
                tvAudioGain.setText(GAIN_LABELS[g]);
                prefs.edit().putInt(KEY_AUDIO_GAIN, g).apply();
            }
        });

        // Channel Mode
        seekChannelMode.setProgress(channelMode);
        if (channelMode >= 0 && channelMode < CHANNEL_MODES.length) {
            tvChannelMode.setText(CHANNEL_MODES[channelMode]);
        }
        seekChannelMode.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int c = Math.min(progress, CHANNEL_MODES.length - 1);
                tvChannelMode.setText(CHANNEL_MODES[c]);
                prefs.edit().putInt(KEY_CHANNEL_MODE, c).apply();
            }
        });

        // Socket buffer multiplier
        seekSocketBuffer.setProgress(socketMul);
        tvSocketBuffer.setText(socketMul + "×");
        seekSocketBuffer.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.max(1, progress);
                tvSocketBuffer.setText(val + "×");
                prefs.edit().putInt(KEY_SOCKET_MULTIPLIER, val).apply();
            }
        });

        // Connection timeout
        seekConnTimeout.setProgress(connTimeout);
        tvConnTimeout.setText((connTimeout * 1000) + " ms");
        seekConnTimeout.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.max(1, progress);
                tvConnTimeout.setText((val * 1000) + " ms");
                prefs.edit().putInt(KEY_CONN_TIMEOUT, val).apply();
            }
        });

        // Custom Port
        etCustomPort.setText(String.valueOf(customPort));
        etCustomPort.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String txt = s.toString().trim();
                if (txt.isEmpty()) {
                    etCustomPort.setError(null);
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(txt);
                } catch (NumberFormatException e) {
                    p = -1;
                }
                if (NetUtils.isAllowedCustomPort(p)) {
                    etCustomPort.setError(null);
                    if (prefs.getInt(KEY_CUSTOM_PORT, DEFAULT_STREAM_PORT) != p) {
                        prefs.edit().putInt(KEY_CUSTOM_PORT, p).apply();
                        notifyChange(KEY_CUSTOM_PORT);
                    }
                } else {
                    // Not saved: the last valid port stays in effect.
                    etCustomPort.setError("Use 1025–65535. Ports 5001–5007 are reserved by "
                            + "SoundShare (ping, chat, duplex, file share, sync, discovery). "
                            + "Default: " + DEFAULT_STREAM_PORT);
                }
            }
        });

        // Client name
        etClientName.setText(clientName);
        etClientName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(KEY_CLIENT_NAME, s.toString().trim()).apply();
                notifyChange(KEY_CLIENT_NAME);
            }
        });

        // Switches
        switchAutoPauseHeadset.setChecked(autoPause);
        switchAutoPauseHeadset.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_AUTO_PAUSE_ON_HEADSET, checked).apply();
            notifyChange(KEY_AUTO_PAUSE_ON_HEADSET);
        });

        switchTcpNoDelay.setChecked(tcpNoDelay);
        switchTcpNoDelay.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_TCP_NODELAY, checked).apply();
            notifyChange(KEY_TCP_NODELAY);
        });

        switchAutoReconnect.setChecked(autoReconnect);
        switchAutoReconnect.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, checked).apply();
            notifyChange(KEY_AUTO_RECONNECT);
        });

        switchKeepScreenOn.setChecked(keepScreenOn);
        switchKeepScreenOn.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply();
            notifyChange(KEY_KEEP_SCREEN_ON);
        });

        switchCpuWakeLock.setChecked(cpuWakeLock);
        switchCpuWakeLock.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_WAKELOCK_ENABLED, checked).apply();
            notifyChange(KEY_WAKELOCK_ENABLED);
        });

        switchHapticFeedback.setChecked(haptic);
        switchHapticFeedback.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, checked).apply();
            notifyChange(KEY_HAPTIC_FEEDBACK);
        });

        // About info
        tvDeviceModel.setText(Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        // Playback capability only: system-audio capture (MediaProjection) is often
        // limited to 48 kHz / 16-bit by the device's capture path.
        tvAudioCaps.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "Playback: up to 32-bit float / 192 kHz (if the device supports it). "
                        + "Capture: system audio via MediaProjection, may be limited to 48 kHz / 16-bit"
                : "Playback: 16/24-bit PCM. Capture: system audio via MediaProjection, "
                        + "may be limited to 48 kHz / 16-bit");

        // Reset to defaults
        btnReset.setOnClickListener(btn -> {
            prefs.edit().clear().apply();
            seekBufferSize.setProgress(0);
            seekPerfMode.setProgress(0);
            seekAudioGain.setProgress(0);
            seekChannelMode.setProgress(0);
            seekSocketBuffer.setProgress(4);
            seekConnTimeout.setProgress(3);
            etCustomPort.setText(String.valueOf(DEFAULT_STREAM_PORT));
            etClientName.setText("");
            switchAutoPauseHeadset.setChecked(true);
            switchTcpNoDelay.setChecked(true);
            switchAutoReconnect.setChecked(true);
            switchKeepScreenOn.setChecked(false);
            switchCpuWakeLock.setChecked(true);
            switchHapticFeedback.setChecked(true);
            updateBufferLabel(0);
            tvPerfMode.setText(PERF_MODES[0]);
            tvAudioGain.setText(GAIN_LABELS[0]);
            tvChannelMode.setText(CHANNEL_MODES[0]);
            tvSocketBuffer.setText("4×");
            tvConnTimeout.setText("3000 ms");
            notifyChange("reset");
            Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show();
        });
    }

    public interface OnSettingsChangedListener {
        void onSettingChanged(String key);
    }

    private static OnSettingsChangedListener globalSettingsListener;

    public static void setOnSettingsChangedListener(OnSettingsChangedListener listener) {
        globalSettingsListener = listener;
    }

    private void notifyChange(String key) {
        if (globalSettingsListener != null) {
            globalSettingsListener.onSettingChanged(key);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void updateBufferLabel(int index) {
        if (index < 0 || index >= BUFFER_VALUES.length) return;
        int val = BUFFER_VALUES[index];
        tvBufferValue.setText(val == 0 ? "Auto" : String.valueOf(val));
    }

    // ===== STATIC HELPERS for reading settings from MainActivity & Services =====

    public static int getBufferSize(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int idx = p.getInt(KEY_BUFFER_INDEX, 0);
        if (idx < 0 || idx >= BUFFER_VALUES.length) return 0;
        return BUFFER_VALUES[idx];
    }

    public static int getPerfMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PERF_MODE, 0);
    }

    public static int getAudioGainDb(Context ctx) {
        int idx = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_AUDIO_GAIN, 0);
        switch (idx) {
            case 1: return 3;
            case 2: return 6;
            case 3: return 9;
            default: return 0;
        }
    }

    public static float getAudioGainMultiplier(Context ctx) {
        int db = getAudioGainDb(ctx);
        if (db == 0) return 1.0f;
        return (float) Math.pow(10.0, db / 20.0);
    }

    public static int getChannelMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CHANNEL_MODE, 0);
    }

    public static boolean isAutoPauseOnHeadset(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_PAUSE_ON_HEADSET, true);
    }

    public static boolean isTcpNoDelay(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TCP_NODELAY, true);
    }

    public static int getSocketMultiplier(Context ctx) {
        // clamp to the slider range (fragment_settings.xml max=16): phase5 review L5
        return clamp(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SOCKET_MULTIPLIER, 4), 1, 16);
    }

    public static int getConnTimeoutMs(Context ctx) {
        // clamp to the slider range (fragment_settings.xml max=10): phase5 review L5
        int secs = clamp(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CONN_TIMEOUT, 3), 1, 10);
        return secs * 1000;
    }

    public static int getCustomStreamPort(Context ctx) {
        int p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CUSTOM_PORT, DEFAULT_STREAM_PORT);
        // Older builds accepted 5001-5007 (the app's own control ports); never use those.
        return NetUtils.isAllowedCustomPort(p) ? p : DEFAULT_STREAM_PORT;
    }

    public static boolean isAutoReconnect(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public static String getClientName(Context ctx) {
        String name = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CLIENT_NAME, "");
        if (name == null || name.trim().isEmpty()) {
            return Build.MANUFACTURER + " " + Build.MODEL;
        }
        return name.trim();
    }

    public static boolean isKeepScreenOn(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public static boolean isWakeLockEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAKELOCK_ENABLED, true);
    }

    public static boolean isHapticFeedback(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HAPTIC_FEEDBACK, true);
    }

    /**
     * In-place DSP audio processor for 16-bit stereo PCM samples.
     * Applies channel routing (Mode 0: Stereo, Mode 1: Mono Downmix, Mode 2: Invert L/R)
     * and digital gain amplification with hard clipping prevention.
     * Delegates to {@link AudioPipelineUtils#applyDsp} with (2 bytes/sample, 2 channels);
     * callers that know the real stream format should call applyDsp directly.
     */
    public static void applyDspProcessing(byte[] buffer, int offset, int length,
                                          float gainMultiplier, int channelMode) {
        AudioPipelineUtils.applyDsp(buffer, offset, length, gainMultiplier, channelMode, 2, 2);
    }

    // Minimal SeekBarChangeListener
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}

