/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fixed catalogue of main UI features an admin can toggle (saiku#2618).
 *
 * <p>Each entry names the feature, its SPA route, and — where the feature has a
 * REST surface of its own — the prefixes the server-side gate refuses for
 * denied callers. {@code restPrefixes} is empty for the MDX workbench: it shares
 * {@code /saiku/api/query} with the main workspace query editor, so a path gate
 * there would deny the workspace too; that feature is enforced at the UI layer
 * only (nav + route), which {@link #serverEnforced} records honestly.
 *
 * <p>The catalogue is deliberately closed. A new spoke (e.g. a future
 * "Mail" surface) is added here and to {@code FeatureGateFilter}'s prefix map;
 * the stored config file only ever references known keys.
 */
public enum FeatureCatalogue {

    DASHBOARDS(
            "dashboards",
            "Dashboards",
            "Dashboard index, editor, comments, history and image uploads",
            "/dashboards",
            List.of("/saiku/api/dashboards"),
            true),
    APPS(
            "apps",
            "Apps",
            "App Builder index and saved apps",
            "/apps",
            List.of("/saiku/api/apps"),
            true),
    NOTEBOOKS(
            "notebooks",
            "Notebooks",
            "Notebook index, editor and cell execution",
            "/notebooks",
            List.of("/saiku/api/notebooks"),
            true),
    WORKBENCH(
            "workbench",
            "MDX Workbench",
            "Standalone MDX workbench route (shares the query API with the workspace, so it is enforced at the UI layer only)",
            "/workbench",
            List.of(),
            false),
    SQL_WORKBENCH(
            "sql-workbench",
            "SQL Workbench",
            "Read-only SQL workbench against warehouse datasources",
            "/sql-workbench",
            List.of("/saiku/sql-workbench"),
            true);

    /** Stable config key — also the key the UI and the stored JSON use. */
    public final String key;
    /** Human label shown in the admin panel. */
    public final String label;
    /** One-line description shown in the admin panel. */
    public final String description;
    /** SPA route prefix the nav entry links to. */
    public final String uiRoute;
    /** REST path prefixes (within the Jersey mount, i.e. after {@code /rest}) the gate enforces. */
    public final List<String> restPrefixes;
    /** False when the feature has no REST surface of its own and is UI-enforced only. */
    public final boolean serverEnforced;

    FeatureCatalogue(
            String key,
            String label,
            String description,
            String uiRoute,
            List<String> restPrefixes,
            boolean serverEnforced) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.uiRoute = uiRoute;
        this.restPrefixes = Collections.unmodifiableList(restPrefixes);
        this.serverEnforced = serverEnforced;
    }

    /** The feature for a config key, or null when the key is unknown. */
    public static FeatureCatalogue of(String key) {
        if (key == null) {
            return null;
        }
        for (FeatureCatalogue f : values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        return null;
    }

    /** Every REST prefix of every server-enforced feature, for the gate's path map. */
    public static List<String> allRestPrefixes() {
        return Arrays.stream(values())
                .flatMap(f -> f.restPrefixes.stream())
                .toList();
    }
}
