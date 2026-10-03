/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinDetails;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinSelection;

/**
 * saiku#1435 — the trust boundary for Creator Mode queries.
 *
 * <p>An authoring token pins ONE cube. The creator is allowed to build a query
 * against it, but the query still arrives as JSON from a third-party host page,
 * so nothing in it may be taken on faith. This validator walks the whole query
 * model and refuses anything that either (a) escapes the pinned cube's
 * {@link AuthoringCubeCatalogue}, or (b) carries free text that the MDX
 * generator would splice in verbatim.
 *
 * <p>Refused outright, because each is a text-injection or escape channel rather
 * than a cube-scoping question:
 * <ul>
 *   <li>{@code mdx} on the query, on an axis, hierarchy or level — a raw MDX
 *       document would replace the model wholesale;</li>
 *   <li>axis / hierarchy / level {@code filters} — the filter model renders
 *       expressions into the generated MDX;</li>
 *   <li>{@code sortEvaluationLiteral} — free text spliced into ORDER BY;</li>
 *   <li>calculated members / measures and named sets — formula text;</li>
 *   <li>legacy {@code parameters} and typed {@code :name} parameters — bound
 *       into MDX after generation;</li>
 *   <li>a non-OLAP {@code queryType} (the OSSIE shelf path);</li>
 *   <li>a {@code QUERYMODEL} type missing entirely — the default must be an
 *       explicit, validated model, not an absent type.</li>
 * </ul>
 *
 * <p>What survives is exactly: axes of hierarchies/levels/members that exist in
 * the pinned cube, plus measures that exist in it. The MDX is then generated
 * server-side by the normal {@code ThinQueryService} path, so a creator can
 * never author a cube, a member, or an expression the catalogue didn't list.
 */
public final class AuthoringQueryValidator {

    /** Upper bound on member picks per level, so a hostile payload can't turn
     *  validation into an O(n) grind on a large level. */
    public static final int MAX_MEMBERS_PER_SELECTION = 500;

    /** Upper bound on levels per axis. */
    public static final int MAX_LEVELS_PER_AXIS = 20;

    private AuthoringQueryValidator() {}

    /** Outcome of a validation pass. Violations are developer-facing (logged /
     *  echoed in a 400 body); they never carry data from the pinned cube. */
    public static final class Result {
        private final List<String> violations;

        Result(List<String> violations) {
            this.violations = List.copyOf(violations);
        }

        public boolean isValid() {
            return violations.isEmpty();
        }

        public List<String> violations() {
            return violations;
        }

        /** First violation, or a generic message when there are none. */
        public String firstViolation() {
            return violations.isEmpty() ? "query rejected" : violations.get(0);
        }
    }

    /**
     * Validate {@code query} against {@code catalogue}.
     *
     * @param catalogue the pinned cube's frozen catalogue
     * @param query the client-supplied query (never mutated here)
     */
    public static Result validate(AuthoringCubeCatalogue catalogue, ThinQuery query) {
        List<String> v = new ArrayList<>();
        if (catalogue == null) {
            return new Result(List.of("no pinned cube catalogue"));
        }
        if (query == null) {
            return new Result(List.of("query is required"));
        }
        if (!ThinQuery.Type.QUERYMODEL.equals(query.getType())) {
            v.add("query type must be QUERYMODEL (MDX and untyped queries are not accepted)");
        }
        if (notBlank(query.getMdx())) {
            v.add("raw MDX is not accepted; build the query from typed selections");
        }
        if (query.getQueryType() != null && !"OLAP".equalsIgnoreCase(query.getQueryType())) {
            v.add("only OLAP queries are supported");
        }
        if (query.getParameters() != null && !query.getParameters().isEmpty()) {
            v.add("query parameters are not accepted");
        }
        if (query.getTypedParameters() != null && !query.getTypedParameters().isEmpty()) {
            v.add("typed query parameters are not accepted");
        }
        ThinQueryModel model = query.getQueryModel();
        if (model == null) {
            v.add("queryModel is required");
            return new Result(v);
        }
        if (!model.getCalculatedMembers().isEmpty() || !model.getCalculatedMeasures().isEmpty()) {
            v.add("calculated members / measures are not accepted");
        }
        if (!model.getNamedSets().isEmpty()) {
            v.add("named sets are not accepted");
        }
        for (ThinQueryModel.AxisLocation axis : ThinQueryModel.AxisLocation.values()) {
            ThinAxis a = model.getAxis(axis);
            if (a != null) {
                validateAxis(catalogue, axis, a, v);
            }
        }
        ThinDetails details = model.getDetails();
        if (details == null || details.getMeasures() == null || details.getMeasures().isEmpty()) {
            v.add("at least one measure is required");
        } else {
            for (ThinMeasure m : details.getMeasures()) {
                if (m == null || !catalogue.hasMeasure(m.getUniqueName())) {
                    v.add("measure is not part of the pinned cube");
                }
            }
        }
        return new Result(v);
    }

