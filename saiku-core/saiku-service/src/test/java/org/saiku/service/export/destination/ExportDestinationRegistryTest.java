/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Registration + lookup behaviour of the destination registry (saiku#1987). */
public class ExportDestinationRegistryTest {

    /** A destination that records what it was handed. */
    static final class Recording implements ExportDestination {
        private final String id;
        final StringBuilder delivered = new StringBuilder();

        Recording(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id + " display";
        }

        @Override
        public List<ExportDestinationConfigField> configFields() {
            return List.of(ExportDestinationConfigField.required("folderId", "Folder"));
        }

        @Override
        public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
            config.require("folderId");
        }

        @Override
        public ExportDeliveryResult deliver(ExportArtifact artifact, ExportDestinationConfig config) {
            delivered.append(artifact.filename());
            return ExportDeliveryResult.of(id, "remote-" + id, "ok");
        }
    }

    @Test
    public void registersAndFindsByIdCaseInsensitively() throws Exception {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.register("GOOGLE_DRIVE", new Recording("GOOGLE_DRIVE"));
        assertEquals(1, registry.size());
        assertNotNull(registry.find("google_drive"));
        assertNotNull(registry.find("  GOOGLE_DRIVE  "));
        assertEquals(List.of("GOOGLE_DRIVE"), registry.ids());
    }

    @Test
    public void setDestinationsReplacesPreviousRegistrations() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.register("S3", new Recording("S3"));
        registry.setDestinations(Map.of("GOOGLE_DRIVE", new Recording("GOOGLE_DRIVE")));
        assertEquals(List.of("GOOGLE_DRIVE"), registry.ids());
    }

    @Test
    public void setDestinationsToleratesNull() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.setDestinations(null);
        assertEquals(0, registry.size());
    }

    /** A bean id that disagrees with the destination's own id() must not become a usable alias. */
    @Test
    public void rejectsAKeyThatDisagreesWithTheDeclaredId() {
        try {
            new ExportDestinationRegistry().register("DRIVE", new Recording("GOOGLE_DRIVE"));
            fail("expected a rejection of the mismatched key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DRIVE"));
            assertTrue(e.getMessage(), e.getMessage().contains("GOOGLE_DRIVE"));
        }
    }

    @Test
    public void rejectsNonUpperSnakeCaseKeys() {
        for (String bad : new String[] {"google drive", "googleDrive", "GOOGLE-DRIVE", ""}) {
            try {
                new ExportDestinationRegistry().register(bad, new Recording("GOOGLE_DRIVE"));
                fail("expected a rejection of the key '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                // correct
            }
        }
    }

    @Test
    public void rejectsADuplicateRegistration() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.register("S3", new Recording("S3"));
        try {
            registry.register("S3", new Recording("S3"));
            fail("expected a duplicate-registration rejection");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullBean() {
        new ExportDestinationRegistry().register("S3", null);
    }

    @Test
    public void requireFailsWithTheRegisteredIdsSoAnOperatorCanSelfCorrect() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.register("GOOGLE_DRIVE", new Recording("GOOGLE_DRIVE"));
        try {
            registry.require("DROPBOX");
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DROPBOX"));
            assertTrue(
                    "the message should list what IS registered: " + e.getMessage(),
                    e.getMessage().contains("GOOGLE_DRIVE"));
        }
    }

    @Test
    public void listIsOrderedById() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        registry.register("S3", new Recording("S3"));
        registry.register("GOOGLE_DRIVE", new Recording("GOOGLE_DRIVE"));
        registry.register("AZURE", new Recording("AZURE"));
        assertEquals(List.of("AZURE", "GOOGLE_DRIVE", "S3"), registry.ids());
    }

    @Test
    public void findOnNullOrUnknownReturnsNull() {
        ExportDestinationRegistry registry = new ExportDestinationRegistry();
        assertNull(registry.find(null));
        assertNull(registry.find("NOPE"));
    }
}
