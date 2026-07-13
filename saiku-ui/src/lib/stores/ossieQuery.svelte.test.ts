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
    // The store now pre-populates factDataset from the relationship graph: 'orders'
    // appears as the `from` end so it's the inferred fact table. The old contract of
    // an empty factDataset on load has moved to the case with no relationships.
    expect(ossieQuery.current?.factDataset).toBe("orders");
    expect(ossieQuery.error).toBeNull();
    expect(ossieQuery.loading).toBe(false);
  });

  test("factDataset is empty when the model has no relationships", async () => {
    // Fresh model with an empty relationships array — no signal to infer a fact from,
    // so factDataset stays empty and the sidebar prompts the user to pick one.
    const flat = { ...fakeModel, relationships: [] };
    mockFetch(async () => new Response(JSON.stringify(flat), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
    expect(ossieQuery.current?.factDataset).toBe("");
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

  test("addRow appends immutably and (when relationships are absent) seeds the fact dataset", () => {
    // Precondition: relationship-graph inference already pre-set factDataset to 'orders'
    // via loadModel. Override to empty so we exercise the addRow seeding path.
    ossieQuery.setFactDataset("");
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "region" }]);
    // With the relationship graph present, addRow's seeder prefers the inferred fact
    // ('orders' — from the relationship) over the row's dataset ('customers'). The
    // fallback to the row's dataset only kicks in when the graph has no signal.
    expect(ossieQuery.current?.factDataset).toBe("orders");
  });

  test("addRow seeds fact from the row's dataset when relationships are absent", async () => {
    const flat = { ...fakeModel, relationships: [] };
    mockFetch(async () => new Response(JSON.stringify(flat), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES", true);
    expect(ossieQuery.current?.factDataset).toBe("");
    ossieQuery.addRow({ dataset: "customers", field: "region" });
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
    ossieQuery.addFilter({ dataset: "customers", field: "region", op: "EQ", value: "NA", values: [] });

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
    // Clear the fact-dataset the model load auto-picked so we test the runnable-shape
    // guard directly.
    ossieQuery.current = {
      ...ossieQuery.current!,
      factDataset: "",
      rows: [{ dataset: "customers", field: "region" }],
    };
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
        // Relationship-graph inference picks 'orders' as the fact — orders → customers
        // is the only relationship, so orders is the from-side (leaf, many-to-one).
        factDataset: "orders",
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

describe("sort / limit / swap axes (P1)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("cycleSort not-sorted → ASC → DESC → cleared", () => {
    const target = { dataset: "customers", field: "region", direction: "ASC" as const };
    ossieQuery.cycleSort(target);
    expect(ossieQuery.current?.sorts).toEqual([{ dataset: "customers", field: "region", direction: "ASC" }]);
    ossieQuery.cycleSort(target);
    expect(ossieQuery.current?.sorts).toEqual([{ dataset: "customers", field: "region", direction: "DESC" }]);
    ossieQuery.cycleSort(target);
    expect(ossieQuery.current?.sorts).toEqual([]);
  });

  test("cycleSort replaces prior single-column sort by default", () => {
    ossieQuery.cycleSort({ dataset: "customers", field: "region", direction: "ASC" });
    ossieQuery.cycleSort({ metric: "revenue", direction: "ASC" });
    // Non-additive → the region sort was replaced by the metric sort.
    expect(ossieQuery.current?.sorts).toEqual([{ metric: "revenue", direction: "ASC" }]);
  });

  test("cycleSort additive appends when the target is new", () => {
    ossieQuery.cycleSort({ dataset: "customers", field: "region", direction: "ASC" });
    ossieQuery.cycleSort({ metric: "revenue", direction: "ASC" }, true);
    expect(ossieQuery.current?.sorts).toEqual([
      { dataset: "customers", field: "region", direction: "ASC" },
      { metric: "revenue", direction: "ASC" },
    ]);
  });

  test("cycleSort additive flips just the matching entry", () => {
    ossieQuery.cycleSort({ dataset: "customers", field: "region", direction: "ASC" });
    ossieQuery.cycleSort({ metric: "revenue", direction: "ASC" }, true);
    // Second click on region flips it to DESC, revenue stays ASC.
    ossieQuery.cycleSort({ dataset: "customers", field: "region", direction: "ASC" }, true);
    expect(ossieQuery.current?.sorts).toEqual([
      { dataset: "customers", field: "region", direction: "DESC" },
      { metric: "revenue", direction: "ASC" },
    ]);
  });

  test("setLimit stores positive values, clears on null / 0 / negative", () => {
    ossieQuery.setLimit(50);
    expect(ossieQuery.current?.limit).toBe(50);
    ossieQuery.setLimit(null);
    expect(ossieQuery.current?.limit).toBeUndefined();
    ossieQuery.setLimit(100);
    expect(ossieQuery.current?.limit).toBe(100);
    ossieQuery.setLimit(0);
    expect(ossieQuery.current?.limit).toBeUndefined();
    ossieQuery.setLimit(200);
    ossieQuery.setLimit(-1);
    expect(ossieQuery.current?.limit).toBeUndefined();
  });

  test("swapAxes swaps rows and columns arrays", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addColumn({ dataset: "customers", field: "signup_date" });
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "region" }]);
    expect(ossieQuery.current?.columns).toEqual([{ dataset: "customers", field: "signup_date" }]);
    ossieQuery.swapAxes();
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "signup_date" }]);
    expect(ossieQuery.current?.columns).toEqual([{ dataset: "customers", field: "region" }]);
  });
});

