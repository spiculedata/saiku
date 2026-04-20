/*
 * Unit tests for the schema-generator REST client.
 *
 * Each test wires a `vi.fn()` fake fetch, invokes one method, and asserts
 * the URL, method, headers, body, and resolved value. These tests run first
 * and fail until `schemaGen.ts` implements `createSchemaGenClient`.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  createSchemaGenClient,
  type DraftView,
  type StartResponse,
  type StatusResponse,
  type SuggestionOp,
  type SuggestionView,
} from "./schemaGen";

const BASE = "/rest/saiku/admin/schema-generator";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function emptyResponse(status = 204): Response {
  return new Response(null, { status });
}

function lastCall(fetcher: ReturnType<typeof vi.fn>): {
  url: string;
  init: RequestInit;
} {
  const calls = fetcher.mock.calls;
  const [url, init] = calls[calls.length - 1];
  return { url: String(url), init: (init ?? {}) as RequestInit };
}

function headerMap(init: RequestInit): Record<string, string> {
  const h = init.headers ?? {};
  if (h instanceof Headers) {
    const out: Record<string, string> = {};
    h.forEach((v, k) => {
      out[k.toLowerCase()] = v;
    });
    return out;
  }
  const raw = h as Record<string, string>;
  const out: Record<string, string> = {};
  for (const k of Object.keys(raw)) out[k.toLowerCase()] = raw[k];
  return out;
}

describe("createSchemaGenClient", () => {
  let fetcher: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetcher = vi.fn();
  });

  it("start POSTs to /start/{id} and returns the session descriptor", async () => {
    const expected: StartResponse = {
      sessionId: "sess-1",
      dataSourceId: "ds-42",
      stage: "PENDING",
    };
    fetcher.mockResolvedValueOnce(jsonResponse(expected, 202));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    const got = await client.start("ds-42");

    expect(got).toEqual(expected);
    const { url, init } = lastCall(fetcher);
    expect(url).toBe(`${BASE}/start/ds-42`);
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(headerMap(init).accept).toBe("application/json");
  });

  it("start URL-encodes the data source id", async () => {
    fetcher.mockResolvedValueOnce(
      jsonResponse({ sessionId: "s", dataSourceId: "a/b", stage: "PENDING" }, 202),
    );
    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    await client.start("a/b");
    expect(lastCall(fetcher).url).toBe(`${BASE}/start/a%2Fb`);
  });

  it("status GETs /{sessionId}/status and returns the status payload", async () => {
    const expected: StatusResponse = {
      sessionId: "sess-1",
      stage: "READY",
      failureMessage: null,
      cubeCount: 3,
      suggestionCount: 7,
    };
    fetcher.mockResolvedValueOnce(jsonResponse(expected));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    const got = await client.status("sess-1");

    expect(got).toEqual(expected);
    const { url, init } = lastCall(fetcher);
    expect(url).toBe(`${BASE}/sess-1/status`);
    expect(init.method ?? "GET").toBe("GET");
  });

  it("draft GETs /{sessionId}/draft", async () => {
    const expected: DraftView = {
      schemaName: "Sales",
      cubes: [],
      sharedDimensions: [],
    } as unknown as DraftView;
    fetcher.mockResolvedValueOnce(jsonResponse(expected));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    const got = await client.draft("sess-1");

    expect(got).toEqual(expected);
    expect(lastCall(fetcher).url).toBe(`${BASE}/sess-1/draft`);
  });

  it("suggestions GETs /{sessionId}/suggestions", async () => {
    const expected: SuggestionView = { ops: [], degraded: false };
    fetcher.mockResolvedValueOnce(jsonResponse(expected));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    const got = await client.suggestions("sess-1");

    expect(got).toEqual(expected);
    expect(lastCall(fetcher).url).toBe(`${BASE}/sess-1/suggestions`);
  });

  it("applyOp POSTs the op wrapped in { op } and returns the updated draft", async () => {
    const op: SuggestionOp = {
      op: "rename",
      target: { kind: "cube", path: ["Sales"] },
      newName: "Sales2",
    };
    const draft: DraftView = {
      schemaName: "Sales",
      cubes: [],
      sharedDimensions: [],
    } as unknown as DraftView;
    fetcher.mockResolvedValueOnce(jsonResponse(draft));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    const got = await client.applyOp("sess-1", op);

    expect(got).toEqual(draft);
    const { url, init } = lastCall(fetcher);
    expect(url).toBe(`${BASE}/sess-1/ops`);
    expect(init.method).toBe("POST");
    expect(headerMap(init)["content-type"]).toBe("application/json");
    expect(JSON.parse(init.body as string)).toEqual({ op });
  });

  it("save POSTs /{sessionId}/save with { schemaName } and resolves void on 204", async () => {
    fetcher.mockResolvedValueOnce(emptyResponse(204));

    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    await client.save("sess-1", "Sales");

    const { url, init } = lastCall(fetcher);
    expect(url).toBe(`${BASE}/sess-1/save`);
    expect(init.method).toBe("POST");
    expect(headerMap(init)["content-type"]).toBe("application/json");
    expect(JSON.parse(init.body as string)).toEqual({ schemaName: "Sales" });
  });

  it("save without schemaName sends an empty body object", async () => {
    fetcher.mockResolvedValueOnce(emptyResponse(204));
    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    await client.save("sess-1");
    const { init } = lastCall(fetcher);
    expect(JSON.parse(init.body as string)).toEqual({});
  });

  it("throws a descriptive error on non-2xx responses", async () => {
    fetcher.mockResolvedValueOnce(
      new Response("boom", { status: 500, statusText: "Internal Server Error" }),
    );
    const client = createSchemaGenClient(fetcher as unknown as typeof fetch);
    await expect(client.status("sess-1")).rejects.toThrow(/500/);
  });

  it("respects a custom baseUrl", async () => {
    fetcher.mockResolvedValueOnce(
      jsonResponse({ sessionId: "s", dataSourceId: "d", stage: "PENDING" }, 202),
    );
    const client = createSchemaGenClient(
      fetcher as unknown as typeof fetch,
      "https://api.example.com",
    );
    await client.start("d");
    expect(lastCall(fetcher).url).toBe(
      `https://api.example.com/rest/saiku/admin/schema-generator/start/d`,
    );
  });
});
