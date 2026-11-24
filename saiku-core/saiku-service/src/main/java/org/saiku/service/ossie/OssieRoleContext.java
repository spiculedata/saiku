/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.saiku.olap.query2.OssieQueryModel;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.exception.SaikuValidationException;

/**
 * Ossie role-based security (saiku#1393) — Mondrian-role parity for the semantic-YAML query path.
 *
 * <p>Two responsibilities, both driven by the {@code saiku.roles} well-known custom_extensions
 * blob (see {@link SaikuWellKnownExtensions.Roles}) and a caller's resolved Spring Security
 * {@code GrantedAuthority} set:
 *
 * <ul>
 *   <li><b>Row-level security</b> — {@link #rowPredicatesFor(String)} returns the ANSI SQL WHERE
 *       conjunctions a dataset's role-scoped predicates contribute for the caller, for the
 *       translator to inject.
 *   <li><b>Visibility (HIDE) enforcement</b> — {@link #assertShelfVisible(OssieQueryModel)}
 *       resolves every row/filter/sort/value identifier against the model and rejects a shelf
 *       state that references a field or metric every one of the caller's roles denies, or an
 *       identifier that resolves to nothing (saiku#2526 — a name that resolved to nothing used
 *       to be emitted as a raw physical column, bypassing the whole mechanism).
 * </ul>
 *
 * <p>{@link #filterHidden(OssieModelDto, Set)} is the discover-surface counterpart: it returns a
 * copy of a semantic model with role-denied fields/metrics removed, for the workbench schema
 * browser and the AI schema endpoint so a caller never sees a name it isn't allowed to query.
 *
 * <p>A caller holding none of the roles a dataset/field/metric names is treated as unrestricted —
 * row-level security here is opt-in per dataset (matching the allow/deny fail-open-when-unset
 * posture already established for {@code saiku.roles}), not a blanket "roles required" gate. This
 * mirrors the design sketch in saiku#1393: each role opens an access window, and a role-less
 * caller falls through unimpeded unless a HIDE rule names them out.
 */
public final class OssieRoleContext {

    private final Set<String> callerRoles;
    private final OssieModelDto semantic;

    private OssieRoleContext(Set<String> callerRoles, OssieModelDto semantic) {
        this.callerRoles = callerRoles;
        this.semantic = semantic;
    }

    /** Resolve a context for one {@code (callerRoles, semantic)} pair. {@code callerRoles} may be null (treated as empty). */
    public static OssieRoleContext resolve(Set<String> callerRoles, OssieModelDto semantic) {
        return new OssieRoleContext(callerRoles == null ? Set.of() : callerRoles, semantic);
    }

    /**
     * Row-predicate SQL expressions contributed by every role the caller holds that restricts
     * {@code datasetName}. Empty when the dataset declares no predicates, or none of the caller's
     * roles match one — either way nothing is injected. The translator ORs multiple entries
     * together and ANDs the result with its own WHERE clauses.
     */
    public List<String> rowPredicatesFor(String datasetName) {
        OssieModelDto.Dataset ds = findDataset(datasetName);
        if (ds == null) return List.of();
        List<String> matched = new ArrayList<>();
        for (OssieModelDto.RowPredicate rp : ds.getRowPredicates()) {
            if (callerRoles.contains(rp.getRole())) {
                matched.add(rp.getExpression());
            }
        }
        return matched;
    }

    /**
     * The caller's row predicates for {@code datasetName} folded into one SQL boolean expression:
     * a single predicate as-is, several ORed inside parentheses (each role opens its own access
     * window, same as the translator). {@code null} when nothing restricts the caller. Used by the
     * value-enumeration surfaces (values-search, schema samples) that issue their own SQL
     * (saiku#2324).
     */
    public String combinedRowPredicateFor(String datasetName) {
        List<String> predicates = rowPredicatesFor(datasetName);
        if (predicates.isEmpty()) return null;
        return predicates.size() == 1 ? predicates.get(0) : "(" + String.join(" OR ", predicates) + ")";
    }

