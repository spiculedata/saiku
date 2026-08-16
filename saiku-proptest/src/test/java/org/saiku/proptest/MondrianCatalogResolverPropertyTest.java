/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.datasource.MondrianCatalogResolver;

/**
 * Property-based tests for {@link MondrianCatalogResolver} — the rule that decides whether a
 * {@code Catalog=} reference names a schema in the Saiku repository, and which repository paths to
 * try for it (saiku#1844).
 *
 * <p>Two things make this worth fuzzing rather than exampling. First, misclassifying a reference is
 * a security-adjacent mistake: claim a {@code file:} URL as a repository reference and the file
 * handler stops confining it; fail to claim a bare path and it falls through to the filesystem.
 * Second, the candidate paths are fed straight to a repository read, so a candidate that escapes the
 * repository root would be a traversal.
 */
class MondrianCatalogResolverPropertyTest {

    /** Schemes Mondrian resolves itself — the resolver must never claim these. */
    private static final List<String> FOREIGN_SCHEMES =
            List.of("file:", "http://", "https://", "ftp://", "jar:file:", "res:");

    /**
     * A reference carrying a scheme Mondrian already understands is never claimed as a repository
     * reference. Claiming one would route it past the containment guard.
     */
    @HegelTest
    void neverClaimsAReferenceWithAForeignScheme(TestCase tc) {
        String scheme = tc.draw(sampledFrom(FOREIGN_SCHEMES), "scheme");
        String rest = tc.draw(fromRegex("[a-zA-Z0-9/._-]{0,30}"), "rest");

        String ref = scheme + rest;

        assertFalse(MondrianCatalogResolver.isRepositoryReference(ref), "wrongly claimed: " + ref);
    }

    /** Anything behind the mondrian scheme is always claimed — that scheme is ours by definition. */
    @HegelTest
    void alwaysClaimsTheMondrianScheme(TestCase tc) {
        String rest = tc.draw(fromRegex("[a-zA-Z0-9/._-]{1,30}"), "rest");

        assertTrue(MondrianCatalogResolver.isRepositoryReference(MondrianCatalogResolver.SCHEME + rest));
    }

    /**
     * Candidate paths never escape the repository root. They are handed to a repository read, so a
     * candidate containing an unresolved {@code ..} segment would be a traversal into the host
     * filesystem.
     */
    @HegelTest
    void candidatePathsNeverEscapeTheRepositoryRoot(TestCase tc) {
        String ref = tc.draw(fromRegex("(mondrian://)?[a-zA-Z0-9/._-]{0,30}"), "ref");

        for (String candidate : MondrianCatalogResolver.candidatePaths(ref)) {
            tc.note("candidate=" + candidate);
            assertTrue(candidate.startsWith("/"), "candidate is not repository-absolute: " + candidate);
            // Normalising must not walk above the root — i.e. the path may not begin with "/..".
            String normalised = java.nio.file.Path.of(candidate).normalize().toString();
            assertFalse(normalised.startsWith("/.."), "candidate escapes the repository root: " + candidate);
        }
    }

    /** Candidates are always distinct — each one costs a repository round trip. */
    @HegelTest
    void candidatePathsAreDistinct(TestCase tc) {
        String ref = tc.draw(fromRegex("(mondrian://)?[a-zA-Z0-9/._-]{0,30}"), "ref");

        List<String> candidates = MondrianCatalogResolver.candidatePaths(ref);

        assertEquals(
                candidates.size(),
                candidates.stream().distinct().count(),
                "duplicate candidate for " + ref + ": " + candidates);
    }

    /**
     * Resolution returns the FIRST candidate that hits, and only ever content the fetcher actually
     * supplied — the resolver must not invent, trim or merge.
     */
    @HegelTest
    void resolveReturnsTheFirstCandidateThatHits(TestCase tc) {
        String name = tc.draw(fromRegex("[A-Za-z][A-Za-z0-9_-]{0,12}"), "name");
        String bodyA = tc.draw(fromRegex("<Schema name='[A-Za-z]{1,8}'/>"), "bodyA");
        String bodyB = tc.draw(fromRegex("<Schema name='[A-Za-z]{1,8}'/>"), "bodyB");

        String ref = MondrianCatalogResolver.SCHEME + name;
        List<String> candidates = MondrianCatalogResolver.candidatePaths(ref);
        tc.assume(candidates.size() >= 2);

        // Populate BOTH candidates with different content; the first must win.
        Map<String, String> repo = new HashMap<>();
        repo.put(candidates.get(0), bodyA);
        repo.put(candidates.get(1), bodyB);

        assertEquals(bodyA, MondrianCatalogResolver.resolve(ref, repo::get));
    }

    /** Falls through to later candidates when earlier ones are absent. */
    @HegelTest
    void resolveFallsThroughToLaterCandidates(TestCase tc) {
        String name = tc.draw(fromRegex("[A-Za-z][A-Za-z0-9_-]{0,12}"), "name");
        String body = tc.draw(fromRegex("<Schema name='[A-Za-z]{1,8}'/>"), "body");

        String ref = MondrianCatalogResolver.SCHEME + name;
        List<String> candidates = MondrianCatalogResolver.candidatePaths(ref);
        tc.assume(candidates.size() >= 2);

        Map<String, String> repo = new HashMap<>();
        repo.put(candidates.get(candidates.size() - 1), body); // only the LAST one exists

        assertEquals(body, MondrianCatalogResolver.resolve(ref, repo::get));
    }

    /**
     * Blank content counts as absent. A truncated or empty schema file must never be handed to
     * Mondrian as though it were valid — that surfaces as a baffling parse error far from the cause.
     */
    @HegelTest
    void blankRepositoryContentIsTreatedAsAbsent(TestCase tc) {
        String name = tc.draw(fromRegex("[A-Za-z][A-Za-z0-9_-]{0,12}"), "name");
        String blank = tc.draw(sampledFrom(List.of("", " ", "\t", "\n", "  \n  ")), "blank");

        assertNull(MondrianCatalogResolver.resolve(MondrianCatalogResolver.SCHEME + name, p -> blank));
    }

    /** Every path the resolver probes is one it declared — no hidden lookups. */
    @HegelTest
    void resolveOnlyProbesItsDeclaredCandidates(TestCase tc) {
        String ref = tc.draw(fromRegex("(mondrian://)?[A-Za-z][A-Za-z0-9/._-]{0,20}"), "ref");

        List<String> declared = MondrianCatalogResolver.candidatePaths(ref);
        List<String> probed = new ArrayList<>();
        MondrianCatalogResolver.resolve(ref, p -> {
            probed.add(p);
            return null;
        });

        assertTrue(declared.containsAll(probed), "probed an undeclared path: " + probed + " vs " + declared);
    }

    /** Total: neither classification nor candidate generation throws on any input. */
    @HegelTest
    void classificationAndCandidatesAreTotal(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z0-9:;=/._%$~ -]{0,50}"), "junk");

        assertDoesNotThrow(() -> MondrianCatalogResolver.isRepositoryReference(junk));
        assertDoesNotThrow(() -> MondrianCatalogResolver.candidatePaths(junk));
        assertDoesNotThrow(() -> MondrianCatalogResolver.resolve(junk, p -> null));
    }
}
