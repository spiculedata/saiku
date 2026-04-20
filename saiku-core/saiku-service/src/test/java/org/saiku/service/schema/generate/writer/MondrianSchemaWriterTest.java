package org.saiku.service.schema.generate.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * Degenerate TIME dims on a cube must emit:
     * <ul>
     *   <li>no phantom {@code <Table name="Time"/>} in the PhysicalSchema;
     *   <li>a {@code ColumnDefs/CalculatedColumnDef} on the fact carrying the {@code YEAR(...)}
     *       / {@code QUARTER(...)} / {@code MONTH(...)} / {@code DAY(...)} SQL;
     *   <li>a cube-local {@code Dimension type="TIME"} whose attributes' {@code keyColumn}
     *       references the calc-column names;
     *   <li>no {@code ForeignKeyLink} for the TIME dim.
     * </ul>
     */
    @Test
    public void degenerateTimeDimEmitsCalculatedColumnsAndNoPhantomTable() throws Exception {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Orders", "orders", PROV);
        schema.cubes().add(cube);

        DraftDimension time = new DraftDimension("order_date", DraftDimension.Type.TIME, PROV);
        time.setSourceTable("orders");
        // foreignKey stays null — degenerate.
        DraftHierarchy h = new DraftHierarchy("order_date", "order_date", PROV);
        h.levels().add(timeLevel("Year", DraftLevel.Type.YEARS, "order_date", "YEAR(order_date)"));
        h.levels().add(timeLevel("Quarter", DraftLevel.Type.QUARTERS, "order_date", "QUARTER(order_date)"));
        h.levels().add(timeLevel("Month", DraftLevel.Type.MONTHS, "order_date", "MONTH(order_date)"));
        h.levels().add(timeLevel("Day", DraftLevel.Type.DAYS, "order_date", "DAY(order_date)"));
        time.hierarchies().add(h);
        cube.dimensions().add(time);

        cube.measures().add(new DraftMeasure("Fact Count", "id", DraftMeasure.Aggregator.COUNT_STAR, PROV));

        String xml = new MondrianSchemaWriter().write(schema);

        // 1. No phantom Time table.
        assertFalse("phantom Time table should not be emitted", xml.contains("<Table name=\"Time\""));

        // 2. CalculatedColumnDef entries with the expected SQL.
        assertTrue("expected YEAR(order_date) in XML", xml.contains("YEAR(order_date)"));
        assertTrue("expected QUARTER(order_date) in XML", xml.contains("QUARTER(order_date)"));
        assertTrue("expected MONTH(order_date) in XML", xml.contains("MONTH(order_date)"));
        assertTrue("expected DAY(order_date) in XML", xml.contains("DAY(order_date)"));
        assertTrue("expected CalculatedColumnDef element", xml.contains("<CalculatedColumnDef"));
        assertTrue("expected order_date_year calc column", xml.contains("order_date_year"));

        // 3. Attributes key on the calc columns.
        assertTrue("attribute keys should point at calc columns", xml.contains("keyColumn=\"order_date_day\""));

        // 4. TIME dim has type="TIME" and no ForeignKeyLink.
        assertTrue(xml.contains("type=\"TIME\""));
        assertFalse(
                "degenerate TIME dim must not emit a ForeignKeyLink",
                xml.contains("<ForeignKeyLink") && xml.contains("dimension=\"order_date\""));

        // 5. Round-trip through Mondrian's parser.
        Parser parser = XOMUtil.createDefaultParser();
        DOMWrapper dom = parser.parse(xml);
        MondrianDef.Schema mSchema = new MondrianDef.Schema(dom);
        assertEquals("Sales", mSchema.name);
    }

    private static DraftLevel timeLevel(String name, DraftLevel.Type type, String column, String expression) {
        DraftLevel l = new DraftLevel(name, column, type, PROV);
        l.setExpression(expression);
        return l;
    }
}