    /**
     * Throws {@link SaikuAccessDeniedException} if the shelf state references a field or metric
     * that exists in the semantic model and denies the caller — and {@link
     * SaikuValidationException} if an identifier does not resolve to any field or metric of the
     * model at all (saiku#2526).
     *
     * <p>The second half is the physical-column-bypass fix: the translator's historical fallback
     * emitted any name it could not resolve as a quoted physical column, so a caller denied the
     * semantic field {@code c_email} could name its physical column {@code C_EMAIL_ADDRESS} and
     * get the values. Every row, filter (with or without {@code dataset}) and sort (by field or
     * by {@code metric}) identifier is resolved here, before any SQL is emitted.
     */
    public void assertShelfVisible(OssieQueryModel model) {
        String factDataset = model.getFactDataset();
        if (factDataset == null || factDataset.isBlank() || findDataset(factDataset) == null) {
            throw new SaikuValidationException(
                    "Unknown fact dataset '" + factDataset + "' in model '" + modelName() + "'");
        }
        for (OssieQueryModel.FieldRef f : model.getRows()) {
            assertFieldRef(f.getDataset(), f.getField());
        }
        for (OssieQueryModel.FieldRef f : model.getColumns()) {
            assertFieldRef(f.getDataset(), f.getField());
        }
        for (OssieQueryModel.FilterExpr f : model.getFilters()) {
            if (f.getDataset() != null) {
                assertFieldRef(f.getDataset(), f.getField());
            } else {
                resolveUnqualifiedField(f.getField());
            }
        }
        for (OssieQueryModel.SortRef s : model.getSorts()) {
            // saiku#2526 — the translator emits the METRIC branch whenever a metric is named,
            // dataset aside, so the metric is the identifier that must resolve. Previously a
            // sort's metric was never checked at all.
            if (s.getMetric() != null && !s.getMetric().isBlank()) {
                assertMetricVisible(s.getMetric());
            } else if (s.getDataset() != null) {
                assertFieldRef(s.getDataset(), s.getField());
            } else {
                throw new SaikuValidationException("Sort field '" + s.getField() + "' requires a dataset");
            }
        }
        for (OssieQueryModel.MetricRef v : model.getValues()) {
            assertMetricVisible(v.getMetric());
        }
    }

