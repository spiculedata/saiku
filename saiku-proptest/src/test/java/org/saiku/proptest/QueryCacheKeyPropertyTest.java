/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.cache.QueryCacheKey;

/**
 * Property-based tests for {@link QueryCacheKey}.
 *
 * <p>A cache key is an access-control boundary in disguise. If two requests that should see
 * DIFFERENT data hash to the same key, the second one is served the first one's cellset — a silent
 * cross-user or cross-role data leak that no amount of endpoint authorisation catches, because the
 * check already passed. saiku#1114 was exactly this: two users with different Mondrian roles running
 * the same MDX shared one cached result.
 *
 * <p>So the property to assert is not "the key is stable" but its contrapositive:
 *
 * <blockquote>
 * any change to something that can change the RESULT must change the key.
 * </blockquote>
 */
class QueryCacheKeyPropertyTest {

    private static SaikuCube cube(String connection, String catalog, String schema, String name) {
        return new SaikuCube(connection, "[" + name + "]", name, name, catalog, schema);
    }

    private static SaikuCube defaultCube() {
        return cube("conn", "cat", "sch", "Sales");
    }

    private static ThinQuery mdxQuery(String mdx) {
        return new ThinQuery("ignored-name", defaultCube(), mdx);
    }

    // --- determinism -----------------------------------------------------------

    /** Identical inputs always hash identically — otherwise the cache never hits at all. */
    @HegelTest
    void identicalInputsHashIdentically(TestCase tc) {
        String mdx =
                tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z ]{1,10}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        String version = tc.draw(fromRegex("[a-z0-9|]{0,20}"), "version");
        List<String> roles = tc.draw(lists(fromRegex("[A-Z_]{1,10}")).minSize(0).maxSize(4), "roles");

