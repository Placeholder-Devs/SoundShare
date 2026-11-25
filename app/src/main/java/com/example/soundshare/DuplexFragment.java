package com.example.soundshare;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class DuplexFragment extends Fragment {

    public interface DuplexCallback {
        void onDuplexHost();
        void onDuplexJoin(String ip);
        void onDuplexStop();
        void onScanForDuplexHost();
        void onDuplexQualityChanged(int presetIndex);
        void onDuplexChatSend(String message);
        void onDuplexTogglePlayPause();
        void onDuplexSkipPrev();
        void onDuplexSkipNext();
        /** Called from setActive(); lets the host update window flags. Default: no-op. */
        default void onDuplexActiveChanged(boolean active) {}
    }

    private DuplexCallback callback;

    // Views
    private View ipCardContainer;
    private TextView tvDeviceIp;
    private EditText ipInput;
    private Button btnHost, btnJoin, btnStop, btnScan;
    private Spinner qualitySpinner;
    private TextView tvQualityFixed;
    private TextView tvStatus;
    private LinearLayout statsCard, nowPlayingCard;
    private CardView chatCard;
    private TextView tvSendRate, tvRecvRate, tvDuration;
    private TextView tvSampleRate, tvPing, tvChannels;
    private View statusDot;
    // Now Playing
    private ImageView ivDuplexAlbumArt;
    private TextView tvNowPlayingTitle, tvNowPlayingSubtitle, tvNowPlayingElapsed;
    private View nowPlayingDot;
    private ImageButton btnDuplexPlayPause, btnDuplexSkipPrev, btnDuplexSkipNext;
    // Chat
    private RecyclerView rvChat;
    private EditText etChatInput;
    private ImageButton btnChatSend;
    private TextView tvChatStatus;
    private View chatStatusDot;
    private ChatAdapter duplexChatAdapter;

    private boolean isActive = false;
    private ObjectAnimator pulseAnimator;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof DuplexCallback) {
            callback = (DuplexCallback) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_duplex, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        // IP Card
        ipCardContainer = v.findViewById(R.id.duplexIpCardContainer);
        tvDeviceIp = v.findViewById(R.id.tvDuplexDeviceIp);

        // Peer IP
        ipInput = v.findViewById(R.id.duplexIpInput);

        // Buttons
        btnHost = v.findViewById(R.id.btnDuplexHost);
        btnJoin = v.findViewById(R.id.btnDuplexJoin);
        btnStop = v.findViewById(R.id.btnDuplexStop);
        btnScan = v.findViewById(R.id.btnDuplexScan);

        if (btnScan != null) {
            btnScan.setOnClickListener(btn -> {
                animateButtonPress(btnScan);
                if (callback != null) callback.onScanForDuplexHost();
            });
        }

        // Quality — hide spinner, show fixed text instead
        qualitySpinner = v.findViewById(R.id.duplexQualitySpinner);
        if (qualitySpinner != null) {
            qualitySpinner.setVisibility(View.GONE);

            ViewGroup spinnerParent = (ViewGroup) qualitySpinner.getParent();
            if (spinnerParent != null) {
                tvQualityFixed = new TextView(requireContext());
                tvQualityFixed.setText("48kHz Stereo 16-bit (System Limit)");
                tvQualityFixed.setTextColor(ContextCompat.getColor(
                        requireContext(), R.color.text_secondary));
                tvQualityFixed.setTextSize(13);
                tvQualityFixed.setPadding(
                        dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
                tvQualityFixed.setBackgroundResource(R.drawable.bg_input_field);

                int spinnerIndex = spinnerParent.indexOfChild(qualitySpinner);
                if (spinnerIndex >= 0) {
                    spinnerParent.addView(tvQualityFixed, spinnerIndex + 1);
                } else {
                    spinnerParent.addView(tvQualityFixed);
                }
            }

        }

        // Status
        tvStatus = v.findViewById(R.id.duplexStatus);

        // Stats
        statsCard = v.findViewById(R.id.duplexStatsCard);
        tvSendRate = v.findViewById(R.id.tvDuplexSendRate);
        tvRecvRate = v.findViewById(R.id.tvDuplexRecvRate);
        tvDuration = v.findViewById(R.id.tvDuplexDuration);
        statusDot = v.findViewById(R.id.duplexStatusDot);
        tvSampleRate = v.findViewById(R.id.tvDuplexSampleRate);
        tvPing = v.findViewById(R.id.tvDuplexPing);
        tvChannels = v.findViewById(R.id.tvDuplexChannels);

        // Now Playing
        nowPlayingCard = v.findViewById(R.id.duplexNowPlayingCard);
        ivDuplexAlbumArt = v.findViewById(R.id.ivDuplexAlbumArt);
        tvNowPlayingTitle = v.findViewById(R.id.tvDuplexNowPlayingTitle);
        tvNowPlayingSubtitle = v.findViewById(R.id.tvDuplexNowPlayingSubtitle);
        tvNowPlayingElapsed = v.findViewById(R.id.tvDuplexNowPlayingElapsed);
        nowPlayingDot = v.findViewById(R.id.duplexNowPlayingDot);
        btnDuplexPlayPause = v.findViewById(R.id.btnDuplexPlayPause);
        btnDuplexSkipPrev = v.findViewById(R.id.btnDuplexSkipPrev);
        btnDuplexSkipNext = v.findViewById(R.id.btnDuplexSkipNext);

        if (btnDuplexPlayPause != null) {
            btnDuplexPlayPause.setOnClickListener(b -> {
                animateButtonPress(btnDuplexPlayPause);
                if (callback != null) callback.onDuplexTogglePlayPause();
            });
        }
        if (btnDuplexSkipPrev != null) {
            btnDuplexSkipPrev.setOnClickListener(b -> {
                animateButtonPress(btnDuplexSkipPrev);
                if (callback != null) callback.onDuplexSkipPrev();
            });
        }
        if (btnDuplexSkipNext != null) {
            btnDuplexSkipNext.setOnClickListener(b -> {
                animateButtonPress(btnDuplexSkipNext);
                if (callback != null) callback.onDuplexSkipNext();
            });
        }

        // Chat
        chatCard = v.findViewById(R.id.duplexChatCard);
        rvChat = v.findViewById(R.id.rvDuplexChat);
        etChatInput = v.findViewById(R.id.etDuplexChatInput);
        btnChatSend = v.findViewById(R.id.btnDuplexChatSend);
        tvChatStatus = v.findViewById(R.id.tvDuplexChatStatus);
        chatStatusDot = v.findViewById(R.id.duplexChatStatusDot);

        // Chat adapter
        duplexChatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvChat.setAdapter(duplexChatAdapter);

        // IP card copy to clipboard with press animation
        ipCardContainer.setOnClickListener(btn -> {
            animateButtonPress(ipCardContainer);
            String ip = tvDeviceIp.getText().toString();
            if (!ip.isEmpty() && !"Loading...".equals(ip)) {
                ClipboardManager cm = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("IP", ip));
                    Toast.makeText(requireContext(), "IP copied!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnHost.setOnClickListener(btn -> {
            animateButtonPress(btnHost);
            if (callback != null) callback.onDuplexHost();
        });

        btnJoin.setOnClickListener(btn -> {
            animateButtonPress(btnJoin);
            String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                tvStatus.setText("Enter the host's IP address first");
                return;
            }
            if (callback != null) callback.onDuplexJoin(ip);
        });

        btnStop.setOnClickListener(btn -> {
            animateButtonPress(btnStop);
            if (callback != null) callback.onDuplexStop();
        });

        // Chat send
        btnChatSend.setOnClickListener(btn -> {
            animateButtonPress(btnChatSend);
            sendChatMessage();
        });
        etChatInput.setOnEditorActionListener((v2, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage();
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

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void sendChatMessage() {
        if (etChatInput == null) return;
        String msg = etChatInput.getText().toString().trim();
        if (msg.isEmpty()) return;
        etChatInput.setText("");
        if (callback != null) callback.onDuplexChatSend(msg);
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
        // One animator for X and Y so stopPulse() cancels both (the separate, untracked
        // scaleY animator used to keep running forever, one more per start).
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat(View.SCALE_X, minScale, maxScale, minScale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, minScale, maxScale, minScale));
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    // ===== UI Update Methods (called from MainActivity) =====

    public void setDeviceIp(String ip) {
        if (tvDeviceIp != null && isAdded()) tvDeviceIp.setText(ip);
    }

    public void setIpInput(String ip) {
        if (ipInput != null) ipInput.setText(ip);
    }

    public void setActive(boolean active) {
        this.isActive = active;
        if (callback != null) callback.onDuplexActiveChanged(active);
        if (!isAdded()) return;
        if (active) {
            btnHost.setVisibility(View.GONE);
            btnJoin.setVisibility(View.GONE);
            btnStop.setVisibility(View.VISIBLE);

            // Animate cards sliding up with stagger
            animateSlideUp(statsCard, 0);
            animateSlideUp(nowPlayingCard, 80);
            animateSlideUp(chatCard, 160);

            ipInput.setEnabled(false);

            // Pulse the status dot
            startPulse(statusDot, 1f, 1.4f);
        } else {
            stopPulse();

            btnHost.setVisibility(View.VISIBLE);
            btnJoin.setVisibility(View.VISIBLE);
            btnStop.setVisibility(View.GONE);

            // Animate cards fading out
            animateFadeOut(statsCard);
            animateFadeOut(nowPlayingCard);
            animateFadeOut(chatCard);

            ipInput.setEnabled(true);
            if (tvNowPlayingTitle != null) tvNowPlayingTitle.setText("Nothing playing");
            if (tvNowPlayingSubtitle != null) tvNowPlayingSubtitle.setText("");
            if (tvNowPlayingElapsed != null) tvNowPlayingElapsed.setText("");
            if (ivDuplexAlbumArt != null) {
                ivDuplexAlbumArt.setImageResource(R.drawable.ic_music_note);
                ivDuplexAlbumArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ivDuplexAlbumArt.setPadding(16, 16, 16, 16);
            }
        }
    }

    public void updateStatus(String msg) {
        if (tvStatus != null && isAdded()) tvStatus.setText(msg);
    }

    public void updateStats(String sendRate, String recvRate, String duration) {
        if (!isAdded()) return;
        if (tvSendRate != null) tvSendRate.setText(sendRate);
        if (tvRecvRate != null) tvRecvRate.setText(recvRate);
        if (tvDuration != null) tvDuration.setText(duration);
    }

    public void updateDetailedStats(String sampleRate, String ping, String channels) {
        if (!isAdded()) return;
        if (tvSampleRate != null) tvSampleRate.setText(sampleRate);
        if (tvPing != null) tvPing.setText(ping);
        if (tvChannels != null) tvChannels.setText(channels);
    }

    public void updateConnectionStatus(String status, int colorRes) {
        // Connection status text removed in v2 redesign
    }

    public void updateNowPlaying(String title, String subtitle, Bitmap albumArt) {
        if (!isAdded()) return;
        if (tvNowPlayingTitle != null) tvNowPlayingTitle.setText(title);
        if (tvNowPlayingSubtitle != null) {
            tvNowPlayingSubtitle.setText(subtitle);
            tvNowPlayingSubtitle.setVisibility(
                    subtitle != null && !subtitle.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (ivDuplexAlbumArt != null) {
            if (albumArt != null) {
                ivDuplexAlbumArt.setImageBitmap(albumArt);
                ivDuplexAlbumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ivDuplexAlbumArt.setPadding(0, 0, 0, 0);
            } else {
                ivDuplexAlbumArt.setImageResource(R.drawable.ic_music_note);
                ivDuplexAlbumArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ivDuplexAlbumArt.setPadding(16, 16, 16, 16);
            }
        }
    }

    public void updateNowPlayingElapsed(String elapsed) {
        if (tvNowPlayingElapsed != null && isAdded()) tvNowPlayingElapsed.setText(elapsed);
    }

    public void setPlayPauseIcon(boolean paused) {
        if (btnDuplexPlayPause != null) {
            btnDuplexPlayPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        }
    }

    public void setNowPlayingState(String statusText, int colorRes) {
        // Status text removed in v2 redesign
    }

    public void addChatMessage(ChatMessage msg) {
        if (duplexChatAdapter != null && isAdded()) {
            duplexChatAdapter.addMessage(msg);
            rvChat.scrollToPosition(duplexChatAdapter.getItemCount() - 1);
        }
    }

    public void addSystemMessage(String text) {
        addChatMessage(new ChatMessage("", text, ChatMessage.TYPE_SYSTEM));
    }

    public void clearChat() {
        if (duplexChatAdapter != null) duplexChatAdapter.clear();
    }

    public void updateChatStatus(String status, int colorRes) {
        if (!isAdded()) return;
        if (tvChatStatus != null) {
            tvChatStatus.setText(status);
            tvChatStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        }
        if (chatStatusDot != null) {
            chatStatusDot.setBackgroundResource(
                    colorRes == R.color.accent_green
                            ? R.drawable.bg_status_dot_green
                            : R.drawable.bg_status_dot_orange);
        }
    }
}
