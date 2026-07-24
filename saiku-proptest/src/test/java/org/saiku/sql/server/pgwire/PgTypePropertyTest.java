/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server.pgwire;

import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Set;

/**
 * {@link PgType#fromJdbcType(int)} maps {@code java.sql.Types} constants to Postgres type OIDs, with
 * anything unrecognised falling through to TEXT. Lives in the {@code pgwire} package so it can reach
 * the package-private type and its OID constants. Properties: totality (every int maps into the
 * known OID set without throwing), determinism, and the TEXT fallback for unknown types.
 */
class PgTypePropertyTest {

    /** The complete set of OIDs {@code fromJdbcType} is allowed to return. */
    private static final Set<Integer> KNOWN_OIDS = Set.of(
            PgType.BOOL,
            PgType.INT2,
            PgType.INT4,
            PgType.INT8,
            PgType.FLOAT4,
            PgType.FLOAT8,
            PgType.NUMERIC,
            PgType.TEXT,
            PgType.VARCHAR,
            PgType.DATE,
            PgType.TIME,
            PgType.TIMESTAMP,
            PgType.TIMESTAMPTZ);

    /** For any int, the result is a known OID and the call never throws. */
    @HegelTest
    void resultIsAlwaysAKnownOid(TestCase tc) {
        int jdbcType = tc.draw(integers(), "jdbcType");

        int oid = PgType.fromJdbcType(jdbcType);

        assertTrue(KNOWN_OIDS.contains(oid), "OID " + oid + " (from " + jdbcType + ") must be a known PG type OID");
    }

    /** The mapping is a pure function: the same input always yields the same OID. */
    @HegelTest
    void mappingIsDeterministic(TestCase tc) {
        int jdbcType = tc.draw(integers(), "jdbcType");

        assertEquals(PgType.fromJdbcType(jdbcType), PgType.fromJdbcType(jdbcType), "same input must map to same OID");
    }

    /** Values well outside the java.sql.Types range are unknown and fall through to TEXT. */
    @HegelTest
    void unknownTypesFallThroughToText(TestCase tc) {
        // java.sql.Types constants all sit within [-16, 2014]; 100000+ is guaranteed unrecognised.
        int jdbcType = tc.draw(integers().map(n -> 100_000 + Math.floorMod(n, 1_000_000)), "jdbcType");

        assertEquals(PgType.TEXT, PgType.fromJdbcType(jdbcType), "unknown JDBC type must map to TEXT");
    }
}
