/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.Optional;
import java.util.function.Function;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3 production enrichment provider. Wraps the existing
 * {@link SchemaGenOrchestrator.SidecarStore} (typically the
 * {@code RepositorySidecarStore}) and translates per-cube draft state
 * into an {@link AiSchemaEnrichment} overlay.
 *
 * <p>Lookup contract:
 * <ol>
 *   <li>Find the sidecar for the data source ({@code AiCubeRef.connectionName}).
 *   <li>Within the sidecar's {@link DraftSchema}, find the {@link DraftCube}
 *       whose {@code name()} matches the requested cube name (case-insensitive).
 *   <li>For each draft dimension/hierarchy/level/measure whose name maps to
 *       a live olap4j element of the same name, emit no rename (canonical
 *       and display already converge — no-op).
 *   <li>Walk the sidecar's op-log for unapplied {@link RenameOp}s and
 *       surface them as {@link AiSchemaSuggestion} entries the agent can
 *       consider. Applied renames in the draft's {@code name()} fields
 *       become the live display labels.
 * </ol>
 *
 * <p>Returns an empty {@link AiSchemaEnrichment} (not null) when no
 * sidecar exists — so the schema endpoint still works, just without
 * enrichment.
 */
public class DraftSidecarEnrichmentProvider implements Function<AiCubeRef, AiSchemaEnrichment> {

    private static final Logger log = LoggerFactory.getLogger(DraftSidecarEnrichmentProvider.class);

    private final SchemaGenOrchestrator.SidecarStore sidecarStore;

    public DraftSidecarEnrichmentProvider(SchemaGenOrchestrator.SidecarStore sidecarStore) {
        this.sidecarStore = sidecarStore;
    }

    @Override
    public AiSchemaEnrichment apply(AiCubeRef ref) {
        AiSchemaEnrichment empty = new AiSchemaEnrichment();
        if (ref == null || ref.getConnectionName() == null) return empty;
        Optional<GeneratedSidecar> opt;
        try {
            opt = sidecarStore.load(ref.getConnectionName());
        } catch (RuntimeException e) {
            log.debug("sidecar load failed for {} — serving base schema", ref.getConnectionName(), e);
            return empty;
        }
        if (opt.isEmpty()) return empty;
        GeneratedSidecar sidecar = opt.get();

        DraftCube cube = findCube(sidecar.draft(), ref.getCubeName());
        if (cube == null) return empty;

        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        // Display labels — only meaningful when the draft has been renamed AND
        // we can map the rename back to the live element. Without
        // provenance fields on the draft tree we play it safe: only emit a
        // rename when the source-table fallback name matches a live name
        // (i.e. when the draft name == the live element name post-applied
        // rename, the displayName collapses to the canonical name; that's a
        // no-op so we skip emitting it).
        //
        // The real payoff today is the suggestions list — un-applied
        // RenameOps from the LLM enrichment phase that the agent should
        // consider but the operator hasn't accepted yet.
        for (SuggestionOp op : sidecar.opLog()) {
            if (op instanceof RenameOp rename) {
                AiSchemaSuggestion s = new AiSchemaSuggestion(
                        "rename",
                        rename.targetPath(),
                        rename.confidence(),
                        rename.rationale(),
                        rename.newCaption());
                overlay.getSuggestions().add(s);
            }
        }

        // For each draft element where name() differs from its source-table /
        // column "stable id", treat the draft name() as the user-facing
        // display label. We key the rename path by the draft name() (matches
        // the live cube name post-XML-rewrite, or pre-rewrite when the
        // operator hasn't renamed anything).
        for (DraftDimension dim : cube.dimensions()) {
            if (looksRenamed(dim)) {
                overlay.getRenames().put(
                        "dimensions." + dim.name(),
                        dim.name());
            }
            for (DraftHierarchy h : dim.hierarchies()) {
                for (DraftLevel l : h.levels()) {
                    if (looksRenamed(l)) {
                        overlay.getRenames().put(
                                "dimensions." + dim.name() + ".hierarchies." + h.name() + ".levels." + l.name(),
                                l.name());
                    }
                }
            }
        }
        for (DraftMeasure m : cube.measures()) {
            if (looksRenamed(m)) {
                overlay.getRenames().put("measures." + m.name(), m.name());
            }
        }
        return overlay;
    }

    private static DraftCube findCube(DraftSchema draft, String cubeName) {
        if (draft == null || cubeName == null) return null;
        for (DraftCube c : draft.cubes()) {
            if (cubeName.equalsIgnoreCase(c.name())) return c;
        }
        return null;
    }

    /** Heuristic: an element name "looks renamed" when it differs from its
     *  underlying physical identifier. This is the closest we can get to
     *  detecting renames without provenance fields on the draft. */
    private static boolean looksRenamed(DraftDimension d) {
        return d.sourceTable() != null && !d.sourceTable().equals(d.name());
    }

    private static boolean looksRenamed(DraftLevel l) {
        return l.column() != null && !l.column().equals(l.name());
    }

    private static boolean looksRenamed(DraftMeasure m) {
        return m.column() != null && !m.column().equals(m.name());
    }
}
