/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Random;
import org.junit.Test;

/**
 * Unit + property tests for {@link AgentSkillParser}. Golden-path parses succeed; every documented
 * error code is exercised; the fuzz test exists to catch any parse path that panics on random
 * bytes (issue #1426 acceptance criterion: "malformed frontmatter parses cleanly and gets rejected
 * with a structured error" — the parser must never NPE or infinite-loop on garbage input).
 */
public class AgentSkillParserTest {

    @Test
    public void parsesGoldenPathSkill() throws Exception {
        String text = "---\nname: weekly-rollup\ndescription: Weekly revenue rollup\n"
                + "cube: unknown_foodmart/FoodMart/FoodMart/Sales\n---\n\n"
                + "## Steps\n\n1. Query total revenue by product family.\n";
        AgentSkill skill = AgentSkillParser.parse("weekly-rollup.md", text);
        assertEquals("weekly-rollup", skill.name());
        assertEquals("Weekly revenue rollup", skill.description());
        assertEquals("unknown_foodmart/FoodMart/FoodMart/Sales", skill.cube());
        assertTrue(skill.body().contains("Query total revenue"));
    }

    @Test
    public void parsesWithBlockScalarDescription() throws Exception {
        String text =
                "---\nname: weekly-rollup\ndescription: |\n  Weekly revenue rollup.\n  Multi-line block scalar.\n---\n\nbody here\n";
        AgentSkill skill = AgentSkillParser.parse("s.md", text);
        assertTrue(skill.description().contains("Multi-line block scalar"));
    }

    @Test
    public void cubeIsOptional() throws Exception {
        String text = "---\nname: general\ndescription: no specific cube\n---\n\nbody\n";
        AgentSkill skill = AgentSkillParser.parse("s.md", text);
        assertNull(skill.cube());
    }

    @Test
    public void rejectsEmptyFile() {
        assertParseCode("", "EMPTY_SKILL");
        assertParseCode("   \n  \t\n", "EMPTY_SKILL");
    }

    @Test
    public void rejectsMissingFrontmatter() {
        assertParseCode("just some markdown\nno frontmatter\n", "MISSING_FRONTMATTER");
    }

    @Test
    public void rejectsEmptyBody() {
        assertParseCode("---\nname: x\ndescription: y\n---\n\n\n  \n", "EMPTY_BODY");
    }

    @Test
    public void rejectsMalformedYaml() {
        // Bare : with no value AND indentation that isn't valid YAML.
        assertParseCode("---\n  key: :\n    ::: bad\n---\n\nbody\n", "MALFORMED_YAML");
    }

