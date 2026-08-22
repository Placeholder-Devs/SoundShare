package com.example.soundshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

public class AudioCaptureService extends Service {

    private static final String CHANNEL_ID = "soundshare_capture";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_PLAY_PAUSE = "CAPTURE_TOGGLE_PLAY_PAUSE";
    public static final String ACTION_SKIP_NEXT = "CAPTURE_SKIP_NEXT";
    public static final String ACTION_SKIP_PREV = "CAPTURE_SKIP_PREV";
    public static final String ACTION_UPDATE = "CAPTURE_UPDATE_NOTIFICATION";

    public static final String BROADCAST_TOGGLE = "com.example.soundshare.CAPTURE_TOGGLE_PLAY_PAUSE";
    public static final String BROADCAST_SKIP_NEXT = "com.example.soundshare.CAPTURE_SKIP_NEXT";
    public static final String BROADCAST_SKIP_PREV = "com.example.soundshare.CAPTURE_SKIP_PREV";

    private static volatile Runnable onServiceReady;
    private static volatile boolean isRunning = false;
    private static volatile String currentTitle = "SoundShare Stream";
    private static volatile String currentArtist = "Streaming audio...";
    private static volatile Bitmap currentAlbumArt = null;
    private static volatile boolean currentPaused = false;
    private static volatile long currentDurationMs = 0;
    private static volatile long currentPositionMs = 0;

    private MediaSessionCompat mediaSession;

    public static void setOnServiceReady(Runnable callback) {
        onServiceReady = callback;
    }

    public static boolean isServiceRunning() {
        return isRunning;
    }

    private void sendExplicitBroadcast(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupMediaSession();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SoundShareCapture");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (currentPaused) sendExplicitBroadcast(BROADCAST_TOGGLE);
            }

            @Override
            public void onPause() {
                if (!currentPaused) sendExplicitBroadcast(BROADCAST_TOGGLE);
            }

            @Override
            public void onSkipToNext() {
                sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                sendExplicitBroadcast(BROADCAST_SKIP_PREV);
            }

            @Override
            public void onSeekTo(long pos) {
                currentPositionMs = pos;
                updateMediaSessionState();
                NotificationManager mgr = getSystemService(NotificationManager.class);
                if (mgr != null) mgr.notify(NOTIFICATION_ID, buildNotification());
            }
        });
        mediaSession.setActive(true);
        updateMediaSessionState();
        updateMediaSessionMetadata();
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        int state = currentPaused
                ? PlaybackStateCompat.STATE_PAUSED
                : PlaybackStateCompat.STATE_PLAYING;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, currentPositionMs, currentPaused ? 0f : 1.0f,
                        SystemClock.elapsedRealtime())
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist);
        if (currentAlbumArt != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt);
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentAlbumArt);
        }
        if (currentDurationMs > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs);
        }
        mediaSession.setMetadata(builder.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY_PAUSE.equals(action)) {
                sendExplicitBroadcast(BROADCAST_TOGGLE);
                return START_NOT_STICKY;
            }
            if (ACTION_SKIP_NEXT.equals(action)) {
                sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
                return START_NOT_STICKY;
            }
            if (ACTION_SKIP_PREV.equals(action)) {
                sendExplicitBroadcast(BROADCAST_SKIP_PREV);
                return START_NOT_STICKY;
            }
            if (ACTION_UPDATE.equals(action)) {
                updateMediaSessionState();
                updateMediaSessionMetadata();
                NotificationManager mgr = getSystemService(NotificationManager.class);
                if (mgr != null) {
                    mgr.notify(NOTIFICATION_ID, buildNotification());
                }
                return START_NOT_STICKY;
            }
        }

        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        isRunning = true;

        Runnable callback = onServiceReady;
        if (callback != null) {
            onServiceReady = null;
            callback.run();
        }

        return START_NOT_STICKY;
    }

    public static void updateNotification(Context context, String title, String artist,
                                           Bitmap albumArt, boolean paused,
                                           long durationMs, long positionMs) {
        currentTitle = (title != null && !title.isEmpty()) ? title : "SoundShare Stream";
        currentArtist = (artist != null && !artist.isEmpty()) ? artist : "Streaming audio...";
        currentAlbumArt = albumArt;
        currentPaused = paused;
        currentDurationMs = durationMs;
        currentPositionMs = positionMs;
        try {
            Intent updateIntent = new Intent(context, AudioCaptureService.class);
            updateIntent.setAction(ACTION_UPDATE);
            context.startService(updateIntent);
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.deleteNotificationChannel(CHANNEL_ID);
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Audio Streaming",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent skipPrevIntent = new Intent(this, AudioCaptureService.class);
        skipPrevIntent.setAction(ACTION_SKIP_PREV);
        PendingIntent skipPrevPending = PendingIntent.getService(
                this, 3, skipPrevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent playPauseIntent = new Intent(this, AudioCaptureService.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePending = PendingIntent.getService(
                this, 2, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent skipNextIntent = new Intent(this, AudioCaptureService.class);
        skipNextIntent.setAction(ACTION_SKIP_NEXT);
        PendingIntent skipNextPending = PendingIntent.getService(
                this, 4, skipNextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int playPauseIcon = currentPaused ? R.drawable.ic_play : R.drawable.ic_pause;
        String playPauseLabel = currentPaused ? "Play" : "Pause";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSubText("SoundShare")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openPending)
                .addAction(R.drawable.ic_skip_previous, "Previous", skipPrevPending)
                .addAction(playPauseIcon, playPauseLabel, playPausePending)
                .addAction(R.drawable.ic_skip_next, "Next", skipNextPending)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF34D399);

        if (currentAlbumArt != null) {
            builder.setLargeIcon(currentAlbumArt);
        }

        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        isRunning = false;
        onServiceReady = null;
        currentTitle = "SoundShare Stream";
        currentArtist = "Streaming audio...";
        currentAlbumArt = null;
        currentPaused = false;
        currentDurationMs = 0;
        currentPositionMs = 0;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
        stopForeground(true);
    }
}