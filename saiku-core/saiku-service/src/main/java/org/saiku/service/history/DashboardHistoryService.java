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
     *  recent {@link #RETENTION}. No-op for null/blank content. */
    public DashboardVersion archive(String dashboardPath, String dashboardJson, String author) {
        if (dashboardJson == null || dashboardJson.isBlank()) {
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
            datasourceService.removeInternalFile(historyPath(dashboardPath));
        } catch (RuntimeException e) {
            log.warn("could not purge history for {}", dashboardPath, e);
        }
    }

    /* --------------------------- internals --------------------------- */

    /** Flat, sanitised internal path at the datadir root (no subdir — the
     *  internal writer won't mkdir one). Separators collapse to {@code _} and
     *  {@code ..} is neutralised; resolveWithinDatadir guards the final path. */
    static String historyPath(String dashboardPath) {
        String flat = dashboardPath == null ? "null" : dashboardPath.replaceAll("[^A-Za-z0-9._-]", "_");
        flat = flat.replace("..", "_");
        return "saiku-history-" + flat + ".jsonl";
    }

    private List<DashboardVersion> readAll(String dashboardPath) {
        List<DashboardVersion> out = new ArrayList<>();
        String raw = datasourceService.getInternalFileData(historyPath(dashboardPath));
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
        datasourceService.saveInternalFile(historyPath(dashboardPath), sb.toString(), null);
    }
}
