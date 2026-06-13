/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.service.olap.OlapDiscoverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link AiCubeMetadataService} implementation backed by
 * {@link OlapDiscoverService}. Caches per-cube {@link AiSchema} snapshots
 * keyed by the cube's unique-name string. Cache invalidation is keyed off
 * a coarse generation counter that can be bumped by callers when the
 * underlying schema changes — Phase 3's enrichment overlay hooks into the
 * same generation seam.
 */
public class OlapAiCubeMetadataService implements AiCubeMetadataService {

    private static final Logger log = LoggerFactory.getLogger(OlapAiCubeMetadataService.class);

    private OlapDiscoverService discoverService;
    private final ConcurrentMap<String, AiSchema> schemaCache = new ConcurrentHashMap<>();
    /** saiku#902 phase 3: per-cube cache of PiiScanner findings. Populated on
     *  schema-build, returned by {@link #getPiiSuggestions}. Cleared in lock-step
     *  with {@link #schemaCache} so stale annotations don't haunt the
     *  suggestion surface after a schema reload. */
    private final ConcurrentMap<String, List<PiiScanner.Match>> piiSuggestionsCache = new ConcurrentHashMap<>();

    private java.util.function.Function<AiCubeRef, AiSchemaEnrichment> enrichmentProvider;
    private final AiSchemaEnricher enricher = new AiSchemaEnricher();
    /** saiku#810: when true, schema build probes each (dim, hier, level)
     *  triple with a 1-row level-members query and drops any triple that
     *  fails — catches "schema lies" cases where a hierarchy is enumerable
     *  via cube metadata but unqueryable through the AI converter's MDX
     *  shape (e.g. Mondrian parent-child closure synthetic hierarchies that
     *  expose under a different name than the discover service reports).
     *
     *  <p>Off by default to keep first-cube-load latency unchanged. Flip to
     *  true via Spring config or {@link #setProbeUnqueryable(boolean)} once
     *  the per-deployment probe cost has been profiled and accepted. */
    private boolean probeUnqueryable = false;

    public void setDiscoverService(OlapDiscoverService s) {
        this.discoverService = s;
    }

    /**
     * Phase 3: install a provider that produces an enrichment overlay
     * for a given cube. Called once per cache miss. Pass {@code null}
     * to disable enrichment (the default).
     */
    public void setEnrichmentProvider(java.util.function.Function<AiCubeRef, AiSchemaEnrichment> p) {
        this.enrichmentProvider = p;
    }

    /** Bump when downstream schema state changes (e.g. cube reload, draft enrichment update). */
    public void invalidateCache() {
        schemaCache.clear();
        // Lock-step so a re-scan happens on the next describe. Otherwise an
        // operator who fixes an annotation, reloads the cube, then hits the
        // suggestions endpoint sees the stale finding from before the fix.
        piiSuggestionsCache.clear();
    }

    /** saiku#810: enable the schema-build-time roundtrip probe that prunes
     *  unqueryable dim/hier/level triples. See {@link #probeUnqueryable}. */
    public void setProbeUnqueryable(boolean b) {
        this.probeUnqueryable = b;
    }

