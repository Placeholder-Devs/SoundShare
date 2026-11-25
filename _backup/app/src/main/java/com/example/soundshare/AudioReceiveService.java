package com.example.soundshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

public class AudioReceiveService extends Service {

    private static final String CHANNEL_ID = "soundshare_receive";
    private static final int NOTIFICATION_ID = 2002;

    public static final String ACTION_STOP = "STOP_RECEIVING";
    public static final String ACTION_PLAY_PAUSE = "TOGGLE_PLAY_PAUSE";
    public static final String ACTION_SKIP_NEXT = "SKIP_NEXT";
    public static final String ACTION_SKIP_PREV = "SKIP_PREV";
    public static final String ACTION_UPDATE = "UPDATE_NOTIFICATION";

    public static final String BROADCAST_TOGGLE = "com.example.soundshare.TOGGLE_PLAY_PAUSE";
    public static final String BROADCAST_SKIP_NEXT = "com.example.soundshare.SKIP_NEXT";
    public static final String BROADCAST_SKIP_PREV = "com.example.soundshare.SKIP_PREV";
    public static final String BROADCAST_STOP = "com.example.soundshare.STOP_RECEIVING";

    private static volatile String currentTitle = "SoundShare Stream";
    private static volatile String currentArtist = "Receiving audio stream...";
    private static volatile Bitmap currentAlbumArt = null;
    private static volatile boolean currentPaused = false;
    private static volatile long currentDurationMs = 0;
    private static volatile long currentPositionMs = 0;

    private MediaSessionCompat mediaSession;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

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
        acquireLocks();
    }

    private void acquireLocks() {
        if (!SettingsFragment.isWakeLockEnabled(this)) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "soundshare:receive_wakelock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12 * 60 * 60 * 1000L); // 12h max safe timeout
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundshare:receive_wifilock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
        wakeLock = null;
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
        wifiLock = null;
    }

    private void setupMediaSession() {
        try {
            if (mediaSession != null) {
                try { mediaSession.release(); } catch (Exception ignored) {}
            }
            mediaSession = new MediaSessionCompat(this, "SoundShareReceiveSession");
            mediaSession.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
                }

                @Override
                public void onPause() {
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
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
                public void onStop() {
                    sendExplicitBroadcast(BROADCAST_STOP);
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
        } catch (Exception ignored) {}
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
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
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(state, currentPositionMs, currentPaused ? 0f : 1.0f,
                            SystemClock.elapsedRealtime())
                    .build();
            mediaSession.setPlaybackState(playbackState);
        } catch (Exception ignored) {}
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null) return;
        try {
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
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                sendExplicitBroadcast(BROADCAST_STOP);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_PLAY_PAUSE.equals(action)) {
                sendExplicitBroadcast(BROADCAST_TOGGLE);
                return START_STICKY;
            }
            if (ACTION_SKIP_NEXT.equals(action)) {
                sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
                return START_STICKY;
            }
            if (ACTION_SKIP_PREV.equals(action)) {
                sendExplicitBroadcast(BROADCAST_SKIP_PREV);
                return START_STICKY;
            }
            if (ACTION_UPDATE.equals(action)) {
                if (mediaSession == null) {
                    setupMediaSession();
                } else if (!mediaSession.isActive()) {
                    mediaSession.setActive(true);
                }
                updateMediaSessionState();
                updateMediaSessionMetadata();
                NotificationManager mgr = getSystemService(NotificationManager.class);
                if (mgr != null) {
                    mgr.notify(NOTIFICATION_ID, buildNotification());
                }
                return START_STICKY;
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    public static void updateNotification(Context context, String title, String artist,
                                           Bitmap albumArt, boolean paused,
                                           long durationMs, long positionMs) {
        currentTitle = (title != null && !title.isEmpty()) ? title : "SoundShare Stream";
        currentArtist = (artist != null && !artist.isEmpty()) ? artist : "Receiving audio stream...";
        currentAlbumArt = albumArt;
        currentPaused = paused;
        currentDurationMs = durationMs;
        currentPositionMs = positionMs;
        try {
            Intent updateIntent = new Intent(context, AudioReceiveService.class);
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
                    CHANNEL_ID,
                    "Audio Receiving",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps audio receiving alive in background");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent skipPrevPending = PendingIntent.getBroadcast(
                this, 1, new Intent(BROADCAST_SKIP_PREV).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent playPausePending = PendingIntent.getBroadcast(
                this, 2, new Intent(BROADCAST_TOGGLE).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent skipNextPending = PendingIntent.getBroadcast(
                this, 3, new Intent(BROADCAST_SKIP_NEXT).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stopPending = PendingIntent.getBroadcast(
                this, 4, new Intent(BROADCAST_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int playPauseIcon = currentPaused ? R.drawable.ic_play : R.drawable.ic_pause;
        String playPauseLabel = currentPaused ? "Play" : "Pause";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSubText("SoundShare")
                .setSmallIcon(R.drawable.ic_play)
                .setOngoing(!currentPaused)
                .setSilent(true)
                .setContentIntent(openPending)
                .addAction(R.drawable.ic_skip_previous, "Previous", skipPrevPending)
                .addAction(playPauseIcon, playPauseLabel, playPausePending)
                .addAction(R.drawable.ic_skip_next, "Next", skipNextPending)
                .addAction(R.drawable.ic_stop, "Stop", stopPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFF3B82F6);

        if (currentAlbumArt != null) {
            builder.setLargeIcon(currentAlbumArt);
        }

        if (mediaSession != null) {
            builder.setStyle(new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPending));
        }

        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        currentTitle = "SoundShare Stream";
        currentArtist = "Receiving audio stream...";
        currentAlbumArt = null;
        currentPaused = false;
        currentDurationMs = 0;
        currentPositionMs = 0;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        releaseLocks();
        stopForeground(true);
    }
}