package com.example.soundshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    public static final String PREFS_NAME = "soundshare_settings";
    public static final String KEY_BUFFER_INDEX = "buffer_index";
    public static final String KEY_PERF_MODE = "perf_mode";
    public static final String KEY_SOCKET_MULTIPLIER = "socket_multiplier";
    public static final String KEY_CONN_TIMEOUT = "conn_timeout";
    public static final String KEY_CLIENT_NAME = "client_name";

    // Buffer size options: 0=Auto, then powers of 2 and common sizes
    private static final int[] BUFFER_VALUES = {
            0, 32, 64, 128, 256, 512, 1024, 2048, 4096, 4800, 9600, 19200, 48000
    };
    private static final String[] PERF_MODES = {"Low Latency", "None", "Power Saving"};

    private SharedPreferences prefs;
    private TextView tvBufferValue, tvPerfMode, tvSocketBuffer, tvConnTimeout;
    private TextView tvAudioCaps, tvDeviceModel;
    private SeekBar seekBufferSize, seekPerfMode, seekSocketBuffer, seekConnTimeout;
    private android.widget.EditText etClientName;

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

        tvBufferValue = v.findViewById(R.id.tvBufferValue);
        tvPerfMode = v.findViewById(R.id.tvPerfMode);
        tvSocketBuffer = v.findViewById(R.id.tvSocketBuffer);
        tvConnTimeout = v.findViewById(R.id.tvConnTimeout);
        tvAudioCaps = v.findViewById(R.id.tvAudioCaps);
        tvDeviceModel = v.findViewById(R.id.tvDeviceModel);

        seekBufferSize = v.findViewById(R.id.seekBufferSize);
        seekPerfMode = v.findViewById(R.id.seekPerfMode);
        seekSocketBuffer = v.findViewById(R.id.seekSocketBuffer);
        seekConnTimeout = v.findViewById(R.id.seekConnTimeout);

        Button btnReset = v.findViewById(R.id.btnResetDefaults);

        // Load saved values
        int bufferIdx = prefs.getInt(KEY_BUFFER_INDEX, 0);
        int perfMode = prefs.getInt(KEY_PERF_MODE, 0);
        int socketMul = prefs.getInt(KEY_SOCKET_MULTIPLIER, 4);
        int connTimeout = prefs.getInt(KEY_CONN_TIMEOUT, 3);
        String clientName = prefs.getString(KEY_CLIENT_NAME, "");

        // Buffer size
        seekBufferSize.setProgress(bufferIdx);
        updateBufferLabel(bufferIdx);
        seekBufferSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                updateBufferLabel(progress);
                prefs.edit().putInt(KEY_BUFFER_INDEX, progress).apply();
            }
        });

        // Performance mode
        seekPerfMode.setProgress(perfMode);
        tvPerfMode.setText(PERF_MODES[perfMode]);
        seekPerfMode.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvPerfMode.setText(PERF_MODES[progress]);
                prefs.edit().putInt(KEY_PERF_MODE, progress).apply();
            }
        });

        // Socket buffer multi
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
        tvConnTimeout.setText((connTimeout * 1000) + "");
        seekConnTimeout.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.max(1, progress);
                tvConnTimeout.setText((val * 1000) + "");
                prefs.edit().putInt(KEY_CONN_TIMEOUT, val).apply();
            }
        });

        // Client name
        etClientName = v.findViewById(R.id.etClientName);
        etClientName.setText(clientName);
        etClientName.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(KEY_CLIENT_NAME, s.toString().trim()).apply();
            }
        });

        // About info
        tvDeviceModel.setText(Build.MANUFACTURER + " " + Build.MODEL);
        tvAudioCaps.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "32-bit PCM, Hi-Res" : "16-bit PCM");

        // Reset
        btnReset.setOnClickListener(btn -> {
            prefs.edit().clear().apply();
            seekBufferSize.setProgress(0);
            seekPerfMode.setProgress(0);
            seekSocketBuffer.setProgress(4);
            seekConnTimeout.setProgress(3);
            etClientName.setText("");
            Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateBufferLabel(int index) {
        if (index < 0 || index >= BUFFER_VALUES.length) return;
        int val = BUFFER_VALUES[index];
        tvBufferValue.setText(val == 0 ? "Auto" : String.valueOf(val));
    }

    // ===== STATIC HELPERS for reading settings from MainActivity =====

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

    public static int getSocketMultiplier(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SOCKET_MULTIPLIER, 4);
    }

    public static int getConnTimeoutMs(Context ctx) {
        int secs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CONN_TIMEOUT, 3);
        return secs * 1000;
    }

    public static String getClientName(Context ctx) {
        String name = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CLIENT_NAME, "");
        if (name == null || name.trim().isEmpty()) {
            return Build.MANUFACTURER + " " + Build.MODEL;
        }
        return name.trim();
    }

    // Minimal SeekBarChangeListener
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
