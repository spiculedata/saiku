// @vitest-environment happy-dom
import { describe, expect, it } from "vitest";
import { SchemaCanvasStore } from "$lib/cube-designer/state.svelte";
import { hydrateFromMondrianXml } from "./edit-load";

/**
 * saiku#1634 — the edit-existing-cube hydration path. Parsing an attached
 * Mondrian-4 schema must populate the canvas doc, seed the workbench cubes, and
 * recenter the viewport (so the loaded nodes aren't off-screen).
 */

const SCHEMA_XML = `<Schema name="Sales" metamodelVersion="4.0">
  <PhysicalSchema>
    <Table name="dim_date"><Key name="k"><Column name="date_key"/></Key></Table>
    <Table name="fact"/>
  </PhysicalSchema>
  <Dimension name="Calendar" type="TIME" table="dim_date" key="Year">
    <Attributes>
      <Attribute name="Year" table="dim_date" keyColumn="year_num" levelType="TimeYears"/>
    </Attributes>
    <Hierarchies>
      <Hierarchy name="Calendar" hasAll="true"><Level attribute="Year"/></Hierarchy>
    </Hierarchies>
  </Dimension>
  <Cube name="C">
    <Dimensions><Dimension source="Calendar"/></Dimensions>
    <MeasureGroups>
      <MeasureGroup name="G" table="fact">
        <Measures><Measure name="Cnt" aggregator="count"/></Measures>
      </MeasureGroup>
    </MeasureGroups>
  </Cube>
</Schema>`;

describe("hydrateFromMondrianXml", () => {
  it("loads tables onto the canvas and recenters the viewport", () => {
    const store = new SchemaCanvasStore("test-ds");
    const result = hydrateFromMondrianXml(store, SCHEMA_XML, "test-ds", () => 4242);

    expect(result.tableCount).toBe(2);
    expect(store.doc.tables.map((t) => t.name).sort()).toEqual(["dim_date", "fact"]);
    // Recenter so the freshly-loaded nodes are visible, not off in the corner.
    expect(store.requestedCanvasAction).toEqual({ kind: "fit_view", ts: 4242 });
  });

  it("seeds the workbench cubes from the imported schema", () => {
    const store = new SchemaCanvasStore("test-ds-2");
    hydrateFromMondrianXml(store, SCHEMA_XML, "test-ds-2");

    expect(store.cubes.map((c) => c.name)).toEqual(["C"]);
    expect(store.cubes[0]?.measureGroups.map((mg) => mg.name)).toEqual(["G"]);
  });

  it("throws on malformed XML so the caller can surface it", () => {
    const store = new SchemaCanvasStore("test-ds-3");
    expect(() => hydrateFromMondrianXml(store, "not xml at all", "test-ds-3")).toThrow();
  });
});
