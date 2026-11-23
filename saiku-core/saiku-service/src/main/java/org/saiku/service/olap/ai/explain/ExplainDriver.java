/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

/**
 * One structured finding about the explained cell — a dimension member the number is attributable
 * to, or a comparison the number makes.
 *
 * <p>Drivers are the whole point of Phase 2 and the input the Phase-3 narrative is grounded on:
 * the LLM is handed this list, not the raw cellset, so every sentence it writes is checkable
 * against a number that came out of Mondrian.
 *
 * <p>Only the fields a kind uses are populated — {@code value} for a contribution, {@code delta} /
 * {@code deltaPct} for a comparison, {@code share} for either.
 */
public record ExplainDriver(
        Kind kind,
        String member,
        String caption,
        String detail,
        Double value,
        Double share,
        Double delta,
        Double deltaPct) {

    public enum Kind {
        /** How much of the column total this cell is. */
        SHARE_OF_COLUMN,
        /** How much of the row total this cell is. */
        SHARE_OF_ROW,
        /** Where this cell sits among the data rows of its column (1 = highest). */
        RANK_IN_COLUMN,
        /**
         * Change against the neighbouring column in the same row, when the two columns sit at the
         * same depth under a shared parent (e.g. two quarters of the same year). This is the
         * period-over-period comparison the issue asks for, computed from the cellset the user is
         * already looking at — no extra query, and it degrades to "no driver" on a ragged axis.
         */
        PREVIOUS_COLUMN,
        /** Largest single member in the same row, as a "compare with" pointer. */
        ROW_PEAK
    }

    public ExplainDriver {
        kind = kind == null ? Kind.SHARE_OF_COLUMN : kind;
        caption = caption == null ? "" : caption;
        detail = detail == null ? "" : detail;
    }

    public static ExplainDriver share(Kind kind, CellMember member, double share, double value) {
        return new ExplainDriver(
                kind,
                member == null ? null : member.uniqueName(),
                member == null ? "" : member.caption(),
                "",
                value,
                share,
                null,
                null);
    }
}
