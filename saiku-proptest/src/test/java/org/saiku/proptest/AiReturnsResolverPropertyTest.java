/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Locale;
import org.saiku.service.olap.ai.AiPiiException;
import org.saiku.service.olap.ai.AiReturnsResolver;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiValidationException;

/**
 * Property-based tests for {@link AiReturnsResolver}, the gate on
 * {@code DRILLTHROUGH ... RETURN} column projection.
 *
 * <p>Drillthrough returns RAW FACT ROWS, so this resolver is the last thing standing between an
 * agent-supplied {@code returns=} list and unaggregated personal data. saiku#902 added the PII gate:
 * a column the schema author flagged {@code saiku.semantic.pii=true} must be refused rather than
 * projected. The gate is otherwise careful — it even scrubs PII names out of the suggestion list so
 * the error can't be mined for column existence.
 */
class AiReturnsResolverPropertyTest {

    /** A schema with one PII measure, one clean measure, one PII level and one clean level. */
    private static AiSchema schema() {
        AiSchema s = new AiSchema("conn/cat/sch/Sales", "Sales", "[Sales]");

        AiSchema.Measure clean = new AiSchema.Measure("Unit Sales", "[Measures].[Unit Sales]");
        AiSchema.Measure secret = new AiSchema.Measure("Salary", "[Measures].[Salary]");
        secret.pii = true;
        s.measures.put(AiSchema.key(clean.name), clean);
        s.measures.put(AiSchema.key(secret.name), secret);

        AiSchema.Dimension dim = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy hier = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level city = new AiSchema.Level("City", "[Customer].[Customer].[City]");
        AiSchema.Level email = new AiSchema.Level("Email", "[Customer].[Customer].[Email]");
        email.pii = true;
        hier.levels.put(AiSchema.key(city.name), city);
        hier.levels.put(AiSchema.key(email.name), email);
        dim.hierarchies.put(AiSchema.key(hier.name), hier);
        s.dimensions.put(AiSchema.key(dim.name), dim);

        return s;
    }

    private static final List<String> PII_NAMES = List.of("Salary", "Email");
    private static final List<String> CLEAN_NAMES = List.of("Unit Sales", "City");

    /** Bare PII names are refused, in any casing — the documented behaviour of saiku#902. */
    @HegelTest
    void aBarePiiColumnIsRefused(TestCase tc) {
        String name = tc.draw(sampledFrom(PII_NAMES), "name");
        boolean upper = tc.draw(dev.hegel.Generators.booleans(), "upper");

        String token = upper ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT);

