/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.demo;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DemoGateSecretTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void envValueWins_andNothingPersisted() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        assertEquals("env-secret", DemoGateSecret.resolve("env-secret", home));
        assertFalse(Files.exists(home.resolve("demo-gate").resolve("secret")));
    }

    @Test
    public void generatesAndPersistsWhenAbsent() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        String s = DemoGateSecret.resolve(null, home);
        assertNotNull(s);
        assertFalse(s.isBlank());
        Path f = home.resolve("demo-gate").resolve("secret");
        assertTrue(Files.exists(f));
        assertEquals(s, Files.readString(f).trim());
    }

    @Test
    public void reusesExistingFileAcrossCalls() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        String first = DemoGateSecret.resolve(null, home);
        String second = DemoGateSecret.resolve(null, home);
        assertEquals("a second boot must read the same persisted secret", first, second);
    }

    @Test
    public void blankEnvFallsThroughToFile() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        String s = DemoGateSecret.resolve("   ", home);
        Path f = home.resolve("demo-gate").resolve("secret");
        assertTrue(Files.exists(f));
        assertEquals(s, Files.readString(f).trim());
    }

    @Test
    public void ephemeralWhenNoHome() {
        String s = DemoGateSecret.resolve(null, null);
        assertNotNull(s);
        assertFalse(s.isBlank());
    }
}
