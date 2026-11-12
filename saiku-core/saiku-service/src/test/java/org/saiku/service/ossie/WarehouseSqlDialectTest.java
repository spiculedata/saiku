/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** saiku#2319 — which warehouses may be trusted with a backslash inside a string literal. */
public class WarehouseSqlDialectTest {

    @Test
    public void mysqlAndMariaDbEscapeBackslashes() {
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("jdbc:mysql://db:3306/sales"));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("jdbc:mariadb://db/sales"));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("JDBC:MySQL://db/sales"));
    }

    @Test
    public void standardConformingEnginesAreTrusted() {
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:postgresql://db/sales"));
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:duckdb:/data/w.duckdb"));
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:quack:grpc://host:443"));
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:h2:mem:test"));
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:sqlserver://h;databaseName=x"));
        assertTrue(WarehouseSqlDialect.backslashIsLiteralData("jdbc:oracle:thin:@h:1521/x"));
    }

    @Test
    public void unknownOrMissingUrlFailsClosed() {
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData(null));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData(""));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("jdbc:snowflake://acct.snowflakecomputing.com"));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("jdbc:clickhouse://h/db"));
        assertFalse(WarehouseSqlDialect.backslashIsLiteralData("not a url"));
    }
}
