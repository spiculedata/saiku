/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * saiku#800 — pin the drillthrough column-label disambiguation. Mondrian
 * sometimes returns the same {@code getColumnLabel(c)} for several
 * time-hierarchy columns (e.g. Year + Quarter + Month all labelled
 * "Quarter"). Before the fix the row Map.put silently overwrote earlier
 * columns. Now the first occurrence keeps its label; later collisions fall
 * back to {@code getColumnName(c)} when distinct, else to a positional
 * suffix.
 */
public class AiQueryResourceColumnDisambiguationTest {

    @Test
    public void distinctLabelsPassThrough() throws Exception {
        List<String> out = AiQueryResource.disambiguateColumnLabels(md(
                new String[] {"Year", "Quarter", "Month", "Store Sales"},
                new String[] {"Year", "Quarter", "Month", "Store Sales"}));
        assertEquals(Arrays.asList("Year", "Quarter", "Month", "Store Sales"), out);
    }

    @Test
    public void duplicateLabelFallsBackToColumnNameWhenDistinct() throws Exception {
        // Label collision on "Quarter" — but the underlying column NAME
        // (a different metadata field) differs and isn't itself a duplicate
        // of the label, so the tiebreaker uses it. First col keeps the raw
        // label so we don't yank back-compat from existing consumers.
        List<String> out = AiQueryResource.disambiguateColumnLabels(md(
                new String[] {"Quarter", "Quarter", "Quarter"},
                new String[] {"yearCol", "quarterCol", "monthCol"}));
        assertEquals(Arrays.asList("Quarter", "quarterCol", "monthCol"), out);
    }

    @Test
    public void duplicateLabelFallsBackToPositionalSuffix() throws Exception {
        // Both label and name collide — only the positional suffix saves us.
        List<String> out = AiQueryResource.disambiguateColumnLabels(
                md(new String[] {"Quarter", "Quarter", "Quarter"}, new String[] {"Quarter", "Quarter", "Quarter"}));
        assertEquals(Arrays.asList("Quarter", "Quarter_2", "Quarter_3"), out);
    }

    @Test
    public void nullOrEmptyLabelGetsColPlaceholder() throws Exception {
        List<String> out = AiQueryResource.disambiguateColumnLabels(
                md(new String[] {null, "", "Store Sales"}, new String[] {"a", "b", "Store Sales"}));
        assertEquals(Arrays.asList("col_1", "col_2", "Store Sales"), out);
    }

    /** Build a Proxy-based ResultSetMetaData backed by parallel label/name arrays. */
    private static ResultSetMetaData md(final String[] labels, final String[] names) {
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] args) {
                switch (m.getName()) {
                    case "getColumnCount":
                        return labels.length;
                    case "getColumnLabel":
                        return labels[((Integer) args[0]) - 1];
                    case "getColumnName":
                        return names[((Integer) args[0]) - 1];
                    case "toString":
                        return "ResultSetMetaData@fake";
                    case "equals":
                        return p == args[0];
                    case "hashCode":
                        return System.identityHashCode(p);
                    default:
                        return null;
                }
            }
        };
        return (ResultSetMetaData) Proxy.newProxyInstance(
                AiQueryResourceColumnDisambiguationTest.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class},
                h);
    }
}
