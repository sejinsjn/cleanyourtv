package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceRepairTest {
    private static final String PACKAGE = "com.gathin.tvhome";

    @Test
    public void addsOnlyRecordedHelpersAndPreservesOtherAccessibilityServices() {
        Set<String> recorded = new HashSet<>(Arrays.asList(
                "com.gathin.tvhome.HomeKeyService",
                "com.gathin.tvhome.InputKeyService"));

        assertEquals(
                "com.example.reader/.ReaderService:com.gathin.tvhome/.HomeKeyService"
                        + ":com.gathin.tvhome/.InputKeyService",
                ServiceRepair.appendMissingServices(
                        "com.example.reader/.ReaderService:com.gathin.tvhome/.HomeKeyService",
                        PACKAGE, recorded));
    }

    @Test
    public void doesNotDuplicateShortOrFullyQualifiedComponents() {
        Set<String> recorded = new HashSet<>(Arrays.asList(
                "com.gathin.tvhome.HomeKeyService",
                "com.gathin.tvhome.InputKeyService"));

        assertEquals(
                "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService"
                        + ":com.gathin.tvhome/.InputKeyService",
                ServiceRepair.appendMissingServices(
                        "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService",
                        PACKAGE, recorded));
    }

    @Test
    public void nullServiceListIsRestoredFromRecordedHelpers() {
        assertEquals(
                "com.gathin.tvhome/.HomeKeyService",
                ServiceRepair.appendMissingServices(null, PACKAGE,
                        new HashSet<>(Arrays.asList("com.gathin.tvhome.HomeKeyService"))));
    }

    @Test
    public void rebindRequiresAccessibilityOffAndEveryRecordedHelperStillEnabled() {
        Set<String> recorded = new HashSet<>(Arrays.asList(
                "com.gathin.tvhome.HomeKeyService",
                "com.gathin.tvhome.InputKeyService"));

        assertTrue(ServiceRepair.needsAccessibilityRebind(false, true));
        assertFalse(ServiceRepair.needsAccessibilityRebind(true, true));
        assertFalse(ServiceRepair.needsAccessibilityRebind(false, false));
        assertTrue(ServiceRepair.containsAllRecordedServices(
                "com.gathin.tvhome/.HomeKeyService:com.gathin.tvhome/com.gathin.tvhome.InputKeyService",
                PACKAGE, recorded));
        assertFalse(ServiceRepair.containsAllRecordedServices(
                "com.gathin.tvhome/.HomeKeyService", PACKAGE, recorded));
    }
}
