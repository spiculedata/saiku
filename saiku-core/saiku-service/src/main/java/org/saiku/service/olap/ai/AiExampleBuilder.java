/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates 2-3 ready-made {@link AiQueryRequest} examples for a cube
 * so an LLM can read a working shape before constructing its own. We
 * pick the first available measure and dimension/hierarchy/level rather
 * than trying to be clever about semantics — the goal is a syntactically
 * valid template, not a great query.
 */
public final class AiExampleBuilder {

    private AiExampleBuilder() {}

    public static List<AiQueryRequest> build(AiSchema schema, AiCubeRef cubeRef) {
        if (schema == null || cubeRef == null) return Collections.emptyList();
        if (schema.measures.isEmpty()) return Collections.emptyList();

        // Pick a measure and a dim/hier/level we can hang an example on.
        AiSchema.Measure firstMeasure = schema.measures.values().iterator().next();
        AiSchema.Dimension dim = firstDimensionWithLevel(schema);
        if (dim == null) {
            // Cube has no real dimensions — return only a flat measure-only example.
            AiQueryRequest measureOnly = base(cubeRef, firstMeasure.name);
            return List.of(measureOnly);
        }
        AiSchema.Hierarchy hier = dim.hierarchies.values().iterator().next();
        AiSchema.Level level = hier.levels.values().iterator().next();

        List<AiQueryRequest> examples = new ArrayList<>(3);

        // Example 1: simple breakdown of measure by level.
        AiQueryRequest breakdown = base(cubeRef, firstMeasure.name);
        breakdown.getRows().add(new AiAxisSelection(dim.name, hier.name, level.name));
        examples.add(breakdown);

        // Example 2: top-N by measure — order + limit emits TopCount.
        AiQueryRequest topN = base(cubeRef, firstMeasure.name);
        topN.getRows().add(new AiAxisSelection(dim.name, hier.name, level.name));
        AiOrderBy order = new AiOrderBy();
        order.setBy(firstMeasure.name);
        order.setDirection("desc");
        topN.getOrder().add(order);
        topN.setLimit(10);
        examples.add(topN);

        // Example 3: with VISUALTOTALS — parent totals reflect only visible members.
        AiQueryRequest withTotals = base(cubeRef, firstMeasure.name);
        withTotals.getRows().add(new AiAxisSelection(dim.name, hier.name, level.name));
        withTotals.setVisualTotals(true);
        examples.add(withTotals);

        return examples;
    }

    private static AiQueryRequest base(AiCubeRef cubeRef, String measureName) {
        AiQueryRequest req = new AiQueryRequest();
        AiCubeRef r = new AiCubeRef(
                cubeRef.getConnectionName(), cubeRef.getCatalog(),
                cubeRef.getSchema(), cubeRef.getCubeName());
        req.setCube(r);
        req.getMeasures().add(new AiMeasureSelection(measureName));
        return req;
    }

    private static AiSchema.Dimension firstDimensionWithLevel(AiSchema schema) {
        for (AiSchema.Dimension d : schema.dimensions.values()) {
            for (AiSchema.Hierarchy h : d.hierarchies.values()) {
                if (!h.levels.isEmpty()) return d;
            }
        }
        return null;
    }
}
