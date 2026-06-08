/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.cache.QueryCacheKey;
import org.saiku.service.user.UserService;

/**
 * saiku#1114 <b>call-site</b> (wiring) coverage. {@code QueryCacheKeyTest} already proves {@link
 * QueryCacheKey#of} is role-aware; this proves {@link ThinQueryService}'s cache-key build
 * actually FEEDS the current user's role-set into it. The original #1114 leak was precisely a
 * call-site omission, so a future revert of the call-site back to a role-blind key would reopen
 * the cross-role cache leak with the {@code QueryCacheKey} unit test still green — this turns
 * that regression RED.
 *
 * <p>Exercises the package-private {@link ThinQueryService#cacheKeyFor(ThinQuery, String)} seam
 * (the exact key {@code executeCached} computes) with a stub {@link UserService}, so no live
 * olap connection is needed.
 */
public class ThinQueryServiceCacheKeyTest {

    private static final String CUBE_VERSION = "conn|cat|schema|Sales";

    private static ThinQuery query() {
        ThinQuery q = new ThinQuery();
        q.setMdx("SELECT {[Measures].[Sales]} ON 0 FROM [Sales]");
        return q;
    }

    private static ThinQueryService serviceWithRoles(final String... roles) {
        ThinQueryService s = new ThinQueryService();
        s.setUserService(new UserService() {
            @Override
            public String[] getCurrentUserRoles() {
                return roles;
            }
        });
        return s;
    }

    /** The crux: the call-site must mix roles in, so two roles can't share a cached cellset. */
    @Test
    public void callSiteMixesRolesIn_differentRolesDifferentKeys() {
        String reader = serviceWithRoles("reader").cacheKeyFor(query(), CUBE_VERSION);
        String manager = serviceWithRoles("manager").cacheKeyFor(query(), CUBE_VERSION);
        assertNotEquals("executeCached's key must include the session role-set (saiku#1114)", reader, manager);
    }

    /** A role-bearing key must differ from the role-less / legacy key. */
    @Test
    public void roleKeyDiffersFromLegacyKey() {
        String roled = serviceWithRoles("reader").cacheKeyFor(query(), CUBE_VERSION);
        assertNotEquals(QueryCacheKey.of(query(), CUBE_VERSION), roled);
    }

    /** No UserService (boot / standalone) ⇒ role-less key, byte-identical to the legacy two-arg key. */
    @Test
    public void noUserServiceDegradesToLegacyKey() {
        ThinQueryService s = new ThinQueryService(); // userService unset
        assertEquals(QueryCacheKey.of(query(), CUBE_VERSION), s.cacheKeyFor(query(), CUBE_VERSION));
    }

    /** A UserService that throws (no security context) must degrade to the legacy key, never fail the query. */
    @Test
    public void throwingUserServiceDegradesToLegacyKey() {
        ThinQueryService s = new ThinQueryService();
        s.setUserService(new UserService() {
            @Override
            public String[] getCurrentUserRoles() {
                throw new IllegalStateException("no security context");
            }
        });
        assertEquals(QueryCacheKey.of(query(), CUBE_VERSION), s.cacheKeyFor(query(), CUBE_VERSION));
    }
}
