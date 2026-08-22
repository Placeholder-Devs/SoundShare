package com.example.soundshare;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/**
 * Fragment for the File Share and Sync Play feature.
 * Supports:
 * - Connecting multiple receivers simultaneously to 1 host
 * - Dynamic queue reordering (drag & drop tracks)
 * - UDP host auto-discovery
 * - Multi-song queue & playlist management (add multiple files, change tracks, remove tracks)
 * - Bidirectional file transfer between host and joiners
 * - Synchronized playback with sub-millisecond precision
 * - Stereo separation mode (Left speaker, Right speaker, or Stereo L+R)
 * - Support for FLAC Lossless, MP3, MP4/M4A, WAV Lossless PCM, OGG, AAC, OPUS
 */
public class FileShareFragment extends Fragment implements FileQueueAdapter.OnQueueItemListener {

    public interface FileShareCallback {
        void onFileShareHost();
        void onFileShareJoin(String ip);
        void onFileShareStop();
        void onScanForHost();
        void onPickAndAddSongs();
        void onSyncPlayPause();
        void onSyncSeek(int positionMs);
        void onSyncSkipNext();
        void onSyncSkipPrev();
        void onTrackSelected(int position, FileQueueItem item);
        void onTrackRemoved(int position, FileQueueItem item);
        void onTrackMoved(int fromPosition, int toPosition);
        void onTrackDownload(int position, FileQueueItem item);
        void onSaveCurrentTrack();
        void onStereoModeChanged(int mode); // 0=Stereo, 1=Left, 2=Right
        String getDeviceIp();
        void onCopyIp();
    }

    private FileShareCallback callback;

    // ----- UI ELEMENTS -----
    private TextView tvDeviceIp;
    private LinearLayout ipCardContainer;
    private EditText peerIpInput;
    private Button btnScan, btnHost, btnJoin, btnPickSend, btnStop;
    private LinearLayout hostJoinContainer, connectedActionsContainer;

    // Transfer progress card
    private LinearLayout transferCard;
    private ProgressBar transferProgressBar;
    private TextView tvTransferPercent, tvTransferSpeed, tvTransferStatus;

    // Sync playback card
    private LinearLayout playbackCard;
    private android.widget.ImageView ivPlaybackAlbumArt;
    private TextView tvPlaybackTitle, tvPlaybackArtist, tvPlaybackDetailsBadge, tvPlaybackElapsed, tvPlaybackDuration;
    private ImageButton btnPlayPause, btnSkipBack, btnSkipFwd;
    private SeekBar seekBarPlayback;
    private Button btnSaveCurrentTrack;

    // Stereo Mode Buttons
    private Button btnStereoBoth, btnStereoLeft, btnStereoRight;
    private int currentStereoMode = 0; // 0=Both, 1=Left, 2=Right

    // Photo Bottom Lyrics Overlay
    private View lyricsPhotoGradient;
    private LinearLayout layoutPhotoLyricsOverlay;
    private TextView tvLiveLyricLine;

    // Lyrics Section
    private Button btnToggleLyrics;
    private LinearLayout layoutLyricsContainer;
    private TextView tvLyricsSource, tvLyricsContent;
    private RecyclerView rvSyncedLyrics;
    private NestedScrollView scrollLyrics;
    private SyncedLyricsAdapter syncedLyricsAdapter;
    private boolean isLyricsVisible = false;
    private LyricsHelper.ParsedLyrics currentLyrics = null;
    private int lastActiveLyricIndex = -1;

    // Queue Card
    private LinearLayout queueCard;
    private TextView tvQueueHeader;
    private Button btnAddQueueSongs;
    private RecyclerView rvPlaybackQueue;
    private FileQueueAdapter queueAdapter;
    private ItemTouchHelper itemTouchHelper;

    // Status
    private View statusDot;
    private TextView tvStatus;

