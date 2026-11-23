/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * saiku#1434 — the Mondrian parser has to cope with every schema shape Saiku ships, because the
 * diff is only as trustworthy as its reader.
 */
class MondrianXmlModelParserTest {

    private final MondrianXmlModelParser parser = new MondrianXmlModelParser();

    private static final String CLASSIC_3 = "<Schema name='FoodMart'>"
            + "<Cube name='Sales'>"
            + "  <Table name='fact_sales'/>"
            + "  <Dimension name='Product'>"
            + "    <Hierarchy hasAll='false'>"
            + "      <Level name='Product Family' column='product_family'/>"
            + "      <Level name='Product Name' column='product_name'/>"
            + "    </Hierarchy>"
            + "  </Dimension>"
            + "  <Measure name='Unit Sales' column='unit_sales' aggregator='sum' formatCurrency='0'/>"
            + "  <Measure name='Store Cost' column='store_cost' aggregator='sum'/>"
            + "</Cube>"
            + "</Schema>";

    @Test
    void readsClassic3Schema() {
        ModelSnapshot snapshot = parser.parse(CLASSIC_3, "FoodMart.xml");
        assertEquals(ModelFormat.MONDRIAN_XML, snapshot.format());
        assertEquals("FoodMart", snapshot.name());
        assertTrue(snapshot.hasCube("Sales"));
        assertTrue(snapshot.hasMeasure("Sales", "Unit Sales"));
        assertTrue(snapshot.hasMeasure("Sales", "Store Cost"));
        assertTrue(snapshot.hasDimension("Sales", "Product"));
        assertTrue(snapshot.hasLevel("Sales", "Product", "Product Family"));
    }

    @Test
    void memberLookupIsCaseInsensitive() {
        ModelSnapshot snapshot = parser.parse(CLASSIC_3, "FoodMart.xml");
        assertTrue(snapshot.hasMeasure("Sales", "unit sales"));
        assertTrue(snapshot.hasLevel("Sales", "product", "PRODUCT FAMILY"));
        assertFalse(snapshot.hasMeasure("Sales", "Unit Sold"));
    }

    @Test
    void readsMondrian4MeasureGroups() {
        String m4 = "<Schema name='FoodMart' metamodelVersion='4.0'>"
                + "<PhysicalSchema><Table name='fact_sales'/></PhysicalSchema>"
                + "<Cube name='Sales'>"
                + "  <MeasureGroups><MeasureGroup name='fact' table='fact_sales'>"
                + "    <Measures><Measure name='Unit Sales' column='unit_sales' aggregator='sum'/>"
                + "    <Measure name='Store Cost' column='store_cost' aggregator='sum'/></Measures>"
                + "  </MeasureGroup></MeasureGroups>"
                + "</Cube></Schema>";
        ModelSnapshot snapshot = parser.parse(m4, "FoodMart4.xml");
        assertTrue(snapshot.hasMeasure("Sales", "Unit Sales"));
        assertTrue(snapshot.hasMeasure("Sales", "Store Cost"));
    }

    @Test
    void dimensionUsageProjectsSharedLevelsUnderTheAlias() {
        // The shape that produces the worst false positive if unhandled: MDX addresses
        // [Product].[Product Family], but the levels are declared on a schema-scoped dimension.
        String shared = "<Schema name='FoodMart'>"
                + "<Dimension name='Product'>"
                + "  <Hierarchy hasAll='false'>"
                + "    <Level name='Product Family' column='product_family'/>"
                + "  </Hierarchy>"
                + "</Dimension>"
                + "<Cube name='Sales'>"
                + "  <DimensionUsage name='Product' source='Product'/>"
                + "</Cube></Schema>";
        ModelSnapshot snapshot = parser.parse(shared, "FoodMart.xml");
        assertTrue(snapshot.hasDimension("Sales", "Product"));
        assertTrue(snapshot.hasLevel("Sales", "Product", "Product Family"));
    }

    @Test
    void readsVirtualCubes() {
        String virtual = "<Schema name='FoodMart'><VirtualCube name='Sales Virtual'>"
                + "<VirtualMeasures><Measure name='Unit Sales'/></VirtualMeasures>"
                + "</VirtualCube></Schema>";
        ModelSnapshot snapshot = parser.parse(virtual, "FoodMart.xml");
        assertTrue(snapshot.hasCube("Sales Virtual"));
    }

