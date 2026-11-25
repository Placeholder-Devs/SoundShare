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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
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
    public static final String BROADCAST_STOP = "com.example.soundshare.CAPTURE_STOP";

    private static volatile Runnable onServiceReady;
    private static volatile boolean isRunning = false;
    private static volatile String currentTitle = "SoundShare Stream";
    private static volatile String currentArtist = "Streaming audio...";
    private static volatile Bitmap currentAlbumArt = null;
    private static volatile boolean currentPaused = false;
    private static volatile long currentDurationMs = 0;
    private static volatile long currentPositionMs = 0;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Running instance (written on the main thread in onCreate/onDestroy). */
    private static volatile AudioCaptureService sInstance;
    private volatile boolean isForegroundNow = false;

    private MediaSessionCompat mediaSession;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.net.wifi.WifiManager.WifiLock wifiLowLatencyLock;

    public static void setOnServiceReady(Runnable callback) {
        onServiceReady = callback;
    }

    // ---- start/stop guard (phase5 review services M2; same idea as AudioReceiveService) ----
    private static final Object LOCK = new Object();
    /** startForegroundService calls not yet answered by startForeground in onStartCommand. */
    private static int sPendingStarts = 0;
    /** stop() arrived while a start was in flight; executed right after startForeground. */
    private static boolean sStopPending = false;

    /**
     * Starts the capture FGS. Call while the app is in the foreground (consent result).
     * @return false if the system rejected the start (the ready-callback will never run)
     */
    public static boolean start(Context c) {
        if (c == null) return false;
        synchronized (LOCK) {
            sPendingStarts++;
            sStopPending = false;
        }
        try {
            ContextCompat.startForegroundService(c, new Intent(c, AudioCaptureService.class));
            PipelineLog.i("FGS_START", "capture service requested");
            return true;
        } catch (Exception e) {
            PipelineLog.e("FGS_START", "capture startForegroundService failed", e);
            synchronized (LOCK) {
                if (sPendingStarts > 0) sPendingStarts--;
                if (sPendingStarts == 0) sStopPending = false;
            }
            return false;
        }
    }

    /**
     * Stops the capture FGS. A stop that arrives before startForeground ran is deferred, because
     * bringing down a not-yet-foreground FGS start crashes the app ("did not then call
     * startForeground"). @return true if a stop was issued or deferred.
     */
    public static boolean stop(Context c) {
        if (c == null) return false;
        synchronized (LOCK) {
            if (sPendingStarts > 0) {
                sStopPending = true;
                PipelineLog.i("FGS_STOP", "capture service stop deferred until startForeground");
                return true;
            }
            sStopPending = false;
            try {
                // under LOCK so a concurrent start() cannot slip in before this stop
                return c.stopService(new Intent(c, AudioCaptureService.class));
            } catch (Exception e) {
                PipelineLog.w("FGS_STOP", "capture stopService failed: " + e.getMessage());
                return false;
            }
        }
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
        sInstance = this;
        createNotificationChannel();
        setupMediaSession();
        acquireLocks();
    }

    private void acquireLocks() {
        // The "High Priority CPU WakeLock" setting only controls the CPU wake lock. The Wi-Fi locks below are
        // always taken; without them Wi-Fi power save makes playback stutter with the screen off (phase5 review M1).
        boolean cpuWakeLock = SettingsFragment.isWakeLockEnabled(this);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (cpuWakeLock && pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "soundshare:capture_wakelock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12 * 60 * 60 * 1000L); // 12h max safe timeout
            }
        } catch (Exception e) {
            PipelineLog.w("FGS_START", "wake lock failed: " + e.getMessage());
        }
        android.net.wifi.WifiManager wm = null;
        try {
            wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Exception ignored) {}
        if (wm == null) return;
        // HIGH_PERF: effective up to Android 13; on 14+ it is mapped to LOW_LATENCY semantics.
        try {
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundshare:capture_wifilock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            PipelineLog.w("FGS_START", "wifi high-perf lock failed: " + e.getMessage());
        }
        // LOW_LATENCY (API 29+): disables Wi-Fi power save while the app is foreground/screen on.
        try {
            if (wifiLowLatencyLock == null) {
                wifiLowLatencyLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "soundshare:capture_wifilock_ll");
                wifiLowLatencyLock.setReferenceCounted(false);
                wifiLowLatencyLock.acquire();
            }
        } catch (Exception e) {
            PipelineLog.w("FGS_START", "wifi low-latency lock failed: " + e.getMessage());
        }
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
        try {
            if (wifiLowLatencyLock != null && wifiLowLatencyLock.isHeld()) {
                wifiLowLatencyLock.release();
            }
        } catch (Exception ignored) {}
        wifiLowLatencyLock = null;
    }

    private void setupMediaSession() {
        try {
            if (mediaSession != null) {
                try { mediaSession.release(); } catch (Exception ignored) {}
            }
            mediaSession = new MediaSessionCompat(this, "SoundShareCaptureSession");
            mediaSession.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    if (MediaMetadataHelper.isEcho()) return; // self-dispatched key (FIX_PLAN #9)
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
                }

                @Override
                public void onPause() {
                    if (MediaMetadataHelper.isEcho()) return; // self-dispatched key (FIX_PLAN #9)
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
                }

                @Override
                public void onSkipToNext() {
                    if (MediaMetadataHelper.isEcho()) return; // self-dispatched key (FIX_PLAN #9)
                    sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    if (MediaMetadataHelper.isEcho()) return; // self-dispatched key (FIX_PLAN #9)
                    sendExplicitBroadcast(BROADCAST_SKIP_PREV);
                }

                @Override
                public void onStop() {
                    if (MediaMetadataHelper.isEcho()) return; // self-dispatched key (FIX_PLAN #9)
                    sendExplicitBroadcast(BROADCAST_STOP);
                }

                @Override
                public void onSeekTo(long pos) {
                    currentPositionMs = pos;
                    updateMediaSessionState();
                    postNotificationIfForeground();
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
        if (intent == null) {
            // Only a system restart delivers a null intent (never an fg-required start).
            // START_NOT_STICKY makes this unreachable in practice; never resurrect.
            PipelineLog.w("FGS_STOP", "capture service null intent, stopping");
            if (!isForegroundNow) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent.getAction() != null) {
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
                // Legacy path: updates now go through refreshFromStatics() on the main thread.
                if (isForegroundNow) {
                    refreshFromStatics();
                } else {
                    stopSelfResult(startId);
                }
                return START_NOT_STICKY;
            }
        }

        Notification notification = buildNotification();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForegroundNow = true;
            isRunning = true;
            PipelineLog.i("FGS_START", "capture service foreground");
        } catch (Exception e) {
            // Logged for diagnostics. The ready-callback still runs: getMediaProjection then
            // fails and the launcher shows the error and calls stopForegroundService().
            PipelineLog.e("FGS_START", "capture startForeground failed", e);
        }

        boolean stopNow;
        synchronized (LOCK) {
            if (sPendingStarts > 0) sPendingStarts--;
            stopNow = sPendingStarts == 0 && sStopPending;
            if (stopNow) sStopPending = false;
        }
        if (stopNow) {
            // The session was cancelled while this start was in flight: don't start capture.
            onServiceReady = null;
            PipelineLog.i("FGS_STOP", "deferred capture stop executed");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

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
        // Never start (and thereby resurrect) the service just to update it (FIX_PLAN #8 / F6).
        if (sInstance == null) return;
        MAIN.post(() -> {
            AudioCaptureService s = sInstance;
            if (s != null) s.refreshFromStatics();
        });
    }

    /** Main thread only. Re-posts the notification if this instance is foreground. */
    private void refreshFromStatics() {
        if (!isForegroundNow) return;
        if (mediaSession == null) {
            setupMediaSession();
        } else if (!mediaSession.isActive()) {
            mediaSession.setActive(true);
        }
        updateMediaSessionState();
        updateMediaSessionMetadata();
        postNotificationIfForeground();
    }

    private void postNotificationIfForeground() {
        if (!isForegroundNow) return;
        try {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            // Create once; never delete + re-create (FIX_PLAN #25 / F15).
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Audio Streaming",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent skipPrevPending = PendingIntent.getBroadcast(
                this, 1, new Intent(BROADCAST_SKIP_PREV).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent playPausePending = PendingIntent.getBroadcast(
                this, 2, new Intent(BROADCAST_TOGGLE).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent skipNextPending = PendingIntent.getBroadcast(
                this, 3, new Intent(BROADCAST_SKIP_NEXT).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopPending = PendingIntent.getBroadcast(
                this, 4, new Intent(BROADCAST_STOP).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
                .setColor(0xFF34D399);

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
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        isForegroundNow = false;
        isRunning = false;
        boolean newStartPending;
        synchronized (LOCK) { newStartPending = sPendingStarts > 0; }
        // A start in flight belongs to the next instance: keep its ready-callback and the
        // notification state it may already have written (phase5 review services L1).
        if (!newStartPending) {
            onServiceReady = null;
            currentTitle = "SoundShare Stream";
            currentArtist = "Streaming audio...";
            currentAlbumArt = null;
            currentPaused = false;
            currentDurationMs = 0;
            currentPositionMs = 0;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        releaseLocks();
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}
        try {
            // Also removes any copy posted with notify() after the foreground state ended.
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
        PipelineLog.i("FGS_STOP", "capture service destroyed");
        super.onDestroy();
    }
}