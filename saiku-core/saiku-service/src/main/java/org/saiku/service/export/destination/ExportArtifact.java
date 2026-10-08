/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The bytes an exporter produced, ready for an {@link ExportDestination} to deliver (saiku#1987).
 *
 * <p>Immutable and <b>fully in-memory</b> for the MVP: the existing exporters (CSV / XLS / PDF via
 * {@code ExporterResource}) and the scheduled {@code EXPORT_DELIVERY} job all materialise a byte
 * array anyway, so a blob is the honest abstraction at this layer. A future streaming destination can
 * widen this to an {@code InputStream} + length without changing the destination contract, because
 * nothing in the SPI promises the array is what a large export must use.
 *
 * <p>{@link #filename()} is <b>not</b> trusted as a path by any destination: implementations write
 * the base name only, so a crafted name can never escape a remote folder. It is validated here
 * against the usual CSV-injection / control-character hazards so a bad name is rejected at the
 * producer rather than at three different destinations.
 */
public final class ExportArtifact {

    private final String filename;
    private final String contentType;
    private final byte[] content;
    private final Map<String, String> metadata;

    private ExportArtifact(String filename, String contentType, byte[] content, Map<String, String> metadata) {
        this.filename = filename;
        this.contentType = contentType;
        this.content = content.clone();
        this.metadata = Map.copyOf(metadata);
    }

    /**
     * A builder, so producers that attach provenance stay readable.
     *
     * <pre>{@code
     * ExportArtifact.builder("sales-2026-04.csv", "text/csv", csvBytes)
     *         .metadata("jobId", jobId)
     *         .build();
     * }</pre>
     */
    public static Builder builder(String filename, String contentType, byte[] content) {
        return new Builder(filename, contentType, content);
    }

    /** A plain artifact with no provenance metadata. */
    public static ExportArtifact of(String filename, String contentType, byte[] content) {
        return builder(filename, contentType, content).build();
    }

    public String filename() {
        return filename;
    }

    /** The MIME type, e.g. {@code text/csv} or {@code application/pdf}. */
    public String contentType() {
        return contentType;
    }

    /** The bytes. A defensive copy on the way out so a caller cannot mutate a delivered artifact. */
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }

    /**
     * Free-form provenance for the destination — {@code jobId}, {@code sourceFile},
     * {@code generatedAt}. Never used for authorisation, and a destination must not treat an
     * untrusted metadata value as a command.
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ExportArtifact[filename=" + filename + ", contentType=" + contentType + ", size=" + content.length
                + "]";
    }

    /** Builder for {@link ExportArtifact}. */
    public static final class Builder {

        private final String filename;
        private final String contentType;
        private final byte[] content;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String filename, String contentType, byte[] content) {
            this.filename = validateFilename(filename);
            this.contentType = Objects.requireNonNull(contentType, "contentType is required")
                    .trim();
            this.content =
                    Objects.requireNonNull(content, "content is required").clone();
        }

        public Builder metadata(String key, String value) {
            if (key != null && !key.isBlank() && value != null) {
                metadata.put(key.trim(), value);
            }
            return this;
        }

        public ExportArtifact build() {
            return new ExportArtifact(filename, contentType, content, metadata);
        }

        /**
         * Reject anything that would make a remote file name ambiguous or dangerous: a path
         * separator, a {@code ..} segment, a control character, a leading dot, or an over-long name.
         * Producers derive this from a saved-query name, which is user-supplied.
         */
        private static String validateFilename(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("artifact filename is required");
            }
            String trimmed = name.trim();
            if (trimmed.length() > 200) {
                throw new IllegalArgumentException("artifact filename must be at most 200 characters");
            }
            if (trimmed.equals(".") || trimmed.equals("..")) {
                throw new IllegalArgumentException("artifact filename must not be a path segment");
            }
            if (trimmed.startsWith(".")) {
                throw new IllegalArgumentException("artifact filename must not start with a dot");
            }
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                    throw new IllegalArgumentException(
                            "artifact filename must not contain a path separator or control character");
                }
            }
            return trimmed;
        }
    }

    /** Test/diagnostic helper: the artifact's content as a UTF-8 string. */
    public String contentAsString() {
        return new String(content, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExportArtifact other)) {
            return false;
        }
        return filename.equals(other.filename)
                && contentType.equals(other.contentType)
                && Arrays.equals(content, other.content);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * filename.hashCode() + contentType.hashCode()) + Arrays.hashCode(content);
    }
}
