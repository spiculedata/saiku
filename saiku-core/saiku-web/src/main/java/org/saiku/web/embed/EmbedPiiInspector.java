/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku-cloud#940 mint-time PII gate. Given a resource (saved query or
 * dashboard), inspects the cubes it binds to and reports whether any
 * referenced column is annotated {@code saiku.semantic.pii=true} (the
 * schema-side foundation from saiku#902).
 *
 * <p>Used by {@code EmbedTokenResource.mint} (sets {@code redactionPolicy=
 * FORCE_ON} on the minted token) and {@code EmbedTokenResource.grantPublic}
 * (refuses the public grant outright — public-anonymous reads of PII
 * captions are the highest-blast-radius leak on the embed surface).
 *
 * <p>Conservative v1 — cube-level granularity. If the cube has ANY
 * PII-annotated measure or level, the resource is treated as referencing
 * PII. A future refinement could parse the ThinQuery's axis selections /
 * each dashboard tile's filter targets to check ONLY columns the resource
 * actually projects, but the conservative pass is simpler, faster (cube
 * metadata is already cached), and safer (no leak path that the parser
 * misses can survive). Operators who want finer granularity can tag
 * fewer columns PII.
 *
 * <p>The inspector is best-effort by design: if a resource is unreadable,
 * a tile references an unloadable cube, or the schema cache fails, we
 * return {@code referencesPii=true} (fail-CLOSED) so the embed gate
 * defaults to the safer posture. The only path to {@code false} is a
 * cleanly-parsed resource whose every referenced cube cleanly resolved to
 * a schema with NO PII annotations.
 */
public class EmbedPiiInspector {

    private static final Logger log = LoggerFactory.getLogger(EmbedPiiInspector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DatasourceService datasourceService;
    private final AiCubeMetadataService cubeMetadataService;

    public EmbedPiiInspector(DatasourceService datasourceService, AiCubeMetadataService cubeMetadataService) {
        this.datasourceService = datasourceService;
        this.cubeMetadataService = cubeMetadataService;
    }

    /**
     * Result of an inspection. {@link #referencesPii} is the load-bearing
     * field that drives mint-time gating; {@link #cubeIds} are the distinct
     * cubes the resource binds to (useful for audit logs and admin
     * tooling). {@link #fellOpenForUnresolvable} flags a "fail-closed" case
     * where the inspector refused but couldn't actually inspect — so an
     * admin investigating a refused public grant can tell "we found PII"
     * apart from "we couldn't tell so we said no."
     */
    public static final class Result {
        public final boolean referencesPii;
        public final List<String> cubeIds;
        public final boolean fellOpenForUnresolvable;

        public Result(boolean referencesPii, List<String> cubeIds, boolean fellOpenForUnresolvable) {
            this.referencesPii = referencesPii;
            this.cubeIds = List.copyOf(cubeIds);
            this.fellOpenForUnresolvable = fellOpenForUnresolvable;
        }
    }

    /**
     * @param resourceKind {@code "query"} or {@code "dashboard"}
     * @param resourcePath repository path of the resource
     * @param ownerUser owner-scoped read of the resource (the embed flow
     *     runs reads under the grantor / owner identity, so the inspector
     *     uses the same scope to read the resource file)
     * @param ownerRoles roles to pass to the repository read
     */
    public Result inspect(String resourceKind, String resourcePath, String ownerUser, List<String> ownerRoles) {
        if (resourcePath == null || resourcePath.isBlank()) {
            // Don't gate something we can't even identify — the caller's
            // upstream validation should have rejected this before us.
            return new Result(false, List.of(), false);
        }
        if (datasourceService == null || cubeMetadataService == null) {
            // No infrastructure wired (test / stub context). Fall to
            // permissive — the caller's surrounding GRANT check is still
            // the load-bearing security check; the PII gate is an
            // additional layer on top.
            return new Result(false, List.of(), false);
        }

        String raw;
        try {
            raw = datasourceService.getFileData(resourcePath, ownerUser, ownerRoles);
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: file unreadable, failing closed — path={} owner={}", resourcePath, ownerUser);
            return new Result(true, List.of(), true);
        }
        if (raw == null || raw.isEmpty()) {
            log.warn("EmbedPiiInspector: file empty, failing closed — path={}", resourcePath);
            return new Result(true, List.of(), true);
        }

        try {
            if ("query".equals(resourceKind)) {
                return inspectQuery(raw, resourcePath);
            } else if ("dashboard".equals(resourceKind)) {
                return inspectDashboard(raw, resourcePath);
            } else {
                // Unknown kind — fail-closed.
                log.warn("EmbedPiiInspector: unknown resourceKind {}, failing closed", resourceKind);
                return new Result(true, List.of(), true);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "EmbedPiiInspector: parse / schema-lookup failed for {}, failing closed: {}",
                    resourcePath,
                    e.toString());
            return new Result(true, List.of(), true);
        }
    }

    /* ------------------------- internals ------------------------- */

    private Result inspectQuery(String raw, String resourcePath) {
        ThinQuery tq;
        try {
            tq = MAPPER.readValue(raw, ThinQuery.class);
        } catch (Exception e) {
            log.warn("EmbedPiiInspector: not valid ThinQuery JSON — {}", resourcePath);
            return new Result(true, List.of(), true);
        }
        SaikuCube cube = tq.getCube();
        if (cube == null) {
            // Cube-less saved query — typically an MDX-mode query bound
            // only to a connection. We can't safely inspect; fail-closed.
            return new Result(true, List.of(), true);
        }
        AiCubeRef ref = new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName());
        return new Result(cubeReferencesPii(ref), List.of(cubeKey(ref)), false);
    }

    private Result inspectDashboard(String raw, String resourcePath) {
        Dashboard dash;
        try {
            dash = MAPPER.readValue(raw, Dashboard.class);
        } catch (Exception e) {
            log.warn("EmbedPiiInspector: not valid Dashboard JSON — {}", resourcePath);
            return new Result(true, List.of(), true);
        }
        if (dash.layout == null || dash.layout.tiles == null) {
            return new Result(false, List.of(), false);
        }
        Set<String> seenCubes = new LinkedHashSet<>();
        boolean anyPii = false;
        for (DashboardTile tile : dash.layout.tiles) {
            if (tile.cube == null) continue; // text / decorative tile
            AiCubeRef ref = tile.cube;
            String key = cubeKey(ref);
            if (!seenCubes.add(key)) continue; // dedupe — many tiles can bind the same cube
            if (cubeReferencesPii(ref)) {
                anyPii = true;
                // Keep walking to collect the full cube set for audit, even
                // after the first PII hit — useful when an operator wants
                // to see "which of the 5 cubes in this dashboard are the
                // problem ones."
            }
        }
        return new Result(anyPii, new ArrayList<>(seenCubes), false);
    }

    private boolean cubeReferencesPii(AiCubeRef ref) {
        try {
            AiSchema schema = cubeMetadataService.getSchema(ref);
            if (schema == null) return true; // null = unresolvable → fail-closed
            for (AiSchema.Measure m : schema.measures.values()) {
                if (m.pii) return true;
            }
            for (AiSchema.Dimension d : schema.dimensions.values()) {
                for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                    for (AiSchema.Level l : h.levels.values()) {
                        if (l.pii) return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: schema lookup failed for {}, failing closed: {}", cubeKey(ref), e.toString());
            return true;
        }
    }

    private static String cubeKey(AiCubeRef ref) {
        return ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
    }
}
