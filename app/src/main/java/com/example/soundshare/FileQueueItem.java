package com.example.soundshare;

import android.net.Uri;

/**
 * Model representing an audio track in the synchronized playback queue with artist, album, and format metadata.
 */
public class FileQueueItem {
    private final String id;
    private final String fileName;
    private final String artist;
    private final String album;
    private final long fileSize;
    private final String mimeType;
    private final int durationMs;
    private final String durationStr;
    private final String formatDesc;
    private Uri localUri;
    private String localFilePath;
    private boolean isReady;

    public FileQueueItem(String id, String fileName, String artist, String album,
                         long fileSize, String mimeType, int durationMs,
                         String durationStr, String formatDesc,
                         Uri localUri, String localFilePath, boolean isReady) {
        this.id = id;
        this.fileName = fileName;
        this.artist = (artist != null && !artist.isEmpty()) ? artist : "Unknown Artist";
        this.album = (album != null && !album.isEmpty()) ? album : "";
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.durationMs = durationMs;
        this.durationStr = durationStr;
        this.formatDesc = formatDesc;
        this.localUri = localUri;
        this.localFilePath = localFilePath;
        this.isReady = isReady;
    }

    public String getId() { return id; }
    public String getFileName() { return fileName; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getFileSize() { return fileSize; }
    public String getMimeType() { return mimeType; }
    public int getDurationMs() { return durationMs; }
    public String getDurationStr() { return durationStr; }
    public String getFormatDesc() { return formatDesc; }
    public Uri getLocalUri() { return localUri; }
    public void setLocalUri(Uri uri) { this.localUri = uri; }
    public String getLocalFilePath() { return localFilePath; }
    public void setLocalFilePath(String path) { this.localFilePath = path; }
    public boolean isReady() { return isReady; }
    public void setReady(boolean ready) { this.isReady = ready; }
}