    /**
     * Resolve a {@code dataset.field} shelf reference: unknown dataset or field name is a typed
     * validation error (saiku#2526); a field the caller's roles deny is an access denial.
     */
    private void assertFieldRef(String datasetName, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new SaikuValidationException("Shelf entry on dataset '" + datasetName + "' names no field");
        }
        OssieModelDto.Dataset ds = findDataset(datasetName);
        if (ds == null) {
            throw new SaikuValidationException("Unknown field '" + datasetName + "." + fieldName + "': dataset '"
                    + datasetName + "' is not in the model");
        }
        for (OssieModelDto.Field f : ds.getFields()) {
            if (fieldName.equalsIgnoreCase(f.getName())) {
                if (!permits(f.getAllowRoles(), f.getDenyRoles(), callerRoles)) {
                    throw new SaikuAccessDeniedException("Access denied: field '" + ds.getName() + "." + f.getName()
                            + "' is not visible to the caller's roles");
                }
                return;
            }
        }
        throw new SaikuValidationException(
                "Unknown field '" + datasetName + "." + fieldName + "' in model '" + modelName() + "'");
    }

    /**
     * Resolve an unqualified filter to its sole declaring dataset. The translator must use this
     * dataset and the field's declared expression: a bare SQL column could otherwise resolve to
     * a different dataset's hidden physical column. Ambiguous semantic names are rejected.
     */
    String resolveUnqualifiedField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new SaikuValidationException("Shelf entry names no field");
        }
        OssieModelDto.Dataset matchDataset = null;
        OssieModelDto.Field matchField = null;
        if (semantic != null) {
            for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
                for (OssieModelDto.Field f : ds.getFields()) {
                    if (fieldName.equalsIgnoreCase(f.getName())) {
                        if (matchDataset != null) {
                            throw new SaikuValidationException(
                                    "Ambiguous field '" + fieldName + "': specify a dataset");
                        }
                        matchDataset = ds;
                        matchField = f;
                    }
                }
            }
        }
        if (matchDataset == null) {
            throw new SaikuValidationException("Unknown field '" + fieldName + "' in model '" + modelName() + "'");
        }
        if (!permits(matchField.getAllowRoles(), matchField.getDenyRoles(), callerRoles)) {
            throw new SaikuAccessDeniedException(
                    "Access denied: field '" + fieldName + "' is not visible to the caller's roles");
        }
        return matchDataset.getName();
    }

    /**
     * Resolve a metric shelf reference: unknown metric name is a typed validation error
     * (saiku#2526); a metric the caller's roles deny is an access denial.
     */
    private void assertMetricVisible(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            throw new SaikuValidationException("Shelf entry names no metric");
        }
        if (semantic == null) return;
        for (OssieModelDto.Metric m : semantic.getMetrics()) {
            if (metricName.equals(m.getName())) {
                if (!permits(m.getAllowRoles(), m.getDenyRoles(), callerRoles)) {
                    throw new SaikuAccessDeniedException(
                            "Access denied: metric '" + metricName + "' is not visible to the caller's roles");
                }
                return;
            }
        }
        throw new SaikuValidationException("Unknown metric '" + metricName + "' in model '" + modelName() + "'");
    }

    /** The model name for typed rejection messages (never null-bearing on the wire). */
    private String modelName() {
        return semantic == null || semantic.getName() == null ? "?" : semantic.getName();
    }

    private OssieModelDto.Dataset findDataset(String name) {
        if (semantic == null || name == null) return null;
        for (OssieModelDto.Dataset ds : semantic.getDatasets()) {
            if (name.equalsIgnoreCase(ds.getName())) return ds;
        }
        return null;
    }

    /**
     * Return a copy of {@code src} with every field/metric the caller's roles deny removed —
     * the discover-surface (workbench schema browser, AI schema) counterpart of {@link
     * #assertShelfVisible}. Datasets, relationships, and alias maps pass through unfiltered;
     * dataset-level HIDE isn't modelled yet (saiku#1393 phase R3).
     */
    public static OssieModelDto filterHidden(OssieModelDto src, Set<String> callerRoles) {
        Set<String> roles = callerRoles == null ? Set.of() : callerRoles;
        OssieModelDto out = new OssieModelDto();
        out.setConnection(src.getConnection());
        out.setName(src.getName());
        out.setDescription(src.getDescription());
        out.setFieldAliases(src.getFieldAliases());
        out.setMetricAliases(src.getMetricAliases());
        out.setDatasetAliases(src.getDatasetAliases());
        out.setRelationships(src.getRelationships());

        for (OssieModelDto.Dataset ds : src.getDatasets()) {
            OssieModelDto.Dataset filtered = new OssieModelDto.Dataset();
            filtered.setName(ds.getName());
            filtered.setSource(ds.getSource());
            filtered.setDescription(ds.getDescription());
            filtered.setPrimaryKey(ds.getPrimaryKey());
            filtered.setCustomExtensions(ds.getCustomExtensions());
            filtered.setRowPredicates(ds.getRowPredicates());
            List<OssieModelDto.Field> keptFields = new ArrayList<>();
            for (OssieModelDto.Field f : ds.getFields()) {
                if (permits(f.getAllowRoles(), f.getDenyRoles(), roles)) {
                    keptFields.add(f);
                }
            }
            filtered.setFields(keptFields);
            out.getDatasets().add(filtered);
        }

        for (OssieModelDto.Metric m : src.getMetrics()) {
            if (permits(m.getAllowRoles(), m.getDenyRoles(), roles)) {
                out.getMetrics().add(m);
            }
        }
        return out;
    }

    /** Shared allow/deny decision, reusing {@link SaikuWellKnownExtensions.Roles#permits}. */
    private static boolean permits(List<String> allow, List<String> deny, Set<String> callerRoles) {
        return new SaikuWellKnownExtensions.Roles(
                        allow == null ? Set.of() : Set.copyOf(allow),
                        deny == null ? Set.of() : Set.copyOf(deny),
                        List.of())
                .permits(callerRoles);
    }
}
