/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.olap4j.Axis;
import org.olap4j.impl.Named;
import org.olap4j.impl.NamedListImpl;
import org.olap4j.mdx.ParseTreeNode;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.metadata.MetadataElement;
import org.olap4j.metadata.NamedList;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.saiku.olap.util.exception.SaikuIncompatibleException;
import org.saiku.query.QueryHierarchy;

/**
 * Regression tests for the two NullPointerException paths fixed in
 * {@code QueryConverter.convertDimension()} (saiku#1883):
 *
 * <ol>
 *   <li>a hierarchy that cannot be resolved on the target {@link org.saiku.query.Query} must
 *       surface as a {@link SaikuIncompatibleException} naming the hierarchy, not as a bare NPE
 *       from {@code includeMember()}/{@code addHierarchy()};</li>
 *   <li>a dimension carrying no inclusions at all (filter-only, or exclusions alone) must be
 *       skipped instead of reaching {@code sAxis.addHierarchy(null)}.</li>
 * </ol>
 *
 * <p>The olap4j metadata surface is faked with JDK dynamic proxies and the saiku-query
 * {@link org.saiku.query.Query} is a real instance built on a proxied {@link Cube} — no mocking
 * framework, in keeping with the rest of this module (Mockito is not on the test classpath).
 * {@code convertDimension} is private static, so it is invoked through reflection with the
 * unwrapped cause rethrown.</p>
 */
public class QueryConverterTest {

    // ---------------------------------------------------------------------
    // Deep-stub proxy plumbing
    // ---------------------------------------------------------------------

    /**
     * Creates a "deep stub" of the given interface: named methods answer from {@code overrides},
     * {@code getName}/{@code getUniqueName}/{@code getCaption} answer the supplied names, methods
     * returning {@link NamedList} answer an empty list, and methods returning another interface
     * answer a further stub. Every stub also implements {@link Named} so it can live inside a
     * {@link NamedListImpl}.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface, String name, String uniqueName, Map<String, Object> overrides) {
        InvocationHandler handler = (proxy, method, args) -> {
            String m = method.getName();
            if (overrides != null && overrides.containsKey(m)) {
                return overrides.get(m);
            }
            switch (m) {
                case "getName":
                case "getCaption":
                    return name;
                case "getUniqueName":
                    return uniqueName;
                case "getDescription":
                    return "";
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return name;
                case "unwrap":
                    return stub((Class<Object>) args[0], name, uniqueName, null);
                case "isWrapperFor":
                    return Boolean.TRUE;
                default:
                    break;
            }
            Class<?> rt = method.getReturnType();
            if (rt == void.class) {
                return null;
            }
            if (NamedList.class.isAssignableFrom(rt)) {
                return new NamedListImpl<>();
            }
            if (rt == List.class) {
                return Collections.emptyList();
            }
            if (rt == boolean.class || rt == Boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return 0;
            }
            if (rt == long.class) {
                return 0L;
            }
            if (rt == double.class) {
                return 0d;
            }
            if (rt == float.class) {
                return 0f;
            }
            if (rt == short.class) {
                return (short) 0;
            }
            if (rt == byte.class) {
                return (byte) 0;
            }
            if (rt == char.class) {
                return (char) 0;
            }
            if (rt == String.class) {
                return name;
            }
            if (rt.isInterface()) {
                return stub(rt, name + "." + m, uniqueName, null);
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(
                QueryConverterTest.class.getClassLoader(), new Class<?>[] {iface, Named.class}, handler);
    }

    /** A cube whose {@code getHierarchies()} answers exactly the given hierarchies. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cube cube(Hierarchy... hierarchies) {
        NamedListImpl list = new NamedListImpl();
        Collections.addAll(list, hierarchies);
        return stub(Cube.class, "TestCube", "[TestCube]", Map.of("getHierarchies", list));
    }

    private static org.saiku.query.Query newSaikuQuery(Cube cube) throws Exception {
        return new org.saiku.query.Query("test", cube);
    }

    /** Minimal hand-rolled {@link Selection}: a root element, an operator, no selection context. */
    private static final class StubSelection implements Selection {
        private final MetadataElement rootElement;
        private Operator operator;

        StubSelection(MetadataElement rootElement, Operator operator) {
            this.rootElement = rootElement;
            this.operator = operator;
        }

        @Override
        public String getUniqueName() {
            return rootElement.getUniqueName();
        }

        @Override
        public ParseTreeNode visit() {
            return null;
        }

        @Override
        public Dimension getDimension() {
            return null;
        }

        @Override
        public MetadataElement getRootElement() {
            return rootElement;
        }

        @Override
        public List<Selection> getSelectionContext() {
            return null;
        }

        @Override
        public void addContext(Selection selection) {}

        @Override
        public void removeContext(Selection selection) {}

        @Override
        public Operator getOperator() {
            return operator;
        }

