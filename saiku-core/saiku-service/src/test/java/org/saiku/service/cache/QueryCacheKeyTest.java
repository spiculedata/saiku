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
}
