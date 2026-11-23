/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License 2.0.
 */
package org.saiku.service.olap.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.ThinSelection;

/**
 * The per-field scope of a saved query's authored FILTER (slicer) axis, used to gate
 * client-supplied filter overrides on a surface that has NO other declared-target list
 * (the bare saved-query embed, {@code POST /saiku/api/embed/query/{path}} — saiku#1946).
 *
 * <p>Why this exists: the dashboard / app tile embed paths gate a guest's runtime filter
 * overrides against targets the AUTHOR declared on the pinned document ({@code type:"filter"}
 * tiles, the filter panel) — see {@code EmbedViewResource.declaredTargetsFor*}. A bare saved-query
 * embed has no such declaration, so nothing stopped a guest from re-pointing an arbitrary
 * non-forced axis at arbitrary members, or from ADDING a deeper level beside an authored rows
 * level, surfacing finer-grain rows the author never meant to publish (presentation-scope
 * over-exposure, CWE-863). The saved query's own slicer IS the author's declaration, so it is
 * what we scope against.
 *
 * <p>The rule is deliberately <b>narrow-only</b>:
 * <ol>
 *   <li>An override survives only if its hierarchy is ALREADY on the saved query's FILTER axis
 *       and its level is one of that hierarchy's authored levels. Anything else — a rows/columns/
 *       pages hierarchy, a brand-new hierarchy, or a different level of an authored hierarchy —
 *       is dropped fail-closed.</li>
 *   <li>A surviving override's members are INTERSECTED with the authored selection on that level,
 *       so a guest can only narrow inside the authored slice, never widen, re-point, or swap the
 *       operator. The intersection keeps the CLIENT's order and drops every out-of-scope member.</li>
 *   <li>An empty intersection drops the override entirely, so the query keeps the AUTHORED
 *       selection for that axis. It never degrades to "no selection" — an empty member list reads
 *       as <em>unrestricted</em> in {@link ThinQueryFilterMerge} and would be exactly the
 *       over-exposure this class exists to prevent.</li>
 * </ol>
 *
 * <p>Scope is matched on the HIERARCHY alone (the names the saved query already stores), not on the
 * client's {@code dimension} — {@code ThinHierarchy.dimension} is not reliably the display
 * dimension name (splices write the hierarchy name into it), so a strict dimension compare would
 * drop legitimate narrowings. Leaving the dimension unchecked cannot over-expose: this class only
 * admits hierarchies the author put on the slicer, and a slicer entry can only ever RESTRICT a
 * result, whereas the over-exposure being guarded (re-pointing a rows / columns / pages axis, or
 * adding a deeper level beside an authored one) lives on those axes — which the gate excludes
 * outright.
 *
 * <p>Fail-closed by construction: an MDX-mode query (no query model to scope against), a
 * parameterised slicer, a null/empty selection, an unparseable override, or a hierarchy string
 * that matches more than one authored hierarchy all yield an empty (or fully dropped) result.
 * For the RLS axis this is additive defence-in-depth only — forced filters ride their own
 * channel and are clamped by {@code ThinQueryFilterMerge.applyReportingUnapplied}.
 *
 * <p>Stateless and pure — no metadata service, no olap4j live calls. Matching is done on the
 * names the saved query already stores (hierarchy unique name / caption / name, level name /
 * caption), normalised case-insensitively, so no schema lookup is needed to decide.
 */
public final class SavedQueryFilterScope {

    private SavedQueryFilterScope() {}

    /**
     * Reduce client-supplied filter overrides to the subset the saved query's authored FILTER axis
     * authorises, narrowed to the authored members. Never returns null; a null / unscopeable query
     * or no overrides yields an empty list.
     *
     * @param tq the pinned saved query (its FILTER axis is the scope); null ⇒ empty
     * @param overrides the client filter payload; null / empty ⇒ empty
     * @return the authorised, member-narrowed overrides (client order preserved)
     */
    public static List<AiFilterSelection> narrowToAuthoredScope(ThinQuery tq, List<AiFilterSelection> overrides) {
        List<AiFilterSelection> out = new ArrayList<>();
        if (tq == null || overrides == null || overrides.isEmpty()) return out;
        Map<String, AuthoredHierarchy> scope = authoredFilterScope(tq);
        if (scope.isEmpty()) return out; // nothing authored on the slicer ⇒ nothing is overridable
        for (AiFilterSelection o : overrides) {
            if (o == null) continue;
            AuthoredHierarchy authored = resolve(scope, o.getHierarchy());
            if (authored == null) continue; // not a declared slicer hierarchy (or ambiguous) ⇒ drop
            AuthoredLevel level = resolveLevel(authored.levels, o.getLevel());
            if (level == null) continue; // a different level of an authored hierarchy ⇒ drop
            if (level.members.isEmpty()) continue; // parameterised / unselected ⇒ nothing to narrow within
            List<String> narrowed = intersect(o.getMembers(), level.members);
            if (narrowed.isEmpty()) continue; // wholly out of the authored slice ⇒ authored selection stands
            // Only a plain member-set inclusion can be proven non-widening here; any other operator
            // (not_in / between / relative / …) is dropped rather than trusted.
            if (!isInOp(o)) continue;
            out.add(new AiFilterSelection(o.getDimension(), o.getHierarchy(), o.getLevel(), narrowed));
        }
        return out;
    }

