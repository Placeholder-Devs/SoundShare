package com.example.soundshare;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to parse time-synchronized and plain lyrics, with LRCLIB & lyrics.ovh API fallback.
 */
public class LyricsHelper {
    private static final String TAG = "LyricsHelper";
    private static final Map<String, ParsedLyrics> memoryCache = new ConcurrentHashMap<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class LyricLine {
        public final long timeMs;
        public final String text;

        public LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static class ParsedLyrics {
        public final boolean isSynced;
        public final List<LyricLine> lines;
        public final String plainText;
        public final String source;

        public ParsedLyrics(boolean isSynced, List<LyricLine> lines, String plainText, String source) {
            this.isSynced = isSynced;
            this.lines = lines != null ? lines : Collections.emptyList();
            this.plainText = plainText != null ? plainText : "";
            this.source = source != null ? source : "";
        }

        public int getActiveLineIndex(long positionMs) {
            if (!isSynced || lines.isEmpty()) return -1;
            int active = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (positionMs >= lines.get(i).timeMs) {
                    active = i;
                } else {
                    break;
                }
            }
            return active;
        }

        public String getActiveLineText(long positionMs) {
            int idx = getActiveLineIndex(positionMs);
            if (idx >= 0 && idx < lines.size()) {
                return lines.get(idx).text;
            }
            return "";
        }
    }

    public interface LyricsCallback {
        void onLyricsLoaded(ParsedLyrics lyrics);
        void onLyricsError(String message);
    }

