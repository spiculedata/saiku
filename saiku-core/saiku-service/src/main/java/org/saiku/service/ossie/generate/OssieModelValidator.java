/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural gate run between "we produced an {@link OssieDocument}" and "we wrote it to the
 * repository" (saiku#1439 Phase 3).
 *
 * <p>Two layers, cheapest first:
 *
 * <ol>
 *   <li><b>Referential integrity.</b> Every relationship must name two datasets that exist in the
 *       same semantic model, with a non-empty column list on each side. This is the class of defect
 *       the converter can actually produce — a M4 dimension with no resolvable primary key yields
 *       a relationship-less cube, and a compound-key attribute whose leaf column can't be resolved
 *       yields a field with no expression.
 *   <li><b>Ossie's own structural minimums.</b> Each semantic model needs at least one dataset
 *       (Ossie's core schema requires it), every dataset needs a {@code source}, and every field
 *       and metric needs an expression — the two invariants whose absence makes a document
 *       round-trip but fail at query time with an opaque error.
 * </ol>
 *
 * <p>Deliberately not a JSON-Schema check. The shipped {@code osi-schema.json} lives in the
 * <em>test</em> classpath only (copied from apache/ossie), and the parse-back check lives in
 * {@code OssieYamlWriterTest}; duplicating both here would give a second, drifting copy of the
 * spec. What this class adds is the layer a JSON Schema can't express — cross-reference integrity
 * within a model.
 *
 * <p>Stateless. Every problem found is reported, not just the first, so one regeneration surfaces
 * every broken join at once.
 */
public final class OssieModelValidator {

    private OssieModelValidator() {}

    /**
     * Validate a generated document.
     *
     * @return the problems found, in document order; empty when the document is sound
     */
    public static List<String> validate(OssieDocument doc) {
        List<String> problems = new ArrayList<>();
        if (doc == null) {
            problems.add("document is null");
            return problems;
        }
        // Top-level list, not getEffectiveSemanticModels(): this validates what the converter
        // emitted, and the converter never populates ontology_mappings. Pulling the effective list
        // in would make ontology-nested models — which have no dataset-name namespace of their own
        // here — report phantom cross-model relationship errors.
        List<SemanticModel> models = doc.getSemanticModel();
        if (models.isEmpty()) {
            problems.add("document has zero semantic models — every cube the converter saw was "
                    + "unrecognised, so the warehouse shape is not one this converter maps");
            return problems;
        }
        for (SemanticModel sm : models) {
            validateModel(sm, problems);
        }
        return problems;
    }

    private static void validateModel(SemanticModel sm, List<String> problems) {
        String where = "semantic model '" + name(sm.getName()) + "'";
        if (sm.getDatasets().isEmpty()) {
            problems.add(where + " has no datasets — Ossie requires at least one");
            return;
        }

        Set<String> datasetNames = new HashSet<>();
        for (Dataset ds : sm.getDatasets()) {
            if (blank(ds.getName())) {
                problems.add(where + " has a dataset with no name");
                continue;
            }
            if (!datasetNames.add(ds.getName())) {
                problems.add(where + " has two datasets named '" + ds.getName()
                        + "' — Ossie resolves relationships by name, so the second is unreachable");
            }
            if (blank(ds.getSource())) {
                problems.add(where + " / dataset '" + ds.getName() + "' has no source table");
            }
            for (var field : ds.getFields()) {
                if (blank(field.getName())) {
                    problems.add(where + " / dataset '" + ds.getName() + "' has an unnamed field");
                } else if (field.getExpression() == null
                        || field.getExpression().getDialects().isEmpty()) {
                    problems.add(where + " / dataset '" + ds.getName() + "' / field '" + field.getName()
                            + "' has no expression — Ossie's schema rejects a field without one");
                }
            }
        }

        for (Relationship rel : sm.getRelationships()) {
            String relWhere = where + " / relationship '" + name(rel.getName()) + "'";
            if (blank(rel.getFrom()) || !datasetNames.contains(rel.getFrom())) {
                problems.add(relWhere + " has from='" + rel.getFrom()
                        + "' which is not a dataset in this model — available: " + datasetNames);
            }
            if (blank(rel.getTo()) || !datasetNames.contains(rel.getTo())) {
                problems.add(relWhere + " has to='" + rel.getTo()
                        + "' which is not a dataset in this model — available: " + datasetNames);
            }
            if (rel.getFromColumns().isEmpty()) {
                problems.add(relWhere + " has no from-columns");
            }
            if (rel.getToColumns().isEmpty()) {
                problems.add(relWhere + " has no to-columns");
            }
        }

        for (var metric : sm.getMetrics()) {
            if (blank(metric.getName())) {
                problems.add(where + " has an unnamed metric");
            } else if (metric.getExpression() == null
                    || metric.getExpression().getDialects().isEmpty()) {
                problems.add(where + " / metric '" + metric.getName()
                        + "' has no expression — Ossie's schema rejects a metric without one");
            }
        }
    }

    private static String name(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
