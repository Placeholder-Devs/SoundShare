// src/main/java/com/example/soundshare/ChatMessage.java
package com.example.soundshare;

public class ChatMessage {
    public static final int TYPE_SENT = 0;
    public static final int TYPE_RECEIVED = 1;
    public static final int TYPE_SYSTEM = 2;

    private final String sender;
    private final String message;
    private final int type;
    private final long timestamp;

    public ChatMessage(String sender, String message, int type) {
        this.sender = sender;
        this.message = message;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSender() { return sender; }
    public String getMessage() { return message; }
    public int getType() { return type; }
    public long getTimestamp() { return timestamp; }

    public String getFormattedTime() {
        long sec = (System.currentTimeMillis() - timestamp) / 1000;
        if (sec < 60) return "just now";
        if (sec < 3600) return (sec / 60) + "m ago";
        return (sec / 3600) + "h ago";
    }
}