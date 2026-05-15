/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * Central, hardened replacement for the three duplicate
 * {@code replaceParameters(String, Map<String,String>)} implementations
 * scattered across the codebase (saiku#780). Removes the three
 * CWE-89-shaped issues each had:
 *
 * <ul>
 *   <li><b>Regex injection in the parameter NAME</b> — the old impl
 *       interpolated the name into a regex pattern. A name containing
 *       {@code .*} or other meta would match anywhere in the MDX. The
 *       new impl uses a fixed {@code \$\{([^}]+)\}} regex and looks
 *       the captured name up in the parameters map, so the name is
 *       never compiled as a pattern.</li>
 *   <li><b>Replacement-string injection in the VALUE</b> —
 *       {@link String#replaceAll(String, String)} treats {@code $} and
 *       {@code \\} in the replacement specially. A value containing
 *       {@code $0} would re-inject the matched text. We pass the value
 *       through {@link Matcher#quoteReplacement(String)} so it's used
 *       literally.</li>
 *   <li><b>MDX injection</b> — the value flows into the MDX verbatim.
 *       An attacker who controls a parameter source can inject
 *       arbitrary MDX (cross-axis bypass, drillthrough escape, etc.).
 *       We reject values containing any MDX-meta character ({@code [ ]
 *       \{ \} ' " ;} + control characters) by default; the caller has
 *       to use the typed olap4j Parameter API (planned follow-up) for
 *       cases that legitimately need those characters.</li>
 * </ul>
 *
 * <p>This is a hardening pass, not a complete fix. The full solution is
 * the typed-parameter API at #780 — see the issue body for the
 * roadmap. Until that lands, this class closes the immediate injection
 * surface without breaking the parameter-substitution flow.
 */
public final class MdxParameterSubstitutor {

    /**
     * MDX-meta characters that must not appear in an untyped parameter
     * value. Brackets and quotes break member-ref / string-literal
     * boundaries; braces break set boundaries; semicolons separate
     * statements; control chars are never legitimate. Strict by default.
     */
    private static final Pattern UNSAFE_VALUE_CHARS = Pattern.compile("[\\[\\]\\{\\}'\";\\u0000-\\u001f]");

    /** Matches {@code ${...}} placeholders with the body captured. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private MdxParameterSubstitutor() {}

    /**
     * Replace every {@code ${name}} placeholder in {@code query} with
     * the corresponding {@code parameters} entry. Empty {@code
     * parameters} or a query with no placeholders returns the input
     * unchanged.
     *
     * @throws SaikuServiceException if any value contains MDX-meta
     *         characters. The exception names the offending parameter
     *         so the caller can fix the source.
     */
    public static String substitute(String query, Map<String, String> parameters) {
        if (query == null || query.isEmpty()) return query;
        if (parameters == null || parameters.isEmpty()) return query;

        Matcher m = PLACEHOLDER.matcher(query);
        StringBuilder out = new StringBuilder(query.length() + 32);
        while (m.find()) {
            String name = m.group(1);
            // Placeholder lookup is case-insensitive — match the prior
            // impl's behaviour so we don't break callers that mix case.
            String value = caseInsensitiveGet(parameters, name);
            if (value == null) value = ""; // unset → drop the placeholder

            validateValue(name, value);

            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Reject values containing any MDX-meta character. The check is
     *  intentionally strict — the typed-parameter API (#780 follow-up)
     *  is the path for parameters that legitimately need those chars. */
    static void validateValue(String name, String value) {
        if (value == null) return;
        if (UNSAFE_VALUE_CHARS.matcher(value).find()) {
            throw new SaikuServiceException("Parameter '" + name
                    + "' contains MDX-meta characters that could change query meaning. "
                    + "Allowed: alphanumerics, spaces, and basic punctuation. "
                    + "For member references or string literals, use the typed olap4j "
                    + "Parameter API (saiku#780 follow-up) instead of string substitution.");
        }
    }

    private static String caseInsensitiveGet(Map<String, String> parameters, String name) {
        // Direct hit first — the common case is matched-case.
        if (parameters.containsKey(name)) return parameters.get(name);
        // Fallback case-insensitive scan for parity with the prior impl.
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
