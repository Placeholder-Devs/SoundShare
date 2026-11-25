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

    /** Longest side of album art decoded from a content URI (services F11). */
    private static final int ART_MAX_SIDE = 512;
    /** Window in which a media-session callback is treated as the echo of our own key. */
    private static final long ECHO_WINDOW_MS = 500;
    private static volatile long sLastSelfDispatchUptime = -ECHO_WINDOW_MS * 2;

    /** Last URI-decoded art (decoding is skipped while the URI is unchanged). */
    private static final Object ART_CACHE_LOCK = new Object();
    private static String sCachedArtUri;
    private static Bitmap sCachedArt;
    private static boolean sCachedArtDownsampled;

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

                boolean[] downsampled = new boolean[1];
                // Cache key includes title/album: some apps reuse one URI for every track.
                String cacheKey = uriStr + "|" + meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                        + "|" + meta.getString(MediaMetadata.METADATA_KEY_ALBUM);
                Bitmap bmp = decodeUriArt(context, uriStr, cacheKey, downsampled);
                if (bmp != null) {
                    // A downsampled decode (source > ART_MAX_SIDE) is good quality by definition.
                    if (bmp.getWidth() > 400 || downsampled[0]) return bmp;
                    if (bestArt == null) bestArt = bmp;
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

    /**
     * Decodes art from a content URI with inSampleSize so the longest side is <= ART_MAX_SIDE
     * (a 3000x3000 cover would otherwise be a ~36 MB bitmap, decoded every second on the UI
     * thread). The result is cached per (URI, title, album).
     */
    private static Bitmap decodeUriArt(Context context, String uriStr, String cacheKey,
                                       boolean[] downsampledOut) throws Exception {
        synchronized (ART_CACHE_LOCK) {
            if (cacheKey.equals(sCachedArtUri) && sCachedArt != null && !sCachedArt.isRecycled()) {
                downsampledOut[0] = sCachedArtDownsampled;
                return sCachedArt;
            }
        }
        Uri uri = Uri.parse(uriStr);
        ContentResolver cr = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) return null;
        int sample = 1;
        while (longest / sample > ART_MAX_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream is = cr.openInputStream(uri)) {
            if (is == null) return null;
            bmp = BitmapFactory.decodeStream(is, null, opts);
        }
        downsampledOut[0] = sample > 1;
        if (bmp != null) {
            synchronized (ART_CACHE_LOCK) {
                sCachedArtUri = cacheKey;
                sCachedArt = bmp;
                sCachedArtDownsampled = sample > 1;
            }
        }
        return bmp;
    }

    // Transport controls
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

    /**
     * True for ~500 ms after SoundShare dispatched a media key itself. The system may route that
     * key back to SoundShare's own media session; session callbacks ignore it (FIX_PLAN #9).
     */
    public static boolean isEcho() {
        return SystemClock.uptimeMillis() - sLastSelfDispatchUptime < ECHO_WINDOW_MS;
    }

    private static void markSelfDispatch() {
        sLastSelfDispatchUptime = SystemClock.uptimeMillis();
    }

    public static void dispatchMediaKey(Context context, int keyCode) {
        markSelfDispatch();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long eventTime = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Exception ignored) {}
    }

    /** Sender behaviour: falls back to a media key when no other app's session is found. */
    public static void sendPlay(Context context) {
        sendPlay(context, true);
    }

    /**
     * @param allowKeyFallback dispatch KEYCODE_MEDIA_PLAY when no controller is found. Pass false
     *                         on devices that play audio themselves (duplex/receiver): there the
     *                         key would be routed to SoundShare's own session and loop.
     */
    public static void sendPlay(Context context, boolean allowKeyFallback) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().play();
            return;
        }
        if (allowKeyFallback) dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    /** Sender behaviour: falls back to a media key when no other app's session is found. */
    public static void sendPause(Context context) {
        sendPause(context, true);
    }

    /** @param allowKeyFallback see {@link #sendPlay(Context, boolean)} */
    public static void sendPause(Context context, boolean allowKeyFallback) {
        MediaController mc = getActiveController(context);
        if (mc != null && mc.getTransportControls() != null) {
            mc.getTransportControls().pause();
            return;
        }
        if (allowKeyFallback) dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
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
