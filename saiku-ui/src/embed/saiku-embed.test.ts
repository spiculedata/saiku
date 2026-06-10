/*
 * Smoke coverage for the <saiku-embed/> custom element:
 *   - Importing the bundle entry registers the tag once globally.
 *   - With server + path attributes, it fires the documented fetch URL.
 *   - With server + path + token, it sends the token header.
 *   - With no token and no server-side public grant, the component
 *     surfaces a friendly 401 message rather than the raw error body.
 *
 * Rendering depth (table structure, header / numeric column logic) is
 * already covered by the API + EmbedTable unit shapes — here we only
 * verify the wire surface the host page can observe.
 */
// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

interface FetchCall {
  url: string;
  init?: RequestInit;
}

describe("<saiku-embed/>", () => {
  let calls: FetchCall[];
  let nextResp: { status: number; body: unknown };

  beforeEach(async () => {
    calls = [];
    nextResp = { status: 200, body: { format: "records", data: [] } };
    vi.stubGlobal("fetch", (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return Promise.resolve(
        new Response(JSON.stringify(nextResp.body), {
          status: nextResp.status,
          headers: { "Content-Type": "application/json" },
        }),
      );
    });
    // The component imports happy-dom-incompatible Svelte runtime bits
    // lazily, so we dynamically import here AFTER the global stubs.
    await import("./saiku-embed");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    // Clean up any element instances left in the DOM.
    document.body.innerHTML = "";
  });

  it("registers the custom element on import", () => {
    expect(customElements.get("saiku-embed")).toBeDefined();
  });

  it("fires the documented embed URL when server + path are set", async () => {
    const el = document.createElement("saiku-embed");
    el.setAttribute("server", "https://demo.saiku.bi");
    el.setAttribute("path", "homes/admin/sales.saiku");
    el.setAttribute("token", "tok-abc");
    document.body.appendChild(el);
    await flush();

    expect(calls.length).toBeGreaterThanOrEqual(1);
    const last = calls[calls.length - 1];
    expect(last.url).toBe("https://demo.saiku.bi/rest/saiku/api/embed/query/homes/admin/sales.saiku");
    const headers = (last.init?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Saiku-Embed-Token"]).toBe("tok-abc");
  });

  it("omits the token header for anonymous (public) reads", async () => {
    const el = document.createElement("saiku-embed");
    el.setAttribute("server", "https://demo.saiku.bi");
    el.setAttribute("path", "shared/public.saiku");
    document.body.appendChild(el);
    await flush();

    const last = calls[calls.length - 1];
    const headers = (last.init?.headers ?? {}) as Record<string, string>;
    expect(headers["X-Saiku-Embed-Token"]).toBeUndefined();
  });

  it("does not fetch until both server and path are set", async () => {
    const el = document.createElement("saiku-embed");
    // server-only — the embed lives in an idle state until path arrives
    // (host React apps hydrate attrs asynchronously, so we mustn't 401
    // a half-mounted component).
    el.setAttribute("server", "https://demo.saiku.bi");
    document.body.appendChild(el);
    await flush();
    expect(calls).toHaveLength(0);

    el.setAttribute("path", "homes/admin/x.saiku");
    await flush();
    expect(calls).toHaveLength(1);
  });

  it("surfaces a friendly message instead of the raw 401 body", async () => {
    nextResp = {
      status: 401,
      body: { status: "EMBED_INVALID", error: "Embed token is invalid or expired." },
    };
    const el = document.createElement("saiku-embed");
    el.setAttribute("server", "https://demo.saiku.bi");
    el.setAttribute("path", "homes/admin/x.saiku");
    document.body.appendChild(el);

    // The error path goes: $effect schedules → fetch resolves → .catch →
    // state assignment → svelte re-render. Drain past the render rather
    // than a fixed tick count so the assertion isn't timing-fragile.
    const root = (el as unknown as { shadowRoot: ShadowRoot }).shadowRoot;
    await waitFor(() => roleText(root, "alert") !== "");

    const text = roleText(root, "alert");
    // Friendly, not the raw EMBED_INVALID surface. Hosts shouldn't have
    // to know whether the token expired, was revoked, or pointed at the
    // wrong resource.
    expect(text).toContain("unavailable");
    expect(text).not.toContain("EMBED_INVALID");
  });
});

/** Push the microtask queue so the $effect callbacks + fetch resolves
 *  run before the assertion. Four awaits covers fetch → then → state →
 *  render in the happy-dom + Svelte 5 scheduler. */
async function flush(): Promise<void> {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

/** Read the live text of the element matching {@code role}. Reading
 *  textContent off the shadow root pulls in the inlined CSS source-map
 *  comment, swamping the assertion. Scoping to the rendered role node
 *  gives us only the user-visible string. */
function roleText(root: ShadowRoot, role: string): string {
  const node = root.querySelector(`[role="${role}"]`);
  return (node?.textContent ?? "").trim();
}

/** Poll the predicate until it returns true or a hard cap (~250ms) of
 *  microtask yields is reached — keeps async-state assertions stable
 *  without a fixed sleep. */
async function waitFor(pred: () => boolean): Promise<void> {
  for (let i = 0; i < 50; i++) {
    if (pred()) return;
    await flush();
  }
}