    /**
     * The saved query's authored FILTER-axis hierarchies, keyed by every name form a client may
     * legitimately use (unique name, caption, name), normalised case-insensitively. Empty for an
     * MDX-mode query, a query with no model, or a query with no slicer axis.
     */
    private static Map<String, AuthoredHierarchy> authoredFilterScope(ThinQuery tq) {
        Map<String, AuthoredHierarchy> out = new LinkedHashMap<>();
        ThinQueryModel model = tq.getType() == ThinQuery.Type.MDX ? null : tq.getQueryModel();
        if (model == null || model.getAxes() == null) return out;
        ThinAxis filter = model.getAxis(AxisLocation.FILTER);
        if (filter == null || filter.getHierarchies() == null) return out;
        for (ThinHierarchy th : filter.getHierarchies()) {
            if (th == null) continue;
            AuthoredHierarchy h = new AuthoredHierarchy();
            register(out, th, h);
            collectLevels(th, h);
        }
        return out;
    }

    /** Register a hierarchy under each of its name forms. A name form already claimed by a
     *  DIFFERENT hierarchy marks both ambiguous, so the key can never authorise an override. */
    private static void register(Map<String, AuthoredHierarchy> out, ThinHierarchy th, AuthoredHierarchy h) {
        for (String key : names(th.getName(), th.getCaption())) {
            AuthoredHierarchy prev = out.putIfAbsent(key, h);
            if (prev != null && prev != h) {
                prev.ambiguous = true;
                h.ambiguous = true;
            }
        }
    }

    private static void collectLevels(ThinHierarchy th, AuthoredHierarchy h) {
        Map<String, ThinLevel> levels = th.getLevels();
        if (levels == null) return;
        for (ThinLevel tl : levels.values()) {
            if (tl == null) continue;
            AuthoredLevel l = new AuthoredLevel();
            Set<String> members = new HashSet<>();
            ThinSelection selection = tl.getSelection();
            // Only an INCLUSION selection with concrete members is a scope we can intersect within.
            // EXCLUSION (author already showed "everything but") and parameterised (no member list)
            // selections give us no ceiling, so they are recorded with NO members ⇒ any override
            // on that level is dropped.
            if (selection != null
                    && selection.getType() == ThinSelection.Type.INCLUSION
                    && selection.getMembers() != null
                    && selection.getParameterName() == null) {
                for (ThinMember m : selection.getMembers()) {
                    if (m != null
                            && m.getUniqueName() != null
                            && !m.getUniqueName().isEmpty()) {
                        members.add(m.getUniqueName());
                    }
                }
            }
            l.members.addAll(members);
            for (String key : names(tl.getName(), tl.getCaption())) {
                AuthoredLevel prev = h.levels.putIfAbsent(key, l);
                if (prev != null && prev != l) {
                    // Two authored levels share a name: ambiguous ⇒ any override on it is dropped.
                    prev.ambiguous = true;
                    l.ambiguous = true;
                }
            }
        }
    }

    /** Case-insensitive, trimmed key set for one name form (skipping blanks). */
    private static List<String> names(String... raw) {
        List<String> out = new ArrayList<>(2);
        for (String s : raw) {
            if (s == null) continue;
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    /** The single authored hierarchy a client-supplied name resolves to, or null when none /
     *  ambiguous. */
    private static AuthoredHierarchy resolve(Map<String, AuthoredHierarchy> scope, String name) {
        if (name == null) return null;
        AuthoredHierarchy h = scope.get(name.trim().toLowerCase(Locale.ROOT));
        if (h == null || h.ambiguous) return null;
        return h;
    }

    /** The single authored level a client-supplied name resolves to, or null when none / ambiguous. */
    private static AuthoredLevel resolveLevel(Map<String, AuthoredLevel> levels, String name) {
        if (name == null) return null;
        AuthoredLevel l = levels.get(name.trim().toLowerCase(Locale.ROOT));
        return (l == null || l.ambiguous) ? null : l;
    }

    /** Client members that are ALSO authored on the level (exact match on MDX unique names),
     *  preserving the client's order. Anything the author didn't declare is dropped — the RLS-style
     *  clamp, and here the presentation-scope clamp. */
    private static List<String> intersect(List<String> client, Set<String> authored) {
        if (client == null || client.isEmpty() || authored.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String m : client) {
            if (m != null && authored.contains(m) && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /** A filter is only narrow-provable when it is a plain member-set inclusion. */
    private static boolean isInOp(AiFilterSelection f) {
        String op = f.getOp();
        return op == null || op.isBlank() || "in".equalsIgnoreCase(op);
    }

    /** One authored slicer hierarchy, with the keys it answers to and its authored levels. */
    private static final class AuthoredHierarchy {
        private final Map<String, AuthoredLevel> levels = new LinkedHashMap<>();
        private boolean ambiguous;
    }

    /** One authored level of a slicer hierarchy: the members the author published on it. */
    private static final class AuthoredLevel {
        private final Set<String> members = new HashSet<>();
        private boolean ambiguous;
    }
}
