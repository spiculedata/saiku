/*
 * Unit tests for tile source discrimination (saiku#1803).
 */

import { describe, expect, it } from "vitest";
import type { CubeRef } from "$lib/api/dashboards";
import {
  isOssieSource,
  ossieSource,
  sameSource,
  sourceKey,
  sourceKind,
  sourceLabel,
} from "$lib/dashboard/tileSource";

const mdx: CubeRef = {
  connectionName: "unknown_foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "Store",
};

describe("sourceKind", () => {
  it("treats an absent kind as mdx — every document written before #1803", () => {
    // The load-bearing default. A call site testing `kind === "mdx"` would be
    // false for every dashboard and app already saved.
    expect(sourceKind(mdx)).toBe("mdx");
    expect(isOssieSource(mdx)).toBe(false);
  });

  it("reads an explicit kind", () => {
    expect(sourceKind({ ...mdx, kind: "mdx" })).toBe("mdx");
    expect(sourceKind(ossieSource("unknown_Flights", "Flights"))).toBe("ossie");
    expect(isOssieSource(ossieSource("unknown_Flights", "Flights"))).toBe(true);
  });

  it("is safe on null / undefined", () => {
    expect(sourceKind(null)).toBe("mdx");
    expect(isOssieSource(undefined)).toBe(false);
  });
});

describe("ossieSource", () => {
  const o = ossieSource("unknown_Flights", "Flights");

  it("carries connection + model", () => {
    expect(o.connectionName).toBe("unknown_Flights");
    expect(o.modelName).toBe("Flights");
    expect(o.kind).toBe("ossie");
  });

  it("fills the MDX coordinates with the model name rather than leaving them blank", () => {
    // A long tail of code renders cube.cubeName as "what this tile is on" and
    // builds cache keys from the four coordinates; blanks would surface as
    // empty labels rather than an error anyone would notice.
    expect(o.cubeName).toBe("Flights");
    expect(o.catalog).toBe("Flights");
    expect(o.schema).toBe("Flights");
  });
});

describe("sourceKey", () => {
  it("separates a cube from a model of the same name on the same connection", () => {
    const cube: CubeRef = {
      connectionName: "c",
      catalog: "Flights",
      schema: "Flights",
      cubeName: "Flights",
    };
    expect(sourceKey(cube)).not.toBe(sourceKey(ossieSource("c", "Flights")));
  });

  it("is stable for the same source", () => {
    expect(sourceKey(mdx)).toBe(sourceKey({ ...mdx }));
    expect(sourceKey(ossieSource("c", "M"))).toBe(sourceKey(ossieSource("c", "M")));
  });

  it("ignores the mirrored coordinates on an ossie ref", () => {
    const a = ossieSource("c", "M");
    expect(sourceKey({ ...a, catalog: "x", schema: "y", cubeName: "z" })).toBe(sourceKey(a));
  });

  it("treats an absent kind as the same source as an explicit mdx", () => {
    expect(sourceKey(mdx)).toBe(sourceKey({ ...mdx, kind: "mdx" }));
  });
});

describe("sourceLabel", () => {
  it("names the cube or the model", () => {
    expect(sourceLabel(mdx)).toBe("Store");
    expect(sourceLabel(ossieSource("c", "Flights"))).toBe("Flights");
  });
});

describe("sameSource", () => {
  it("compares by key, and is false when either side is missing", () => {
    expect(sameSource(mdx, { ...mdx })).toBe(true);
    expect(sameSource(mdx, ossieSource("c", "Store"))).toBe(false);
    expect(sameSource(mdx, null)).toBe(false);
    expect(sameSource(null, null)).toBe(false);
  });
});
