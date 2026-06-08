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
        assertFalse(
                "Z-score is fooled by the training outlier and misses #9",
                z.get(9).isAnomaly());
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

    @Test
    public void belowMedianSpikeIsFlaggedAsBelow() {
        // Symmetry with the ZScore suite: a downward spike must report direction "below".
        double[] series = {50, 51, 49, 50, 50, -400, 51, 49, 50, 50};
        List<AnomalyPoint> out = det.detect(series, 3.5);
        AnomalyPoint dip = out.get(5);
        assertTrue(dip.isAnomaly());
        assertEquals("below", dip.getDirection());
    }

    @Test
    public void nanGapsAreFilteredFromStatsAndNeverFlagged() {
        // NaN must be excluded from the median/MAD stats (the .filter) yet keep its output slot
        // as a non-anomalous zero-score point — the ZScore suite locks this; MAD didn't. Remove
        // the NaN filter and the median skews (Java sorts NaN last) → this goes RED.
        // (Series is varied enough that MAD stays > 0 after the NaN is dropped — see the MAD=0
        // limitation test below for the degenerate majority-identical case.)
        double[] series = {10, Double.NaN, 12, 9, 11, 100, 8, 13, 10, 9};
        List<AnomalyPoint> out = det.detect(series, 3.5);
        assertEquals(series.length, out.size());
        AnomalyPoint gap = out.get(1);
        assertFalse(gap.isAnomaly());
        assertNull(gap.getDirection());
        assertEquals(0.0, gap.getScore(), 1e-9);
        assertTrue(
                "the real spike must still be flagged with NaN present",
                out.get(5).isAnomaly());
    }

    @Test
    public void madZeroFromMajorityIdenticalValuesYieldsNoAnomalies() {
        // Known MAD limitation (characterization): when MORE than half the points equal the
        // median, the median absolute deviation is 0, so even a huge spike is NOT flagged — the
        // detector deliberately emits zero-score non-anomalies instead of dividing by zero.
        // Pinned here so this surprising-but-intentional behaviour can't change silently.
        double[] series = {10, 10, 10, 10, 10, 1000, 10, 10, 10, 10}; // 9 of 10 identical => MAD 0
        List<AnomalyPoint> out = det.detect(series, 3.5);
        for (AnomalyPoint p : out) {
            assertFalse("MAD=0 (majority-identical) flags nothing, even the 1000 spike", p.isAnomaly());
            assertEquals(0.0, p.getScore(), 1e-9);
        }
    }

    @Test
    public void thresholdComparisonIsStrictlyGreater() {
        // Lock `score > threshold` (NOT >=), same as the ZScore guard: at exactly the spike's
        // score it must not flag; one ULP below flags; one ULP above does not.
        double[] series = {10, 11, 9, 10, 10, 100, 11, 9, 10, 10};
        double spikeScore = det.detect(series, 3.5).get(5).getScore();
        assertTrue("sanity: spike has a real positive score", spikeScore > 0);
        assertFalse(
                "at exactly the score, strict > must not flag",
                det.detect(series, spikeScore).get(5).isAnomaly());
        assertTrue(
                "one ULP below the score must flag",
                det.detect(series, Math.nextDown(spikeScore)).get(5).isAnomaly());
        assertFalse(
                "one ULP above the score must not flag",
                det.detect(series, Math.nextUp(spikeScore)).get(5).isAnomaly());
    }
}
