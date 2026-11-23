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
 * Schema (Ossie/M4 YAML) version history — Phase 1 of issue #1121. Every write of an Ossie
 * datasource's semantic-model YAML (see {@code org.saiku.web.rest.resources.ossie.OssieSchemaResource})
 * is recorded here so an admin can see who changed a cube's schema and when, with the raw
 * before/after YAML for an expandable diff. Restore/rollback is out of scope for Phase 1 (tracked
 * on the issue as Phase 3).
 *
 * <p>Stored as one flat {@code saiku-schema-history-<sanitised-target>.jsonl} at the datadir root
 * via the internal-file API (the internal writer doesn't create subdirs) — same mechanics as
 * {@code DashboardHistoryService} (#947). One version per line, chronological; the most recent
 * {@link #RETENTION} are kept (prune-on-append).
 *
 * <p>Read-modify-write (no append primitive); single-node-safe, not concurrency-guarded — same
 * caveat as the dashboard history service.
 */
public class SchemaHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaHistoryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Versions retained per target. */
    static final int RETENTION = 50;

    private DatasourceService datasourceService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    /**
     * Record a write of {@code target}'s YAML. {@code oldYaml} is {@code null} when the file
     * didn't exist before this write (a {@code CREATE}); otherwise the write is an {@code UPDATE}.
     * No-op (returns {@code null}) when {@code newYaml} is null/blank — there's nothing to
     * archive if the write itself didn't happen.
     */
    public SchemaVersion archive(String target, String oldYaml, String newYaml, String author) {
        if (newYaml == null || newYaml.isBlank()) {
            return null;
        }
        SchemaVersion v = new SchemaVersion();
        v.id = UUID.randomUUID().toString();
        v.createdAt = System.currentTimeMillis();
        v.author = author;
        v.action = (oldYaml == null || oldYaml.isBlank()) ? "CREATE" : "UPDATE";
        v.target = target;
        v.oldYaml = oldYaml;
        v.newYaml = newYaml;

        List<SchemaVersion> all = readAll(target);
        all.add(v);
        // Keep the most recent RETENTION (drop oldest from the front).
        while (all.size() > RETENTION) {
            all.remove(0);
        }
        writeAll(target, all);
        return v;
    }

    /** Versions newest-first (full entries, including old/new YAML). */
    public List<SchemaVersion> list(String target) {
        List<SchemaVersion> all = readAll(target);
        Collections.reverse(all);
        return all;
    }

    /** One version by id, or null. */
    public SchemaVersion getVersion(String target, String versionId) {
        for (SchemaVersion v : readAll(target)) {
            if (v.id != null && v.id.equals(versionId)) {
                return v;
            }
        }
        return null;
    }

    /* --------------------------- internals --------------------------- */

    /** Flat, sanitised internal path at the datadir root (no subdir — the internal writer won't
     *  mkdir one). Separators collapse to {@code _} and {@code ..} is neutralised;
     *  resolveWithinDatadir guards the final path. */
    static String historyPath(String target) {
        String flat = target == null ? "null" : target.replaceAll("[^A-Za-z0-9._-]", "_");
        flat = flat.replace("..", "_");
        return "saiku-schema-history-" + flat + ".jsonl";
    }

    private List<SchemaVersion> readAll(String target) {
        List<SchemaVersion> out = new ArrayList<>();
        String raw = datasourceService.getInternalFileData(historyPath(target));
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(MAPPER.readValue(line, SchemaVersion.class));
            } catch (Exception e) {
                log.warn("Skipping unparseable schema-history line in {}", historyPath(target), e);
            }
        }
        return out;
    }

    private void writeAll(String target, List<SchemaVersion> versions) {
        StringBuilder sb = new StringBuilder();
        for (SchemaVersion v : versions) {
            try {
                sb.append(MAPPER.writeValueAsString(v)).append('\n');
            } catch (Exception e) {
                log.error("Failed to serialise schema version {}", v.id, e);
            }
        }
        datasourceService.saveInternalFile(historyPath(target), sb.toString(), null);
    }
}
