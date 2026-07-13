/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server.pgwire;

import java.sql.Types;

/**
 * Postgres type OID mapping. Each PG type has a well-known OID (from
 * {@code src/include/catalog/pg_type.dat} in the Postgres source) that clients read from
 * RowDescription messages to know how to parse each column.
 *
 * <p>We only care about the subset a read-only BI-tool workload actually queries. Anything more
 * exotic gets mapped to TEXT — clients still receive the string representation, which is what
 * simple query mode's text format returns anyway.
 */
final class PgType {

    /** BOOL. */
    static final int BOOL = 16;
    /** INT2 (smallint). */
    static final int INT2 = 21;
    /** INT4 (integer). */
    static final int INT4 = 23;
    /** INT8 (bigint). */
    static final int INT8 = 20;
    /** FLOAT4 (real). */
    static final int FLOAT4 = 700;
    /** FLOAT8 (double precision). */
    static final int FLOAT8 = 701;
    /** NUMERIC / DECIMAL. */
    static final int NUMERIC = 1700;
    /** TEXT — Postgres's arbitrary-length string type. */
    static final int TEXT = 25;
    /** VARCHAR — length-limited string. */
    static final int VARCHAR = 1043;
    /** DATE. */
    static final int DATE = 1082;
    /** TIME. */
    static final int TIME = 1083;
    /** TIMESTAMP (without time zone). */
    static final int TIMESTAMP = 1114;
    /** TIMESTAMPTZ (with time zone). */
    static final int TIMESTAMPTZ = 1184;

    private PgType() {}

    /**
     * Translate a {@link java.sql.Types} constant into the corresponding PG type OID. Anything
     * unrecognised falls through to {@link #TEXT}, which is safe because simple-query text
     * format lets clients coerce as needed.
     */
    static int fromJdbcType(int jdbcType) {
        switch (jdbcType) {
            case Types.BOOLEAN:
            case Types.BIT:
                return BOOL;
            case Types.SMALLINT:
            case Types.TINYINT:
                return INT2;
            case Types.INTEGER:
                return INT4;
            case Types.BIGINT:
                return INT8;
            case Types.REAL:
                return FLOAT4;
            case Types.FLOAT:
            case Types.DOUBLE:
                return FLOAT8;
            case Types.NUMERIC:
            case Types.DECIMAL:
                return NUMERIC;
            case Types.CHAR:
            case Types.VARCHAR:
                return VARCHAR;
            case Types.LONGVARCHAR:
                return TEXT;
            case Types.DATE:
                return DATE;
            case Types.TIME:
                return TIME;
            case Types.TIMESTAMP:
                return TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return TIMESTAMPTZ;
            default:
                return TEXT;
        }
    }
}