    public List<AiCubeSummary> listCubes() {
        List<AiCubeSummary> out = new ArrayList<>();
        try {
            for (SaikuCube cube : discoverService.getAllCubes()) {
                AiCubeSummary s = new AiCubeSummary();
                s.setConnectionName(cube.getConnection());
                s.setCatalog(cube.getCatalog());
                s.setSchema(cube.getSchema());
                s.setCubeName(cube.getName());
                s.setCubeCaption(cube.getCaption());
                try {
                    List<org.saiku.olap.dto.SaikuMember> measures = discoverService.getMeasures(cube);
                    s.setMeasureCount(measures == null ? 0 : measures.size());
                    if (measures != null && !measures.isEmpty()) {
                        s.setDefaultMeasure(measures.get(0).getCaption());
                    }
                } catch (RuntimeException ignore) {
                    // A bad single-cube measure lookup shouldn't poison the
                    // whole listing — leave defaultMeasure/measureCount blank.
                }
                out.add(s);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list cubes for AI metadata", e);
        }
        return out;
    }

    @Override
    public AiSchema getSchema(AiCubeRef ref) {
        if (ref == null || ref.getCubeName() == null) {
            throw new AiValidationException("cube", "cube ref required", null);
        }
        String key = cacheKey(ref);
        AiSchema cached = schemaCache.get(key);
        if (cached != null) return cached;
        AiSchema fresh = buildSchema(ref);
        if (enrichmentProvider != null) {
            try {
                AiSchemaEnrichment overlay = enrichmentProvider.apply(ref);
                enricher.apply(fresh, overlay);
            } catch (RuntimeException e) {
                // Enrichment failure must never break the schema endpoint.
                // The base schema is still valid and the agent can proceed.
                log.warn("Enrichment provider failed for {} — serving base schema", ref, e);
            }
        }
        schemaCache.put(key, fresh);
        // saiku#902 phase 3: pattern-scan the cube's measure + level names
        // for likely-PII surfaces and stash the findings on the cube
        // metadata service. Surfaced via getPiiSuggestions() →
        // /ai/schema/{cubeId}/pii-suggestions for admin tools; the scan
        // itself only logs WARN per finding so it shows up in the launcher
        // log too. Scanner runs ONCE per cache fill — same lifecycle as
        // the schema itself.
        try {
            piiSuggestionsCache.put(key, PiiScanner.scan(fresh));
        } catch (RuntimeException e) {
            // Scanner failure must never break the schema endpoint. Empty
            // suggestion list is the right fallback — the operator just
            // doesn't see automated hints; explicit annotations still work.
            log.warn("PII scanner failed for {} — no suggestions surfaced", ref, e);
            piiSuggestionsCache.put(key, java.util.Collections.emptyList());
        }
        return fresh;
    }

    /**
     * saiku#902 phase 3 admin surface. Returns the cached PiiScanner findings
     * for a cube — every measure / level name that matched a PII pattern AND
     * doesn't already have {@code saiku.semantic.pii=true} set. Empty list
     * when the cube hasn't been seen yet (i.e. no {@link #getSchema} call
     * resolved successfully) — call {@code getSchema} first if you need a
     * fresh scan.
     */
    public List<PiiScanner.Match> getPiiSuggestions(AiCubeRef ref) {
        if (ref == null) return java.util.Collections.emptyList();
        List<PiiScanner.Match> cached = piiSuggestionsCache.get(cacheKey(ref));
        return cached == null ? java.util.Collections.emptyList() : cached;
    }

    /**
     * Member search for a level. Resolves dimension/hierarchy/level by name
     * (canonical or display alias) against the cached schema, then delegates
     * to {@link OlapDiscoverService#getLevelMembers}. Returns up to {@code limit}
     * hits (default 20 when {@code limit <= 0}).
     */
    public List<SimpleCubeElement> searchMembers(
            AiCubeRef ref, String dimensionName, String hierarchyName, String levelName, String q, int limit) {
        if (limit <= 0) limit = 20;
        AiSchema schema = getSchema(ref);

        String dimK = AiSchema.key(dimensionName);
        AiSchema.Dimension dim = schema.dimensions.get(dimK);
        if (dim == null) {
            String aliasTarget = schema.dimensionAliases.get(dimK);
            if (aliasTarget != null) dim = schema.dimensions.get(aliasTarget);
        }
        if (dim == null) {
            throw new AiValidationException(
                    "dimension", "Unknown dimension '" + dimensionName + "'", canonicalDimNames(schema));
        }

        AiSchema.Hierarchy hier;
        if (hierarchyName == null || hierarchyName.isEmpty()) {
            if (dim.hierarchies.size() != 1) {
                throw new AiValidationException(
                        "hierarchy", "Dimension has multiple hierarchies; specify one", canonicalHierNames(dim));
            }
            hier = dim.hierarchies.values().iterator().next();
        } else {
            String hierK = AiSchema.key(hierarchyName);
            hier = dim.hierarchies.get(hierK);
            if (hier == null) {
                String aliasTarget = dim.hierarchyAliases.get(hierK);
                if (aliasTarget != null) hier = dim.hierarchies.get(aliasTarget);
            }
            if (hier == null) {
                throw new AiValidationException(
                        "hierarchy", "Unknown hierarchy '" + hierarchyName + "'", canonicalHierNames(dim));
            }
        }

        String lvlK = AiSchema.key(levelName);
        AiSchema.Level level = hier.levels.get(lvlK);
        if (level == null) {
            String aliasTarget = hier.levelAliases.get(lvlK);
            if (aliasTarget != null) level = hier.levels.get(aliasTarget);
        }
        if (level == null) {
            throw new AiValidationException("level", "Unknown level '" + levelName + "'", canonicalLevelNames(hier));
        }

        SaikuCube cube = findCube(ref);
        try {
            List<SimpleCubeElement> hits = discoverService.getLevelMembers(
                    cube, hier.name, level.name, q == null || q.isEmpty() ? null : q, limit);
            return hits == null ? Collections.emptyList() : hits;
        } catch (RuntimeException e) {
            log.warn("member search failed for {}/{}/{} q={}", dim.name, hier.name, level.name, q, e);
            return Collections.emptyList();
        }
    }

    /* ----------------------------- impl ------------------------------- */

    private String cacheKey(AiCubeRef ref) {
        return ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
    }

    /** How many sample members to inline per level. Five is enough for an
     *  LLM to ground its query; bigger numbers risk paying a per-level
     *  member-fetch cost for huge dimensions. Configurable for tests. */
    private int sampleMembersPerLevel = 5;

    public void setSampleMembersPerLevel(int n) {
        this.sampleMembersPerLevel = n;
    }

    private AiSchema buildSchema(AiCubeRef ref) {
        SaikuCube cube = findCube(ref);
        // saiku#811: build the canonical AiCubeRef from the matched cube's
        // actual fields, not from the agent-supplied ref. The cacheKey and
        // every downstream consumer (AiSchemaConverter.toSaikuCube,
        // serialised cubeId in the response) reads from this canonical
        // form, so a lowercase/mixed-case agent input doesn't produce
        // "Cannot get native cube" 500s further down the pipeline.
        AiCubeRef canonical = new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName());
        AiSchema schema = new AiSchema(cacheKey(canonical), cube.getName(), cube.getUniqueName());
        schema.canonicalCube = canonical;
        if (cube.getCaption() != null && !cube.getCaption().equals(cube.getName())) {
            schema.description = cube.getCaption();
        }

        try {
            for (SaikuMember m : discoverService.getMeasures(cube)) {
                String n = m.getCaption() != null && !m.getCaption().isEmpty() ? m.getCaption() : m.getName();
                AiSchema.Measure measure = new AiSchema.Measure(n, m.getUniqueName());
                if (m.getDescription() != null && !m.getDescription().isEmpty()) {
                    measure.description = m.getDescription();
                }
                // saiku#778: surface the schema-level visible flag so admin
                // tooling can see hidden helper measures while analyst clients
                // filter them out. Null → assume visible (legacy fixtures).
                if (m.isVisible() != null) {
                    measure.visible = m.isVisible();
                }
                // saiku#818: project saiku.semantic.* annotations onto the typed fields.
                // Annotation-supplied description wins over Member.getDescription().
                SemanticAnnotationParser.MeasureAnnotations ann =
                        SemanticAnnotationParser.parseMeasure(m.getAnnotations());
                if (ann.description != null) measure.description = ann.description;
                if (!ann.synonyms.isEmpty()) measure.synonyms = ann.synonyms;
                if (ann.unit != null) measure.unit = ann.unit;
                if (ann.currency != null) measure.currency = ann.currency;
                if (ann.aggregationKind != null) measure.aggregationKind = ann.aggregationKind;
                // saiku#902: pii flag is `true` only when explicitly set; absent /
                // typo'd values default to false (the conservative default —
                // operator must opt in to redaction).
                measure.pii = ann.pii;
                schema.measures.put(AiSchema.key(n), measure);
            }
        } catch (RuntimeException e) {
            log.warn("getMeasures failed for {}", cube.getUniqueName(), e);
        }

        // saiku#877: per-loop try/catch so one bad dim/hier/level doesn't
        // truncate the rest. Pre-fix, the single outer catch ended dim
        // iteration on the first throw inside any hierarchy/level walk —
        // e.g. mondrian-saiku#30's Calcite-H2 introspection blowup made
        // every dimension after the broken one silently vanish, leaving
        // dimensionAliases pointing at dimensions that were absent.
        List<SaikuDimension> dims;
        try {
            dims = discoverService.getAllDimensions(cube);
        } catch (RuntimeException e) {
            log.warn("getAllDimensions failed for {}", cube.getUniqueName(), e);
            dims = null;
        }
        if (dims != null) {
            for (SaikuDimension dim : dims) {
                // Skip the Measures dimension — already covered by measures map.
                if ("Measures".equalsIgnoreCase(dim.getName())) continue;
                try {
                    AiSchema.Dimension d = buildDimension(cube, dim);
                    schema.dimensions.put(AiSchema.key(dim.getName()), d);
                } catch (RuntimeException e) {
                    log.warn("Skipping dimension {} of {} — discover failed", dim.getName(), cube.getUniqueName(), e);
                }
            }
        }

        // saiku#818: register synonyms from XML annotations into the alias maps so
        // input resolution (AiSchemaConverter) sees them even when no overlay runs.
        // The enricher repeats this idempotently after overlay merge.
        AiSchemaEnricher.registerSynonymAliases(schema);

        // Embed a hand-rolled JSON Schema of AiQueryRequest so an LLM can
        // self-validate its request shape. We don't generate this at runtime
        // (would need a swagger/jackson schema generator dependency); it's
        // static — the AiQueryRequest fields are public API.
        schema.requestSchema = AiRequestJsonSchema.forRequest();

        // Auto-generate 2–3 example requests for the cube — a top-N pattern
        // and a breakdown pattern. The LLM can use these as templates.
        schema.examples.addAll(AiExampleBuilder.build(schema, ref));

        // saiku#810: optional probe step. Runs after the full schema is
        // built so the probe sees the same shape consumers do; pruning
        // happens in-place on the AiSchema. Off by default — see
        // probeUnqueryable field doc.
        if (probeUnqueryable) {
            pruneUnqueryable(cube, schema);
        }

        return schema;
    }