describe("addFilter via context-menu action shapes (P4)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("EQ filter builds and captures for undo", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.canUndo).toBe(true);
    const beforeUndoCount = ossieQuery.past.length;
    ossieQuery.addFilter({ dataset: "customers", field: "region", op: "EQ", value: "North", values: [] });
    expect(ossieQuery.current?.filters).toEqual([
      { dataset: "customers", field: "region", op: "EQ", value: "North", values: [] },
    ]);
    // Undo → the EQ filter disappears; undo again → the row disappears.
    expect(ossieQuery.past.length).toBe(beforeUndoCount + 1);
    ossieQuery.undo();
    expect(ossieQuery.current?.filters).toEqual([]);
    ossieQuery.undo();
    expect(ossieQuery.current?.rows).toEqual([]);
  });

  test("NEQ filter (exclude) applies the same way", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addFilter({ dataset: "customers", field: "region", op: "NEQ", value: "North", values: [] });
    expect(ossieQuery.current?.filters).toEqual([
      { dataset: "customers", field: "region", op: "NEQ", value: "North", values: [] },
    ]);
  });
});

describe("removeRow / removeColumn / removeValue (P4 follow-up)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("removeRow captures for undo so Cmd-Z restores the removed chip", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addRow({ dataset: "customers", field: "signup_date" });
    expect(ossieQuery.current?.rows.length).toBe(2);
    ossieQuery.removeRow(0);
    expect(ossieQuery.current?.rows).toEqual([{ dataset: "customers", field: "signup_date" }]);
    ossieQuery.undo();
    expect(ossieQuery.current?.rows.length).toBe(2);
  });

  test("removeColumn / removeValue capture for undo the same way", () => {
    ossieQuery.addColumn({ dataset: "customers", field: "region" });
    ossieQuery.removeColumn(0);
    expect(ossieQuery.current?.columns).toEqual([]);
    ossieQuery.undo();
    expect(ossieQuery.current?.columns).toEqual([{ dataset: "customers", field: "region" }]);

    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.removeValue(0);
    expect(ossieQuery.current?.values).toEqual([]);
    ossieQuery.undo();
    expect(ossieQuery.current?.values).toEqual([{ metric: "revenue" }]);
  });
});

describe("setMetricAggregation (D1)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("sets an aggregation override on the matching value shelf entry", () => {
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.setMetricAggregation("revenue", "AVG");
    expect(ossieQuery.current?.values[0].aggregation).toBe("AVG");
  });

  test("clearing via null drops the field entirely (not just sets undefined)", () => {
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.setMetricAggregation("revenue", "AVG");
    ossieQuery.setMetricAggregation("revenue", null);
    expect("aggregation" in ossieQuery.current!.values[0]).toBe(false);
  });

  test("no-op when the metric isn't on the Values shelf", () => {
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.setMetricAggregation("does-not-exist", "SUM");
    expect(ossieQuery.current?.values[0].aggregation).toBeUndefined();
  });

  test("captures for undo", () => {
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.setMetricAggregation("revenue", "MAX");
    ossieQuery.undo();
    expect(ossieQuery.current?.values[0].aggregation).toBeUndefined();
  });
});

describe("reorderShelf (D2)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("moves a rows-shelf chip forward one slot", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addRow({ dataset: "customers", field: "signup_date" });
    ossieQuery.reorderShelf("rows", 0, 2); // move region to end
    expect(ossieQuery.current?.rows.map((r) => r.field)).toEqual(["signup_date", "region"]);
  });

  test("moves a values-shelf chip backward one slot", () => {
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.addValue({ metric: "order_count" });
    ossieQuery.reorderShelf("values", 1, 0);
    expect(ossieQuery.current?.values.map((v) => v.metric)).toEqual(["order_count", "revenue"]);
  });

  test("no-op when from == to", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    const before = ossieQuery.current?.rows;
    const historyLen = ossieQuery.past.length;
    ossieQuery.reorderShelf("rows", 0, 0);
    expect(ossieQuery.current?.rows).toBe(before);
    expect(ossieQuery.past.length).toBe(historyLen);
  });

  test("captures for undo", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addRow({ dataset: "customers", field: "signup_date" });
    ossieQuery.reorderShelf("rows", 0, 2);
    ossieQuery.undo();
    expect(ossieQuery.current?.rows.map((r) => r.field)).toEqual(["region", "signup_date"]);
  });
});

