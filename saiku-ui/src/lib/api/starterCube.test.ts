import { describe, expect, it } from "vitest";
import {
  findCubeByRef,
  parseStarterCubeRef,
  pickStarterLevelDrop,
  pickStarterMeasure,
  pickStarterMeasureAndLevel,
} from "./starterCube";
import type {
  SaikuConnection,
  SaikuDimension,
  SaikuLevel,
  SaikuMeasure,
} from "./discover";

describe("parseStarterCubeRef()", () => {
  it("returns null when no params present", () => {
    const params = new URLSearchParams();
    expect(parseStarterCubeRef(params)).toBeNull();
  });

  it("returns null when any of the 4 fields is missing", () => {
    const p = new URLSearchParams();
    p.set("starterCubeConnection", "conn");
    p.set("starterCubeCatalog", "cat");
    p.set("starterCubeSchema", "sch");
    // starterCubeName missing
    expect(parseStarterCubeRef(p)).toBeNull();
  });

  it("returns null when a field is blank/whitespace", () => {
    const p = new URLSearchParams();
    p.set("starterCubeConnection", "  ");
    p.set("starterCubeCatalog", "cat");
    p.set("starterCubeSchema", "sch");
    p.set("starterCubeName", "Sales");
    expect(parseStarterCubeRef(p)).toBeNull();
  });

  it("parses all 4 fields when present", () => {
    const p = new URLSearchParams();
    p.set("starterCubeConnection", "my-conn");
    p.set("starterCubeCatalog", "FoodMart");
    p.set("starterCubeSchema", "FoodMart");
    p.set("starterCubeName", "Sales");
    expect(parseStarterCubeRef(p)).toEqual({
      connection: "my-conn",
      catalog: "FoodMart",
      schema: "FoodMart",
      name: "Sales",
    });
  });

  it("trims surrounding whitespace", () => {
    const p = new URLSearchParams();
    p.set("starterCubeConnection", "  my-conn  ");
    p.set("starterCubeCatalog", " FoodMart");
    p.set("starterCubeSchema", "FoodMart ");
    p.set("starterCubeName", " Sales ");
    expect(parseStarterCubeRef(p)).toEqual({
      connection: "my-conn",
      catalog: "FoodMart",
      schema: "FoodMart",
      name: "Sales",
    });
  });
});

describe("findCubeByRef()", () => {
  const connections: SaikuConnection[] = [
    {
      name: "conn-a",
      catalogs: [
        {
          name: "cat",
          schemas: [
            {
              name: "sch",
              cubes: [
                cube("Sales", "conn-a"),
                cube("Inventory", "conn-a"),
              ],
            },
          ],
        },
      ],
    },
    {
      name: "conn-b",
      catalogs: [
        {
          name: "cat",
          schemas: [
            {
              name: "sch",
              cubes: [cube("Sales", "conn-b")], // same cube name, different connection
            },
          ],
        },
      ],
    },
  ];

  it("finds a cube by exact 4-tuple match", () => {
    const found = findCubeByRef(connections, {
      connection: "conn-a",
      catalog: "cat",
      schema: "sch",
      name: "Inventory",
    });
    expect(found?.name).toBe("Inventory");
    expect(found?.connection).toBe("conn-a");
  });

  it("disambiguates same cube name across connections", () => {
    const found = findCubeByRef(connections, {
      connection: "conn-b",
      catalog: "cat",
      schema: "sch",
      name: "Sales",
    });
    expect(found?.connection).toBe("conn-b");
  });

  it("returns null when no cube matches", () => {
    expect(
      findCubeByRef(connections, {
        connection: "missing",
        catalog: "cat",
        schema: "sch",
        name: "Sales",
      }),
    ).toBeNull();
  });
});

describe("pickStarterMeasure()", () => {
  it("returns null on empty list", () => {
    expect(pickStarterMeasure([])).toBeNull();
  });

  it("prefers a non-calculated measure over a calculated one", () => {
    const measures: SaikuMeasure[] = [
      { name: "Revenue per Order", caption: "Revenue/Order", uniqueName: "[Measures].[RPO]", calculated: true },
      { name: "Revenue", caption: "Revenue", uniqueName: "[Measures].[Revenue]" },
    ];
    const picked = pickStarterMeasure(measures);
    expect(picked?.name).toBe("Revenue");
    expect(picked?.type).toBe("EXACT");
  });

  it("falls back to the first measure when all are calculated and tags type", () => {
    const measures: SaikuMeasure[] = [
      { name: "RPO", caption: "RPO", uniqueName: "[Measures].[RPO]", calculated: true },
      { name: "Margin", caption: "Margin", uniqueName: "[Measures].[Margin]", calculated: true },
    ];
    const picked = pickStarterMeasure(measures);
    expect(picked?.name).toBe("RPO");
    expect(picked?.type).toBe("CALCULATED");
  });

  it("returns a ThinMeasure shape ready for query.addMeasure", () => {
    const picked = pickStarterMeasure([
      { name: "Revenue", caption: "", uniqueName: "[Measures].[Revenue]" },
    ]);
    expect(picked).toEqual({
      name: "Revenue",
      uniqueName: "[Measures].[Revenue]",
      caption: "Revenue", // empty caption falls back to name
      type: "EXACT",
    });
  });
});