    public static void fetchLyrics(Context context, FileQueueItem item, LyricsCallback callback) {
        if (item == null) {
            mainHandler.post(() -> callback.onLyricsError("No track specified"));
            return;
        }

        String trackId = item.getId();
        // 1. Check in-memory cache
        if (memoryCache.containsKey(trackId)) {
            ParsedLyrics cached = memoryCache.get(trackId);
            mainHandler.post(() -> callback.onLyricsLoaded(cached));
            return;
        }

        new Thread(() -> {
            // 2. Check disk cache
            // trackId can come from a peer over the network: sanitize before building a path.
            File cacheFile = new File(context.getCacheDir(),
                    "lyrics_v2_" + NetUtils.sanitizeFileName(trackId) + ".json");
            if (cacheFile.exists() && cacheFile.length() > 0) {
                try {
                    String jsonStr = readFile(cacheFile);
                    if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                        JSONObject obj = new JSONObject(jsonStr);
                        boolean synced = obj.optBoolean("synced", false);
                        String rawLrc = obj.optString("lrc", "");
                        String plain = obj.optString("plain", "");
                        String src = obj.optString("source", "Cached");
                        List<LyricLine> parsedLines = synced ? parseLrc(rawLrc) : parsePlainLines(plain);
                        ParsedLyrics res = new ParsedLyrics(synced, parsedLines, plain, src);
                        memoryCache.put(trackId, res);
                        mainHandler.post(() -> callback.onLyricsLoaded(res));
                        return;
                    }
                } catch (Exception ignored) {}
            }

            // 3. Try local embedded lyrics or local .lrc file
            ParsedLyrics localLyrics = extractLocalLyrics(context, item);
            if (localLyrics != null) {
                saveToCache(cacheFile, trackId, localLyrics);
                mainHandler.post(() -> callback.onLyricsLoaded(localLyrics));
                return;
            }

            // 4. Fetch from Internet (LRCLIB & fallback)
            String rawTitle = item.getFileName();
            String rawArtist = item.getArtist();
            String cleanTitle = sanitizeTitle(rawTitle);
            String cleanArtist = sanitizeArtist(rawArtist, rawTitle);
            int durationSec = item.getDurationMs() > 0 ? (item.getDurationMs() / 1000) : 0;

            ParsedLyrics onlineLyrics = fetchFromLrclib(cleanTitle, cleanArtist, item.getAlbum(), durationSec);
            if (onlineLyrics == null) {
                onlineLyrics = searchLrclib(cleanTitle + " " + cleanArtist);
            }
            if (onlineLyrics == null) {
                onlineLyrics = fetchFromLyricsOvh(cleanArtist, cleanTitle);
            }

            if (onlineLyrics != null) {
                saveToCache(cacheFile, trackId, onlineLyrics);
                final ParsedLyrics result = onlineLyrics;
                mainHandler.post(() -> callback.onLyricsLoaded(result));
            } else {
                mainHandler.post(() -> callback.onLyricsError("No lyrics found for \"" + cleanTitle + "\""));
            }
        }, "LyricsFetchThread_" + trackId).start();
    }

    private static ParsedLyrics extractLocalLyrics(Context context, FileQueueItem item) {
        try {
            if (item.getLocalFilePath() != null) {
                File audioFile = new File(item.getLocalFilePath());
                String baseName = audioFile.getName();
                int dotIdx = baseName.lastIndexOf('.');
                if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
                File lrcFile = new File(audioFile.getParentFile(), baseName + ".lrc");
                if (lrcFile.exists()) {
                    String lrcContent = readFile(lrcFile);
                    if (lrcContent != null && !lrcContent.trim().isEmpty()) {
                        List<LyricLine> lines = parseLrc(lrcContent);
                        return new ParsedLyrics(true, lines, cleanLrcTimestamps(lrcContent), "Local .LRC");
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static ParsedLyrics fetchFromLrclib(String title, String artist, String album, int durationSec) {
        try {
            StringBuilder sb = new StringBuilder("https://lrclib.net/api/get?");
            sb.append("track_name=").append(URLEncoder.encode(title, "UTF-8"));
            if (artist != null && !artist.isEmpty() && !artist.equalsIgnoreCase("Unknown Artist")) {
                sb.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
            }
            if (album != null && !album.isEmpty()) {
                sb.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"));
            }
            if (durationSec > 0) {
                sb.append("&duration=").append(durationSec);
            }

            URL url = new URL(sb.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "SoundShare-Android/2.0");

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(resp);
                String synced = json.optString("syncedLyrics", "");
                String plain = json.optString("plainLyrics", "");

                if (!synced.trim().isEmpty()) {
                    List<LyricLine> lines = parseLrc(synced);
                    if (!lines.isEmpty()) {
                        return new ParsedLyrics(true, lines, cleanLrcTimestamps(synced), "Synced (LRCLIB)");
                    }
                }
                if (!plain.trim().isEmpty()) {
                    return new ParsedLyrics(false, parsePlainLines(plain), plain, "Plain (LRCLIB)");
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Lrclib get failed: " + e.getMessage());
        }
        return null;
    }

    private static ParsedLyrics searchLrclib(String query) {
        try {
            String urlStr = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "SoundShare-Android/2.0");

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONArray arr = new JSONArray(resp);
                if (arr.length() > 0) {
                    JSONObject first = arr.getJSONObject(0);
                    String synced = first.optString("syncedLyrics", "");
                    String plain = first.optString("plainLyrics", "");

                    if (!synced.trim().isEmpty()) {
                        List<LyricLine> lines = parseLrc(synced);
                        if (!lines.isEmpty()) {
                            return new ParsedLyrics(true, lines, cleanLrcTimestamps(synced), "Synced (LRCLIB)");
                        }
                    }
                    if (!plain.trim().isEmpty()) {
                        return new ParsedLyrics(false, parsePlainLines(plain), plain, "Plain (LRCLIB)");
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Lrclib search failed: " + e.getMessage());
        }
        return null;
    }

    private static ParsedLyrics fetchFromLyricsOvh(String artist, String title) {
        if (artist == null || artist.isEmpty() || artist.equalsIgnoreCase("Unknown Artist")) {
            return null;
        }
        try {
            String urlStr = "https://api.lyrics.ovh/v1/"
                    + URLEncoder.encode(artist, "UTF-8") + "/"
                    + URLEncoder.encode(title, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(resp);
                if (json.has("lyrics")) {
                    String plain = json.getString("lyrics");
                    if (!plain.trim().isEmpty()) {
                        return new ParsedLyrics(false, parsePlainLines(plain), plain, "Lyrics.ovh");
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Lyrics.ovh failed: " + e.getMessage());
        }
        return null;
    }

    public static List<LyricLine> parseLrc(String lrcContent) {
        List<LyricLine> list = new ArrayList<>();
        if (lrcContent == null || lrcContent.isEmpty()) return list;

        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
        String[] rawLines = lrcContent.split("\\r?\\n");

        for (String raw : rawLines) {
            Matcher m = pattern.matcher(raw);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int ms = 0;
                String msGroup = m.group(3);
                if (msGroup != null) {
                    if (msGroup.length() == 1) ms = Integer.parseInt(msGroup) * 100;
                    else if (msGroup.length() == 2) ms = Integer.parseInt(msGroup) * 10;
                    else if (msGroup.length() >= 3) ms = Integer.parseInt(msGroup.substring(0, 3));
                }
                long timeMs = (min * 60L + sec) * 1000L + ms;
                times.add(timeMs);
                lastEnd = m.end();
            }
            if (!times.isEmpty()) {
                String text = raw.substring(lastEnd).trim();
                for (long t : times) {
                    list.add(new LyricLine(t, text));
                }
            }
        }

        Collections.sort(list, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return list;
    }

    public static List<LyricLine> parsePlainLines(String plainText) {
        List<LyricLine> list = new ArrayList<>();
        if (plainText == null || plainText.isEmpty()) return list;
        String[] lines = plainText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                list.add(new LyricLine(-1, trimmed));
            }
        }
        return list;
    }

    public static String cleanLrcTimestamps(String lrc) {
        if (lrc == null) return "";
        return lrc.replaceAll("\\[\\d{2}:\\d{2}(\\.\\d{1,3})?\\]", "").trim();
    }

    public static String sanitizeTitle(String fileNameOrTitle) {
        if (fileNameOrTitle == null) return "";
        String s = fileNameOrTitle;
        s = s.replaceAll("(?i)\\.(mp3|flac|wav|m4a|aac|ogg|opus|wma|alac)$", "");
        s = s.replaceAll("^\\d{1,3}\\s*[-._]\\s*", "");
        if (s.contains(" - ")) {
            String[] parts = s.split(" - ", 2);
            if (parts.length > 1) {
                s = parts[1];
            }
        }
        s = s.replaceAll("(?i)\\[(official|video|audio|lyrics|hd|4k|hq|remastered|explicit|clean).*?\\]", "");
        s = s.replaceAll("(?i)\\((official|video|audio|lyrics|hd|4k|hq|remastered|explicit|clean).*?\\)", "");
        return s.trim();
    }

    public static String sanitizeArtist(String artist, String fileName) {
        if (artist != null && !artist.trim().isEmpty() && !artist.equalsIgnoreCase("Unknown Artist")) {
            return artist.trim();
        }
        if (fileName != null && fileName.contains(" - ")) {
            String[] parts = fileName.split(" - ", 2);
            if (parts.length > 0) {
                String potentialArtist = parts[0].replaceAll("^\\d{1,3}\\s*[-._]\\s*", "").trim();
                if (!potentialArtist.isEmpty()) return potentialArtist;
            }
        }
        return "";
    }

    private static void saveToCache(File file, String trackId, ParsedLyrics lyrics) {
        memoryCache.put(trackId, lyrics);
        try {
            JSONObject obj = new JSONObject();
            obj.put("synced", lyrics.isSynced);
            obj.put("plain", lyrics.plainText);
            obj.put("source", lyrics.source);
            if (lyrics.isSynced && !lyrics.lines.isEmpty()) {
                StringBuilder lrcSb = new StringBuilder();
                for (LyricLine ll : lyrics.lines) {
                    long min = (ll.timeMs / 1000) / 60;
                    long sec = (ll.timeMs / 1000) % 60;
                    long ms = (ll.timeMs % 1000) / 10;
                    lrcSb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, ms, ll.text));
                }
                obj.put("lrc", lrcSb.toString());
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(InputStream is) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}
