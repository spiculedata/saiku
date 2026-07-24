/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.Generator;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.saiku.service.olap.ai.ask.AgentSkill;
import org.saiku.service.olap.ai.ask.AgentSkillParser;

/**
 * Parsing invariants for {@link AgentSkillParser#parse(String, String)}, the frontmatter reader that
 * turns a skill markdown file into an {@link AgentSkill}. Two properties: a round-trip (a canonical
 * document parses back to its inputs) and a totality guard (every input is either a valid skill or a
 * structured {@code ParseException} — never a leaked runtime exception).
 */
class AgentSkillParserPropertyTest {

    private static final Generator<String> NAME = fromRegex("[a-z][a-z0-9-]{0,20}");

    /** Single YAML-safe word: starts alphanumeric, no colon/hash/newline, no leading indicator. */
    private static final Generator<String> WORD = fromRegex("[A-Za-z0-9][A-Za-z0-9.,!?()_-]{0,15}");

    /**
     * Body starts with an alphanumeric so it is non-blank and the closing-fence {@code \s*} never
     * swallows leading whitespace — keeping the round-trip byte-exact. No {@code -} so the body can
     * never contain a stray {@code ---} fence.
     */
    private static final Generator<String> BODY = fromRegex("[A-Za-z0-9][A-Za-z0-9 .,\n]{0,39}");

    /** A canonical, well-formed document parses back to exactly the name, description, and body fed in. */
    @HegelTest
    void validDocumentRoundTrips(TestCase tc) {
        String name = tc.draw(NAME, "name");
        // Two words joined by a space: an internal space guarantees YAML reads it as a plain string
        // (never coerced to a bool/number like `on`/`Yes`/`123`), so asText echoes it verbatim.
        String description = tc.draw(WORD, "descA") + " " + tc.draw(WORD, "descB");
        String body = tc.draw(BODY, "body");

        String text = "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;

        AgentSkill skill;
        try {
            skill = AgentSkillParser.parse("test", text);
        } catch (AgentSkillParser.ParseException e) {
            fail("canonical document must parse, got " + e.code() + ": " + e.getMessage());
            return;
        }

        assertEquals(name, skill.name(), "name must round-trip");
        assertEquals(description, skill.description(), "description must round-trip");
        assertEquals(body, skill.body(), "body must round-trip verbatim");
    }

    /** For ANY input, parse returns a non-null skill or throws {@code ParseException} — nothing else. */
    @HegelTest
    void parseIsTotal(TestCase tc) {
        String raw = tc.draw(text(), "raw");
        // Half the cases wrap the raw text in a frontmatter fence to drive the YAML/body code paths
        // instead of bailing early on MISSING_FRONTMATTER.
        boolean fence = tc.draw(booleans(), "fence");
        String s = fence ? "---\n" + raw + "\n---\n" + raw : raw;

        try {
            AgentSkill skill = AgentSkillParser.parse("test", s);
            if (skill == null) {
                fail("parse returned null instead of throwing ParseException");
            }
        } catch (AgentSkillParser.ParseException e) {
            // Expected structured failure — fine.
        } catch (Throwable t) {
            fail("non-ParseException thrown for input " + describe(s) + ": " + t);
        }
    }

    private static String describe(String s) {
        String trimmed = s.length() > 60 ? s.substring(0, 60) + "..." : s;
        return "\"" + trimmed.replace("\n", "\\n") + "\"";
    }
}
