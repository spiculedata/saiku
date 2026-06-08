/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.forecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCell;
import org.saiku.service.olap.ai.AiQueryMetadata;
import org.saiku.service.olap.ai.AiQueryResponse;
import org.saiku.service.olap.ai.AiValidationException;

/** Locks the records→forecast-block assembler (saiku#908). */
public class ForecastAssemblerTest {

    private static AiQueryResponse responseWith(String measure, double... values) {
        AiQueryResponse resp = new AiQueryResponse();
        AiQueryMetadata meta = new AiQueryMetadata();
        List<AiQueryMetadata.Caption> cols = new ArrayList<>();
        cols.add(new AiQueryMetadata.Caption(measure, measure));
        meta.setColumns(cols);
        resp.setMetadata(meta);
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Month", "M" + (i + 1)); // row-header column (plain string)
            row.put(measure, new AiCell(values[i], String.valueOf(values[i]), null));
            data.add(row);
        }
        resp.setData(data);
        return resp;
    }

    @Test
    public void forecastsEachMeasure_continuingTheTrend() {
        AiQueryResponse resp = responseWith("Sales", 1, 2, 3, 4, 5, 6);
        Map<String, List<ForecastPoint>> block =
                ForecastAssembler.assemble(resp, new EtsForecaster(), 3, 0.95, "[Time].[Month]");
        assertTrue(block.containsKey("Sales"));
        List<ForecastPoint> f = block.get("Sales");
        assertEquals(3, f.size());
        assertEquals(7.0, f.get(0).getValue(), 0.01);
        assertEquals(9.0, f.get(2).getValue(), 0.01);
    }

    @Test
    public void emptyData_yieldsEmptyBlock_noThrow() {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setData(new ArrayList<>());
        Map<String, List<ForecastPoint>> block =
                ForecastAssembler.assemble(resp, new EtsForecaster(), 6, 0.95, "[Time].[Month]");
        assertTrue(block.isEmpty());
    }

    @Test
    public void noMeasureColumns_throwsValidation() {
        AiQueryResponse resp = new AiQueryResponse();
        AiQueryMetadata meta = new AiQueryMetadata();
        meta.setColumns(new ArrayList<>()); // no measure columns
        resp.setMetadata(meta);
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Month", "M1");
        data.add(row);
        resp.setData(data);
        try {
            ForecastAssembler.assemble(resp, new EtsForecaster(), 6, 0.95, "[Time].[Month]");
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("timeAxis", e.getField());
        }
    }

    @Test
    public void badHorizon_throwsValidation() {
        AiQueryResponse resp = responseWith("Sales", 1, 2, 3);
        try {
            ForecastAssembler.assemble(resp, new EtsForecaster(), 0, 0.95, "[Time].[Month]");
            fail("expected AiValidationException for horizon < 1");
        } catch (AiValidationException e) {
            assertEquals("horizon", e.getField());
        }
    }

    @Test
    public void badConfidence_throwsValidation() {
        AiQueryResponse resp = responseWith("Sales", 1, 2, 3);
        try {
            ForecastAssembler.assemble(resp, new EtsForecaster(), 6, 1.5, "[Time].[Month]");
            fail("expected AiValidationException for confidence out of range");
        } catch (AiValidationException e) {
            assertEquals("confidence", e.getField());
        }
    }
}
