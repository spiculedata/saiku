/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie;

import java.util.List;
import java.util.Locale;

/**
 * Classifies an Ossie warehouse by its JDBC URL for one question: does a backslash inside a
 * single-quoted SQL string literal mean just a backslash (SQL standard), or is it an escape
 * character (MySQL, MariaDB, BigQuery, Snowflake, ClickHouse, Spark/Hive, ...)?
 *
 * <p>It matters because the Calcite adapter pushes filters down to the warehouse and re-emits
 * string literals through the warehouse's dialect, which only doubles single quotes. On a
 * backslash-escaping engine a value ending {@code \'} therefore breaks out of the literal
 * (saiku#2319). This is an allow-list: only engines known to treat the backslash literally are
 * trusted; an unknown, missing or unparseable URL is treated as backslash-escaping (fail closed).
 */
public final class WarehouseSqlDialect {

    private static final List<String> BACKSLASH_IS_DATA_PREFIXES = List.of(
            "jdbc:postgresql:",
            "jdbc:duckdb:",
            "jdbc:quack:",
            "jdbc:h2:",
            "jdbc:hsqldb:",
            "jdbc:sqlserver:",
            "jdbc:oracle:",
            "jdbc:sqlite:",
            "jdbc:derby:",
            "jdbc:db2:");

    private WarehouseSqlDialect() {}

    /** True only for a URL of a known engine whose string literals treat {@code \} as plain data. */
    public static boolean backslashIsLiteralData(String jdbcUrl) {
        if (jdbcUrl == null) return false;
        String url = jdbcUrl.trim().toLowerCase(Locale.ROOT);
        for (String prefix : BACKSLASH_IS_DATA_PREFIXES) {
            if (url.startsWith(prefix)) return true;
        }
        return false;
    }
}
