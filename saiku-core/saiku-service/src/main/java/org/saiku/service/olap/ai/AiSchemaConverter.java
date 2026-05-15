/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;

/**
 * Validates an {@link AiQueryRequest} against a live {@link AiSchema} and
 * produces an MDX-mode {@link ThinQuery} the existing ThinQueryService can
 * execute.
 *
 * <p>The converter is pure: no olap4j, no Mondrian, no I/O. All schema
 * lookups happen in-memory against the cached AiSchema. This is what
 * makes the unit tests trivial — the resource layer builds the schema
 * once via olap4j and hands it in.
 */
public class AiSchemaConverter {

    /**
     * Convert + validate.
     *
     * @throws AiValidationException with field/available populated if any
     *         name in the request fails to resolve.
     */
    public ThinQuery convert(AiQueryRequest req, AiSchema schema) {
        if (req == null) throw new AiValidationException("request", "Request body required", null);
        if (req.getCube() == null) throw new AiValidationException("cube", "cube ref required", null);
        if (req.getMeasures() == null || req.getMeasures().isEmpty()) {
            throw new AiValidationException("measures", "At least one measure required", null);
        }
        if (req.getRows() == null || req.getRows().isEmpty()) {
            // We allow no rows (degenerate: SELECT measures ON COLUMNS FROM cube),
            // but the agent almost always wants rows. Don't error — just emit.
        }

        // Resolve everything to canonical unique names + build MDX.
        StringBuilder mdx = new StringBuilder();

        // COLUMNS axis: { measures }  CROSSJOIN  columns-axes
        mdx.append("SELECT ");
        if (req.isNonEmpty()) mdx.append("NON EMPTY ");
        mdx.append(buildColumnsAxis(req, schema));
        mdx.append(" ON COLUMNS");

        if (req.getRows() != null && !req.getRows().isEmpty()) {
            mdx.append(",\n");
            if (req.isNonEmpty()) mdx.append("NON EMPTY ");
            mdx.append(buildRowsAxis(req, schema));
            mdx.append(" ON ROWS");
        }

        // Mondrian's MDX parser wants FROM [cubeName], not the
        // connection-prefixed unique name that SaikuCube.getUniqueName()
        // returns. The cube ref's own cube name is what we want.
        mdx.append("\nFROM [").append(schema.getCubeName()).append("]");

        if (req.getFilters() != null && !req.getFilters().isEmpty()) {
            mdx.append("\nWHERE ").append(buildSlicer(req.getFilters(), schema));
        }

        // Build the ThinQuery wrapper.
        ThinQuery tq = new ThinQuery();
        tq.setName(UUID.randomUUID().toString());
        tq.setType(ThinQuery.Type.MDX);
        tq.setMdx(mdx.toString());
        tq.setCube(toSaikuCube(req.getCube(), schema));
        return tq;
    }

    /* ------------------------------------------------------------------ */

    private String buildColumnsAxis(AiQueryRequest req, AiSchema schema) {
        // Measures set.
        StringBuilder s = new StringBuilder();
        s.append("{");
        boolean first = true;
        for (AiMeasureSelection m : req.getMeasures()) {
            AiSchema.Measure resolved = lookupMeasure(m.getName(), schema);
            if (!first) s.append(", ");
            s.append(resolved.uniqueName);
            first = false;
        }
        s.append("}");

        // Optional columns-axes — crossjoin onto measures.
        if (req.getColumns() != null && !req.getColumns().isEmpty()) {
            for (AiAxisSelection col : req.getColumns()) {
                s.insert(0, "CROSSJOIN(");
                s.append(", ").append(axisSet(col, schema, "columns")).append(")");
            }
        }
        return s.toString();
    }

