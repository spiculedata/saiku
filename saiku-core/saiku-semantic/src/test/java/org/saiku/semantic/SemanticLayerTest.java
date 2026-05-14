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
}
