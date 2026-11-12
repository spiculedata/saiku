/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.olap4j.OlapException;
import org.olap4j.mdx.IdentifierSegment;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Member;
import org.saiku.olap.dto.SimpleCubeElement;

/**
 * saiku#2331: SQLMemberLookup hits come straight from the warehouse, so they must be re-checked
 * against the role-applied cube and capped.
 */
class OlapMetaExplorerSqlLookupRoleTest {

    private static final Member ANY_MEMBER =
            (Member) Proxy.newProxyInstance(Member.class.getClassLoader(), new Class<?>[] {Member.class}, (p, m, a) -> {
                throw new UnsupportedOperationException();
            });

    /** A cube whose role lets the caller see only the given unique names; the "boom" name throws. */
    private static Cube cubeVisible(Set<String> visibleUniqueNames) {
        return (Cube) Proxy.newProxyInstance(Cube.class.getClassLoader(), new Class<?>[] {Cube.class}, (p, m, a) -> {
            if (!"lookupMember".equals(m.getName())) {
                throw new UnsupportedOperationException(m.getName());
            }
            @SuppressWarnings("unchecked")
            List<IdentifierSegment> segs = (List<IdentifierSegment>) a[0];
            String un = segs.stream()
                    .map(IdentifierSegment::toString)
                    .reduce((x, y) -> x + "." + y)
                    .orElse("");
            if (un.contains("boom")) {
                throw new OlapException("lookup failed");
            }
            return visibleUniqueNames.contains(un) ? ANY_MEMBER : null;
        });
    }

    private static SimpleCubeElement hit(String name) {
        return new SimpleCubeElement(name, "[Customer].[" + name + "]", name);
    }

    @Test
    void dropsHitsTheRoleCannotSee() {
        Cube cube = cubeVisible(Set.of("[Customer].[Mine]"));
        List<SimpleCubeElement> hits = List.of(hit("Mine"), hit("Hidden"));

        List<SimpleCubeElement> out = OlapMetaExplorer.restrictToRoleAndLimit(cube, hits, 20);

        assertEquals(1, out.size());
        assertEquals("Mine", out.get(0).getName());
    }

    @Test
    void clampsToTheLimitAfterFiltering() {
        Cube cube = cubeVisible(Set.of("[Customer].[a]", "[Customer].[b]", "[Customer].[c]"));
        List<SimpleCubeElement> hits = List.of(hit("x"), hit("a"), hit("b"), hit("c"));

        List<SimpleCubeElement> out = OlapMetaExplorer.restrictToRoleAndLimit(cube, hits, 2);

        assertEquals(2, out.size());
        assertEquals("a", out.get(0).getName());
        assertEquals("b", out.get(1).getName());
    }

    @Test
    void nonPositiveLimitFallsBackToTheHardCap() {
        List<SimpleCubeElement> hits = new ArrayList<>();
        java.util.Set<String> visible = new java.util.HashSet<>();
        for (int i = 0; i < OlapMetaExplorer.MAX_SQL_LOOKUP_RESULTS + 50; i++) {
            hits.add(hit("m" + i));
            visible.add("[Customer].[m" + i + "]");
        }

        List<SimpleCubeElement> out = OlapMetaExplorer.restrictToRoleAndLimit(cubeVisible(visible), hits, -1);

        assertEquals(OlapMetaExplorer.MAX_SQL_LOOKUP_RESULTS, out.size());
    }

    @Test
    void failsClosedOnLookupErrorOrMissingUniqueName() {
        Cube cube = cubeVisible(Set.of());
        List<SimpleCubeElement> hits = List.of(hit("boom"), new SimpleCubeElement("n", null, "n"));

        assertTrue(OlapMetaExplorer.restrictToRoleAndLimit(cube, hits, 20).isEmpty());
    }
}
