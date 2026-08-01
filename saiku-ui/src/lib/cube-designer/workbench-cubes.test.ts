import { describe, it, expect } from "vitest";
import {
  maxSeq,
  blankCube,
  cubeToDoc,
  cubeFromDoc,
  mgFromDoc,
  calcFromDoc,
  renderCalcTokens,
  calcMode,
  type WorkbenchCube,
} from "./workbench-cubes.js";
import { exportToMondrianXml } from "./mondrian-export.js";
import type { SchemaCanvasState, SchemaCanvasCube } from "./types.js";

describe("maxSeq", () => {
  it("returns the highest captured number, or 0 when none match", () => {
    expect(maxSeq(["mg-1", "mg-4", "mg-2"], /mg-(\d+)/)).toBe(4);
    expect(maxSeq([], /mg-(\d+)/)).toBe(0);
    expect(maxSeq(["dim-x", "other"], /mg-(\d+)/)).toBe(0);
  });
});

describe("blankCube", () => {
  it("creates an empty edit-mode cube with the given id + name", () => {
    const c = blankCube("cube-1", "Cube 1");
    expect(c.id).toBe("cube-1");
    expect(c.name).toBe("Cube 1");
    expect(c.editMode).toBe(true);
    expect(c.measureGroups).toEqual([]);
    expect(c.factsCalcs).toEqual([]);
  });
});

describe("renderCalcTokens / calcMode", () => {
  it("renders chip tokens as a Mondrian bracket formula", () => {
    expect(
      renderCalcTokens({
        id: "c1",
        name: "Margin",
        tokens: [
          { kind: "measure", name: "Sales" },
          { kind: "op", op: "-" },
          { kind: "measure", name: "Cost" },
        ],
      }),
    ).toBe("[Sales] - [Cost]");
  });

  it("defaults calcMode to build when unset", () => {
    expect(calcMode({ id: "c", name: "x", tokens: [] })).toBe("build");
    expect(
      calcMode({ id: "c", name: "x", tokens: [], mode: "expression" }),
    ).toBe("expression");
  });
});

describe("cubeToDoc ⇄ cubeFromDoc", () => {
  function richWorkbenchCube(): WorkbenchCube {
    return {
      id: "cube-1",
      name: "Sales",
      editMode: false,
      selectedTableId: "f",
      tableConfirmed: true,
      selectedMeasures: ["amount"],
      tableSearch: "sea",
      measureGroups: [
        {
          id: "mg-1",
          name: "Sales Facts",
          measureColumns: ["amount", "qty"],
          factTableId: "f",
          dimensionLinks: [
            { dimensionId: "d1", foreignKeyColumn: "customer_id" },
          ],
          measureCaptions: { amount: "Revenue" },
          factConfirmed: true,
          measuresConfirmed: true,
          dimsConfirmed: true,
        },
      ],
      selectedGroupId: "mg-1",
      groupsEditMode: false,
      groupSeq: 1,
      factsCalcs: [
        {
          id: "calc-1",
          name: "Margin",
          tokens: [
            { kind: "measure", name: "Sales" },
            { kind: "op", op: "-" },
            { kind: "measure", name: "Cost" },
          ],
          mode: "build",
        },
      ],
      selectedCalcId: "calc-1",
      calcsEditMode: false,
      calcSeq: 1,
    };
  }

  it("strips UI-only flags to the durable doc shape", () => {
    const doc = cubeToDoc(richWorkbenchCube());
    expect(doc).toEqual({
      id: "cube-1",
      name: "Sales",
      measureGroups: [
        {
          id: "mg-1",
          name: "Sales Facts",
          measureColumns: ["amount", "qty"],
          factTableId: "f",
          dimensionLinks: [
            { dimensionId: "d1", foreignKeyColumn: "customer_id" },
          ],
          measureCaptions: { amount: "Revenue" },
        },
      ],
      calcs: [
        {
          id: "calc-1",
          name: "Margin",
          tokens: [
            { kind: "measure", name: "Sales" },
            { kind: "op", op: "-" },
            { kind: "measure", name: "Cost" },
          ],
          formula: undefined,
          mode: "build",
        },
      ],
    });
  });

  it("does not share nested references with its source (immutable copy)", () => {
    const wb = richWorkbenchCube();
    const doc = cubeToDoc(wb);
    expect(doc.measureGroups[0].measureColumns).not.toBe(
      wb.measureGroups[0].measureColumns,
    );
    expect(doc.measureGroups[0].dimensionLinks?.[0]).not.toBe(
      wb.measureGroups[0].dimensionLinks?.[0],
    );
  });

  it("re-derives UI flags from content on the way back", () => {
    const doc = cubeToDoc(richWorkbenchCube());
    const wb = cubeFromDoc(doc);
    expect(wb.selectedTableId).toBe("f"); // first MG fact
    expect(wb.tableConfirmed).toBe(true);
    expect(wb.groupsEditMode).toBe(false); // has groups
    expect(wb.calcsEditMode).toBe(false); // has calcs
    expect(wb.groupSeq).toBe(1);
    expect(wb.calcSeq).toBe(1);
    expect(wb.measureGroups[0].factConfirmed).toBe(true);
    expect(wb.measureGroups[0].measuresConfirmed).toBe(true);
    expect(wb.measureGroups[0].dimsConfirmed).toBe(true);
  });

  it("mgFromDoc opens a blank group in edit mode", () => {
    const mg = mgFromDoc({ id: "mg-9", name: "Empty", measureColumns: [] });
    expect(mg.factConfirmed).toBe(false);
    expect(mg.measuresConfirmed).toBe(false);
    expect(mg.dimsConfirmed).toBe(false);
  });

  it("calcFromDoc defaults an op token op to +", () => {
    const calc = calcFromDoc({ id: "c", name: "x", tokens: [{ kind: "op" }] });
    expect(calc.tokens[0]).toEqual({ kind: "op", op: "+" });
  });
});

