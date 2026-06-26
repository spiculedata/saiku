/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constants for the {@code emit_view_change} tool's input schema — the catalogue of view modes and
 * chart types the LLM is allowed to pick from. Kept in sync with the SvelteKit UI's
 * {@code chartTypes.ts} ({@code CHART_TYPES} array).
 *
 * <p>When the UI grows a new chart type, add it here too. The drift-detection lives in
 * {@code AiViewChangeCatalogParityTest} (UI source-of-truth → server enum match) — if you add a
 * chart only here, the parity test still passes; the LLM just won't know to emit the new id. If you
 * add it only on the UI side, the model can never reach it.
 */
public final class AiViewChangeCatalog {

    private AiViewChangeCatalog() {}

    public static final List<String> VIEW_MODES = List.of("grid", "chart");

    /**
     * Catalogue with one entry per supported chart type. {@code group} is the grouping label the UI
     * shows; {@code hint} is a one-line guide for the model on when this chart fits — fed into the
     * tool description so the LLM picks sensibly without us having to coach it per question.
     */
    public static final List<Map<String, String>> CHART_TYPES = List.of(
            Map.of(
                    "id",
                    "bar",
                    "label",
                    "Bar",
                    "group",
                    "Bars",
                    "hint",
                    "1 measure × N categorical dims; quick comparisons across rows."),
            Map.of(
                    "id",
                    "stackedBar",
                    "label",
                    "Stacked bar",
                    "group",
                    "Bars",
                    "hint",
                    "1 measure × N dims with a secondary categorical breakdown; part-to-whole per category."),
            Map.of(
                    "id",
                    "waterfall",
                    "label",
                    "Waterfall",
                    "group",
                    "Bars",
                    "hint",
                    "Sequential +/- contributions to a running total (P&L, variance bridges)."),
            Map.of(
                    "id",
                    "line",
                    "label",
                    "Line",
                    "group",
                    "Lines",
                    "hint",
                    "1+ measures over an ordered/time dimension; default for time series."),
            Map.of(
                    "id",
                    "stackedLine",
                    "label",
                    "Stacked line",
                    "group",
                    "Lines",
                    "hint",
                    "Multiple series over time with cumulative stacking."),
            Map.of(
                    "id",
                    "area",
                    "label",
                    "Area",
                    "group",
                    "Lines",
                    "hint",
                    "Single series over time emphasising magnitude."),
            Map.of(
                    "id",
                    "stackedArea",
                    "label",
                    "Stacked area",
                    "group",
                    "Lines",
                    "hint",
                    "Multiple series over time with cumulative magnitude (composition)."),
            Map.of(
                    "id",
                    "pie",
                    "label",
                    "Pie",
                    "group",
                    "Proportional",
                    "hint",
                    "Single measure split across <=6 categories; part-to-whole at one point in time."),
            Map.of(
                    "id",
                    "donut",
                    "label",
                    "Donut",
                    "group",
                    "Proportional",
                    "hint",
                    "Same as pie but with center space for a KPI overlay."),
            Map.of(
                    "id",
                    "treemap",
                    "label",
                    "Treemap",
                    "group",
                    "Proportional",
                    "hint",
                    "Hierarchical part-to-whole with many leaf categories (size-encoded)."),
            Map.of(
                    "id",
                    "sunburst",
                    "label",
                    "Sunburst",
                    "group",
                    "Proportional",
                    "hint",
                    "Hierarchical part-to-whole rendered as concentric rings."),
            Map.of(
                    "id",
                    "heatmap",
                    "label",
                    "Heatmap",
                    "group",
                    "Matrix",
                    "hint",
                    "2 categorical dims × 1 measure; spot hotspots in a matrix."),
            Map.of(
                    "id",
                    "radar",
                    "label",
                    "Radar",
                    "group",
                    "Matrix",
                    "hint",
                    "Multiple measures profiled across one categorical axis (KPI radar)."),
            Map.of(
                    "id",
                    "scatter",
                    "label",
                    "Scatter",
                    "group",
                    "Points",
                    "hint",
                    "2 measures plotted as x/y points; correlation / clustering."),
            Map.of(
                    "id",
                    "bubble",
                    "label",
                    "Bubble",
                    "group",
                    "Points",
                    "hint",
                    "3 measures (x, y, size); like scatter with a magnitude dimension."),
            Map.of(
                    "id",
                    "map",
                    "label",
                    "Map (choropleth)",
                    "group",
                    "Geo",
                    "hint",
                    "1 measure × geographic dim (country/state/postcode); regional comparison."));

    /** Fast contains-check used by the routing layer to reject hallucinated ids. */
    public static final Set<String> CHART_TYPE_IDS =
            Set.copyOf(CHART_TYPES.stream().map(c -> c.get("id")).toList());
}
