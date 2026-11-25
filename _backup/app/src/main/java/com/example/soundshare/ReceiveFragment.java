package com.example.soundshare;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ReceiveFragment extends Fragment {

    public interface ReceiveCallback {
        void onStartReceiving(String ip);
        void onStopReceiving();
        void onScanForStreamHost();
        void onReceiveTogglePlayPause();
        void onReceiveSkipPrev();
        void onReceiveSkipNext();
        void onChatSend(String message);
    }

    private ReceiveCallback callback;

    // Views
    private TextView tvDeviceIp, status;
    private Button btnReceive, btnScan;
    private EditText ipInput;
    private LinearLayout statsCard, nowPlayingCard;
    private androidx.cardview.widget.CardView chatCard;
    private TextView tvBitrate, tvTotalSent, tvDuration;
    private TextView tvSampleRate, tvFormat, tvConnectionStatus;
    private TextView tvLatency;
    private View statusDot, chatStatusDot;
    private TextView tvChatStatus;
    private RecyclerView rvChat;
    private EditText etChatInput;
    private ImageButton btnChatSend;

    // Now Playing
    private ImageView ivReceiveAlbumArt;
    private TextView tvNowPlayingTitle, tvNowPlayingSubtitle;
    private TextView tvNowPlayingElapsed, tvNowPlayingStatus;
    private View nowPlayingDot;
    private ImageButton btnPlayPause, btnPlayPauseMini, btnSkipPrev, btnSkipNext;

    private ChatAdapter chatAdapter;
    private boolean isActive = false;
    private ObjectAnimator pulseAnimator;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof ReceiveCallback) {
            callback = (ReceiveCallback) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_receive, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        tvDeviceIp = v.findViewById(R.id.tvDeviceIp);
        status = v.findViewById(R.id.status);
        btnReceive = v.findViewById(R.id.btnReceive);
        btnScan = v.findViewById(R.id.btnScan);
        ipInput = v.findViewById(R.id.ipInput);

        if (btnScan != null) {
            btnScan.setOnClickListener(btn -> {
                animateButtonPress(btnScan);
                if (callback != null) callback.onScanForStreamHost();
            });
        }
        statsCard = v.findViewById(R.id.statsCard);
        chatCard = v.findViewById(R.id.chatCard);
        tvBitrate = v.findViewById(R.id.tvBitrate);
        tvTotalSent = v.findViewById(R.id.tvTotalSent);
        tvDuration = v.findViewById(R.id.tvDuration);
        tvSampleRate = v.findViewById(R.id.tvSampleRate);
        tvFormat = v.findViewById(R.id.tvFormat);
        tvConnectionStatus = v.findViewById(R.id.tvConnectionStatus);
        tvLatency = v.findViewById(R.id.tvLatency);
        statusDot = v.findViewById(R.id.statusDot);
        chatStatusDot = v.findViewById(R.id.chatStatusDot);
        tvChatStatus = v.findViewById(R.id.tvChatStatus);
        rvChat = v.findViewById(R.id.rvChat);
        etChatInput = v.findViewById(R.id.etChatInput);
        btnChatSend = v.findViewById(R.id.btnChatSend);

        nowPlayingCard = v.findViewById(R.id.nowPlayingCard);
        ivReceiveAlbumArt = v.findViewById(R.id.ivReceiveAlbumArt);
        tvNowPlayingTitle = v.findViewById(R.id.tvNowPlayingTitle);
        tvNowPlayingSubtitle = v.findViewById(R.id.tvNowPlayingSubtitle);
        tvNowPlayingElapsed = v.findViewById(R.id.tvNowPlayingElapsed);
        tvNowPlayingStatus = v.findViewById(R.id.tvNowPlayingStatus);
        nowPlayingDot = v.findViewById(R.id.nowPlayingDot);
        btnPlayPause = v.findViewById(R.id.btnPlayPause);
        btnPlayPauseMini = v.findViewById(R.id.btnPlayPauseMini);
        btnSkipPrev = v.findViewById(R.id.btnSkipPrev);
        btnSkipNext = v.findViewById(R.id.btnSkipNext);

        // Chat setup
        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(chatAdapter);

        // Button listeners with press animation
        btnReceive.setOnClickListener(btn -> {
            animateButtonPress(btnReceive);
            if (callback == null) return;
            if (isActive) {
                callback.onStopReceiving();
            } else {
                callback.onStartReceiving(ipInput.getText().toString().trim());
            }
        });

        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(btn -> {
                animateButtonPress(btnPlayPause);
                if (callback != null) callback.onReceiveTogglePlayPause();
            });
        }

        if (btnPlayPauseMini != null) {
            btnPlayPauseMini.setOnClickListener(btn -> {
                animateButtonPress(btnPlayPauseMini);
                if (callback != null) callback.onReceiveTogglePlayPause();
            });
        }

        if (btnSkipPrev != null) {
            btnSkipPrev.setOnClickListener(btn -> {
                animateButtonPress(btnSkipPrev);
                if (callback != null) callback.onReceiveSkipPrev();
            });
        }

        if (btnSkipNext != null) {
            btnSkipNext.setOnClickListener(btn -> {
                animateButtonPress(btnSkipNext);
                if (callback != null) callback.onReceiveSkipNext();
            });
        }

        btnChatSend.setOnClickListener(btn -> {
            animateButtonPress(btnChatSend);
            sendChat();
        });

        etChatInput.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onDestroyView() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        super.onDestroyView();
    }

    private void sendChat() {
        String msg = etChatInput.getText().toString().trim();
        if (!msg.isEmpty() && callback != null) {
            callback.onChatSend(msg);
            etChatInput.setText("");
        }
    }

    // ===== ANIMATION HELPERS =====

    /** Scale button down then back up — tactile press feel */
    private void animateButtonPress(View view) {
        if (getContext() != null && SettingsFragment.isHapticFeedback(getContext())) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }
        view.animate()
            .scaleX(0.92f).scaleY(0.92f)
            .setDuration(80)
            .withEndAction(() ->
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start())
            .start();
    }

    /** Slide up + fade in with stagger */
    private void animateSlideUp(View view, long delayMs) {
        view.setTranslationY(40f);
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(delayMs)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();
    }

    /** Fade out + slide down */
    private void animateFadeOut(View view) {
        view.animate()
            .translationY(20f)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> view.setVisibility(View.GONE))
            .start();
    }

    /** Continuous pulse on the status dot */
    private void startPulse(View dot, float minScale, float maxScale) {
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseAnimator = ObjectAnimator.ofFloat(dot, "scaleX", minScale, maxScale, minScale);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(dot, "scaleY", minScale, maxScale, minScale);
        pulseY.setDuration(1200);
        pulseY.setRepeatCount(ObjectAnimator.INFINITE);
        pulseY.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
        pulseY.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    // ===== PUBLIC UPDATE METHODS (called by MainActivity) =====

    public void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    public String getIpInput() {
        return ipInput != null ? ipInput.getText().toString().trim() : "";
    }

    public void setIpInput(String ip) {
        if (ipInput != null) ipInput.setText(ip);
    }

    public void showReceivingUI() {
        if (getView() == null) return;
        isActive = true;

        // Animated reveal with stagger
        animateSlideUp(statsCard, 0);
        animateSlideUp(chatCard, 100);

        btnReceive.setText("Stop Receiving");
        btnReceive.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent_red));
        ipInput.setEnabled(false);
        tvSampleRate.setText("—");
        tvFormat.setText("—");
        tvLatency.setText("Waiting...");
        tvBitrate.setText("0 kbps");
        tvTotalSent.setText("0 KB");
        tvDuration.setText("00:00");
        tvConnectionStatus.setText("Waiting for sender...");
        tvConnectionStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent_orange));
        updateDot(statusDot, R.color.accent_orange);
        updateDot(chatStatusDot, R.color.accent_orange);
        tvChatStatus.setText("Connecting...");
        tvChatStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent_orange));
        chatAdapter.clear();
        addSystemMessage("Connecting to sender...");

        // Pulse the status dot
        startPulse(statusDot, 1f, 1.4f);
    }

    public void updateReceivingUI(String sampleRate, String format, String ip) {
        if (getView() == null) return;
        if (sampleRate != null) tvSampleRate.setText(sampleRate);
        if (format != null) tvFormat.setText(format);
        if (ip != null && tvConnectionStatus != null) {
            tvConnectionStatus.setText("Connected to " + ip);
        }
    }

    public void resetUI() {
        if (getView() == null) return;
        isActive = false;
        stopPulse();

        animateFadeOut(statsCard);
        animateFadeOut(chatCard);
        if (nowPlayingCard != null) animateFadeOut(nowPlayingCard);
        if (ivReceiveAlbumArt != null) {
            ivReceiveAlbumArt.setImageResource(R.drawable.ic_music_note);
            ivReceiveAlbumArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            ivReceiveAlbumArt.setPadding(16, 16, 16, 16);
        }

        btnReceive.setText("Start Receiving");
        btnReceive.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent_green));
        ipInput.setEnabled(true);
        updateDot(statusDot, R.color.accent_orange);
    }

    public void updateStats(String bitrate, String total, String duration, String latency) {
        if (getView() == null) return;
        tvBitrate.setText(bitrate);
        tvTotalSent.setText(total);
        tvDuration.setText(duration);
        if (latency != null) tvLatency.setText(latency);
    }

    public void updateLatency(String lat) {
        if (tvLatency != null) tvLatency.setText(lat);
    }

    // Now Playing
    public void showNowPlaying(String title, String subtitle, String device) {
        if (nowPlayingCard == null || getView() == null) return;
        animateSlideUp(nowPlayingCard, 150);
        tvNowPlayingTitle.setText(title);
        tvNowPlayingSubtitle.setText(subtitle);
        tvNowPlayingElapsed.setText("00:00");
        setPlayPauseIcon(false);
        setNowPlayingState("Live", R.color.accent_green);
    }

    public void updateNowPlayingElapsed(String elapsed) {
        if (tvNowPlayingElapsed != null) tvNowPlayingElapsed.setText(elapsed);
    }

    public void updateNowPlayingMeta(String title, String subtitle) {
        updateNowPlayingMeta(title, subtitle, null);
    }

    public void updateNowPlayingMeta(String title, String subtitle, Bitmap albumArt) {
        if (getView() == null || nowPlayingCard == null) return;
        if (title != null) tvNowPlayingTitle.setText(title);
        if (subtitle != null) tvNowPlayingSubtitle.setText(subtitle);
        if (ivReceiveAlbumArt != null) {
            if (albumArt != null) {
                ivReceiveAlbumArt.setImageBitmap(albumArt);
                ivReceiveAlbumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ivReceiveAlbumArt.setPadding(0, 0, 0, 0);
            } else {
                ivReceiveAlbumArt.setImageResource(R.drawable.ic_music_note);
                ivReceiveAlbumArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ivReceiveAlbumArt.setPadding(16, 16, 16, 16);
            }
        }
    }

    public void setNowPlayingState(String label, int colorRes) {
        if (tvNowPlayingStatus != null && getContext() != null) {
            tvNowPlayingStatus.setText(label);
            tvNowPlayingStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        }
        updateDot(nowPlayingDot, colorRes);
    }

    public void setPlayPauseIcon(boolean paused) {
        int resId = paused ? R.drawable.ic_play : R.drawable.ic_pause;
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(resId);
        }
        if (btnPlayPauseMini != null) {
            btnPlayPauseMini.setImageResource(resId);
        }
    }

    // Chat
    public void addChatMessage(ChatMessage msg) {
        if (chatAdapter != null) {
            chatAdapter.addMessage(msg);
            rvChat.scrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }

    public void addSystemMessage(String text) {
        addChatMessage(new ChatMessage("", text, ChatMessage.TYPE_SYSTEM));
    }

    public void updateChatStatus(String text, int colorRes) {
        if (getView() == null) return;
        tvChatStatus.setText(text);
        tvChatStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        updateDot(chatStatusDot, colorRes);
    }

    public ChatAdapter getChatAdapter() { return chatAdapter; }

    private void updateDot(View dot, int colorRes) {
        if (dot == null || getContext() == null) return;
        int drawableRes;
        if (colorRes == R.color.accent_green) drawableRes = R.drawable.bg_status_dot_green;
        else if (colorRes == R.color.accent_red) drawableRes = R.drawable.bg_status_dot_red;
        else drawableRes = R.drawable.bg_status_dot_orange;
        dot.setBackgroundResource(drawableRes);
    }
}
