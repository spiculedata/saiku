/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

/**
 * saiku#2579 — the update check's finding must reach the webapp. The channel is the
 * {@code saiku.update.latest} system property: set only when the reply names a release NEWER than
 * the running version (its presence IS the newVersionAvailable answer), cleared when a later
 * check no longer finds one, and untouched by a reply without a parsable "latest" — no result is
 * not a result. The webapp's capabilities endpoint reads this property, so a false record here
 * would put a phantom upgrade banner in front of every admin.
 */
public class TelemetryServiceUpdateStateTest {

    @After
    public void clearProperty() {
        System.clearProperty(TelemetryService.LATEST_VERSION_PROPERTY);
    }

    @Test
    public void aNewerLatestIsRecorded() {
        TelemetryService.noteLatest("{\"latest\":\"4.10.0\"}", "4.9.1");
        assertEquals("4.10.0", System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));
    }

    @Test
    public void anEqualOrOlderLatestClearsAnyStaleRecord() {
        System.setProperty(TelemetryService.LATEST_VERSION_PROPERTY, "4.10.0");
        TelemetryService.noteLatest("{\"latest\":\"4.9.1\"}", "4.10.0");
        assertNull(System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));
    }

    @Test
    public void anUnparsableReplyChangesNothing() {
        System.setProperty(TelemetryService.LATEST_VERSION_PROPERTY, "4.10.0");
        TelemetryService.noteLatest("{\"ok\":true}", "4.9.1");
        assertEquals(
                "no result is not a result — a stale record must survive",
                "4.10.0",
                System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));

        System.clearProperty(TelemetryService.LATEST_VERSION_PROPERTY);
        TelemetryService.noteLatest("{\"ok\":true}", "4.9.1");
        assertNull("and must not invent one", System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));
    }

    /** The loose-semver edge cases {@link TelemetryService} compares with. */
    @Test
    public void comparisonsFollowTheLooseSemverRules() {
        TelemetryService.noteLatest("{\"latest\":\"4.10\"}", "4.9.1");
        assertEquals("4.10", System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));

        System.clearProperty(TelemetryService.LATEST_VERSION_PROPERTY);
        TelemetryService.noteLatest("{\"latest\":\"4.9.1\"}", "4.9.1");
        assertNull(System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));

        System.clearProperty(TelemetryService.LATEST_VERSION_PROPERTY);
        TelemetryService.noteLatest("{\"latest\":\"5.0.0\"}", "4.9.1");
        assertEquals("5.0.0", System.getProperty(TelemetryService.LATEST_VERSION_PROPERTY));
    }
}
