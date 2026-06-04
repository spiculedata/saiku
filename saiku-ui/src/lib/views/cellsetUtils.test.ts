import { describe, expect, it } from "vitest";
import { deriveLeafRows, parseCellset } from "./cellsetUtils";
import type { CellEntry, QueryResult } from "$lib/api/query";

function header(value: string): CellEntry {
  return { type: "COLUMN_HEADER", value };
}
function rowHeader(value: string, uniquename?: string): CellEntry {
  const cell: CellEntry = { type: "ROW_HEADER", value };
  if (uniquename) cell.properties = { uniquename };
  return cell;
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

  it("keeps every row when they all sit at the same depth (no rollups present)", () => {
    // No quarter detail — just two year rows. Treating these as "all
    // rollups so drop them all" would produce an empty chart, which is
    // strictly worse UX than just charting the two year totals.
    const result = buildResult([
      [emptyHeader(), emptyHeader(), header("Sales")],
      [rowHeader("2024"), rowHeader(""), dataCell("99")],
      [rowHeader("2025"), rowHeader(""), dataCell("120")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    expect(leaf.indices).toEqual([0, 1]);
    expect(leaf.labels).toEqual(["2024", "2025"]);
  });

  it("detects rollup rows by uniquename depth when the hierarchy uses one row-header column", () => {
    // The Time(2 levels) shape from Mondrian — Year and Month flattened
    // into a single row-header column, distinguished by uniquename depth.
    const result = buildResult([
      [emptyHeader(), header("Store Cost")],
      [rowHeader("1997", "[Time].[Time].[1997]"), dataCell("225627")],
      [rowHeader("1", "[Time].[Time].[1997].[1]"), dataCell("18000")],
      [rowHeader("2", "[Time].[Time].[1997].[2]"), dataCell("17500")],
      [rowHeader("3", "[Time].[Time].[1997].[3]"), dataCell("19500")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    // The 1997 rollup at body row 0 must drop; the three months survive
    // with parent context restored.
    expect(leaf.indices).toEqual([1, 2, 3]);
    expect(leaf.labels).toEqual(["1997 / 1", "1997 / 2", "1997 / 3"]);
  });

  it("handles single-col cellsets across multiple parents", () => {
    const result = buildResult([
      [emptyHeader(), header("Sales")],
      [rowHeader("1997", "[Time].[Time].[1997]"), dataCell("100")],
      [rowHeader("Q1", "[Time].[Time].[1997].[Q1]"), dataCell("40")],
      [rowHeader("Q2", "[Time].[Time].[1997].[Q2]"), dataCell("60")],
      [rowHeader("1998", "[Time].[Time].[1998]"), dataCell("120")],
      [rowHeader("Q1", "[Time].[Time].[1998].[Q1]"), dataCell("50")],
      [rowHeader("Q2", "[Time].[Time].[1998].[Q2]"), dataCell("70")],
    ]);
    const parsed = parseCellset(result);
    const leaf = deriveLeafRows(parsed);
    expect(leaf.indices).toEqual([1, 2, 4, 5]);
    expect(leaf.labels).toEqual([
      "1997 / Q1",
      "1997 / Q2",
      "1998 / Q1",
      "1998 / Q2",
    ]);
  });
});
