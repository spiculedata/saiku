package org.saiku.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.saiku.semantic.ir.CubeDefinition;
import org.saiku.semantic.mondrian.MondrianCompiler;
import org.saiku.semantic.yaml.CubeYamlParser;

class SemanticLayerTest {

    private CubeDefinition parseSalesFixture() throws IOException {
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/sales.yml"), StandardCharsets.UTF_8)) {
            return new CubeYamlParser().parse(r);
        }
    }

    @Test
    void parsesSalesFixture() throws IOException {
        CubeDefinition cube = parseSalesFixture();
        assertEquals("Sales", cube.name());
        assertEquals("sales_fact", cube.fact().table());
        assertEquals(1, cube.dimensions().size());
        assertEquals("Time", cube.dimensions().get(0).name());
        assertEquals(2, cube.dimensions().get(0).levels().size());
        assertEquals(2, cube.measures().size());
    }

    @Test
    void compilesToMondrianXml() throws IOException {
        String xml = new MondrianCompiler().compile(parseSalesFixture());
        assertNotNull(xml);
        assertTrue(xml.contains("<Schema name=\"Sales\""), xml);
        assertTrue(xml.contains("<Cube name=\"Sales\""), xml);
        assertTrue(xml.contains("<Table name=\"sales_fact\""), xml);
        assertTrue(xml.contains("<Dimension name=\"Time\" foreignKey=\"time_id\""), xml);
        assertTrue(xml.contains("<Level name=\"Year\" column=\"the_year\" type=\"Numeric\""), xml);
        assertTrue(xml.contains("<Measure name=\"Unit Sales\" column=\"unit_sales\" aggregator=\"sum\""), xml);
    }

    @Test
    void isDeterministic() throws IOException {
        MondrianCompiler compiler = new MondrianCompiler();
        String a = compiler.compile(parseSalesFixture());
        String b = compiler.compile(parseSalesFixture());
        assertEquals(a, b);
    }

    @Test
    void rejectsCubeWithoutMeasures() {
        String yaml =
                """
                name: Broken
                fact:
                  table: t
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsMissingMeasureColumn() {
        String yaml =
                """
                name: Broken
                fact:
                  table: t
                measures:
                  - name: Sales
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsBlankCubeName() {
        String yaml =
                """
                name: ""
                fact:
                  table: t
                measures:
                  - name: a
                    column: c
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsMissingFactTable() {
        String yaml =
                """
                name: Broken
                fact:
                  table: ""
                measures:
                  - name: a
                    column: c
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsDimensionWithoutLevels() {
        String yaml =
                """
                name: Sales
                fact:
                  table: t
                measures:
                  - name: a
                    column: c
                dimensions:
                  - name: Empty
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsLevelMissingColumn() {
        String yaml =
                """
                name: Sales
                fact:
                  table: t
                measures:
                  - name: a
                    column: c
                dimensions:
                  - name: D
                    levels:
                      - name: L
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void rejectsLevelMissingName() {
        String yaml =
                """
                name: Sales
                fact:
                  table: t
                measures:
                  - name: a
                    column: c
                dimensions:
                  - name: D
                    levels:
                      - column: lc
                """;
        assertThrows(CubeYamlParser.SemanticValidationException.class, () -> new CubeYamlParser().parse(yaml));
    }

    @Test
    void compilerEmitsMultipleDimensionsInOrder() throws IOException {
        String yaml =
                """
                name: Multi
                fact:
                  table: f
                measures:
                  - name: m1
                    column: c1
                dimensions:
                  - name: Time
                    levels: [{ name: Year, column: y }]
                  - name: Geo
                    levels: [{ name: Country, column: g }]
                """;
        CubeDefinition cube = new CubeYamlParser().parse(yaml);
        String xml = new MondrianCompiler().compile(cube);
        int timeAt = xml.indexOf("<Dimension name=\"Time\"");
        int geoAt = xml.indexOf("<Dimension name=\"Geo\"");
        assertTrue(timeAt > 0, "Time dim must be emitted");
        assertTrue(geoAt > timeAt, "Geo dim must follow Time in declaration order, got " + xml);
    }

    @Test
    void compilerEmitsFactSchemaAttributeWhenPresent() throws IOException {
        String yaml =
                """
                name: WithSchema
                fact:
                  schema: analytics
                  table: f
                measures:
                  - name: m
                    column: c
                """;
        String xml = new MondrianCompiler().compile(new CubeYamlParser().parse(yaml));
        assertTrue(xml.contains("schema=\"analytics\""), xml);
    }

    @Test
    void compilerOmitsFactSchemaAttributeWhenBlank() throws IOException {
        String yaml =
                """
                name: NoSchema
                fact:
                  schema: ""
                  table: f
                measures:
                  - name: m
                    column: c
                """;
        String xml = new MondrianCompiler().compile(new CubeYamlParser().parse(yaml));
        assertTrue(!xml.contains("schema=\"\""), "blank schema must not be emitted, got: " + xml);
    }

    @Test
    void compilerEmitsLevelTypeAndOptionalColumnsWhenPresent() throws IOException {
        String yaml =
                """
                name: Sales
                fact:
                  table: f
                measures:
                  - name: m
                    column: c
                dimensions:
                  - name: Geo
                    foreignKey: geo_id
                    primaryKey: id
                    table: geo_dim
                    levels:
                      - name: Country
                        column: country_id
                        type: Numeric
                        nameColumn: country_name
                        ordinalColumn: country_ord
                """;
        String xml = new MondrianCompiler().compile(new CubeYamlParser().parse(yaml));
        assertTrue(xml.contains("foreignKey=\"geo_id\""), xml);
        assertTrue(xml.contains("primaryKey=\"id\""), xml);
        assertTrue(xml.contains("<Table name=\"geo_dim\""), xml);
        assertTrue(xml.contains("nameColumn=\"country_name\""), xml);
        assertTrue(xml.contains("ordinalColumn=\"country_ord\""), xml);
        assertTrue(xml.contains("type=\"Numeric\""), xml);
    }
}
