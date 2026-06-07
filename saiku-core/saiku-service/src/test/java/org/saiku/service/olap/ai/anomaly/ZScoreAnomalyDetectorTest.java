/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** saiku#907 — Z-score anomaly detector. */
public class ZScoreAnomalyDetectorTest {

    private final ZScoreAnomalyDetector det = new ZScoreAnomalyDetector();

    @Test
    public void flagsAKnownSpike() {
        // A roughly stationary series around 10 with one big spike at index 5.
        // (A 20-point baseline keeps the single outlier's z-score clear of the
        // sqrt(N-1) ceiling that caps a lone spike in a tiny population.)
        double[] series = {10, 11, 9, 10, 10, 100, 11, 9, 10, 10, 9, 11, 10, 10, 9, 11, 10, 9, 11, 10};
        List<AnomalyPoint> out = det.detect(series, 3.0);
        assertEquals(series.length, out.size());

        AnomalyPoint spike = out.get(5);
        assertTrue("spike should be flagged", spike.isAnomaly());
        assertEquals("above", spike.getDirection());
        assertTrue("spike score should exceed threshold", spike.getScore() > 3.0);

        // Every other point should be clean.
        for (int i = 0; i < out.size(); i++) {
            if (i == 5) continue;
            assertFalse("index " + i + " should not be flagged", out.get(i).isAnomaly());
        }
    }

    @Test
    public void flatSeriesYieldsNoAnomaliesAndEmptyDirection() {
        double[] series = {5, 5, 5, 5, 5, 5};
        List<AnomalyPoint> out = det.detect(series, 3.0);
        assertEquals(series.length, out.size());
        for (AnomalyPoint p : out) {
            assertFalse(p.isAnomaly());
            assertEquals(0.0, p.getScore(), 1e-9);
            assertNull(p.getDirection());
            assertEquals(5.0, p.getExpected(), 1e-9);
        }
    }

    @Test
    public void belowMeanSpikeIsFlaggedAsBelow() {
        double[] series = {50, 51, 49, 50, 50, -400, 51, 49, 50, 50, 49, 51, 50, 50, 49, 51, 50, 49, 51, 50};
        List<AnomalyPoint> out = det.detect(series, 3.0);
        AnomalyPoint dip = out.get(5);
        assertTrue(dip.isAnomaly());
        assertEquals("below", dip.getDirection());
    }

    @Test
    public void emptyAndSinglePointSeriesAreSafe() {
        assertTrue(det.detect(new double[0], 3.0).isEmpty());
        List<AnomalyPoint> one = det.detect(new double[] {42}, 3.0);
        assertEquals(1, one.size());
        assertFalse(one.get(0).isAnomaly());
    }

    @Test
    public void nanGapsArePositionallyPreservedAndNeverFlagged() {
        double[] series = {10, Double.NaN, 9, 10, 11, 100, 9, 11, 10, 10, 9, 11, 10, 10, 9, 11, 10, 9, 11, 10};
        List<AnomalyPoint> out = det.detect(series, 3.0);
        assertEquals(series.length, out.size());
        AnomalyPoint gap = out.get(1);
        assertNotNull(gap);
        assertFalse(gap.isAnomaly());
        assertTrue("the spike at index 5 should still be flagged", out.get(5).isAnomaly());
    }

    @Test
    public void defaultThresholdIsThreeSigma() {
        assertEquals(3.0, det.defaultThreshold(), 1e-9);
        assertEquals("zscore", det.method());
    }
}
