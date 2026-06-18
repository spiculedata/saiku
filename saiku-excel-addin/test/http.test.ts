import { describe, it, expect, vi, beforeEach } from "vitest";
import { api } from "../src/lib/http";

describe("http", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=tok123";
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200, headers: { "content-type": "application/json" } })));
  });
  it("adds X-XSRF-TOKEN header on POST and credentials include", async () => {
    await api("/rest/saiku/api/ai/query", { method: "POST", body: JSON.stringify({}) });
    const [, opts] = (fetch as any).mock.calls[0];
    expect(opts.headers["X-XSRF-TOKEN"]).toBe("tok123");
    expect(opts.credentials).toBe("include");
  });
  it("omits XSRF header on GET", async () => {
    await api("/rest/saiku/api/ai/cubes");
    const [, opts] = (fetch as any).mock.calls[0];
    expect(opts.headers["X-XSRF-TOKEN"]).toBeUndefined();
  });
});
