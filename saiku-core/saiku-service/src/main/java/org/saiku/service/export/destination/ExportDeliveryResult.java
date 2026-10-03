/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a destination reports back after a successful {@link ExportDestination#deliver} (saiku#1987).
 *
 * <p>Deliberately thin: a remote object id (Drive file id, S3 key) plus a human-readable
 * description, so the scheduled job's run history and the admin "test delivery" response can point
 * an operator at what was actually created. Everything here is safe to log.
 */
public final class ExportDeliveryResult {

    private final String destinationId;
    private final String remoteId;
    private final String description;

    private ExportDeliveryResult(String destinationId, String remoteId, String description) {
        this.destinationId = destinationId;
        this.remoteId = remoteId;
        this.description = description;
    }

    public static ExportDeliveryResult of(String destinationId, String remoteId, String description) {
        return new ExportDeliveryResult(destinationId, remoteId, description);
    }

    /** The {@link ExportDestination#id()} that produced this result. */
    public String destinationId() {
        return destinationId;
    }

    /** The remote object's identifier — a Drive file id, an S3 key. Null when the destination has none. */
    public String remoteId() {
        return remoteId;
    }

    /** A short human-readable summary, e.g. {@code "uploaded sales.csv to Drive folder 1Ab…"}. */
    public String description() {
        return description;
    }

    public Map<String, String> asMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("destination", destinationId);
        if (remoteId != null) {
            out.put("remoteId", remoteId);
        }
        if (description != null) {
            out.put("description", description);
        }
        return out;
    }

    @Override
    public String toString() {
        return "ExportDeliveryResult[" + asMap() + "]";
    }

    /** Convenience for tests asserting the ordered field set. */
    public List<String> fieldNames() {
        return List.copyOf(asMap().keySet());
    }
}
