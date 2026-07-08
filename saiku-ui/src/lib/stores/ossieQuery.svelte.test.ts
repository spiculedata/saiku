import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ossieQuery } from "./ossieQuery.svelte";
import type { OssieModel } from "$lib/api/ossie";

/**
 * Unit tests for the Ossie query store. Focus is shelf-state mutation invariants + the
 * runnable-shape guard — the actual network execute is covered end-to-end by the
 * Playwright suite; here we mock fetch so we don't need a live server.
 */

const fakeModel: OssieModel = {
  connection: "SALES",
  name: "SALES",
  datasets: [
    {
      name: "customers",
      source: "public.customers",
      fields: [
        { name: "region", time: false, pii: false },
        { name: "signup_date", time: true, pii: false },
      ],
      primaryKey: ["id"],
    },
    {
      name: "orders",
      source: "public.orders",
      fields: [{ name: "amount", time: false, pii: false }],
      primaryKey: ["order_id"],
    },
  ],
  metrics: [{ name: "revenue", expression: "SUM(orders.amount)", aggregationKind: "SUM" }],
  relationships: [
    {
      name: "orders_to_customers",
      from: "orders",
      to: "customers",
      fromColumns: ["customer_id"],
      toColumns: ["id"],
    },
  ],
};

const originalFetch = globalThis.fetch;

beforeEach(() => {
  ossieQuery.reset();
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function mockFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  globalThis.fetch = vi.fn(handler) as unknown as typeof fetch;
}

describe("ossieQuery.loadModel", () => {
  test("loads the model and seeds a fresh shelf state", async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    expect(ossieQuery.model?.name).toBe("SALES");
    expect(ossieQuery.current?.connection).toBe("SALES");
    expect(ossieQuery.current?.factDataset).toBe("");
    expect(ossieQuery.error).toBeNull();
    expect(ossieQuery.loading).toBe(false);
  });

  test("second call for the same connection is a no-op", async () => {
    const fetchStub = vi.fn(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    globalThis.fetch = fetchStub as unknown as typeof fetch;
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    expect(fetchStub).toHaveBeenCalledTimes(1);
  });

  test("force: true bypasses the memoisation", async () => {
    const fetchStub = vi.fn(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    globalThis.fetch = fetchStub as unknown as typeof fetch;
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    await ossieQuery.loadModel("admin", "SALES", "SALES", true);
    expect(fetchStub).toHaveBeenCalledTimes(2);
  });

  test("surfaces an error and clears the model on failure", async () => {
    mockFetch(async () => new Response("nope", { status: 404 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    expect(ossieQuery.model).toBeNull();
    expect(ossieQuery.current).toBeNull();
    expect(ossieQuery.error).toMatch(/404/);
  });
});

describe("shelf mutations", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("addRow appends immutably and seeds the fact dataset", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "region" }]);
    expect(ossieQuery.current?.factDataset).toBe("customers");
  });

  test("addRow de-dupes on identical (dataset, field)", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.current?.rows.length).toBe(1);
  });

  test("addValue de-dupes on metric name and infers the fact from an existing row", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    // Fact is now customers. Explicitly override to orders to prove the metric-inference
    // path picks the current factDataset (not the row's) as the anchor.
    ossieQuery.setFactDataset("orders");
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.addValue({ metric: "revenue" });
    expect(ossieQuery.current?.values).toEqual([{ metric: "revenue" }]);
    expect(ossieQuery.current?.factDataset).toBe("orders");
  });

  test("removeRow / removeValue / removeFilter drop by index", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addRow({ dataset: "customers", field: "signup_date" });
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.addFilter({ dataset: "customers", field: "region", op: "EQ", value: "NA" });

    ossieQuery.removeRow(0);
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "signup_date" }]);
    ossieQuery.removeValue(0);
    expect(ossieQuery.current?.values).toEqual([]);
    ossieQuery.removeFilter(0);
    expect(ossieQuery.current?.filters).toEqual([]);
  });

  test("setFactDataset overrides the auto-seeded pick", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.setFactDataset("orders");
    expect(ossieQuery.current?.factDataset).toBe("orders");
  });
});

describe("hasRunnableShape", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("false with no shelves", () => {
    expect(ossieQuery.hasRunnableShape()).toBe(false);
  });

  test("false with shelves but no fact dataset", () => {
    ossieQuery.current = { ...ossieQuery.current!, rows: [{ dataset: "customers", field: "region" }] };
    // fact stays empty because we bypassed the addRow helper.
    expect(ossieQuery.current?.factDataset).toBe("");
    expect(ossieQuery.hasRunnableShape()).toBe(false);
  });

  test("true with a value + fact", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    expect(ossieQuery.hasRunnableShape()).toBe(true);
  });

  test("true with only a row + fact (rowset scan, no values)", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.hasRunnableShape()).toBe(true);
  });
});

