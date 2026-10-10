/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * {@link CellNarrative} — the deterministic text. It is the panel's content when no LLM is
 * configured and the fallback when one fails, so what it is allowed to claim matters more here
 * than anywhere else in the feature.
 */
public class CellNarrativeTest {

    private static final CellMember SALES = CellMember.measure("[Measures].[Store Sales]", "Store Sales");
    private static final CellMember USA = CellMember.of("[Customers].[USA]", "USA");
    private static final CellMember Q1 = CellMember.of("[Time].[1997].[Q1]", "Q1");

    @Test
    public void statesTheValueAndEveryDriverItWasGiven() {
        List<ExplainDriver> drivers = List.of(
                new ExplainDriver(
                        ExplainDriver.Kind.SHARE_OF_COLUMN,
                        "[Customers].[Canada]",
                        "Canada",
                        "share of the column total",
                        300d,
                        0.5d,
                        null,
                        null),
                new ExplainDriver(
                        ExplainDriver.Kind.RANK_IN_COLUMN,
                        "[Customers].[Canada]",
                        "Canada",
                        "2 of 3 rows with a value in this column",
                        300d,
                        null,
                        null,
                        null),
                new ExplainDriver(
                        ExplainDriver.Kind.PREVIOUS_COLUMN,
                        "[Time].[1997].[Q1]",
                        "Q1",
                        "change against the previous column of the same row",
                        300d,
                        null,
                        60d,
                        0.25d));

        String text = CellNarrative.describe(SALES, List.of(USA), List.of(Q1, SALES), 300d, "$300.00", drivers);

        assertEquals(
                "Store Sales for USA / Q1 is $300.00. That is 50.00% of the column total. "
                        + "It ranks 2 of 3 rows with a value in this column. "
                        + "It is 60.00 (+25.00%) above Q1 in the same row.",
                text);
    }

    @Test
    public void describesADirectionlessCellWithoutInventingDrivers() {
        String text = CellNarrative.describe(SALES, List.of(USA), List.of(SALES), 1234.5d, null, List.of());

        assertTrue(text, text.startsWith("Store Sales for USA is 1,234.50."));
    }

    @Test
    public void namesTheGridWhenThereIsNoCoordinate() {
        assertEquals(
                "The cell for the grid is 5.00.",
                CellNarrative.describe(null, List.of(), List.of(), 5d, null, List.of()));
    }

    @Test
    public void aDownwardMoveReadsAsBelow() {
        String text = CellNarrative.describe(
                SALES,
                List.of(USA),
                List.of(Q1, SALES),
                80d,
                null,
                List.of(new ExplainDriver(
                        ExplainDriver.Kind.PREVIOUS_COLUMN, "[Time].[1997].[Q1]", "Q1", "", 80d, null, -20d, -0.2d)));

        assertTrue(text, text.contains("below Q1 in the same row"));
    }

    @Test
    public void aZeroPreviousColumnDoesNotInventAPercentage() {
        String text = CellNarrative.describe(
                SALES,
                List.of(USA),
                List.of(Q1, SALES),
                80d,
                null,
                List.of(new ExplainDriver(
                        ExplainDriver.Kind.PREVIOUS_COLUMN, "[Time].[1997].[Q1]", "Q1", "", 80d, null, 80d, null)));

        assertTrue(text, text.contains("(n/a) above Q1"));
    }

    /** The digest is what the LLM is given instead of the cellset, so every figure must be there. */
    @Test
    public void factsListCarriesTheFiguresTheNarrativeMayUse() {
        String facts = CellNarrative.facts(
                SALES,
                "$300.00",
                "USA",
                "Q1",
                List.of(new ExplainDriver(
                        ExplainDriver.Kind.SHARE_OF_COLUMN,
                        "[Customers].[Canada]",
                        "Canada",
                        "share of the column total",
                        300d,
                        0.5d,
                        null,
                        null)));

        assertTrue(facts, facts.contains("- measure: Store Sales"));
        assertTrue(facts, facts.contains("- value: $300.00"));
        assertTrue(facts, facts.contains("SHARE_OF_COLUMN: Canada = 50.00% (share of the column total)"));
    }

    @Test
    public void factsListSaysGridTotalRatherThanLeavingABlank() {
        String facts = CellNarrative.facts(SALES, null, null, null, List.of());
        assertTrue(facts, facts.contains("- row: (grid total)"));
        assertTrue(facts, facts.contains("- column: (grid total)"));
        assertTrue(facts, facts.contains("- value: ?"));
    }
}
