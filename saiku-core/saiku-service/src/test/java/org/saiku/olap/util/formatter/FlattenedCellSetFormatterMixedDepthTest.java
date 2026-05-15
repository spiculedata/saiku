/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.olap4j.Axis;
import org.olap4j.Cell;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.CellSetAxisMetaData;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.Matrix;

/**
 * saiku#788 — mixed-depth row positions must each emit a distinct matrix row
 * with the shallow-depth slots padded empty, not silently collapsed to the
 * deepest position.
 *
 * <p>Pre-fix the formatter walked {@code axis.getPositions()} and dropped any
 * position whose member sat above the max observed depth, leaking the two
 * shallower rows entirely — silent data loss. Post-fix all three positions
 * emit, with shallow slots null-padded so a Family row reads
 * {@code "Drink | (empty) | (empty) | $48,836.21"} rather than overwriting
 * a sibling.
 */
public class FlattenedCellSetFormatterMixedDepthTest {

    @Test
    public void threePositionsAtDifferentDepthsAllSurvive() {
        // depth=2 Family, depth=3 Department, depth=4 Category — three
        // positions, one member each, decreasing-deeper down the hierarchy.
        Dimension productDim = proxy(Dimension.class, map("getName", "Product"));
        Hierarchy productH = proxy(
                Hierarchy.class,
                map("getName", "Products", "getUniqueName", "[Product].[Products]", "getDimension", productDim));
        Level familyLevel = level("Product Family", "[Product].[Products].[Product Family]", productH, productDim, 2);
        Level deptLevel =
                level("Product Department", "[Product].[Products].[Product Department]", productH, productDim, 3);
        Level catLevel = level("Product Category", "[Product].[Products].[Product Category]", productH, productDim, 4);

        Member drink = member("Drink", "[Product].[Products].[Drink]", productDim, productH, familyLevel, 2);
        Member beverages =
                member("Beverages", "[Product].[Products].[Drink].[Beverages]", productDim, productH, deptLevel, 3);
        Member drinks = member(
                "Drinks", "[Product].[Products].[Drink].[Beverages].[Drinks]", productDim, productH, catLevel, 4);

        Position pDrink = pos(Collections.singletonList(drink));
        Position pBev = pos(Collections.singletonList(beverages));
        Position pDrinks = pos(Collections.singletonList(drinks));
        List<Position> rowPositions = Arrays.asList(pDrink, pBev, pDrinks);

        // Measures axis: one measure.
        Dimension measDim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy measH = proxy(
                Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", measDim));
        Level measLvl = level("MeasuresLevel", "[Measures].[MeasuresLevel]", measH, measDim, 0);
        Member storeSales = member("Store Sales", "[Measures].[Store Sales]", measDim, measH, measLvl, 0);
        Position pSales = pos(Collections.singletonList(storeSales));
        List<Position> colPositions = Collections.singletonList(pSales);

        // CellSetAxisMetaData reports the hierarchies on each axis. The
        // formatter uses .size() to size its AxisInfo.
        CellSetAxisMetaData rowAxisMeta =
                proxy(CellSetAxisMetaData.class, map("getHierarchies", Collections.singletonList(productH)));
        CellSetAxisMetaData colAxisMeta =
                proxy(CellSetAxisMetaData.class, map("getHierarchies", Collections.singletonList(measH)));

        CellSetAxis rowAxis = proxy(
                CellSetAxis.class,
                map(
                        "getAxisOrdinal",
                        Axis.ROWS,
                        "getPositions",
                        rowPositions,
                        "getPositionCount",
                        3,
                        "getAxisMetaData",
                        rowAxisMeta));
        CellSetAxis colAxis = proxy(
                CellSetAxis.class,
                map(
                        "getAxisOrdinal",
                        Axis.COLUMNS,
                        "getPositions",
                        colPositions,
                        "getPositionCount",
                        1,
                        "getAxisMetaData",
                        colAxisMeta));

        // Three cells, one per (column=Store Sales, row=position). Coordinate
        // list is [col, row] per olap4j convention.
        final Map<List<Integer>, Cell> cellByCoord = new HashMap<>();
        cellByCoord.put(Arrays.asList(0, 0), cell(48836.21, "$48,836.21", Arrays.asList(0, 0)));
        cellByCoord.put(Arrays.asList(0, 1), cell(27748.53, "$27,748.53", Arrays.asList(0, 1)));
        cellByCoord.put(Arrays.asList(0, 2), cell(5642.29, "$5,642.29", Arrays.asList(0, 2)));

        final List<CellSetAxis> axes = Arrays.asList(colAxis, rowAxis);
        InvocationHandler cellSetHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                    case "getAxes":
                        return axes;
                    case "getCell":
                        if (a != null && a.length == 1 && a[0] instanceof List) {
                            return cellByCoord.get(a[0]);
                        }
                        return null;
                    case "getFilterAxes":
                        return Collections.emptyList();
                    case "toString":
                        return "CellSet@fake";
                    case "equals":
                        return p == a[0];
                    case "hashCode":
                        return System.identityHashCode(p);
                    default:
                        return defaultReturn(m);
                }
            }
        };
        CellSet cellSet = (CellSet) Proxy.newProxyInstance(
                FlattenedCellSetFormatterMixedDepthTest.class.getClassLoader(),
                new Class<?>[] {CellSet.class},
                cellSetHandler);

        Matrix matrix = new FlattenedCellSetFormatter().format(cellSet);

        // Width = 3 row-header columns (Family/Department/Category) + 1 measure
        // column. Height = 1 column-header row + 3 data rows.
        assertEquals("matrix width = 3 row-header levels + 1 measure", 4, matrix.getMatrixWidth());
        assertEquals("matrix height = 1 header row + 3 data rows", 4, matrix.getMatrixHeight());

        // Row 1 (Family): only the first row-header column is filled with
        // "Drink", the deeper Department/Category columns are empty.
        assertEquals("Drink", captionAt(matrix, 0, 1));
        assertEquals("Department slot empty for Family row", "", captionAt(matrix, 1, 1));
        assertEquals("Category slot empty for Family row", "", captionAt(matrix, 2, 1));
        assertEquals("$48,836.21", captionAt(matrix, 3, 1));

        // Row 2 (Department): Department-slot filled with Beverages, Category
        // empty. Family slot may be auto-filled with parent Drink by the
        // formatter's parent-walk; we don't pin that — just confirm the row
        // exists and isn't collapsed into row 3.
        assertEquals("Beverages", captionAt(matrix, 1, 2));
        assertEquals("Category slot empty for Department row", "", captionAt(matrix, 2, 2));
        assertEquals("$27,748.53", captionAt(matrix, 3, 2));

        // Row 3 (Category): Category-slot filled with Drinks.
        assertEquals("Drinks", captionAt(matrix, 2, 3));
        assertEquals("$5,642.29", captionAt(matrix, 3, 3));
    }

    /* ------------------------------ helpers ------------------------------- */

    private static String captionAt(Matrix m, int x, int y) {
        AbstractBaseCell c = m.get(x, y);
        if (c == null) return null;
        String v = c.getFormattedValue();
        return v == null ? "" : v;
    }

    private static Cell cell(double v, String formatted, List<Integer> coord) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("isEmpty", Boolean.FALSE);
        answers.put("isNull", Boolean.FALSE);
        answers.put("getValue", v);
        answers.put("getDoubleValue", v);
        answers.put("getFormattedValue", formatted);
        answers.put("getCoordinateList", coord);
        return proxy(Cell.class, answers);
    }

    private static Level level(String name, String uniqueName, Hierarchy h, Dimension d, int depth) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getName", name);
        answers.put("getCaption", name);
        answers.put("getUniqueName", uniqueName);
        answers.put("getHierarchy", h);
        answers.put("getDimension", d);
        answers.put("getDepth", depth);
        return proxy(Level.class, answers);
    }

    private static Member member(String caption, String uniqueName, Dimension d, Hierarchy h, Level l, int depth) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getCaption", caption);
        answers.put("getName", caption);
        answers.put("getUniqueName", uniqueName);
        answers.put("getDimension", d);
        answers.put("getHierarchy", h);
        answers.put("getLevel", l);
        answers.put("getDepth", depth);
        answers.put("getParentMember", null);
        return proxy(Member.class, answers);
    }

    private static Position pos(List<Member> members) {
        return proxy(Position.class, map("getMembers", members, "getOrdinal", 0));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Map<String, Object> answers) {
        Object obj = Proxy.newProxyInstance(
                FlattenedCellSetFormatterMixedDepthTest.class.getClassLoader(),
                new Class<?>[] {iface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("toString".equals(m.getName())) return iface.getSimpleName() + "@fake";
                        if ("equals".equals(m.getName())) return p == a[0];
                        if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                        if (answers.containsKey(m.getName())) return answers.get(m.getName());
                        return defaultReturn(m);
                    }
                });
        return (T) obj;
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
        if (rt == void.class) return null;
        return null;
    }
}