describe("undo / redo (P3)", () => {
  beforeEach(async () => {
    mockFetch(async () => new Response(JSON.stringify(fakeModel), { status: 200 }));
    await ossieQuery.loadModel("admin", "SALES", "SALES");
  });

  test("addRow captures for undo; undo reverses; redo replays", () => {
    expect(ossieQuery.canUndo).toBe(false);
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.canUndo).toBe(true);
    expect(ossieQuery.current?.rows.length).toBe(1);
    ossieQuery.undo();
    expect(ossieQuery.current?.rows.length).toBe(0);
    expect(ossieQuery.canRedo).toBe(true);
    ossieQuery.redo();
    expect(ossieQuery.current?.rows.length).toBe(1);
  });

  test("multi-mutation history walks back one step at a time", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    ossieQuery.setLimit(50);
    expect(ossieQuery.current?.limit).toBe(50);
    ossieQuery.undo(); // undo limit
    expect(ossieQuery.current?.limit).toBeUndefined();
    expect(ossieQuery.current?.values.length).toBe(1);
    ossieQuery.undo(); // undo addValue
    expect(ossieQuery.current?.values.length).toBe(0);
    ossieQuery.undo(); // undo addRow
    expect(ossieQuery.current?.rows.length).toBe(0);
    expect(ossieQuery.canUndo).toBe(false);
  });

  test("forward mutation after undo clears the redo stack", () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.undo();
    expect(ossieQuery.canRedo).toBe(true);
    ossieQuery.addValue({ metric: "revenue" });
    // A new mutation invalidates the previously-undone branch.
    expect(ossieQuery.canRedo).toBe(false);
  });

  test("loadModel clears history so Cmd-Z can't step into a stale-model state", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    expect(ossieQuery.canUndo).toBe(true);
    // Different connection to bypass the memoisation.
    mockFetch(async () => new Response(JSON.stringify({ ...fakeModel, name: "OTHER" }), { status: 200 }));
    await ossieQuery.loadModel("admin", "OTHER", "OTHER");
    expect(ossieQuery.canUndo).toBe(false);
  });

  test("history is capped at 50 entries", () => {
    for (let i = 0; i < 60; i++) {
      ossieQuery.setLimit(i + 1);
    }
    // 60 mutations → history capped at 50. First 10 mutations get shifted out.
    expect(ossieQuery.past.length).toBe(50);
    expect(ossieQuery.current?.limit).toBe(60);
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

  test("stores rawResult alongside the projected result", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    const responseEnv = {
      cellset: [
        [
          { value: "customers.region", type: "COLUMN_HEADER" },
          { value: "revenue", type: "COLUMN_HEADER" },
        ],
        [
          { value: "North", type: "ROW_HEADER" },
          { value: "350.0", type: "DATA_CELL", properties: { raw: "350.0" } },
        ],
      ],
      runtime: 12,
      width: 2,
      height: 1,
    };
    mockFetch(async () => new Response(JSON.stringify(responseEnv), { status: 200 }));
    await ossieQuery.run();
    // rawResult surfaces the wire envelope so ChartView can consume it unchanged.
    expect(ossieQuery.rawResult?.cellset?.length).toBe(2);
    expect(ossieQuery.rawResult?.cellset?.[0]?.[0]?.value).toBe("customers.region");
  });

  test("runs and stores the result on success", async () => {
    ossieQuery.addRow({ dataset: "customers", field: "region" });
    ossieQuery.addValue({ metric: "revenue" });
    // Server envelope: QueryResult shape (Query2Resource → RestUtil.convert(CellDataSet)).
    // Header rows tagged COLUMN_HEADER, body rows tagged ROW_HEADER / DATA_CELL. Numeric
    // values ride in properties.raw.
    const responseEnv = {
      cellset: [
        [
          { value: "customers.region", type: "COLUMN_HEADER" },
          { value: "revenue", type: "COLUMN_HEADER" },
        ],
        [
          { value: "North", type: "ROW_HEADER" },
          { value: "350.0", type: "DATA_CELL", properties: { raw: "350.0" } },
        ],
      ],
      runtime: 12,
      width: 2,
      height: 1,
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
