/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.olap4j.OlapException;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.NamedList;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;

/**
 * Covers the Saiku 2.x compatibility paths in {@link QueryDeserializer}: level lookup by stored
 * unique name with the two-segment Mondrian 3 fallback ({@code findLevel}), and the one-shot
 * dotted-head member retry ({@code includeMember}).
 *
 * <p>olap4j metadata is mocked with JDK dynamic proxies, the same approach the module's cellset
 * formatter tests use (the project's mockito-all 1.8.5 cannot instrument classes on modern JDKs).
 */
public class QueryDeserializerSaiku2CompatTest {

    // ------------------------------------------------------------------ findLevel

    @Test
    public void exactThreeSegmentMatchWinsWithoutFallback() {
        Level timeYear = level("Year", "[Time].[Time].[Year]");
        Level weeklyYear = level("Year", "[Time].[Weekly].[Year]");
        Hierarchy time = hierarchy("[Time].[Time]", timeYear);
        Hierarchy weekly = hierarchy("[Time].[Weekly]", weeklyYear);
        RecordingDimension dim = dimension(time, Arrays.asList(weekly, time));

        // The exact unique name names the NON-default hierarchy's level: the exact match must win
        // and the default-hierarchy preference must never run.
        Level found = QueryDeserializer.findLevel(dim.dimension, "[Time].[Weekly].[Year]");

        assertSame("exact unique-name match must be returned as-is", weeklyYear, found);
        assertEquals(
                "exact match must not consult the default hierarchy (no fallback ran)", 0, dim.defaultHierarchyCalls);
    }

    @Test
    public void twoSegmentSaiku2NameResolvesViaLastSegmentFallback() {
        Level family = level("Product Family", "[Product].[Products].[Product Family]");
        Level department = level("Product Department", "[Product].[Products].[Product Department]");
        Hierarchy products = hierarchy("[Product].[Products]", family, department);
        RecordingDimension dim = dimension(products, Arrays.asList(products));

        // Mondrian 3 wrote [dimension].[level] for a single-hierarchy dimension.
        Level found = QueryDeserializer.findLevel(dim.dimension, "[Product].[Product Family]");

        assertSame("two-segment Saiku 2.x name must resolve by last-segment name", family, found);
    }

    @Test
    public void ambiguousLevelNamePrefersDefaultHierarchy() {
        Level timeYear = level("Year", "[Time].[Time].[Year]");
        Level weeklyYear = level("Year", "[Time].[Weekly].[Year]");
        Hierarchy time = hierarchy("[Time].[Time]", timeYear);
        Hierarchy weekly = hierarchy("[Time].[Weekly]", weeklyYear);
        // Weekly is listed FIRST: a plain first-match scan would bind [Time].[Weekly].[Year].
        RecordingDimension dim = dimension(time, Arrays.asList(weekly, time));

        Level found = QueryDeserializer.findLevel(dim.dimension, "[Time].[Year]");

        assertSame(
                "with two hierarchies sharing a level name, the DEFAULT hierarchy's level must win", timeYear, found);
    }

    @Test
    public void fallbackStillFindsLevelOutsideDefaultHierarchy() {
        Level timeYear = level("Year", "[Time].[Time].[Year]");
        Level week = level("Week", "[Time].[Weekly].[Week]");
        Hierarchy time = hierarchy("[Time].[Time]", timeYear);
        Hierarchy weekly = hierarchy("[Time].[Weekly]", week);
        RecordingDimension dim = dimension(time, Arrays.asList(time, weekly));

        Level found = QueryDeserializer.findLevel(dim.dimension, "[Time].[Week]");

        assertSame("a name absent from the default hierarchy must still match elsewhere", week, found);
    }

    @Test
    public void noMatchAnywhereReturnsNullWithoutThrowing() {
        Level timeYear = level("Year", "[Time].[Time].[Year]");
        Hierarchy time = hierarchy("[Time].[Time]", timeYear);
        RecordingDimension dim = dimension(time, Arrays.asList(time));

        assertNull(
                "an unresolvable stored name must return null (caller warns and skips the selection)",
                QueryDeserializer.findLevel(dim.dimension, "[Time].[Quinquennium]"));
    }

    // ------------------------------------------------------------------ includeMember

    @Test
    public void dottedHeadSegmentIsRetriedSplit() throws OlapException {
        Selection selection = selection();
        FakeQueryDimension dim = new FakeQueryDimension(1, selection);

        Selection result = QueryDeserializer.includeMember(dim, Selection.Operator.MEMBER, "[Time.Weekly].[1997]");

        assertSame("the retried include's selection must be returned", selection, result);
        assertEquals("include must be called twice: original form, then split head", 2, dim.calls.size());
        assertEquals(
                "first attempt keeps the stored dotted head", Arrays.asList("Time.Weekly", "1997"), dim.calls.get(0));
        assertEquals(
                "retry splits the head into dimension and hierarchy segments",
                Arrays.asList("Time", "Weekly", "1997"),
                dim.calls.get(1));
    }