    @Test
    void readsCalculatedMembers() {
        String calculated = "<Schema name='FoodMart'><Cube name='Sales'>"
                + "<CalculatedMember name='Store Sales' formula='[Measures].[Unit Sales] * 1.5'/>"
                + "<Dimension name='Time'><Hierarchy><Level name='Quarter' column='q'/>"
                + "<CalculatedMember name='Fiscal Quarter' formula='[Time].[Quarter]'/></Hierarchy></Dimension>"
                + "</Cube></Schema>";
        ModelSnapshot snapshot = parser.parse(calculated, "FoodMart.xml");
        assertTrue(snapshot.hasMeasure("Sales", "Store Sales"));
        assertTrue(snapshot.hasLevel("Sales", "Time", "Fiscal Quarter"));
    }

    @Test
    void readsCalculatedMembersInsideACalculatedMembersContainer() {
        // The shape FoodMart4 actually ships: calculated members inside a <CalculatedMembers>
        // wrapper. Reading only direct children made every reference to one of them look broken —
        // found by running the validator over the shipped demo repository.
        String calculated = "<Schema name='FoodMart'><Cube name='Sales'>"
                + "<Measure name='Store Sales' column='ss' aggregator='sum'/>"
                + "<Measure name='Store Cost' column='sc' aggregator='sum'/>"
                + "<CalculatedMembers>"
                + "  <CalculatedMember name='Profit' dimension='Measures'>"
                + "    <Formula>[Measures].[Store Sales] - [Measures].[Store Cost]</Formula>"
                + "  </CalculatedMember>"
                + "  <CalculatedMember name='Store Sales Growth' dimension='Measures' caption='MoM Growth'>"
                + "    <Formula>x</Formula>"
                + "  </CalculatedMember>"
                + "</CalculatedMembers></Cube></Schema>";
        ModelSnapshot snapshot = parser.parse(calculated, "FoodMart4.xml");
        assertTrue(snapshot.hasMeasure("Sales", "Profit"));
        assertTrue(snapshot.hasMeasure("Sales", "Store Sales Growth"));
    }

    @Test
    void aCaptionResolvesTheMemberItNames() {
        // A dashboard tile authored in the UI binds the caption the schema gives a member
        // (FoodMart4 captions "Store Sales Growth" as "MoM Growth"), and the shipped demo app does
        // exactly that. Name-only lookup would report a working tile as broken.
        String captioned = "<Schema name='FoodMart'><Cube name='Sales'>"
                + "<CalculatedMembers><CalculatedMember name='Store Sales Growth' dimension='Measures' "
                + "caption='MoM Growth'><Formula>x</Formula></CalculatedMember></CalculatedMembers>"
                + "</Cube></Schema>";
        ModelSnapshot snapshot = parser.parse(captioned, "FoodMart4.xml");
        assertTrue(snapshot.hasMeasure("Sales", "MoM Growth"));
        assertTrue(snapshot.hasMeasure("Sales", "mom growth"));
        assertTrue(snapshot.find(ModelElementKind.MEASURE, "Sales", null, "MoM Growth")
                .orElseThrow()
                .name()
                .equals("Store Sales Growth"));
    }

    @Test
    void malformedXmlIsRejected() {
        ModelDiffException e = assertThrows(
                ModelDiffException.class,
                () -> parser.parse("<Schema name='X'><Cube name='C'></Schema>", "broken.xml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void emptyPayloadIsRejected() {
        ModelDiffException e = assertThrows(ModelDiffException.class, () -> parser.parse("", "empty.xml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void externalEntitiesAreRefused() {
        // XXE: a model payload arrives from a REST body, so an entity-capable parser here would
        // be a file-read primitive reachable by any admin.
        String xxe = "<?xml version='1.0'?><!DOCTYPE Schema [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<Schema name='&xxe;'><Cube name='C'/></Schema>";
        ModelDiffException e = assertThrows(ModelDiffException.class, () -> parser.parse(xxe, "xxe.xml"));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void signatureExcludesTheNameSoARenameIsRecognisable() {
        String renamed = CLASSIC_3.replace("name='Unit Sales'", "name='Units'");
        ModelSnapshot before = parser.parse(CLASSIC_3, "before.xml");
        ModelSnapshot after = parser.parse(renamed, "after.xml");
        ModelElement original = before.find(ModelElementKind.MEASURE, "Sales", null, "Unit Sales")
                .orElseThrow();
        ModelElement renamed2 =
                after.find(ModelElementKind.MEASURE, "Sales", null, "Units").orElseThrow();
        assertEquals(original.signature(), renamed2.signature());
    }
}
