/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;

/**
 * saiku#1114: the cellset cache key must include the active session's Mondrian role-set, or a
 * role-masked cellset can be served from cache to a different role — a silent cross-role data
 * leak. These tests lock that the role-set participates in the key, is order/duplicate
 * insensitive, and that an empty role-set stays byte-compatible with the legacy two-arg key so
 * existing single-role caches keep hitting.
 */
public class QueryCacheKeyTest {

    private static final String CUBE_VERSION = "conn|cat|schema|Sales";

    private static ThinQuery query() {
        ThinQuery q = new ThinQuery();
        q.setMdx("SELECT {[Measures].[Sales]} ON 0 FROM [Sales]");
        return q;
    }

    /** The security crux: same MDX, different roles ⇒ different cache key (no shared cellset). */
    @Test
    public void differentRolesProduceDifferentKeys() {
        String reader = QueryCacheKey.of(query(), CUBE_VERSION, Collections.singletonList("reader"));
        String manager = QueryCacheKey.of(query(), CUBE_VERSION, Collections.singletonList("manager"));
        assertNotEquals("a role-masked cellset must not be keyed identically across roles", reader, manager);
    }

    /** The key is a set: binding order and duplicates must not change it. */
    @Test
    public void roleOrderAndDuplicatesDoNotMatter() {
        String a = QueryCacheKey.of(query(), CUBE_VERSION, Arrays.asList("admin", "reader"));
        String b = QueryCacheKey.of(query(), CUBE_VERSION, Arrays.asList("reader", "admin", "admin"));
        assertEquals(a, b);
    }

    /** Same query + same role-set ⇒ same key (so the second execution still hits cache). */
    @Test
    public void sameQuerySameRolesAreStable() {
        String a = QueryCacheKey.of(query(), CUBE_VERSION, Arrays.asList("reader"));
        String b = QueryCacheKey.of(query(), CUBE_VERSION, Arrays.asList(" reader ")); // trimmed
        assertEquals(a, b);
    }

    /** Backward-compat: an empty/null role-set hashes identically to the legacy two-arg key. */
    @Test
    public void emptyRolesMatchLegacyTwoArgKey() {
        String legacy = QueryCacheKey.of(query(), CUBE_VERSION);
        assertEquals(legacy, QueryCacheKey.of(query(), CUBE_VERSION, null));
        assertEquals(legacy, QueryCacheKey.of(query(), CUBE_VERSION, Collections.emptyList()));
        // and a role-bearing key must differ from the legacy/no-role key
        assertNotEquals(legacy, QueryCacheKey.of(query(), CUBE_VERSION, Collections.singletonList("reader")));
    }

    // ── saiku#1483: cubeVersion must change when the connection's metadata epoch bumps ──

    private static ThinQuery cubeQuery(String connection) {
        ThinQuery q = query();
        q.setCube(new org.saiku.olap.dto.SaikuCube(connection, "[Sales]", "Sales", "Sales", "FoodMart", "FoodMart"));
        return q;
    }

    /** A schema reload (epoch bump) must produce a different fingerprint — the stale-cellset bug. */
    @Test
    public void cubeVersionChangesAfterEpochBump() {
        CubeMetadataVersions.resetForTests();
        ThinQuery q = cubeQuery("conn-a");
        String before = QueryCacheKey.cubeVersion(q);
        CubeMetadataVersions.bump("conn-a");
        String after = QueryCacheKey.cubeVersion(q);
        assertNotEquals("a schema reload must invalidate the cache key", before, after);
        // and the full key changes with it
        assertNotEquals(QueryCacheKey.of(q, before), QueryCacheKey.of(q, after));
    }

    /** Bumping one connection must not invalidate another connection's entries. */
    @Test
    public void epochBumpIsScopedToItsConnection() {
        CubeMetadataVersions.resetForTests();
        ThinQuery other = cubeQuery("conn-b");
        String before = QueryCacheKey.cubeVersion(other);
        CubeMetadataVersions.bump("conn-a");
        assertEquals("conn-a reload must not bust conn-b's cache", before, QueryCacheKey.cubeVersion(other));
    }

    /** Stable between reloads: two computations with no bump in between are identical. */
    @Test
    public void cubeVersionStableWithoutReload() {
        CubeMetadataVersions.resetForTests();
        ThinQuery q = cubeQuery("conn-c");
        assertEquals(QueryCacheKey.cubeVersion(q), QueryCacheKey.cubeVersion(q));
    }

    /** Null-safety: no cube (or null connection) keeps the legacy empty-fingerprint behaviour. */
    @Test
    public void cubeVersionNullSafety() {
        assertEquals("", QueryCacheKey.cubeVersion(null));
        assertEquals("", QueryCacheKey.cubeVersion(query())); // no cube set
    }
}
