/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.metadata.NamedList;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.ThinSelection;

/**
 * Merge a list of {@link AiFilterSelection}s into a previously-loaded
 * {@link ThinQuery}. Used by the dashboard layer to apply runtime filters
 * to reference-bound tiles (saved {@code .saiku} queries).
 *
 * <p>Resolution rules (mirroring user intent for dashboard chips):
 * <ol>
 *   <li>A filter whose dim/hier/level doesn't resolve in the saved query's
 *       cube schema is dropped silently (multi-cube dashboards may carry
 *       filters that only apply to a subset of tiles).</li>
 *   <li>If the filter's hierarchy already appears on <em>any</em> axis of
 *       the saved query (FILTER, ROWS, COLUMNS, PAGES) — whether the axis
 *       declares an INCLUSION or EXCLUSION selection — the filter
 *       <em>replaces that selection in place</em> with an INCLUSION list
 *       of the supplied members. This is the typical case: a saved query
 *       has "Product Family" on rows showing all 3 members, and the
 *       dashboard chip "Product Family = Drink" narrows the rows axis to
 *       just Drink instead of trying to add a duplicate slicer (which
 *       Mondrian rejects).</li>
 *   <li>If the hierarchy isn't anywhere on the query, the filter is added
 *       to the FILTER (slicer) axis with the supplied level + members.</li>
 * </ol>
 *
 * <p>MDX-mode ThinQueries are passed through unchanged — we can't safely
 * splice a WHERE clause into a hand-written MDX string. The dashboard
 * filter bar still works for QUERYMODEL-mode saved queries (the Workspace's
 * "Save" action emits QUERYMODEL).
 *
 * <p>Stateless and pure — no metadata service, no olap4j live calls.
 * Tests construct an {@link AiSchema} directly.
 */
public final class ThinQueryFilterMerge {

    private ThinQueryFilterMerge() {}

    /**
     * Apply the supplied dashboard filters to {@code tq} in-place. Best-effort: filters that don't
     * resolve (MDX-mode query, unknown dim/hier/level, empty members) are dropped silently — correct
     * for optional dashboard chips. Safe to call with null/empty {@code filters} (no-op).
     */
    public static void apply(ThinQuery tq, List<AiFilterSelection> filters, AiSchema schema) {
        // Best-effort callers don't care which filters were dropped.
        applyReportingUnapplied(tq, filters, schema);
    }

    /**
     * Strict variant for <em>forced RLS filters</em> (saiku#1104): apply what can be applied and
     * return the sublist that could NOT be — an MDX-mode query (can't splice a WHERE into hand-written
     * MDX), a dim/hier/level that doesn't resolve in the cube, or empty members. The caller MUST
     * <strong>fail closed</strong> when the returned list is non-empty: a forced RLS filter that
     * didn't apply means the query would run <em>unfiltered</em>, which is exactly the leak RLS
     * exists to prevent. Behaviour-identical to {@link #apply} for the filters that DO apply.
     *
     * @return the filters that were not applied (empty when all applied); never null
     */
    public static List<AiFilterSelection> applyReportingUnapplied(
            ThinQuery tq, List<AiFilterSelection> filters, AiSchema schema) {
        List<AiFilterSelection> unapplied = new ArrayList<>();
        if (filters == null || filters.isEmpty()) return unapplied;
        // MDX-mode or model-less queries can't take a spliced slicer at all — every filter is unapplied.
        ThinQueryModel model = (tq == null || tq.getType() == ThinQuery.Type.MDX) ? null : tq.getQueryModel();
        for (AiFilterSelection f : filters) {
            if (f == null) continue;
            if (schema == null || model == null || !applyOne(model, f, schema)) {
                unapplied.add(f);
            }
        }
        return unapplied;
    }

    /** Apply one filter to the model. Returns true iff it resolved and was spliced onto an axis / the slicer. */
    private static boolean applyOne(ThinQueryModel model, AiFilterSelection f, AiSchema schema) {
        if (f.getMembers() == null || f.getMembers().isEmpty()) return false;
        AiSchema.Hierarchy resolvedHier = resolveHierarchy(schema, f.getDimension(), f.getHierarchy());
        if (resolvedHier == null) return false;
        AiSchema.Level resolvedLevel = resolveLevel(resolvedHier, f.getLevel());
        if (resolvedLevel == null) return false;

        // Try to find the hierarchy on any existing axis and rewrite its selection in place; else
        // add to the FILTER (slicer) axis.
        if (rewriteExistingAxisSelection(model, resolvedHier, resolvedLevel, f.getMembers())) {
            return true;
        }
        ThinAxis filterAxis = ensureFilterAxis(model);
        replaceOrAppendHierarchy(filterAxis, resolvedHier, resolvedLevel, f.getMembers());
        return true;
    }

    /* ---------------------------- resolution ---------------------------- */

