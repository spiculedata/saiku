/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * saiku#1769 — regression coverage for demo mode never reaching the webapp.
 *
 * <p>The launcher's demo switch is the {@code SAIKU_DEMO} env var; the webapp reads the
 * {@code saiku.demo} SYSTEM PROPERTY ({@code InfoResource}) and publishes it as {@code demoMode}
 * on {@code /info/capabilities}. Nothing bridged the two, so a {@code SAIKU_DEMO=true} boot
 * seeded the demo users and printed the admin/admin banner while the UI — which gates its
 * pre-filled credential and "Try the demo" panel on {@code demoMode} — rendered a production
 * login form. The advertised credential was unreachable.
 *
 * <p>The behaviour pinned here:
 *
 * <ul>
 *   <li>Non-demo boots leave {@code saiku.demo} untouched — production must never self-declare
 *       as a demo instance.</li>
 *   <li>Demo mode with the property unset returns {@code "true"} so the launcher sets it.</li>
 *   <li>An explicit {@code -Dsaiku.demo=...} wins, in either direction — including the operator
 *       who runs the demo profile but deliberately pins {@code false}.</li>
 *   <li>Empty / whitespace counts as unset, matching {@code resolveDemoAiPolicyDefault}.</li>
 * </ul>
 */
public class DemoModePropertyTest {

    @Test
    public void nonDemoBootLeavesPropertyUntouched() {
        assertNull(ServeCommand.resolveDemoModeProperty(false, null));
    }

    @Test
    public void nonDemoBootLeavesAnExplicitPropertyUntouched() {
        // Someone set -Dsaiku.demo=true without SAIKU_DEMO: not ours to rewrite.
        assertNull(ServeCommand.resolveDemoModeProperty(false, "true"));
    }

    @Test
    public void demoBootSetsTheProperty() {
        assertEquals("true", ServeCommand.resolveDemoModeProperty(true, null));
    }

    @Test
    public void explicitPropertyWinsOverDemoDefaulting() {
        assertNull(ServeCommand.resolveDemoModeProperty(true, "true"));
        // The important direction: demo profile on, but the operator pinned it off.
        assertNull(ServeCommand.resolveDemoModeProperty(true, "false"));
    }

    @Test
    public void blankPropertyCountsAsUnset() {
        assertEquals("true", ServeCommand.resolveDemoModeProperty(true, ""));
        assertEquals("true", ServeCommand.resolveDemoModeProperty(true, "   "));
    }
}