    @Test
    public void rejectsMissingName() {
        assertParseCode("---\ndescription: only a description\n---\n\nbody\n", "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingDescription() {
        assertParseCode("---\nname: x\n---\n\nbody\n", "MISSING_FIELD");
    }

    @Test
    public void rejectsBlankName() {
        assertParseCode("---\nname: '   '\ndescription: y\n---\n\nbody\n", "BLANK_FIELD");
    }

    @Test
    public void rejectsInvalidNameCharacters() {
        assertParseCode("---\nname: has_underscores\ndescription: y\n---\n\nbody\n", "INVALID_NAME");
        assertParseCode("---\nname: has spaces\ndescription: y\n---\n\nbody\n", "INVALID_NAME");
        assertParseCode("---\nname: UPPER\ndescription: y\n---\n\nbody\n", "INVALID_NAME");
        assertParseCode("---\nname: 123-starts-with-digit\ndescription: y\n---\n\nbody\n", "INVALID_NAME");
        // 65-char name exceeds the [a-z][a-z0-9-]{0,63} bound.
        assertParseCode(
                "---\nname: a234567890123456789012345678901234567890123456789012345678901234567\n"
                        + "description: y\n---\n\nbody\n",
                "INVALID_NAME");
    }

    @Test
    public void rejectsTypeMismatch() {
        assertParseCode("---\nname: 42\ndescription: y\n---\n\nbody\n", "TYPE_MISMATCH");
    }

    @Test
    public void coercesYamlBooleanDescriptions() throws Exception {
        // YAML gotcha (saiku#1440 debug): `description: Yes` (unquoted) parses as boolean true.
        // Authors writing natural English shouldn't get burned — the parser coerces.
        AgentSkill yes = AgentSkillParser.parse("s.md", "---\nname: skill-yes\ndescription: Yes\n---\n\nbody\n");
        assertEquals("true", yes.description());
        // Numbers coerce too — a raw number is silly for a description but at least parses.
        AgentSkill num = AgentSkillParser.parse("s.md", "---\nname: skill-num\ndescription: 42\n---\n\nbody\n");
        assertEquals("42", num.description());
        // Plain sentence starting with "Yes" — the quoted form works trivially, this is the
        // reason we coerce: the unquoted form is what real authors write.
        AgentSkill sentence = AgentSkillParser.parse(
                "s.md", "---\nname: skill-sentence\ndescription: \"Yes, this is a sentence.\"\n---\n\nbody\n");
        assertEquals("Yes, this is a sentence.", sentence.description());
    }

    @Test
    public void rejectsUnknownField() {
        assertParseCode("---\nname: x\ndescription: y\ndescripton: typo\n---\n\nbody\n", "UNKNOWN_FIELD");
    }

    /**
     * Fuzz: 1000 random inputs of varied length + charset must all either parse successfully or fail
     * with a structured ParseException. Nothing may throw a RuntimeException / NPE / stack overflow
     * — those would surface as HTTP 500s from the /ai/skills endpoint and mask the real problem.
     *
     * <p>Deterministic seed so failures reproduce. If the parser regresses on a specific corpus
     * shape, add a hand-authored test case reproducing the failure before adjusting the fuzz seed.
     */
    @Test
    public void fuzzParseNeverPanics() {
        Random rng = new Random(0xF00DBEEFL);
        int iterations = 1000;
        int parsed = 0;
        int rejected = 0;
        for (int i = 0; i < iterations; i++) {
            String text = randomInput(rng, i);
            try {
                AgentSkill skill = AgentSkillParser.parse("fuzz-" + i + ".md", text);
                assertNotNull(skill);
                assertNotNull(skill.name());
                assertNotNull(skill.description());
                parsed++;
            } catch (AgentSkillParser.ParseException e) {
                assertNotNull("ParseException must carry a code", e.code());
                assertNotNull("ParseException must carry a source", e.source());
                assertNotNull("ParseException must carry a message", e.getMessage());
                rejected++;
            } catch (RuntimeException e) {
                fail("iteration " + i + " leaked " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nInput was:\n" + text);
            }
        }
        // The seed's corpus produces a mix; both flags must be exercised so the assertions bite.
        assertTrue("fuzz parsed " + parsed + " / rejected " + rejected, rejected > 0);
    }

    private static String randomInput(Random rng, int i) {
        int shape = i % 8;
        switch (shape) {
            case 0:
                return ""; // triggers EMPTY_SKILL
            case 1:
                return randomBytes(rng, rng.nextInt(200)); // no fence at all
            case 2:
                // Truncated frontmatter fence at unpredictable spots.
                String body = "---\n" + randomBytes(rng, rng.nextInt(120));
                return body.substring(0, Math.min(body.length(), 3 + rng.nextInt(120)));
            case 3:
                // Valid fence + garbage YAML.
                return "---\n" + randomBytes(rng, rng.nextInt(200)) + "\n---\n\nbody\n";
            case 4:
                // Valid frontmatter shape but wrong types / missing fields.
                return "---\nname: " + rng.nextInt() + "\ndescription: [not, a, string]\n---\n\nbody\n";
            case 5:
                // Golden-path skeleton with random name — should mostly INVALID_NAME.
                return "---\nname: " + randomBytes(rng, 4 + rng.nextInt(20)) + "\ndescription: d\n---\n\nbody\n";
            case 6:
                // Valid.
                return "---\nname: skill-"
                        + i
                        + "\ndescription: fuzz "
                        + rng.nextInt(1000)
                        + "\n---\n\nbody-" + i + "\n";
            default:
                // Extra frontmatter fences buried in the body.
                return "---\nname: valid-"
                        + i
                        + "\ndescription: d\n---\n\nbody\n---\nembedded: fence\n---\nmore body\n";
        }
    }

    private static String randomBytes(Random rng, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            // Restrict to printable + a few whitespace chars; malformed but not binary-junk.
            int c = 32 + rng.nextInt(95);
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static void assertParseCode(String text, String expectedCode) {
        try {
            AgentSkillParser.parse("src.md", text);
            fail("expected ParseException(" + expectedCode + ")");
        } catch (AgentSkillParser.ParseException e) {
            assertEquals("code (message was: " + e.getMessage() + ")", expectedCode, e.code());
        }
    }
}
