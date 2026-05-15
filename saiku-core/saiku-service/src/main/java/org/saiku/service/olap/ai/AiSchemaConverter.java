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

        rejectDuplicateAxisEntries(req.getColumns(), "columns");
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
        rejectDuplicateAxisEntries(req.getRows(), "rows");
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
                AiSchema.Hierarchy filterHier = lookupHierarchy(probe, schema, fieldPath);
                // Detect duplicate-by-leaf-segment within this filter's
                // members[] — catches the same-logical-member-two-ref-forms
                // pattern that silently double-counts in Mondrian's bare-set
                // slicer (saiku#804). Within a single filter all members are
                // at the same declared level, so two refs sharing the same
                // leaf-segment caption resolve to the same member.
                java.util.Map<String, Integer> seenLeaf = new java.util.LinkedHashMap<>();
                for (int mi = 0; mi < members.size(); mi++) {
                    String mref = members.get(mi);
                    validateMemberRef(mref, fieldPath + ".members[" + mi + "]");
                    String leaf = lastBracketSegmentLower(mref);
                    Integer prev = seenLeaf.put(leaf, mi);
                    if (prev != null) {
                        throw new AiValidationException(
                                fieldPath + ".members[" + mi + "]",
                                "Member '" + mref + "' resolves to the same member as "
                                        + fieldPath + ".members[" + prev
                                        + "] ('" + members.get(prev) + "'). "
                                        + "Mondrian aggregates duplicates without deduplication "
                                        + "and would silently double-count. "
                                        + "Use only one ref form per logical member.",
                                null);
                    }
                    // Also assert the member's dim+hier prefix matches the
                    // declared filter dim+hier (saiku#798, saiku#799). The
                    // level depth check is skipped because slicer members can
                    // sit at any depth (op=in with a Year member while
                    // level=Quarter is a valid pattern). Dim+hier checks are
                    // the ones safe to apply uniformly to slicers.
                    String[] dimHier = firstTwoSegments(mref);
                    if (filterHier != null && dimHier != null && dimHier[0] != null) {
                        String expectedDim = extractDimFromHierarchyUniqueName(filterHier.uniqueName);
                        if (expectedDim != null && !expectedDim.equalsIgnoreCase(dimHier[0])) {
                            throw new AiValidationException(
                                    fieldPath + ".members[" + mi + "]",
                                    "Member '" + mref + "' belongs to dimension '" + dimHier[0]
                                            + "', but the filter declares dimension '" + expectedDim
                                            + "'. Move the member into a filter on its own dimension, "
                                            + "or supply a member from '" + expectedDim + "'.",
                                    null);
                        }
                        String expectedHier = extractHierFromHierarchyUniqueName(filterHier.uniqueName, expectedDim);
                        if (expectedHier != null && dimHier[1] != null && !expectedHier.equalsIgnoreCase(dimHier[1])) {
                            throw new AiValidationException(
                                    fieldPath + ".members[" + mi + "]",
                                    "Member '" + mref + "' belongs to hierarchy '" + dimHier[1]
                                            + "' under dimension '" + dimHier[0]
                                            + "', but the filter declares hierarchy '" + expectedHier
                                            + "'. Supply a member from hierarchy '" + expectedHier + "'.",
                                    null);
                        }
                    }
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
                    // Mondrian's `:` range operator requires both endpoints
                    // at the same level. Heterogeneous depths (Year :
                    // Quarter) make Mondrian crash with "Internal error"
                    // (saiku#802). Skip key-form refs (same caveat as
                    // saiku#790's level-depth check).
                    String mA = members.get(0);
                    String mB = members.get(1);
                    if (mA != null && mB != null && mA.indexOf("&[") < 0 && mB.indexOf("&[") < 0) {
                        int depthA = countBracketedSegments(mA);
                        int depthB = countBracketedSegments(mB);
                        if (depthA != depthB) {
                            throw new AiValidationException(
                                    fieldPath + ".members",
                                    "'between' endpoints must be at the same level. "
                                            + "Member 0 has " + depthA + " bracketed segments, member 1 has "
                                            + depthB + ".",
                                    null);
                        }
                    }
                    // Standard MDX range: m1 : m2 returns the set of
                    // members from m1 to m2 inclusive at their common
                    // level. Mondrian's slicer accepts the bare range.
                    expr = "{" + mA + " : " + mB + "}";
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
                    // Reject `members[]` being populated alongside a
                    // relative preset — it's silently dropped otherwise,
                    // and an agent that supplied both is almost certainly
                    // confused about which one will take effect.
                    if (!members.isEmpty()) {
                        throw new AiValidationException(
                                fieldPath + ".members",
                                "'relative' filter doesn't read `members[]`. "
                                        + "Either drop the relative preset and use op=in with the members, "
                                        + "or drop the members and rely on the preset (value + n).",
                                null);
                    }
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
        String slicer = s.toString();
        // If there are ≥2 filters AND any of them is a set, the bare
        // tuple-of-sets form '({set1}, {set2})' is illegal — Mondrian
        // returns "No function matches signature '(<Set>, <Set>)'"
        // (saiku#801). Rewrite to a single-element slicer over the
        // CROSSJOIN of the constituent sets, which Mondrian implicitly
        // aggregates the same way a single set is implicitly aggregated.
        // Single-filter sets keep the bare form to avoid the iter-1
        // digit-only-leaves parser quirk.
        if (filters.size() >= 2 && slicerHasSet(filters)) {
            StringBuilder cj = new StringBuilder();
            cj.append("CROSSJOIN(");
            // The slicer we just built is '(e1, e2, ..., eN)'.
            // Strip the outer parens and reassemble as
            // CROSSJOIN(CROSSJOIN(e1, e2), e3, ...) — Mondrian's
            // CROSSJOIN is binary, so left-nest for N≥3 (mirroring the
            // rows-axis pattern). For N=2 the simple form works.
            String inner = slicer.substring(1, slicer.length() - 1);
            String[] parts = splitTopLevelCommas(inner);
            if (parts.length == 1) {
                return slicer;
            }
            StringBuilder ch = new StringBuilder(parts[0]);
            for (int k = 1; k < parts.length; k++) {
                ch.insert(0, "CROSSJOIN(");
                ch.append(", ").append(parts[k]).append(")");
            }
            return "(" + ch.toString() + ")";
        }
        return slicer;
    }

    /** Returns true when any filter in the list will emit a set
     *  expression. Mirrors the isSet decisions inside the switch. */
    private static boolean slicerHasSet(List<AiFilterSelection> filters) {
        for (AiFilterSelection f : filters) {
            String op = f.getOp() == null ? "in" : f.getOp().toLowerCase();
            List<String> members = f.getMembers() == null ? java.util.Collections.emptyList() : f.getMembers();
            switch (op) {
                case "in":
                    if (members.size() > 1) return true;
                    break;
                case "not_in":
                case "between":
                    return true;
                case "relative":
                    if (isRelativeSet(f)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /** Split a string like 'a, b, c' on top-level commas — commas inside
     *  brackets or parentheses are preserved. Needed because slicer
     *  elements can themselves contain commas (Aggregate({a, b})). */
    private static String[] splitTopLevelCommas(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        return out.toArray(new String[0]);
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
                int n = f.getN();
                if (n <= 0) {
                    // Silently coercing n<=0 to 1 was masking agent typos.
                    // The presets require a positive count.
                    throw new AiValidationException(
                            fieldPath + ".n", "'" + preset + "' requires n >= 1. Got " + n + ".", null);
                }
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

        // First two segments of the ref must match the declared dim and hier
        // (saiku#798, saiku#799). Without this, refs from a wrong dim or wrong
        // hierarchy-within-the-dim are spliced silently into the MDX and the
        // declared dim/hier/level fields are ignored.
        String[] dimHier = firstTwoSegments(memberRef);
        if (hier != null && dimHier != null && dimHier[0] != null) {
            String expectedDim = extractDimFromHierarchyUniqueName(hier.uniqueName);
            if (expectedDim != null && !expectedDim.equalsIgnoreCase(dimHier[0])) {
                throw new AiValidationException(
                        fieldPath,
                        "Member '" + memberRef + "' belongs to dimension '"
                                + dimHier[0] + "', but the axis declares dimension '"
                                + expectedDim + "'. Move the member into a filter "
                                + "on its own dimension, or supply a member from '"
                                + expectedDim + "'.",
                        null);
            }
            String expectedHier = extractHierFromHierarchyUniqueName(hier.uniqueName, expectedDim);
            if (expectedHier != null && dimHier[1] != null && !expectedHier.equalsIgnoreCase(dimHier[1])) {
                throw new AiValidationException(
                        fieldPath,
                        "Member '" + memberRef + "' belongs to hierarchy '"
                                + dimHier[1] + "' under dimension '" + dimHier[0]
                                + "', but the axis declares hierarchy '" + expectedHier
                                + "'. Supply a member from hierarchy '" + expectedHier + "'.",
                        null);
            }
        }

        int segments = countBracketedSegments(memberRef);
        if (segments < 2) {
            // A bracketed ref needs at least [Dim].[Member] or
            // [Dim].[Hier].[Member]; a single-segment ref ([Product]) is the
            // dimension itself, not a member. Reject with a clearer message
            // than the depth-mismatch path would emit (depth -1 is mechanically
            // correct but cryptic).
            throw new AiValidationException(
                    fieldPath,
                    "Member '" + memberRef + "' is the dimension itself, not a member. "
                            + "Member refs need at least [Dim].[Member] or [Dim].[Hier].[Member].",
                    null);
        }
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

    /** Return the content of the LAST bracketed segment of a member ref,
     *  lowercased, with the leading {@code &} stripped if present.
     *  Used by the filter dedupe (saiku#804): two refs that share the same
     *  leaf segment within one filter's members[] resolve to the same
     *  member (because filter members are constrained to a single level).
     *
     *  Examples:
     *    [Time].[Time].[1997]               → "1997"
     *    [Time].[Time].[Year].&[1997]       → "1997"
     *    [Promotion].[Media Type].[No Media] → "no media"
     */
    private static String lastBracketSegmentLower(String ref) {
        if (ref == null) return "";
        int close = ref.lastIndexOf(']');
        if (close < 0) return ref.toLowerCase(java.util.Locale.ROOT);
        int open = ref.lastIndexOf('[', close - 1);
        if (open < 0) return ref.toLowerCase(java.util.Locale.ROOT);
        return ref.substring(open + 1, close).toLowerCase(java.util.Locale.ROOT);
    }

    private static String firstBracketedSegment(String ref) {
        if (ref == null) return null;
        int open = ref.indexOf('[');
        if (open < 0) return null;
        int close = ref.indexOf(']', open + 1);
        if (close < 0) return null;
        return ref.substring(open + 1, close);
    }

    private static String extractDimFromHierarchyUniqueName(String hierUniqueName) {
        if (hierUniqueName == null) return null;
        // Pattern: [Dim].[Hier]  OR  [Dim] when hier name == dim name.
        int open = hierUniqueName.indexOf('[');
        if (open < 0) return null;
        int close = hierUniqueName.indexOf(']', open + 1);
        if (close < 0) return null;
        return hierUniqueName.substring(open + 1, close);
    }

    /** Returns the hierarchy name from a "[Dim].[Hier]" unique name, or null
     *  when the hier name equals the dim name (Mondrian "default hierarchy"
     *  convention with single-segment unique names). */
    private static String extractHierFromHierarchyUniqueName(String hierUniqueName, String dimName) {
        if (hierUniqueName == null) return null;
        int firstClose = hierUniqueName.indexOf(']');
        if (firstClose < 0) return null;
        int dot = hierUniqueName.indexOf('.', firstClose);
        if (dot < 0) return dimName; // single-segment form
        int open = hierUniqueName.indexOf('[', dot);
        int close = hierUniqueName.indexOf(']', open + 1);
        if (open < 0 || close < 0) return dimName;
        return hierUniqueName.substring(open + 1, close);
    }

    /** Returns the first and (optionally) second bracketed segments of a
     *  member ref. Second segment is null when there's only one. */
    private static String[] firstTwoSegments(String ref) {
        if (ref == null) return null;
        int o1 = ref.indexOf('[');
        if (o1 < 0) return null;
        int c1 = ref.indexOf(']', o1 + 1);
        if (c1 < 0) return null;
        String first = ref.substring(o1 + 1, c1);
        int o2 = ref.indexOf('[', c1 + 1);
        if (o2 < 0) return new String[] {first, null};
        int c2 = ref.indexOf(']', o2 + 1);
        if (c2 < 0) return new String[] {first, null};
        return new String[] {first, ref.substring(o2 + 1, c2)};
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

    /**
     * Reject exact-duplicate axis selections (same dimension + hierarchy +
     * level + members[]). Mondrian doesn't dedupe them — the result has the
     * row/column emitted twice, which is useless and confusing for the agent
     * (saiku#797). Companion to the same-hierarchy-twice filter check and
     * the duplicate-measures check.
     */
    private static void rejectDuplicateAxisEntries(List<AiAxisSelection> axes, String fieldPrefix) {
        if (axes == null || axes.size() < 2) return;
        java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
        for (int i = 0; i < axes.size(); i++) {
            AiAxisSelection a = axes.get(i);
            if (a == null) continue;
            String sig = axisSignature(a);
            Integer prev = seen.put(sig, i);
            if (prev != null) {
                throw new AiValidationException(
                        fieldPrefix,
                        "Duplicate axis selection at " + fieldPrefix + "[" + prev
                                + "] and " + fieldPrefix + "[" + i + "]. "
                                + "Each row/column axis entry should be unique.",
                        null);
            }
        }
    }

    private static String axisSignature(AiAxisSelection a) {
        StringBuilder s = new StringBuilder();
        s.append(AiSchema.key(a.getDimension())).append('|');
        s.append(AiSchema.key(a.getHierarchy())).append('|');
        s.append(AiSchema.key(a.getLevel())).append('|');
        if (a.getMembers() != null) {
            for (String m : a.getMembers()) {
                s.append(m == null ? "" : m).append(',');
            }
        }
        return s.toString();
    }
}
