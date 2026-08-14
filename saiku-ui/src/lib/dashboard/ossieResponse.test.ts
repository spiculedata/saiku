/*
 * Unit tests for the Ossie → AiQueryResponse adapter (saiku#1803).
 *
 * The fixtures below are REAL payloads, captured from /ai/ossie/query running
 * against the Flights demo via the launcher IT harness — not shapes inferred
 * from the docs. The docs describe the two AI surfaces as "the same shape",
 * which is true of the cells and false of the envelope; believing it is what
 * made the first cut of this feature render nothing.
 */

import { describe, expect, it } from "vitest";
import { toAiQueryResponse } from "$lib/dashboard/ossieResponse";
import { buildChartOption, projectFromAiQueryResponse } from "$lib/dashboard/chartOptions";

/** Verbatim from the IT probe: flight_count by carrier.carrier_name. */
function realSuccess() {
  return {
    queryId: "ossie-ai-032d244b",
    runtime: 201,
    columns: [
      { key: "carrier.carrier_name", label: "Carrier", type: "dimension" },
      { key: "flight_count", label: "flight_count", type: "metric", aggregationKind: "count" },
    ],
    records: [
      { "carrier.carrier_name": "American Airlines", flight_count: { value: 18.0, formatted: "18" } },
      { "carrier.carrier_name": "Delta Air Lines", flight_count: { value: 18.0, formatted: "18" } },
      { "carrier.carrier_name": "Southwest Airlines", flight_count: { value: 12.0, formatted: "12" } },
    ],
    meta: { rowCount: 3, truncated: false },
  };
}

describe("toAiQueryResponse — success", () => {
  it("moves records onto data and synthesises the missing status", () => {
    // The envelope carries no `status` at all on success; every tile checks it.
    const r = toAiQueryResponse(realSuccess());
    expect(r.status).toBe("SUCCESS");
    expect(r.data).toHaveLength(3);
  });

  it("renames record keys to their column labels", () => {
    // "carrier.carrier_name" is what a chart would otherwise print on its
    // category axis.
    const r = toAiQueryResponse(realSuccess());
    expect(Object.keys(r.data![0])).toEqual(["Carrier", "flight_count"]);
  });

  it("keeps raw keys when two columns would collide on one label", () => {
    const raw = {
      ...realSuccess(),
      columns: [
        { key: "a.state", label: "State", type: "dimension" },
        { key: "b.state", label: "State", type: "dimension" },
        { key: "n", label: "n", type: "metric" },
      ],
      records: [{ "a.state": "WA", "b.state": "CA", n: { value: 1, formatted: "1" } }],
    };
    // Renaming both to "State" would silently drop a column.
    expect(Object.keys(toAiQueryResponse(raw).data![0])).toEqual(["a.state", "b.state", "n"]);
  });

  it("puts only the measure columns in metadata.columns", () => {
    const r = toAiQueryResponse(realSuccess());
    expect(r.metadata?.columns?.map((c) => c.name)).toEqual(["flight_count"]);
  });

  it("carries the row count and runtime across", () => {
    const r = toAiQueryResponse(realSuccess());
    expect(r.totalRows).toBe(3);
    expect(r.runtimeMs).toBe(201);
  });

  it("survives an empty result", () => {
    const r = toAiQueryResponse({ ...realSuccess(), records: [], meta: { rowCount: 0 } });
    expect(r.status).toBe("SUCCESS");
    expect(r.data).toEqual([]);
    expect(r.totalRows).toBe(0);
  });
});

describe("toAiQueryResponse — the property the feature rests on", () => {
  it("projects through the shared chart projection exactly like an MDX result", () => {
    // This is the whole claim: adapt the wrapper and every renderer downstream
    // is untouched. If this breaks, tiles render blank with no error.
    const p = projectFromAiQueryResponse(toAiQueryResponse(realSuccess()));
    expect(p.rowCategories).toEqual(["American Airlines", "Delta Air Lines", "Southwest Airlines"]);
    expect(p.columnCategories).toEqual(["flight_count"]);
    expect(p.matrix).toEqual([[18], [18], [12]]);
  });
});

describe("toAiQueryResponse — errors", () => {
  it("normalises the validator's shape into the self-correcting envelope", () => {
    // Captured verbatim: this variant puts the CODE in `error` and the prose in
    // `message`, the opposite way round from the MDX path.
    const r = toAiQueryResponse({
      error: "VALIDATION_ERROR",
      field: "rows[0].field",
      message: "unknown field 'name' on dataset 'carrier'",
      available: ["carrier_id", "iata_code", "carrier_name", "hub_state"],
    });
    expect(r.status).toBe("VALIDATION_ERROR");
    expect(r.error).toContain("unknown field");
    expect(r.field).toBe("rows[0].field");
    expect(r.available).toContain("carrier_name");
    expect(r.data).toEqual([]);
  });

  it("normalises the generic mapper's shape too", () => {
    const r = toAiQueryResponse({
      queryId: null,
      status: "VALIDATION_ERROR",
      error: "Unknown field 'metrics' on OssieAiQueryRequest.",
      field: "metrics",
      available: ["columns", "connection", "filters"],
    });
    expect(r.status).toBe("VALIDATION_ERROR");
    expect(r.error).toContain("Unknown field");
    expect(r.field).toBe("metrics");
  });

  it("falls back to ERROR when the payload names no code", () => {
    const r = toAiQueryResponse({ error: "Internal error" });
    expect(r.status).toBe("Internal error");
    expect(r.data).toEqual([]);
  });

  it("treats a payload with records as a result even if it carries a status", () => {
    // `records` is the discriminator — a success envelope never has an error
    // shape, but being strict about it keeps a future added field from
    // silently turning results into errors.
    const r = toAiQueryResponse({ ...realSuccess(), status: "SUCCESS" });
    expect(r.data).toHaveLength(3);
  });

  it("is safe on null / garbage", () => {
    expect(toAiQueryResponse(null).data).toEqual([]);
    expect(toAiQueryResponse(undefined).status).toBe("SUCCESS");
  });
});

describe("toAiQueryResponse — the last step before the canvas", () => {
  it("builds a complete ECharts option from the real payload", () => {
    // The furthest a headless test can follow a model-backed tile: real captured
    // response -> adapter -> shared projection -> the option the tile hands to
    // ECharts. Everything after this is the library drawing pixels, which is not
    // where a bug of this class lives — the last one was the envelope, two steps
    // upstream, and no amount of hand-written fixtures caught it.
    const opt = buildChartOption(toAiQueryResponse(realSuccess()), "bar") as Record<string, unknown>;
    expect(opt).not.toBeNull();

    const xAxis = opt.xAxis as { type?: string; data?: string[] };
    expect(xAxis.type).toBe("category");
    expect(xAxis.data).toEqual(["American Airlines", "Delta Air Lines", "Southwest Airlines"]);

    const series = opt.series as Array<{ name?: string; type?: string; data?: unknown[] }>;
    expect(series).toHaveLength(1);
    expect(series[0].name).toBe("flight_count");
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([18, 18, 12]);
  });

  it("an empty slice yields no option rather than an empty chart frame", () => {
    // The WA / OR case: the tile falls through to its empty state, which since
    // saiku#1804 names the source that came up empty.
    const empty = toAiQueryResponse({ ...realSuccess(), records: [], meta: { rowCount: 0 } });
    expect(buildChartOption(empty, "bar")).toBeNull();
  });
});
