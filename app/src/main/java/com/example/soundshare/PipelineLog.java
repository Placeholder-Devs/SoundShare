package com.example.soundshare;

import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tagged logging for the audio/network pipeline (FIX_PLAN.md §3, fix #32).
 * Filter with: {@code adb logcat -s SoundSharePipe}.
 * Debug lines need: {@code adb shell setprop log.tag.SoundSharePipe DEBUG}.
 *
 * Messages are formatted as "[STAGE] message". IPv4 addresses are redacted (third octet → 'x')
 * in i/w/e/status; d() keeps full addresses because it is opt-in (developer setprop only).
 *
 * Never pass chat text, client names, file paths or track titles to this class.
 *
 * {@link #redactIps(String)} is pure Java so it can be unit-tested on the JVM; the Log calls
 * are wrapped so a stray call from JVM code ("Method ... not mocked") can never crash.
 */
final class PipelineLog {
    static final String TAG = "SoundSharePipe";

    /** Dotted-quad IPv4 not embedded in a longer run of digits. Groups: 1, 2, 3, 4 = octets. */
    private static final Pattern IPV4 = Pattern.compile(
            "(?<!\\d)(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\d)");

    private PipelineLog() {}

    static void i(String stage, String msg) {
        try {
            Log.i(TAG, format(stage, redactIps(msg)));
        } catch (RuntimeException ignored) {
            // Logging must never break the pipeline.
        }
    }

    static void w(String stage, String msg) {
        try {
            Log.w(TAG, format(stage, redactIps(msg)));
        } catch (RuntimeException ignored) {
        }
    }

    static void e(String stage, String msg, Throwable t) {
        try {
            if (t != null) {
                Log.e(TAG, format(stage, redactIps(msg)), t);
            } else {
                Log.e(TAG, format(stage, redactIps(msg)));
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Only emitted when {@code Log.isLoggable(TAG, Log.DEBUG)} (setprop log.tag.SoundSharePipe DEBUG). */
    static void d(String stage, String msg) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, format(stage, msg));
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Mirrors a user-visible status line into logcat. */
    static void status(String msg) {
        try {
            Log.i(TAG, "STATUS " + redactIps(msg));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Replaces the third octet of every IPv4 dotted quad with 'x' (192.168.1.5 → 192.168.x.5).
     * Returns null for null. Pure: no Android calls.
     */
    static String redactIps(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = IPV4.matcher(s);
        if (!m.find()) return s;
        StringBuffer sb = new StringBuffer(s.length());
        do {
            String repl = m.group(1) + "." + m.group(2) + ".x." + m.group(4);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String format(String stage, String msg) {
        return "[" + (stage == null ? "?" : stage) + "] " + (msg == null ? "null" : msg);
    }
}
