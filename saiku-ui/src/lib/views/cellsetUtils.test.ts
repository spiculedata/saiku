import { describe, expect, it } from "vitest";
import { deriveLeafRows, parseCellset } from "./cellsetUtils";
import type { CellEntry, QueryResult } from "$lib/api/query";

function header(value: string): CellEntry {
  return { type: "COLUMN_HEADER", value };
}
function rowHeader(value: string): CellEntry {
  return { type: "ROW_HEADER", value };
}
function dataCell(value: string): CellEntry {
  return { type: "DATA_CELL", value, properties: { raw: value } };
}
function emptyHeader(): CellEntry {
  return { type: "ROW_HEADER_HEADER", value: "" };
}

function buildResult(cells: CellEntry[][]): QueryResult {
  return { cellset: cells } as QueryResult;
}

describe("deriveLeafRows", () => {
  it("treats every row as a leaf when there is only one row-header level", () => {
    const result = buildResult([
      [emptyHeader(), header("M1")],
      [rowHeader("A"), dataCell("10")],
      [rowHeader("B"), dataCell("20")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    expect(leaf.indices).toEqual([0, 1]);
    expect(leaf.labels).toEqual(["A", "B"]);
  });

  it("drops year rollup rows and keeps quarter leaves with parent context", () => {
    // Two row-header columns: Year, Quarter. Mondrian-style dedup means
    // the Year column is blank on quarter rows that share their parent.
    const result = buildResult([
      [emptyHeader(), emptyHeader(), header("Sales")],
      [rowHeader("2024"), rowHeader(""), dataCell("99")],
      [rowHeader(""), rowHeader("Q1"), dataCell("21")],
      [rowHeader(""), rowHeader("Q2"), dataCell("33")],
      [rowHeader("2025"), rowHeader(""), dataCell("120")],
      [rowHeader(""), rowHeader("Q1"), dataCell("60")],
      [rowHeader(""), rowHeader("Q2"), dataCell("60")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    // Body row 0 is the 2024 rollup, body row 3 is the 2025 rollup;
    // those should be dropped. The four quarters remain.
    expect(leaf.indices).toEqual([1, 2, 4, 5]);
    expect(leaf.labels).toEqual([
      "2024 / Q1",
      "2024 / Q2",
      "2025 / Q1",
      "2025 / Q2",
    ]);
  });

  it("handles rows that repeat the parent value instead of deduping", () => {
    // Some Mondrian configs ship the parent value on every row instead of
    // eliding it. Our walk should still identify leaves correctly.
    const result = buildResult([
      [emptyHeader(), emptyHeader(), header("Sales")],
      [rowHeader("2024"), rowHeader(""), dataCell("99")],
      [rowHeader("2024"), rowHeader("Q1"), dataCell("21")],
      [rowHeader("2024"), rowHeader("Q2"), dataCell("33")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    expect(leaf.indices).toEqual([1, 2]);
    expect(leaf.labels).toEqual(["2024 / Q1", "2024 / Q2"]);
  });

  it("returns empty arrays when there are no leaf rows", () => {
    // Pathological case: only rollup rows, no quarter detail.
    const result = buildResult([
      [emptyHeader(), emptyHeader(), header("Sales")],
      [rowHeader("2024"), rowHeader(""), dataCell("99")],
      [rowHeader("2025"), rowHeader(""), dataCell("120")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    expect(leaf.indices).toEqual([]);
    expect(leaf.labels).toEqual([]);
  });
});
