/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

/**
 * One thing in the repository that references a measure / dimension / hierarchy / level.
 * Returned by {@link LineageResource} grouped by {@link #kind}, see saiku#1120.
 */
public class LineageDependent {

    /** {@code "dashboard" | "saved-query" | "calc-measure"}. */
    public String kind;

    /** Display name — the dashboard/query name, or the calculated member's name. */
    public String name;

    /** Repository path to the dependent (schema calc members append {@code #<memberName>}). */
    public String path;

    /** Filesystem last-modified time in millis since epoch, used as the "last used" proxy for
     *  Phase 1 (no execution-audit log to join against yet — see saiku#906/#1120). {@code 0} when
     *  unknown (schema-level calculated members don't carry a per-member timestamp). */
    public long lastModified;

    public LineageDependent() {}

    public LineageDependent(String kind, String name, String path, long lastModified) {
        this.kind = kind;
        this.name = name;
        this.path = path;
        this.lastModified = lastModified;
    }
}
