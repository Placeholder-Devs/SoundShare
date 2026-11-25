package com.example.soundshare;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;

import java.io.InputStream;
import java.util.List;

public class MediaMetadataHelper {

    private static final String TAG = "MediaMetadataHelper";

    public static class NowPlayingInfo {
        public final String title;
        public final String artist;
        public final String album;
        public final Bitmap albumArt;
        public final long durationMs;
        public final long positionMs;
        public final boolean isPlaying;

        public NowPlayingInfo(String title, String artist, String album,
                              Bitmap albumArt, long durationMs, 
                              long positionMs, boolean isPlaying) {
            this.title = title != null ? title : "Unknown";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.albumArt = albumArt;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.isPlaying = isPlaying;
        }

        public String getSubtitle() {
            StringBuilder sb = new StringBuilder();
            if (!artist.isEmpty()) sb.append(artist);
            if (!album.isEmpty()) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(album);
            }
            return sb.length() > 0 ? sb.toString() : "Audio Stream";
        }
    }

    public static NowPlayingInfo getCurrentMedia(Context context) {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;

            ComponentName cn = new ComponentName(context, NLService.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);
            if (controllers.isEmpty()) return null;

            String myPackage = context.getPackageName();

            for (MediaController mc : controllers) {
                if (myPackage.equals(mc.getPackageName())) continue;
                
                PlaybackState state = mc.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return extractInfo(context, mc, true);
                }
            }

            for (MediaController mc : controllers) {
                if (myPackage.equals(mc.getPackageName())) continue;
                PlaybackState state = mc.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PAUSED) {
                    return extractInfo(context, mc, false);
                }
            }

            return null;

        } catch (Exception e) {
            Log.w(TAG, "Error getting media: " + e.getMessage());
            return null;
        }
    }

    private static NowPlayingInfo extractInfo(Context context, MediaController mc, boolean playing) {
        MediaMetadata meta = mc.getMetadata();
        if (meta == null) return null;

        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null || artist.isEmpty()) {
            artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        String album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // Try to get high quality art from Poweramp
        Bitmap albumArt = getHighQualityArt(context, meta);

        long position = 0;
        PlaybackState state = mc.getPlaybackState();
        if (state != null) {
            position = state.getPosition();
            if (playing && state.getPlaybackSpeed() > 0 && state.getLastPositionUpdateTime() > 0) {
                long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
                position += (long) (elapsed * state.getPlaybackSpeed());
                if (duration > 0 && position > duration) position = duration;
            }
        }

        return new NowPlayingInfo(title, artist, album, albumArt, duration, position, playing);
    }

    /** Improved method to get clearer album art from Poweramp */
    private static Bitmap getHighQualityArt(Context context, MediaMetadata meta) {
        Bitmap bestArt = null;

        // 1. Try URI first (Poweramp often provides this)
        String[] uriKeys = {
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            "android.media.metadata.ALBUM_ART_URI",
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        };

        for (String key : uriKeys) {
            try {
                String uriStr = meta.getString(key);
                if (uriStr == null || uriStr.isEmpty()) continue;

                Uri uri = Uri.parse(uriStr);
                InputStream is = context.getContentResolver().openInputStream(uri);
                if (is != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp != null) {
                        Log.d(TAG, "Loaded from URI: " + bmp.getWidth() + "x" + bmp.getHeight());
                        if (bmp.getWidth() > 400) return bmp; // Good quality
                        if (bestArt == null) bestArt = bmp;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Try direct bitmap from session
        try {
            Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp != null) {
                if (bmp.getWidth() > 400) return bmp;
                if (bestArt == null || bmp.getWidth() > bestArt.getWidth()) {
                    bestArt = bmp;
                }
            }
        } catch (Exception ignored) {}

        return bestArt;
    }

    // Transport controls (unchanged)
    private static MediaController getActiveController(Context context) {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;

            ComponentName cn = new ComponentName(context, NLService.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);
            if (controllers.isEmpty()) return null;

            String myPackage = context.getPackageName();
            for (MediaController mc : controllers) {
                if (myPackage.equals(mc.getPackageName())) continue;
                PlaybackState state = mc.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return mc;
            }
            for (MediaController mc : controllers) {
                if (myPackage.equals(mc.getPackageName())) continue;
                PlaybackState state = mc.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PAUSED) return mc;
            }
            for (MediaController mc : controllers) {
                if (myPackage.equals(mc.getPackageName())) continue;
                return mc;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void dispatchMediaKey(Context context, int keyCode) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long eventTime = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Exception ignored) {}
    }

    public static void sendPlay(Context context) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().play();
            return;
        }
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    public static void sendPause(Context context) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().pause();
            return;
        }
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public static void sendSkipNext(Context context) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().skipToNext();
            return;
        }
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void sendSkipPrevious(Context context) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().skipToPrevious();
            return;
        }
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public static class NLService extends NotificationListenerService {}
}
