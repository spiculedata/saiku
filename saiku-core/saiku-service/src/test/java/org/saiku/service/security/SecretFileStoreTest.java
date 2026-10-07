/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers owner-only, atomic secret writes (saiku#1919 item 18c — CWE-732 / CWE-377).
 *
 * <p>Per the task brief these use try/fail/catch rather than {@code assertThrows}.
 */
public class SecretFileStoreTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("saiku-secretfile-test-");
    }

    @After
    public void tearDown() throws Exception {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignore) {
                    // best effort
                }
            });
        }
    }

    private static boolean posix(Path p) {
        return Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class) != null;
    }

    @Test
    public void writeCreatesFileWithOwnerOnlyPermissions() throws Exception {
        Path target = dir.resolve("conf").resolve("secret.key");
        SecretFileStore.writeOwnerOnly(target, "s3cret".getBytes(StandardCharsets.UTF_8));

        assertTrue("file must exist", Files.isRegularFile(target));
        assertEquals("s3cret", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        if (posix(target)) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
            assertEquals("secret file must be 0600", PosixFilePermissions.fromString("rw-------"), perms);
        }
    }

    @Test
    public void writeReplacesAnExistingFileAndLeavesNoTemp() throws Exception {
        Path target = dir.resolve("mail-config.json");
        SecretFileStore.writeOwnerOnly(target, "first".getBytes(StandardCharsets.UTF_8));
        SecretFileStore.writeOwnerOnly(target, "second".getBytes(StandardCharsets.UTF_8));

        assertEquals("second", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse("temp file must be moved, not left", Files.exists(target.resolveSibling("mail-config.json.tmp")));
        if (posix(target)) {
            assertEquals(
                    "a replaced secret file must still be 0600",
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(target));
        }
    }

    @Test
    public void writeUsesTheTempPathSoWriterCannotWidenTheTarget() throws Exception {
        Path target = dir.resolve("consent.json");
        final boolean[] sawTemp = new boolean[1];
        SecretFileStore.writeOwnerOnly(target, tmp -> {
            sawTemp[0] = tmp.getFileName().toString().endsWith(".tmp");
            Files.write(tmp, "payload".getBytes(StandardCharsets.UTF_8));
        });
        assertTrue("the writer must receive a temp sibling, never the target", sawTemp[0]);
        assertEquals("payload", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    public void restrictDirectoryIsOwnerOnly() throws Exception {
        Path sub = dir.resolve("sessions");
        SecretFileStore.restrictDirectory(sub);
        assertTrue(Files.isDirectory(sub));
        if (posix(sub)) {
            assertEquals(
                    "session dir must be 0700",
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(sub));
        }
    }

    @Test
    public void failedWriteStillCleansUpTheTempFile() {
        Path target = dir.resolve("boom.json");
        try {
            SecretFileStore.writeOwnerOnly(target, tmp -> {
                throw new java.io.IOException("simulated serialisation failure");
            });
            fail("the IOException must propagate to the caller");
        } catch (java.io.IOException expected) {
            assertEquals("simulated serialisation failure", expected.getMessage());
        }
        assertFalse("no temp file may survive a failed write", Files.exists(target.resolveSibling("boom.json.tmp")));
    }
}
