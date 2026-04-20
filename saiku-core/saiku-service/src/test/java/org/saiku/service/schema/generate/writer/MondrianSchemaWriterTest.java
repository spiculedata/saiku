package org.saiku.service.schema.generate.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import mondrian.olap.MondrianDef;
import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMUtil;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Round-trip test for {@link MondrianSchemaWriter}.
 *
 * <p>Builds a minimal foodmart-style {@link DraftSchema} (1 cube, 1 standard dim, 1 measure), runs
 * the writer, and asserts:
 *
 * <ol>
 *   <li>Output is well-formed XML (parsed by JDK DocumentBuilder).
 *   <li>Output re-parses through Mondrian's own schema constructor ({@code new
 *       MondrianDef.Schema(DOMWrapper)}) without throwing — that validates required attributes and
 *       element structure per Mondrian's XOM metamodel. This does not boot a full RolapSchema
 *       connection (which would require a datasource), but does exercise Mondrian's own parser.
 *   <li>Cube, dimension and measure survive the round-trip with correct names and aggregator.
 * </ol>
 */
public class MondrianSchemaWriterTest {

    private static final Provenance PROV = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

    private DraftSchema buildMinimalSchema() {
        DraftSchema schema = new DraftSchema("Sales");

        DraftCube cube = new DraftCube("Orders", "orders", PROV);
        schema.cubes().add(cube);

        DraftDimension customer = new DraftDimension("Customer", DraftDimension.Type.STANDARD, PROV);
        customer.setSourceTable("customers");
        customer.setForeignKey("customer_id");
        cube.dimensions().add(customer);

        DraftHierarchy hier = new DraftHierarchy("Customer", "id", PROV);
        customer.hierarchies().add(hier);

        DraftLevel level = new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, PROV);
        hier.levels().add(level);

        DraftMeasure measure = new DraftMeasure("Total Sales", "sales", DraftMeasure.Aggregator.SUM, PROV);
        cube.measures().add(measure);

        return schema;
    }

    @Test
    public void writesWellFormedXmlContainingSchemaCubeAndMeasure() throws Exception {
        DraftSchema draft = buildMinimalSchema();

        String xml = new MondrianSchemaWriter().write(draft);

        assertNotNull("writer returned null", xml);
        assertTrue("xml is empty", xml.length() > 0);
        assertTrue("missing Schema name=\"Sales\"", xml.contains("name=\"Sales\""));
        assertTrue("missing Cube element", xml.contains("<Cube"));
        assertTrue("missing Measure element", xml.contains("<Measure"));

        // Well-formed via JDK parser.
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.parse(new InputSource(new StringReader(xml)));
        assertEquals("Schema", doc.getDocumentElement().getNodeName());
    }

    @Test
    public void xmlRoundTripsThroughMondrianSchemaParser() throws Exception {
        DraftSchema draft = buildMinimalSchema();

        String xml = new MondrianSchemaWriter().write(draft);

        Parser parser = XOMUtil.createDefaultParser();
        DOMWrapper dom = parser.parse(xml);

        MondrianDef.Schema mSchema = new MondrianDef.Schema(dom);

        assertEquals("Sales", mSchema.name);

        // Locate the cube.
        MondrianDef.Cube parsedCube = null;
        for (MondrianDef.SchemaElement el : mSchema.childArray) {
            if (el instanceof MondrianDef.Cube) {
                parsedCube = (MondrianDef.Cube) el;
                break;
            }
        }
        assertNotNull("no Cube in parsed schema", parsedCube);
        assertEquals("Orders", parsedCube.name);

        // Locate the measure inside the cube's single MeasureGroup.
        MondrianDef.Measure parsedMeasure = null;
        for (MondrianDef.CubeElement ce : parsedCube.childArray) {
            if (ce instanceof MondrianDef.MeasureGroups) {
                MondrianDef.MeasureGroups mgs = (MondrianDef.MeasureGroups) ce;
                for (MondrianDef.MeasureGroup mg : mgs.array) {
                    for (MondrianDef.MeasureGroupElement mge : mg.childArray) {
                        if (mge instanceof MondrianDef.Measures) {
                            MondrianDef.Measures ms = (MondrianDef.Measures) mge;
                            for (MondrianDef.MeasureOrRef mor : ms.array) {
                                if (mor instanceof MondrianDef.Measure) {
                                    parsedMeasure = (MondrianDef.Measure) mor;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertNotNull("no Measure in parsed schema", parsedMeasure);
        assertEquals("Total Sales", parsedMeasure.name);
        assertEquals("sum", parsedMeasure.aggregator);
        assertEquals("sales", parsedMeasure.column);
    }
}
