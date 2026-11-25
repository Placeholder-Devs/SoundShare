package com.example.soundshare;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * JVM tests for the pure part of {@link PipelineLog} (redactIps). The Log-calling methods are
 * not exercised here because android.util.Log is a stub on the JVM.
 */
public class PipelineLogTest {

    @Test
    public void tagIsStable() {
        assertEquals("SoundSharePipe", PipelineLog.TAG);
    }

    @Test
    public void redactsThirdOctet() {
        assertEquals("192.168.x.5", PipelineLog.redactIps("192.168.1.5"));
        assertEquals("10.0.x.12", PipelineLog.redactIps("10.0.0.12"));
        assertEquals("172.16.x.254", PipelineLog.redactIps("172.16.200.254"));
    }

    @Test
    public void redactsInsideText() {
        assertEquals("connect to 10.0.x.12:5000 ok", PipelineLog.redactIps("connect to 10.0.0.12:5000 ok"));
        assertEquals("peer=/192.168.x.7", PipelineLog.redactIps("peer=/192.168.43.7"));
        assertEquals("Connected to 192.168.x.20.", PipelineLog.redactIps("Connected to 192.168.1.20."));
    }

    @Test
    public void redactsMultipleAddresses() {
        assertEquals("a 192.168.x.5 b 10.1.x.4 c",
                PipelineLog.redactIps("a 192.168.1.5 b 10.1.2.4 c"));
    }

    @Test
    public void nullAndNonIpInputs() {
        assertNull(PipelineLog.redactIps(null));
        assertEquals("", PipelineLog.redactIps(""));
        assertEquals("no address here", PipelineLog.redactIps("no address here"));
        assertEquals("48000Hz 16bit 2ch fpb=960", PipelineLog.redactIps("48000Hz 16bit 2ch fpb=960"));
        assertEquals("1.2.3", PipelineLog.redactIps("1.2.3"));
        // more than 3 digits in the first group is not an IPv4 literal
        assertEquals("1234.5.6.7", PipelineLog.redactIps("1234.5.6.7"));
    }

    @Test
    public void textWithRegexSpecialCharactersIsPreserved() {
        assertEquals("$1 \\ 192.168.x.5 $", PipelineLog.redactIps("$1 \\ 192.168.1.5 $"));
    }
}
