/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import java.util.List;

/**
 * Renders an already-resolved {@link Snapshot} into a single encoded artifact (saiku#1810).
 *
 * <p>Implementations are <b>purely computational</b>: they take resolved label/value pairs and lay
 * them out. They must not open a socket, resolve a hostname, or shell out to a browser — the
 * reference that selected the content was signature-verified before anything reached this seam, and
 * an implementation that could reach the network would reintroduce the SSRF primitive the signature
 * exists to prevent. {@link SnapshotRenderService} runs every implementation on a bounded, timed
 * worker and caps the output size, so a pathological layout cannot wedge a scheduler thread.
 */
public interface SnapshotRenderer {

    /** The format this renderer emits. */
    SnapshotFormat format();

    /**
     * Encode {@code snapshot} as {@link #format()}.
     *
     * @param snapshot the resolved title + rows; never null
     * @return the encoded bytes; must be non-empty and no larger than the service's output cap
     * @throws SnapshotRenderException if the content cannot be laid out or encoded
     */
    byte[] render(Snapshot snapshot);

    /** The resolved content of a snapshot: a title plus label/value rows in render order. */
    record Snapshot(String title, List<Row> rows) {

        public Snapshot {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /** One rendered tile: its display label and the formatted value read under the owner's RLS. */
        public record Row(String label, String value) {}
    }
}