    /**
     * Build a single {@link AiSchema.Dimension} from its Saiku metadata,
     * with per-hierarchy and per-level try/catch. Each scope's failure
     * stays scoped: a broken level skips just that level, a broken
     * hierarchy skips just that hierarchy, and the caller wraps a broken
     * dimension. See saiku#877.
     */
    private AiSchema.Dimension buildDimension(SaikuCube cube, SaikuDimension dim) {
        AiSchema.Dimension d = new AiSchema.Dimension(dim.getName(), dim.getUniqueName());
        if (dim.getDescription() != null && !dim.getDescription().isEmpty()) {
            d.description = dim.getDescription();
        }
        // saiku#818 follow-up: project dimension-level annotations.
        try {
            SemanticAnnotationParser.DimensionAnnotations dann =
                    SemanticAnnotationParser.parseDimension(dim.getAnnotations());
            if (dann.description != null) d.description = dann.description;
            if (!dann.synonyms.isEmpty()) d.synonyms = dann.synonyms;
        } catch (RuntimeException e) {
            log.debug("dimension annotations unreadable for {}: {}", dim.getName(), e.getMessage());
        }
        List<SaikuHierarchy> hiers;
        try {
            hiers = dim.getHierarchies();
            if (hiers == null || hiers.isEmpty()) {
                hiers = discoverService.getAllDimensionHierarchies(cube, dim.getName());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Hierarchy fetch failed for {}.{} — keeping dim with empty hierarchies",
                    cube.getUniqueName(),
                    dim.getName(),
                    e);
            hiers = null;
        }
        if (hiers != null) {
            for (SaikuHierarchy h : hiers) {
                try {
                    AiSchema.Hierarchy hh = buildHierarchy(cube, dim, h);
                    d.hierarchies.put(AiSchema.key(h.getName()), hh);
                } catch (RuntimeException e) {
                    log.warn(
                            "Skipping hierarchy {}.{}.{} — discover failed",
                            cube.getUniqueName(),
                            dim.getName(),
                            h.getName(),
                            e);
                }
            }
        }
        return d;
    }

