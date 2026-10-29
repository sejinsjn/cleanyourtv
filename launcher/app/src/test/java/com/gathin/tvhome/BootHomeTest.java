package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootHomeTest {
    @Test
    public void bootHomeRequiresBothOptInsAndAStockHomeRedirect() {
        assertFalse(BootHomeReceiver.enabledFor(false, true, "com.google.android.apps.tv.launcherx"));
        assertFalse(BootHomeReceiver.enabledFor(true, false, "com.google.android.apps.tv.launcherx"));
        assertFalse(BootHomeReceiver.enabledFor(true, true, ""));
        assertFalse(BootHomeReceiver.enabledFor(true, true, null));
        assertTrue(BootHomeReceiver.enabledFor(true, true, "com.google.android.apps.tv.launcherx"));
    }
}