    private static void validateAxis(
            AuthoringCubeCatalogue catalogue, ThinQueryModel.AxisLocation axis, ThinAxis a, List<String> v) {
        if (notBlank(a.getMdx())) {
            v.add("axis " + axis + ": raw MDX is not accepted");
        }
        if (a.getFilters() != null && !a.getFilters().isEmpty()) {
            v.add("axis " + axis + ": filters are not accepted");
        }
        List<ThinHierarchy> hierarchies = a.getHierarchies();
        if (hierarchies == null || hierarchies.isEmpty()) {
            return;
        }
        if (hierarchies.size() > MAX_LEVELS_PER_AXIS) {
            v.add("axis " + axis + ": too many hierarchies");
            return;
        }
        for (ThinHierarchy h : hierarchies) {
            if (h == null) {
                v.add("axis " + axis + ": null hierarchy");
                continue;
            }
            if (notBlank(h.getMdx()) || (h.getFilters() != null && !h.getFilters().isEmpty())) {
                v.add("axis " + axis + ": filters / raw MDX are not accepted");
            }
            if (h.getSortEvaluationLiteral() != null) {
                v.add("axis " + axis + ": sort expressions are not accepted");
            }
            AuthoringCubeCatalogue.Dimension dimension = catalogue.dimensionForHierarchy(h.getName());
            if (dimension == null) {
                // A measures pseudo-axis is not a cube pivot: the workbench keys
                // the measures it selected on a "[Measures]" hierarchy, so it
                // resolves to no dimension. Accept it ONLY when every level it
                // names is a measure of the pinned cube — which is the same
                // allowlist the details block below enforces, and equally
                // unusable as a pivot.
                if (isMeasuresAxis(catalogue, h)) {
                    continue;
                }
                v.add("axis " + axis + ": hierarchy is not part of the pinned cube");
                continue;
            }
            if (h.getLevels() == null || h.getLevels().isEmpty()) {
                continue;
            }
            if (h.getLevels().size() > MAX_LEVELS_PER_AXIS) {
                v.add("axis " + axis + ": too many levels");
                continue;
            }
            for (ThinLevel l : h.getLevels().values()) {
                validateLevel(catalogue, axis, dimension, l, v);
            }
        }
    }

    /**
     * True when {@code h} names nothing but measures of the pinned cube (and at
     * least one). An empty or measure-less "hierarchy" is NOT accepted — that
     * would let a client smuggle a hierarchy the catalogue doesn't list.
     */
    private static boolean isMeasuresAxis(AuthoringCubeCatalogue catalogue, ThinHierarchy h) {
        if (h.getLevels() == null || h.getLevels().isEmpty()) {
            return false;
        }
        for (String level : h.getLevels().keySet()) {
            if (!catalogue.hasMeasure(level)) {
                return false;
            }
        }
        return true;
    }

    private static void validateLevel(
            AuthoringCubeCatalogue catalogue,
            ThinQueryModel.AxisLocation axis,
            AuthoringCubeCatalogue.Dimension dimension,
            ThinLevel l,
            List<String> v) {
        if (l == null) {
            v.add("axis " + axis + ": null level");
            return;
        }
        if (notBlank(l.getMdx()) || (l.getFilters() != null && !l.getFilters().isEmpty())) {
            v.add("axis " + axis + ": filters / raw MDX are not accepted");
        }
        if (l.getSortEvaluationLiteral() != null) {
            v.add("axis " + axis + ": sort expressions are not accepted");
        }
        boolean levelInCube = false;
        for (AuthoringCubeCatalogue.Level known : dimension.levels) {
            if (known.name.equals(l.getName())) {
                levelInCube = true;
                break;
            }
        }
        if (!levelInCube) {
            v.add("axis " + axis + ": level is not part of the pinned cube");
            return;
        }
        AuthoringCubeCatalogue.Level level = catalogue.level(l.getName());
        ThinSelection selection = l.getSelection();
        if (selection == null) {
            return;
        }
        List<ThinMember> members = selection.getMembers();
        if (members == null || members.isEmpty()) {
            return;
        }
        if (members.size() > MAX_MEMBERS_PER_SELECTION) {
            v.add("axis " + axis + ": too many members selected");
            return;
        }
        if (level != null && level.truncated) {
            // We could not enumerate this level, so we cannot vouch for any
            // member on it. Refuse rather than accept an unverified name.
            v.add("axis " + axis + ": member filtering is unavailable on this level");
            return;
        }
        for (ThinMember m : members) {
            if (m == null || !containsMember(level, m.getUniqueName())) {
                v.add("axis " + axis + ": member is not part of the pinned cube");
                return;
            }
        }
    }

    private static boolean containsMember(AuthoringCubeCatalogue.Level level, String uniqueName) {
        if (level == null || uniqueName == null) {
            return false;
        }
        for (String known : level.members) {
            if (known.equals(uniqueName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
