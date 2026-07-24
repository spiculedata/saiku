/*
 * Unit tests for aiDashboard.ts. No network — global fetch is stubbed per
 * test. Mirrors aiAsk.test.ts's setup.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { buildAiDashboard, type BuildDashboardRequest, type DashboardSpec } from "./aiDashboard";
import { AiAskTransportError } from "./aiAsk";

const baseReq: BuildDashboardRequest = {
  question: "build me a sales overview dashboard",
  cube: {
    connectionName: "foodmart",
    catalog: "FoodMart",
    schema: "FoodMart",
    cubeName: "Sales",
  },
};

describe("buildAiDashboard", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  function mockJson(status: number, body: unknown) {
    globalThis.fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  }

  test("returns the parsed DashboardSpec on a 200 happy path", async () => {
    const body: DashboardSpec = {
      title: "Sales Overview",
      degraded: false,
      reason: "",
      model: "claude-x",
      tiles: [
        {
          title: "Sales by Country",
          type: "chart",
          chartType: "bar",
          query: { cube: { cubeName: "Sales" }, measures: [{ name: "Store Sales" }] },
        },
        {
          title: "Sales detail",
          type: "table",
          query: { cube: { cubeName: "Sales" }, measures: [{ name: "Store Sales" }] },
        },
      ],
    };
    mockJson(200, body);

    const out = await buildAiDashboard(baseReq);

    expect(out.degraded).toBe(false);
    expect(out.title).toBe("Sales Overview");
    expect(out.model).toBe("claude-x");
    expect(out.tiles).toHaveLength(2);
    expect(out.tiles[0].chartType).toBe("bar");
    expect(out.tiles[1].chartType).toBeUndefined();
  });

  test("returns a degraded:true 200 envelope as-is (does not throw)", async () => {
    const body: DashboardSpec = {
      degraded: true,
      reason: "couldn't build a dashboard for that request",
      tiles: [],
    };
    mockJson(200, body);

    const out = await buildAiDashboard(baseReq);

    expect(out.degraded).toBe(true);
    expect(out.reason).toContain("couldn't build");
    expect(out.tiles).toHaveLength(0);
    expect(out.title).toBeUndefined();
  });

  test("returns a 503 not-configured degrade envelope as-is (does not throw)", async () => {
    const body: DashboardSpec = {
      degraded: true,
      reason: "AI ask is not configured. Set saiku.ai.ask.provider...",
      tiles: [],
    };
    mockJson(503, body);

    const out = await buildAiDashboard(baseReq);

    expect(out.degraded).toBe(true);
    expect(out.reason).toContain("not configured");
  });

  test("throws AiAskTransportError on a 500 that is not a spec envelope", async () => {
    mockJson(500, { error: "boom" });
    await expect(buildAiDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
  });

  test("throws AiAskTransportError on an empty body", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response("", { status: 500 }));
    await expect(buildAiDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
  });

  test("throws AiAskTransportError on a non-JSON body", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response("<html>oops</html>", { status: 500 }));
    await expect(buildAiDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
  });

  test("throws AiAskTransportError with status 0 on a network failure", async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error("ECONNREFUSED"));
    try {
      await buildAiDashboard(baseReq);
      expect.fail("should have thrown");
    } catch (e) {
      expect(e).toBeInstanceOf(AiAskTransportError);
      expect((e as AiAskTransportError).status).toBe(0);
    }
  });

  test("forwards an AbortSignal to fetch", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ degraded: false, title: "x", tiles: [] }), { status: 200 }),
    );
    globalThis.fetch = fetchMock;
    const controller = new AbortController();

    await buildAiDashboard(baseReq, controller.signal);

    const [, init] = fetchMock.mock.calls[0];
    expect(init.signal).toBe(controller.signal);
  });

  test("rethrows an AbortError UNWRAPPED (not an AiAskTransportError) so a cancel reads as a cancel", async () => {
    globalThis.fetch = vi
      .fn()
      .mockRejectedValue(Object.assign(new Error("aborted"), { name: "AbortError" }));
    try {
      await buildAiDashboard(baseReq, new AbortController().signal);
      expect.fail("should have thrown");
    } catch (e) {
      expect((e as Error).name).toBe("AbortError");
      expect(e).not.toBeInstanceOf(AiAskTransportError);
    }
  });

  test("posts to the dashboard endpoint with credentials + JSON + the request body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ degraded: false, title: "x", tiles: [] }), { status: 200 }),
    );
    globalThis.fetch = fetchMock;

    await buildAiDashboard({
      ...baseReq,
      history: [
        { role: "user", content: "sales by country" },
        { role: "assistant", content: "{...}" },
      ],
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/rest/saiku/api/ai/ask/dashboard");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(init.headers["Content-Type"]).toBe("application/json");
    const parsed = JSON.parse(init.body as string);
    expect(parsed.question).toBe(baseReq.question);
    expect(parsed.cube.cubeName).toBe("Sales");
    expect(parsed.history).toHaveLength(2);
  });
});
