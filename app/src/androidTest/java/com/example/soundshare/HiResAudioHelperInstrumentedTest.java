package com.example.soundshare;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The capability probe runs on every app start; it must never throw on any device.
 * The result line ("SoundShareTest" tag) can be pasted into DEVICE_MATRIX.md.
 */
@RunWith(AndroidJUnit4.class)
public class HiResAudioHelperInstrumentedTest {

    @Test
    public void detectCapabilities_returnsNonNullWithoutThrowing() {
        HiResAudioHelper.Capabilities caps = HiResAudioHelper.detectCapabilities();
        assertNotNull(caps);
        assertTrue(caps.maxSampleRate >= 48000);
        assertTrue(caps.maxBitDepth >= 16);
        assertNotNull(caps.toString());
        Log.i("SoundShareTest", "Capabilities: " + caps);
    }
}
