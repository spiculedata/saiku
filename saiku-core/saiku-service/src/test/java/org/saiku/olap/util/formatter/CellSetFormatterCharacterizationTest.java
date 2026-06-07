/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.olap4j.metadata.Property;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.Matrix;
import org.saiku.olap.dto.resultset.MemberCell;

/**
 * Characterization (golden) test for the three {@link ICellSetFormatter}
 * implementations — {@link CellSetFormatter}, {@link HierarchicalCellSetFormatter}
 * and {@link FlattenedCellSetFormatter}.
 *
 * <p>This test is a SAFETY NET for the behaviour-preserving refactor in
 * issue #1163 (lifting the shared scaffolding into a common base class). It
 * serialises the <b>entire</b> {@link Matrix} output — width, height, offset
 * and every populated coordinate's full cell state (cell class, rawValue,
 * formattedValue, parentDimension, expanded, lastRow, uniqueName, hierarchy,
 * level, rawNumber, formatString, coordinates and the complete properties map
 * including {@code levelindex} / {@code __headertype}) — into a deterministic
 * string, then pins it with hard-coded golden literals.
 *
 * <p>The goldens were captured GREEN against the UNMODIFIED pre-refactor code
 * and must stay byte-identical afterwards. Four cellset shapes are covered per
 * formatter:
 * <ul>
 *   <li>{@code flatSingleDim} — one dimension on rows, one measure on columns;</li>
 *   <li>{@code mixedDepth}    — three row positions at different hierarchy depths
 *       (the saiku#788 / #845 path with the {@code Arrays.fill(members,null)}
 *       clear and the parent-walk);</li>
 *   <li>{@code noAxes}        — a cellset with zero axes (single scalar cell);</li>
 *   <li>{@code throwingCell}  — a data cell whose {@code getDoubleValue()} throws
 *       and whose {@code FORMAT_STRING} is null (exercises the three
 *       exception-swallow sites).</li>
 * </ul>
 *
 * <p>Because the matrix shapes differ per formatter, each formatter has its own
 * golden literal. The serialiser sorts coordinates and property keys so the
 * output is stable across JVM/map-iteration order.
 */
public class CellSetFormatterCharacterizationTest {

    /* ===================================================================== */
    /*  Tests                                                                */
    /* ===================================================================== */

    @Test
    public void flatSingleDim_allThreeFormatters() {
        CellSet cs = flatSingleDimCellSet();
        assertEquals(GOLDEN_FLAT_FLAT, serialize(new CellSetFormatter().format(cs)));
        assertEquals(GOLDEN_FLAT_HIER, serialize(new HierarchicalCellSetFormatter().format(cs)));
        assertEquals(GOLDEN_FLAT_FLATTENED, serialize(new FlattenedCellSetFormatter().format(cs)));
    }

    @Test
    public void mixedDepth_allThreeFormatters() {
        // Each formatter must get a fresh cellset because the fake cells track
        // no state, but the formatters do (FlattenedCellSetFormatter keeps
        // ignorex/ignorey instance lists). A fresh cellset keeps them isolated.
        assertEquals(GOLDEN_MIXED_FLAT, serialize(new CellSetFormatter().format(mixedDepthCellSet())));
        assertEquals(GOLDEN_MIXED_HIER, serialize(new HierarchicalCellSetFormatter().format(mixedDepthCellSet())));
        assertEquals(GOLDEN_MIXED_FLATTENED, serialize(new FlattenedCellSetFormatter().format(mixedDepthCellSet())));
    }

    @Test
    public void noAxes_allThreeFormatters() {
        assertEquals(GOLDEN_NOAXES_FLAT, serialize(new CellSetFormatter().format(noAxesCellSet())));
        assertEquals(GOLDEN_NOAXES_HIER, serialize(new HierarchicalCellSetFormatter().format(noAxesCellSet())));
        assertEquals(GOLDEN_NOAXES_FLATTENED, serialize(new FlattenedCellSetFormatter().format(noAxesCellSet())));
    }

