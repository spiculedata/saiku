/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
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
    private java.util.function.Function<AiCubeRef, AiSchemaEnrichment> enrichmentProvider;
    private final AiSchemaEnricher enricher = new AiSchemaEnricher();

    public void setDiscoverService(OlapDiscoverService s) { this.discoverService = s; }

    /**
     * Phase 3: install a provider that produces an enrichment overlay
     * for a given cube. Called once per cache miss. Pass {@code null}
     * to disable enrichment (the default).
     */
    public void setEnrichmentProvider(java.util.function.Function<AiCubeRef, AiSchemaEnrichment> p) {
        this.enrichmentProvider = p;
    }

    /** Bump when downstream schema state changes (e.g. cube reload, draft enrichment update). */
    public void invalidateCache() { schemaCache.clear(); }

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
        return fresh;
    }

    /* ----------------------------- impl ------------------------------- */

    private String cacheKey(AiCubeRef ref) {
        return ref.getConnectionName() + "/" + ref.getCatalog() + "/" + ref.getSchema() + "/" + ref.getCubeName();
    }

    private AiSchema buildSchema(AiCubeRef ref) {
        SaikuCube cube = findCube(ref);
        AiSchema schema = new AiSchema(cacheKey(ref), cube.getName(), cube.getUniqueName());

        try {
            for (SaikuMember m : discoverService.getMeasures(cube)) {
                String n = m.getCaption() != null && !m.getCaption().isEmpty() ? m.getCaption() : m.getName();
                schema.measures.put(AiSchema.key(n), new AiSchema.Measure(n, m.getUniqueName()));
            }
        } catch (RuntimeException e) {
            log.warn("getMeasures failed for {}", cube.getUniqueName(), e);
        }

        try {
            for (SaikuDimension dim : discoverService.getAllDimensions(cube)) {
                // Skip the Measures dimension — already covered by measures map.
                if ("Measures".equalsIgnoreCase(dim.getName())) continue;
                AiSchema.Dimension d = new AiSchema.Dimension(dim.getName(), dim.getUniqueName());
                List<SaikuHierarchy> hiers = dim.getHierarchies();
                if (hiers == null || hiers.isEmpty()) {
                    hiers = discoverService.getAllDimensionHierarchies(cube, dim.getName());
                }
                if (hiers != null) {
                    for (SaikuHierarchy h : hiers) {
                        AiSchema.Hierarchy hh = new AiSchema.Hierarchy(h.getName(), h.getUniqueName());
                        List<SaikuLevel> levels = h.getLevels();
                        if (levels == null || levels.isEmpty()) {
                            levels = discoverService.getAllHierarchyLevels(cube, dim.getName(), h.getName());
                        }
                        if (levels != null) {
                            for (SaikuLevel lvl : levels) {
                                hh.levels.put(AiSchema.key(lvl.getName()),
                                        new AiSchema.Level(lvl.getName(), lvl.getUniqueName()));
                            }
                        }
                        d.hierarchies.put(AiSchema.key(h.getName()), hh);
                    }
                }
                schema.dimensions.put(AiSchema.key(dim.getName()), d);
            }
        } catch (RuntimeException e) {
            log.warn("getAllDimensions failed for {}", cube.getUniqueName(), e);
        }
        return schema;
    }

    private SaikuCube findCube(AiCubeRef ref) {
        try {
            List<SaikuCube> all = discoverService.getAllCubes();
            List<String> available = new ArrayList<>();
            for (SaikuCube c : all) {
                available.add(c.getName());
                if (matchesRef(c, ref)) return c;
            }
            throw new AiValidationException("cube",
                    "Unknown cube '" + ref + "'", available);
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
}
