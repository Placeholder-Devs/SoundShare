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

/**
 * Foreground service (type mediaPlayback) that keeps the process alive while receiving a stream
 * ("receive" mode) or while File Share sync playback is active ("fileshare" mode).
 *
 * <p>Start/stop go through {@link #start(Context, String)} / {@link #stop(Context)}. A stop that
 * arrives while a {@code startForegroundService} is still in flight is deferred until right after
 * {@code startForeground} — stopping earlier makes the system kill the app with
 * "Context.startForegroundService() did not then call Service.startForeground()" (FIX_PLAN #8,
 * services F2).
 */
public class AudioReceiveService extends Service {

    private static final String CHANNEL_ID = "soundshare_receive";
    private static final int NOTIFICATION_ID = 2002;
    /** Id of the foreground notification; FileShareNotificationHelper reuses it in fileshare mode. */
    static final int FGS_NOTIFICATION_ID = NOTIFICATION_ID;

    public static final String MODE_RECEIVE = "receive";
    public static final String MODE_FILESHARE = "fileshare";

    public static final String ACTION_START = "START_RECEIVE_SERVICE";
    public static final String ACTION_STOP = "STOP_RECEIVING";
    public static final String ACTION_PLAY_PAUSE = "TOGGLE_PLAY_PAUSE";
    public static final String ACTION_SKIP_NEXT = "SKIP_NEXT";
    public static final String ACTION_SKIP_PREV = "SKIP_PREV";
    public static final String ACTION_UPDATE = "UPDATE_NOTIFICATION";
    private static final String EXTRA_MODE = "mode";

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

    // ---- start/stop state machine (all fields guarded by LOCK) ----
    private static final Object LOCK = new Object();
    /** startForegroundService calls not yet answered by startForeground in onStartCommand. */
    private static int sPendingStarts = 0;
    /** stop() was requested while a start was in flight; executed after startForeground. */
    private static boolean sStopPending = false;
    /** An instance has called startForeground and has not been asked to stop. */
    private static boolean sForeground = false;
    private static String sMode = MODE_RECEIVE;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Running instance (written on the main thread in onCreate/onDestroy). */
    private static volatile AudioReceiveService sInstance;

    private volatile boolean isForegroundNow = false;
    private volatile String currentMode = MODE_RECEIVE;

    private MediaSessionCompat mediaSession;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.net.wifi.WifiManager.WifiLock wifiLowLatencyLock;

    // ---------- public control API ----------

    /**
     * Starts the service in the foreground. Must be called while the app is in the foreground
     * (user tap), because Android 12+ forbids starting an FGS from the background.
     *
     * @param mode {@link #MODE_RECEIVE} or {@link #MODE_FILESHARE}
     * @return false if the start request was rejected by the system
     */
    public static boolean start(Context c, String mode) {
        if (c == null) return false;
        String m = MODE_FILESHARE.equals(mode) ? MODE_FILESHARE : MODE_RECEIVE;
        synchronized (LOCK) {
            sPendingStarts++;
            sStopPending = false;
            sMode = m;
        }
        Intent i = new Intent(c, AudioReceiveService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, m);
        try {
            ContextCompat.startForegroundService(c, i);
            PipelineLog.i("FGS_START", "receive service requested, mode=" + m);
            return true;
        } catch (Exception e) {
            PipelineLog.e("FGS_START", "startForegroundService failed, mode=" + m, e);
            boolean stopNow = false;
            synchronized (LOCK) {
                if (sPendingStarts > 0) sPendingStarts--;
                if (sPendingStarts == 0 && sStopPending) {
                    sStopPending = false;
                    sForeground = false;
                    stopNow = true;
                }
                if (stopNow) stopServiceQuietly(c);
            }
            return false;
        }
    }

    /** Stops the service regardless of its mode (deferred if a start is still in flight). */
    public static void stop(Context c) {
        stop(c, null);
    }

    /**
     * Stops the service only if it was last started in {@code mode} (null = any mode), so that
     * e.g. the receive path's cleanup cannot kill a File Share session's service.
     */
    public static void stop(Context c, @Nullable String mode) {
        if (c == null) return;
        synchronized (LOCK) {
            if (mode != null && !mode.equals(sMode)) return;
            if (sPendingStarts > 0) {
                sStopPending = true;
                PipelineLog.i("FGS_STOP", "receive service stop deferred until startForeground");
                return;
            }
            sForeground = false;
            sStopPending = false;
            // Called under LOCK so a concurrent start() cannot slip its startForegroundService
            // in before this stopService (which would bring down a not-yet-foreground start).
            stopServiceQuietly(c);
        }
    }

