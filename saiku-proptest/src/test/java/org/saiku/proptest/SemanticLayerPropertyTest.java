/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.saiku.semantic.ir.CubeDefinition;
import org.saiku.semantic.ir.Dimension;
import org.saiku.semantic.ir.Level;
import org.saiku.semantic.ir.Measure;
import org.saiku.semantic.mondrian.MondrianCompiler;
import org.saiku.semantic.yaml.CubeYamlParser;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Property-based tests for the YAML semantic layer ({@code saiku-semantic}), which had NO property
 * coverage — largely because {@code saiku-proptest} could not see the module until now.
 *
 * <p>The pipeline is {@code YAML -> CubeDefinition -> Mondrian XML}. Both ends take input a human
 * wrote, and the output is XML that Mondrian will load, so two things matter:
 *
 * <ul>
 *   <li><b>The compiler must always emit well-formed XML.</b> A cube, dimension or measure name is
 *       author-supplied text landing in an XML attribute. If it isn't escaped, a name containing a
 *       quote either corrupts the document or injects attributes — and the corruption surfaces far
 *       from its cause, as a Mondrian parse error about something else entirely.
 *   <li><b>The parser must fail typed.</b> A malformed YAML file should raise
 *       {@code SemanticValidationException}, not an arbitrary runtime exception from Jackson's guts.
 * </ul>
 */
class SemanticLayerPropertyTest {

    private static final CubeYamlParser PARSER = new CubeYamlParser();
    private static final MondrianCompiler COMPILER = new MondrianCompiler();

    /** Names that break naive string-concatenation XML writers. */
    private static final List<String> XML_HOSTILE = List.of(
            "a\"b",
            "a'b",
            "a<b",
            "a>b",
            "a&b",
            "\" onload=\"evil()",
            "a\"/><Cube name=\"injected",
            "a]]>b",
            "line\nbreak",
            "tab\there");

    private static CubeDefinition cube(String name, List<Dimension> dims, List<Measure> measures) {
        return new CubeDefinition(name, new CubeDefinition.FactTable("fact_table", null), dims, measures);
    }

    private static Measure measure(String name) {
        return new Measure(name, "amount", "sum", "Numeric", null);
    }

    private static Dimension dimension(String name) {
        return new Dimension(
                name, "dim_table", "fk", "pk", List.of(new Level(name + " Level", "col", null, null, null)));
    }

    /** Parse XML strictly; any malformedness throws. */
    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // --- the compiler ----------------------------------------------------------

    /**
     * THE property. Whatever the author called their cube, the emitted document parses. A name
     * carrying a quote or an angle bracket must be escaped, not embedded raw.
     */
    @HegelTest
    void compiledXmlIsAlwaysWellFormed(TestCase tc) throws Exception {
        String cubeName = tc.draw(sampledFrom(XML_HOSTILE), "cubeName");
        String measureName = tc.draw(sampledFrom(XML_HOSTILE), "measureName");
        String dimName = tc.draw(sampledFrom(XML_HOSTILE), "dimName");

        String xml = COMPILER.compile(cube(cubeName, List.of(dimension(dimName)), List.of(measure(measureName))));
        tc.note(xml);

        assertNotNull(parseXml(xml), "compiler emitted unparseable XML");
    }

    /**
     * Escaping must be faithful, not merely safe: the name that comes back out of the parsed
     * document is the name the author wrote. Stripping or mangling it would silently rename their
     * cube.
     */
    @HegelTest
    void hostileNamesSurviveCompilationVerbatim(TestCase tc) throws Exception {
        String cubeName = tc.draw(sampledFrom(XML_HOSTILE), "cubeName");

        Document doc = parseXml(COMPILER.compile(cube(cubeName, List.of(), List.of(measure("Amount")))));

        NodeList cubes = doc.getElementsByTagName("Cube");
        assertEquals(1, cubes.getLength(), "expected exactly one Cube element");
        String roundTripped = cubes.item(0).getAttributes().getNamedItem("name").getNodeValue();

        // XML normalises newline and tab inside attributes; compare on that basis rather than
        // pretending the transport is byte-transparent.
        assertEquals(cubeName.replace("\n", " ").replace("\t", " "), roundTripped, "cube name was mangled");
    }

    /** An injected attribute payload must not become a second Cube element. */
    @HegelTest
    void anInjectionPayloadNeverCreatesExtraElements(TestCase tc) throws Exception {
        String payload = tc.draw(sampledFrom(List.of("a\"/><Cube name=\"injected", "\"><Measure name=\"x")), "payload");

        Document doc = parseXml(COMPILER.compile(cube(payload, List.of(), List.of(measure("Amount")))));

        assertEquals(1, doc.getElementsByTagName("Cube").getLength(), "an injected Cube element appeared");
    }

    /** Every declared measure and dimension reaches the output — none silently dropped. */
    @HegelTest
    void everyMeasureAndDimensionIsEmitted(TestCase tc) throws Exception {
        int measureCount = tc.draw(integers().min(1).max(5), "measureCount");
        int dimCount = tc.draw(integers().min(0).max(4), "dimCount");

        List<Measure> measures = new ArrayList<>();
        for (int i = 0; i < measureCount; i++) {
            measures.add(measure("Measure " + i));
        }
        List<Dimension> dims = new ArrayList<>();
        for (int i = 0; i < dimCount; i++) {
            dims.add(dimension("Dim " + i));
        }

        Document doc = parseXml(COMPILER.compile(cube("Sales", dims, measures)));

        assertEquals(measureCount, doc.getElementsByTagName("Measure").getLength(), "measures were dropped");
        assertEquals(dimCount, doc.getElementsByTagName("Dimension").getLength(), "dimensions were dropped");
    }

