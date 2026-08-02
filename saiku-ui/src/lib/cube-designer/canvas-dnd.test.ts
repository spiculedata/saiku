/**
 * Unit tests for the canvas drag-and-drop + connection-handle parsers.
 */
import { describe, it, expect } from "vitest";
import {
  parseConnectionJoin,
  parseDroppedTableCandidates,
  resolveDropOrigin,
} from "./canvas-dnd.js";
import type { SourceTableCandidate } from "./types.js";

describe("parseConnectionJoin", () => {
  it("extracts column names from `<tableId>:<column>:<dir>` handles", () => {
    const parsed = parseConnectionJoin({
      source: "tbl_a",
      target: "tbl_b",
      sourceHandle: "tbl_a:product_id:out-right",
      targetHandle: "tbl_b:product_id:in-left",
    });
    expect(parsed).toEqual({
      sourceTableId: "tbl_a",
      sourceColumnName: "product_id",
      targetTableId: "tbl_b",
      targetColumnName: "product_id",
    });
  });

  it("returns null when a handle is missing", () => {
    expect(
      parseConnectionJoin({
        source: "a",
        target: "b",
        sourceHandle: null,
        targetHandle: "b:x:in",
      }),
    ).toBeNull();
  });

  it("returns null when a handle has no column segment", () => {
    expect(
      parseConnectionJoin({
        source: "a",
        target: "b",
        sourceHandle: "a:",
        targetHandle: "b:x:in",
      }),
    ).toBeNull();
  });
});

describe("parseDroppedTableCandidates", () => {
  const one: SourceTableCandidate = {
    schema: "public",
    name: "sales",
    columns: [{ name: "id", sqlType: "INT" }],
    onCanvas: false,
  };
  const two: SourceTableCandidate = { ...one, name: "product" };

  it("prefers the multi-table array payload", () => {
    const out = parseDroppedTableCandidates(
      JSON.stringify([one, two]),
      JSON.stringify(one),
    );
    expect(out.map((t) => t.name)).toEqual(["sales", "product"]);
  });

  it("falls back to the single-table payload", () => {
    const out = parseDroppedTableCandidates(undefined, JSON.stringify(one));
    expect(out).toHaveLength(1);
    expect(out[0].name).toBe("sales");
  });

  it("returns [] when neither payload is present", () => {
    expect(parseDroppedTableCandidates(undefined, undefined)).toEqual([]);
    expect(parseDroppedTableCandidates("", "")).toEqual([]);
  });

  it("returns [] on malformed JSON instead of throwing", () => {
    expect(parseDroppedTableCandidates("{not json", null)).toEqual([]);
  });
});

describe("resolveDropOrigin (saiku#1634 #3)", () => {
  const rect = { left: 100, top: 50 };

  it("maps viewport coords through the converter when the flow is mounted", () => {
    // A panned/zoomed viewport: the converter returns coords far from the
    // naive pane-relative fallback, proving we honour pan/zoom.
    const screenToFlow = ({ x, y }: { x: number; y: number }) => ({
      x: (x - 100) * 2 + 500,
      y: (y - 50) * 2 + 300,
    });
    expect(resolveDropOrigin(screenToFlow, 300, 250, rect)).toEqual({
      x: (300 - 100) * 2 + 500,
      y: (250 - 50) * 2 + 300,
    });
  });

  it("falls back to pane-relative pixels when no converter is available", () => {
    expect(resolveDropOrigin(null, 300, 250, rect)).toEqual({ x: 200, y: 200 });
    expect(resolveDropOrigin(undefined, 300, 250, rect)).toEqual({ x: 200, y: 200 });
  });
});
