/*
 * Unit tests for the inline-query ↔ ThinQuery conversion (issue #912).
 * Covers:
 *   - body → model: measures, rows, columns, filters, member selections
 *   - model → body: axis projection, multi-level drilldown, cube ref shape
 *   - passthrough preservation of unmanaged body fields (order/limit)
 *   - round-trip stability
 *   - runnable-shape guard
 *   - immutability of inputs
 */

import { describe, test, expect } from "vitest";
import {
  bodyToThinQuery,
  thinQueryToBody,
  bodyIsRunnable,
  type InlineQueryBody,
} from "$lib/dashboard/queryBodyConversion";
import type { SaikuCube } from "$lib/api/discover";

function sampleCube(): SaikuCube {
  return {
    connection: "foodmart",
    catalog: "FoodMart",
    schema: "FoodMart",
    name: "Sales",
    caption: "Sales",
    uniqueName: "[Sales]",
    visible: true,
  };
}

function sampleBody(): InlineQueryBody {
  return {
    cube: { connectionName: "foodmart", catalog: "FoodMart", schema: "FoodMart", cubeName: "Sales" },
    measures: [{ name: "Store Sales" }, { name: "Unit Sales" }],
    rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
    columns: [],
    order: [{ by: "Store Sales", direction: "desc" }],
    limit: 3,
  };
}

describe("bodyToThinQuery", () => {
  test("maps measures, rows, columns and filters onto the model", () => {
    const body: InlineQueryBody = {
      measures: [{ name: "Store Sales" }],
      rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
      columns: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
      filters: [
        {
          dimension: "Store",
          hierarchy: "Store",
          level: "Store Country",
          members: ["[Store].[USA]"],
        },
      ],
    };
    const q = bodyToThinQuery(body, sampleCube());
    const model = q.queryModel!;
    expect(model.details.measures.map((m) => m.name)).toEqual(["Store Sales"]);
    expect(model.details.measures[0].uniqueName).toBe("[Measures].[Store Sales]");
    expect(model.axes.ROWS.hierarchies).toHaveLength(1);
    expect(model.axes.ROWS.hierarchies[0].name).toBe("Products");
    expect(model.axes.ROWS.hierarchies[0].dimension).toBe("Product");
    expect(Object.keys(model.axes.ROWS.hierarchies[0].levels)).toEqual(["Product Family"]);
    expect(model.axes.COLUMNS.hierarchies[0].name).toBe("Time");
    // filter member selection carried as INCLUSION
    const filterHier = model.axes.FILTER.hierarchies[0];
    expect(filterHier.name).toBe("Store");
    expect(filterHier.levels["Store Country"].selection?.type).toBe("INCLUSION");
    expect(filterHier.levels["Store Country"].selection?.members).toEqual([
      { uniqueName: "[Store].[USA]" },
    ]);
  });

  test("drops malformed axis entries and tolerates a null body", () => {
    const body = {
      measures: [{ name: "Store Sales" }, { notName: "x" }],
      rows: [{ dimension: "Product" }, null, { dimension: "Time", hierarchy: "Time", level: "Year" }],
    } as unknown as InlineQueryBody;
    const q = bodyToThinQuery(body, sampleCube());
    expect(q.queryModel!.details.measures.map((m) => m.name)).toEqual(["Store Sales"]);
    expect(q.queryModel!.axes.ROWS.hierarchies).toHaveLength(1);

    const empty = bodyToThinQuery(null, sampleCube());
    expect(empty.queryModel!.details.measures).toEqual([]);
    expect(empty.queryModel!.axes.ROWS.hierarchies).toEqual([]);
  });

  test("does not mutate the input body", () => {
    const body = sampleBody();
    const snapshot = JSON.stringify(body);
    bodyToThinQuery(body, sampleCube());
    expect(JSON.stringify(body)).toBe(snapshot);
  });
});

describe("thinQueryToBody", () => {
  test("projects the model back to an AiQueryRequest body with the 4-segment cube ref", () => {
    const q = bodyToThinQuery(sampleBody(), sampleCube());
    const body = thinQueryToBody(q);
    expect(body.cube).toEqual({
      connectionName: "foodmart",
      catalog: "FoodMart",
      schema: "FoodMart",
      cubeName: "Sales",
    });
    expect(body.measures).toEqual([{ name: "Store Sales" }, { name: "Unit Sales" }]);
    expect(body.rows).toEqual([
      { dimension: "Product", hierarchy: "Products", level: "Product Family" },
    ]);
    expect(body.columns).toEqual([]);
  });

  test("emits one row entry per level for a multi-level drilldown hierarchy", () => {
    const q = bodyToThinQuery(
      {
        measures: [{ name: "Store Sales" }],
        rows: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
      },
      sampleCube(),
    );
    // add a second level to the same hierarchy (drilldown)
    q.queryModel!.axes.ROWS.hierarchies[0].levels["Quarter"] = { name: "Quarter" };
    const body = thinQueryToBody(q);
    expect(body.rows).toEqual([
      { dimension: "Time", hierarchy: "Time", level: "Year" },
      { dimension: "Time", hierarchy: "Time", level: "Quarter" },
    ]);
  });

  test("carries over unmanaged passthrough fields and overwrites managed ones", () => {
    const prev = sampleBody();
    const q = bodyToThinQuery(prev, sampleCube());
    const body = thinQueryToBody(q, prev);
    // order / limit preserved
    expect(body.order).toEqual([{ by: "Store Sales", direction: "desc" }]);
    expect(body.limit).toBe(3);
    // cube reduced to the 4-segment ref (not the prevBody value verbatim)
    expect(body.cube).toEqual({
      connectionName: "foodmart",
      catalog: "FoodMart",
      schema: "FoodMart",
      cubeName: "Sales",
    });
  });

  test("round-trips a body through the model without losing axis shape", () => {
    const body = sampleBody();
    const q = bodyToThinQuery(body, sampleCube());
    const out = thinQueryToBody(q, body);
    expect(out.measures).toEqual(body.measures);
    expect(out.rows).toEqual(body.rows);
    expect(out.order).toEqual(body.order);
    expect(out.limit).toBe(body.limit);
  });
});

describe("bodyIsRunnable", () => {
  test("requires at least one measure and one row/column hierarchy", () => {
    const runnable = bodyToThinQuery(sampleBody(), sampleCube());
    expect(bodyIsRunnable(runnable)).toBe(true);

    const noMeasure = bodyToThinQuery(
      { rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }] },
      sampleCube(),
    );
    expect(bodyIsRunnable(noMeasure)).toBe(false);

    const noAxis = bodyToThinQuery({ measures: [{ name: "Store Sales" }] }, sampleCube());
    expect(bodyIsRunnable(noAxis)).toBe(false);

    expect(bodyIsRunnable(null)).toBe(false);
  });
});
