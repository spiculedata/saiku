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
        // All four cube-ref fields are required for downstream Mondrian
        // resolution; a partial object ({"cubeName":"Sales"} alone) would
        // crash with a connection NPE during execution. Reject up-front
        // with a structured 400.
        AiCubeRef ref = req.getCube();
        if (isBlank(ref.getConnectionName())
                || isBlank(ref.getCatalog())
                || isBlank(ref.getSchema())
                || isBlank(ref.getCubeName())) {
            throw new AiValidationException(
                    "cube",
                    "cube ref must specify connectionName, catalog, schema, and cubeName "
                            + "(or be a 'connection/catalog/schema/cubeName' string).",
                    null);
        }
        if (req.getMeasures() == null || req.getMeasures().isEmpty()) {
            throw new AiValidationException("measures", "At least one measure required", null);
        }
        // Reject duplicate measures up-front. Without this guard the records
        // renderer silently drops the duplicate's cell (same map-key collision
        // as saiku#789's multi-axis crossjoin path) — agents have no signal
        // that anything went wrong (saiku#796).
        java.util.Set<String> seenMeasures = new java.util.LinkedHashSet<>();
        for (AiMeasureSelection m : req.getMeasures()) {
            String mn = m == null ? null : m.getName();
            if (mn == null) continue;
            String key = AiSchema.key(mn);
            if (!seenMeasures.add(key)) {
                throw new AiValidationException(
                        "measures",
                        "Measure '" + mn + "' appears more than once. "
                                + "Each measure should appear at most once in `measures[]`.",
                        null);
            }
        }
        if (req.getRows() == null || req.getRows().isEmpty()) {
            // We allow no rows (degenerate: SELECT measures ON COLUMNS FROM cube),
            // but the agent almost always wants rows. Don't error — just emit.
        }

        // Mondrian rejects MDX where the same hierarchy appears in both an
        // axis and the slicer ("Hierarchy '...' appears in more than one
        // independent axis"). Detect that here so the agent gets a 400 with
        // field-level pointer + teaching message, not a 500 (saiku#784).
        validateNoAxisFilterHierarchyOverlap(req, schema);

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
        // Measures set lives on the [Measures] hierarchy.
        StringBuilder measuresSet = new StringBuilder("{");
        boolean first = true;
        for (AiMeasureSelection m : req.getMeasures()) {
            AiSchema.Measure resolved = lookupMeasure(m.getName(), schema);
            if (!first) measuresSet.append(", ");
            measuresSet.append(resolved.uniqueName);
            first = false;
        }
        measuresSet.append("}");

        if (req.getColumns() == null || req.getColumns().isEmpty()) {
            return measuresSet.toString();
        }

        // Resolve each column axis and group consecutive entries by hierarchy
        // — same MDX shape rule as rows: same-hierarchy crossjoin is illegal,
        // so collapse into Hierarchize({set1, set2}). Across distinct
        // hierarchies (and against measures), keep CROSSJOIN.
        List<ResolvedAxis> resolved = new ArrayList<>();
        for (int i = 0; i < req.getColumns().size(); i++) {
            resolved.add(resolveAxis(req.getColumns().get(i), schema, "columns[" + i + "]"));
        }
        List<String> groupExprs = groupSetExpressionsByHierarchy(resolved);

        // Cross-join: measures × each distinct-hierarchy column group.
        StringBuilder s = new StringBuilder(measuresSet);
        for (String groupExpr : groupExprs) {
            s.insert(0, "CROSSJOIN(");
            s.append(", ").append(groupExpr).append(")");
        }
        return s.toString();
    }

    /** Group consecutive same-hierarchy axes into {@code Hierarchize({...})}
     *  set expressions. Distinct hierarchies stay as standalone set
     *  expressions and are CROSSJOIN-ed by the caller. */
    private static List<String> groupSetExpressionsByHierarchy(List<ResolvedAxis> resolved) {
        List<List<ResolvedAxis>> groups = new ArrayList<>();
        for (ResolvedAxis ra : resolved) {
            if (!groups.isEmpty()) {
                List<ResolvedAxis> last = groups.get(groups.size() - 1);
                if (last.get(0).hierarchy.uniqueName.equals(ra.hierarchy.uniqueName)) {
                    last.add(ra);
                    continue;
                }
            }
            List<ResolvedAxis> g = new ArrayList<>();
            g.add(ra);
            groups.add(g);
        }
        List<String> out = new ArrayList<>();
        for (List<ResolvedAxis> group : groups) {
            if (group.size() == 1) {
                out.add(group.get(0).setExpr);
            } else {
                StringBuilder hier = new StringBuilder("Hierarchize({");
                for (int i = 0; i < group.size(); i++) {
                    if (i > 0) hier.append(", ");
                    hier.append(group.get(i).setExpr);
                }
                hier.append("})");
                out.add(hier.toString());
            }
        }
        return out;
    }

    private String buildRowsAxis(AiQueryRequest req, AiSchema schema) {
        // Resolve each axis to (hierarchy, set-expression) so we can group
        // entries that share a hierarchy. MDX rejects CROSSJOIN of two sets
        // from the same hierarchy ("Tuple contains more than one member of
        // hierarchy"). When the agent asks for, e.g. Store State + Store
        // Name (both under [Store].[Stores]), we emit
        // Hierarchize({stateMembers, nameMembers}) instead — the canonical
        // way to mix levels of one hierarchy. Distinct hierarchies still
        // CROSSJOIN as before.
        List<ResolvedAxis> resolved = new ArrayList<>();
        for (int i = 0; i < req.getRows().size(); i++) {
            AiAxisSelection r = req.getRows().get(i);
            resolved.add(resolveAxis(r, schema, "rows[" + i + "]"));
        }

        List<String> groupExprs = groupSetExpressionsByHierarchy(resolved);

        // Mondrian's CROSSJOIN is binary — N-ary CROSSJOIN(a,b,c,d) raises
        // "No function matches signature 'CROSSJOIN(<Set>, <Set>, <Set>,
        // <Set>)'". For 3+ distinct-hierarchy groups, nest left-associatively:
        // CROSSJOIN(CROSSJOIN(CROSSJOIN(a, b), c), d). Mirrors the existing
        // columns-axis pattern that inserts "CROSSJOIN(" at the head and
        // appends ", set)" per additional group.
        String rowExpr;
        if (groupExprs.size() == 1) {
            rowExpr = groupExprs.get(0);
        } else {
            StringBuilder s = new StringBuilder(groupExprs.get(0));
            for (int i = 1; i < groupExprs.size(); i++) {
                s.insert(0, "CROSSJOIN(");
                s.append(", ").append(groupExprs.get(i)).append(")");
            }
            rowExpr = s.toString();
        }

        // Apply ordering / top-N. Precedence:
        //   order + limit -> TopCount/BottomCount
        //   order alone   -> Order
        //   limit alone   -> HEAD
        //   neither       -> raw set
        if (req.getOrder() != null && !req.getOrder().isEmpty()) {
            if (req.getOrder().size() > 1) {
                // MDX TopCount/Order take exactly one sort key. We won't
                // silently ignore the extras — they're almost always an
                // agent's misunderstanding of the API (e.g. "SQL-style
                // multi-column ORDER BY"). Reject with a teaching error
                // pointing at the right shape.
                throw new AiValidationException(
                        "order",
                        "Only one sort key is supported. MDX TopCount/Order take a single measure. "
                                + "If you need multi-key sorting, send the primary key in `order[0]` and "
                                + "apply secondary tie-breaks client-side after the response.",
                        null);
            }
            AiOrderBy ob = req.getOrder().get(0);
            // direction must be exactly "asc" or "desc" (case-insensitive).
            // Without this guard, anything that doesn't start with "asc"
            // falls through to descending — silent garbage acceptance.
            String dir = ob.getDirection();
            if (dir != null && !dir.equalsIgnoreCase("asc") && !dir.equalsIgnoreCase("desc")) {
                throw new AiValidationException(
                        "order[0].direction",
                        "direction must be 'asc' or 'desc' (case-insensitive). Got '" + dir + "'.",
                        null);
            }
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
        return resolveAxis(sel, schema, fieldPath).setExpr;
    }

    /** Pairs an axis's resolved hierarchy with its emitted set expression. */
    private static final class ResolvedAxis {
        final AiSchema.Hierarchy hierarchy;
        final AiSchema.Level level;
        final String setExpr;

        ResolvedAxis(AiSchema.Hierarchy hierarchy, AiSchema.Level level, String setExpr) {
            this.hierarchy = hierarchy;
            this.level = level;
            this.setExpr = setExpr;
        }
    }

    private ResolvedAxis resolveAxis(AiAxisSelection sel, AiSchema schema, String fieldPath) {
        AiSchema.Hierarchy hierarchy = lookupHierarchy(sel, schema, fieldPath);
        AiSchema.Level level = lookupLevelOnHierarchy(hierarchy, sel.getLevel(), fieldPath);

        String setExpr;
        if (sel.getMembers() != null && !sel.getMembers().isEmpty()) {
            StringBuilder s = new StringBuilder("{");
            for (int i = 0; i < sel.getMembers().size(); i++) {
                String m = sel.getMembers().get(i);
                validateMemberRef(m, fieldPath + ".members[" + i + "]");
                validateMemberLevelMatch(m, level, hierarchy, fieldPath + ".members[" + i + "]");
                if (i > 0) s.append(", ");
                s.append(m);
            }
            s.append("}");
            setExpr = s.toString();
        } else {
            setExpr = level.uniqueName + ".Members";
        }
        return new ResolvedAxis(hierarchy, level, setExpr);
    }

    private String buildSlicer(List<AiFilterSelection> filters, AiSchema schema) {
        // Each filter becomes a slicer tuple-element. Multiple filters
        // cross-join into a tuple: (slicer1, slicer2, ...).
        //
        // Mondrian's WHERE clause expects a tuple of *single* members
        // (or expressions that resolve to one). A bare set like
        // {m1, m2} or Descendants(...) raises "Descendants() expects a
        // tuple, got a set". So whenever a filter produces a set we wrap
        // it in Aggregate(...) — which collapses the set to a single
        // implicit-aggregation point on that dimension.
        //
        // Two filters on the same hierarchy would also produce a tuple
        // with two members of one hierarchy, which Mondrian rejects.
        // Reject up-front with a teaching error so the agent merges them
        // into one filter with op:"in" + combined members instead.
        java.util.Set<String> seenHierarchies = new java.util.LinkedHashSet<>();
        for (int i = 0; i < filters.size(); i++) {
            AiFilterSelection f = filters.get(i);
            String fieldPath = "filters[" + i + "]";
            AiAxisSelection probe = new AiAxisSelection(f.getDimension(), f.getHierarchy(), f.getLevel());
            AiSchema.Hierarchy h = lookupHierarchy(probe, schema, fieldPath);
            if (!seenHierarchies.add(h.uniqueName)) {
                throw new AiValidationException(
                        fieldPath + ".hierarchy",
                        "Multiple filters target hierarchy '" + h.name
                                + "'. Mondrian's WHERE clause is a tuple — combine them into one filter "
                                + "with op:'in' and all the members in `members[]` (or op:'between' for a range).",
                        null);
            }
        }

        StringBuilder s = new StringBuilder("(");
        for (int i = 0; i < filters.size(); i++) {
            AiFilterSelection f = filters.get(i);
            String fieldPath = "filters[" + i + "]";
            AiAxisSelection probe = new AiAxisSelection(f.getDimension(), f.getHierarchy(), f.getLevel());
            AiSchema.Level level = lookupLevel(probe, schema, fieldPath);

            String op = f.getOp() == null ? "in" : f.getOp().toLowerCase();
            List<String> members = f.getMembers() == null ? java.util.Collections.emptyList() : f.getMembers();
            // Reject anything in `members[]` that isn't a strict member-reference
            // form before it gets spliced into the MDX. saiku#786: arbitrary
            // strings (containing comma + function call) used to inline into the
            // {...} set literal and reach Mondrian's parser. Doesn't apply to
            // op=relative, which doesn't read members[].
            if (!"relative".equals(op)) {
                for (int mi = 0; mi < members.size(); mi++) {
                    validateMemberRef(members.get(mi), fieldPath + ".members[" + mi + "]");
                }
            }

            if (i > 0) s.append(", ");

            String expr;
            boolean isSet;
            switch (op) {
                case "in": {
                    if (members.isEmpty()) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'in' filter must specify at least one member", null);
                    }
                    if (members.size() == 1) {
                        expr = members.get(0);
                        isSet = false;
                    } else {
                        StringBuilder set = new StringBuilder("{");
                        for (int j = 0; j < members.size(); j++) {
                            if (j > 0) set.append(", ");
                            set.append(members.get(j));
                        }
                        set.append("}");
                        expr = set.toString();
                        isSet = true;
                    }
                    break;
                }
                case "not_in": {
                    if (members.isEmpty()) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'not_in' filter must specify at least one member", null);
                    }
                    StringBuilder set = new StringBuilder();
                    set.append("Except(").append(level.uniqueName).append(".Members, {");
                    for (int j = 0; j < members.size(); j++) {
                        if (j > 0) set.append(", ");
                        set.append(members.get(j));
                    }
                    set.append("})");
                    expr = set.toString();
                    isSet = true;
                    break;
                }
                case "between": {
                    if (members.size() != 2) {
                        throw new AiValidationException(
                                fieldPath + ".members",
                                "'between' filter requires exactly 2 members [start, end]",
                                null);
                    }
                    // Standard MDX range: m1 : m2 returns the set of
                    // members from m1 to m2 inclusive at their common
                    // level. Mondrian's slicer accepts the bare range.
                    expr = "{" + members.get(0) + " : " + members.get(1) + "}";
                    isSet = true;
                    break;
                }
                case "descendants_of": {
                    if (members.size() != 1) {
                        throw new AiValidationException(
                                fieldPath + ".members", "'descendants_of' filter requires exactly 1 member", null);
                    }
                    // In WHERE position, a non-leaf member is already the
                    // aggregate of all its descendants — that's how
                    // Mondrian's natural roll-up works. Emitting the
                    // member alone (instead of Descendants(member)) gives
                    // the same numeric answer with MDX that parses
                    // reliably across Mondrian's function-overload rules.
                    expr = members.get(0);
                    isSet = false;
                    break;
                }
                case "relative": {
                    expr = relativeSet(f, level, fieldPath);
                    isSet = isRelativeSet(f);
                    break;
                }
                default:
                    throw new AiValidationException(
                            fieldPath + ".op",
                            "Unknown filter op '" + f.getOp() + "'",
                            java.util.Arrays.asList("in", "not_in", "between", "descendants_of", "relative"));
            }

            // Mondrian's slicer accepts a bare set and applies implicit
            // aggregation across its members. Wrapping with Aggregate()
            // turns the slicer-element into a Numeric Expression and
            // confuses the parser ("No function matches signature
            // '{<Numeric Expression>}'") when the set is in the WHERE
            // position. Emit the set form directly.
            s.append(expr);
        }
        s.append(")");
        return s.toString();
    }

    /**
     * Of the relative presets, {@code previous_period} is the only one
     * that resolves to a single member ({@code Tail(...).Item(0)}). The
     * rest are sets and must be Aggregate-wrapped in the slicer.
     */
    private static boolean isRelativeSet(AiFilterSelection f) {
        return !"previous_period".equalsIgnoreCase(f.getValue());
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
     *       The member immediately preceding the latest member that has
     *       data in the cube — NOT "yesterday" relative to wall-clock time.
     *       If the warehouse is stale, this is stale too.</li>
     * </ul>
     *
     * <p>{@code same_period_last_year} is intentionally NOT supported in v1.
     * At Year level it would be identical to {@code previous_period}; at
     * Month/Quarter level the year-aware equivalent requires a hierarchy-
     * aware ParallelPeriod we don't yet introspect, and shipping it as a
     * silent fall-through to "second-most-recent" would be a footgun.
     */
    private String relativeSet(AiFilterSelection f, AiSchema.Level level, String fieldPath) {
        String preset = f.getValue() == null ? "" : f.getValue().toLowerCase();
        if (preset.isEmpty()) {
            throw new AiValidationException(
                    fieldPath + ".value",
                    "'relative' filter requires value=last_n_days|last_n_months|last_n_quarters|last_n_years|ytd|mtd|qtd|previous_period",
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
                                "previous_period"));
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
        AiSchema.Hierarchy hier = lookupHierarchy(sel, schema, fieldPath);
        return lookupLevelOnHierarchy(hier, sel.getLevel(), fieldPath);
    }

    private AiSchema.Hierarchy lookupHierarchy(AiAxisSelection sel, AiSchema schema, String fieldPath) {
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
        if (sel.getHierarchy() == null || sel.getHierarchy().isEmpty()) {
            // Pick the single hierarchy if there's only one; otherwise demand explicit.
            if (dim.hierarchies.size() == 1) {
                return dim.hierarchies.values().iterator().next();
            }
            throw new AiValidationException(
                    fieldPath + ".hierarchy",
                    "Dimension '" + dim.name + "' has multiple hierarchies; specify one",
                    availableHierarchyNames(dim));
        }
        String hierK = AiSchema.key(sel.getHierarchy());
        AiSchema.Hierarchy hier = dim.hierarchies.get(hierK);
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
        return hier;
    }

    private AiSchema.Level lookupLevelOnHierarchy(AiSchema.Hierarchy hier, String levelName, String fieldPath) {
        if (levelName == null || levelName.isEmpty()) {
            throw new AiValidationException(fieldPath + ".level", "Level required", availableLevelNames(hier));
        }
        String lvlK = AiSchema.key(levelName);
        AiSchema.Level level = hier.levels.get(lvlK);
        if (level == null) {
            String aliasTarget = hier.levelAliases.get(lvlK);
            if (aliasTarget != null) level = hier.levels.get(aliasTarget);
        }
        if (level == null) {
            throw new AiValidationException(
                    fieldPath + ".level",
                    "Unknown level '" + levelName + "' on hierarchy '" + hier.name + "'",
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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Reject member refs whose depth doesn't match the declared level's
     * position in the hierarchy. Without this, agents that supply a member
     * at the wrong level (e.g. {@code level: "Product Family"} but
     * {@code members: ["[Product].[Products].[Drink].[Beverages]"]} which is
     * at Product Department) get a SUCCESS response with the value present
     * but the corresponding row-header column rendered as {@code null} —
     * silent inconsistency the agent can't easily detect (saiku#790).
     *
     * <p>The check is purely segment counting (cheap, no extra olap4j roundtrip):
     * a Mondrian unique name has the shape
     * {@code [Dim].[Hier].[Level1Member].[Level2Member]...[LeafMember]}, so the
     * member's depth equals (segments - 2). The declared level's depth is its
     * 0-based index in the hierarchy's levels map; the "(All)" pseudo-level at
     * index 0 has depth 0, the first real level has depth 1, etc.
     */
    private static void validateMemberLevelMatch(
            String memberRef, AiSchema.Level declared, AiSchema.Hierarchy hier, String fieldPath) {
        // Skip key-form refs ([Lvl].&[Key]) — their depth is set by the
        // level-name segments, which require a richer parser than we have
        // here. Name-form refs are the agent-friendly default and are the
        // ones LLMs typically emit, so the validator covers the practical
        // failure mode while staying conservative.
        if (memberRef != null && memberRef.indexOf("&[") >= 0) return;
        int segments = countBracketedSegments(memberRef);
        int expectedDepth = levelDepth(declared, hier);
        if (expectedDepth < 0) return;
        // member depth = segments - 2 (dim + hier prefix), with the (All)
        // member at depth 0.
        int actualDepth = segments - 2;
        if (actualDepth != expectedDepth) {
            String actualLevelName = levelNameAtDepth(hier, actualDepth);
            throw new AiValidationException(
                    fieldPath,
                    "Member '" + memberRef + "' is at level '"
                            + (actualLevelName == null ? "depth " + actualDepth : actualLevelName)
                            + "', but the axis declares level '" + declared.name + "'. "
                            + "Either change the declared level to match the member, or supply a member "
                            + "at the declared level.",
                    null);
        }
    }

    private static int countBracketedSegments(String ref) {
        if (ref == null) return 0;
        int n = 0;
        int depth = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c == '[') {
                if (depth == 0) n++;
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return n;
    }

    private static int levelDepth(AiSchema.Level target, AiSchema.Hierarchy hier) {
        if (hier == null || target == null) return -1;
        int i = 0;
        for (AiSchema.Level l : hier.levels.values()) {
            if (l == target) return i;
            i++;
        }
        return -1;
    }

    private static String levelNameAtDepth(AiSchema.Hierarchy hier, int depth) {
        if (hier == null || depth < 0) return null;
        int i = 0;
        for (AiSchema.Level l : hier.levels.values()) {
            if (i == depth) return l.name;
            i++;
        }
        return null;
    }

    /** Strict member-reference syntax: one or more bracketed identifiers
     *  separated by dots, optional {@code &} key prefix on any segment.
     *  Examples that match:
     *    {@code [Foo]}
     *    {@code [Foo].[Bar]}
     *    {@code [Time].[Time].[Year].&[1997]}
     *  Examples that do NOT (and are rejected as MDX-injection attempts):
     *    {@code [Foo], Crossjoin(...)} — embeds a function call
     *    {@code [Foo] + [Bar]} — embeds an operator
     *    {@code Foo} — bare identifier (must be bracketed) */
    private static final java.util.regex.Pattern MEMBER_REF_PATTERN =
            java.util.regex.Pattern.compile("^\\s*\\[[^\\[\\]]*\\](\\s*\\.\\s*&?\\[[^\\[\\]]*\\])*\\s*$");

    /**
     * Reject anything that isn't a strict member-reference shape. Prevents
     * agent-supplied strings from injecting arbitrary MDX through {@code
     * members[]} entries (see saiku#786).
     */
    static void validateMemberRef(String memberRef, String fieldPath) {
        if (memberRef == null || !MEMBER_REF_PATTERN.matcher(memberRef).matches()) {
            throw new AiValidationException(
                    fieldPath,
                    "Member references must be bracketed-dot-separated identifiers "
                            + "(e.g. [Foo].[Bar] or [Foo].&[Bar]). "
                            + "Embedded MDX expressions are not allowed in members[].",
                    null);
        }
    }

    /**
     * Reject requests whose filters target a hierarchy already on rows or
     * columns. Mondrian rejects such MDX at parse time with
     * "Hierarchy '...' appears in more than one independent axis", which
     * would otherwise leak as a 500 EXECUTION_ERROR (see saiku#784).
     *
     * <p>The agent's recovery is to either move the filter members onto the
     * axis selection's {@code members[]}, or filter on a different
     * hierarchy/dimension.
     */
    private void validateNoAxisFilterHierarchyOverlap(AiQueryRequest req, AiSchema schema) {
        if (req.getFilters() == null || req.getFilters().isEmpty()) return;
        java.util.Set<String> axisHierarchies = new java.util.LinkedHashSet<>();
        collectAxisHierarchies(req.getRows(), schema, "rows", axisHierarchies);
        collectAxisHierarchies(req.getColumns(), schema, "columns", axisHierarchies);
        for (int i = 0; i < req.getFilters().size(); i++) {
            AiFilterSelection f = req.getFilters().get(i);
            String fieldPath = "filters[" + i + "]";
            AiAxisSelection probe = new AiAxisSelection(f.getDimension(), f.getHierarchy(), f.getLevel());
            AiSchema.Hierarchy h = lookupHierarchy(probe, schema, fieldPath);
            if (axisHierarchies.contains(h.uniqueName)) {
                throw new AiValidationException(
                        fieldPath + ".hierarchy",
                        "Hierarchy '" + h.name + "' is already on the rows/columns axis. "
                                + "Mondrian rejects the same hierarchy on two independent axes. "
                                + "Either move the filter members onto the axis selection's `members[]`, "
                                + "or filter on a different hierarchy/dimension.",
                        null);
            }
        }
    }

    private void collectAxisHierarchies(
            List<AiAxisSelection> axes, AiSchema schema, String fieldPrefix, java.util.Set<String> out) {
        if (axes == null) return;
        for (int i = 0; i < axes.size(); i++) {
            AiSchema.Hierarchy h = lookupHierarchy(axes.get(i), schema, fieldPrefix + "[" + i + "]");
            out.add(h.uniqueName);
        }
    }
}
