/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License 2.0.
 */
package org.saiku.web.rest.util;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.saiku.olap.util.QueryGuardrails;
import org.saiku.web.rest.objects.resultset.QueryResult;

/** saiku#2327: {@link RestUtil#convert(ResultSet)} must never buffer more than the server row ceiling. */
public class RestUtilConvertTest {

    @Test
    public void convert_defaultLimit_capsRowsAtGuardrail() throws Exception {
        int ceiling = QueryGuardrails.maxRows();
        AtomicInteger consumed = new AtomicInteger();

        QueryResult result = RestUtil.convert(fakeResultSet(ceiling + 10, consumed));

        // height counts data rows only (the header row is not counted)
        assertEquals(ceiling, (int) result.getHeight());
    }

    @Test
    public void convert_smallResult_isReturnedInFull() throws Exception {
        QueryResult result = RestUtil.convert(fakeResultSet(3, new AtomicInteger()));
        assertEquals(3, (int) result.getHeight());
    }

    private static ResultSet fakeResultSet(int totalRows, AtomicInteger cursor) {
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(
                RestUtilConvertTest.class.getClassLoader(), new Class<?>[] {ResultSetMetaData.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getColumnCount":
                            return 1;
                        case "getColumnName":
                            return "c";
                        case "getColumnType":
                            return Types.VARCHAR;
                        default:
                            return null;
                    }
                });
        return (ResultSet) Proxy.newProxyInstance(
                RestUtilConvertTest.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "next":
                            return cursor.incrementAndGet() <= totalRows;
                        case "getMetaData":
                            return md;
                        case "getString":
                        case "getObject":
                            return "v";
                        case "wasNull":
                            return false;
                        default:
                            return null;
                    }
                });
    }
}