    private AiSchema.Hierarchy buildHierarchy(SaikuCube cube, SaikuDimension dim, SaikuHierarchy h) {
        AiSchema.Hierarchy hh = new AiSchema.Hierarchy(h.getName(), h.getUniqueName());
        if (h.getDescription() != null && !h.getDescription().isEmpty()) {
            hh.description = h.getDescription();
        }
        List<SaikuLevel> levels;
        try {
            levels = h.getLevels();
            if (levels == null || levels.isEmpty()) {
                levels = discoverService.getAllHierarchyLevels(cube, dim.getName(), h.getName());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Level fetch failed for {}.{}.{} — keeping hierarchy with empty levels",
                    cube.getUniqueName(),
                    dim.getName(),
                    h.getName(),
                    e);
            levels = null;
        }
        if (levels != null) {
            for (SaikuLevel lvl : levels) {
                try {
                    AiSchema.Level l = buildLevel(cube, h, lvl);
                    hh.levels.put(AiSchema.key(lvl.getName()), l);
                } catch (RuntimeException e) {
                    log.warn(
                            "Skipping level {}.{}.{}.{} — discover failed",
                            cube.getUniqueName(),
                            dim.getName(),
                            h.getName(),
                            lvl.getName(),
                            e);
                }
            }
        }
        return hh;
    }