        @Override
        public void setOperator(Operator operator) {
            this.operator = operator;
        }

        @Override
        public void addQueryNodeListener(org.olap4j.query.QueryNodeListener l) {}

        @Override
        public void removeQueryNodeListener(org.olap4j.query.QueryNodeListener l) {}
    }

    /** {@link QueryDimension} whose inclusion list is supplied by the test. */
    private static final class StubQueryDimension extends QueryDimension {
        private final List<Selection> inclusions;

        StubQueryDimension(List<Selection> inclusions) {
            super(null, null);
            this.inclusions = inclusions;
        }

        @Override
        public List<Selection> getInclusions() {
            return inclusions;
        }
    }

    /** Invokes the private static {@code convertDimension}, unwrapping reflection exceptions. */
    private static void convertDimension(
            QueryDimension qD, org.saiku.query.QueryAxis sAxis, org.saiku.query.Query sQuery) throws Exception {
        Method m = QueryConverter.class.getDeclaredMethod(
                "convertDimension", QueryDimension.class, org.saiku.query.QueryAxis.class, org.saiku.query.Query.class);
        m.setAccessible(true);
        try {
            m.invoke(null, qD, sAxis, sQuery);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    /**
     * A dimension with no inclusions (filter-only, or exclusions alone) must be skipped. The
     * pre-#1883 code fell through to {@code sAxis.addHierarchy(null)} and threw an NPE.
     */
    @Test
    public void filterOnlyDimensionIsSkippedWithoutAddingNullHierarchy() throws Exception {
        org.saiku.query.Query sQuery = newSaikuQuery(cube());
        org.saiku.query.QueryAxis sAxis = sQuery.getAxis(Axis.ROWS);

        convertDimension(new StubQueryDimension(new ArrayList<>()), sAxis, sQuery);

        assertTrue(
                "a dimension without inclusions must not place anything on the axis",
                sAxis.getQueryHierarchies().isEmpty());
    }

    /**
     * When neither {@code getHierarchy(Hierarchy)} nor the unique-name fallback resolves the
     * hierarchy, the converter must throw a {@link SaikuIncompatibleException} that names the
     * missing hierarchy. The pre-#1883 code threw a bare NPE from {@code includeMember()}.
     */
    @Test
    public void hierarchyMissOnBothLookupsThrowsSaikuIncompatibleNamingTheHierarchy() throws Exception {
        // The target query knows one hierarchy ([Time].[Time]) ...
        Hierarchy timeHierarchy = stub(Hierarchy.class, "Time", "[Time].[Time]", null);
        org.saiku.query.Query sQuery = newSaikuQuery(cube(timeHierarchy));
        org.saiku.query.QueryAxis sAxis = sQuery.getAxis(Axis.ROWS);

        // ... but the legacy selection references [Gender], which is absent.
        Hierarchy genderHierarchy = stub(Hierarchy.class, "Gender", "[Gender]", null);
        Member member = stub(Member.class, "F", "[Gender].[F]", Map.of("getHierarchy", genderHierarchy));
        Selection selection = new StubSelection(member, Selection.Operator.MEMBER);
        QueryDimension qD = new StubQueryDimension(Collections.singletonList(selection));

        try {
            convertDimension(qD, sAxis, sQuery);
            fail("expected SaikuIncompatibleException for an unresolvable hierarchy");
        } catch (SaikuIncompatibleException e) {
            assertNotNull(e.getMessage());
            assertTrue(
                    "the exception must name the missing hierarchy, got: " + e.getMessage(),
                    e.getMessage().contains("[Gender]"));
        }
        assertTrue(
                "nothing may be placed on the axis on failure",
                sAxis.getQueryHierarchies().isEmpty());
    }

    /**
     * Happy path: the hierarchy resolves through the metadata overload
     * {@code getHierarchy(Hierarchy)} and the query hierarchy lands on the axis.
     */
    @Test
    public void resolvableHierarchyIsAddedToAxis() throws Exception {
        Hierarchy timeHierarchy = stub(Hierarchy.class, "Time", "[Time].[Time]", null);
        org.saiku.query.Query sQuery = newSaikuQuery(cube(timeHierarchy));
        org.saiku.query.QueryAxis sAxis = sQuery.getAxis(Axis.ROWS);

        Level yearLevel = stub(Level.class, "Year", "[Time].[Time].[Year]", Map.of("getHierarchy", timeHierarchy));
        Selection selection = new StubSelection(yearLevel, Selection.Operator.MEMBERS);
        QueryDimension qD = new StubQueryDimension(Collections.singletonList(selection));

        convertDimension(qD, sAxis, sQuery);

        assertEquals(1, sAxis.getQueryHierarchies().size());
        QueryHierarchy qh = sAxis.getQueryHierarchies().get(0);
        assertEquals("[Time].[Time]", qh.getUniqueName());
        assertSame(sQuery.getHierarchy(timeHierarchy), qh);
    }
}
