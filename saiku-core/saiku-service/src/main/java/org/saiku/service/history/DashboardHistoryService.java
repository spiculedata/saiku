/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.util.DashboardSidecars;
import org.saiku.service.util.StorageLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dashboard version history (issue #947). Each save archives the replaced
 * state here; users can list, preview, and restore prior versions.
 *
 * <p>Stored as one flat {@code saiku-history-<sanitised-dashboardPath>.jsonl}
 * at the datadir root via the internal-file API (the internal writer doesn't
 * create subdirs). One version per line, chronological; the most recent
 * {@link #RETENTION} are kept (prune-on-append). Mechanics only — the REST
 * layer owns identity + the dashboard canRead/canWrite gate.
 *
 * <p>Read-modify-write (no append primitive); single-node-safe, not
 * concurrency-guarded — see the PR note.
 */
public class DashboardHistoryService {

    private static final Logger log = LoggerFactory.getLogger(DashboardHistoryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Versions retained per dashboard. */
    static final int RETENTION = 50;

    private DatasourceService datasourceService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    /** Archive {@code dashboardJson} as the latest version, pruning to the most
     *  recent {@link #RETENTION}. No-op for null/blank content. An oversized
     *  version (over {@code saiku.history.maxEntryBytes}, #2535) is not archived
     *  — the save itself still succeeds; only the historical snapshot is
     *  skipped, so the history file cannot grow without bound. */
    public DashboardVersion archive(String dashboardPath, String dashboardJson, String author) {
        if (dashboardJson == null || dashboardJson.isBlank()) {
            return null;
        }
        long maxEntryBytes = StorageLimits.historyEntryBytes();
        long entryBytes = dashboardJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (entryBytes > maxEntryBytes) {
            log.warn(
                    "Not archiving dashboard version for {} ({} bytes > cap {} bytes, saiku.history.maxEntryBytes)",
                    dashboardPath,
                    entryBytes,
                    maxEntryBytes);
            return null;
        }
        DashboardVersion v = new DashboardVersion();
        v.version = UUID.randomUUID().toString();
        v.createdAt = System.currentTimeMillis();
        v.author = author;
        v.dashboard = dashboardJson;

        List<DashboardVersion> all = readAll(dashboardPath);
        all.add(v);
        // Keep the most recent RETENTION (drop oldest from the front).
        while (all.size() > RETENTION) {
            all.remove(0);
        }
        writeAll(dashboardPath, all);
        return v;
    }

    /** Versions newest-first (full snapshots; the resource trims for listing). */
    public List<DashboardVersion> list(String dashboardPath) {
        List<DashboardVersion> all = readAll(dashboardPath);
        Collections.reverse(all);
        return all;
    }

    /** One version by id, or null. */
    public DashboardVersion getVersion(String dashboardPath, String versionId) {
        for (DashboardVersion v : readAll(dashboardPath)) {
            if (v.version != null && v.version.equals(versionId)) {
                return v;
            }
        }
        return null;
    }

    /** Delete this dashboard's entire history file — called when the dashboard
     *  itself is removed so the sidecar JSONL isn't orphaned (#947 cleanup). */
    public void purge(String dashboardPath) {
        try {
            DashboardSidecars.purge(datasourceService, KIND, dashboardPath);
        } catch (RuntimeException e) {
            log.warn("could not purge history for {}", dashboardPath, e);
        }
    }

    /* --------------------------- internals --------------------------- */

    /** Sidecar kind — the prefix DashboardSidecars names our file with. */
    static final String KIND = "history";

    /** Sidecar file for a dashboard. saiku#2550d: keyed by a hash of the exact
     *  path (DashboardSidecars), not the old sanitised flattening that collapsed
     *  {@code a_b/x} and {@code a/b/x} onto one file. Kept for logging + tests. */
    static String historyPath(String dashboardPath) {
        return DashboardSidecars.path(KIND, dashboardPath);
    }

    private List<DashboardVersion> readAll(String dashboardPath) {
        List<DashboardVersion> out = new ArrayList<>();
        String raw = DashboardSidecars.read(datasourceService, KIND, dashboardPath);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(MAPPER.readValue(line, DashboardVersion.class));
            } catch (Exception e) {
                log.warn("Skipping unparseable history line in {}", historyPath(dashboardPath), e);
            }
        }
        return out;
    }

    private void writeAll(String dashboardPath, List<DashboardVersion> versions) {
        StringBuilder sb = new StringBuilder();
        for (DashboardVersion v : versions) {
            try {
                sb.append(MAPPER.writeValueAsString(v)).append('\n');
            } catch (Exception e) {
                log.error("Failed to serialise dashboard version {}", v.version, e);
            }
        }
        DashboardSidecars.write(datasourceService, KIND, dashboardPath, sb.toString());
    }
}
