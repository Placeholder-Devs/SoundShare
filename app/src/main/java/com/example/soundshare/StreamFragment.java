package com.example.soundshare;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class StreamFragment extends Fragment {

    public interface StreamCallback {
        void onStartStreaming();
        void onStopStreaming();
        void onStreamTogglePlayPause();
        void onChatSend(String message);
        void onSetupQualitySpinner(Spinner spinner);
        String getDeviceIp();
        void onCopyIp();
    }

    private StreamCallback callback;

    // Views
    private TextView tvDeviceIp, status;
    private Button btnSend;
    private Spinner qualitySpinner;
    private CardView statsCard, chatCard;
    private LinearLayout nowPlayingCard;
    private TextView tvBitrate, tvTotalSent, tvDuration;
    private TextView tvFormat, tvConnectionStatus;
    private TextView tvReceiverCount, tvLatency;
    private View statusDot, chatStatusDot;
    private TextView tvChatStatus;
    private RecyclerView rvChat;
    private EditText etChatInput;
    private ImageButton btnChatSend;

    // Now Playing
    private TextView tvNowPlayingTitle, tvNowPlayingSubtitle;
    private TextView tvNowPlayingElapsed, tvNowPlayingStatus;
    private View nowPlayingDot;
    private ImageButton btnPlayPause;

    private ChatAdapter chatAdapter;
    private boolean isActive = false;
    private ObjectAnimator pulseAnimator;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof StreamCallback) {
            callback = (StreamCallback) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stream, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        tvDeviceIp = v.findViewById(R.id.tvDeviceIp);
        status = v.findViewById(R.id.status);
        btnSend = v.findViewById(R.id.btnSend);
        qualitySpinner = v.findViewById(R.id.qualitySpinner);
        statsCard = v.findViewById(R.id.statsCard);
        chatCard = v.findViewById(R.id.chatCard);
        tvBitrate = v.findViewById(R.id.tvBitrate);
        tvTotalSent = v.findViewById(R.id.tvTotalSent);
        tvDuration = v.findViewById(R.id.tvDuration);
        tvFormat = v.findViewById(R.id.tvFormat);
        tvConnectionStatus = v.findViewById(R.id.tvConnectionStatus);
        tvReceiverCount = v.findViewById(R.id.tvReceiverCount);
        tvLatency = v.findViewById(R.id.tvLatency);
        statusDot = v.findViewById(R.id.statusDot);
        chatStatusDot = v.findViewById(R.id.chatStatusDot);
        tvChatStatus = v.findViewById(R.id.tvChatStatus);
        rvChat = v.findViewById(R.id.rvChat);
        etChatInput = v.findViewById(R.id.etChatInput);
        btnChatSend = v.findViewById(R.id.btnChatSend);

        nowPlayingCard = v.findViewById(R.id.nowPlayingCard);
        tvNowPlayingTitle = v.findViewById(R.id.tvNowPlayingTitle);
        tvNowPlayingSubtitle = v.findViewById(R.id.tvNowPlayingSubtitle);
        tvNowPlayingElapsed = v.findViewById(R.id.tvNowPlayingElapsed);
        tvNowPlayingStatus = v.findViewById(R.id.tvNowPlayingStatus);
        nowPlayingDot = v.findViewById(R.id.nowPlayingDot);
        btnPlayPause = v.findViewById(R.id.btnPlayPause);

        // Chat setup
        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(chatAdapter);

        // Button listeners with press animation
        btnSend.setOnClickListener(btn -> {
            animateButtonPress(btnSend);
            if (callback == null) return;
            if (isActive) {
                callback.onStopStreaming();
            } else {
                callback.onStartStreaming();
            }
        });

        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(btn -> {
                animateButtonPress(btnPlayPause);
                if (callback != null) callback.onStreamTogglePlayPause();
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

        View ipCard = v.findViewById(R.id.ipCardContainer);
        if (ipCard != null) {
            ipCard.setOnClickListener(card -> {
                animateButtonPress(ipCard);
                if (callback != null) callback.onCopyIp();
            });
        }

        // Setup spinner via activity
        if (callback != null) {
            callback.onSetupQualitySpinner(qualitySpinner);
            String ip = callback.getDeviceIp();
            if (ip != null) tvDeviceIp.setText(ip);
        }
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
        // Also pulse scaleY
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

    public void setDeviceIp(String ip) {
        if (tvDeviceIp != null) tvDeviceIp.setText(ip);
    }

    public void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    public void showStreamingUI(String sampleRate, String format) {
        if (getView() == null) return;
        isActive = true;

        // Animated reveal with stagger
        animateSlideUp(statsCard, 0);
        animateSlideUp(chatCard, 100);

        btnSend.setText("Stop Streaming");
        btnSend.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent_red));
        qualitySpinner.setEnabled(false);
        tvFormat.setText(format);
        tvLatency.setText("Waiting...");
        tvBitrate.setText("0 kbps");
        tvTotalSent.setText("0 KB");
        tvDuration.setText("00:00");
        tvReceiverCount.setText("0");
        tvConnectionStatus.setText("Waiting for receivers...");
        tvConnectionStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent_orange));
        updateDot(statusDot, R.color.accent_orange);
        updateDot(chatStatusDot, R.color.accent_orange);
        tvChatStatus.setText("Listening...");
        tvChatStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent_orange));
        chatAdapter.clear();
        addSystemMessage("Chat server started. Waiting for connections...");

        // Pulse the status dot
        startPulse(statusDot, 1f, 1.4f);
    }

    public void resetUI() {
        if (getView() == null) return;
        isActive = false;
        stopPulse();

        animateFadeOut(statsCard);
        animateFadeOut(chatCard);
        if (nowPlayingCard != null) animateFadeOut(nowPlayingCard);

        btnSend.setText("Start Streaming");
        btnSend.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.accent_green));
        qualitySpinner.setEnabled(true);
        updateDot(statusDot, R.color.accent_orange);
    }

    public void updateStats(String bitrate, String total, String duration,
                            String sampleRate, String format, String latency, String receivers) {
        if (getView() == null) return;
        tvBitrate.setText(bitrate);
        tvTotalSent.setText(total);
        tvDuration.setText(duration);
        if (format != null) tvFormat.setText(format);
        if (latency != null) tvLatency.setText(latency);
        if (receivers != null) tvReceiverCount.setText(receivers);
    }

    public void updateConnectionStatus(String text, int colorRes) {
        if (getView() == null) return;
        tvConnectionStatus.setText(text);
        tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        updateDot(statusDot, colorRes);
    }

    public void updateLatency(String lat) {
        if (tvLatency != null) tvLatency.setText(lat);
    }

    public void updateReceiverCount(String count) {
        if (tvReceiverCount != null) tvReceiverCount.setText(count);
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
        if (getView() == null || nowPlayingCard == null) return;
        if (title != null) tvNowPlayingTitle.setText(title);
        if (subtitle != null) tvNowPlayingSubtitle.setText(subtitle);
    }

    public void setNowPlayingState(String label, int colorRes) {
        if (tvNowPlayingStatus != null && getContext() != null) {
            tvNowPlayingStatus.setText(label);
            tvNowPlayingStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        }
        updateDot(nowPlayingDot, colorRes);
    }

    public void setPlayPauseIcon(boolean paused) {
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
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