    private String buildRowsAxis(AiQueryRequest req, AiSchema schema) {
        // CROSSJOIN of each rows-axis entry.
        StringBuilder s = new StringBuilder();
        List<String> sets = new ArrayList<>();
        for (AiAxisSelection r : req.getRows()) {
            sets.add(axisSet(r, schema, "rows"));
        }
        if (sets.size() == 1) {
            s.append(sets.get(0));
        } else {
            s.append("CROSSJOIN(");
            for (int i = 0; i < sets.size(); i++) {
                if (i > 0) s.append(", ");
                s.append(sets.get(i));
            }
            s.append(")");
        }

        String rowExpr = s.toString();

        // Apply ordering / top-N. Precedence:
        //   order + limit -> TopCount/BottomCount
        //   order alone   -> Order
        //   limit alone   -> HEAD
        //   neither       -> raw set
        if (req.getOrder() != null && !req.getOrder().isEmpty()) {
            AiOrderBy ob = req.getOrder().get(0);
            AiSchema.Measure m = lookupMeasure(ob.getBy(), schema);
            if (req.getLimit() > 0) {
                rowExpr = (ob.isAscending() ? "BottomCount(" : "TopCount(") + rowExpr + ", " + req.getLimit() + ", "
                        + m.uniqueName + ")";
            } else {
                rowExpr = "Order(" + rowExpr + ", " + m.uniqueName + ", " + (ob.isAscending() ? "BASC" : "BDESC") + ")";
            }
        } else if (req.getLimit() > 0) {
            rowExpr = "HEAD(" + rowExpr + ", " + req.getLimit() + ")";
        }

        if (req.isVisualTotals()) {
            return "VISUALTOTALS(" + rowExpr + ")";
        }
        return rowExpr;
    }

    private String axisSet(AiAxisSelection sel, AiSchema schema, String fieldPath) {
        AiSchema.Level level = lookupLevel(sel, schema, fieldPath);
        if (sel.getMembers() != null && !sel.getMembers().isEmpty()) {
            // Explicit member set.
            StringBuilder s = new StringBuilder("{");
            for (int i = 0; i < sel.getMembers().size(); i++) {
                if (i > 0) s.append(", ");
                s.append(sel.getMembers().get(i));
            }
            s.append("}");
            return s.toString();
        }
        return level.uniqueName + ".Members";
    }