    @Test
    public void throwingCellNullFormat_allThreeFormatters() {
        assertEquals(GOLDEN_THROW_FLAT, serialize(new CellSetFormatter().format(throwingCellSet())));
        assertEquals(GOLDEN_THROW_HIER, serialize(new HierarchicalCellSetFormatter().format(throwingCellSet())));
        assertEquals(GOLDEN_THROW_FLATTENED, serialize(new FlattenedCellSetFormatter().format(throwingCellSet())));
    }

    /* ===================================================================== */
    /*  Matrix serialiser — deterministic, full cell state                    */
    /* ===================================================================== */

    private static String serialize(Matrix m) {
        StringBuilder sb = new StringBuilder();
        sb.append("width=").append(m.getMatrixWidth());
        sb.append(" height=").append(m.getMatrixHeight());
        sb.append(" offset=").append(m.getOffset());
        sb.append('\n');

        // Sort the populated coordinates (x then y) so iteration order is stable.
        List<List<Integer>> coords = new ArrayList<>(m.getMap().keySet());
        coords.sort((a, b) -> {
            int cx = Integer.compare(a.get(0), b.get(0));
            return cx != 0 ? cx : Integer.compare(a.get(1), b.get(1));
        });

        for (List<Integer> c : coords) {
            AbstractBaseCell cell = m.getMap().get(c);
            sb.append('(').append(c.get(0)).append(',').append(c.get(1)).append(") ");
            sb.append(describe(cell));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String describe(AbstractBaseCell cell) {
        if (cell == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(cell.getClass().getSimpleName());
        sb.append("{raw=").append(q(cell.getRawValue()));
        sb.append(", fmt=").append(q(cell.getFormattedValue()));
        sb.append(", parentDim=").append(q(cell.getParentDimension()));

        if (cell instanceof MemberCell) {
            MemberCell mc = (MemberCell) cell;
            sb.append(", expanded=").append(mc.isExpanded());
            sb.append(", lastRow=").append(mc.isLastRow());
            sb.append(", uniqueName=").append(q(mc.getUniqueName()));
            sb.append(", hierarchy=").append(q(mc.getHierarchy()));
            sb.append(", level=").append(q(mc.getLevel()));
        } else if (cell instanceof DataCell) {
            DataCell dc = (DataCell) cell;
            sb.append(", rawNumber=").append(dc.getRawNumber());
            sb.append(", formatString=").append(q(dc.getFormatString()));
            sb.append(", coordinates=").append(dc.getCoordinates());
        }

        // Properties map — sort keys for determinism.
        Map<String, String> props = new TreeMap<>(cell.getProperties());
        sb.append(", props=").append(props);
        sb.append('}');
        return sb.toString();
    }

    private static String q(String s) {
        return s == null ? "<null>" : "'" + s + "'";
    }

    /* ===================================================================== */
    /*  Cellset fixtures                                                      */
    /* ===================================================================== */

    /** Flat single-dim: rows = Product Family (3 members), cols = 1 measure. */
    private static CellSet flatSingleDimCellSet() {
        Dimension productDim = proxy(Dimension.class, map("getName", "Product"));
        Hierarchy productH = proxy(
                Hierarchy.class,
                map("getName", "Products", "getUniqueName", "[Product].[Products]", "getDimension", productDim));
        Level familyLevel = level("Product Family", "[Product].[Products].[Product Family]", productH, productDim, 2);

        Member drink = member("Drink", "[Product].[Products].[Drink]", productDim, productH, familyLevel, 2, null);
        Member food = member("Food", "[Product].[Products].[Food]", productDim, productH, familyLevel, 2, null);
        Member nonconsum = member(
                "Non-Consumable", "[Product].[Products].[Non-Consumable]", productDim, productH, familyLevel, 2, null);

        List<Position> rowPositions = Arrays.asList(
                pos(Collections.singletonList(drink)),
                pos(Collections.singletonList(food)),
                pos(Collections.singletonList(nonconsum)));

        Dimension measDim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy measH = proxy(
                Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", measDim));
        Level measLvl = level("MeasuresLevel", "[Measures].[MeasuresLevel]", measH, measDim, 0);
        Member storeSales = member("Store Sales", "[Measures].[Store Sales]", measDim, measH, measLvl, 0, null);
        List<Position> colPositions = Collections.singletonList(pos(Collections.singletonList(storeSales)));

        Map<List<Integer>, Cell> cells = new HashMap<>();
        cells.put(Arrays.asList(0, 0), cell(48836.21, "$48,836.21", "$#,##0.00", Arrays.asList(0, 0)));
        cells.put(Arrays.asList(0, 1), cell(191940.0, "$191,940.00", "$#,##0.00", Arrays.asList(0, 1)));
        cells.put(Arrays.asList(0, 2), cell(50236.88, "$50,236.88", "$#,##0.00", Arrays.asList(0, 2)));

        return twoAxisCellSet(productH, measH, rowPositions, colPositions, cells);
    }

    /** Mixed depth: three row positions at depths 2,3,4 of one hierarchy. */
    private static CellSet mixedDepthCellSet() {
        Dimension productDim = proxy(Dimension.class, map("getName", "Product"));
        Hierarchy productH = proxy(
                Hierarchy.class,
                map("getName", "Products", "getUniqueName", "[Product].[Products]", "getDimension", productDim));
        Level familyLevel = level("Product Family", "[Product].[Products].[Product Family]", productH, productDim, 2);
        Level deptLevel =
                level("Product Department", "[Product].[Products].[Product Department]", productH, productDim, 3);
        Level catLevel = level("Product Category", "[Product].[Products].[Product Category]", productH, productDim, 4);

        Member drink = member("Drink", "[Product].[Products].[Drink]", productDim, productH, familyLevel, 2, null);
        Member beverages = member(
                "Beverages", "[Product].[Products].[Drink].[Beverages]", productDim, productH, deptLevel, 3, drink);
        Member drinks = member(
                "Drinks",
                "[Product].[Products].[Drink].[Beverages].[Drinks]",
                productDim,
                productH,
                catLevel,
                4,
                beverages);

        List<Position> rowPositions = Arrays.asList(
                pos(Collections.singletonList(drink)),
                pos(Collections.singletonList(beverages)),
                pos(Collections.singletonList(drinks)));

        Dimension measDim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy measH = proxy(
                Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", measDim));
        Level measLvl = level("MeasuresLevel", "[Measures].[MeasuresLevel]", measH, measDim, 0);
        Member storeSales = member("Store Sales", "[Measures].[Store Sales]", measDim, measH, measLvl, 0, null);
        List<Position> colPositions = Collections.singletonList(pos(Collections.singletonList(storeSales)));

        Map<List<Integer>, Cell> cells = new HashMap<>();
        cells.put(Arrays.asList(0, 0), cell(48836.21, "$48,836.21", "$#,##0.00", Arrays.asList(0, 0)));
        cells.put(Arrays.asList(0, 1), cell(27748.53, "$27,748.53", "$#,##0.00", Arrays.asList(0, 1)));
        cells.put(Arrays.asList(0, 2), cell(5642.29, "$5,642.29", "$#,##0.00", Arrays.asList(0, 2)));

        return twoAxisCellSet(productH, measH, rowPositions, colPositions, cells);
    }

    /** No axes at all: a single scalar cell at coordinate []. */
    private static CellSet noAxesCellSet() {
        final List<CellSetAxis> axes = Collections.emptyList();
        final Map<List<Integer>, Cell> cells = new HashMap<>();
        cells.put(
                Collections.<Integer>emptyList(),
                cell(123456.78, "$123,456.78", "$#,##0.00", Collections.<Integer>emptyList()));
        return cellSetProxy(axes, cells);
    }

    /**
     * A data cell whose getDoubleValue() throws and whose FORMAT_STRING is null.
     * Single dim on rows, one measure column, one cell.
     */
    private static CellSet throwingCellSet() {
        Dimension productDim = proxy(Dimension.class, map("getName", "Product"));
        Hierarchy productH = proxy(
                Hierarchy.class,
                map("getName", "Products", "getUniqueName", "[Product].[Products]", "getDimension", productDim));
        Level familyLevel = level("Product Family", "[Product].[Products].[Product Family]", productH, productDim, 2);
        Member drink = member("Drink", "[Product].[Products].[Drink]", productDim, productH, familyLevel, 2, null);
        List<Position> rowPositions = Collections.singletonList(pos(Collections.singletonList(drink)));

        Dimension measDim = proxy(Dimension.class, map("getName", "Measures"));
        Hierarchy measH = proxy(
                Hierarchy.class, map("getName", "Measures", "getUniqueName", "[Measures]", "getDimension", measDim));
        Level measLvl = level("MeasuresLevel", "[Measures].[MeasuresLevel]", measH, measDim, 0);
        Member storeSales = member("Store Sales", "[Measures].[Store Sales]", measDim, measH, measLvl, 0, null);
        List<Position> colPositions = Collections.singletonList(pos(Collections.singletonList(storeSales)));

        Map<List<Integer>, Cell> cells = new HashMap<>();
        cells.put(Arrays.asList(0, 0), throwingCell(Arrays.asList(0, 0)));

        return twoAxisCellSet(productH, measH, rowPositions, colPositions, cells);
    }

    /* ===================================================================== */
    /*  Cellset / proxy plumbing                                              */
    /* ===================================================================== */

    private static CellSet twoAxisCellSet(
            Hierarchy rowH,
            Hierarchy colH,
            List<Position> rowPositions,
            List<Position> colPositions,
            Map<List<Integer>, Cell> cellByCoord) {
        CellSetAxisMetaData rowAxisMeta =
                proxy(CellSetAxisMetaData.class, map("getHierarchies", Collections.singletonList(rowH)));
        CellSetAxisMetaData colAxisMeta =
                proxy(CellSetAxisMetaData.class, map("getHierarchies", Collections.singletonList(colH)));

        CellSetAxis rowAxis = proxy(
                CellSetAxis.class,
                map(
                        "getAxisOrdinal",
                        Axis.ROWS,
                        "getPositions",
                        rowPositions,
                        "getPositionCount",
                        rowPositions.size(),
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
                        colPositions.size(),
                        "getAxisMetaData",
                        colAxisMeta));

        // olap4j ordering: axes are [columns, rows].
        List<CellSetAxis> axes = Arrays.asList(colAxis, rowAxis);
        return cellSetProxy(axes, cellByCoord);
    }

    private static CellSet cellSetProxy(final List<CellSetAxis> axes, final Map<List<Integer>, Cell> cellByCoord) {
        InvocationHandler h = new InvocationHandler() {
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
        return (CellSet) Proxy.newProxyInstance(
                CellSetFormatterCharacterizationTest.class.getClassLoader(), new Class<?>[] {CellSet.class}, h);
    }

    private static Cell cell(double v, String formatted, final String formatString, List<Integer> coord) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("isEmpty", Boolean.FALSE);
        answers.put("isNull", Boolean.FALSE);
        answers.put("isError", Boolean.FALSE);
        answers.put("getValue", v);
        answers.put("getDoubleValue", v);
        answers.put("getFormattedValue", formatted);
        answers.put("getCoordinateList", coord);
        // FORMAT_STRING is read both by CellPropertyExtractor and by the
        // formatter's own format-string block; expose it via getPropertyValue.
        final Map<Property, Object> propValues = new HashMap<>();
        if (formatString != null) propValues.put(Property.StandardCellProperty.FORMAT_STRING, formatString);
        return cellProxy(answers, propValues);
    }

    /**
     * A cell whose getDoubleValue() throws (exercises the rawNumber swallow),
     * with a null FORMAT_STRING (exercises the format-string swallow's NPE
     * branch). getValue() returns a non-null, non-numeric value so the
     * DecimalFormat block is hit as well.
     */
    private static Cell throwingCell(List<Integer> coord) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("isEmpty", Boolean.FALSE);
        answers.put("isNull", Boolean.FALSE);
        answers.put("isError", Boolean.FALSE);
        // Non-numeric value: DecimalFormat.format(Object) on a String throws,
        // hitting the second swallow site too; getFormattedValue is blank.
        answers.put("getValue", "not-a-number");
        answers.put("getFormattedValue", "");
        answers.put("getCoordinateList", coord);
        answers.put("__throwDouble", Boolean.TRUE);
        // No FORMAT_STRING -> getPropertyValue returns null -> the format-string
        // block's else branch calls .substring on null -> NPE -> swallowed.
        return cellProxy(answers, new HashMap<Property, Object>());
    }

    private static Cell cellProxy(final Map<String, Object> answers, final Map<Property, Object> propValues) {
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                String name = m.getName();
                if ("getDoubleValue".equals(name) && Boolean.TRUE.equals(answers.get("__throwDouble"))) {
                    throw new RuntimeException("getDoubleValue blew up");
                }
                if ("getPropertyValue".equals(name) && a != null && a.length >= 1 && a[0] instanceof Property) {
                    return propValues.get(a[0]);
                }
                if ("toString".equals(name)) return "Cell@fake";
                if ("equals".equals(name)) return p == a[0];
                if ("hashCode".equals(name)) return System.identityHashCode(p);
                if (answers.containsKey(name)) return answers.get(name);
                return defaultReturn(m);
            }
        };
        return (Cell) Proxy.newProxyInstance(
                CellSetFormatterCharacterizationTest.class.getClassLoader(), new Class<?>[] {Cell.class}, h);
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

    private static Member member(
            String caption, String uniqueName, Dimension d, Hierarchy h, Level l, int depth, Member parent) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getCaption", caption);
        answers.put("getName", caption);
        answers.put("getUniqueName", uniqueName);
        answers.put("getDimension", d);
        answers.put("getHierarchy", h);
        answers.put("getLevel", l);
        answers.put("getDepth", depth);
        answers.put("getParentMember", parent);
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
    private static <T> T proxy(final Class<T> iface, final Map<String, Object> answers) {
        Object obj = Proxy.newProxyInstance(
                CellSetFormatterCharacterizationTest.class.getClassLoader(),
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

    /* ===================================================================== */
    /*  Golden literals — captured GREEN on the pre-refactor code.            */
    /*  (filled in after the first capture run)                               */
    /* ===================================================================== */

    private static final String GOLDEN_FLAT_FLAT =
            """
            width=2 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,2) MemberCell{raw='[Product].[Products].[Food]', fmt='Food', parentDim='Product', expanded=false, lastRow=true, uniqueName='[Product].[Products].[Food]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,3) MemberCell{raw='[Product].[Products].[Non-Consumable]', fmt='Non-Consumable', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Non-Consumable]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (1,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (1,2) DataCell{raw=<null>, fmt='$191,940.00', parentDim=<null>, rawNumber=191940.0, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (1,3) DataCell{raw=<null>, fmt='$50,236.88', parentDim=<null>, rawNumber=50236.88, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_FLAT_HIER =
            """
            width=2 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,2) MemberCell{raw='[Product].[Products].[Food]', fmt='Food', parentDim='Product', expanded=false, lastRow=true, uniqueName='[Product].[Products].[Food]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,3) MemberCell{raw='[Product].[Products].[Non-Consumable]', fmt='Non-Consumable', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Non-Consumable]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (1,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (1,2) DataCell{raw=<null>, fmt='$191,940.00', parentDim=<null>, rawNumber=191940.0, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (1,3) DataCell{raw=<null>, fmt='$50,236.88', parentDim=<null>, rawNumber=50236.88, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_FLAT_FLATTENED =
            """
            width=2 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (0,2) MemberCell{raw='[Product].[Products].[Food]', fmt='Food', parentDim='Product', expanded=false, lastRow=true, uniqueName='[Product].[Products].[Food]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (0,3) MemberCell{raw='[Product].[Products].[Non-Consumable]', fmt='Non-Consumable', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Non-Consumable]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={}}
            (1,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (1,2) DataCell{raw=<null>, fmt='$191,940.00', parentDim=<null>, rawNumber=191940.0, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (1,3) DataCell{raw=<null>, fmt='$50,236.88', parentDim=<null>, rawNumber=50236.88, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_MIXED_FLAT =
            """
            width=4 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,2) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (0,3) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (1,0) MemberCell{raw='Product Department', fmt='Product Department', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={__headertype=row_header_header, levelindex=1}}
            (1,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (1,2) MemberCell{raw='[Product].[Products].[Drink].[Beverages]', fmt='Beverages', parentDim='Product', expanded=true, lastRow=true, uniqueName='[Product].[Products].[Drink].[Beverages]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={levelindex=1}}
            (1,3) MemberCell{raw='[Product].[Products].[Drink].[Beverages]', fmt='Beverages', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink].[Beverages]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={}}
            (2,0) MemberCell{raw='Product Category', fmt='Product Category', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={__headertype=row_header_header, levelindex=2}}
            (2,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,2) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,3) MemberCell{raw='[Product].[Products].[Drink].[Beverages].[Drinks]', fmt='Drinks', parentDim='Product', expanded=true, lastRow=false, uniqueName='[Product].[Products].[Drink].[Beverages].[Drinks]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={levelindex=2}}
            (3,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (3,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (3,2) DataCell{raw=<null>, fmt='$27,748.53', parentDim=<null>, rawNumber=27748.53, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (3,3) DataCell{raw=<null>, fmt='$5,642.29', parentDim=<null>, rawNumber=5642.29, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_MIXED_HIER =
            """
            width=4 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (0,2) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (0,3) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (1,0) MemberCell{raw='Product Department', fmt='Product Department', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={__headertype=row_header_header, levelindex=1}}
            (1,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (1,2) MemberCell{raw='[Product].[Products].[Drink].[Beverages]', fmt='Beverages', parentDim='Product', expanded=true, lastRow=true, uniqueName='[Product].[Products].[Drink].[Beverages]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={levelindex=1}}
            (1,3) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,0) MemberCell{raw='Product Category', fmt='Product Category', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={__headertype=row_header_header, levelindex=2}}
            (2,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,2) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,3) MemberCell{raw='[Product].[Products].[Drink].[Beverages].[Drinks]', fmt='Drinks', parentDim='Product', expanded=true, lastRow=false, uniqueName='[Product].[Products].[Drink].[Beverages].[Drinks]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={levelindex=2}}
            (3,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (3,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (3,2) DataCell{raw=<null>, fmt='$27,748.53', parentDim=<null>, rawNumber=27748.53, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (3,3) DataCell{raw=<null>, fmt='$5,642.29', parentDim=<null>, rawNumber=5642.29, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_MIXED_FLATTENED =
            """
            width=4 height=4 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (0,2) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (0,3) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (1,0) MemberCell{raw='Product Department', fmt='Product Department', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={__headertype=row_header_header, levelindex=1}}
            (1,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (1,2) MemberCell{raw='[Product].[Products].[Drink].[Beverages]', fmt='Beverages', parentDim='Product', expanded=true, lastRow=true, uniqueName='[Product].[Products].[Drink].[Beverages]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={}}
            (1,3) MemberCell{raw='[Product].[Products].[Drink].[Beverages]', fmt='Beverages', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink].[Beverages]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Department]', props={}}
            (2,0) MemberCell{raw='Product Category', fmt='Product Category', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={__headertype=row_header_header, levelindex=2}}
            (2,1) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=false, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,2) MemberCell{raw=<null>, fmt=<null>, parentDim=<null>, expanded=true, lastRow=false, uniqueName=<null>, hierarchy=<null>, level=<null>, props={}}
            (2,3) MemberCell{raw='[Product].[Products].[Drink].[Beverages].[Drinks]', fmt='Drinks', parentDim='Product', expanded=true, lastRow=false, uniqueName='[Product].[Products].[Drink].[Beverages].[Drinks]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Category]', props={}}
            (3,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={}}
            (3,1) DataCell{raw=<null>, fmt='$48,836.21', parentDim=<null>, rawNumber=48836.21, formatString='$#,##0.00', coordinates=[0, 0], props={formatString=$#,##0.00}}
            (3,2) DataCell{raw=<null>, fmt='$27,748.53', parentDim=<null>, rawNumber=27748.53, formatString='$#,##0.00', coordinates=[0, 1], props={formatString=$#,##0.00}}
            (3,3) DataCell{raw=<null>, fmt='$5,642.29', parentDim=<null>, rawNumber=5642.29, formatString='$#,##0.00', coordinates=[0, 2], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_NOAXES_FLAT =
            """
            width=1 height=1 offset=0
            (0,0) DataCell{raw=<null>, fmt='$123,456.78', parentDim=<null>, rawNumber=123456.78, formatString='$#,##0.00', coordinates=[], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_NOAXES_HIER =
            """
            width=1 height=1 offset=0
            (0,0) DataCell{raw=<null>, fmt='$123,456.78', parentDim=<null>, rawNumber=123456.78, formatString='$#,##0.00', coordinates=[], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_NOAXES_FLATTENED =
            """
            width=1 height=1 offset=0
            (0,0) DataCell{raw=<null>, fmt='$123,456.78', parentDim=<null>, rawNumber=123456.78, formatString='$#,##0.00', coordinates=[], props={formatString=$#,##0.00}}
            """;

    private static final String GOLDEN_THROW_FLAT =
            """
            width=2 height=2 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (1,1) DataCell{raw=<null>, fmt='', parentDim=<null>, rawNumber=null, formatString=<null>, coordinates=[0, 0], props={}}
            """;

    private static final String GOLDEN_THROW_HIER =
            """
            width=2 height=2 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={levelindex=0}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={levelindex=0}}
            (1,1) DataCell{raw=<null>, fmt='', parentDim=<null>, rawNumber=null, formatString=<null>, coordinates=[0, 0], props={}}
            """;

    private static final String GOLDEN_THROW_FLATTENED =
            """
            width=2 height=2 offset=1
            (0,0) MemberCell{raw='Product Family', fmt='Product Family', parentDim='Product', expanded=false, lastRow=false, uniqueName=<null>, hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={__headertype=row_header_header, levelindex=0}}
            (0,1) MemberCell{raw='[Product].[Products].[Drink]', fmt='Drink', parentDim='Product', expanded=false, lastRow=false, uniqueName='[Product].[Products].[Drink]', hierarchy='[Product].[Products]', level='[Product].[Products].[Product Family]', props={}}
            (1,0) MemberCell{raw='[Measures].[Store Sales]', fmt='Store Sales', parentDim='Measures', expanded=false, lastRow=false, uniqueName='[Measures].[Store Sales]', hierarchy='[Measures]', level='[Measures].[MeasuresLevel]', props={}}
            (1,1) DataCell{raw=<null>, fmt='', parentDim=<null>, rawNumber=null, formatString=<null>, coordinates=[0, 0], props={}}
            """;
}