    private boolean isActive = false;
    private boolean isSeeking = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof FileShareCallback) {
            callback = (FileShareCallback) context;
        } else {
            throw new RuntimeException(context + " must implement FileShareCallback");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_file_share, container, false);

        // IP card
        tvDeviceIp = v.findViewById(R.id.tvDeviceIp);
        ipCardContainer = v.findViewById(R.id.ipCardContainer);
        peerIpInput = v.findViewById(R.id.peerIpInput);

        // Buttons
        btnScan = v.findViewById(R.id.btnScan);
        btnHost = v.findViewById(R.id.btnHost);
        btnJoin = v.findViewById(R.id.btnJoin);
        btnPickSend = v.findViewById(R.id.btnPickSend);
        btnStop = v.findViewById(R.id.btnStop);
        hostJoinContainer = v.findViewById(R.id.hostJoinContainer);
        connectedActionsContainer = v.findViewById(R.id.connectedActionsContainer);

        // Stereo Mode Buttons
        btnStereoBoth = v.findViewById(R.id.btnStereoBoth);
        btnStereoLeft = v.findViewById(R.id.btnStereoLeft);
        btnStereoRight = v.findViewById(R.id.btnStereoRight);

        // Transfer progress
        transferCard = v.findViewById(R.id.transferCard);
        transferProgressBar = v.findViewById(R.id.transferProgressBar);
        tvTransferPercent = v.findViewById(R.id.tvTransferPercent);
        tvTransferSpeed = v.findViewById(R.id.tvTransferSpeed);
        tvTransferStatus = v.findViewById(R.id.tvTransferStatus);

        // Sync playback
        playbackCard = v.findViewById(R.id.playbackCard);
        ivPlaybackAlbumArt = v.findViewById(R.id.ivPlaybackAlbumArt);
        tvPlaybackTitle = v.findViewById(R.id.tvPlaybackTitle);
        tvPlaybackArtist = v.findViewById(R.id.tvPlaybackArtist);
        tvPlaybackDetailsBadge = v.findViewById(R.id.tvPlaybackDetailsBadge);
        tvPlaybackElapsed = v.findViewById(R.id.tvPlaybackElapsed);
        tvPlaybackDuration = v.findViewById(R.id.tvPlaybackDuration);
        btnPlayPause = v.findViewById(R.id.btnSyncPlayPause);
        btnSkipBack = v.findViewById(R.id.btnSyncSkipBack);
        btnSkipFwd = v.findViewById(R.id.btnSyncSkipFwd);
        seekBarPlayback = v.findViewById(R.id.seekBarPlayback);

        // Photo Bottom Lyrics Overlay
        lyricsPhotoGradient = v.findViewById(R.id.lyricsPhotoGradient);
        layoutPhotoLyricsOverlay = v.findViewById(R.id.layoutPhotoLyricsOverlay);
        tvLiveLyricLine = v.findViewById(R.id.tvLiveLyricLine);

        // Lyrics & Download
        btnToggleLyrics = v.findViewById(R.id.btnToggleLyrics);
        btnSaveCurrentTrack = v.findViewById(R.id.btnSaveCurrentTrack);
        layoutLyricsContainer = v.findViewById(R.id.layoutLyricsContainer);
        tvLyricsSource = v.findViewById(R.id.tvLyricsSource);
        tvLyricsContent = v.findViewById(R.id.tvLyricsContent);
        rvSyncedLyrics = v.findViewById(R.id.rvSyncedLyrics);
        scrollLyrics = v.findViewById(R.id.scrollLyrics);

        if (btnSaveCurrentTrack != null) {
            btnSaveCurrentTrack.setOnClickListener(view -> {
                if (callback != null) callback.onSaveCurrentTrack();
            });
        }

        syncedLyricsAdapter = new SyncedLyricsAdapter();
        rvSyncedLyrics.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvSyncedLyrics.setAdapter(syncedLyricsAdapter);

        // Queue
        queueCard = v.findViewById(R.id.queueCard);
        tvQueueHeader = v.findViewById(R.id.tvQueueHeader);
        btnAddQueueSongs = v.findViewById(R.id.btnAddQueueSongs);
        rvPlaybackQueue = v.findViewById(R.id.rvPlaybackQueue);

        // Setup Queue RecyclerView with drag reordering
        queueAdapter = new FileQueueAdapter(this);
        rvPlaybackQueue.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvPlaybackQueue.setAdapter(queueAdapter);

        ItemTouchHelper.SimpleCallback touchHelperCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                queueAdapter.moveItem(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        };
        itemTouchHelper = new ItemTouchHelper(touchHelperCallback);
        itemTouchHelper.attachToRecyclerView(rvPlaybackQueue);
        queueAdapter.setOnStartDragListener(viewHolder -> itemTouchHelper.startDrag(viewHolder));

        // Status
        statusDot = v.findViewById(R.id.statusDot);
        tvStatus = v.findViewById(R.id.status);

        // Initialize device IP from callback
        if (callback != null) {
            String ip = callback.getDeviceIp();
            if (ip != null) tvDeviceIp.setText(ip);
        }

        // ----- CLICK LISTENERS -----

        ipCardContainer.setOnClickListener(view -> {
            if (callback != null) callback.onCopyIp();
        });

        btnScan.setOnClickListener(view -> {
            if (callback != null) callback.onScanForHost();
        });

        btnHost.setOnClickListener(view -> {
            if (callback != null) callback.onFileShareHost();
        });

        btnJoin.setOnClickListener(view -> {
            String ip = peerIpInput.getText().toString().trim();
            if (ip.isEmpty()) {
                peerIpInput.setError("Enter host IP or tap Scan");
                return;
            }
            if (callback != null) callback.onFileShareJoin(ip);
        });

        btnPickSend.setOnClickListener(view -> {
            if (callback != null) callback.onPickAndAddSongs();
        });

        btnAddQueueSongs.setOnClickListener(view -> {
            if (callback != null) callback.onPickAndAddSongs();
        });

        btnStop.setOnClickListener(view -> {
            if (callback != null) callback.onFileShareStop();
        });

        btnPlayPause.setOnClickListener(view -> {
            if (callback != null) callback.onSyncPlayPause();
        });

        btnSkipBack.setOnClickListener(view -> {
            if (callback != null) callback.onSyncSkipPrev();
        });

        btnSkipFwd.setOnClickListener(view -> {
            if (callback != null) callback.onSyncSkipNext();
        });

        // Stereo Mode Buttons
        btnStereoBoth.setOnClickListener(view -> updateStereoUI(0));
        btnStereoLeft.setOnClickListener(view -> updateStereoUI(1));
        btnStereoRight.setOnClickListener(view -> updateStereoUI(2));

        // Lyrics Toggle Button
        if (btnToggleLyrics != null) {
            btnToggleLyrics.setOnClickListener(view -> {
                isLyricsVisible = !isLyricsVisible;
                if (layoutLyricsContainer != null) {
                    layoutLyricsContainer.setVisibility(isLyricsVisible ? View.VISIBLE : View.GONE);
                }
                btnToggleLyrics.setText(isLyricsVisible ? "Hide Lyrics" : "Lyrics");
            });
        }

        seekBarPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    tvPlaybackElapsed.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                isSeeking = false;
                if (callback != null) {
                    callback.onSyncSeek(sb.getProgress());
                }
            }
        });

        return v;
    }

    private void updateStereoUI(int mode) {
        currentStereoMode = mode;
        if (btnStereoBoth == null || btnStereoLeft == null || btnStereoRight == null) return;

        btnStereoBoth.setBackgroundResource(mode == 0 ? R.drawable.bg_button_green : R.drawable.bg_ip_card);
        btnStereoBoth.setTextColor(mode == 0 ? Color.WHITE : Color.parseColor("#8A7A9A"));

        btnStereoLeft.setBackgroundResource(mode == 1 ? R.drawable.bg_button_blue : R.drawable.bg_ip_card);
        btnStereoLeft.setTextColor(mode == 1 ? Color.WHITE : Color.parseColor("#8A7A9A"));

        btnStereoRight.setBackgroundResource(mode == 2 ? R.drawable.bg_button_blue : R.drawable.bg_ip_card);
        btnStereoRight.setTextColor(mode == 2 ? Color.WHITE : Color.parseColor("#8A7A9A"));

        if (callback != null) callback.onStereoModeChanged(mode);
    }

    // ===== QUEUE ADAPTER CALLBACKS =====

    @Override
    public void onTrackSelected(int position, FileQueueItem item) {
        if (callback != null) callback.onTrackSelected(position, item);
    }

    @Override
    public void onTrackRemoved(int position, FileQueueItem item) {
        if (callback != null) callback.onTrackRemoved(position, item);
    }

    @Override
    public void onTrackMoved(int fromPosition, int toPosition) {
        if (callback != null) callback.onTrackMoved(fromPosition, toPosition);
    }

    @Override
    public void onTrackDownload(int position, FileQueueItem item) {
        if (callback != null) callback.onTrackDownload(position, item);
    }

    // ===== PUBLIC METHODS — called by MainActivity =====

    public void setDeviceIp(String ip) {
        if (tvDeviceIp != null) tvDeviceIp.setText(ip);
    }

    public void setStatus(String status) {
        if (tvStatus != null) tvStatus.setText(status);
    }

    public void setPeerIp(String ip) {
        if (peerIpInput != null) peerIpInput.setText(ip);
    }

    public void updateQueue(List<FileQueueItem> items, int currentPlayingIndex) {
        if (queueAdapter == null) return;
        queueAdapter.setItems(items);
        queueAdapter.setCurrentPlayingIndex(currentPlayingIndex);
        if (queueCard != null) {
            queueCard.setVisibility((items != null && !items.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        if (tvQueueHeader != null && items != null) {
            tvQueueHeader.setText("Playback Queue (" + items.size() + " tracks) - Drag to reorder");
        }
    }

    public void moveQueueItemLocally(int fromPosition, int toPosition) {
        if (queueAdapter != null) {
            queueAdapter.moveItem(fromPosition, toPosition);
        }
    }

    public void showConnectedUI() {
        isActive = true;
        if (hostJoinContainer != null) hostJoinContainer.setVisibility(View.GONE);
        if (connectedActionsContainer != null) {
            connectedActionsContainer.setVisibility(View.VISIBLE);
            connectedActionsContainer.setAlpha(0f);
            connectedActionsContainer.animate().alpha(1f).setDuration(300).start();
        }
        if (peerIpInput != null) peerIpInput.setEnabled(false);
        if (btnScan != null) btnScan.setEnabled(false);

        if (statusDot != null) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
        }
    }

    public void showTransferProgress(boolean show) {
        if (transferCard == null) return;
        if (show) {
            transferCard.setVisibility(View.VISIBLE);
            transferCard.setAlpha(0f);
            transferCard.setTranslationY(40);
            transferCard.animate().alpha(1f).translationY(0).setDuration(300).start();
            tvTransferStatus.setText("Waiting...");
            tvTransferPercent.setText("0%");
            tvTransferSpeed.setText("");
            transferProgressBar.setProgress(0);
        } else {
            transferCard.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> {
                        if (transferCard != null) transferCard.setVisibility(View.GONE);
                    }).start();
        }
    }

    public void updateTransferProgress(int percent, String speed) {
        if (transferProgressBar == null) return;
        transferProgressBar.setProgress(percent);
        tvTransferPercent.setText(percent + "%");
        tvTransferSpeed.setText(speed);
        tvTransferStatus.setText("Transferring...");
    }

    public void setTransferComplete() {
        if (tvTransferStatus != null) tvTransferStatus.setText("Transfer complete");
        if (transferProgressBar != null) transferProgressBar.setProgress(100);
        if (tvTransferPercent != null) tvTransferPercent.setText("100%");
        if (tvTransferSpeed != null) tvTransferSpeed.setText("");
    }

    public void showPlaybackControls(boolean show) {
        if (playbackCard == null) return;
        if (show) {
            playbackCard.setVisibility(View.VISIBLE);
            playbackCard.setAlpha(0f);
            playbackCard.setTranslationY(40);
            playbackCard.animate().alpha(1f).translationY(0).setDuration(400).start();
        } else {
            playbackCard.setVisibility(View.GONE);
        }
    }

    public void updatePlayback(int positionMs, int durationMs, boolean isPlaying) {
        if (seekBarPlayback == null) return;
        seekBarPlayback.setMax(durationMs);
        if (!isSeeking) {
            seekBarPlayback.setProgress(positionMs);
            tvPlaybackElapsed.setText(formatTime(positionMs));
        }
        tvPlaybackDuration.setText(formatTime(durationMs));
        setPlayPauseIcon(isPlaying);

        // Real-time time-synchronized lyric updates
        if (currentLyrics != null && currentLyrics.isSynced) {
            int activeIdx = currentLyrics.getActiveLineIndex(positionMs);
            if (activeIdx != lastActiveLyricIndex) {
                lastActiveLyricIndex = activeIdx;
                String activeText = currentLyrics.getActiveLineText(positionMs);

                // Smooth bottom transition over photo
                if (tvLiveLyricLine != null) {
                    if (activeText != null && !activeText.trim().isEmpty()) {
                        tvLiveLyricLine.setText(activeText);
                        if (layoutPhotoLyricsOverlay != null && layoutPhotoLyricsOverlay.getVisibility() != View.VISIBLE) {
                            layoutPhotoLyricsOverlay.setVisibility(View.VISIBLE);
                            layoutPhotoLyricsOverlay.setAlpha(0f);
                            layoutPhotoLyricsOverlay.animate().alpha(1f).setDuration(300).start();
                        }
                        if (lyricsPhotoGradient != null && lyricsPhotoGradient.getVisibility() != View.VISIBLE) {
                            lyricsPhotoGradient.setVisibility(View.VISIBLE);
                            lyricsPhotoGradient.setAlpha(0f);
                            lyricsPhotoGradient.animate().alpha(1f).setDuration(300).start();
                        }
                    }
                }

                // Synchronized lyrics list auto-scrolling
                if (syncedLyricsAdapter != null && rvSyncedLyrics != null) {
                    syncedLyricsAdapter.updateActiveIndex(activeIdx, rvSyncedLyrics);
                }
            }
        }
    }

    public void setCurrentPlayingTrack(int index, String fileName, String artist, String album,
                                       String formatDesc, android.graphics.Bitmap albumArt, int durationMs) {
        if (tvPlaybackTitle != null) tvPlaybackTitle.setText(fileName);
        if (tvPlaybackArtist != null) {
            String artText = (artist != null && !artist.isEmpty() && !"Unknown Artist".equals(artist))
                    ? artist + (album != null && !album.isEmpty() ? " • " + album : "")
                    : (album != null && !album.isEmpty() ? album : "SoundShare Sync");
            tvPlaybackArtist.setText(artText);
        }
        if (tvPlaybackDetailsBadge != null) {
            tvPlaybackDetailsBadge.setText(formatDesc != null && !formatDesc.isEmpty() ? formatDesc : "Lossless");
        }
        if (ivPlaybackAlbumArt != null) {
            if (albumArt != null) {
                ivPlaybackAlbumArt.setImageBitmap(albumArt);
                ivPlaybackAlbumArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                ivPlaybackAlbumArt.setPadding(0, 0, 0, 0);
            } else {
                ivPlaybackAlbumArt.setImageResource(R.drawable.ic_music_note);
                ivPlaybackAlbumArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                ivPlaybackAlbumArt.setPadding(16, 16, 16, 16);
            }
        }
        if (queueAdapter != null) queueAdapter.setCurrentPlayingIndex(index);
        if (seekBarPlayback != null) {
            seekBarPlayback.setMax(durationMs);
            seekBarPlayback.setProgress(0);
        }
        if (tvPlaybackElapsed != null) tvPlaybackElapsed.setText("0:00");
        if (tvPlaybackDuration != null) {
            tvPlaybackDuration.setText(formatTime(durationMs));
        }

        // Reset live lyric line for new track
        if (tvLiveLyricLine != null) tvLiveLyricLine.setText("");
        if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.GONE);
        if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.GONE);
    }

    public void setCurrentPlayingTrack(int index, String fileName, int durationMs) {
        setCurrentPlayingTrack(index, fileName, "Unknown Artist", "", "Lossless", null, durationMs);
    }

    public void setPlaybackTitle(String title) {
        if (tvPlaybackTitle != null) tvPlaybackTitle.setText(title);
    }

    public void setPlayPauseIcon(boolean isPlaying) {
        if (btnPlayPause != null) {
            btnPlayPause.setImageResource(
                    isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    public void setLyrics(LyricsHelper.ParsedLyrics lyrics) {
        currentLyrics = lyrics;
        lastActiveLyricIndex = -1;
        if (lyrics == null) {
            if (tvLyricsSource != null) tvLyricsSource.setText("");
            if (tvLyricsContent != null) tvLyricsContent.setText("No lyrics available");
            if (rvSyncedLyrics != null) rvSyncedLyrics.setVisibility(View.GONE);
            if (scrollLyrics != null) scrollLyrics.setVisibility(View.VISIBLE);
            if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.GONE);
            if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.GONE);
            return;
        }

        if (tvLyricsSource != null) {
            tvLyricsSource.setText(lyrics.source != null ? lyrics.source : "");
        }

        if (lyrics.isSynced && !lyrics.lines.isEmpty()) {
            if (rvSyncedLyrics != null) {
                rvSyncedLyrics.setVisibility(View.VISIBLE);
                syncedLyricsAdapter.setLines(lyrics.lines);
            }
            if (scrollLyrics != null) scrollLyrics.setVisibility(View.GONE);
            if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.VISIBLE);
            if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.VISIBLE);
        } else {
            if (rvSyncedLyrics != null) rvSyncedLyrics.setVisibility(View.GONE);
            if (scrollLyrics != null) scrollLyrics.setVisibility(View.VISIBLE);
            if (tvLyricsContent != null) {
                tvLyricsContent.setText(!lyrics.plainText.isEmpty() ? lyrics.plainText : "No lyrics available");
            }
            if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.GONE);
            if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.GONE);
        }
    }

    public void setLyricsLoading() {
        currentLyrics = null;
        lastActiveLyricIndex = -1;
        if (tvLyricsSource != null) {
            tvLyricsSource.setText("Fetching lyrics...");
        }
        if (tvLyricsContent != null) {
            tvLyricsContent.setText("Searching for lyrics...");
        }
        if (rvSyncedLyrics != null) rvSyncedLyrics.setVisibility(View.GONE);
        if (scrollLyrics != null) scrollLyrics.setVisibility(View.VISIBLE);
        if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.GONE);
        if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.GONE);
        if (tvLiveLyricLine != null) tvLiveLyricLine.setText("");
    }

    public void resetUI() {
        isActive = false;
        if (hostJoinContainer != null) hostJoinContainer.setVisibility(View.VISIBLE);
        if (connectedActionsContainer != null) connectedActionsContainer.setVisibility(View.GONE);
        if (peerIpInput != null) peerIpInput.setEnabled(true);
        if (btnScan != null) btnScan.setEnabled(true);

        showTransferProgress(false);
        showPlaybackControls(false);
        if (queueCard != null) queueCard.setVisibility(View.GONE);

        currentLyrics = null;
        lastActiveLyricIndex = -1;
        isLyricsVisible = false;
        if (layoutLyricsContainer != null) layoutLyricsContainer.setVisibility(View.GONE);
        if (btnToggleLyrics != null) btnToggleLyrics.setText("Lyrics");
        if (tvLyricsContent != null) tvLyricsContent.setText("Loading lyrics...");
        if (rvSyncedLyrics != null) rvSyncedLyrics.setVisibility(View.GONE);
        if (scrollLyrics != null) scrollLyrics.setVisibility(View.VISIBLE);
        if (layoutPhotoLyricsOverlay != null) layoutPhotoLyricsOverlay.setVisibility(View.GONE);
        if (lyricsPhotoGradient != null) lyricsPhotoGradient.setVisibility(View.GONE);
        if (tvLiveLyricLine != null) tvLiveLyricLine.setText("");

        if (ivPlaybackAlbumArt != null) {
            ivPlaybackAlbumArt.setImageResource(R.drawable.ic_music_note);
            ivPlaybackAlbumArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            ivPlaybackAlbumArt.setPadding(16, 16, 16, 16);
        }
        if (tvPlaybackTitle != null) tvPlaybackTitle.setText("--");
        if (tvPlaybackArtist != null) tvPlaybackArtist.setText("Unknown Artist");

        if (statusDot != null) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
        }
        if (tvStatus != null) tvStatus.setText("Idle");
    }

    public String getPeerIp() {
        return peerIpInput != null ? peerIpInput.getText().toString().trim() : "";
    }

    // ===== PRIVATE HELPERS =====

    private String formatTime(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }
}
