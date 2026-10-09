/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The deterministic, no-LLM narrator (saiku#1119 phase 1 / the universal fallback). */
public class TemplateDigestNarratorTest {

    private final TemplateDigestNarrator narrator = new TemplateDigestNarrator();

    private static MeasureDelta delta(String label, double current, double previous) {
        return new MeasureDelta(null, label, current, previous);
    }

    @Test
    public void statesMovementWithBothNumbers() {
        List<String> bullets = narrator.narrate("Exec", List.of(delta("Total Units", 1200d, 1000d)), 3);
        assertEquals(1, bullets.size());
        assertEquals("Total Units rose +20.0% to 1,200 (was 1,000).", bullets.get(0));
    }

    @Test
    public void statesADrop() {
        List<String> bullets = narrator.narrate(null, List.of(delta("Total Units", 800d, 1000d)), 3);
        assertEquals("Total Units fell -20.0% to 800 (was 1,000).", bullets.get(0));
    }

    @Test
    public void statesAnUnchangedMeasure() {
        List<String> bullets = narrator.narrate(null, List.of(delta("Total Units", 1000d, 1000d)), 3);
        assertEquals("Total Units was unchanged at 1,000.", bullets.get(0));
    }

    @Test
    public void ranksByRelativeChangeSoTheBiggestMoveLeads() {
        List<MeasureDelta> deltas =
                List.of(delta("Units", 1200d, 1000d), delta("Margin", 110d, 100d), delta("Flat", 5d, 5d));
        List<String> bullets = narrator.narrate(null, deltas, 3);
        assertEquals(3, bullets.size());
        assertTrue(bullets.get(0).startsWith("Units"));
        assertTrue(bullets.get(1).startsWith("Margin"));
        assertTrue(bullets.get(2).startsWith("Flat"));
    }

    @Test
    public void aBigAbsoluteMoveOnNoBaselineStillRanksAndSaysSo() {
        List<MeasureDelta> deltas = List.of(delta("New Stores", 900d, 0d), delta("Units", 1010d, 1000d));
        List<String> bullets = narrator.narrate(null, deltas, 2);
        // 900 new rows outranks a 1% move, and the bullet never prints a percentage from zero.
        assertTrue(bullets.get(0).contains("New Stores"));
        assertTrue(bullets.get(0).contains("(was 0)"));
    }

    @Test
    public void honoursTheBulletCap() {
        List<MeasureDelta> deltas =
                List.of(delta("A", 200d, 100d), delta("B", 150d, 100d), delta("C", 140d, 100d), delta("D", 130d, 100d));
        assertEquals(2, narrator.narrate(null, deltas, 2).size());
    }

    @Test
    public void aCapBelowOneIsReadAsOne() {
        List<MeasureDelta> deltas = List.of(delta("A", 200d, 100d), delta("B", 150d, 100d));
        assertEquals(1, narrator.narrate(null, deltas, 0).size());
    }

    @Test
    public void noDeltasMeansNoBullets() {
        assertTrue(narrator.narrate(null, List.of(), 3).isEmpty());
        assertTrue(narrator.narrate(null, null, 3).isEmpty());
    }

    @Test
    public void outputIsStableForTheSameInput() {
        List<MeasureDelta> deltas = List.of(delta("B", 150d, 100d), delta("A", 200d, 100d));
        assertEquals(narrator.narrate(null, deltas, 3), narrator.narrate(null, deltas, 3));
    }
}