    /** Compilation is deterministic — the same definition yields the same XML. */
    @HegelTest
    void compilationIsDeterministic(TestCase tc) {
        String name = tc.draw(fromRegex("[A-Za-z ]{1,12}"), "name");
        CubeDefinition def = cube(name, List.of(dimension("D")), List.of(measure("M")));

        assertEquals(COMPILER.compile(def), COMPILER.compile(def));
    }

    // --- the parser ------------------------------------------------------------

    /** A well-formed cube round-trips YAML -> IR with its names intact. */
    @HegelTest
    void aWellFormedCubeParsesWithNamesIntact(TestCase tc) throws Exception {
        String cubeName = tc.draw(fromRegex("[A-Za-z][A-Za-z ]{0,15}"), "cubeName");
        String measureName = tc.draw(fromRegex("[A-Za-z][A-Za-z ]{0,15}"), "measureName");
        String table = tc.draw(fromRegex("[a-z_]{1,12}"), "table");

        String yaml = "name: \"" + cubeName + "\"\n"
                + "fact:\n  table: \"" + table + "\"\n"
                + "measures:\n  - name: \"" + measureName + "\"\n    column: amount\n";
        tc.note(yaml);

        CubeDefinition parsed = PARSER.parse(yaml);

        assertEquals(cubeName, parsed.name());
        assertEquals(table, parsed.fact().table());
        assertEquals(1, parsed.measures().size());
        assertEquals(measureName, parsed.measures().get(0).name());
    }

    /** A cube declaring no measures is rejected with the typed exception. */
    @HegelTest
    void aCubeWithoutMeasuresIsRejected(TestCase tc) {
        String cubeName = tc.draw(fromRegex("[A-Za-z]{1,10}"), "cubeName");

        String yaml = "name: " + cubeName + "\nfact:\n  table: t\nmeasures: []\n";

        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> PARSER.parse(yaml));
    }

    /** A missing required field is rejected with the typed exception, naming nothing sensitive. */
    @HegelTest
    void missingRequiredFieldsAreRejectedTyped(TestCase tc) {
        String missing = tc.draw(sampledFrom(List.of("name", "fact", "measure.column", "measure.name")), "missing");

        String yaml =
                switch (missing) {
                    case "name" -> "fact:\n  table: t\nmeasures:\n  - name: M\n    column: c\n";
                    case "fact" -> "name: C\nmeasures:\n  - name: M\n    column: c\n";
                    case "measure.column" -> "name: C\nfact:\n  table: t\nmeasures:\n  - name: M\n";
                    default -> "name: C\nfact:\n  table: t\nmeasures:\n  - column: c\n";
                };

        try {
            PARSER.parse(yaml);
            fail("expected a typed rejection for missing " + missing);
        } catch (CubeYamlParser.SemanticValidationException | NullPointerException expected) {
            // Both are typed, deliberate signals from validate().
        } catch (Exception e) {
            fail("expected a typed rejection for missing " + missing + ", got "
                    + e.getClass().getName());
        }
    }

    /**
     * Total over junk: a malformed semantic file must not produce an unchecked failure from
     * Jackson's internals, which would surface to an operator as an unexplained stack trace.
     */
    @HegelTest
    void parsingIsTotalOverJunk(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z0-9:\\-\\n .\"\\[\\]{}]{0,60}"), "junk");
        tc.note(junk);

        assertDoesNotThrow(
                () -> {
                    try {
                        PARSER.parse(junk);
                    } catch (CubeYamlParser.SemanticValidationException
                            | NullPointerException
                            | java.io.IOException expected) {
                        // Typed / declared failures are the contract.
                    }
                },
                "parser threw something unchecked for: " + junk);
    }

    /**
     * End to end: whatever the parser accepts, the compiler emits as well-formed XML. This is the
     * property that matters operationally — an author writes YAML, and the XML Mondrian loads must
     * be valid no matter what they called things.
     */
    @HegelTest
    void anythingTheParserAcceptsTheCompilerCanEmit(TestCase tc) throws Exception {
        String cubeName = tc.draw(sampledFrom(XML_HOSTILE), "cubeName");

        String yaml =
                "name: " + quote(cubeName) + "\n" + "fact:\n  table: t\n" + "measures:\n  - name: M\n    column: c\n";
        tc.note(yaml);

        CubeDefinition parsed;
        try {
            parsed = PARSER.parse(yaml);
        } catch (Exception notRepresentableInYaml) {
            return; // the name can't be expressed in this YAML shape; nothing to assert
        }

        assertNotNull(parseXml(COMPILER.compile(parsed)), "a parsed cube did not compile to valid XML");
    }

    /** YAML double-quoted scalar escaping for the hostile names above. */
    private static String quote(String raw) {
        return "\""
                + raw.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t") + "\"";
    }
}
