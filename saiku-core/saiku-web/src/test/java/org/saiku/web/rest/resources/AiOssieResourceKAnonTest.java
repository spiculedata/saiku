/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.KAnonymityFilter;
import org.saiku.service.ossie.ai.OssieAiQueryResponse;

/**
 * saiku#1482 — call-site tests for the Ossie R2 k-anonymity path
 * ({@link AiOssieResource#applyKAnonymity}). The shared {@link KAnonymityFilter} is unit-tested
 * elsewhere; these pin the Ossie-specific wiring: count-shaped-metric detection via
 * {@code aggregationKind=count}, masking of metric cells on sub-k rows, dimension cells left
 * intact, and the {@code meta.suppressed} block (count + reason) that agents read to know values
 * were withheld — the piece #1482 called out as a placeholder.
 */
public class AiOssieResourceKAnonTest {

    private static OssieAiQueryResponse.Column col(String key, String type, String aggregationKind) {
        OssieAiQueryResponse.Column c = new OssieAiQueryResponse.Column(key, key, type);
        c.setAggregationKind(aggregationKind);
        return c;
    }

    private static Map<String, Object> row(String region, double count, double revenue) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("region", region);
        r.put("order_count", new OssieAiQueryResponse.CellValue(count, String.valueOf(count), null));
        r.put("revenue", new OssieAiQueryResponse.CellValue(revenue, String.valueOf(revenue), "USD"));
        return r;
    }

    private static OssieAiQueryResponse response(List<Map<String, Object>> records) {
        OssieAiQueryResponse resp = new OssieAiQueryResponse();
        List<OssieAiQueryResponse.Column> cols = new ArrayList<>();
        cols.add(col("region", "dimension", null));
        cols.add(col("order_count", "metric", "count"));
        cols.add(col("revenue", "metric", "sum"));
        resp.setColumns(cols);
        resp.setRecords(records);
        return resp;
    }

    private static AiOssieResource resourceWithK(int k) {
        AiOssieResource r = new AiOssieResource();
        r.setKAnonymityFilter(new KAnonymityFilter(k, null));
        return r;
    }

    @Test
    public void subKRowIsMaskedAndSuppressedMetaPopulated() {
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(row("North", 12, 5000.0)); // >= k, untouched
        records.add(row("South", 3, 812.0)); // < k, masked
        OssieAiQueryResponse resp = response(records);

        resourceWithK(5).applyKAnonymity(resp);

        // Large row untouched.
        OssieAiQueryResponse.CellValue northRev =
                (OssieAiQueryResponse.CellValue) records.get(0).get("revenue");
        assertEquals(5000.0, (Double) northRev.getValue(), 0.0001);

        // Small row: every metric cell masked (the count itself is disclosive too).
        OssieAiQueryResponse.CellValue southRev =
                (OssieAiQueryResponse.CellValue) records.get(1).get("revenue");
        OssieAiQueryResponse.CellValue southCnt =
                (OssieAiQueryResponse.CellValue) records.get(1).get("order_count");
        assertNull("sub-k revenue must not cross the AI boundary", southRev.getValue());
        assertNull("the sub-k count itself is disclosive", southCnt.getValue());

        // Dimension cell (row header) left intact.
        assertEquals("South", records.get(1).get("region"));

        // meta.suppressed — the block #1482 flagged as a placeholder — must be real.
        OssieAiQueryResponse.Suppressed s = resp.getMeta().getSuppressed();
        assertNotNull("meta.suppressed must be populated when rows were masked", s);
        assertEquals(1, s.getCount());
        assertEquals("k-anonymity threshold k=5", s.getReason());
    }

    @Test
    public void noCountShapedMetricIsANoOp() {
        // Only sum-aggregated metrics: semantically there is no backing count, so the
        // response must pass through unchanged (documented fail-open for non-count shelves).
        OssieAiQueryResponse resp = new OssieAiQueryResponse();
        List<OssieAiQueryResponse.Column> cols = new ArrayList<>();
        cols.add(col("region", "dimension", null));
        cols.add(col("revenue", "metric", "sum"));
        resp.setColumns(cols);
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("region", "North");
        r.put("revenue", new OssieAiQueryResponse.CellValue(1.0, "1", null));
        records.add(r);
        resp.setRecords(records);

        resourceWithK(5).applyKAnonymity(resp);

        OssieAiQueryResponse.CellValue rev =
                (OssieAiQueryResponse.CellValue) records.get(0).get("revenue");
        assertEquals(1.0, (Double) rev.getValue(), 0.0001);
        assertNull("no masking -> no suppressed block", resp.getMeta().getSuppressed());
    }

    @Test
    public void disabledFilterIsANoOp() {
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(row("South", 1, 99.0)); // would be masked if enabled
        OssieAiQueryResponse resp = response(records);

        resourceWithK(0).applyKAnonymity(resp); // k=0 => disabled

        OssieAiQueryResponse.CellValue rev =
                (OssieAiQueryResponse.CellValue) records.get(0).get("revenue");
        assertEquals(99.0, (Double) rev.getValue(), 0.0001);
        assertNull(resp.getMeta().getSuppressed());
    }

    @Test
    public void rowAtExactlyKIsNotMasked() {
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(row("East", 5, 700.0)); // count == k: inclusive, stays
        OssieAiQueryResponse resp = response(records);

        resourceWithK(5).applyKAnonymity(resp);

        OssieAiQueryResponse.CellValue rev =
                (OssieAiQueryResponse.CellValue) records.get(0).get("revenue");
        assertEquals(700.0, (Double) rev.getValue(), 0.0001);
        assertNull(resp.getMeta().getSuppressed());
    }
}
