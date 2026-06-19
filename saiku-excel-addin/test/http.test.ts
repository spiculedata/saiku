import { describe, it, expect, vi, beforeEach } from "vitest";
import { api, apiJson } from "../src/lib/http";
import type { ServerCtx } from "../src/lib/http";

const ctx: ServerCtx = {
  baseUrl: "https://x.test",
  credential: { kind: "basic", username: "u", password: "p" }
};

describe("http", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200, headers: { "content-type": "application/json" } })));
  });

  it("prefixes the baseUrl and sets Authorization header", async () => {
    await api(ctx, "/rest/saiku/api/ai/cubes");
    const [url, opts] = (fetch as any).mock.calls[0];
    expect(url).toBe("https://x.test/rest/saiku/api/ai/cubes");
    expect(opts.headers["Authorization"]).toMatch(/^Basic /);
  });

  it("uses credentials:omit (no cookies)", async () => {
    await api(ctx, "/rest/saiku/api/ai/cubes");
    const [, opts] = (fetch as any).mock.calls[0];
    expect(opts.credentials).toBe("omit");
  });

  it("sets Content-Type on POST and sends Authorization", async () => {
    await api(ctx, "/rest/saiku/api/ai/query", { method: "POST", body: JSON.stringify({}) });
    const [url, opts] = (fetch as any).mock.calls[0];
    expect(url).toBe("https://x.test/rest/saiku/api/ai/query");
    expect(opts.headers["Content-Type"]).toBe("application/json");
    expect(opts.headers["Authorization"]).toMatch(/^Basic /);
    expect(opts.credentials).toBe("omit");
  });

  it("does NOT send X-XSRF-TOKEN header (no cookies, no XSRF)", async () => {
    await api(ctx, "/rest/saiku/api/ai/query", { method: "POST", body: JSON.stringify({}) });
    const [, opts] = (fetch as any).mock.calls[0];
    expect(opts.headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("apiJson throws on non-ok status", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("err", { status: 500 })));
    await expect(apiJson(ctx, "/rest/saiku/api/ai/cubes")).rejects.toThrow("HTTP 500");
  });
});
