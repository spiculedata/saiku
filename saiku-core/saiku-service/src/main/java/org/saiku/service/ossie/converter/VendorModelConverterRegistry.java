/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The set of importable formats, keyed by wire id.
 *
 * <p>Deliberately a registry rather than a switch so saiku#1730's "converter hub" stays open:
 * a format is a {@link VendorModelConverter} bean, and the REST/UI layers only ever see ids.
 * Adding a third spoke (dbt-semantic-layer, Snowflake, …) means implementing the interface and
 * adding it to the constructor list — no changes to the resource, the validation report or the UI.
 */
public class VendorModelConverterRegistry {

    private final Map<String, VendorModelConverter> byId = new LinkedHashMap<>();

    public VendorModelConverterRegistry(VendorModelConverter... converters) {
        for (VendorModelConverter c : converters) {
            byId.put(normalise(c.id()), c);
        }
    }

    /** The converters shipped in-box. */
    public static VendorModelConverterRegistry defaults() {
        return new VendorModelConverterRegistry(new LookmlConverter(), new DbtManifestConverter());
    }

    /** @return the converter, or null when {@code formatId} is unknown */
    public VendorModelConverter find(String formatId) {
        if (formatId == null) return null;
        return byId.get(normalise(formatId));
    }

    /**
     * @return the converter for {@code formatId}
     * @throws IllegalArgumentException naming the supported ids, so the client gets a usable 400
     */
    public VendorModelConverter require(String formatId) {
        VendorModelConverter c = find(formatId);
        if (c == null) {
            throw new IllegalArgumentException(
                    "Unknown import format '" + formatId + "'. Supported: " + String.join(", ", byId.keySet()) + ".");
        }
        return c;
    }

    public List<FormatDescriptor> supported() {
        List<FormatDescriptor> out = new ArrayList<>();
        for (VendorModelConverter c : byId.values()) {
            out.add(new FormatDescriptor(c.id(), c.displayName(), c.description(), c.fileExtensions()));
        }
        return out;
    }

    private static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** One entry of {@code GET /saiku/api/ossie/import/formats} — what the UI's picker renders. */
    public static class FormatDescriptor {
        private final String id;
        private final String displayName;
        private final String description;
        private final List<String> fileExtensions;

        public FormatDescriptor(String id, String displayName, String description, List<String> fileExtensions) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.fileExtensions = fileExtensions;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getFileExtensions() {
            return fileExtensions;
        }
    }
}
