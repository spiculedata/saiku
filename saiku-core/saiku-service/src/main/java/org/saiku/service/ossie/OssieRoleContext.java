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
 *   <li><b>Visibility (HIDE) enforcement</b> — {@link #assertShelfVisible(OssieQueryModel)} rejects
 *       a shelf state that references a field or metric every one of the caller's roles denies.
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
     * Throws {@link SaikuAccessDeniedException} if the shelf state references a field or metric
     * that exists in the semantic model and denies the caller. Entries that don't resolve to a
     * known field/metric are left alone — that's the translator's own not-found handling to do.
     */
    public void assertShelfVisible(OssieQueryModel model) {
        for (OssieQueryModel.FieldRef f : model.getRows()) {
            assertFieldVisible(f.getDataset(), f.getField());
        }
        for (OssieQueryModel.FieldRef f : model.getColumns()) {
            assertFieldVisible(f.getDataset(), f.getField());
        }
        for (OssieQueryModel.FilterExpr f : model.getFilters()) {
            if (f.getDataset() != null) {
                assertFieldVisible(f.getDataset(), f.getField());
            }
        }
        for (OssieQueryModel.SortRef s : model.getSorts()) {
            if (s.getDataset() != null && s.getField() != null) {
                assertFieldVisible(s.getDataset(), s.getField());
            }
        }
        for (OssieQueryModel.MetricRef v : model.getValues()) {
            assertMetricVisible(v.getMetric());
        }
    }

    private void assertFieldVisible(String datasetName, String fieldName) {
        if (datasetName == null || fieldName == null) return;
        OssieModelDto.Dataset ds = findDataset(datasetName);
        if (ds == null) return;
        for (OssieModelDto.Field f : ds.getFields()) {
            if (fieldName.equalsIgnoreCase(f.getName())) {
                if (!permits(f.getAllowRoles(), f.getDenyRoles(), callerRoles)) {
                    throw new SaikuAccessDeniedException(
                            "Access denied: field '" + datasetName + "." + fieldName + "' is not visible to the caller's roles");
                }
                return;
            }
        }
    }

    private void assertMetricVisible(String metricName) {
        if (metricName == null || semantic == null) return;
        for (OssieModelDto.Metric m : semantic.getMetrics()) {
            if (metricName.equals(m.getName())) {
                if (!permits(m.getAllowRoles(), m.getDenyRoles(), callerRoles)) {
                    throw new SaikuAccessDeniedException(
                            "Access denied: metric '" + metricName + "' is not visible to the caller's roles");
                }
                return;
            }
        }
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