        assertThrows(AiPiiException.class, () -> AiReturnsResolver.resolve(token, schema()), "PII leaked via " + token);
    }

    /** Clean columns resolve to their unique names, in any casing. */
    @HegelTest
    void aBareCleanColumnResolvesToItsUniqueName(TestCase tc) {
        String name = tc.draw(sampledFrom(CLEAN_NAMES), "name");
        boolean upper = tc.draw(dev.hegel.Generators.booleans(), "upper");

        String token = upper ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT);
        String resolved = AiReturnsResolver.resolve(token, schema());

        assertTrue(resolved.startsWith("["), "did not resolve to a unique name: " + resolved);
        assertFalse(resolved.contains("Salary"), "clean token resolved to a PII column: " + resolved);
    }

    /** The refusal never names other PII columns — the suggestion list must not leak existence. */
    @HegelTest
    void theRefusalNeverLeaksPiiColumnNames(TestCase tc) {
        String name = tc.draw(sampledFrom(PII_NAMES), "name");

        AiPiiException e = assertThrows(AiPiiException.class, () -> AiReturnsResolver.resolve(name, schema()));

        for (String pii : PII_NAMES) {
            if (pii.equals(name)) {
                continue; // the offending token is echoed back deliberately
            }
            assertFalse(
                    String.valueOf(e.getAvailable()).contains(pii),
                    "the suggestion list leaked PII column '" + pii + "': " + e.getAvailable());
        }
    }

    /** An unknown bare token is a validation error, not a silent pass-through. */
    @HegelTest
    void anUnknownBareTokenIsRejected(TestCase tc) {
        String junk = tc.draw(fromRegex("[A-Za-z][A-Za-z ]{0,12}"), "junk");
        String key = AiSchema.key(junk);
        tc.assume(!schema().measures.containsKey(key));
        tc.assume(CLEAN_NAMES.stream().noneMatch(n -> AiSchema.key(n).equals(key)));
        tc.assume(PII_NAMES.stream().noneMatch(n -> AiSchema.key(n).equals(key)));

        assertThrows(AiValidationException.class, () -> AiReturnsResolver.resolve(junk, schema()));
    }

    /** A PII column anywhere in a comma-separated list poisons the whole list. */
    @HegelTest
    void aPiiColumnAnywhereInTheListIsRefused(TestCase tc) {
        String pii = tc.draw(sampledFrom(PII_NAMES), "pii");
        String clean = tc.draw(sampledFrom(CLEAN_NAMES), "clean");
        int position = tc.draw(dev.hegel.Generators.integers().min(0).max(2), "position");

        String list =
                switch (position) {
                    case 0 -> pii + "," + clean;
                    case 1 -> clean + "," + pii;
                    default -> clean + "," + pii + "," + clean;
                };

        assertThrows(AiPiiException.class, () -> AiReturnsResolver.resolve(list, schema()), "PII leaked via " + list);
    }

    /** Blank / null input passes through so the caller can skip the RETURN clause entirely. */
    @HegelTest
    void blankInputPassesThrough(TestCase tc) {
        String blank = tc.draw(sampledFrom(List.of("", " ", "\t", "  ")), "blank");

        assertEquals(blank, AiReturnsResolver.resolve(blank, schema()));
    }

    /**
     * THE PROPERTY THAT MATTERS (saiku#1848): the gate is on the COLUMN, not on the spelling.
     *
     * <p>{@code resolve()} used to pass any token starting with {@code [} straight through
     * unvalidated, because the PII check lived only in the bare-token branch. So a column refused as
     * {@code Salary} was projected without complaint as {@code [Measures].[Salary]} — and the
     * refusal message for the bare form recommends exactly that spelling:
     *
     * <blockquote>
     * "Use either a bare measure/level caption or a fully-qualified MDX identifier like
     * [Time].[Time].[Year]."
     * </blockquote>
     *
     * <p>So an agent following the self-correction hint walked into the bypass by design rather than
     * by malice, and the unique names were never secret — {@code GET /ai/schema/...} hands them out.
     */
    @HegelTest
    void aPiiColumnIsRefusedInEitherSpelling(TestCase tc) {
        String uniqueName =
                tc.draw(sampledFrom(List.of("[Measures].[Salary]", "[Customer].[Customer].[Email]")), "uniqueName");
        boolean upper = tc.draw(dev.hegel.Generators.booleans(), "upper");

        // MDX identifiers are case-insensitive, so the casing must not matter either.
        String token = upper ? uniqueName.toUpperCase(Locale.ROOT) : uniqueName;

        assertThrows(
                AiPiiException.class,
                () -> AiReturnsResolver.resolve(token, schema()),
                "PII leaked via the qualified spelling: " + token);
    }

    /**
     * A bracketed identifier the schema doesn't know is still passed through untouched — the fix
     * adds a refusal, never a new rejection. Mondrian remains the judge of unknown MDX.
     */
    @HegelTest
    void anUnknownBracketedIdentifierStillPassesThrough(TestCase tc) {
        String dim = tc.draw(fromRegex("[A-Za-z]{1,8}"), "dim");
        String member = tc.draw(fromRegex("[A-Za-z]{1,8}"), "member");
        String token = "[" + dim + "].[" + member + "]";
        tc.assume(!token.equalsIgnoreCase("[Measures].[Salary]"));

        assertEquals(token, AiReturnsResolver.resolve(token, schema()), "an unknown identifier was altered");
    }

    /** Clean columns still resolve through the qualified spelling — no false refusals. */
    @HegelTest
    void aCleanColumnStillResolvesInEitherSpelling(TestCase tc) {
        String uniqueName =
                tc.draw(sampledFrom(List.of("[Measures].[Unit Sales]", "[Customer].[Customer].[City]")), "uniqueName");

        assertEquals(uniqueName, AiReturnsResolver.resolve(uniqueName, schema()), "refused a clean column");
    }

    /** Bracketed and bare forms of the SAME clean column agree — the two spellings are equivalent. */
    @HegelTest
    void bracketedAndBareFormsAgreeForCleanColumns(TestCase tc) {
        String name = tc.draw(sampledFrom(CLEAN_NAMES), "name");

        String viaBare = AiReturnsResolver.resolve(name, schema());
        String viaBracketed = AiReturnsResolver.resolve(viaBare, schema());

        assertEquals(viaBare, viaBracketed, "resolving an already-resolved name changed it");
    }
}
