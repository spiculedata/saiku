/*
 * Unit tests for emailSelf.ts. No network — global fetch is stubbed per test.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fetchMailHealth, sendEmailSelf } from "./emailSelf";

describe("emailSelf", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  describe("fetchMailHealth", () => {
    test("returns {configured: true} on 200 happy path", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ configured: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      const out = await fetchMailHealth();

      expect(out.configured).toBe(true);
    });

    test("hits GET /rest/saiku/api/email/health with credentials included", async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ configured: false }), { status: 200 }),
      );
      globalThis.fetch = fetchMock;

      await fetchMailHealth();

      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe("/rest/saiku/api/email/health");
      expect(init.method).toBe("GET");
      expect(init.credentials).toBe("include");
    });

    test("returns {configured: false} on non-2xx response", async () => {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response("", { status: 503 }));
      const out = await fetchMailHealth();
      expect(out.configured).toBe(false);
    });

    test("returns {configured: false} on network failure (no throw)", async () => {
      globalThis.fetch = vi.fn().mockRejectedValue(new Error("ECONNREFUSED"));
      const out = await fetchMailHealth();
      expect(out.configured).toBe(false);
    });
  });

  describe("sendEmailSelf", () => {
    test("posts body verbatim to /rest/saiku/api/email/self with credentials + JSON content-type", async () => {
      const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
      globalThis.fetch = fetchMock;

      const body = { subject: "My dashboard", format: "pdf" };
      const res = await sendEmailSelf(body);

      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe("/rest/saiku/api/email/self");
      expect(init.method).toBe("POST");
      expect(init.credentials).toBe("include");
      expect(init.headers["Content-Type"]).toBe("application/json");
      expect(JSON.parse(init.body as string)).toEqual(body);
      expect(res.status).toBe(200);
    });
  });
});
