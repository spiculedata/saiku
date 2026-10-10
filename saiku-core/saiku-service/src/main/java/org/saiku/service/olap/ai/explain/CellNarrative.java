/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The deterministic narrative — what the panel shows when no LLM provider is configured, and what
 * it falls back to when the provider is configured but fails (the issue's "LLM unavailable → falls
 * back to Phase 2 output").
 *
 * <p>It states only what {@link CellDrivers} computed, and every sentence carries the number it is
 * making a claim about. That is what makes it a usable fallback rather than a placeholder: the LLM
 * path is an improvement in phrasing, never the only source of the figures.
 */
public final class CellNarrative {

    private CellNarrative() {}

    /**
     * One or two paragraphs of plain text describing the cell and its drivers.
     *
     * @param formatted the cellset's own rendering of the value, e.g. {@code $26,507.17}
     */
    public static String describe(
            CellMember measure,
            List<CellMember> rowHeader,
            List<CellMember> columnHeader,
            double value,
            String formatted,
            List<ExplainDriver> drivers) {
        String subject = subject(measure, rowHeader, columnHeader);
        String shown = formatted == null || formatted.isBlank() ? format(value) : formatted;
        StringBuilder out = new StringBuilder();

        out.append(subject).append(" is ").append(shown).append('.');
        for (ExplainDriver driver : drivers) {
            String sentence = sentence(driver, value);
            if (sentence != null) {
                out.append(' ').append(sentence);
            }
        }
        return out.toString();
    }

    /**
     * The same facts as a bullet list, for the LLM prompt. The model is given this instead of the
     * raw cellset so every figure in the narrative is one the server computed and the panel can
     * show alongside it.
     */
    public static String facts(
            CellMember measure, String formatted, String rowPath, String columnPath, List<ExplainDriver> drivers) {
        StringBuilder out = new StringBuilder();
        out.append("- measure: ")
                .append(measure == null ? "(none on this axis)" : measure.caption())
                .append('\n');
        out.append("- row: ")
                .append(rowPath == null || rowPath.isBlank() ? "(grid total)" : rowPath)
                .append('\n');
        out.append("- column: ")
                .append(columnPath == null || columnPath.isBlank() ? "(grid total)" : columnPath)
                .append('\n');
        out.append("- value: ")
                .append(formatted == null || formatted.isBlank() ? "?" : formatted)
                .append('\n');
        for (ExplainDriver driver : drivers) {
            out.append("- ")
                    .append(driver.kind())
                    .append(": ")
                    .append(fact(driver))
                    .append('\n');
        }
        return out.toString();
    }

    private static String fact(ExplainDriver driver) {
        switch (driver.kind()) {
            case SHARE_OF_COLUMN:
            case SHARE_OF_ROW:
                return caption(driver) + " = " + percent(driver.share()) + " (" + driver.detail() + ")";
            case RANK_IN_COLUMN:
                return caption(driver) + " ranks " + driver.detail();
            case PREVIOUS_COLUMN:
                return driver.detail() + " " + caption(driver) + ": " + format(driver.delta()) + " ("
                        + signedPercent(driver.deltaPct()) + ")";
            case ROW_PEAK:
                return "largest in this row is " + caption(driver) + " at " + format(driver.value()) + " ("
                        + driver.detail() + ")";
            default:
                return caption(driver) + " — " + driver.detail();
        }
    }

    private static String sentence(ExplainDriver driver, double value) {
        switch (driver.kind()) {
            case SHARE_OF_COLUMN:
                return "That is " + percent(driver.share()) + " of the column total.";
            case SHARE_OF_ROW:
                return "It is " + percent(driver.share()) + " of the row total.";
            case RANK_IN_COLUMN:
                return "It ranks " + driver.detail() + ".";
            case PREVIOUS_COLUMN:
                return "It is " + format(driver.delta()) + " (" + signedPercent(driver.deltaPct()) + ") "
                        + (driver.delta() >= 0d ? "above" : "below") + " " + caption(driver) + " in the same row.";
            case ROW_PEAK:
                return "The largest cell in the same row is " + caption(driver) + " at " + format(driver.value()) + ".";
            default:
                return null;
        }
    }

    private static String subject(CellMember measure, List<CellMember> rowHeader, List<CellMember> columnHeader) {
        String rows = path(rowHeader);
        String columns = path(columnHeader, true);
        String what = measure == null ? "The cell" : measure.caption();
        if (columns == null) {
            return what + " for " + (rows == null ? "the grid" : rows);
        }
        if (rows == null) {
            return what + " for " + columns;
        }
        return what + " for " + rows + " / " + columns;
    }

    /** {@code USA / CA / Altadena}, or {@code null} for an empty band. */
    private static String path(List<CellMember> header) {
        return path(header, false);
    }

    private static String path(List<CellMember> header, boolean dropMeasure) {
        List<String> parts = new ArrayList<>();
        for (CellMember member : header) {
            if (dropMeasure && member.measure()) {
                continue;
            }
            parts.add(member.caption());
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private static String caption(ExplainDriver driver) {
        return driver.caption() == null || driver.caption().isBlank() ? "the other cell" : driver.caption();
    }

    private static String percent(Double share) {
        return share == null ? "an unknown share" : format(share * 100d) + "%";
    }

    private static String signedPercent(Double share) {
        if (share == null) {
            return "n/a";
        }
        return (share >= 0d ? "+" : "") + format(share * 100d) + "%";
    }

    /** Two decimals, thousands separators, {@code -0.00} normalised to {@code 0.00}. */
    private static String format(Double value) {
        if (value == null) {
            return "n/a";
        }
        double v = value;
        if (v == 0d) {
            v = 0d;
        }
        String formatted = String.format(Locale.ROOT, "%,.2f", v);
        return "-0.00".equals(formatted) ? "0.00" : formatted;
    }
}
