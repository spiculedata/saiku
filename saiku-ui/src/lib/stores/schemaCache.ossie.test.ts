/*
 * saiku#1821 — an MDX schema lookup for an Ossie source must not hit the wire.
 *
 * A semantic model has no MDX schema, and the cube endpoint fails for one. A
 * failed fetch is deliberately not cached, so every caller that primes schemas
 * on an interaction re-requested forever, and each rejection re-rendered the
 * surrounding UI. In the filter panel that meant focusing the <select> closed
 * its own dropdown — a filter the user could not click.
 */

import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { schemaCache } from "$lib/stores/schemaCache.svelte";
import { ossieSource } from "$lib/dashboard/tileSource";

const model = ossieSource("unknown_Flights", "Flights");

beforeEach(() => {
  schemaCache.clear();
  vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("must not be called"))));
});
afterEach(() => vi.unstubAllGlobals());

describe("schemaCache — ossie sources", () => {
  it("answers without a request", async () => {
    const s = await schemaCache.get(model);
    expect(s).toEqual({ dimensions: {} });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("caches the answer, so repeated hovers cost nothing", async () => {
    await schemaCache.get(model);
    expect(schemaCache.peek(model)).toEqual({ dimensions: {} });
    await schemaCache.get(model);
    await schemaCache.get(model);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("reports no dimensions, so an MDX applicability check resolves to a definite no", () => {
    // The point of answering rather than failing: callers asking "does this
    // dim/hier/level resolve here" get a real answer instead of retrying.
    return schemaCache.get(model).then((s) => expect(Object.keys(s.dimensions ?? {})).toEqual([]));
  });
});
