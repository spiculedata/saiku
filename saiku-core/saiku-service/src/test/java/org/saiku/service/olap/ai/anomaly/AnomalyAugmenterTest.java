/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/** saiku#907 — augmenter writes verdicts back onto flagged cells. */
public class AnomalyAugmenterTest {

    /** Build a records-format response: one measure column "Sales" over N rows. */
    private static AiQueryResponse responseWithSeries(double[] values) {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setFormat("records");
        AiQueryMetadata meta = new AiQueryMetadata();
        List<AiQueryMetadata.Caption> cols = new ArrayList<>();
        cols.add(new AiQueryMetadata.Caption("Sales", "Sales"));
        meta.setColumns(cols);
        resp.setMetadata(meta);

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Month", "M" + i); // row-header column = plain string
            AiCell cell = Double.isNaN(values[i]) ? new AiCell(null, "", null) : new AiCell(values[i], "", null);
            row.put("Sales", cell);
            data.add(row);
        }
        resp.setData(data);
        resp.setTotalRows(values.length);
        return resp;
    }

    private static AiCell salesCell(AiQueryResponse resp, int row) {
        return (AiCell) resp.getData().get(row).get("Sales");
    }

    @Test
    public void spikeCellGetsAnomalyAttached() {
        double[] s = {10, 11, 9, 10, 10, 100, 11, 9, 10, 10, 9, 11, 10, 10, 9, 11, 10, 9, 11, 10};
        AiQueryResponse resp = responseWithSeries(s);
        AnomalyAugmenter.augment(resp, new ZScoreAnomalyDetector(), 3.0, "[Time].[Month]");

        AnomalyPoint hit = salesCell(resp, 5).getAnomaly();
        assertNotNull("spike cell must carry an anomaly verdict", hit);
        assertTrue(hit.isAnomaly());
        assertEquals("above", hit.getDirection());

        // No other cell should be flagged.
        for (int i = 0; i < s.length; i++) {
            if (i == 5) continue;
            assertNull("row " + i + " should not be flagged", salesCell(resp, i).getAnomaly());
        }
        assertEquals(1, AnomalyAugmenter.countAnomalies(resp));
    }

    @Test
    public void flatSeriesAttachesNothingAndCountsZero() {
        double[] s = {5, 5, 5, 5, 5, 5};
        AiQueryResponse resp = responseWithSeries(s);
        AnomalyAugmenter.augment(resp, new ZScoreAnomalyDetector(), 3.0, "[Time].[Month]");
        for (int i = 0; i < s.length; i++) {
            assertNull(salesCell(resp, i).getAnomaly());
        }
        // The "no anomalies => explicit empty/zero, not a missing field" rule:
        // count is a real 0, and the cells simply have no anomaly object.
        assertEquals(0, AnomalyAugmenter.countAnomalies(resp));
    }

    @Test
    public void emptyResultIsNotAnError() {
        AiQueryResponse resp = responseWithSeries(new double[0]);
        AnomalyAugmenter.augment(resp, new ZScoreAnomalyDetector(), 3.0, "[Time].[Month]");
        assertEquals(0, AnomalyAugmenter.countAnomalies(resp));
    }

    @Test
    public void responseWithNoMeasureColumnsRaisesValidationError() {
        AiQueryResponse resp = new AiQueryResponse();
        resp.setFormat("records");
        AiQueryMetadata meta = new AiQueryMetadata();
        meta.setColumns(new ArrayList<>()); // no measure columns
        resp.setMetadata(meta);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Month", "M0");
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(row);
        resp.setData(data);

        try {
            AnomalyAugmenter.augment(resp, new ZScoreAnomalyDetector(), 3.0, "[Time].[Month]");
            fail("expected AiValidationException");
        } catch (AiValidationException e) {
            assertEquals("timeAxis", e.getField());
        }
    }
}
