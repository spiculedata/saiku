/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.saiku.olap.query2.ThinQuery;

/**
 * An admin-approved saved query the agent invokes VERBATIM (saiku#1430).
 *
 * <p>The problem this solves: a regulated buyer whose CFO won't tolerate the agent inventing a
 * "net revenue" calculation afresh on every ask. An operator certifies the query once — the exact
 * {@link ThinQuery}, the intent phrasings that should reach it — and every matching ask then runs
 * that query unchanged, with the response attributed so downstream systems can audit the fact that
 * a certified answer (not a re-derived one) was served.
 *
 * <p>Persisted as JSON under {@code saiku-home/certified/*.json}. See {@link CertifiedQueryParser}
 * for the on-disk shape and {@link CertifiedQueryRegistry} for the catalogue.
 *
 * <p>Two orthogonal guarantees, both load-bearing:
 *
 * <ul>
 *   <li><b>Verbatim.</b> The query body is parsed once, at scan time, and executed as-is. Nothing
 *       downstream re-derives, optimises or "helpfully" extends it — the only mutation anywhere on
 *       the run path is a null/unsafe name fix-up, matching what {@code /ai/query/saved} does.
 *   <li><b>Attributed.</b> Every response produced by a certified run carries {@code
 *       source: "certified"} and {@code certifiedId}, so a consumer can tell a certified answer
 *       from a model-authored one without guessing.
 * </ul>
 */
public record CertifiedQuery(
        String id, String description, List<String> matchIntent, ThinQuery query, String sourcePath) {

    /**
     * Words that carry no intent signal. An intent phrase is reduced to its content words before
     * matching, so "revenue by month" and "monthly revenue" score against the same content set and
     * a question full of filler ("can you show me…") still matches.
     */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "a", "an", "the", "of", "by", "for", "in", "on", "to", "and", "or", "with", "per", "as", "at", "from", "is",
            "are", "was", "were", "be", "do", "does", "did", "i", "we", "me", "my", "our", "us", "it", "its", "this",
            "that", "these", "those", "please", "show", "give", "tell", "get", "pull", "what", "how", "many", "much",
            "can", "could", "would", "should", "there", "here", "up", "out");

    public CertifiedQuery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(matchIntent, "matchIntent");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sourcePath, "sourcePath");
        if (id.isBlank()) {
            throw new IllegalArgumentException("certified query id must be non-blank");
        }
        if (matchIntent.isEmpty()) {
            throw new IllegalArgumentException("certified query must declare at least one matchIntent");
        }
        matchIntent = List.copyOf(matchIntent);
    }

    /** Name of the cube this certified query runs against, or null when the body doesn't pin one. */
    public String cubeName() {
        return query.getCube() == null ? null : query.getCube().getName();
    }

    /**
     * True when this certified query's cube is compatible with the cube the caller is asking about.
     *
     * <p>A null {@code askedCube} (no cube in play) matches anything. Otherwise the cube names must
     * be equal — case-insensitively, because Mondrian cube names travel with wildly inconsistent
     * capitalisation across the UI, the API and hand-authored files.
     *
     * <p>Deliberately strict: running a certified query authored for one cube against a different
     * cube would silently answer a different question than the one the admin approved.
     */
    public boolean servesCube(String askedCube) {
        if (askedCube == null || askedCube.isBlank()) {
            return true;
        }
        String mine = cubeName();
        return mine != null && mine.equalsIgnoreCase(askedCube.trim());
    }

    /**
     * How strongly {@code question} matches this certified query, in {@code [0, 1]}.
     *
     * <p>Scoring is a whole-token, stop-word-stripped, plural-insensitive set comparison against
     * each declared {@code matchIntent} phrase; a phrase only contributes when EVERY one of its
     * content words is present in the question. Partial coverage scores 0 — "monthly revenue" must
     * not fire on a question that only says "monthly". The best-scoring phrase wins, so an author
     * can list several phrasings of the same intent and get the strongest evidence.
     *
     * <p>Deterministic and LLM-free by design. The whole point of the feature is that the routing
     * decision is reproducible and auditable; a probabilistic router would defeat it.
     */
    public double score(String question) {
        List<String> qTokens = contentTokens(question);
        if (qTokens.isEmpty()) {
            return 0d;
        }
        java.util.Set<String> qSet = new java.util.HashSet<>(qTokens);
        for (String phrase : matchIntent) {
            List<String> intentTokens = contentTokens(phrase);
            if (intentTokens.isEmpty()) {
                continue;
            }
            if (!qSet.containsAll(intentTokens)) {
                continue; // partial coverage never counts
            }
            return 1d;
        }
        return 0d;
    }

    /**
     * How SPECIFIC the best matching {@code matchIntent} phrase is: the number of content words in
     * the longest phrase whose every content word appears in {@code question}, or 0 when nothing
     * matches. The tie-break when two certified queries both match: "monthly store sales by
     * country" should reach the entry that says "monthly store sales", not the one that says
     * "monthly sales" — the narrower approval is the more deliberate one.
     */
    public int specificity(String question) {
        List<String> qTokens = contentTokens(question);
        if (qTokens.isEmpty()) {
            return 0;
        }
        java.util.Set<String> qSet = new java.util.HashSet<>(qTokens);
        int best = 0;
        for (String phrase : matchIntent) {
            List<String> intentTokens = contentTokens(phrase);
            if (intentTokens.isEmpty()) {
                continue;
            }
            if (qSet.containsAll(intentTokens)) {
                best = Math.max(best, intentTokens.size());
            }
        }
        return best;
    }

    /**
     * Split {@code text} into normalised content tokens: lower-cased, punctuation collapsed to
     * spaces, stop words dropped, and a naive plural stripped so "revenue"/"revenues" and
     * "region"/"regions" match. Package-visible so the parser and the tests share one
     * normalisation — a matcher that disagreed with the parser would be a silent routing bug.
     */
    static List<String> contentTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned =
                text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{Alnum}]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : cleaned.split("\\s+")) {
            String t = raw;
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.isEmpty() || STOPWORDS.contains(t)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Compact projection for the catalogue endpoint — id, description and the intent phrasings.
     * Deliberately omits the {@link ThinQuery} body: the catalogue is a public, unauthenticated-
     * friendly listing and the MDX behind a certified answer is not something an embed should
     * scrape to bypass the agent.
     */
    public record Summary(String id, String description, List<String> matchIntent) {}

    public Summary asSummary() {
        return new Summary(id, description, matchIntent);
    }
}