        assertEquals(QueryCacheKey.of(mdxQuery(mdx), version, roles), QueryCacheKey.of(mdxQuery(mdx), version, roles));
    }

    /** The client-only query name never affects the key — it is a random UUID per request. */
    @HegelTest
    void theQueryNameIsExcludedFromTheKey(TestCase tc) {
        String mdx = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        String nameA = tc.draw(fromRegex("[a-z0-9-]{1,20}"), "nameA");
        String nameB = tc.draw(fromRegex("[a-z0-9-]{1,20}"), "nameB");

        String keyA = QueryCacheKey.of(new ThinQuery(nameA, defaultCube(), mdx), "v1");
        String keyB = QueryCacheKey.of(new ThinQuery(nameB, defaultCube(), mdx), "v1");

        assertEquals(keyA, keyB, "the client-side query name leaked into the cache key");
    }

    // --- the security property -------------------------------------------------

    /**
     * THE property (saiku#1114). Two different role-sets must never share a key: with Mondrian
     * {@code <Role>} schema masking, the same MDX returns different data per role.
     */
    @HegelTest
    void differentRoleSetsNeverShareAKey(TestCase tc) {
        String mdx = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        List<String> rolesA = tc.draw(lists(fromRegex("[A-Z]{1,8}")).minSize(0).maxSize(3), "rolesA");
        List<String> rolesB = tc.draw(lists(fromRegex("[A-Z]{1,8}")).minSize(0).maxSize(3), "rolesB");

        // Compare as canonical sets — that's what the key is meant to be keyed on.
        java.util.TreeSet<String> setA = new java.util.TreeSet<>(rolesA);
        java.util.TreeSet<String> setB = new java.util.TreeSet<>(rolesB);
        tc.assume(!setA.equals(setB));

        assertNotEquals(
                QueryCacheKey.of(mdxQuery(mdx), "v1", rolesA),
                QueryCacheKey.of(mdxQuery(mdx), "v1", rolesB),
                "role-sets " + setA + " and " + setB + " collided — cross-role cache leak");
    }

    /** Role binding ORDER and duplication are irrelevant — the same set must hit the same entry. */
    @HegelTest
    void roleOrderAndDuplicationDoNotAffectTheKey(TestCase tc) {
        String mdx = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        List<String> roles = tc.draw(lists(fromRegex("[A-Z]{1,8}")).minSize(1).maxSize(4), "roles");

        List<String> shuffled = new ArrayList<>(roles);
        Collections.reverse(shuffled);
        List<String> duplicated = new ArrayList<>(shuffled);
        duplicated.addAll(roles);

        String base = QueryCacheKey.of(mdxQuery(mdx), "v1", roles);

        assertEquals(base, QueryCacheKey.of(mdxQuery(mdx), "v1", shuffled), "key depended on role order");
        assertEquals(base, QueryCacheKey.of(mdxQuery(mdx), "v1", duplicated), "key depended on role duplication");
    }

    /** No roles and an empty role-set are the same thing — and stay compatible with legacy keys. */
    @HegelTest
    void anEmptyRoleSetMatchesTheLegacyNoRoleKey(TestCase tc) {
        String mdx = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        List<String> blankish =
                tc.draw(lists(sampledFrom(List.of("", " ", "\t"))).minSize(0).maxSize(3), "blankish");

        String legacy = QueryCacheKey.of(mdxQuery(mdx), "v1");

        assertEquals(legacy, QueryCacheKey.of(mdxQuery(mdx), "v1", null), "null roles diverged from the legacy key");
        assertEquals(
                legacy,
                QueryCacheKey.of(mdxQuery(mdx), "v1", Collections.emptyList()),
                "empty roles diverged from the legacy key");
        assertEquals(
                legacy, QueryCacheKey.of(mdxQuery(mdx), "v1", blankish), "blank roles diverged from the legacy key");
    }

    // --- anything that changes the result changes the key ----------------------

    /** Different MDX must never share a key. */
    @HegelTest
    void differentMdxNeverSharesAKey(TestCase tc) {
        String a = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "a");
        String b = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "b");
        tc.assume(!a.equals(b));

        assertNotEquals(QueryCacheKey.of(mdxQuery(a), "v1"), QueryCacheKey.of(mdxQuery(b), "v1"), "MDX collided");
    }

    /** A changed cube-version fingerprint must invalidate — that is how a schema reload takes effect. */
    @HegelTest
    void aChangedCubeVersionInvalidatesTheKey(TestCase tc) {
        String mdx = tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z]{1,8}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        String v1 = tc.draw(fromRegex("[a-z0-9]{1,12}"), "v1");
        String v2 = tc.draw(fromRegex("[a-z0-9]{1,12}"), "v2");
        tc.assume(!v1.equals(v2));

        assertNotEquals(
                QueryCacheKey.of(mdxQuery(mdx), v1),
                QueryCacheKey.of(mdxQuery(mdx), v2),
                "a schema reload would keep serving stale cellsets");
    }

    /** Different cube coordinates must never share a key, even with identical MDX. */
    @HegelTest
    void differentCubeCoordinatesNeverShareAKey(TestCase tc) {
        String mdx = "SELECT {[Measures].[Unit Sales]} ON 0 FROM [Sales]";
        String connA = tc.draw(fromRegex("[a-z]{1,8}"), "connA");
        String connB = tc.draw(fromRegex("[a-z]{1,8}"), "connB");
        tc.assume(!connA.equals(connB));

        String keyA = QueryCacheKey.of(new ThinQuery("n", cube(connA, "cat", "sch", "Sales"), mdx), "v1");
        String keyB = QueryCacheKey.of(new ThinQuery("n", cube(connB, "cat", "sch", "Sales"), mdx), "v1");

        assertNotEquals(keyA, keyB, "two connections shared a cache key — cross-connection leak");
    }

    // --- shape -----------------------------------------------------------------

    /** The key is always a full SHA-256 hex digest — nothing truncated, nothing empty. */
    @HegelTest
    void theKeyIsAlwaysAFullSha256HexDigest(TestCase tc) {
        String mdx =
                tc.draw(fromRegex("SELECT \\{\\[Measures\\].\\[[A-Za-z ]{1,10}\\]\\} ON 0 FROM \\[Sales\\]"), "mdx");
        List<String> roles = tc.draw(lists(fromRegex("[A-Z_]{1,8}")).minSize(0).maxSize(3), "roles");

        String key = QueryCacheKey.of(mdxQuery(mdx), "v1", roles);

        assertEquals(64, key.length(), "not a SHA-256 digest: " + key);
        assertTrue(key.matches("[0-9a-f]{64}"), "not lowercase hex: " + key);
    }

    /**
     * Computing a cache key must not MUTATE the query it is asked to hash.
     *
     * <p>saiku#1847: {@code sortMembersOnly} sorted each selection's member list in place, on the
     * caller's object. Member order is semantically a set for hashing purposes — but it is NOT a set
     * for execution: {@code ThinQueryService.execute} builds the coalescing key BEFORE calling
     * {@code executeInternalQuery}, which calls {@code updateQuery} → {@code Fat.convert} to
     * regenerate the MDX from the query model. So the alphabetised order became the order in the
     * emitted MDX, and the user got their rows back sorted by member name instead of in the order
     * they selected them.
     *
     * <p>Observed directly before the fix — hashing a selection of
     * {@code Zulu, Alpha, Mike} left the caller's query holding {@code Alpha, Mike, Zulu}.
     */
    @HegelTest
    void hashingAQueryNeverReordersTheCallersMembers(TestCase tc) {
        List<String> names =
                tc.draw(lists(fromRegex("[A-Za-z]{1,10}")).minSize(2).maxSize(6), "names");
        tc.assume(names.size() == new java.util.HashSet<>(names).size());

        ThinQuery q = ThinQueryFixtures.rowsQuery(defaultCube(), names);
        List<String> before = ThinQueryFixtures.memberOrder(q);

        QueryCacheKey.of(q, "v1");

        assertEquals(before, ThinQueryFixtures.memberOrder(q), "computing a cache key reordered the caller's members");
    }

    /** ...and the key must still be order-independent, which is the reason the sort exists at all. */
    @HegelTest
    void theKeyStaysIndependentOfMemberSelectionOrder(TestCase tc) {
        List<String> names =
                tc.draw(lists(fromRegex("[A-Za-z]{1,10}")).minSize(2).maxSize(6), "names");
        tc.assume(names.size() == new java.util.HashSet<>(names).size());

        List<String> reversed = new ArrayList<>(names);
        Collections.reverse(reversed);

        assertEquals(
                QueryCacheKey.of(ThinQueryFixtures.rowsQuery(defaultCube(), names), "v1"),
                QueryCacheKey.of(ThinQueryFixtures.rowsQuery(defaultCube(), reversed), "v1"),
                "the same member SET in a different order produced a different key");
    }

    /**
     * {@code cubeVersion} joins its four coordinates with {@code |}. A coordinate that itself
     * contains {@code |} therefore shifts the field boundaries, so two different cubes can produce
     * the same fingerprint string.
     *
     * <p>Pinned as a known limitation rather than a live vulnerability: {@code of()} also serialises
     * the whole {@link SaikuCube} into the canonical JSON, so the composite key still separates the
     * two. This asserts BOTH facts — the fingerprint collides, and the real key does not — so if
     * anyone ever keys a cache on {@code cubeVersion} alone, this test says why that is unsafe.
     */
    @HegelTest
    void cubeVersionFingerprintIsAmbiguousButTheRealKeyIsNot(TestCase tc) {
        String left = tc.draw(fromRegex("[a-z]{1,6}"), "left");
        String right = tc.draw(fromRegex("[a-z]{1,6}"), "right");

        // "a|b" + "c"  vs  "a" + "b|c"  →  identical "a|b|c|..." fingerprints.
        ThinQuery q1 = new ThinQuery("n", cube(left + "|" + right, "cat", "sch", "Sales"), "SELECT FROM [Sales]");
        ThinQuery q2 = new ThinQuery("n", cube(left, right + "|cat", "sch", "Sales"), "SELECT FROM [Sales]");

        assertEquals(
                QueryCacheKey.cubeVersion(q1),
                QueryCacheKey.cubeVersion(q2),
                "behaviour changed — the separator is now escaped, so this note can be removed");

        // ...but the composite key still separates them, because the cube itself is serialised.
        assertNotEquals(
                QueryCacheKey.of(q1, QueryCacheKey.cubeVersion(q1)),
                QueryCacheKey.of(q2, QueryCacheKey.cubeVersion(q2)),
                "the ambiguous fingerprint became a REAL cache collision");
    }
}
