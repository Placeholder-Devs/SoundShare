package com.example.soundshare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java networking helpers (no Android dependencies, JVM unit-testable).
 * Contract: FIX_PLAN.md §3 (owner M3).
 */
final class NetUtils {
    private NetUtils() {}

    /** One IPv4 address found on a network interface. */
    static final class Candidate {
        final String iface;
        final String ipv4;

        Candidate(String iface, String ipv4) {
            this.iface = iface;
            this.ipv4 = ipv4;
        }
    }

    /** Interface name prefixes that never carry the LAN (cellular, VPN, CLAT, dummy...). */
    private static final String[] EXCLUDED_IFACE_PREFIXES = {
            "rmnet", "ccmni", "tun", "ppp", "v4-", "clat", "dummy", "ipsec", "r_rmnet", "ifb"
    };

    /** Preferred interface name prefixes, best first. Unlisted names rank after all of these. */
    private static final String[] PREFERRED_IFACE_PREFIXES = {
            "wlan", "ap", "swlan", "softap", "eth", "rndis", "usb", "bt-pan"
    };

    /**
     * Filters and orders IPv4 candidates for display as "this device's LAN IP".
     * Drops loopback (127.*), link-local (169.254.*), CLAT (192.0.0.*) and cellular/VPN/virtual
     * interfaces. Orders by interface group (wlan, ap, swlan, softap, eth, rndis, usb, bt-pan,
     * others); within a group site-local addresses come first. Duplicate addresses are removed.
     */
    static List<String> rankIpv4Candidates(List<Candidate> c) {
        List<String> out = new ArrayList<>();
        if (c == null || c.isEmpty()) return out;

        List<int[]> keys = new ArrayList<>();      // {group, siteLocal ? 0 : 1, originalIndex}
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            Candidate cand = c.get(i);
            if (cand == null || cand.ipv4 == null) continue;
            String ip = cand.ipv4.trim();
            int[] oct = parseIpv4(ip);
            if (oct == null) continue;
            if (oct[0] == 127) continue;                                   // loopback
            if (oct[0] == 169 && oct[1] == 254) continue;                  // link-local
            if (oct[0] == 192 && oct[1] == 0 && oct[2] == 0) continue;     // 464XLAT / CLAT
            String name = cand.iface == null ? "" : cand.iface.toLowerCase(Locale.ROOT);
            if (isExcludedIface(name)) continue;
            keys.add(new int[]{ifaceGroup(name), isSiteLocal(oct) ? 0 : 1, i});
            ips.add(ip);
        }

        // Sort on (group, siteLocal, originalIndex); lists are tiny.
        Integer[] order = new Integer[keys.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int[] ka = keys.get(a), kb = keys.get(b);
            if (ka[0] != kb[0]) return Integer.compare(ka[0], kb[0]);
            if (ka[1] != kb[1]) return Integer.compare(ka[1], kb[1]);
            return Integer.compare(ka[2], kb[2]);
        });

        Set<String> seen = new LinkedHashSet<>();
        for (Integer idx : order) seen.add(ips.get(idx));
        out.addAll(seen);
        return out;
    }

    /** True for 1025..65535 excluding SoundShare's fixed ports 5001..5007. */
    static boolean isAllowedCustomPort(int p) {
        if (p < 1025 || p > 65535) return false;
        return p < 5001 || p > 5007;
    }

    /**
     * Makes a network-supplied name safe as a single path component: every character outside
     * [A-Za-z0-9._ -] becomes '_', leading dots are stripped, max 120 characters,
     * empty or null becomes "file".
     */
    static String sanitizeFileName(String name) {
        if (name == null) return "file";
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 256));
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == ' ' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        int start = 0;
        while (start < sb.length() && sb.charAt(start) == '.') start++;
        String s = sb.substring(start);
        if (s.length() > 120) s = s.substring(0, 120);
        return s.isEmpty() ? "file" : s;
    }

    // ---- internals ----

    private static boolean isExcludedIface(String name) {
        for (String p : EXCLUDED_IFACE_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    private static int ifaceGroup(String name) {
        // Longest matching prefix wins.
        int best = PREFERRED_IFACE_PREFIXES.length;
        int bestLen = -1;
        for (int i = 0; i < PREFERRED_IFACE_PREFIXES.length; i++) {
            String p = PREFERRED_IFACE_PREFIXES[i];
            if (name.startsWith(p) && p.length() > bestLen) {
                best = i;
                bestLen = p.length();
            }
        }
        return best;
    }

    private static boolean isSiteLocal(int[] o) {
        if (o[0] == 10) return true;
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;
        return o[0] == 192 && o[1] == 168;
    }

    /** Parses dotted-quad IPv4; returns null when not a valid literal. */
    private static int[] parseIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            int v = 0;
            for (int k = 0; k < p.length(); k++) {
                char ch = p.charAt(k);
                if (ch < '0' || ch > '9') return null;
                v = v * 10 + (ch - '0');
            }
            if (v > 255) return null;
            o[i] = v;
        }
        return o;
    }
}