describe("pickStarterLevelDrop()", () => {
  it("returns null when no dimensions", () => {
    expect(pickStarterLevelDrop([])).toBeNull();
  });

  it("prefers a time-named level (e.g. Year) over a String dim", () => {
    const dims: SaikuDimension[] = [
      dimension("Region", [hierarchy("Region", [level("Region")])]),
      dimension("Time", [hierarchy("Time", [level("Year"), level("Month")])]),
    ];
    const drop = pickStarterLevelDrop(dims);
    expect(drop?.dimensionName).toBe("Time");
    expect(drop?.levelName).toBe("Year");
  });

  it("recognises a time-named DIMENSION when its levels are non-obvious", () => {
    const dims: SaikuDimension[] = [
      dimension("Region", [hierarchy("Region", [level("Region")])]),
      dimension("Date", [hierarchy("Date", [level("All Members")])]),
    ];
    const drop = pickStarterLevelDrop(dims);
    expect(drop?.dimensionName).toBe("Date");
  });

  it("falls back to first dim's first level when nothing is time-like", () => {
    const dims: SaikuDimension[] = [
      dimension("Product", [hierarchy("Product", [level("Category"), level("SKU")])]),
      dimension("Region", [hierarchy("Region", [level("Region")])]),
    ];
    const drop = pickStarterLevelDrop(dims);
    expect(drop?.dimensionName).toBe("Product");
    expect(drop?.levelName).toBe("Category");
  });

  it("skips a dimension whose hierarchy has no levels", () => {
    const dims: SaikuDimension[] = [
      dimension("EmptyDim", [hierarchy("EmptyHier", [])]),
      dimension("Region", [hierarchy("Region", [level("Region")])]),
    ];
    const drop = pickStarterLevelDrop(dims);
    expect(drop?.dimensionName).toBe("Region");
  });

  it("returns a LevelDrop with all 7 chip fields populated", () => {
    const dims: SaikuDimension[] = [
      dimension("Region", [hierarchy("Region", [level("Region")])]),
    ];
    const drop = pickStarterLevelDrop(dims);
    expect(drop).toEqual({
      dimensionName: "Region",
      dimensionUniqueName: "[Region]",
      hierarchyName: "Region",
      hierarchyUniqueName: "[Region.Region]",
      hierarchyCaption: "Region",
      levelName: "Region",
      levelCaption: "Region",
    });
  });
});

describe("pickStarterMeasureAndLevel()", () => {
  it("returns null when measures are empty", () => {
    expect(
      pickStarterMeasureAndLevel(
        [],
        [dimension("Region", [hierarchy("Region", [level("Region")])])],
      ),
    ).toBeNull();
  });

  it("returns null when dimensions are empty", () => {
    expect(
      pickStarterMeasureAndLevel(
        [measure("Revenue")],
        [],
      ),
    ).toBeNull();
  });

  it("returns both picks together when both axes have viable candidates", () => {
    const r = pickStarterMeasureAndLevel(
      [measure("Revenue")],
      [dimension("Time", [hierarchy("Time", [level("Year")])])],
    );
    expect(r?.measure.name).toBe("Revenue");
    expect(r?.drop.dimensionName).toBe("Time");
    expect(r?.drop.levelName).toBe("Year");
  });
});

// ----- helpers ------------------------------------------------------

function cube(name: string, connection = "conn-a") {
  return {
    connection,
    catalog: "cat",
    schema: "sch",
    name,
    caption: name,
    uniqueName: `[${name}]`,
    visible: true,
  };
}

function level(name: string): SaikuLevel {
  return { name, caption: name, uniqueName: `[${name}]` };
}

function hierarchy(name: string, levels: SaikuLevel[]) {
  return {
    name,
    caption: name,
    uniqueName: `[${name}.${name}]`,
    levels,
  };
}

function dimension(name: string, hierarchies: ReturnType<typeof hierarchy>[]): SaikuDimension {
  return {
    name,
    caption: name,
    uniqueName: `[${name}]`,
    hierarchies,
  };
}

function measure(name: string): SaikuMeasure {
  return { name, caption: name, uniqueName: `[Measures].[${name}]` };
}
