/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#1845 — a data source's {@code Catalog=} must not be able to read arbitrary host files.
 *
 * <p>Demonstrated before the fix: pointing a catalog at a file outside saiku home and calling
 * {@code GET /admin/cube-designer/schema/{id}} returned that file's contents in the HTTP response.
 */
public class SchemaFileAccessGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path write(File dir, String name, String body) throws IOException {
        Path p = dir.toPath().resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    public void allowsASchemaInsideAPermittedRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        Path schema = write(home, "data/FoodMart4.xml", "<Schema/>");
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath()));

        assertTrue(guard.isAllowed(schema));
        guard.assertReadable(schema); // must not throw
    }

    @Test
    public void refusesAFileOutsideEveryPermittedRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File elsewhere = tmp.newFolder("elsewhere");
        Path secret = write(elsewhere, "secret.txt", "SECRET");
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath()));

        assertFalse(guard.isAllowed(secret));
        try {
            guard.assertReadable(secret);
            fail("expected the read to be refused");
        } catch (IOException expected) {
            // The message must not enumerate the server's allowed directories — it frequently
            // ends up in an HTTP response body.
            assertFalse(expected.getMessage().contains(home.getAbsolutePath()));
        }
    }

    @Test
    public void refusesATraversalOutOfAPermittedRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File elsewhere = tmp.newFolder("elsewhere");
        write(elsewhere, "secret.txt", "SECRET");
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath()));

        Path traversal = home.toPath().resolve("../elsewhere/secret.txt");
        assertFalse(guard.isAllowed(traversal));
    }

    @Test
    public void refusesATraversalEvenWhenTheTargetDoesNotExist() throws Exception {
        // toRealPath() fails for a missing file, so the lexical fallback must still normalise
        // the "../" away rather than treating the path as opaque and letting it through.
        File home = tmp.newFolder("saiku-home");
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath()));

        assertFalse(guard.isAllowed(home.toPath().resolve("../../etc/shadow")));
    }

    @Test
    public void refusesASymlinkWhoseTargetEscapesTheRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File elsewhere = tmp.newFolder("elsewhere");
        Path secret = write(elsewhere, "secret.txt", "SECRET");
        Path link = home.toPath().resolve("looks-innocent.xml");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlink support (e.g. Windows without privilege) — nothing to assert
        }
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath()));

        // The name is inside the root; the target is not. Containment must follow the target.
        assertFalse(guard.isAllowed(link));
    }

    @Test
    public void allowsAnyOfSeveralRoots() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File mounted = tmp.newFolder("mnt-schemas");
        Path onVolume = write(mounted, "Corporate.xml", "<Schema/>");
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of(home.toPath(), mounted.toPath()));

        assertTrue(guard.isAllowed(onVolume));
    }

    @Test
    public void nullIsNeverReadable() {
        SchemaFileAccessGuard guard =
                new SchemaFileAccessGuard(List.of(tmp.getRoot().toPath()));
        assertFalse(guard.isAllowed(null));
    }

    @Test
    public void anUnconfiguredGuardStaysPermissiveRatherThanBreakingTheServer() {
        // A deployment with no resolvable saiku.home (plain WAR in Tomcat) must keep loading its
        // cubes; fromEnvironment() warns instead. Refusing everything here would be a worse bug
        // than the one being fixed.
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(List.of());
        assertTrue(guard.allowedRoots().isEmpty());
        assertTrue(guard.isAllowed(Path.of("/etc/shadow")));
    }

    @Test
    public void dropsUnresolvableAndDuplicateRoots() throws Exception {
        File home = tmp.newFolder("saiku-home");
        SchemaFileAccessGuard guard =
                new SchemaFileAccessGuard(java.util.Arrays.asList(home.toPath(), home.toPath(), null));
        assertTrue(guard.allowedRoots().size() == 1);
    }

    @Test
    public void fromEnvironmentPicksUpTheRepositoryDataDirectory() throws Exception {
        File repo = tmp.newFolder("repo-data");
        Path schema = write(repo, "datasources/Foo.xml", "<Schema/>");
        SchemaFileAccessGuard guard = SchemaFileAccessGuard.fromEnvironment(repo.getAbsolutePath());
        assertTrue(guard.isAllowed(schema));
    }

    @Test
    public void fromEnvironmentHonoursTheOperatorEscapeHatch() throws Exception {
        File mounted = tmp.newFolder("mnt");
        Path schema = write(mounted, "Corporate.xml", "<Schema/>");
        String previous = System.getProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY);
        System.setProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY, mounted.getAbsolutePath());
        try {
            SchemaFileAccessGuard guard = SchemaFileAccessGuard.fromEnvironment(null);
            assertTrue(guard.isAllowed(schema));
        } finally {
            if (previous == null) {
                System.clearProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY);
            } else {
                System.setProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY, previous);
            }
        }
    }
}
