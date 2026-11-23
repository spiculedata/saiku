/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the insight half of a digest (saiku#1119): reads each measure twice — once scoped to the
 * current period, once scoped to the previous one — turns the pair into {@link MeasureDelta}s, and asks
 * the configured {@link DigestNarrator} for the "what changed" bullets.
 *
 * <p><b>One measure, two reads, one filter apart.</b> Both reads go through the same
 * {@link MeasureValueReader} seam the plain #943 digest uses, under the owner identity the scheduler
 * already established, with the measure's static slicers plus a different relative-period slicer. So the
 * two numbers differ only by the period — never by cube, RLS or an unrelated filter.
 *
 * <p><b>A measure that fails does not sink the digest.</b> A read failure is logged and that measure is
 * skipped; the remaining measures still produce a digest. Only if <b>every</b> delta-bearing measure
 * failed does the build fail, and then with {@link DashboardDigestDeliveryException} so the engine
 * records a sanitized failure and applies its usual backoff — the same contract every other digest
 * failure has.
 */
public final class InsightDigestBuilder {

    private static final Logger log = LoggerFactory.getLogger(InsightDigestBuilder.class);

    private final MeasureValueReader valueReader;
    private final DigestNarrator narrator;

    public InsightDigestBuilder(MeasureValueReader valueReader, DigestNarrator narrator) {
        if (valueReader == null || narrator == null) {
            throw new IllegalArgumentException("valueReader and narrator are required");
        }
        this.valueReader = valueReader;
        this.narrator = narrator;
    }

    /** What the handler renders: the headline bullets plus the structured deltas behind them. */
    public record InsightDigest(List<String> bullets, List<MeasureDelta> deltas) {

        public InsightDigest {
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
            deltas = deltas == null ? List.of() : List.copyOf(deltas);
        }
    }

    /**
     * Build the digest for {@code spec}.
     *
     * @throws DashboardDigestDeliveryException when every period-bearing measure failed to read
     */
    public InsightDigest build(DashboardDigestSpec spec) throws DashboardDigestDeliveryException {
        List<MeasureDelta> deltas = new ArrayList<>();
        int failures = 0;
        int attempted = 0;
        for (DashboardDigestSpec.Measure m : spec.getMeasures()) {
            PeriodSpec period = m.getPeriod();
            if (period == null) {
                // No period block — this measure contributes a current value only (the #943 table row).
                continue;
            }
            attempted++;
            try {
                deltas.add(readDelta(m, period));
            } catch (Exception e) {
                failures++;
                // Log the measure label and the failure class only — never the value, never a stack
                // trace with datasource/JDBC detail.
                log.warn(
                        "Digest: measure '{}' could not be read period-over-period ({}); skipping it",
                        m.getLabel(),
                        e.getClass().getSimpleName());
            }
        }

        if (attempted > 0 && deltas.isEmpty()) {
            throw new DashboardDigestDeliveryException(
                    "no digest measure could be read for the current and previous periods (" + failures + " failed)");
        }
        List<String> bullets = narrator.narrate(spec.getDashboardTitle(), deltas, spec.getInsightMaxBullets());
        return new InsightDigest(bullets, deltas);
    }

    /** Read one measure over both periods and difference them. */
    private MeasureDelta readDelta(DashboardDigestSpec.Measure m, PeriodSpec period) throws Exception {
        double current = valueReader.readMeasure(
                m.getCube(), m.getMeasure(), withPeriod(m.getFilters(), period.currentFilter()));
        double previous = valueReader.readMeasure(
                m.getCube(), m.getMeasure(), withPeriod(m.getFilters(), period.previousFilter()));
        return new MeasureDelta(m.getCube(), m.getLabel(), current, previous);
    }

    /**
     * The measure's static slicers with {@code periodFilter} substituted in — dropping any filter the
     * period block supersedes, so the two filters never collide on one hierarchy (which the converter
     * rejects outright).
     */
    static List<AiFilterSelection> withPeriod(List<AiFilterSelection> filters, AiFilterSelection periodFilter) {
        List<AiFilterSelection> out = new ArrayList<>();
        if (filters != null) {
            for (AiFilterSelection f : filters) {
                if (f == null) {
                    continue;
                }
                String hierarchy = f.getHierarchy();
                boolean collides = hierarchy != null
                        && hierarchy.equals(periodFilter.getHierarchy())
                        && hierarchy.equals(periodFilter.getDimension());
                if (!collides) {
                    out.add(f);
                }
            }
        }
        out.add(periodFilter);
        return out;
    }
}
