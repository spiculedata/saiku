/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.anomaly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** saiku#907 — MAD (median absolute deviation) anomaly detector. */
public class MadAnomalyDetectorTest {

    private final MadAnomalyDetector det = new MadAnomalyDetector();

    @Test
    public void flagsAKnownSpike() {
        double[] series = {10, 11, 9, 10, 10, 100, 11, 9, 10, 10};
        List<AnomalyPoint> out = det.detect(series, 3.5);
        assertEquals(series.length, out.size());
        AnomalyPoint spike = out.get(5);
        assertTrue(spike.isAnomaly());
        assertEquals("above", spike.getDirection());
    }

    @Test
    public void robustToASingleTrainingOutlier() {
        // Z-score's mean/stddev gets dragged toward a single huge training
        // outlier, which can MASK a genuine second anomaly. MAD's median-based
        // centre/spread barely moves, so the second spike is still caught.
        double[] series = {10, 12, 9, 11, 8, 13, 1000, 10, 9, 55, 11, 12, 8, 10, 9};
        // index 6 = the dominant training outlier, index 9 = a smaller spike.

        List<AnomalyPoint> mad = det.detect(series, 3.5);
        assertTrue("MAD must still flag the dominant outlier", mad.get(6).isAnomaly());
        assertTrue("MAD must flag the second, smaller spike too", mad.get(9).isAnomaly());

        // Contrast: Z-score's spread is inflated by the 1000 outlier and misses #9.
        List<AnomalyPoint> z = new ZScoreAnomalyDetector().detect(series, 3.5);
        assertFalse("Z-score is fooled by the training outlier and misses #9", z.get(9).isAnomaly());
    }

    @Test
    public void flatSeriesYieldsNoAnomalies() {
        double[] series = {7, 7, 7, 7, 7};
        List<AnomalyPoint> out = det.detect(series, 3.5);
        for (AnomalyPoint p : out) {
            assertFalse(p.isAnomaly());
            assertEquals(0.0, p.getScore(), 1e-9);
            assertNull(p.getDirection());
            assertEquals(7.0, p.getExpected(), 1e-9);
        }
    }

    @Test
    public void medianHelperHandlesEvenAndOddLengths() {
        assertEquals(2.0, MadAnomalyDetector.median(new double[] {1, 2, 3}), 1e-9);
        assertEquals(2.5, MadAnomalyDetector.median(new double[] {1, 2, 3, 4}), 1e-9);
        assertEquals(0.0, MadAnomalyDetector.median(new double[0]), 1e-9);
    }

    @Test
    public void defaultsAndMethodId() {
        assertEquals(3.5, det.defaultThreshold(), 1e-9);
        assertEquals("mad", det.method());
    }
}
