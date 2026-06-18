import { describe, it, expect, vi } from "vitest";
import { runQuery } from "../src/lib/executor";
const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });
const req = { cube: "c", measures: [{ name: "M" }], rows: [], nonEmpty: true };
describe("executor", () => {
  it("returns grid on SUCCESS", async () => {
    vi.stubGlobal("fetch", vi.fn<() => Promise<Response>>(async () => json({
      status: "SUCCESS", metadata: { measures: ["M"] },
      data: [{ "Dim": "A", "M": { value: 5, formatted: "5", unit: null } }]
    })));
    const r = await runQuery(req);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.grid.rows).toEqual([["A", 5]]);
  });
  it("returns validation info on VALIDATION_ERROR (HTTP 400 body)", async () => {
    // The real AI API returns HTTP 400 for validation errors; the body still carries field/available.
    vi.stubGlobal("fetch", vi.fn<() => Promise<Response>>(async () => json({
      status: "VALIDATION_ERROR", error: "Unknown hierarchy", field: "rows[0].hierarchy", available: ["Products"]
    }, 400)));
    const r = await runQuery(req);
    expect(r.ok).toBe(false);
    if (!r.ok) { expect(r.field).toBe("rows[0].hierarchy"); expect(r.available).toEqual(["Products"]); }
  });
});
