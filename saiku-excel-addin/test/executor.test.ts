import { describe, it, expect, vi } from "vitest";
import { runQuery } from "../src/lib/executor";
import type { ServerCtx } from "../src/lib/http";

const ctx: ServerCtx = {
  baseUrl: "https://x.test",
  credential: { kind: "basic", username: "u", password: "p" }
};

const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });
const req = { cube: "c", measures: [{ name: "M" }], rows: [], nonEmpty: true };

describe("executor", () => {
  it("returns grid on SUCCESS — URL is baseUrl + /rest/..., credentials omit", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({
      status: "SUCCESS", metadata: { measures: ["M"] },
      data: [{ "Dim": "A", "M": { value: 5, formatted: "5", unit: null } }]
    })));
    const r = await runQuery(ctx, req);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.grid.rows).toEqual([["A", 5]]);
    // Verify server-scoped request
    const call = (fetch as any).mock.calls[0] as [string, RequestInit];
    expect(call[0]).toBe("https://x.test/rest/saiku/api/ai/query");
    expect(call[1].credentials).toBe("omit");
    expect((call[1].headers as Record<string, string>)["Authorization"]).toMatch(/^Basic /);
  });

  it("returns validation info on VALIDATION_ERROR (HTTP 400 body)", async () => {
    // The real AI API returns HTTP 400 for validation errors; the body still carries field/available.
    vi.stubGlobal("fetch", vi.fn<() => Promise<Response>>(async () => json({
      status: "VALIDATION_ERROR", error: "Unknown hierarchy", field: "rows[0].hierarchy", available: ["Products"]
    }, 400)));
    const r = await runQuery(ctx, req);
    expect(r.ok).toBe(false);
    if (!r.ok) { expect(r.field).toBe("rows[0].hierarchy"); expect(r.available).toEqual(["Products"]); }
  });
});
