/*
 * Unit coverage for the embed fetch helpers — URL shape, token header
 * transport, path encoding, and error parsing. The Web Component on top
 * of this fetch is rendered separately; here we only check the wire
 * surface so a regression in the URL or header doesn't silently 401.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EmbedFetchError, fetchDashboard, fetchDashboardTile, fetchSavedQuery } from "./api";

describe("fetchSavedQuery", () => {
  let calls: Array<{ url: string; init?: RequestInit }>;
  let resolveBody: unknown;
  let respStatus = 200;

  beforeEach(() => {
    calls = [];
    resolveBody = { format: "records", data: [] };
    respStatus = 200;
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return Promise.resolve(
        new Response(JSON.stringify(resolveBody), {
          status: respStatus,
          headers: { "Content-Type": "application/json" },
        }),
      );
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("hits the embed query URL with the token header when a token is given", async () => {
    await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/sales.saiku", "tok-abc");
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("https://demo.saiku.bi/rest/saiku/api/embed/query/homes/admin/sales.saiku");
    const headers = (calls[0].init?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Saiku-Embed-Token"]).toBe("tok-abc");
  });

  it("omits the token header when no token is given (anonymous public flow)", async () => {
    await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/sales.saiku");
    const headers = (calls[0].init?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Saiku-Embed-Token"]).toBeUndefined();
  });

  it("strips trailing slashes from the server origin", async () => {
    await fetchSavedQuery("https://demo.saiku.bi/", "homes/admin/x.saiku");
    expect(calls[0].url.startsWith("https://demo.saiku.bi/rest/")).toBe(true);
    expect(calls[0].url).not.toContain("//rest/");
  });

  it("URL-encodes each path segment but preserves slashes", async () => {
    // The auth filter on the server URL-decodes the segment before
    // comparing against the stored canonical path; we need to match.
    await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/My Sales (Q4).saiku", "tok");
    expect(calls[0].url).toBe(
      "https://demo.saiku.bi/rest/saiku/api/embed/query/homes/admin/My%20Sales%20(Q4).saiku",
    );
  });

  it("sets credentials: omit so the host page's saiku cookie isn't sent cross-origin", async () => {
    // Critical: the embed lives on third-party origins, the saiku-session
    // cookie shouldn't be sent along with the embed fetch (token is the
    // ONLY auth carrier for this surface).
    await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/x.saiku", "tok");
    expect(calls[0].init?.credentials).toBe("omit");
  });

  it("throws EmbedFetchError carrying the server's status + body on 4xx", async () => {
    respStatus = 401;
    resolveBody = { status: "EMBED_INVALID", error: "Embed token is invalid or expired." };
    await expect(fetchSavedQuery("https://demo.saiku.bi", "homes/admin/x.saiku", "tok")).rejects.toMatchObject({
      name: "EmbedFetchError",
      status: 401,
      body: { status: "EMBED_INVALID" },
    });
  });

  it("synthesises an error envelope when the server returns non-JSON", async () => {
    respStatus = 500;
    vi.stubGlobal("fetch", () =>
      Promise.resolve(
        new Response("Internal Server Error", {
          status: 500,
          headers: { "Content-Type": "text/plain" },
        }),
      ),
    );
    let caught: unknown;
    try {
      await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/x.saiku", "tok");
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeInstanceOf(EmbedFetchError);
    expect((caught as EmbedFetchError).status).toBe(500);
  });

  it("returns the parsed records payload on success", async () => {
    resolveBody = {
      format: "records",
      data: [{ "Store Sales": { value: 1234, formatted: "$1,234" } }],
    };
    const out = await fetchSavedQuery("https://demo.saiku.bi", "homes/admin/x.saiku", "tok");
    expect(out.format).toBe("records");
    expect(out.data).toHaveLength(1);
    expect(out.data[0]["Store Sales"].formatted).toBe("$1,234");
  });
});

describe("fetchDashboard", () => {
  let calls: Array<{ url: string; init?: RequestInit }>;

  beforeEach(() => {
    calls = [];
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      const body = { id: "d-1", name: "Exec", version: 1, layout: { cols: 12, tiles: [] } };
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it("hits the dashboard URL with the token header", async () => {
    await fetchDashboard("https://demo.saiku.bi", "homes/admin/exec.saikudash", "tok-d");
    expect(calls[0].url).toBe("https://demo.saiku.bi/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash");
    expect((calls[0].init?.headers as Record<string, string>)["X-Saiku-Embed-Token"]).toBe("tok-d");
  });

  it("returns the parsed dashboard layout", async () => {
    const out = await fetchDashboard("https://demo.saiku.bi", "homes/admin/exec.saikudash");
    expect(out.id).toBe("d-1");
    expect(out.layout.cols).toBe(12);
  });
});

describe("fetchDashboardTile", () => {
  let calls: Array<{ url: string; init?: RequestInit }>;

  beforeEach(() => {
    calls = [];
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return Promise.resolve(
        new Response(JSON.stringify({ format: "records", data: [] }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it("POSTs to /tile/{id}/query with the URL-encoded tileId", async () => {
    // tileId can contain characters (a real tile is a UUID, but a
    // server-side rename could in principle widen this) — encode
    // defensively.
    await fetchDashboardTile("https://demo.saiku.bi", "homes/admin/exec.saikudash", "tile/with/slash", "tok");
    expect(calls[0].url).toBe(
      "https://demo.saiku.bi/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash/tile/tile%2Fwith%2Fslash/query",
    );
    expect(calls[0].init?.method).toBe("POST");
    expect((calls[0].init?.headers as Record<string, string>)["X-Saiku-Embed-Token"]).toBe("tok");
  });

  it("omits the token header for anonymous public dashboards", async () => {
    await fetchDashboardTile("https://demo.saiku.bi", "shared/public.saikudash", "tile-1");
    const headers = (calls[0].init?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Saiku-Embed-Token"]).toBeUndefined();
  });
});
