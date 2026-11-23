/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import java.util.Locale;

/** The output formats the headless snapshot renderer (saiku#1810) can produce. */
public enum SnapshotFormat {

    /** Paginated PDF — the attachment format for scheduled subscriptions (#943). */
    PDF("application/pdf", "pdf"),

    /** Single-page PNG — the thumbnail format for channel digests (#1099). */
    PNG("image/png", "png");

    private final String mediaType;
    private final String extension;

    SnapshotFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    /** The MIME type a mail attachment / webhook payload declares for this format. */
    public String mediaType() {
        return mediaType;
    }

    /** The file extension (no leading dot) a rendered artifact should be named with. */
    public String extension() {
        return extension;
    }

    /** Parse {@code value} case-insensitively; {@code null} when unknown/blank. Never throws. */
    public static SnapshotFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (SnapshotFormat f : values()) {
            if (f.name().equals(v)) {
                return f;
            }
        }
        return null;
    }
}
