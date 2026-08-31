/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.totals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.olap4j.Axis;
import org.olap4j.CellSetAxis;
import org.olap4j.CellSetAxisMetaData;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.NamedList;

/**
 * saiku#1884 — {@link org.olap4j.CellSetAxisMetaData#getHierarchies()} can report a null entry on
 * Mondrian 4 schemas converted from Mondrian 3, and {@link AxisInfo} used to NPE on it, turning
 * every query against the cube into an "Internal error".
 *
 * <p>Covers the three shapes: a null slot resolvable from the members at that position, complete
 * metadata (the hot path — behavior unchanged), and a null slot no member can fill (levelCount
 * falls back to deepest-observed-depth + 1, i.e. 0 when nothing is observed at that index).
 */
public class AxisInfoTest {

    /* ------------------------------ fixtures ------------------------------ */

    private final Dimension measDim = proxy(Dimension.class, map("getName", "Measures"));
    private final Level measLevel = level("MeasuresLevel", "[Measures].[MeasuresLevel]", 0);
    private final Hierarchy measH = hierarchy("Measures", "[Measures]", measDim, measLevel);

    private final Dimension productDim = proxy(Dimension.class, map("getName", "Product"));
    private final Level allLevel = level("(All)", "[Product].[Products].[(All)]", 0);
    private final Level familyLevel = level("Product Family", "[Product].[Products].[Product Family]", 1);
    private final Level deptLevel = level("Product Department", "[Product].[Products].[Product Department]", 2);
    private final Hierarchy productH =
            hierarchy("Products", "[Product].[Products]", productDim, allLevel, familyLevel, deptLevel);

    private final Member storeSales = member("Store Sales", "[Measures].[Store Sales]", measDim, measH, measLevel, 0);
    private final Member drink = member("Drink", "[Product].[Products].[Drink]", productDim, productH, familyLevel, 1);
    private final Member beverages =
            member("Beverages", "[Product].[Products].[Drink].[Beverages]", productDim, productH, deptLevel, 2);

    private List<Position> twoHierarchyPositions() {
        return Arrays.asList(pos(storeSales, drink), pos(storeSales, beverages));
    }

    /* -------------------------------- tests ------------------------------- */

    @Test
    public void nullMetadataSlotIsResolvedFromTheMembersAtThatPosition() {
        // Metadata reports [Measures, null] — the product slot has no metadata
        // counterpart, but every position carries a product member at index 1.
        CellSetAxis axis = axis(hierarchyList(measH, null), twoHierarchyPositions());

        AxisInfo info = new AxisInfo(axis); // pre-fix: NPE right here

        // The slot resolved to the member's hierarchy: the unique level names
        // are read off that hierarchy's own level list.
        assertTrue(
                info.uniqueLevelNames.contains(familyLevel.getUniqueName()),
                "Family level resolved via the member's hierarchy: " + info.uniqueLevelNames);
        assertTrue(
                info.uniqueLevelNames.contains(deptLevel.getUniqueName()),
                "Department level resolved via the member's hierarchy: " + info.uniqueLevelNames);
        assertEquals(0, info.measuresMember, "Measures member index");
    }

    @Test
    public void completeMetadataBehavesAsBefore() {
        // Hot path: no null slot, so resolution never walks the positions and
        // the result is identical to what complete metadata always produced.
        CellSetAxis complete = axis(hierarchyList(measH, productH), twoHierarchyPositions());
        AxisInfo info = new AxisInfo(complete);

        assertEquals(
                Arrays.asList(measLevel.getUniqueName(), familyLevel.getUniqueName(), deptLevel.getUniqueName()),
                info.uniqueLevelNames,
                "uniqueLevelNames populated normally");
        assertEquals(3, info.maxDepth, "one measure level + two product levels");
        assertEquals(0, info.measuresMember, "Measures member index");

        // And the null-slot axis resolves to exactly the same shape.
        AxisInfo resolved = new AxisInfo(axis(hierarchyList(measH, null), twoHierarchyPositions()));
        assertEquals(info.uniqueLevelNames, resolved.uniqueLevelNames, "resolved slot matches complete metadata");
        assertEquals(info.maxDepth, resolved.maxDepth, "maxDepth matches complete metadata");
        assertEquals(
                info.fullPositions.size(), resolved.fullPositions.size(), "fullPositions matches complete metadata");
    }

