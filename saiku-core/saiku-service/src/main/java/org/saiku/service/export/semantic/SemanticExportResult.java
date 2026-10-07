/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

import java.util.Objects;

/** One rendered export artefact — the file bytes plus enough metadata to serve it as a download. */
public final class SemanticExportResult {

    private final String filename;
    private final String contentType;
    private final byte[] content;

    public SemanticExportResult(String filename, String contentType, byte[] content) {
        this.filename = Objects.requireNonNull(filename, "filename");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.content = Objects.requireNonNull(content, "content");
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content;
    }
}
