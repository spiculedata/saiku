import { describe, expect, test } from "vitest";
import { ossieResultToCsv } from "./exportCsv";
import type { OssieQueryResult } from "$lib/api/ossie";

function mkResult(headers: string[], body: Array<Array<string | number | null>>): OssieQueryResult {
  return {
    cellSetHeaders: [headers.map((h) => ({ formattedValue: h, rawValue: h }))],
    cellSetBody: body.map((row) =>
      row.map((v) => {
        if (v === null) return {};
        if (typeof v === "number") return { formattedValue: v.toFixed(2), rawValue: v.toString(), rawNumber: v };
        return { formattedValue: v, rawValue: v };
      }),
    ),
    width: headers.length,
    height: body.length,
  };
}

describe("ossieResultToCsv", () => {
  test("headers + numeric body use formattedValue", () => {
    const r = mkResult(["region", "revenue"], [["North", 350]]);
    expect(ossieResultToCsv(r)).toBe("region,revenue\r\nNorth,350.00\r\n");
  });

  test("escapes commas / quotes / newlines per RFC 4180", () => {
    const r = mkResult(
      ["name", "note"],
      [
        ["O'Brien", 'has, comma'],
        ["quoted \"inline\"", "line1\nline2"],
      ],
    );
    // ' does NOT trigger quoting; , and " do; embedded " doubles.
    expect(ossieResultToCsv(r)).toBe(
      "name,note\r\n" +
        "O'Brien,\"has, comma\"\r\n" +
        '"quoted ""inline""","line1\nline2"\r\n',
    );
  });

  test("empty body produces just the header row", () => {
    const r = mkResult(["region", "revenue"], []);
    expect(ossieResultToCsv(r)).toBe("region,revenue\r\n");
  });

  test("null cells render as empty strings, not the literal 'null'", () => {
    const r = mkResult(["region", "revenue"], [["North", null]]);
    expect(ossieResultToCsv(r)).toBe("region,revenue\r\nNorth,\r\n");
  });
});
