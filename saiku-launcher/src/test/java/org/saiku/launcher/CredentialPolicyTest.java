/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * saiku#1153 — unit tests for the default-admin-credentials boot policy. The
 * pure decision logic ({@code isDefaultAdminValue} + {@code shouldRefuse}) is
 * tested directly; the WAR-reading and live boot paths are exercised by the IT
 * harness (which opts in via {@code -Dsaiku.allowDefaultAdmin=true}).
 */
public class CredentialPolicyTest {

    @Test
    public void legacyNoopDefaultIsDetected() {
        assertTrue(ServeCommand.isDefaultAdminValue("{noop}admin,ROLE_USER,ROLE_ADMIN"));
    }

    @Test
    public void shippedBcryptDefaultIsDetected() {
        // saiku#1154 ships the admin password as a bcrypt hash; the policy must
        // still recognise that exact shipped hash as "unchanged default".
        assertTrue(
                ServeCommand.isDefaultAdminValue(ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT + ",ROLE_USER,ROLE_ADMIN"));
    }

    @Test
    public void rotatedPasswordIsNotDefault() {
        assertFalse(ServeCommand.isDefaultAdminValue(
                "{bcrypt}$2a$10$ROTATEDrotatedROTATEDrotatedROTATEDrotatedROTATEDrotat,ROLE_USER,ROLE_ADMIN"));
    }

    @Test
    public void nullOrMissingIsNotDefault() {
        assertFalse(ServeCommand.isDefaultAdminValue(null));
        assertFalse(ServeCommand.isDefaultAdminValue(""));
    }

    @Test
    public void shippedConstantStillEncodesTheWordAdmin() {
        // Guard against the constant being edited to a non-bcrypt / wrong shape.
        assertTrue(ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT.startsWith("{bcrypt}$2"));
    }

    @Test
    public void refusesWhenDefaultInProductionWithoutOverride() {
        assertTrue(ServeCommand.shouldRefuse(true, false, false));
    }

    @Test
    public void demoModeStartsWithDefault() {
        assertFalse(ServeCommand.shouldRefuse(true, true, false));
    }

    @Test
    public void explicitOverrideStartsWithDefault() {
        assertFalse(ServeCommand.shouldRefuse(true, false, true));
    }

    @Test
    public void rotatedPasswordAlwaysStarts() {
        assertFalse("a rotated password must never block boot", ServeCommand.shouldRefuse(false, false, false));
        assertFalse(ServeCommand.shouldRefuse(false, true, false));
        assertFalse(ServeCommand.shouldRefuse(false, false, true));
    }

    @Test
    public void overrideReadsSystemProperty() {
        String prev = System.getProperty("saiku.allowDefaultAdmin");
        try {
            System.setProperty("saiku.allowDefaultAdmin", "true");
            assertTrue(ServeCommand.allowDefaultAdmin());
            System.setProperty("saiku.allowDefaultAdmin", "false");
            assertFalse(ServeCommand.allowDefaultAdmin());
        } finally {
            if (prev == null) {
                System.clearProperty("saiku.allowDefaultAdmin");
            } else {
                System.setProperty("saiku.allowDefaultAdmin", prev);
            }
        }
    }

    @Test
    public void encodingIsSplitOffRolesCorrectly() {
        // The role list after the first comma must not affect detection.
        assertEquals(
                ServeCommand.isDefaultAdminValue("{noop}admin,ROLE_USER"),
                ServeCommand.isDefaultAdminValue("{noop}admin,ROLE_USER,ROLE_ADMIN,ROLE_EXTRA"));
    }
}