    private AiSchema.Level buildLevel(SaikuCube cube, SaikuHierarchy h, SaikuLevel lvl) {
        AiSchema.Level l = new AiSchema.Level(lvl.getName(), lvl.getUniqueName());
        if (lvl.getDescription() != null && !lvl.getDescription().isEmpty()) {
            l.description = lvl.getDescription();
        }
        // saiku#818: project saiku.semantic.* annotations onto the typed fields.
        try {
            SemanticAnnotationParser.LevelAnnotations lann = SemanticAnnotationParser.parseLevel(lvl.getAnnotations());
            if (lann.description != null) l.description = lann.description;
            if (!lann.synonyms.isEmpty()) l.synonyms = lann.synonyms;
            if (lann.cardinality != null) l.cardinality = lann.cardinality;
            if (lann.grain != null) l.grain = lann.grain;
            if (!lann.requiredFilters.isEmpty()) l.requiredFilters = lann.requiredFilters;
            // saiku#902: pii flag is `true` only when explicitly set.
            l.pii = lann.pii;
        } catch (RuntimeException e) {
            log.debug("level annotations unreadable for {}/{}: {}", h.getName(), lvl.getName(), e.getMessage());
        }
        populateSampleMembers(l, cube, h.getName(), lvl.getName());
        return l;
    }

    /**
     * saiku#810: prune dim/hier/level triples that the AI converter's MDX
     * shape can't actually query, even though they're enumerable via cube
     * metadata. Originally aimed at Mondrian parent-child closure
     * synthetics ({@code Employee$Closure.[Employee$Manager Id$Parent]})
     * where {@code [Hier].[Level].Members} doesn't parse.
     *
     * <p>Two-pass design — gather probe results first, then decide:
     *
     * <ol>
     *   <li>For each non-(All) level under each dim/hier, run
     *       {@link OlapDiscoverService#getLevelMembers} with maxrows=1.
     *       Record which levels passed.</li>
     *   <li>Per-dimension rollback (saiku#878 follow-up — HR cube fix):
     *       if EVERY non-(All) level in a dimension failed the probe,
     *       treat it as probe-infrastructure failure rather than a real
     *       unqueryable dimension. Mondrian's {@code AddCalculatedMembers}
     *       expansion (used by the discover service) blows up on HR's
     *       Employee + Department hierarchies because of key
     *       configurations, but the dims are still filterable + queryable
     *       at the converter layer. Dropping them would hide whole-cube
     *       capability from agents. Keep them all.</li>
     *   <li>Otherwise: drop the failed levels, then empty hierarchies, then
     *       empty dimensions (the original cascade).</li>
     * </ol>
     *
     * <p>Pruning logs at INFO so a deployment can audit what was removed.
     */
    private void pruneUnqueryable(SaikuCube cube, AiSchema schema) {
        // Pass 1: probe every non-(All) level, record the failures keyed
        // by (dimName, hierName, levelName).
        java.util.Set<String> failed = new java.util.HashSet<>();
        java.util.Map<String, Integer> levelCountPerDim = new java.util.HashMap<>();
        java.util.Map<String, Integer> failedCountPerDim = new java.util.HashMap<>();
        for (AiSchema.Dimension dim : schema.dimensions.values()) {
            for (AiSchema.Hierarchy hier : dim.hierarchies.values()) {
                for (AiSchema.Level level : hier.levels.values()) {
                    if (level.name == null || level.name.equalsIgnoreCase("(All)")) continue;
                    levelCountPerDim.merge(dim.name, 1, Integer::sum);
                    if (level.queryableProven) continue;
                    if (!isLevelQueryable(cube, hier.name, level.name)) {
                        failed.add(probeKey(dim.name, hier.name, level.name));
                        failedCountPerDim.merge(dim.name, 1, Integer::sum);
                    }
                }
            }
        }

        // Pass 2: per-dimension rollback. The probe can produce false
        // positives when the probe infrastructure itself is wonky (e.g.
        // Mondrian's AddCalculatedMembers expansion on parent-child
        // hierarchies — saiku#878). The pattern we want to recognise:
        // MULTIPLE dimensions show 100% failure simultaneously. That's
        // the "infra is broken globally" signal — roll those back to
        // keep the schema usable while the operator investigates.
        //
        // If only ONE dimension has 100% failure but other dims succeed,
        // that dimension is genuinely unqueryable (the saiku#810 case)
        // and should be pruned. Protecting it here would shadow real
        // bad-data problems behind a probe-infra excuse.
        java.util.Set<String> fullyFailedDims = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, Integer> e : levelCountPerDim.entrySet()) {
            int total = e.getValue();
            int fails = failedCountPerDim.getOrDefault(e.getKey(), 0);
            if (total > 0 && fails == total) {
                fullyFailedDims.add(e.getKey());
            }
        }
        java.util.Set<String> protectedDims = new java.util.HashSet<>();
        if (fullyFailedDims.size() >= 2) {
            // ≥2 dims with 100% failure → looks global → protect them all.
            protectedDims.addAll(fullyFailedDims);
            for (String d : fullyFailedDims) {
                log.info(
                        "Probe rolled back for dimension {} on {} schema —"
                                + " {} dimensions show 100% level-enumeration failure; treating as probe-infra"
                                + " signal (not real unqueryable). saiku#878.",
                        d, schema.getCubeName(), fullyFailedDims.size());
            }
        } else if (fullyFailedDims.size() == 1) {
            String d = fullyFailedDims.iterator().next();
            log.info(
                    "Dimension {} on {} schema shows 100% level-enumeration failure"
                            + " while other dimensions succeed — treating as genuinely unqueryable"
                            + " and pruning (saiku#810).",
                    d, schema.getCubeName());
        }