    private String buildSlicer(List<AiFilterSelection> filters, AiSchema schema) {
        // Each filter becomes a slicer tuple-element. Multiple filters
        // cross-join into a tuple: (slicer1, slicer2, ...).
        StringBuilder s = new StringBuilder("(");
        for (int i = 0; i < filters.size(); i++) {
            AiFilterSelection f = filters.get(i);
            String fieldPath = "filters[" + i + "]";
            AiAxisSelection probe = new AiAxisSelection(f.getDimension(), f.getHierarchy(), f.getLevel());
            AiSchema.Level level = lookupLevel(probe, schema, fieldPath);

            String op = f.getOp() == null ? "in" : f.getOp().toLowerCase();
            List<String> members = f.getMembers() == null ? java.util.Collections.emptyList() : f.getMembers();

            if (i > 0) s.append(", ");

            switch (op) {
                case "in": {
                    if (members.isEmpty()) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'in' filter must specify at least one member", null);
                    }
                    if (members.size() == 1) {
                        s.append(members.get(0));
                    } else {
                        s.append("{");
                        for (int j = 0; j < members.size(); j++) {
                            if (j > 0) s.append(", ");
                            s.append(members.get(j));
                        }
                        s.append("}");
                    }
                    break;
                }
                case "not_in": {
                    if (members.isEmpty()) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'not_in' filter must specify at least one member", null);
                    }
                    s.append("Except(").append(level.uniqueName).append(".Members, {");
                    for (int j = 0; j < members.size(); j++) {
                        if (j > 0) s.append(", ");
                        s.append(members.get(j));
                    }
                    s.append("})");
                    break;
                }
                case "between": {
                    if (members.size() != 2) {
                        throw new AiValidationException(
                                fieldPath + ".members",
                                "'between' filter requires exactly 2 members [start, end]",
                                null);
                    }
                    s.append(members.get(0)).append(" : ").append(members.get(1));
                    break;
                }
                case "descendants_of": {
                    if (members.size() != 1) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'descendants_of' filter requires exactly 1 member", null);
                    }
                    s.append("Descendants(").append(members.get(0)).append(")");
                    break;
                }
                case "relative": {
                    s.append(relativeSet(f, level, fieldPath));
                    break;
                }
                default:
                    throw new AiValidationException(
                            fieldPath + ".op",
                            "Unknown filter op '" + f.getOp() + "'",
                            java.util.Arrays.asList("in", "not_in", "between", "descendants_of", "relative"));
            }
        }
        s.append(")");
        return s.toString();
    }

    /**
     * Translate a {@code "relative"} filter into an MDX set expression
     * over the given level. Presets:
     *
     * <ul>
     *   <li>{@code last_n_days|last_n_months|last_n_quarters|last_n_years} —
     *       {@code Tail(level.Members, n)}. Picks the most-recent N members
     *       on the level. The agent picks the level matching the period
     *       ({@code Day}/{@code Month}/{@code Quarter}/{@code Year}).</li>
     *   <li>{@code ytd|mtd|qtd} — {@code Ytd()|Mtd()|Qtd()}. Relies on the
     *       cube's time-dimension default member; if the cube author
     *       didn't anchor it, the agent should switch to {@code last_n_*}.</li>
     *   <li>{@code previous_period} — {@code Tail(level.Members, 2).Item(0)}.
     *       The member immediately preceding the latest available.</li>
     *   <li>{@code same_period_last_year} — for {@code Year} level only:
     *       {@code Tail(level.Members, 2).Item(0)}. For finer levels
     *       (Quarter/Month/Day) the year-shifted equivalent needs a
     *       hierarchy-aware ParallelPeriod we don't yet introspect; the
     *       converter raises a validation error pointing at v1 alternatives.</li>
     * </ul>
     */
    private String relativeSet(AiFilterSelection f, AiSchema.Level level, String fieldPath) {
        String preset = f.getValue() == null ? "" : f.getValue().toLowerCase();
        if (preset.isEmpty()) {
            throw new AiValidationException(
                    fieldPath + ".value",
                    "'relative' filter requires value=last_n_days|last_n_months|last_n_quarters|last_n_years|ytd|mtd|qtd|previous_period|same_period_last_year",
                    null);
        }
        switch (preset) {
            case "last_n_days":
            case "last_n_months":
            case "last_n_quarters":
            case "last_n_years": {
                int n = f.getN() <= 0 ? 1 : f.getN();
                return "Tail(" + level.uniqueName + ".Members, " + n + ")";
            }
            case "ytd":
                return "Ytd()";
            case "mtd":
                return "Mtd()";
            case "qtd":
                return "Qtd()";
            case "previous_period":
                return "Tail(" + level.uniqueName + ".Members, 2).Item(0)";
            case "same_period_last_year":
                // Defensible only when the level *is* the year. For sub-year
                // levels the correct MDX is ParallelPeriod against a year-level
                // ancestor — we don't yet introspect hierarchy shape, so we
                // fail loudly rather than emit incorrect MDX.
                if ("year".equalsIgnoreCase(level.name)) {
                    return "Tail(" + level.uniqueName + ".Members, 2).Item(0)";
                }
                throw new AiValidationException(
                        fieldPath + ".value",
                        "'same_period_last_year' currently only supports level=Year. "
                                + "For finer-grained periods use last_n_* with the appropriate n.",
                        null);
            default:
                throw new AiValidationException(
                        fieldPath + ".value",
                        "Unknown relative preset '" + f.getValue() + "'",
                        java.util.Arrays.asList(
                                "last_n_days",
                                "last_n_months",
                                "last_n_quarters",
                                "last_n_years",
                                "ytd",
                                "mtd",
                                "qtd",
                                "previous_period",
                                "same_period_last_year"));
        }
    }

    /* ------------------ name resolution ------------------------------- */

    private AiSchema.Measure lookupMeasure(String name, AiSchema schema) {
        if (name == null || name.isEmpty()) {
            throw new AiValidationException("measures[].name", "Measure name required", availableMeasureNames(schema));
        }
        String k = AiSchema.key(name);
        AiSchema.Measure m = schema.measures.get(k);
        if (m == null) {
            // Phase 3: display-name alias fallback.
            String aliasTarget = schema.measureAliases.get(k);
            if (aliasTarget != null) m = schema.measures.get(aliasTarget);
        }
        if (m == null) {
            throw new AiValidationException(
                    "measures[].name", "Unknown measure '" + name + "'", availableMeasureNames(schema));
        }
        return m;
    }

    /** Canonical names plus any display-name aliases — so error messages
     *  list every string the agent could have used. */
    private static List<String> availableMeasureNames(AiSchema schema) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Measure m : schema.measures.values()) {
            out.add(m.name);
            if (m.displayName != null && !m.displayName.isEmpty()) out.add(m.displayName);
        }
        return out;
    }

    private AiSchema.Level lookupLevel(AiAxisSelection sel, AiSchema schema, String fieldPath) {
        if (sel.getDimension() == null || sel.getDimension().isEmpty()) {
            throw new AiValidationException(
                    fieldPath + ".dimension", "Dimension required", availableDimensionNames(schema));
        }
        String dimK = AiSchema.key(sel.getDimension());
        AiSchema.Dimension dim = schema.dimensions.get(dimK);
        if (dim == null) {
            String aliasTarget = schema.dimensionAliases.get(dimK);
            if (aliasTarget != null) dim = schema.dimensions.get(aliasTarget);
        }
        if (dim == null) {
            throw new AiValidationException(
                    fieldPath + ".dimension",
                    "Unknown dimension '" + sel.getDimension() + "'",
                    availableDimensionNames(schema));
        }
        AiSchema.Hierarchy hier;
        if (sel.getHierarchy() == null || sel.getHierarchy().isEmpty()) {
            // Pick the single hierarchy if there's only one; otherwise demand explicit.
            if (dim.hierarchies.size() == 1) {
                hier = dim.hierarchies.values().iterator().next();
            } else {
                throw new AiValidationException(
                        fieldPath + ".hierarchy",
                        "Dimension '" + dim.name + "' has multiple hierarchies; specify one",
                        availableHierarchyNames(dim));
            }
        } else {
            String hierK = AiSchema.key(sel.getHierarchy());
            hier = dim.hierarchies.get(hierK);
            if (hier == null) {
                String aliasTarget = dim.hierarchyAliases.get(hierK);
                if (aliasTarget != null) hier = dim.hierarchies.get(aliasTarget);
            }
            if (hier == null) {
                throw new AiValidationException(
                        fieldPath + ".hierarchy",
                        "Unknown hierarchy '" + sel.getHierarchy() + "' on dimension '" + dim.name + "'",
                        availableHierarchyNames(dim));
            }
        }
        if (sel.getLevel() == null || sel.getLevel().isEmpty()) {
            throw new AiValidationException(fieldPath + ".level", "Level required", availableLevelNames(hier));
        }
        String lvlK = AiSchema.key(sel.getLevel());
        AiSchema.Level level = hier.levels.get(lvlK);
        if (level == null) {
            String aliasTarget = hier.levelAliases.get(lvlK);
            if (aliasTarget != null) level = hier.levels.get(aliasTarget);
        }
        if (level == null) {
            throw new AiValidationException(
                    fieldPath + ".level",
                    "Unknown level '" + sel.getLevel() + "' on hierarchy '" + hier.name + "'",
                    availableLevelNames(hier));
        }
        return level;
    }

    private static List<String> availableDimensionNames(AiSchema schema) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Dimension d : schema.dimensions.values()) {
            out.add(d.name);
            if (d.displayName != null && !d.displayName.isEmpty()) out.add(d.displayName);
        }
        return out;
    }

    private static List<String> availableHierarchyNames(AiSchema.Dimension d) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Hierarchy h : d.hierarchies.values()) {
            out.add(h.name);
            if (h.displayName != null && !h.displayName.isEmpty()) out.add(h.displayName);
        }
        return out;
    }

    private static List<String> availableLevelNames(AiSchema.Hierarchy h) {
        List<String> out = new ArrayList<>();
        for (AiSchema.Level l : h.levels.values()) {
            out.add(l.name);
            if (l.displayName != null && !l.displayName.isEmpty()) out.add(l.displayName);
        }
        return out;
    }

    private static List<String> canonicalNames(java.util.Collection<?> items) {
        List<String> out = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof AiSchema.Measure) out.add(((AiSchema.Measure) o).name);
            else if (o instanceof AiSchema.Dimension) out.add(((AiSchema.Dimension) o).name);
            else if (o instanceof AiSchema.Hierarchy) out.add(((AiSchema.Hierarchy) o).name);
            else if (o instanceof AiSchema.Level) out.add(((AiSchema.Level) o).name);
        }
        return out;
    }

    private static SaikuCube toSaikuCube(AiCubeRef ref, AiSchema schema) {
        return new SaikuCube(
                ref.getConnectionName(),
                schema.getCubeUniqueName(),
                ref.getCubeName(),
                schema.getCubeName(),
                ref.getCatalog(),
                ref.getSchema());
    }
}
