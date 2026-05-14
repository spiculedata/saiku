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
import org.saiku.service.schema.generate.draft.DraftJoin;
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

    /**
     * Snowflake dims (one-hop) must emit both the dim source table AND the lookup table in the
     * PhysicalSchema, plus a {@code <Link source='<lookup>' target='<dim>'>} with a ForeignKey
     * column. The lookup-side level must carry {@code table='<lookup>'} on the Attribute so
     * Mondrian routes the column through the link.
     */
    @Test
    public void snowflakeDimRegistersLookupTableAndLink() throws Exception {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Orders", "sales_fact", PROV);
        schema.cubes().add(cube);

        DraftDimension product = new DraftDimension("product", DraftDimension.Type.STANDARD, PROV);
        product.setSourceTable("product");
        product.setForeignKey("product_id");
        cube.dimensions().add(product);

        DraftHierarchy hier = new DraftHierarchy("product", "id", PROV);
        hier.setJoin(new DraftJoin("product", "category_id", "product_category", "id"));
        // Source-side level on "product" (no explicit table).
        hier.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, PROV));
        // Lookup-side level: column "label" lives on "product_category".
        DraftLevel categoryLevel = new DraftLevel("Category", "label", DraftLevel.Type.REGULAR, PROV);
        categoryLevel.setTable("product_category");
        hier.levels().add(categoryLevel);
        product.hierarchies().add(hier);

        cube.measures().add(new DraftMeasure("Fact Count", "id", DraftMeasure.Aggregator.COUNT_STAR, PROV));

        String xml = new MondrianSchemaWriter().write(schema);

        // 1. Both physical tables present.
        assertTrue("missing primary dim table 'product'", xml.contains("<Table name=\"product\""));
        assertTrue("missing lookup table 'product_category'", xml.contains("<Table name=\"product_category\""));

        // 2. Link source=lookup target=dim.source with FK column.
        assertTrue("missing snowflake Link", xml.contains("<Link source=\"product_category\" target=\"product\""));
        assertTrue(
                "missing snowflake ForeignKey Column name=\"category_id\"",
                xml.contains("<Column name=\"category_id\""));

        // 3. Lookup-side attribute carries table="product_category".
        assertTrue(
                "lookup-side attribute must carry table=\"product_category\"",
                xml.matches("(?s).*<Attribute[^>]*name=\"Category\"[^>]*table=\"product_category\".*")
                        || xml.matches("(?s).*<Attribute[^>]*table=\"product_category\"[^>]*name=\"Category\".*"));

        // 4. Round-trip through Mondrian's parser.
        Parser parser = XOMUtil.createDefaultParser();
        DOMWrapper dom = parser.parse(xml);
        MondrianDef.Schema mSchema = new MondrianDef.Schema(dom);
        assertEquals("Sales", mSchema.name);

        // 5. PhysicalSchema contains two Table elements + at least one Link (the snowflake one —
        //    plus the fact→dim cube link).
        MondrianDef.PhysicalSchema phys = null;
        for (MondrianDef.SchemaElement el : mSchema.childArray) {
            if (el instanceof MondrianDef.PhysicalSchema) {
                phys = (MondrianDef.PhysicalSchema) el;
                break;
            }
        }
        assertNotNull("PhysicalSchema missing", phys);
        int tables = 0;
        int snowLinks = 0;
        for (MondrianDef.PhysicalSchemaElement el : phys.childArray) {
            if (el instanceof MondrianDef.Table) {
                tables++;
            } else if (el instanceof MondrianDef.Link) {
                MondrianDef.Link l = (MondrianDef.Link) el;
                if ("product_category".equals(l.source) && "product".equals(l.target)) {
                    snowLinks++;
                    assertNotNull("snowflake Link must have ForeignKey", l.foreignKey);
                }
            }
        }
        // sales_fact + product + product_category = 3 tables.
        assertEquals("expected 3 physical tables", 3, tables);
        assertEquals("expected one snowflake Link", 1, snowLinks);
    }

    /**
     * Two snowflake dims pointing at the same lookup table must register the lookup
     * {@code <Table>} exactly once and emit one {@code <Link>} per (lookup, dim) pair.
     */
    @Test
    public void snowflakeDedupesSharedLookupTable() throws Exception {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Orders", "sales_fact", PROV);
        schema.cubes().add(cube);

        cube.dimensions().add(snowflakeDim("product", "product_id", "cat_id", "taxonomy"));
        cube.dimensions().add(snowflakeDim("service", "service_id", "cat_id", "taxonomy"));
        cube.measures().add(new DraftMeasure("Fact Count", "id", DraftMeasure.Aggregator.COUNT_STAR, PROV));

        String xml = new MondrianSchemaWriter().write(schema);

        // The lookup table must appear exactly once.
        int lookupCount = 0;
        int idx = 0;
        while ((idx = xml.indexOf("<Table name=\"taxonomy\"", idx)) >= 0) {
            lookupCount++;
            idx++;
        }
        assertEquals("lookup table should be emitted exactly once", 1, lookupCount);

        // Two snowflake links (one per dim).
        assertTrue(xml.contains("<Link source=\"taxonomy\" target=\"product\""));
        assertTrue(xml.contains("<Link source=\"taxonomy\" target=\"service\""));
    }

    private static DraftDimension snowflakeDim(
            String dimTable, String foreignKey, String joinColumn, String lookupTable) {
        DraftDimension d = new DraftDimension(dimTable, DraftDimension.Type.STANDARD, PROV);
        d.setSourceTable(dimTable);
        d.setForeignKey(foreignKey);
        DraftHierarchy h = new DraftHierarchy(dimTable, "id", PROV);
        h.setJoin(new DraftJoin(dimTable, joinColumn, lookupTable, "id"));
        h.levels().add(new DraftLevel("Name", "name", DraftLevel.Type.REGULAR, PROV));
        DraftLevel cat = new DraftLevel("Category_" + dimTable, "label", DraftLevel.Type.REGULAR, PROV);
        cat.setTable(lookupTable);
        h.levels().add(cat);
        d.hierarchies().add(h);
        return d;
    }

    /**
     * A {@link DraftLevel} with a {@code nameColumn} set must emit a {@code nameColumn} attribute
     * on the Mondrian Attribute, and that attribute must survive a round-trip through Mondrian's
     * own parser. When {@code nameColumn} is null the attribute must not appear at all — this
     * preserves the pre-F6 output for levels with no distinct caption source.
     */
    @Test
    public void levelWithNameColumnEmitsAttributeAndRoundTrips() throws Exception {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Orders", "orders", PROV);
        schema.cubes().add(cube);

        DraftDimension customer = new DraftDimension("Customer", DraftDimension.Type.STANDARD, PROV);
        customer.setSourceTable("customers");
        customer.setForeignKey("customer_id");
        cube.dimensions().add(customer);

        DraftHierarchy hier = new DraftHierarchy("Customer", "id", PROV);
        customer.hierarchies().add(hier);

        DraftLevel named = new DraftLevel("Customer", "id", DraftLevel.Type.REGULAR, PROV);
        named.setNameColumn("name");
        hier.levels().add(named);

        cube.measures().add(new DraftMeasure("Fact Count", "id", DraftMeasure.Aggregator.COUNT_STAR, PROV));

        String xml = new MondrianSchemaWriter().write(schema);

        // Attribute carries keyColumn=id and nameColumn=name.
        assertTrue(
                "expected Attribute with keyColumn=\"id\" nameColumn=\"name\"",
                xml.matches("(?s).*<Attribute[^>]*name=\"Customer\"[^>]*keyColumn=\"id\"[^>]*nameColumn=\"name\".*")
                        || xml.matches(
                                "(?s).*<Attribute[^>]*name=\"Customer\"[^>]*nameColumn=\"name\"[^>]*keyColumn=\"id\".*"));

        // Round-trip: parse via Mondrian and confirm the Attribute.nameColumn field.
        Parser parser = XOMUtil.createDefaultParser();
        DOMWrapper dom = parser.parse(xml);
        MondrianDef.Schema mSchema = new MondrianDef.Schema(dom);
        MondrianDef.Attribute parsed = findAttribute(mSchema, "Customer", "Customer");
        assertNotNull("Attribute 'Customer' missing from parsed schema", parsed);
        assertEquals("id", parsed.keyColumn);
        assertEquals("name", parsed.nameColumn);
    }

    @Test
    public void levelWithoutNameColumnOmitsAttribute() throws Exception {
        DraftSchema schema = new DraftSchema("Sales");
        DraftCube cube = new DraftCube("Orders", "orders", PROV);
        schema.cubes().add(cube);

        DraftDimension codes = new DraftDimension("Codes", DraftDimension.Type.STANDARD, PROV);
        codes.setSourceTable("codes");
        codes.setForeignKey("code_id");
        cube.dimensions().add(codes);

        DraftHierarchy hier = new DraftHierarchy("Codes", "id", PROV);
        codes.hierarchies().add(hier);
        // No nameColumn — key only, original behaviour.
        hier.levels().add(new DraftLevel("Code", "id", DraftLevel.Type.REGULAR, PROV));

        cube.measures().add(new DraftMeasure("Fact Count", "id", DraftMeasure.Aggregator.COUNT_STAR, PROV));

        String xml = new MondrianSchemaWriter().write(schema);
        assertFalse(
                "null nameColumn must not emit a nameColumn attribute",
                xml.matches("(?s).*<Attribute[^>]*name=\"Code\"[^>]*nameColumn=.*"));
    }

    private static MondrianDef.Attribute findAttribute(MondrianDef.Schema s, String dimName, String attrName) {
        for (MondrianDef.SchemaElement se : s.childArray) {
            if (se instanceof MondrianDef.Cube) {
                MondrianDef.Cube c = (MondrianDef.Cube) se;
                for (MondrianDef.CubeElement ce : c.childArray) {
                    if (ce instanceof MondrianDef.Dimensions) {
                        for (MondrianDef.Dimension d : ((MondrianDef.Dimensions) ce).array) {
                            if (!dimName.equals(d.name)) {
                                continue;
                            }
                            for (MondrianDef.DimensionElement de : d.childArray) {
                                if (de instanceof MondrianDef.Attributes) {
                                    for (MondrianDef.Attribute a : ((MondrianDef.Attributes) de).array) {
                                        if (attrName.equals(a.name)) {
                                            return a;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static DraftLevel timeLevel(String name, DraftLevel.Type type, String column, String expression) {
        DraftLevel l = new DraftLevel(name, column, type, PROV);
        l.setExpression(expression);
        return l;
    }
}