        // Pass 3: apply the cascade for the levels NOT protected by pass 2.
        int prunedLevels = 0;
        int prunedHiers = 0;
        int prunedDims = 0;
        java.util.Iterator<java.util.Map.Entry<String, AiSchema.Dimension>> dimIt =
                schema.dimensions.entrySet().iterator();
        while (dimIt.hasNext()) {
            java.util.Map.Entry<String, AiSchema.Dimension> de = dimIt.next();
            AiSchema.Dimension dim = de.getValue();
            if (protectedDims.contains(dim.name)) continue;
            java.util.Iterator<java.util.Map.Entry<String, AiSchema.Hierarchy>> hierIt =
                    dim.hierarchies.entrySet().iterator();
            while (hierIt.hasNext()) {
                java.util.Map.Entry<String, AiSchema.Hierarchy> he = hierIt.next();
                AiSchema.Hierarchy hier = he.getValue();
                java.util.Iterator<java.util.Map.Entry<String, AiSchema.Level>> levelIt =
                        hier.levels.entrySet().iterator();
                while (levelIt.hasNext()) {
                    java.util.Map.Entry<String, AiSchema.Level> le = levelIt.next();
                    AiSchema.Level level = le.getValue();
                    if (level.name == null || level.name.equalsIgnoreCase("(All)")) continue;
                    if (level.queryableProven) continue;
                    if (failed.contains(probeKey(dim.name, hier.name, level.name))) {
                        log.info(
                                "Pruning unqueryable level {}/{}/{} from {} schema (saiku#810)",
                                dim.name,
                                hier.name,
                                level.name,
                                schema.getCubeName());
                        levelIt.remove();
                        prunedLevels++;
                    }
                }
                boolean hierHasQueryableLevel = false;
                for (AiSchema.Level l : hier.levels.values()) {
                    if (l.name == null || !l.name.equalsIgnoreCase("(All)")) {
                        hierHasQueryableLevel = true;
                        break;
                    }
                }
                if (!hierHasQueryableLevel) {
                    log.info(
                            "Pruning unqueryable hierarchy {}/{} from {} schema (saiku#810)",
                            dim.name,
                            hier.name,
                            schema.getCubeName());
                    hierIt.remove();
                    prunedHiers++;
                }
            }
            if (dim.hierarchies.isEmpty()) {
                log.info("Pruning empty dimension {} from {} schema (saiku#810)", dim.name, schema.getCubeName());
                dimIt.remove();
                prunedDims++;
            }
        }
        if (prunedLevels + prunedHiers + prunedDims > 0) {
            log.info(
                    "Schema probe pruned {} levels, {} hiers, {} dims from {} (saiku#810)",
                    prunedLevels,
                    prunedHiers,
                    prunedDims,
                    schema.getCubeName());
        }
    }

    private static String probeKey(String dim, String hier, String level) {
        return dim + "\u0000" + hier + "\u0000" + level;
    }

    /** Probe one (hier, level) pair via the same path member-search uses.
     *  Returns true if the discover service can enumerate at least one
     *  member of the level without throwing — i.e. the level is reachable
     *  from MDX. Any exception → unqueryable. */
    private boolean isLevelQueryable(SaikuCube cube, String hierName, String levelName) {
        try {
            discoverService.getLevelMembers(cube, hierName, levelName, null, 1);
            return true;
        } catch (RuntimeException e) {
            log.debug("Probe failed for {}/{}: {}", hierName, levelName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("Probe failed for {}/{}: {}", hierName, levelName, e.getMessage());
            return false;
        }
    }

    private void populateSampleMembers(AiSchema.Level out, SaikuCube cube, String hierarchyName, String levelName) {
        if (sampleMembersPerLevel <= 0) return;
        try {
            // Over-fetch so post-dedupe we still get a useful sample —
            // levels like Quarter repeat captions ("Q1") across years and
            // would otherwise collapse to fewer distinct entries than asked
            // for. The discover service has its own internal cap so 4x is
            // a safe ceiling.
            int overfetch = Math.max(sampleMembersPerLevel * 4, sampleMembersPerLevel);
            List<org.saiku.olap.dto.SimpleCubeElement> members =
                    discoverService.getLevelMembers(cube, hierarchyName, levelName, overfetch);
            if (members == null) return;
            // Sample fetch returning ≥1 row is proof the level is queryable —
            // pruneUnqueryable can skip its redundant probe call (saiku#780 perf).
            if (!members.isEmpty()) {
                out.queryableProven = true;
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (org.saiku.olap.dto.SimpleCubeElement m : members) {
                if (out.sampleMembers.size() >= sampleMembersPerLevel) break;
                String caption = m.getCaption() != null && !m.getCaption().isEmpty() ? m.getCaption() : m.getName();
                if (caption == null || caption.isEmpty()) continue;
                if (!seen.add(caption)) continue; // skip caption duplicates (rolled-up levels)
                out.sampleMembers.add(new AiSchema.MemberSample(caption, m.getUniqueName()));
            }
        } catch (RuntimeException e) {
            // sample-member fetch failure must never break the schema response.
            log.debug("sample-member fetch failed for {}/{}: {}", hierarchyName, levelName, e.getMessage());
        }
    }

    private SaikuCube findCube(AiCubeRef ref) {
        try {
            List<SaikuCube> all = discoverService.getAllCubes();
            List<String> available = new ArrayList<>();
            for (SaikuCube c : all) {
                available.add(c.getName());
                if (matchesRef(c, ref)) return c;
            }
            throw new AiValidationException("cube", "Unknown cube '" + ref + "'", available);
        } catch (AiValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve cube " + ref, e);
        }
    }

    private static boolean matchesRef(SaikuCube cube, AiCubeRef ref) {
        if (!equalsCi(cube.getName(), ref.getCubeName())) return false;
        if (ref.getConnectionName() != null && !equalsCi(cube.getConnection(), ref.getConnectionName())) {
            return false;
        }
        if (ref.getCatalog() != null && !equalsCi(cube.getCatalog(), ref.getCatalog())) return false;
        if (ref.getSchema() != null && !equalsCi(cube.getSchema(), ref.getSchema())) return false;
        return true;
    }

    private static boolean equalsCi(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.equalsIgnoreCase(b);
    }

    /** Canonical (proper-case) names for an `available` validation list.
     *  The schema's hierarchies/levels/dimensions are keyed by lower-cased
     *  identifier, but the error message should echo the names an agent
     *  recognises — matches what {@code AiSchemaConverter} emits on the
     *  query-axis 400 paths. */
    private static List<String> canonicalDimNames(AiSchema schema) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Dimension d : schema.dimensions.values()) out.add(d.name);
        return out;
    }

    private static List<String> canonicalHierNames(AiSchema.Dimension dim) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Hierarchy h : dim.hierarchies.values()) out.add(h.name);
        return out;
    }

    private static List<String> canonicalLevelNames(AiSchema.Hierarchy hier) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Level l : hier.levels.values()) out.add(l.name);
        return out;
    }
}
