import { describe, expect, test } from "vitest";
import { parseFormattedCell } from "./cellFormat";

describe("parseFormattedCell", () => {
  test("returns raw string when no marker", () => {
    expect(parseFormattedCell("2,096")).toEqual({ display: "2,096" });
    expect(parseFormattedCell("$4,250.51")).toEqual({ display: "$4,250.51" });
  });

  test("extracts inner value + colour for the Mondrian marker", () => {
    expect(parseFormattedCell("|($2,561.57)|style=red")).toEqual({
      display: "($2,561.57)",
      color: "red",
    });
  });

  test("handles localised currency inside the marker", () => {
    expect(parseFormattedCell("|(2,561.57 €)|style=red")).toEqual({
      display: "(2,561.57 €)",
      color: "red",
    });
  });

  test("handles hex colours", () => {
    expect(parseFormattedCell("|42|style=#ff8800")).toEqual({
      display: "42",
      color: "#ff8800",
    });
    expect(parseFormattedCell("|42|style=#fff")).toEqual({
      display: "42",
      color: "#fff",
    });
  });

  test("returns raw when the marker is malformed", () => {
    expect(parseFormattedCell("|value|noStyle")).toEqual({ display: "|value|noStyle" });
    expect(parseFormattedCell("|value|style=")).toEqual({ display: "|value|style=" });
    expect(parseFormattedCell("|value|style=rgba(255,0,0,0.5)")).toEqual({
      display: "|value|style=rgba(255,0,0,0.5)",
    });
  });

  test("handles null/empty input", () => {
    expect(parseFormattedCell(null)).toEqual({ display: "" });
    expect(parseFormattedCell(undefined)).toEqual({ display: "" });
    expect(parseFormattedCell("")).toEqual({ display: "" });
  });
});
