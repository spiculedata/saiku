/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.database;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * saiku#1953 — the demo sample loaders (foodmart / bank / earthquakes) register the bundled
 * demo datasources independently of {@code saiku-launcher}'s staging step, so gating only the
 * launcher would still leave demo datasources on a non-demo boot. {@code saiku-launcher}
 * publishes the resolved {@code SAIKU_SEED} / {@code -Dsaiku.seed} decision as the
 * {@code saiku.seed} system property; these tests pin how the webapp reads it.
 *
 * <p>Unset must stay TRUE — a bare webapp deployment that never launches through the launcher
 * keeps the historical behaviour (samples seeded), so this is a launcher-default change only.
 */
public class SampleDataGateTest {

    private static final String KEY = "saiku.seed";

    @After
    public void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    public void unsetPropertyKeepsHistoricalBehaviour() {
        assertTrue(Database.sampleDataEnabled());
    }

    @Test
    public void blankPropertyCountsAsUnset() {
        System.setProperty(KEY, "  ");
        assertTrue(Database.sampleDataEnabled());
    }

    @Test
    public void explicitFalseDisablesSampleDatasources() {
        System.setProperty(KEY, "false");
        assertFalse(Database.sampleDataEnabled());
    }

    @Test
    public void explicitTrueEnablesSampleDatasources() {
        System.setProperty(KEY, " true ");
        assertTrue(Database.sampleDataEnabled());
    }
}
