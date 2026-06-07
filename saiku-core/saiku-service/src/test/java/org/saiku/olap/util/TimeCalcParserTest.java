/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Test;
import org.saiku.olap.dto.SaikuTimeCalc;
import org.saiku.service.util.xml.SecureXml;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Unit tests for {@link TimeCalcParser}. No filesystem when avoidable —
 *  most assertions feed a {@link Document} parsed from an in-memory string. */
public class TimeCalcParserTest {

    private static final String BANK_SCHEMA_FRAGMENT =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<Schema name='Bank'>\n"
                    + "  <Cube name='Monthly Revenue'>\n"
                    + "    <MeasureGroups/>\n"
                    + "    <TimeCalcs>\n"
                    + "      <TimeCalc name='Revenue YoY' type='yoy' measure='Revenue'\n"
                    + "                timeDimension='Calendar' formatString='0.0%'/>\n"
                    + "      <TimeCalc name='Revenue PoP' type='pop' measure='Revenue'\n"
                    + "                timeDimension='Calendar' formatString='0.0%'/>\n"
                    + "      <TimeCalc name='Revenue YTD' type='ytd' measure='Revenue'\n"
                    + "                timeDimension='Calendar' formatString='#,###'/>\n"
                    + "      <TimeCalc name='Revenue R3' type='rolling' measure='Revenue'\n"
                    + "                timeDimension='Calendar' window='3' function='avg'\n"
                    + "                formatString='#,###'/>\n"
                    + "    </TimeCalcs>\n"
                    + "  </Cube>\n"
                    + "  <Cube name='Accounts'>\n"
                    + "    <!-- no TimeCalcs here — parser must return empty list for this cube -->\n"
                    + "    <MeasureGroups/>\n"
                    + "  </Cube>\n"
                    + "</Schema>\n";

    private static Document doc(String xml) throws IOException, SAXException, ParserConfigurationException {
        return SecureXml.secureDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void extractsAllFourDirectivesForMonthlyRevenue() throws Exception {
        List<SaikuTimeCalc> calcs = TimeCalcParser.parseDocument(doc(BANK_SCHEMA_FRAGMENT), "Monthly Revenue");
        assertEquals(4, calcs.size());

        SaikuTimeCalc yoy = calcs.get(0);
        assertEquals("Revenue YoY", yoy.getName());
        assertEquals("yoy", yoy.getType());
        assertEquals("Revenue", yoy.getMeasure());
        assertEquals("Calendar", yoy.getTimeDimension());
        assertEquals("0.0%", yoy.getFormatString());
        assertNull("non-rolling kinds should have window=null", yoy.getWindow());
        assertNull("non-rolling kinds should have function=null", yoy.getFunction());

        SaikuTimeCalc rolling = calcs.get(3);
        assertEquals("rolling", rolling.getType());
        assertEquals(Integer.valueOf(3), rolling.getWindow());
        assertEquals("avg", rolling.getFunction());
    }

    @Test
    public void returnsEmptyListForCubeWithoutTimeCalcs() throws Exception {
        List<SaikuTimeCalc> calcs = TimeCalcParser.parseDocument(doc(BANK_SCHEMA_FRAGMENT), "Accounts");
        assertTrue("Accounts has no <TimeCalcs> — expected []", calcs.isEmpty());
    }

    @Test
    public void returnsEmptyListForMissingCube() throws Exception {
        List<SaikuTimeCalc> calcs = TimeCalcParser.parseDocument(doc(BANK_SCHEMA_FRAGMENT), "ThereIsNoCubeNamedThis");
        assertTrue(calcs.isEmpty());
    }

    @Test
    public void parseFileBackedSchemaRoundTrips() throws Exception {
        Path tmp = Files.createTempFile("saiku-timecalc-", ".xml");
        try {
            Files.writeString(tmp, BANK_SCHEMA_FRAGMENT, StandardCharsets.UTF_8);
            List<SaikuTimeCalc> calcs = TimeCalcParser.parse(tmp.toUri().toString(), "Monthly Revenue");
            assertEquals(4, calcs.size());
            assertEquals("Revenue YoY", calcs.get(0).getName());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void parseReturnsEmptyOnUnreadableFile() {
        List<SaikuTimeCalc> calcs = TimeCalcParser.parse("file:/nonexistent/path/to/missing.xml", "Whatever");
        assertTrue("missing file → empty list, never throw", calcs.isEmpty());
    }

    @Test
    public void parseReturnsEmptyOnNullOrBlankInputs() {
        assertTrue(TimeCalcParser.parse(null, "Monthly Revenue").isEmpty());
        assertTrue(TimeCalcParser.parse("", "Monthly Revenue").isEmpty());
        assertTrue(TimeCalcParser.parse("file:/x.xml", null).isEmpty());
        assertTrue(TimeCalcParser.parse("file:/x.xml", "").isEmpty());
    }

    // --- Catalog URL extraction ---------------------------------------------

    @Test
    public void extractsCatalogUrlFromMondrianConnectionString() {
        String dsl = "jdbc:mondrian:Jdbc=jdbc:h2:/app/data/foodmart;Catalog=file:/app/data/Bank.xml;JdbcDrivers=org.h2.Driver";
        assertEquals("file:/app/data/Bank.xml", TimeCalcParser.extractCatalogUrl(dsl));
    }

    @Test
    public void extractsCatalogUrlIsCaseInsensitiveOnKey() {
        String dsl = "Jdbc=jdbc:h2:/x;catalog=file:/y/Bank.xml";
        assertEquals("file:/y/Bank.xml", TimeCalcParser.extractCatalogUrl(dsl));
    }

    @Test
    public void extractsCatalogUrlNullWhenAbsent() {
        assertNull(TimeCalcParser.extractCatalogUrl("Jdbc=jdbc:h2:/x;JdbcDrivers=org.h2.Driver"));
        assertNull(TimeCalcParser.extractCatalogUrl(null));
    }

    @Test
    public void parseFromDatasourceLocationGluesParseAndExtract() throws Exception {
        Path tmp = Files.createTempFile("saiku-timecalc-dsl-", ".xml");
        try {
            Files.writeString(tmp, BANK_SCHEMA_FRAGMENT, StandardCharsets.UTF_8);
            String dsl = "Jdbc=jdbc:h2:/db;Catalog=" + tmp.toUri() + ";JdbcDrivers=org.h2.Driver";
            List<SaikuTimeCalc> calcs = TimeCalcParser.parseFromDatasourceLocation(dsl, "Monthly Revenue");
            assertNotNull(calcs);
            assertEquals(4, calcs.size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
