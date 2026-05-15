/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.writer.GeneratedSidecar;

/**
 * Tests for the {@link DraftSidecarEnrichmentProvider} adapter that
 * translates a schema-generator sidecar into an {@link AiSchemaEnrichment}.
 */
public class DraftSidecarEnrichmentProviderTest {

    private DraftSidecarEnrichmentProvider provider;
    private final List<GeneratedSidecar> registered = new ArrayList<>();

    @Before
    public void setUp() {
        // A stub SidecarStore that returns whatever we register.
        SchemaGenOrchestrator.SidecarStore store = new SchemaGenOrchestrator.SidecarStore() {
            @Override
            public Optional<GeneratedSidecar> load(String dataSourceId) {
                for (GeneratedSidecar s : registered) {
                    if (dataSourceId.equals(s.schemaName())) return Optional.of(s);
                }
                return Optional.empty();
            }
        };
        provider = new DraftSidecarEnrichmentProvider(store);
    }

    @Test
    public void noSidecarReturnsEmptyEnrichment() {
        AiSchemaEnrichment overlay = provider.apply(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertNotNull(overlay);
        assertTrue(overlay.getRenames().isEmpty());
        assertTrue(overlay.getSuggestions().isEmpty());
    }

    @Test
    public void nullCubeRefIsSafe() {
        AiSchemaEnrichment overlay = provider.apply(null);
        assertNotNull(overlay);
    }

    @Test
    public void unappliedRenameOpsBecomeSuggestions() {
        DraftSchema draft = buildSimpleDraft();
        RenameOp rename = new RenameOp(
                "cubes/sales/dimensions/time",
                "Time",
                "Period",
                "match analyst vocabulary",
                0.87,
                "users prefer Period over Time");
        registered.add(new GeneratedSidecar(
                "foodmart", Instant.now(), "test", draft, List.of(rename)));

        AiSchemaEnrichment overlay = provider.apply(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals(1, overlay.getSuggestions().size());
        AiSchemaSuggestion s = overlay.getSuggestions().get(0);
        assertEquals("rename", s.getOp());
        assertEquals("Period", s.getSuggestedValue());
        assertEquals(0.87, s.getConfidence(), 0.001);
    }

    @Test
    public void renamedDraftElementBecomesDisplayName() {
        // A draft where the dimension was renamed from physical "time_by_day" to "Period".
        DraftCube cube = new DraftCube(
                "Sales", "sales_fact",
                new Provenance(Provenance.Source.RULE, "rule", 1.0));

        DraftDimension dim = new DraftDimension(
                "Period", DraftDimension.Type.STANDARD,
                new Provenance(Provenance.Source.LLM, "rule", 0.9));
        dim.setSourceTable("time_by_day");  // physical != name → looks renamed
        cube.dimensions().add(dim);

        DraftSchema draft = new DraftSchema("foodmart");
        draft.cubes().add(cube);

        registered.add(new GeneratedSidecar(
                "foodmart", Instant.now(), "test", draft, List.of()));

        AiSchemaEnrichment overlay = provider.apply(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals("Period", overlay.getRenames().get("dimensions.Period"));
    }

    @Test
    public void unrenamedElementsDoNotEmitRenameEntries() {
        DraftCube cube = new DraftCube(
                "Sales", "sales_fact",
                new Provenance(Provenance.Source.RULE, "rule", 1.0));
        DraftDimension dim = new DraftDimension(
                "Time", DraftDimension.Type.STANDARD,
                new Provenance(Provenance.Source.RULE, "rule", 1.0));
        dim.setSourceTable("Time");  // physical == name → not renamed
        cube.dimensions().add(dim);

        DraftSchema draft = new DraftSchema("foodmart");
        draft.cubes().add(cube);

        registered.add(new GeneratedSidecar(
                "foodmart", Instant.now(), "test", draft, List.of()));

        AiSchemaEnrichment overlay = provider.apply(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertFalse(overlay.getRenames().containsKey("dimensions.Time"));
    }

    @Test
    public void unknownCubeWithinSidecarReturnsEmpty() {
        DraftSchema draft = buildSimpleDraft();
        registered.add(new GeneratedSidecar(
                "foodmart", Instant.now(), "test", draft, List.of()));

        AiSchemaEnrichment overlay = provider.apply(
                new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Nonsense"));
        assertTrue(overlay.getRenames().isEmpty());
        assertTrue(overlay.getSuggestions().isEmpty());
    }

    private static DraftSchema buildSimpleDraft() {
        DraftCube cube = new DraftCube(
                "Sales", "sales_fact",
                new Provenance(Provenance.Source.RULE, "rule", 1.0));
        DraftSchema draft = new DraftSchema("foodmart");
        draft.cubes().add(cube);
        return draft;
    }
}
