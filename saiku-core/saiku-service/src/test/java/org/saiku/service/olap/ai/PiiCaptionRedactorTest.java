/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;

/** saiku#910 — unit coverage for the dashboard-narrative PII caption redaction pass. */
public class PiiCaptionRedactorTest {

    private static final String CUSTOMER_LEVEL = "[Customer].[Customer].[Customer]";
    private static final String YEAR_LEVEL = "[Time].[Time By].[Year]";

    private static AiSchema schemaWithPiiCustomerLevel() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");

        AiSchema.Dimension customer = new AiSchema.Dimension("Customer", "[Customer]");
        AiSchema.Hierarchy customerH = new AiSchema.Hierarchy("Customer", "[Customer].[Customer]");
        AiSchema.Level customerLevel = new AiSchema.Level("Customer", CUSTOMER_LEVEL);
        customerLevel.pii = true;
        customerH.levels.put(AiSchema.key("Customer"), customerLevel);
        customer.hierarchies.put(AiSchema.key("Customer"), customerH);
        schema.dimensions.put(AiSchema.key("Customer"), customer);

        AiSchema.Dimension time = new AiSchema.Dimension("Time", "[Time]");
        AiSchema.Hierarchy timeH = new AiSchema.Hierarchy("Time By", "[Time].[Time By]");
        timeH.levels.put(AiSchema.key("Year"), new AiSchema.Level("Year", YEAR_LEVEL));
        time.hierarchies.put(AiSchema.key("Time By"), timeH);
        schema.dimensions.put(AiSchema.key("Time"), time);

        return schema;
    }

    /** Row-header MemberCell + a measure DataCell, mirroring the AI query result grid shape. */
    private static CellDataSet cellDataSetWithRowHeader(String level, String caption, String measureValue) {
        CellDataSet cds = new CellDataSet(2, 1);

        AbstractBaseCell hdrA = new MemberCell(false, false);
        hdrA.setFormattedValue("Customer");
        AbstractBaseCell hdrB = new MemberCell(false, false);
        hdrB.setFormattedValue("Store Sales");
        cds.setCellSetHeaders(new AbstractBaseCell[][] {new AbstractBaseCell[] {hdrA, hdrB}});

        MemberCell rowHeader = new MemberCell(false, false);
        rowHeader.setFormattedValue(caption);
        rowHeader.setRawValue(caption);
        rowHeader.setLevel(level);

        AbstractBaseCell dataCell = new DataCell(true, false, null);
        dataCell.setFormattedValue(measureValue);
        dataCell.setRawValue(measureValue);

        cds.setCellSetBody(new AbstractBaseCell[][] {new AbstractBaseCell[] {rowHeader, dataCell}});
        return cds;
    }

    @Test
    public void redactsCaptionFromPiiLevel_keepsMeasureValue() {
        AiSchema schema = schemaWithPiiCustomerLevel();
        CellDataSet cds = cellDataSetWithRowHeader(CUSTOMER_LEVEL, "John Smith", "100");

        int redacted = PiiCaptionRedactor.redact(cds, schema);

        assertEquals(1, redacted);
        MemberCell rowHeader = (MemberCell) cds.getCellSetBody()[0][0];
        assertEquals(PiiCaptionRedactor.REDACTED, rowHeader.getFormattedValue());
        assertEquals(PiiCaptionRedactor.REDACTED, rowHeader.getRawValue());
        // measure value untouched — the contract is "values still in prompt, captions redacted".
        assertEquals("100", cds.getCellSetBody()[0][1].getFormattedValue());
    }

    @Test
    public void nonPiiLevelCaptionUntouched() {
        AiSchema schema = schemaWithPiiCustomerLevel();
        CellDataSet cds = cellDataSetWithRowHeader(YEAR_LEVEL, "1997", "100");

        int redacted = PiiCaptionRedactor.redact(cds, schema);

        assertEquals(0, redacted);
        assertEquals("1997", cds.getCellSetBody()[0][0].getFormattedValue());
    }

    @Test
    public void schemaWithNoPiiLevels_isNoOp() {
        AiSchema schema = new AiSchema("foodmart/FoodMart/FoodMart/Sales", "Sales", "[FoodMart].[Sales]");
        CellDataSet cds = cellDataSetWithRowHeader(CUSTOMER_LEVEL, "John Smith", "100");

        assertEquals(0, PiiCaptionRedactor.redact(cds, schema));
        assertEquals("John Smith", cds.getCellSetBody()[0][0].getFormattedValue());
    }

    @Test
    public void nullArguments_areSafeNoOps() {
        AiSchema schema = schemaWithPiiCustomerLevel();
        CellDataSet cds = cellDataSetWithRowHeader(CUSTOMER_LEVEL, "John Smith", "100");

        assertEquals(0, PiiCaptionRedactor.redact(null, schema));
        assertEquals(0, PiiCaptionRedactor.redact(cds, null));
        assertNull(schema.dimensions.get(AiSchema.key("Nonexistent")));
    }
}
