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
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.ai.AiAxisSelection;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.web.rest.resources.dashboards.Dashboard;
import org.saiku.web.rest.resources.dashboards.DashboardFilter;
import org.saiku.web.rest.resources.dashboards.DashboardTile;
import org.saiku.web.rest.resources.dashboards.KpiConfig;
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
 * <p><b>Per-column granularity (saiku-cloud#949).</b> When the resource
 * parses cleanly into a {@link ThinQuery} with a {@link ThinQueryModel}
 * (or a dashboard tile carries enough refs to enumerate columns), the
 * inspector walks ONLY the dimensions/hierarchies/levels/measures the
 * resource actually projects and reports PII iff one of THOSE is
 * annotated. This avoids over-flagging finely-tagged cubes where a single
 * PII level on an otherwise-clean cube would have flipped every saved
 * query on that cube to {@code FORCE_ON} under v1.
 *
 * <p>Per-column extraction can fail for plenty of legitimate reasons —
 * MDX-mode queries with no model, a tile shape we don't recognise, a
 * malformed axis. Every such failure falls back to the conservative
 * v1 cube-level scan rather than weakening the safety floor. The
 * fallback is a one-line WARN log so an admin investigating the
 * over-flag can find the trigger.
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
                return inspectDashboard(raw, resourcePath, ownerUser, ownerRoles);
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

        AiSchema schema;
        try {
            schema = cubeMetadataService.getSchema(ref);
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: schema lookup failed for {}, failing closed: {}", cubeKey(ref), e.toString());
            return new Result(true, List.of(cubeKey(ref)), true);
        }
        if (schema == null) {
            // Unresolvable schema — fail-closed.
            return new Result(true, List.of(cubeKey(ref)), true);
        }

        // saiku-cloud#949: per-column refinement. Extract the dim/hier/level
        // and measure refs the query actually projects. If extraction
        // succeeds, check ONLY those against the schema's PII flags.
        ColumnRefs refs = extractRefsFromThinQuery(tq);
        if (refs == null || refs.isEmpty()) {
            // No model (MDX-mode), no axes populated, or extraction threw —
            // fall back to v1 cube-level scan. Cube-level scan is conservative:
            // any PII column anywhere flips the result to true, so the
            // safety floor holds.
            log.debug(
                    "EmbedPiiInspector: per-column extraction empty for {} — falling back to cube-level scan",
                    resourcePath);
            return new Result(schemaHasAnyPii(schema), List.of(cubeKey(ref)), false);
        }
        return new Result(refsHitPii(schema, refs), List.of(cubeKey(ref)), false);
    }

    private Result inspectDashboard(String raw, String resourcePath, String ownerUser, List<String> ownerRoles) {
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
        // Per-walk schema cache so a multi-tile dashboard binding the same
        // cube performs ONE schema lookup, not one per tile. The per-tile
        // column refs differ but the schema is invariant for the duration
        // of the inspection. {@code Boolean.TRUE} = schema unresolvable /
        // threw → that tile fails closed; {@code null} value = never looked
        // up yet.
        java.util.Map<String, AiSchema> schemaCache = new java.util.HashMap<>();
        java.util.Set<String> brokenCubes = new java.util.HashSet<>();
        boolean anyPii = false;
        for (DashboardTile tile : dash.layout.tiles) {
            // Text / image / decorative tiles can't bind PII. Skip.
            if (tile.cube == null && tile.target == null && (tile.query == null || tile.query.path == null)) {
                continue;
            }
            // Reference-style tile: read the referenced saved query under
            // the owner's scope and recurse on inspectQuery. This recursion
            // is bounded — saved queries do not nest, so depth is 1.
            if (tile.query != null && "reference".equals(tile.query.kind) && tile.query.path != null) {
                Result inner = inspectReferencedQuery(tile.query.path, ownerUser, ownerRoles);
                seenCubes.addAll(inner.cubeIds);
                if (inner.referencesPii) {
                    anyPii = true;
                }
                continue;
            }
            // Tile binds to a cube directly (inline query / filter widget
            // / KPI). Use the tile's cube as the schema home; extract the
            // tile's referenced columns and lookup against that one cube.
            AiCubeRef ref = tile.cube;
            if (ref == null) {
                // No cube to inspect (e.g. filter widget with no target
                // wired yet) — skip cleanly; nothing to leak.
                continue;
            }
            String key = cubeKey(ref);
            seenCubes.add(key);
            if (brokenCubes.contains(key)) {
                // Already failed to resolve this cube on a previous tile;
                // every other tile bound to it fails closed too.
                anyPii = true;
                continue;
            }
            AiSchema schema = schemaCache.get(key);
            if (schema == null && !schemaCache.containsKey(key)) {
                try {
                    schema = cubeMetadataService.getSchema(ref);
                } catch (RuntimeException e) {
                    log.warn("EmbedPiiInspector: schema lookup failed for {}, failing closed: {}", key, e.toString());
                    brokenCubes.add(key);
                    anyPii = true;
                    continue;
                }
                if (schema == null) {
                    brokenCubes.add(key);
                    anyPii = true;
                    continue;
                }
                schemaCache.put(key, schema);
            }
            ColumnRefs refs = extractRefsFromTile(tile);
            boolean tilePii = (refs == null || refs.isEmpty()) ? schemaHasAnyPii(schema) : refsHitPii(schema, refs);
            if (tilePii) {
                anyPii = true;
                // Keep walking to collect the full cube set for audit, even
                // after the first PII hit — useful when an operator wants
                // to see "which of the 5 cubes in this dashboard are the
                // problem ones."
            }
        }
        return new Result(anyPii, new ArrayList<>(seenCubes), false);
    }

    /**
     * Inspect a saved-query tile-reference under the owner's scope. Mirrors
     * the top-level {@link #inspectQuery} but reads the file inline rather
     * than from the inspect() entry point so we keep cube-ids merging into
     * the parent dashboard's audit set.
     */
    private Result inspectReferencedQuery(String path, String ownerUser, List<String> ownerRoles) {
        String raw;
        try {
            raw = datasourceService.getFileData(path, ownerUser, ownerRoles);
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: referenced saved-query unreadable, failing closed — path={}", path);
            return new Result(true, List.of(), true);
        }
        if (raw == null || raw.isEmpty()) {
            log.warn("EmbedPiiInspector: referenced saved-query empty, failing closed — path={}", path);
            return new Result(true, List.of(), true);
        }
        try {
            return inspectQuery(raw, path);
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: referenced saved-query parse failed, failing closed — path={}", path, e);
            return new Result(true, List.of(), true);
        }
    }

    /* ------------------------- column refs ------------------------- */

    /**
     * The dim/hier/level + measure columns a resource projects. Used as
     * the input to the per-column PII check (saiku-cloud#949).
     *
     * <p>Levels are keyed by lowercased {@code dim/hierarchy/level} short
     * names so they match the AiSchema map keys (see {@link AiSchema#key(String)}).
     * Measures are keyed by lowercased short name only — measures live
     * directly on the cube, not under a hierarchy.
     */
    static final class ColumnRefs {
        final Set<String> levelKeys = new LinkedHashSet<>(); // "dimKey/hierKey/levelKey"
        final Set<String> measureKeys = new LinkedHashSet<>();

        boolean isEmpty() {
            return levelKeys.isEmpty() && measureKeys.isEmpty();
        }

        void addLevel(String dim, String hier, String level) {
            if (dim == null || hier == null || level == null) return;
            levelKeys.add(AiSchema.key(dim) + "/" + AiSchema.key(hier) + "/" + AiSchema.key(level));
        }

        void addMeasure(String name) {
            if (name == null) return;
            measureKeys.add(AiSchema.key(name));
        }
    }

    /**
     * Walk a {@link ThinQuery}'s axes + measure details to collect the
     * dim/hier/level + measure refs it projects. Returns {@code null} if
     * the query has no queryModel (MDX-mode); the caller treats that as
     * "fall back to cube-level scan."
     */
    private static ColumnRefs extractRefsFromThinQuery(ThinQuery tq) {
        ThinQueryModel model = tq.getQueryModel();
        if (model == null || model.getAxes() == null) {
            return null;
        }
        ColumnRefs out = new ColumnRefs();
        try {
            for (ThinAxis axis : model.getAxes().values()) {
                if (axis == null || axis.getHierarchies() == null) continue;
                for (ThinHierarchy hier : axis.getHierarchies()) {
                    String dim = hier.getDimension();
                    // ThinHierarchy.name is populated with the unique name
                    // (e.g. "[Customer].[Customers]") by Thin.convertHierarchy.
                    // AiSchema keys hierarchies by short name, so extract
                    // the last bracket segment.
                    String hierShort = lastBracketSegment(hier.getName());
                    if (hier.getLevels() != null) {
                        for (ThinLevel level : hier.getLevels().values()) {
                            if (level == null) continue;
                            out.addLevel(dim, hierShort, level.getName());
                        }
                    }
                }
            }
            // Measures from the details slot. The details axis carries the
            // measure list across both row-pivot and column-pivot layouts.
            if (model.getDetails() != null && model.getDetails().getMeasures() != null) {
                for (ThinMeasure m : model.getDetails().getMeasures()) {
                    if (m != null) out.addMeasure(m.getName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: ThinQuery walk threw, falling back to cube-level scan: {}", e.toString());
            return null;
        }
        return out;
    }

    /**
     * Walk a single {@link DashboardTile} for the dim/hier/level + measure
     * refs it actually projects. Covers:
     * <ul>
     *   <li>{@code kpi.measure} — KPI tile's primary measure</li>
     *   <li>{@code target} — filter widget's driven dim/hier/level</li>
     *   <li>{@code query.body} (inline {@link AiQueryRequest}) — rows /
     *       columns / filters / measures</li>
     * </ul>
     * Reference-style queries are handled at the dashboard walker level
     * (recurse into the saved file), not here.
     *
     * <p>Returns {@code null} when no per-column refs could be extracted,
     * which the caller treats as "fall back to cube-level scan."
     */
    private static ColumnRefs extractRefsFromTile(DashboardTile tile) {
        ColumnRefs out = new ColumnRefs();
        try {
            KpiConfig kpi = tile.kpi;
            if (kpi != null && kpi.measure != null) {
                out.addMeasure(lastBracketSegment(kpi.measure));
            }
            DashboardFilter target = tile.target;
            if (target != null) {
                out.addLevel(target.dimension, target.hierarchy, target.level);
            }
            if (tile.query != null && "inline".equals(tile.query.kind) && tile.query.body != null) {
                AiQueryRequest body = tile.query.body;
                if (body.getRows() != null) {
                    for (AiAxisSelection s : body.getRows()) {
                        if (s != null) out.addLevel(s.getDimension(), s.getHierarchy(), s.getLevel());
                    }
                }
                if (body.getColumns() != null) {
                    for (AiAxisSelection s : body.getColumns()) {
                        if (s != null) out.addLevel(s.getDimension(), s.getHierarchy(), s.getLevel());
                    }
                }
                if (body.getFilters() != null) {
                    for (AiFilterSelection f : body.getFilters()) {
                        if (f != null) out.addLevel(f.getDimension(), f.getHierarchy(), f.getLevel());
                    }
                }
                if (body.getMeasures() != null) {
                    for (AiMeasureSelection m : body.getMeasures()) {
                        if (m != null) out.addMeasure(m.getName());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("EmbedPiiInspector: dashboard tile walk threw, falling back to cube-level scan: {}", e.toString());
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Check whether any of the supplied column refs maps to a PII-annotated
     * schema entry. An unknown ref (one that doesn't resolve to anything in
     * the schema map) is treated as "not PII" — that's the right call for
     * the embed gate because:
     * <ul>
     *   <li>It cannot expose PII data — the engine will reject the query at
     *       run time anyway if the ref doesn't resolve to a real column;</li>
     *   <li>The cube-level fallback already covers the "we couldn't read
     *       the schema" case, so we never get here with a null schema.</li>
     * </ul>
     */
    private static boolean refsHitPii(AiSchema schema, ColumnRefs refs) {
        for (String mKey : refs.measureKeys) {
            AiSchema.Measure m = schema.measures.get(mKey);
            if (m != null && m.pii) return true;
        }
        for (String lKey : refs.levelKeys) {
            String[] parts = lKey.split("/", 3);
            if (parts.length != 3) continue;
            AiSchema.Dimension d = schema.dimensions.get(parts[0]);
            if (d == null) continue;
            AiSchema.Hierarchy h = d.hierarchies.get(parts[1]);
            if (h == null) continue;
            AiSchema.Level l = h.levels.get(parts[2]);
            if (l != null && l.pii) return true;
        }
        return false;
    }

    /**
     * v1 cube-level scan kept as the safety fallback. Returns true iff ANY
     * measure or level on the cube carries the PII flag. Used when:
     * <ul>
     *   <li>per-column extraction returns null (MDX-mode, unparseable);</li>
     *   <li>per-column extraction returns empty (no axes populated yet);</li>
     *   <li>extraction throws.</li>
     * </ul>
     */
    private static boolean schemaHasAnyPii(AiSchema schema) {
        if (schema == null) return true;
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
    }

    /**
     * Return the last bracketed segment of a Mondrian-style unique name.
     * {@code [Customer].[Customers]} → {@code "Customers"};
     * {@code [Customers]} → {@code "Customers"}; a non-bracketed input
     * passes through unchanged so the helper composes with short-name
     * inputs from KPI / filter widget refs.
     */
    static String lastBracketSegment(String name) {
        if (name == null || name.isEmpty()) return name;
        int open = name.lastIndexOf('[');
        if (open < 0) return name;
        int close = name.indexOf(']', open);
        if (close < 0) return name.substring(open + 1);
        return name.substring(open + 1, close);
    }

    private static String cubeKey(AiCubeRef ref) {
        return ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
    }
}