    private static AiSchema.Hierarchy resolveHierarchy(AiSchema schema, String dimName, String hierName) {
        if (dimName == null) return null;
        String dimK = AiSchema.key(dimName);
        AiSchema.Dimension d = schema.dimensions.get(dimK);
        if (d == null) {
            String alias = schema.dimensionAliases.get(dimK);
            if (alias != null) d = schema.dimensions.get(alias);
        }
        if (d == null) return null;
        if (hierName == null || hierName.isEmpty()) {
            if (d.hierarchies.size() != 1) return null;
            return d.hierarchies.values().iterator().next();
        }
        String hierK = AiSchema.key(hierName);
        AiSchema.Hierarchy h = d.hierarchies.get(hierK);
        if (h == null) {
            String alias = d.hierarchyAliases.get(hierK);
            if (alias != null) h = d.hierarchies.get(alias);
        }
        return h;
    }

    private static AiSchema.Level resolveLevel(AiSchema.Hierarchy h, String levelName) {
        if (h == null || levelName == null) return null;
        String lvlK = AiSchema.key(levelName);
        AiSchema.Level l = h.levels.get(lvlK);
        if (l == null) {
            String alias = h.levelAliases.get(lvlK);
            if (alias != null) l = h.levels.get(alias);
        }
        return l;
    }

    /* ------------------------------ axes -------------------------------- */

    /**
     * Walk every axis on the model. If any of them carries the target
     * hierarchy (matched by MDX unique name), replace the matching level's
     * selection in place with an INCLUSION list of the supplied members and
     * return true. The axis's other levels / sorts / aggregators are left
     * untouched.
     *
     * <p>If the hierarchy is on the axis but a different level is the
     * active one, we still update — the axis will gain a level entry for
     * the dashboard filter's level (the saved query's original axis level
     * stays where it was; Mondrian handles co-existing levels of the same
     * hierarchy fine, since they cascade as parent/child sets).
     */
    private static boolean rewriteExistingAxisSelection(
            ThinQueryModel model, AiSchema.Hierarchy hier, AiSchema.Level level, List<String> members) {
        if (model.getAxes() == null) return false;
        for (ThinAxis axis : model.getAxes().values()) {
            if (axis == null || axis.getHierarchies() == null) continue;
            for (ThinHierarchy th : axis.getHierarchies()) {
                if (th.getName() == null || !th.getName().equals(hier.uniqueName)) continue;
                // Hierarchy match — narrow/add level selection in place.
                ThinSelection selection = new ThinSelection(ThinSelection.Type.INCLUSION, buildMembers(members));
                Map<String, ThinLevel> levels = th.getLevels();
                if (levels == null) {
                    levels = new LinkedHashMap<>();
                    th.setLevels(levels);
                }
                levels.put(level.name, new ThinLevel(level.name, level.name, selection, new ArrayList<>()));
                return true;
            }
        }
        return false;
    }

    private static ThinAxis ensureFilterAxis(ThinQueryModel model) {
        ThinAxis existing = model.getAxis(AxisLocation.FILTER);
        if (existing != null) return existing;
        NamedList<ThinHierarchy> empty = new NamedListImpl<>();
        ThinAxis fresh = new ThinAxis(AxisLocation.FILTER, empty, false, new ArrayList<>());
        model.getAxes().put(AxisLocation.FILTER, fresh);
        return fresh;
    }

    private static void replaceOrAppendHierarchy(
            ThinAxis filterAxis, AiSchema.Hierarchy hier, AiSchema.Level level, List<String> memberUniqueNames) {
        ThinSelection selection = new ThinSelection(ThinSelection.Type.INCLUSION, buildMembers(memberUniqueNames));
        ThinLevel thinLevel = new ThinLevel(level.name, level.name, selection, new ArrayList<>());
        Map<String, ThinLevel> levels = new LinkedHashMap<>();
        levels.put(level.name, thinLevel);

        // Replace existing entry on this axis for the same hierarchy, if
        // any — else append. Mondrian rejects duplicate hierarchies in
        // a slicer; the duplicate guard is mandatory.
        for (int i = 0; i < filterAxis.getHierarchies().size(); i++) {
            ThinHierarchy existing = filterAxis.getHierarchies().get(i);
            if (existing.getName() != null && existing.getName().equals(hier.uniqueName)) {
                filterAxis.getHierarchies().set(i, new ThinHierarchy(hier.uniqueName, hier.name, hier.name, levels));
                return;
            }
        }
        filterAxis.getHierarchies().add(new ThinHierarchy(hier.uniqueName, hier.name, hier.name, levels));
    }

    private static List<ThinMember> buildMembers(List<String> uniqueNames) {
        List<ThinMember> out = new ArrayList<>(uniqueNames.size());
        for (String un : uniqueNames) {
            if (un == null || un.isEmpty()) continue;
            // Caption mirrors the unique name — the slicer renderer reads
            // uniqueName for MDX construction; the caption is informational
            // for the UI which doesn't render slicer captions for tiles.
            out.add(new ThinMember(un, un, un));
        }
        return out;
    }
}