    @Test
    public void headWithoutDotPropagatesTheOriginalException() {
        FakeQueryDimension dim = new FakeQueryDimension(Integer.MAX_VALUE, null);

        try {
            QueryDeserializer.includeMember(dim, Selection.Operator.MEMBER, "[Time].[1997]");
            fail("a failing include with a dot-free head must not be retried");
        } catch (RuntimeException e) {
            assertSame("the original exception must propagate unchanged", dim.lastThrown, e);
        } catch (OlapException e) {
            fail("unexpected OlapException: " + e);
        }
        assertEquals("no retry may run when the head has no dot", 1, dim.calls.size());
    }

    // ------------------------------------------------------------------ proxy plumbing

    /** QueryDimension whose include(operator, segments) fails a given number of times. */
    private static final class FakeQueryDimension extends QueryDimension {
        private final int failures;
        private final Selection onSuccess;
        final List<List<String>> calls = new ArrayList<List<String>>();
        RuntimeException lastThrown;

        FakeQueryDimension(int failures, Selection onSuccess) {
            super(null, null);
            this.failures = failures;
            this.onSuccess = onSuccess;
        }

        @Override
        public Selection include(Selection.Operator operator, List<IdentifierSegment> segments) {
            List<String> names = new ArrayList<String>();
            for (IdentifierSegment segment : segments) {
                names.add(segment.getName());
            }
            calls.add(names);
            if (calls.size() <= failures) {
                lastThrown = new RuntimeException("member not found: " + names);
                throw lastThrown;
            }
            return onSuccess;
        }
    }

    /** A Dimension proxy that records how often getDefaultHierarchy is consulted. */
    private static final class RecordingDimension {
        final Dimension dimension;
        int defaultHierarchyCalls;

        RecordingDimension(final Hierarchy defaultHierarchy, final List<Hierarchy> hierarchies) {
            this.dimension = (Dimension) Proxy.newProxyInstance(
                    QueryDeserializerSaiku2CompatTest.class.getClassLoader(),
                    new Class<?>[] {Dimension.class},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            String name = method.getName();
                            if ("getHierarchies".equals(name)) {
                                return namedList(hierarchies);
                            }
                            if ("getDefaultHierarchy".equals(name)) {
                                defaultHierarchyCalls++;
                                return defaultHierarchy;
                            }
                            if ("getName".equals(name) || "toString".equals(name)) {
                                return "Time";
                            }
                            throw new UnsupportedOperationException(name);
                        }
                    });
        }
    }

    private static RecordingDimension dimension(Hierarchy defaultHierarchy, List<Hierarchy> hierarchies) {
        return new RecordingDimension(defaultHierarchy, hierarchies);
    }

    private static Hierarchy hierarchy(final String uniqueName, final Level... levels) {
        return (Hierarchy) Proxy.newProxyInstance(
                QueryDeserializerSaiku2CompatTest.class.getClassLoader(),
                new Class<?>[] {Hierarchy.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                            throws Throwable {
                        String name = method.getName();
                        if ("getLevels".equals(name)) {
                            return namedList(Arrays.asList(levels));
                        }
                        if ("getUniqueName".equals(name) || "toString".equals(name)) {
                            return uniqueName;
                        }
                        throw new UnsupportedOperationException(name);
                    }
                });
    }

    private static Level level(final String name, final String uniqueName) {
        return (Level) Proxy.newProxyInstance(
                QueryDeserializerSaiku2CompatTest.class.getClassLoader(),
                new Class<?>[] {Level.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                            throws Throwable {
                        String methodName = method.getName();
                        if ("getName".equals(methodName)) {
                            return name;
                        }
                        if ("getUniqueName".equals(methodName) || "toString".equals(methodName)) {
                            return uniqueName;
                        }
                        throw new UnsupportedOperationException(methodName);
                    }
                });
    }

    private static Selection selection() {
        return (Selection) Proxy.newProxyInstance(
                QueryDeserializerSaiku2CompatTest.class.getClassLoader(),
                new Class<?>[] {Selection.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        return "toString".equals(method.getName()) ? "selection" : null;
                    }
                });
    }

    /** NamedList proxy delegating the List surface to a backing java.util.List. */
    @SuppressWarnings("unchecked")
    private static <T> NamedList<T> namedList(final List<T> backing) {
        return (NamedList<T>) Proxy.newProxyInstance(
                QueryDeserializerSaiku2CompatTest.class.getClassLoader(),
                new Class<?>[] {NamedList.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                            throws Throwable {
                        if (method.getDeclaringClass().isAssignableFrom(List.class)
                                || method.getDeclaringClass() == Object.class) {
                            return method.invoke(backing, args);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
