/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic, no-LLM narrator (saiku#1119 phase 1, and the universal fallback).
 *
 * <p>Ranks the deltas by how much they moved and states the movement in plain words. There is no model
 * call, so it works on an instance with no LLM configured, under an egress policy that forbids sending
 * aggregates anywhere, and on a night when the provider is down — a digest always gets written.
 *
 * <p>Ranking is by <b>relative</b> change where a baseline exists, because "Sales +9%" is the thing a
 * reader acts on and "Row Count +9,000" is not. Measures with no baseline (previous = 0) rank by their
 * absolute change instead, so a brand-new metric still surfaces instead of being dropped as "undefined".
 * Ties break on the label so two runs over the same data produce byte-identical bullets.
 */
public final class TemplateDigestNarrator implements DigestNarrator {

    @Override
    public List<String> narrate(String dashboardTitle, List<MeasureDelta> deltas, int maxBullets) {
        int cap = Math.max(1, maxBullets);
        if (deltas == null || deltas.isEmpty()) {
            return List.of();
        }
        List<MeasureDelta> ranked = new ArrayList<>(deltas);
        ranked.sort(Comparator.comparingDouble(TemplateDigestNarrator::magnitude)
                .reversed()
                .thenComparing(MeasureDelta::label));
        List<String> bullets = new ArrayList<>();
        for (MeasureDelta d : ranked) {
            if (bullets.size() >= cap) {
                break;
            }
            bullets.add(bullet(d));
        }
        return List.copyOf(bullets);
    }

    /**
     * Ranking key: the relative change where there is one, else the absolute change normalised so the
     * two kinds remain comparable in magnitude. A previous value of 0 has no percentage, so it is
     * treated as "a whole number's worth of new activity" rather than being ranked last.
     */
    private static double magnitude(MeasureDelta d) {
        Double pct = d.percentChange();
        return pct != null ? Math.abs(pct) : Math.abs(d.absoluteChange());
    }

    /** One bullet: what moved, by how much, and the two numbers behind the claim. */
    static String bullet(MeasureDelta d) {
        String label = d.label().isEmpty() ? "Measure" : d.label();
        return switch (d.direction()) {
            case FLAT -> label + " was unchanged at " + d.formattedCurrent() + ".";
            case UP -> label + " rose " + d.formattedPercent() + " to " + d.formattedCurrent() + " (was "
                    + d.formattedPrevious() + ").";
            case DOWN -> label + " fell " + d.formattedPercent() + " to " + d.formattedCurrent() + " (was "
                    + d.formattedPrevious() + ").";
        };
    }
}