    @Test
    public void unresolvableNullSlotStillConstructs() {
        // Metadata reports [Measures, null] but no position has a member at
        // index 1, so nothing can fill the slot: levelCount falls back to
        // deepest-observed-depth + 1 = 0 and the slot is simply skipped.
        List<Position> measureOnly = Arrays.asList(pos(storeSales));
        CellSetAxis axis = axis(hierarchyList(measH, null), measureOnly);

        AxisInfo info = new AxisInfo(axis); // no NPE

        assertEquals(
                Collections.singletonList(measLevel.getUniqueName()),
                info.uniqueLevelNames,
                "still-null slot contributes no level names");
        assertTrue(info.levels[1].isEmpty(), "no level slots reserved for the unresolved hierarchy");
        assertEquals(1, info.maxDepth, "only the measures level counts");
        assertEquals(1, info.fullPositions.size(), "the measure position is still full");
    }

    /* ------------------------------ helpers ------------------------------- */

    private static CellSetAxis axis(NamedList<Hierarchy> hierarchies, List<Position> positions) {
        CellSetAxisMetaData meta = proxy(CellSetAxisMetaData.class, map("getHierarchies", hierarchies));
        return proxy(
                CellSetAxis.class,
                map(
                        "getAxisOrdinal",
                        Axis.ROWS,
                        "getPositions",
                        positions,
                        "getPositionCount",
                        positions.size(),
                        "getAxisMetaData",
                        meta));
    }

    private static Hierarchy hierarchy(String name, String uniqueName, Dimension d, Level... levels) {
        Map<String, Object> answers =
                map("getName", name, "getCaption", name, "getUniqueName", uniqueName, "getDimension", d);
        Hierarchy h = proxy(Hierarchy.class, answers);
        answers.put("getLevels", namedList(Arrays.asList(levels)));
        for (Level l : levels) {
            levelAnswers.get(l).put("getHierarchy", h);
        }
        return h;
    }

    private static final Map<Level, Map<String, Object>> levelAnswers = new HashMap<>();

    private static Level level(String name, String uniqueName, int depth) {
        Map<String, Object> answers =
                map("getName", name, "getCaption", name, "getUniqueName", uniqueName, "getDepth", depth);
        Level l = proxy(Level.class, answers);
        levelAnswers.put(l, answers);
        return l;
    }

    private static Member member(String caption, String uniqueName, Dimension d, Hierarchy h, Level l, int depth) {
        return proxy(
                Member.class,
                map(
                        "getCaption", caption,
                        "getName", caption,
                        "getUniqueName", uniqueName,
                        "getDimension", d,
                        "getHierarchy", h,
                        "getLevel", l,
                        "getDepth", depth));
    }

    private static Position pos(Member... members) {
        return proxy(Position.class, map("getMembers", Arrays.asList(members), "getOrdinal", 0));
    }

    /** A NamedList backed by a plain list — AxisInfo only uses the List surface. */
    @SuppressWarnings("unchecked")
    private static <T> NamedList<T> namedList(List<T> backing) {
        List<T> list = new ArrayList<>(backing);
        return (NamedList<T>) Proxy.newProxyInstance(
                AxisInfoTest.class.getClassLoader(), new Class<?>[] {NamedList.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                        if (m.getDeclaringClass().isAssignableFrom(List.class)
                                || m.getDeclaringClass() == Object.class) {
                            try {
                                return m.invoke(list, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                        throw new UnsupportedOperationException("NamedList." + m.getName() + " not backed");
                    }
                });
    }

    @SafeVarargs
    private static <T> NamedList<T> hierarchyList(T... items) {
        return namedList(Arrays.asList(items));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(
                AxisInfoTest.class.getClassLoader(), new Class<?>[] {iface}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("toString".equals(m.getName())) return iface.getSimpleName() + "@fake";
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        if (answers.containsKey(m.getName())) return answers.get(m.getName());
                        return defaultReturn(m);
                    }
                });
    }

    private static Object defaultReturn(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == double.class) return 0d;
        if (rt == float.class) return 0f;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return (char) 0;
        return null;
    }
}
