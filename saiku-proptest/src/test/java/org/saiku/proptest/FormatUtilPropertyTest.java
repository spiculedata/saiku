/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.HashMap;
import java.util.Map;
import org.saiku.service.util.export.excel.FormatUtil;

/**
 * {@link FormatUtil#getFormatString(String)} resolves Excel macro-token names (e.g. {@code Standard})
 * to their concrete format strings, passing anything unknown straight through. Properties: unknown
 * strings are returned unchanged, and each known token maps to its declared translation — including
 * the {@code Currency} edge, whose declared translation is {@code null}.
 */
class FormatUtilPropertyTest {

    /** Declared token → translation, mirrored from FormatUtil.MacroToken (Currency is intentionally null). */
    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        KNOWN.put("Currency", null);
        KNOWN.put("Fixed", "0");
        KNOWN.put("Standard", "#,##0");
        KNOWN.put("Percent", "0.00%");
        KNOWN.put("Scientific", "0.00e+00");
        KNOWN.put("Long Date", "dddd, mmmm dd, yyyy");
        KNOWN.put("Medium Date", "dd-mmm-yy");
        KNOWN.put("Short Date", "m/d/yy");
        KNOWN.put("Long Time", "h:mm:ss AM/PM");
        KNOWN.put("Medium Time", "h:mm AM/PM");
        KNOWN.put("Short Time", "hh:mm");
        KNOWN.put("Yes/No", "\\Y\\e\\s;\\Y\\e\\s;\\N\\o;\\N\\o");
        KNOWN.put("True/False", "\\T\\r\\u\\e;\\T\\r\\u\\e;\\F\\a\\l\\s\\e;\\F\\a\\l\\s\\e");
        KNOWN.put("On/Off", "\\O\\n;\\O\\n;\\O\\f\\f;\\O\\f\\f");
    }

    /** An unknown string passes through unchanged. Lowercase input can never equal a known token
     *  (all of which are capitalised), so no collision guard is needed. */
    @HegelTest
    void unknownTokenIsReturnedUnchanged(TestCase tc) {
        String s = tc.draw(fromRegex("[a-z]{1,15}"), "s");

        assertEquals(s, FormatUtil.getFormatString(s), "unknown token must pass through unchanged");
    }

    /** Every declared token resolves to exactly its declared translation (null for Currency). */
    @HegelTest
    void knownTokenMapsToDeclaredTranslation(TestCase tc) {
        String token = tc.draw(sampledFrom(KNOWN.keySet().stream().toList()), "token");

        assertEquals(KNOWN.get(token), FormatUtil.getFormatString(token), "token '" + token + "' translation");
    }
}
