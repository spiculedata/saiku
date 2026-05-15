/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util.formatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.olap4j.Cell;
import org.olap4j.metadata.Property;

/**
 * Unit tests for {@link CellPropertyExtractor} — pulls every supported
 * {@link Property.StandardCellProperty} value off an olap4j {@link Cell}
 * and surfaces them as a stable camelCase map (saiku#773).
 *
 * <p>Uses a JDK {@link Proxy} stub so the test doesn't need a real
 * Mondrian cellset — we just need to assert the extractor reads the
 * right properties and short-circuits cleanly on nulls / errors.
 */
public class CellPropertyExtractorTest {

    @Test
    public void extractsPopulatedStandardProperties() {
        Map<Property.StandardCellProperty, Object> values = new HashMap<>();
        values.put(Property.StandardCellProperty.FORMAT_STRING, "$#,##0.00");
        values.put(Property.StandardCellProperty.FORE_COLOR, 255);
        values.put(Property.StandardCellProperty.BACK_COLOR, 16777215);
        values.put(Property.StandardCellProperty.FONT_FLAGS, 1);
        values.put(Property.StandardCellProperty.ACTION_TYPE, "Drill");

        Cell cell = stubCell(values, false, null);
        Map<String, String> out = CellPropertyExtractor.extract(cell);

        assertEquals("$#,##0.00", out.get("formatString"));
        assertEquals("255", out.get("foreColor"));
        assertEquals("16777215", out.get("backColor"));
        assertEquals("1", out.get("fontFlags"));
        assertEquals("Drill", out.get("actionType"));
    }

    @Test
    public void absentPropertiesAreOmitted() {
        // Only FORMAT_STRING set; every other property returns null.
        Map<Property.StandardCellProperty, Object> values = new HashMap<>();
        values.put(Property.StandardCellProperty.FORMAT_STRING, "#,##0");

        Cell cell = stubCell(values, false, null);
        Map<String, String> out = CellPropertyExtractor.extract(cell);

        assertTrue("formatString present", out.containsKey("formatString"));
        assertFalse("foreColor not in map when null", out.containsKey("foreColor"));
        assertFalse("backColor not in map when null", out.containsKey("backColor"));
        assertEquals(1, out.size());
    }

    @Test
    public void emptyStringValueIsTreatedAsAbsent() {
        Map<Property.StandardCellProperty, Object> values = new HashMap<>();
        values.put(Property.StandardCellProperty.FORMAT_STRING, "");

        Cell cell = stubCell(values, false, null);
        Map<String, String> out = CellPropertyExtractor.extract(cell);

        assertTrue("empty-string property dropped", out.isEmpty());
    }

    @Test
    public void errorCellSurfacesErrorKey() {
        Cell cell = stubCell(new HashMap<>(), true, "division by zero");
        Map<String, String> out = CellPropertyExtractor.extract(cell);

        assertEquals("division by zero", out.get("error"));
    }

    @Test
    public void nullCellReturnsEmptyMap() {
        Map<String, String> out = CellPropertyExtractor.extract(null);
        assertNotNull(out);
        assertEquals(0, out.size());
    }

    @Test
    public void thrownPropertyAccessSilentlyOmits() {
        // A cell whose getPropertyValue() throws for FORE_COLOR should
        // still surface other properties cleanly.
        Cell cell = (Cell) Proxy.newProxyInstance(
                CellPropertyExtractorTest.class.getClassLoader(), new Class<?>[] {Cell.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getPropertyValue".equals(name) && args != null && args.length == 1) {
                            Property p = (Property) args[0];
                            if (p == Property.StandardCellProperty.FORE_COLOR) {
                                throw new RuntimeException("evaluator not materialised");
                            }
                            if (p == Property.StandardCellProperty.FORMAT_STRING) return "#,##0.00";
                            return null;
                        }
                        if ("isError".equals(name)) return Boolean.FALSE;
                        return null;
                    }
                });

        Map<String, String> out = CellPropertyExtractor.extract(cell);
        assertEquals("formatString still extracted", "#,##0.00", out.get("formatString"));
        assertFalse("foreColor that threw is silently absent", out.containsKey("foreColor"));
    }

    /* ------------------------ test helpers ------------------------ */

    private static Cell stubCell(
            Map<Property.StandardCellProperty, Object> values, boolean isError, String errorValue) {
        return (Cell) Proxy.newProxyInstance(
                CellPropertyExtractorTest.class.getClassLoader(), new Class<?>[] {Cell.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getPropertyValue".equals(name) && args != null && args.length == 1) {
                            Property p = (Property) args[0];
                            return values.get(p);
                        }
                        if ("isError".equals(name)) return isError;
                        if ("getValue".equals(name)) return errorValue;
                        return null;
                    }
                });
    }
}
