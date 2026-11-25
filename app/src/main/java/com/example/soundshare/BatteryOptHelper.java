package com.example.soundshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * One-time hint that the phone's battery optimisation may stop background playback.
 * Contract: FIX_PLAN.md §3 (owner M6), fix #31.
 *
 * Policy-safe: no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission, no direct
 * "request exemption" intent and no undocumented OEM autostart component intents.
 * It only opens the system battery-optimisation list (no permission needed) or,
 * if that screen is missing, the app's own App info page.
 */
final class BatteryOptHelper {
    private static final String TAG = "BatteryOptHelper";
    private static final String PREFS_NAME = "soundshare_battery_hint";
    private static final String KEY_SHOWN = "hint_shown";

    private BatteryOptHelper() {}

    /**
     * True if the app is exempt from battery optimisation ("Unrestricted" / "Don't optimise").
     * Returns true on any error, so that a failure never triggers the hint.
     */
    static boolean isIgnoringOptimizations(Context c) {
        try {
            if (c == null) return true;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "isIgnoringBatteryOptimizations failed", t);
            return true;
        }
    }

    /**
     * Shows the hint dialog at most once per install, only if the app is not exempt and
     * the activity is still alive. Safe to call from any thread. Never throws.
     */
    static void maybeShowHint(final Activity a) {
        try {
            if (a == null) return;
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showIfNeeded(a);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "maybeShowHint failed", t);
        }
    }

    private static void showIfNeeded(final Activity a) {
        try {
            if (a.isFinishing() || a.isDestroyed()) return;
            final SharedPreferences prefs =
                    a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_SHOWN, false)) return;
            if (isIgnoringOptimizations(a)) return;

            // Mark first, so two quick calls (e.g. Receive then FileShare) never stack dialogs.
            prefs.edit().putBoolean(KEY_SHOWN, true).apply();

            String message = "Your phone's battery optimisation may stop SoundShare "
                    + "playing in the background or with the screen off.\n\n"
                    + "To keep it running, set SoundShare to unrestricted battery use:\n"
                    + manufacturerTip();

            new AlertDialog.Builder(a)
                    .setTitle("Keep SoundShare running")
                    .setMessage(message)
                    .setPositiveButton("Open settings", (d, w) -> openSettings(a))
                    .setNegativeButton("Not now", (d, w) -> {
                        try { d.dismiss(); } catch (Throwable ignored) { }
                    })
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "Could not show battery hint", t);
        }
    }

    /** One-line, manufacturer-specific instruction (plain text; no OEM intents). */
    static String manufacturerTip() {
        String m = lower(Build.MANUFACTURER);
        String b = lower(Build.BRAND);
        if (has(m, b, "xiaomi") || has(m, b, "redmi") || has(m, b, "poco")) {
            return "Settings → Apps → SoundShare → Battery saver → "
                    + "No restrictions, and enable Autostart.";
        }
        if (has(m, b, "oppo") || has(m, b, "realme") || has(m, b, "oneplus")) {
            return "Settings → Apps → SoundShare → Battery → "
                    + "Allow background activity.";
        }
        if (has(m, b, "vivo") || has(m, b, "iqoo")) {
            return "Settings → Apps → SoundShare → Battery → "
                    + "Background power consumption → Allow.";
        }
        if (has(m, b, "huawei") || has(m, b, "honor")) {
            return "Settings → Battery → App launch → SoundShare → "
                    + "Manage manually (allow all).";
        }
        if (has(m, b, "samsung")) {
            return "Settings → Apps → SoundShare → Battery → "
                    + "Unrestricted, and remove it from Sleeping apps.";
        }
        return "Settings → Apps → SoundShare → Battery → Unrestricted "
                + "(or \"Don't optimise\").";
    }

    private static void openSettings(Activity a) {
        try {
            if (a.isFinishing() || a.isDestroyed()) return;
        } catch (Throwable ignored) {
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Battery optimisation settings not available, falling back to App info");
        } catch (Throwable t) {
            Log.w(TAG, "Battery optimisation settings failed, falling back to App info", t);
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.getPackageName(), null));
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "App info settings not available");
        } catch (Throwable t) {
            Log.w(TAG, "App info settings failed", t);
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean has(String manufacturer, String brand, String key) {
        return manufacturer.contains(key) || brand.contains(key);
    }
}
