/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.drillthrough.DrillThroughResult;

/**
 * {@link ThinQueryService#drillthroughWithCaptions} opens the drillthrough {@link ResultSet} first
 * and resolves the column captions afterwards. When that lookup fails the ResultSet never reaches
 * the caller, so the service must close it itself; the engine only returns the statement's slot
 * on {@code ResultSet.close()}.
 */
public class ThinQueryServiceDrillthroughCaptionsCloseTest {

    private static final SaikuCube CUBE = new SaikuCube("c", "[Sales]", "Sales", "Sales", "FoodMart", "FoodMart");

    @Test
    public void captionLookupFailure_closesTheResultSet_andRethrows() {
        AtomicBoolean closed = new AtomicBoolean();
        ThinQueryService service = serviceReturning(trackingResultSet(closed), Collections.emptyList());

        try {
            // No measure of that name is known, so the caption lookup throws.
            service.drillthroughWithCaptions("q", Arrays.asList(0, 0), 1, "[Measures].[Unit Sales]");
            fail("expected the caption lookup to fail");
        } catch (NoSuchElementException expected) {
            // the original exception reaches the caller unchanged
        }

        assertTrue("ResultSet must be closed when the caption lookup fails", closed.get());
    }

    @Test
    public void successfulCaptionLookup_leavesTheResultSetOpenForTheCaller() {
        AtomicBoolean closed = new AtomicBoolean();
        ResultSet rs = trackingResultSet(closed);
        SaikuMember unitSales = new SaikuMember("Unit Sales", "[Measures].[Unit Sales]", "Units", "", "", "", "");
        ThinQueryService service = serviceReturning(rs, Collections.singletonList(unitSales));

        DrillThroughResult result =
                service.drillthroughWithCaptions("q", Arrays.asList(0, 0), 1, "[Measures].[Unit Sales]");

        assertSame(rs, result.getResultSet());
        assertEquals("Units", result.getSimpleHeaders()[0]);
        assertFalse("the caller reads and closes the ResultSet", closed.get());
    }

    private static ThinQueryService serviceReturning(final ResultSet rs, final List<SaikuMember> measures) {
        ThinQueryService service = new ThinQueryService() {
            @Override
            public ResultSet drillthrough(
                    String queryName, List<Integer> cellPosition, Integer maxrows, String returns) {
                return rs;
            }
        };
        service.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public List<SaikuMember> getMeasures(SaikuCube cube) {
                return measures;
            }
        });
        service.registerExternalContext(new ThinQuery("q", CUBE), null);
        return service;
    }

    private static ResultSet trackingResultSet(final AtomicBoolean closed) {
        final ClassLoader cl = ThinQueryServiceDrillthroughCaptionsCloseTest.class.getClassLoader();
        final ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(
                cl,
                new Class<?>[] {ResultSetMetaData.class},
                (p, m, a) -> "getColumnCount".equals(m.getName()) ? 1 : defaultValue(m.getReturnType()));
        return (ResultSet) Proxy.newProxyInstance(cl, new Class<?>[] {ResultSet.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getMetaData":
                    return md;
                case "close":
                    closed.set(true);
                    return null;
                case "isClosed":
                    return closed.get();
                default:
                    return defaultValue(m.getReturnType());
            }
        });
    }

    private static Object defaultValue(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt.isPrimitive()) return 0;
        return null;
    }
}
