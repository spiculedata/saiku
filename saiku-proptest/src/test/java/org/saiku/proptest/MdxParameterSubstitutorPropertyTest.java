/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Map;
import org.saiku.service.util.MdxParameterSubstitutor;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * MDX parameter-substitution injection safety. Substituting a user value into an MDX query is a
 * classic injection surface; these properties assert the two defences hold for all inputs:
 * dangerous values (MDX-meta chars) are rejected outright, and safe values are inserted
 * <em>literally</em> — never re-interpreted as regex replacements ({@code $0}, {@code \}).
 */
class MdxParameterSubstitutorPropertyTest {

    /** Safe value alphabet: excludes every MDX-meta char ([ ] {@literal { } ' " ;}) and control chars,
     *  but deliberately keeps {@code $} to exercise regex-replacement quoting. */
    private static final Generator<String> SAFE_VALUE = fromRegex("[a-zA-Z0-9 $.,:/@!?()_+=-]{0,20}");

    /** A safe value is inserted verbatim — even when it contains {@code $}, which raw regex
     *  replacement would treat as a group reference. */
    @HegelTest
    void safeValueIsSubstitutedLiterally(TestCase tc) {
        String value = tc.draw(SAFE_VALUE, "value");

        String out = MdxParameterSubstitutor.substitute("SELECT ${p} ON ROWS", Map.of("p", value));

        assertEquals("SELECT " + value + " ON ROWS", out);
    }

    /** Any value carrying an MDX-meta character is rejected, so it can never reach the query. */
    @HegelTest
    void valuesWithMdxMetaCharsAreRejected(TestCase tc) {
        String pre = tc.draw(SAFE_VALUE, "pre");
        String post = tc.draw(SAFE_VALUE, "post");
        String meta = tc.draw(sampledFrom("[", "]", "{", "}", "'", "\"", ";"), "meta");
        String value = pre + meta + post;

        assertThrows(
                SaikuServiceException.class,
                () -> MdxParameterSubstitutor.substitute("SELECT ${p} ON ROWS", Map.of("p", value)));
    }

    /** A query with no {@code ${...}} placeholder is returned unchanged, whatever the params say. */
    @HegelTest
    void queriesWithoutPlaceholdersAreUnchanged(TestCase tc) {
        String query = tc.draw(fromRegex("[a-zA-Z0-9 .,()]{0,40}"), "query"); // no '$' or '{' => no token

        String out = MdxParameterSubstitutor.substitute(query, Map.of("p", "anything"));

        assertEquals(query, out);
    }
}
