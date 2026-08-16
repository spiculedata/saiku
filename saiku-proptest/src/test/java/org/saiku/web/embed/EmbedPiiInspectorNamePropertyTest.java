/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.embed;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;

/**
 * Property-based tests for {@link EmbedPiiInspector#lastBracketSegment}, the name normaliser used
 * when deciding whether an embed grant touches PII-flagged columns (saiku-cloud#940).
 *
 * <p>Lives in the production package because the method is package-private — same arrangement as
 * {@code PgTypePropertyTest}.
 *
 * <p>This is a matching function guarding a refusal: the inspector compares the short name of a
 * referenced hierarchy or measure against the names the schema marks {@code saiku.semantic.pii}. A
 * name that normalises WRONG simply fails to match, the grant is allowed, and PII flows to an
 * anonymous embed viewer. The failure direction is open, so the properties below are about
 * faithfulness, not tidiness.
 */
class EmbedPiiInspectorNamePropertyTest {

    /** A single bracketed segment round-trips to its content. */
    @HegelTest
    void aSingleBracketedSegmentRoundTrips(TestCase tc) {
        String inner = tc.draw(fromRegex("[A-Za-z0-9 _.-]{1,20}"), "inner");

        assertEquals(inner, EmbedPiiInspector.lastBracketSegment("[" + inner + "]"));
    }

    /** For a multi-segment unique name, the LAST segment is returned. */
    @HegelTest
    void theLastSegmentOfAUniqueNameIsReturned(TestCase tc) {
        List<String> segments = tc.draw(
                lists(fromRegex("[A-Za-z][A-Za-z0-9 _]{0,12}")).minSize(2).maxSize(5), "segments");

        StringBuilder uniqueName = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                uniqueName.append('.');
            }
            uniqueName.append('[').append(segments.get(i)).append(']');
        }
        tc.note(uniqueName.toString());

        assertEquals(
                segments.get(segments.size() - 1),
                EmbedPiiInspector.lastBracketSegment(uniqueName.toString()),
                "wrong segment for " + uniqueName);
    }

    /** A bare short name (KPI / filter-widget refs supply these) passes through unchanged. */
    @HegelTest
    void aNonBracketedNamePassesThroughUnchanged(TestCase tc) {
        String name = tc.draw(fromRegex("[A-Za-z0-9 _.-]{1,20}"), "name");

        assertEquals(name, EmbedPiiInspector.lastBracketSegment(name));
    }

    /** Null and empty pass through rather than throwing — refs arrive from client JSON. */
    @HegelTest
    void nullAndEmptyPassThrough(TestCase tc) {
        boolean useNull = tc.draw(dev.hegel.Generators.booleans(), "useNull");

        if (useNull) {
            assertNull(EmbedPiiInspector.lastBracketSegment(null));
        } else {
            assertEquals("", EmbedPiiInspector.lastBracketSegment(""));
        }
    }

    /** The result never carries the surrounding brackets, whatever the shape. */
    @HegelTest
    void theResultNeverRetainsBrackets(TestCase tc) {
        String inner = tc.draw(fromRegex("[A-Za-z0-9 _.-]{1,15}"), "inner");
        String prefix = tc.draw(sampledFrom(List.of("", "[Dim].", "[Dim].[Hier].")), "prefix");

        String result = EmbedPiiInspector.lastBracketSegment(prefix + "[" + inner + "]");

        assertEquals(inner, result, "brackets survived normalisation: " + result);
    }

    /**
     * saiku#1850. Mondrian escapes a literal {@code ]} inside a name by DOUBLING it, so a hierarchy
     * genuinely called {@code Foo]Bar} is written {@code [Foo]]Bar]}. That name must normalise back
     * to {@code Foo]Bar}.
     *
     * <p>It used to normalise to {@code Foo} — the parser stopped at the first {@code ]} — which
     * then failed to match the schema's PII-flagged name, so the embed grant was allowed and the
     * column served to an anonymous viewer. The check failed OPEN.
     */
    @HegelTest
    void anEscapedClosingBracketRoundTripsToALiteralBracket(TestCase tc) {
        String head = tc.draw(fromRegex("[A-Za-z]{1,8}"), "head");
        String tail = tc.draw(fromRegex("[A-Za-z]{1,8}"), "tail");

        // The MDX spelling of a name literally containing "]" between head and tail.
        String escaped = "[" + head + "]]" + tail + "]";

        assertEquals(head + "]" + tail, EmbedPiiInspector.lastBracketSegment(escaped), "for input " + escaped);
    }

    /** The same holds for an escaped bracket in the LAST segment of a qualified name. */
    @HegelTest
    void anEscapedBracketSurvivesInAQualifiedName(TestCase tc) {
        String dim = tc.draw(fromRegex("[A-Za-z]{1,8}"), "dim");
        String head = tc.draw(fromRegex("[A-Za-z]{1,8}"), "head");
        String tail = tc.draw(fromRegex("[A-Za-z]{1,8}"), "tail");

        String qualified = "[" + dim + "].[" + head + "]]" + tail + "]";

        assertEquals(head + "]" + tail, EmbedPiiInspector.lastBracketSegment(qualified), "for input " + qualified);
    }

    /** An escaped bracket in an EARLIER segment must not derail the walk to the last one. */
    @HegelTest
    void anEscapedBracketInAnEarlierSegmentIsSkippedCorrectly(TestCase tc) {
        String head = tc.draw(fromRegex("[A-Za-z]{1,6}"), "head");
        String tail = tc.draw(fromRegex("[A-Za-z]{1,6}"), "tail");
        String wanted = tc.draw(fromRegex("[A-Za-z]{1,8}"), "wanted");

        String qualified = "[" + head + "]]" + tail + "].[" + wanted + "]";

        assertEquals(wanted, EmbedPiiInspector.lastBracketSegment(qualified), "for input " + qualified);
    }

    /** Never throws, whatever bracket soup arrives from a client-supplied ref. */
    @HegelTest
    void normalisationIsTotal(TestCase tc) {
        String junk = tc.draw(fromRegex("[\\[\\]A-Za-z0-9 ._-]{0,30}"), "junk");

        EmbedPiiInspector.lastBracketSegment(junk); // must not throw
    }
}