/**
 * #1038 — preview == export. The inspector Code-tab preview and the
 * save/export path both funnel the workbench cubes through
 * `cubeToDoc` → `exportToMondrianXml`. This pins that the two paths emit
 * byte-identical Mondrian 4 for a representative workbench cube (previously
 * the Code tab used a second, drift-prone inline emitter).
 */
describe("preview == export parity (#1038)", () => {
  function representativeState(): {
    base: SchemaCanvasState;
    wbCube: WorkbenchCube;
  } {
    const base: SchemaCanvasState = {
      version: 1,
      connectionId: "conn-1",
      label: "Sales",
      tables: [
        {
          id: "f",
          schema: "public",
          name: "sales_fact",
          role: "fact",
          columns: [
            { name: "amount", sqlType: "numeric" },
            { name: "qty", sqlType: "numeric" },
            { name: "customer_id", sqlType: "int" },
          ],
          position: { x: 0, y: 0 },
          groupId: null,
        },
        {
          id: "c",
          schema: "public",
          name: "customer",
          role: "dimension",
          columns: [
            { name: "id", sqlType: "int" },
            { name: "country", sqlType: "text" },
          ],
          position: { x: 100, y: 0 },
          groupId: null,
        },
      ],
      joins: [],
      groups: [],
      updatedAt: "2026-07-24T00:00:00.000Z",
      dimensions: [
        {
          id: "d1",
          name: "Customer",
          sourceTableId: "c",
          primaryKeyTableId: "c",
          primaryKey: "id",
          attributes: [{ tableId: "c", columnName: "id" }],
          hierarchies: [
            {
              id: "h1",
              name: "Geo",
              hasAll: true,
              levels: [
                {
                  id: "l1",
                  name: "Country",
                  tableId: "c",
                  columnName: "country",
                  type: "String",
                },
              ],
            },
          ],
        },
      ],
      measures: [
        {
          id: "m1",
          name: "Sales",
          aggregator: "sum",
          tableId: "f",
          columnName: "amount",
          formatString: "$#,##0",
        },
      ],
    };
    const wbCube: WorkbenchCube = {
      ...blankCube("cube-1", "Sales"),
      measureGroups: [
        {
          id: "mg-1",
          name: "Sales Facts",
          measureColumns: ["amount", "qty"],
          factTableId: "f",
          dimensionLinks: [
            { dimensionId: "d1", foreignKeyColumn: "customer_id" },
          ],
        },
      ],
      factsCalcs: [
        {
          id: "calc-1",
          name: "Margin",
          tokens: [
            { kind: "measure", name: "Sales" },
            { kind: "op", op: "-" },
            { kind: "measure", name: "Cost" },
          ],
          mode: "build",
        },
      ],
    };
    return { base, wbCube };
  }

  it("the preview and export paths emit identical XML", () => {
    const { base, wbCube } = representativeState();
    const docCubes: SchemaCanvasCube[] = [wbCube].map(cubeToDoc);

    // Export/save path: persist writes docCubes onto the doc, export reads it.
    const exported = exportToMondrianXml({ ...base, cubes: docCubes });
    // Preview path: builds the same docCubes live and exports on the fly.
    const previewed = exportToMondrianXml({
      ...base,
      cubes: [wbCube].map(cubeToDoc),
    });

    expect(previewed).toBe(exported);
  });

  it("emits structurally valid Mondrian 4", () => {
    const { base, wbCube } = representativeState();
    const xml = exportToMondrianXml({
      ...base,
      cubes: [wbCube].map(cubeToDoc),
    });
    expect(xml).toContain('metamodelVersion="4.0"');
    expect(xml).toContain('<Cube name="Sales">');
    expect(xml).toContain(
      '<MeasureGroup name="Sales Facts" table="sales_fact">',
    );
    expect(xml).toContain(
      '<ForeignKeyLink dimension="Customer" foreignKeyColumn="customer_id" />',
    );
    expect(xml).toContain(
      '<CalculatedMember name="Margin" dimension="Measures" formula="[Sales] - [Cost]"/>',
    );
    // M4 only — never the legacy M3 markers.
    expect(xml).not.toContain("foreignKey=");
  });
});