describe("save / load", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("save posts JSON and tracks savedPath / savedName", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    let seen: RequestInit | undefined;
    globalThis.fetch = (async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      seen = init;
      return new Response("", { status: 200 });
    }) as unknown as typeof fetch;
    await ossieQuery.save("/homes/home:admin/my-query.saiku", "my-query");
    expect(ossieQuery.savedPath).toBe("/homes/home:admin/my-query.saiku");
    expect(ossieQuery.savedName).toBe("my-query");
    // The body is content=<url-encoded JSON>. Decode it back to a JSON payload so we
    // can assert on the shape without brittle string-matching.
    const encoded = String(seen?.body ?? "");
    expect(encoded.startsWith("content=")).toBe(true);
    const decoded = decodeURIComponent(encoded.slice("content=".length));
    const parsed = JSON.parse(decoded);
    expect(parsed).toMatchObject({
      name: "my-query",
      queryType: "OSSIE",
      saikuOssieVersion: 1,
      ossieQueryModel: {
        connection: "SALES",
        model: "SALES",
        factDataset: "customers",
        rows: [{ dataset: "customers", field: "region" }],
        values: [{ metric: "revenue" }],
      },
    });
  });

  test("save surfaces an error and rethrows on non-2xx", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    mockFetch(async () => new Response("nope", { status: 500 }));
    await expect(ossieQuery.save("/x.saiku", "x")).rejects.toBeInstanceOf(Error);
    expect(ossieQuery.error).toMatch(/500/);
  });

  test("load hydrates savedPath / savedName + current from a valid file", async () => {
    const saved = {
      name: "my-query",
      queryType: "OSSIE",
      saikuOssieVersion: 1,
      ossieQueryModel: {
        connection: "SALES",
        model: "SALES",
        factDataset: "customers",
        rows: [{ dataset: "customers", field: "region" }],
        columns: [],
        values: [{ metric: "revenue" }],
        filters: [],
        sorts: [],
      },
    };
    mockFetch(async () => new Response(JSON.stringify(saved), { status: 200 }));
    const loaded = await ossieQuery.load("/homes/home:admin/my-query.saiku");
    expect(loaded).not.toBeNull();
    expect(ossieQuery.savedPath).toBe("/homes/home:admin/my-query.saiku");
    expect(ossieQuery.savedName).toBe("my-query");
    expect(ossieQuery.current?.factDataset).toBe("customers");
    expect(ossieQuery.current?.values).toEqual([{ metric: "revenue" }]);
  });

  test("load returns null when the file is MDX-shaped, leaves store alone", async () => {
    // Simulate an MDX .saiku file — carries queryType='OLAP' and a queryModel field.
    const mdx = { name: "MDX Q", queryType: "OLAP", queryModel: { axes: {} } };
    mockFetch(async () => new Response(JSON.stringify(mdx), { status: 200 }));
    const before = ossieQuery.current;
    const loaded = await ossieQuery.load("/homes/home:admin/mdx.saiku");
    expect(loaded).toBeNull();
    expect(ossieQuery.current).toBe(before);
    expect(ossieQuery.savedPath).toBeNull();
  });

  test("load returns null on empty response body", async () => {
    mockFetch(async () => new Response("", { status: 200 }));
    const loaded = await ossieQuery.load("/x.saiku");
    expect(loaded).toBeNull();
  });

  test("load surfaces an error and rethrows on non-2xx", async () => {
    mockFetch(async () => new Response("gone", { status: 404 }));
    await expect(ossieQuery.load("/missing.saiku")).rejects.toBeInstanceOf(Error);
    expect(ossieQuery.error).toMatch(/404/);
  });

  test("loadModel clears savedPath so save-in-place doesn't overwrite the prior file", async () => {
    // Precondition: loaded a file so savedPath is set.
    const saved = {
      name: "prior",
      queryType: "OSSIE",
      saikuOssieVersion: 1,
      ossieQueryModel: {
        connection: "SALES",
        model: "SALES",
        factDataset: "customers",
        rows: [],
        columns: [],
        values: [],
        filters: [],
        sorts: [],
      },
    };
    mockFetch(async () => new Response(JSON.stringify(saved), { status: 200 }));
    await ossieQuery.load("/x.saiku");
    expect(ossieQuery.savedPath).toBe("/x.saiku");
    // Model refetch (force:true so we don't hit the memoisation short-circuit).
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES", true);
    expect(ossieQuery.savedPath).toBeNull();
    expect(ossieQuery.savedName).toBeNull();
  });
});

describe("run", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("no-op when the shape isn't runnable", async () => {
    const spy = vi.fn(async () => new Response(JSON.stringify({}), { status: 200 }));
    globalThis.fetch = spy as unknown as typeof fetch;
    await ossieQuery.run();
    // The initial load already used fetch once — after reset the counter starts fresh, but
    // in this beforeEach we loaded the model. The load fetch is a separate stub; the run
    // stub was just installed. Assert the run stub was never invoked.
    expect(spy).not.toHaveBeenCalled();
  });

  test("runs and stores the result on success", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    const responseEnv = {
      cellSetHeaders: [
        [{ formattedValue: "customers.region" }, { formattedValue: "revenue" }],
      ],
      cellSetBody: [
        [{ formattedValue: "North" }, { formattedValue: "350.0", rawNumber: 350.0 }],
      ],
      width: 2,
      height: 1,
      runtime: 12,
    };
    mockFetch(async () => new Response(JSON.stringify(responseEnv), { status: 200 }));
    await ossieQuery.run();
    expect(ossieQuery.result?.cellSetBody?.[0]?.[1]?.rawNumber).toBe(350.0);
    expect(ossieQuery.error).toBeNull();
  });

  test("surfaces an error on non-2xx", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    mockFetch(async () => new Response("boom", { status: 500 }));
    await ossieQuery.run();
    expect(ossieQuery.error).toMatch(/500/);
    expect(ossieQuery.result).toBeNull();
  });
});
