package com.example.soundshare;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class ChatNotificationHelper {

    private static final String TAG = "ChatNotif";
    private static final String CHANNEL_ID = "soundshare_chat_alert";
    private static final String CHANNEL_NAME = "Chat Messages";
    private static final int NOTIFICATION_ID = 7777;

    private final Context context;
    private volatile boolean isAppInForeground = true;

    // Store message history for notification style
    private final List<ChatEntry> messageHistory = new ArrayList<>();
    private int unreadCount = 0;

    private static class ChatEntry {
        final String sender;
        final String message;
        final long timestamp;

        ChatEntry(String sender, String message) {
            this.sender = sender;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public ChatNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            // Remove the legacy channel only if it still exists (one-time migration).
            if (manager.getNotificationChannel("soundshare_chat") != null) {
                manager.deleteNotificationChannel("soundshare_chat");
            }
            // Create once; never delete + re-create (FIX_PLAN #25).
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return;

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            AudioAttributes audioAttr = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH  // HIGH = heads-up alert
            );
            channel.setDescription("Chat message alerts from SoundShare");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 150, 300});
            channel.enableLights(true);
            channel.setLightColor(0xFF42A5F5);
            channel.setShowBadge(true);
            channel.setSound(soundUri, audioAttr);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(false);

            manager.createNotificationChannel(channel);
            Log.i(TAG, "Notification channel created: " + CHANNEL_ID);
        }
    }

    public void setAppInForeground(boolean foreground) {
        this.isAppInForeground = foreground;
        if (foreground) {
            clearAllNotifications();
        }
    }

    public void showChatNotification(String sender, String message) {
        // Don't notify if app is in foreground
        if (isAppInForeground) {
            Log.d(TAG, "App in foreground, skipping notification");
            return;
        }

        // Check permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted");
                return;
            }
        }

        // Add to history
        messageHistory.add(new ChatEntry(sender, message));
        unreadCount++;

        // Keep max 20 messages in history
        while (messageHistory.size() > 20) {
            messageHistory.remove(0);
        }

        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);

            // Intent to open app
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            openIntent.putExtra("from_notification", true);

            PendingIntent openPending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Mark as read action
            Intent markReadIntent = new Intent(context, MainActivity.class);
            markReadIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            markReadIntent.putExtra("mark_read", true);

            PendingIntent markReadPending = PendingIntent.getActivity(
                    context, 1, markReadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Build messaging style for chat history in notification
            Person you = new Person.Builder()
                    .setName("You")
                    .build();

            NotificationCompat.MessagingStyle messagingStyle =
                    new NotificationCompat.MessagingStyle(you)
                            .setConversationTitle(unreadCount > 1
                                    ? "SoundShare Chat (" + unreadCount + " new)"
                                    : "SoundShare Chat");

            // Add all messages to the notification
            for (ChatEntry entry : messageHistory) {
                Person senderPerson = new Person.Builder()
                        .setName(entry.sender)
                        .build();

                messagingStyle.addMessage(
                        entry.message,
                        entry.timestamp,
                        senderPerson
                );
            }

            // Sound URI
            Uri soundUri = RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION);

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setStyle(messagingStyle)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                            .setContentIntent(openPending)
                            .setAutoCancel(true)
                            .setSound(soundUri)
                            .setVibrate(new long[]{0, 300, 150, 300})
                            .setLights(0xFF42A5F5, 500, 500)
                            .setColor(0xFF42A5F5)
                            .setNumber(unreadCount)
                            .setTicker("💬 " + sender + ": " + message)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setDefaults(NotificationCompat.DEFAULT_ALL)
                            .addAction(
                                    android.R.drawable.ic_menu_view,
                                    "Open Chat",
                                    openPending
                            )
                            .addAction(
                                    android.R.drawable.ic_menu_close_clear_cancel,
                                    "Mark Read",
                                    markReadPending
                            );

            // For heads-up on older Android
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL);
                builder.setPriority(NotificationCompat.PRIORITY_MAX);
            }

            // Full screen intent for maximum attention (like calls)
            builder.setFullScreenIntent(openPending, true);

            manager.notify(NOTIFICATION_ID, builder.build());

            // Never log chat sender or text (privacy, FIX_PLAN #25 / #32).
            Log.i(TAG, "Notification posted (unread: " + unreadCount + ")");

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException posting notification", e);
        } catch (Exception e) {
            Log.e(TAG, "Error posting notification", e);
        }
    }

    public void clearAllNotifications() {
        try {
            NotificationManager manager =
                    context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
            messageHistory.clear();
            unreadCount = 0;
            Log.d(TAG, "Notifications cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing notifications", e);
        }
    }
}