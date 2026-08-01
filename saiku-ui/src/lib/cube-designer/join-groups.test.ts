/**
 * Unit tests for the join-group derivation helpers.
 */
import { describe, it, expect } from "vitest";
import { SchemaCanvasStore } from "./state.svelte.js";
import type { SourceTableCandidate } from "./types.js";
import { computeJoinGroups, isSemanticGroup } from "./join-groups.js";

function candidate(name: string, columns: string[]): SourceTableCandidate {
  return {
    schema: "public",
    name,
    columns: columns.map((c) => ({ name: c, sqlType: "INTEGER" })),
    onCanvas: false,
  };
}

function addTable(
  store: SchemaCanvasStore,
  name: string,
  columns: string[],
): string {
  return store.addTable(candidate(name, columns), { x: 0, y: 0 }).id;
}

describe("computeJoinGroups", () => {
  it("groups joins by rendered label preserving insertion order", () => {
    const store = new SchemaCanvasStore("jg1");
    const a = addTable(store, "a", ["x", "y"]);
    const b = addTable(store, "b", ["x", "y"]);
    store.addJoin({
      sourceTableId: a,
      sourceColumnName: "x",
      targetTableId: b,
      targetColumnName: "x",
      kind: "inner",
    });
    store.addJoin({
      sourceTableId: a,
      sourceColumnName: "y",
      targetTableId: b,
      targetColumnName: "y",
      kind: "inner",
    });

    const groups = computeJoinGroups(store);
    // Two distinct column pairs → two groups, each with one join.
    expect(groups).toHaveLength(2);
    expect(groups.every((g) => g.joins.length === 1)).toBe(true);
  });
});

describe("isSemanticGroup", () => {
  it("is true only when every join is cube-link / inferred-fk", () => {
    const store = new SchemaCanvasStore("jg2");
    const a = addTable(store, "a", ["x"]);
    const b = addTable(store, "b", ["x"]);
    store.addJoin({
      sourceTableId: a,
      sourceColumnName: "x",
      targetTableId: b,
      targetColumnName: "x",
      kind: "inner",
      origin: "cube-link",
    });
    expect(isSemanticGroup(computeJoinGroups(store)[0])).toBe(true);

    const store2 = new SchemaCanvasStore("jg3");
    const c = addTable(store2, "a", ["x"]);
    const d = addTable(store2, "b", ["x"]);
    store2.addJoin({
      sourceTableId: c,
      sourceColumnName: "x",
      targetTableId: d,
      targetColumnName: "x",
      kind: "inner",
      origin: "physical",
    });
    expect(isSemanticGroup(computeJoinGroups(store2)[0])).toBe(false);
  });
});
