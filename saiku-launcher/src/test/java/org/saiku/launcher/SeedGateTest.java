/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * saiku#1953 — regression coverage for the demo-fixture staging gate.
 *
 * <p>Reported behaviour: {@code stageSeedAssets}, {@code stageDefaultDatasource} and
 * {@code stageOssieDemoDatasources} ran on every boot, so {@code SAIKU_DEMO=false} — and an
 * unset {@code SAIKU_DEMO} — still seeded FoodMart4.xml, Bank.xml, bank.lkml, tpcds.mv.db,
 * flights.mv.db and their .sds descriptors, leaving four demo datasources in a production
 * instance with no supported way to opt out.
 *
 * <p>The behaviour pinned here:
 *
 * <ul>
 *   <li>The default follows demo mode: demo boots seed, non-demo boots don't.</li>
 *   <li>{@code SAIKU_SEED} / {@code -Dsaiku.seed} override the default in BOTH directions —
 *       fixtures without demo accounts, and demo accounts against the operator's own cubes.</li>
 *   <li>Precedence: {@code -Dsaiku.seed} &gt; {@code SAIKU_SEED} &gt; demo mode.</li>
 *   <li>Empty / whitespace counts as unset, matching {@code resolveDemoAiPolicyDefault} and
 *       {@code resolveDemoModeProperty}.</li>
 * </ul>
 */
public class SeedGateTest {

    @Test
    public void demoBootSeedsFixtures() {
        assertTrue(ServeCommand.shouldStageSeedFixtures(true, null, null));
    }

    @Test
    public void nonDemoBootDoesNotSeedFixtures() {
        assertFalse(ServeCommand.shouldStageSeedFixtures(false, null, null));
    }

    @Test
    public void seedEnvOptInWorksWithoutDemoMode() {
        assertTrue(ServeCommand.shouldStageSeedFixtures(false, "true", null));
    }

    @Test
    public void seedEnvOptOutBeatsDemoMode() {
        assertFalse(ServeCommand.shouldStageSeedFixtures(true, "false", null));
    }

    @Test
    public void seedPropertyBeatsSeedEnv() {
        assertFalse(ServeCommand.shouldStageSeedFixtures(true, "true", "false"));
        assertTrue(ServeCommand.shouldStageSeedFixtures(false, "false", "true"));
    }

    @Test
    public void blankValuesCountAsUnset() {
        assertTrue(ServeCommand.shouldStageSeedFixtures(true, "  ", ""));
        assertFalse(ServeCommand.shouldStageSeedFixtures(false, "", "   "));
    }

    @Test
    public void valuesAreTrimmed() {
        assertTrue(ServeCommand.shouldStageSeedFixtures(false, " true ", null));
        assertFalse(ServeCommand.shouldStageSeedFixtures(true, null, " false "));
    }
}
