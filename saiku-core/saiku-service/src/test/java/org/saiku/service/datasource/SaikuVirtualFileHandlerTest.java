/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import mondrian.spi.VirtualFileHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * saiku#1844 — Mondrian must be able to read a schema out of the Saiku repository.
 *
 * <p>Before this handler existed, {@code Catalog=mondrian://…} — which is what the admin UI writes
 * for EVERY Mondrian data source — failed with {@code Virtual file is not readable}, so the cube
 * never loaded.
 */
public class SaikuVirtualFileHandlerTest {

    private String previousProperty;

    @Before
    public void captureProperty() {
        previousProperty = System.getProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY);
    }

    @After
    public void restore() {
        if (previousProperty == null) {
            System.clearProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY);
        } else {
            System.setProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY, previousProperty);
        }
        SaikuVirtualFileHandler.setRepositoryReader(null);
        SaikuVirtualFileHandler.setFileGuard(null);
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    public void readsARepositoryHostedSchemaThroughTheMondrianScheme() throws Exception {
        Map<String, String> repo = new HashMap<>();
        repo.put("/datasources/ScratchSales.xml", "<Schema name='ScratchSales'/>");
        SaikuVirtualFileHandler.setRepositoryReader(repo::get);

        // The exact shape DataSourceMapper writes for a schema field of "/datasources/X.xml".
        try (InputStream in =
                new SaikuVirtualFileHandler().readVirtualFile("mondrian:///datasources/ScratchSales.xml")) {
            assertEquals("<Schema name='ScratchSales'/>", read(in));
        }
    }

    @Test
    public void readsABareNameViaTheConventionalSchemaLocation() throws Exception {
        Map<String, String> repo = new HashMap<>();
        repo.put("/datasources/Foo.xml", "<Schema name='Foo'/>");
        SaikuVirtualFileHandler.setRepositoryReader(repo::get);

        try (InputStream in = new SaikuVirtualFileHandler().readVirtualFile("mondrian://Foo")) {
            assertEquals("<Schema name='Foo'/>", read(in));
        }
    }

    @Test
    public void stillReadsPlainFileCatalogsThroughMondriansOwnHandler() throws Exception {
        // Every seeded data source uses Catalog=file:. Delegation must be untouched.
        File tmp = File.createTempFile("saiku-vfs", ".xml");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), "<Schema name='OnDisk'/>", StandardCharsets.UTF_8);
        SaikuVirtualFileHandler.setRepositoryReader(p -> null);

        try (InputStream in =
                new SaikuVirtualFileHandler().readVirtualFile(tmp.toURI().toString())) {
            assertEquals("<Schema name='OnDisk'/>", read(in));
        }
    }

    @Test
    public void delegatesWhenNoRepositoryReaderHasBeenInstalled() throws Exception {
        // Before startup wiring runs, behaviour must be exactly Mondrian's stock behaviour.
        File tmp = File.createTempFile("saiku-vfs", ".xml");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), "<Schema name='NoReader'/>", StandardCharsets.UTF_8);
        SaikuVirtualFileHandler.setRepositoryReader(null);

        try (InputStream in =
                new SaikuVirtualFileHandler().readVirtualFile(tmp.toURI().toString())) {
            assertEquals("<Schema name='NoReader'/>", read(in));
        }
    }

    @Test
    public void missingRepositorySchemaFailsWithTheCandidatesItLookedIn() {
        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        try {
            new SaikuVirtualFileHandler().readVirtualFile("mondrian://Missing");
            fail("expected a FileNotFoundException");
        } catch (IOException e) {
            assertTrue(e instanceof FileNotFoundException);
            // The old failure was a bare "Virtual file is not readable" with no hint where it
            // looked. An admin needs the candidate paths to fix their schema field.
            assertTrue(e.getMessage(), e.getMessage().contains("/datasources/Missing.xml"));
        }
    }

    // --- saiku#1844 Cloud: mondrian:// falls through to a registered VFS provider ----------

    @Test
    public void anUnresolvedMondrianSchemeFallsThroughToTheRegisteredVfsProvider() throws Exception {
        // Saiku Cloud has no filesystem repository: the reader (irm.getInternalFile) cannot reach
        // the tenant's Postgres-hosted schema, so it returns null. A mondrian:// provider IS
        // registered on the global VFS manager, though, and must get the chance to resolve it —
        // otherwise every cloud-authored cube goes Broken. The stock delegate stands in for that
        // provider here.
        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        VirtualFileHandler fakeProvider = url -> {
            assertEquals("mondrian:///datasources/cloud-uploads/t/v1.xml", url);
            return new java.io.ByteArrayInputStream("<Schema name='FromCloud'/>".getBytes(StandardCharsets.UTF_8));
        };

        try (InputStream in = new SaikuVirtualFileHandler(fakeProvider)
                .readVirtualFile("mondrian:///datasources/cloud-uploads/t/v1.xml")) {
            assertEquals("<Schema name='FromCloud'/>", read(in));
        }
    }

    @Test
    public void aBarePathIsNeverHandedToTheDelegateEvenWhenOneCouldResolveIt() throws Exception {
        // The scheme guard, not just a null reader, is what keeps saiku#1845 closed: a schemeless
        // "../../etc/shadow" must fail closed even if the delegate would happily read it.
        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        boolean[] delegateTouched = {false};
        VirtualFileHandler wouldResolveAnything = url -> {
            delegateTouched[0] = true;
            return new java.io.ByteArrayInputStream("SECRET".getBytes(StandardCharsets.UTF_8));
        };

        try {
            new SaikuVirtualFileHandler(wouldResolveAnything).readVirtualFile("../../etc/shadow");
            fail("a bare path must fail closed, never reach the delegate");
        } catch (FileNotFoundException expected) {
            assertTrue("delegate must not be consulted for a schemeless path", !delegateTouched[0]);
        }
    }

    // --- saiku#1845 containment -------------------------------------------------

    @Test
    public void refusesAFileCatalogOutsideThePermittedRoots() throws Exception {
        File allowed = Files.createTempDirectory("saiku-allowed").toFile();
        allowed.deleteOnExit();
        File secret = File.createTempFile("outside", ".txt");
        secret.deleteOnExit();
        Files.writeString(secret.toPath(), "SECRET", StandardCharsets.UTF_8);

        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        SaikuVirtualFileHandler.setFileGuard(new SchemaFileAccessGuard(java.util.List.of(allowed.toPath())));

        try {
            new SaikuVirtualFileHandler().readVirtualFile(secret.toURI().toString());
            fail("expected the read to be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("outside the Saiku data directories"));
        }
    }

    @Test
    public void stillAllowsAFileCatalogInsideThePermittedRoots() throws Exception {
        File allowed = Files.createTempDirectory("saiku-allowed").toFile();
        allowed.deleteOnExit();
        File schema = new File(allowed, "FoodMart4.xml");
        schema.deleteOnExit();
        Files.writeString(schema.toPath(), "<Schema name='FoodMart'/>", StandardCharsets.UTF_8);

        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        SaikuVirtualFileHandler.setFileGuard(new SchemaFileAccessGuard(java.util.List.of(allowed.toPath())));

        try (InputStream in =
                new SaikuVirtualFileHandler().readVirtualFile(schema.toURI().toString())) {
            assertEquals("<Schema name='FoodMart'/>", read(in));
        }
    }

    @Test
    public void anUnresolvedBarePathNeverFallsThroughToTheFilesystem() throws Exception {
        // "../../etc/shadow" carries no scheme, so it looks like a repository reference. When the
        // repository doesn't have it, the old code handed it to the VFS delegate, which resolves
        // relative paths against the process working directory — a failed lookup becoming a read.
        SaikuVirtualFileHandler.setRepositoryReader(p -> null);
        try {
            new SaikuVirtualFileHandler().readVirtualFile("../../etc/shadow");
            fail("expected the read to be refused");
        } catch (FileNotFoundException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Saiku repository"));
        }
    }

    @Test
    public void installSetsTheMondrianHandlerProperty() {
        System.clearProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY);
        SaikuVirtualFileHandler.install(p -> null);
        assertEquals(
                SaikuVirtualFileHandler.class.getName(), System.getProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY));
    }

    @Test
    public void installDoesNotOverrideAnOperatorsExplicitChoice() {
        System.setProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY, "com.example.CustomHandler");
        SaikuVirtualFileHandler.install(p -> null);
        assertEquals("com.example.CustomHandler", System.getProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY));
    }

    @Test
    public void installIsIdempotent() {
        System.clearProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY);
        SaikuVirtualFileHandler.install(p -> null);
        SaikuVirtualFileHandler.install(p -> null);
        assertEquals(
                SaikuVirtualFileHandler.class.getName(), System.getProperty(SaikuVirtualFileHandler.HANDLER_PROPERTY));
    }

    // ── preview schemas (saiku#1872) ────────────────────────────────────────

    @Test
    public void aRegisteredPreviewIsServedInsteadOfHittingTheRepository() throws Exception {
        SaikuVirtualFileHandler.setRepositoryReader(path -> "<Schema name=\"FromRepository\"/>");
        String ref = SaikuVirtualFileHandler.registerPreview("<Schema name=\"Unsaved\"/>");
        try {
            String got = new String(
                    new SaikuVirtualFileHandler().readVirtualFile(ref).readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("preview did not win over the repository: " + got, got.contains("Unsaved"));
        } finally {
            SaikuVirtualFileHandler.unregisterPreview(ref);
        }
    }

    /** Mondrian may present the reference with its scheme attached; both spellings must hit. */
    @Test
    public void aPreviewResolvesWithOrWithoutTheMondrianScheme() throws Exception {
        String ref = SaikuVirtualFileHandler.registerPreview("<Schema name=\"Unsaved\"/>");
        try {
            String withScheme = "mondrian://" + ref;
            String got = new String(
                    new SaikuVirtualFileHandler().readVirtualFile(withScheme).readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("scheme-qualified preview did not resolve: " + got, got.contains("Unsaved"));
        } finally {
            SaikuVirtualFileHandler.unregisterPreview(ref);
        }
    }

    /** THE cleanup property: a preview must not outlive the request that made it. */
    @Test
    public void unregisteringAPreviewStopsServingIt() throws Exception {
        SaikuVirtualFileHandler.setRepositoryReader(path -> null);
        String ref = SaikuVirtualFileHandler.registerPreview("<Schema name=\"Unsaved\"/>");
        SaikuVirtualFileHandler.unregisterPreview(ref);

        try {
            new SaikuVirtualFileHandler().readVirtualFile(ref);
            fail("an unregistered preview must no longer resolve");
        } catch (FileNotFoundException expected) {
            // exactly right: nothing serves it, and it does NOT fall through to the filesystem
        }
        assertEquals("a preview leaked", 0, SaikuVirtualFileHandler.previewCount());
    }

    @Test
    public void unregisteringIsIdempotentAndNullSafe() {
        String ref = SaikuVirtualFileHandler.registerPreview("<Schema/>");
        SaikuVirtualFileHandler.unregisterPreview(ref);
        SaikuVirtualFileHandler.unregisterPreview(ref);
        SaikuVirtualFileHandler.unregisterPreview(null);

        assertEquals(0, SaikuVirtualFileHandler.previewCount());
    }

    /** Two concurrent previews must not see each other's schema. */
    @Test
    public void previewsAreIsolatedFromEachOther() throws Exception {
        String a = SaikuVirtualFileHandler.registerPreview("<Schema name=\"AAA\"/>");
        String b = SaikuVirtualFileHandler.registerPreview("<Schema name=\"BBB\"/>");
        try {
            SaikuVirtualFileHandler h = new SaikuVirtualFileHandler();
            assertTrue(new String(h.readVirtualFile(a).readAllBytes(), StandardCharsets.UTF_8).contains("AAA"));
            assertTrue(new String(h.readVirtualFile(b).readAllBytes(), StandardCharsets.UTF_8).contains("BBB"));
        } finally {
            SaikuVirtualFileHandler.unregisterPreview(a);
            SaikuVirtualFileHandler.unregisterPreview(b);
        }
    }
}
