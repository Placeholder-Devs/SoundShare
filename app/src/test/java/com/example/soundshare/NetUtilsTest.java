package com.example.soundshare;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for {@link NetUtils}, written against the FIX_PLAN.md §3 contract.
 */
public class NetUtilsTest {

    private static NetUtils.Candidate c(String iface, String ip) {
        return new NetUtils.Candidate(iface, ip);
    }

    // ------------------------------------------------------------------ rankIpv4Candidates

    @Test
    public void rank_dropsCellularVpnVirtualInterfaces() {
        List<NetUtils.Candidate> in = Arrays.asList(
                c("rmnet_data0", "10.11.12.13"),
                c("rmnet0", "10.11.12.14"),
                c("ccmni0", "10.20.0.1"),
                c("tun0", "10.8.0.2"),
                c("ppp0", "10.64.0.1"),
                c("v4-rmnet_data0", "192.168.200.1"),
                c("clat4", "10.99.0.1"),
                c("dummy0", "10.0.5.5"),
                c("ipsec0", "10.7.7.7"),
                c("r_rmnet_data0", "10.6.6.6"),
                c("ifb0", "10.5.5.5"),
                c("seth_lte0", "10.33.1.2"),
                c("sipa_eth0", "10.34.1.2"),
                c("wlan0", "192.168.1.5"));
        assertEquals(Collections.singletonList("192.168.1.5"), NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_dropsLoopbackLinkLocalAndClatAddresses() {
        List<NetUtils.Candidate> in = Arrays.asList(
                c("lo", "127.0.0.1"),
                c("wlan0", "127.0.1.1"),
                c("wlan0", "169.254.3.4"),
                c("wlan0", "192.0.0.4"),
                c("eth0", "192.0.0.2"),
                c("wlan0", "192.168.1.5"));
        assertEquals(Collections.singletonList("192.168.1.5"), NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_interfaceGroupOrder() {
        // Supplied in reverse of the expected order.
        List<NetUtils.Candidate> in = Arrays.asList(
                c("xyz0", "10.0.0.9"),
                c("bt-pan", "192.168.44.1"),
                c("usb0", "192.168.42.1"),
                c("rndis0", "192.168.42.129"),
                c("eth0", "192.168.50.2"),
                c("softap0", "192.168.43.2"),
                c("swlan0", "192.168.43.3"),
                c("ap0", "192.168.43.1"),
                c("wlan0", "192.168.1.5"));
        List<String> expected = Arrays.asList(
                "192.168.1.5",      // wlan
                "192.168.43.1",     // ap
                "192.168.43.3",     // swlan
                "192.168.43.2",     // softap
                "192.168.50.2",     // eth
                "192.168.42.129",   // rndis
                "192.168.42.1",     // usb
                "192.168.44.1",     // bt-pan
                "10.0.0.9");        // others
        assertEquals(expected, NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_siteLocalFirstWithinGroup() {
        List<NetUtils.Candidate> in = Arrays.asList(
                c("wlan1", "100.64.1.2"),     // CGNAT, not site-local
                c("eth0", "203.0.113.7"),     // public
                c("eth0", "172.16.0.10"),     // site-local
                c("wlan0", "192.168.1.5"));   // site-local
        List<String> expected = Arrays.asList("192.168.1.5", "100.64.1.2", "172.16.0.10", "203.0.113.7");
        assertEquals(expected, NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_siteLocalRanges() {
        // 10/8, 172.16/12 and 192.168/16 are site-local; 172.32.x is not.
        List<NetUtils.Candidate> in = Arrays.asList(
                c("wlan0", "172.32.0.1"),
                c("wlan0", "10.1.1.1"));
        assertEquals(Arrays.asList("10.1.1.1", "172.32.0.1"), NetUtils.rankIpv4Candidates(in));

        in = Arrays.asList(c("wlan0", "172.32.0.1"), c("wlan0", "172.31.255.1"));
        assertEquals(Arrays.asList("172.31.255.1", "172.32.0.1"), NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_dedupes() {
        List<NetUtils.Candidate> in = Arrays.asList(
                c("wlan0", "192.168.1.5"),
                c("wlan0", "192.168.1.5"),
                c("eth0", "192.168.1.5"),
                c("ap0", "192.168.43.1"));
        assertEquals(Arrays.asList("192.168.1.5", "192.168.43.1"), NetUtils.rankIpv4Candidates(in));
    }

    @Test
    public void rank_emptyInput() {
        assertTrue(NetUtils.rankIpv4Candidates(new ArrayList<NetUtils.Candidate>()).isEmpty());
    }

    // ------------------------------------------------------------------ isAllowedCustomPort

    @Test
    public void isAllowedCustomPort() {
        assertFalse(NetUtils.isAllowedCustomPort(-1));
        assertFalse(NetUtils.isAllowedCustomPort(0));
        assertFalse(NetUtils.isAllowedCustomPort(80));
        assertFalse(NetUtils.isAllowedCustomPort(1024));
        assertTrue(NetUtils.isAllowedCustomPort(1025));
        assertTrue(NetUtils.isAllowedCustomPort(5000));
        for (int p = 5001; p <= 5007; p++) {
            assertFalse("port " + p, NetUtils.isAllowedCustomPort(p));
        }
        assertTrue(NetUtils.isAllowedCustomPort(5008));
        assertTrue(NetUtils.isAllowedCustomPort(9876));
        assertTrue(NetUtils.isAllowedCustomPort(65535));
        assertFalse(NetUtils.isAllowedCustomPort(65536));
    }

    // ------------------------------------------------------------------ sanitizeFileName

    private static void assertSafe(String s) {
        assertFalse(s, s.isEmpty());
        assertFalse(s, s.contains("/"));
        assertFalse(s, s.contains("\\"));
        assertFalse(s, s.startsWith("."));
        assertTrue(s, s.length() <= 120);
        assertTrue(s, s.matches("[A-Za-z0-9._ -]+"));
    }

    @Test
    public void sanitizeFileName_pathTraversal() {
        String s = NetUtils.sanitizeFileName("../../evil");
        assertSafe(s);
        assertEquals("_.._evil", s);

        assertSafe(NetUtils.sanitizeFileName("..\\..\\windows\\system32"));
        assertSafe(NetUtils.sanitizeFileName("/etc/passwd"));
        assertSafe(NetUtils.sanitizeFileName(".hidden"));
        assertEquals("hidden", NetUtils.sanitizeFileName(".hidden"));
    }

    @Test
    public void sanitizeFileName_nullEmptyAndDotsOnly() {
        assertEquals("file", NetUtils.sanitizeFileName(null));
        assertEquals("file", NetUtils.sanitizeFileName(""));
        assertEquals("file", NetUtils.sanitizeFileName("..."));
    }

    @Test
    public void sanitizeFileName_truncatesTo120() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('a');
        String s = NetUtils.sanitizeFileName(sb.toString() + ".flac");
        assertEquals(120, s.length());
        assertSafe(s);
    }

    @Test
    public void sanitizeFileName_normalNameStaysReadable() {
        assertEquals("My Song _Live_.flac", NetUtils.sanitizeFileName("My Song (Live).flac"));
        assertEquals("track-01_final.mp3", NetUtils.sanitizeFileName("track-01_final.mp3"));
        assertEquals("Caf_.mp3", NetUtils.sanitizeFileName("Café.mp3"));
    }
}