    /** True while an instance is foreground in the given mode (main-thread accurate). */
    public static boolean isForegroundInMode(String mode) {
        AudioReceiveService s = sInstance;
        return s != null && s.isForegroundNow && mode != null && mode.equals(s.currentMode);
    }

    private static void stopServiceQuietly(Context c) {
        try {
            c.stopService(new Intent(c, AudioReceiveService.class));
        } catch (Exception e) {
            PipelineLog.w("FGS_STOP", "stopService failed: " + e.getMessage());
        }
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
    }

    private void acquireLocks() {
        // The "High Priority CPU WakeLock" setting only controls the CPU wake lock. The Wi-Fi locks below are
        // always taken; without them Wi-Fi power save makes playback stutter with the screen off (phase5 review M1).
        boolean cpuWakeLock = SettingsFragment.isWakeLockEnabled(this);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (cpuWakeLock && pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "soundshare:receive_wakelock");
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
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundshare:receive_wifilock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            PipelineLog.w("FGS_START", "wifi high-perf lock failed: " + e.getMessage());
        }
        // LOW_LATENCY (API 29+): disables Wi-Fi power save while the app is foreground/screen on.
        try {
            if (wifiLowLatencyLock == null) {
                wifiLowLatencyLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "soundshare:receive_wifilock_ll");
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
            mediaSession = new MediaSessionCompat(this, "SoundShareReceiveSession");
            mediaSession.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                // Echo guard (FIX_PLAN #9): a media key we dispatched ourselves can be routed back
                // to this session; reacting to it would toggle again and loop.
                @Override
                public void onPlay() {
                    if (MediaMetadataHelper.isEcho()) return;
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
                }

                @Override
                public void onPause() {
                    if (MediaMetadataHelper.isEcho()) return;
                    sendExplicitBroadcast(BROADCAST_TOGGLE);
                }

                @Override
                public void onSkipToNext() {
                    if (MediaMetadataHelper.isEcho()) return;
                    sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    if (MediaMetadataHelper.isEcho()) return;
                    sendExplicitBroadcast(BROADCAST_SKIP_PREV);
                }

                @Override
                public void onStop() {
                    if (MediaMetadataHelper.isEcho()) return;
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

    private void releaseMediaSession() {
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception ignored) {}
            mediaSession = null;
        }
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
            // System restart of a previously sticky instance: nothing to serve without the
            // Activity. A restart is not an fg-required start, so stopping without
            // startForeground is safe. Never stop while one of our own starts is in flight.
            boolean stopNow;
            synchronized (LOCK) { stopNow = sPendingStarts == 0 && !sForeground; }
            PipelineLog.w("FGS_STOP", "null intent (system restart), stopNow=" + stopNow);
            if (stopNow) stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Notification Stop: works even when the Activity is gone.
            sendExplicitBroadcast(MODE_FILESHARE.equals(currentMode)
                    ? FileShareNotificationHelper.BROADCAST_FILE_STOP
                    : BROADCAST_STOP);
            boolean stopNow;
            synchronized (LOCK) {
                if (sPendingStarts > 0) {
                    sStopPending = true;
                    stopNow = false;
                } else {
                    sForeground = false;
                    sStopPending = false;
                    stopNow = true;
                }
            }
            PipelineLog.i("FGS_STOP", "notification stop, mode=" + currentMode);
            // stopSelfResult: never brings down a start that reached the system after this one.
            if (stopNow) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_PAUSE.equals(action)) {
            sendExplicitBroadcast(BROADCAST_TOGGLE);
            stopIfNotForeground(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_SKIP_NEXT.equals(action)) {
            sendExplicitBroadcast(BROADCAST_SKIP_NEXT);
            stopIfNotForeground(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_SKIP_PREV.equals(action)) {
            sendExplicitBroadcast(BROADCAST_SKIP_PREV);
            stopIfNotForeground(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_UPDATE.equals(action)) {
            // Legacy path: updates now go through refreshFromStatics() on the main thread.
            if (isForegroundNow) {
                refreshFromStatics();
            } else {
                stopIfNotForeground(startId);
            }
            return START_NOT_STICKY;
        }

        // ACTION_START (or a legacy start without action): this may be an fg-required start,
        // so startForeground must be called before any stop.
        String mode = intent.getStringExtra(EXTRA_MODE);
        if (mode == null) {
            synchronized (LOCK) { mode = sMode; }
        }
        boolean ok = goForeground(mode);

        boolean stopNow = false;
        synchronized (LOCK) {
            if (sPendingStarts > 0) sPendingStarts--;
            if (ok) sForeground = true;
            if (sPendingStarts == 0 && (sStopPending || !ok)) {
                stopNow = true;
                sStopPending = false;
                sForeground = false;
            }
        }
        if (stopNow) {
            PipelineLog.i("FGS_STOP", "receive service stopping after start (deferred stop or start failure)");
            // stopSelfResult: if a newer start reached the system meanwhile, this is a no-op
            // and that newer onStartCommand takes over.
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void stopIfNotForeground(int startId) {
        boolean stopNow;
        synchronized (LOCK) { stopNow = sPendingStarts == 0 && !sForeground; }
        if (stopNow && !isForegroundNow) stopSelfResult(startId);
    }

    /** Calls startForeground for the given mode. Never throws. */
    private boolean goForeground(String mode) {
        currentMode = MODE_FILESHARE.equals(mode) ? MODE_FILESHARE : MODE_RECEIVE;
        Notification n;
        if (MODE_FILESHARE.equals(currentMode)) {
            // File Share has its own media session (FileShareNotificationHelper), which then
            // replaces this placeholder by posting on the same notification id.
            releaseMediaSession();
            n = buildFileShareNotification();
        } else {
            if (mediaSession == null) setupMediaSession();
            n = buildNotification();
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            PipelineLog.e("FGS_START", "startForeground failed, mode=" + currentMode, e);
            isForegroundNow = false;
            return false;
        }
        isForegroundNow = true;
        acquireLocks();
        PipelineLog.i("FGS_START", "receive service foreground, mode=" + currentMode);
        return true;
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
        // Never start (and thereby resurrect) the service just to update it (FIX_PLAN #8 / F6).
        if (sInstance == null) return;
        MAIN.post(() -> {
            AudioReceiveService s = sInstance;
            if (s != null) s.refreshFromStatics();
        });
    }

    /** Main thread only. Re-posts the receive-mode notification if this instance is foreground. */
    private void refreshFromStatics() {
        if (!isForegroundNow || !MODE_RECEIVE.equals(currentMode)) return;
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
        if (!isForegroundNow || !MODE_RECEIVE.equals(currentMode)) return;
        try {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            // Create once; never delete + re-create (that cancels live notifications and
            // increments Settings' "deleted categories" count). FIX_PLAN #25 / F15.
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Receiving",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps audio receiving alive in background");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
    }

    private PendingIntent buildOpenPending() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    /** Stop handled by the service itself, so it works without the Activity (F4). */
    private PendingIntent buildStopPending() {
        return PendingIntent.getService(
                this, 4, new Intent(this, AudioReceiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private Notification buildFileShareNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SoundShare File Share")
                .setContentText("Keeping playback in sync")
                .setSmallIcon(R.drawable.ic_nav_fileshare)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(buildOpenPending())
                .addAction(R.drawable.ic_stop, "Stop", buildStopPending())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private Notification buildNotification() {
        PendingIntent openPending = buildOpenPending();

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

        PendingIntent stopPending = buildStopPending();

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
        if (sInstance == this) sInstance = null;
        isForegroundNow = false;
        boolean newStartPending;
        synchronized (LOCK) {
            // A start still in flight belongs to the next instance; leave it alone.
            sForeground = false;
            newStartPending = sPendingStarts > 0;
        }
        // Keep notification state the next session may already have written (phase5 review L1).
        if (!newStartPending) {
            currentTitle = "SoundShare Stream";
            currentArtist = "Receiving audio stream...";
            currentAlbumArt = null;
            currentPaused = false;
            currentDurationMs = 0;
            currentPositionMs = 0;
        }
        releaseMediaSession();
        releaseLocks();
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}
        try {
            // Also removes any copy posted with notify() (e.g. by FileShareNotificationHelper).
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {}
        PipelineLog.i("FGS_STOP", "receive service destroyed, mode=" + currentMode);
        super.onDestroy();
    }
}
