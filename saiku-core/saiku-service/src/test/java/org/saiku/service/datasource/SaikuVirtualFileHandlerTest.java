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
}
