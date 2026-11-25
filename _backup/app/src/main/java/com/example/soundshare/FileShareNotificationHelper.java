package com.example.soundshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Manages rich media playback notifications with album art and synchronized transport controls
 * for the File Share & Sync Play mode on both Host and Receiver devices.
 */
public class FileShareNotificationHelper {

    private static final String CHANNEL_ID = "soundshare_fileshare_playback";
    private static final int NOTIFICATION_ID = 2005;

    public static final String BROADCAST_FILE_PLAY_PAUSE = "com.example.soundshare.FILE_PLAY_PAUSE";
    public static final String BROADCAST_FILE_NEXT = "com.example.soundshare.FILE_NEXT";
    public static final String BROADCAST_FILE_PREV = "com.example.soundshare.FILE_PREV";
    public static final String BROADCAST_FILE_STOP = "com.example.soundshare.FILE_STOP";

    private final Context context;
    private final NotificationManager notificationManager;
    private MediaSessionCompat mediaSession;

    private String currentTitle = "Audio Track";
    private String currentArtist = "Synced Playback";
    private String currentFormat = "Lossless";
    private Bitmap currentAlbumArt = null;
    private boolean isPlaying = false;
    private long currentDurationMs = 0;
    private long currentPositionMs = 0;

    public FileShareNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        setupMediaSession();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Synced Audio Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows album art and synchronized playback controls");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void setupMediaSession() {
        try {
            if (mediaSession != null) {
                try { mediaSession.release(); } catch (Exception ignored) {}
            }
            mediaSession = new MediaSessionCompat(context, "SoundShareSyncSession");
            mediaSession.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            );
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public void onPlay() {
                    sendExplicitBroadcast(BROADCAST_FILE_PLAY_PAUSE);
                }

                @Override
                public void onPause() {
                    sendExplicitBroadcast(BROADCAST_FILE_PLAY_PAUSE);
                }

                @Override
                public void onSkipToNext() {
                    sendExplicitBroadcast(BROADCAST_FILE_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    sendExplicitBroadcast(BROADCAST_FILE_PREV);
                }

                @Override
                public void onStop() {
                    sendExplicitBroadcast(BROADCAST_FILE_STOP);
                }

                @Override
                public void onSeekTo(long pos) {
                    currentPositionMs = pos;
                    updateMediaSessionState();
                }
            });
            mediaSession.setActive(true);
        } catch (Exception ignored) {}
    }

    private void sendExplicitBroadcast(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public void updateNotification(String title, String artist, String formatDesc,
                                   Bitmap albumArt, boolean playing,
                                   long durationMs, long positionMs) {
        this.currentTitle = (title != null && !title.isEmpty()) ? title : "Audio Track";
        this.currentArtist = (artist != null && !artist.isEmpty()) ? artist : "Synced Playback";
        this.currentFormat = (formatDesc != null) ? formatDesc : "";
        this.currentAlbumArt = albumArt;
        this.isPlaying = playing;
        this.currentDurationMs = durationMs;
        this.currentPositionMs = positionMs;

        if (mediaSession == null) {
            setupMediaSession();
        } else if (!mediaSession.isActive()) {
            mediaSession.setActive(true);
        }

        updateMediaSessionState();
        updateMediaSessionMetadata();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
            int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
            PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(state, currentPositionMs, isPlaying ? 1.0f : 0f, SystemClock.elapsedRealtime())
                    .build();
            mediaSession.setPlaybackState(playbackState);
        } catch (Exception ignored) {}
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null) return;
        try {
            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentFormat);
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

    private Notification buildNotification() {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Action PendingIntents
        PendingIntent prevIntent = PendingIntent.getBroadcast(
                context, 1, new Intent(BROADCAST_FILE_PREV).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent playPauseIntent = PendingIntent.getBroadcast(
                context, 2, new Intent(BROADCAST_FILE_PLAY_PAUSE).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent nextIntent = PendingIntent.getBroadcast(
                context, 3, new Intent(BROADCAST_FILE_NEXT).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent = PendingIntent.getBroadcast(
                context, 4, new Intent(BROADCAST_FILE_STOP).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String subtitle = currentArtist + (currentFormat.isEmpty() ? "" : " • " + currentFormat);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_nav_fileshare)
                .setContentIntent(contentIntent)
                .setOngoing(isPlaying)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
                .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play,
                        isPlaying ? "Pause" : "Play", playPauseIntent)
                .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopIntent);

        if (currentAlbumArt != null) {
            builder.setLargeIcon(currentAlbumArt);
        }

        if (mediaSession != null) {
            builder.setStyle(new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent));
        }

        return builder.build();
    }

    public void clearNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            } catch (Exception ignored) {}
        }
    }
}
