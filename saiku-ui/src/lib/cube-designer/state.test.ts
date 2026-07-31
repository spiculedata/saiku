import { describe, it, expect } from "vitest";
import { SchemaCanvasStore } from "./state.svelte.js";

describe("SchemaCanvasStore cubes", () => {
  it("addCube + addMeasureGroup + toggleMeasureColumn build a cube on the doc", () => {
    // Arrange
    const store = new SchemaCanvasStore("conn-1");

    // Act
    const cube = store.addCube({ name: "Sales" });
    const mg = store.addMeasureGroup(cube.id, {
      name: "Facts",
      factTableId: "f",
    });
    store.toggleMeasureColumn(cube.id, mg!.id, "amount");
    store.toggleMeasureColumn(cube.id, mg!.id, "qty");
    store.toggleMeasureColumn(cube.id, mg!.id, "amount"); // toggles amount back off

    // Assert
    expect(mg).not.toBeNull();
    expect(store.cubes).toHaveLength(1);
    const group = store.cubes[0].measureGroups[0];
    expect(group.measureColumns).toEqual(["qty"]);
    expect(group.factTableId).toBe("f");
  });

  it("setMeasureGroupDimLink upserts by dimensionId", () => {
    // Arrange
    const store = new SchemaCanvasStore("conn-2");
    const cube = store.addCube({});
    const mg = store.addMeasureGroup(cube.id, { factTableId: "f" })!;

    // Act
    store.setMeasureGroupDimLink(cube.id, mg.id, {
      dimensionId: "d1",
      foreignKeyColumn: "a",
    });
    store.setMeasureGroupDimLink(cube.id, mg.id, {
      dimensionId: "d1",
      foreignKeyColumn: "b",
    });
    store.setMeasureGroupDimLink(cube.id, mg.id, {
      dimensionId: "d2",
      foreignKeyColumn: "c",
    });

    // Assert
    const links = store.cubes[0].measureGroups[0].dimensionLinks ?? [];
    expect(links).toHaveLength(2);
    expect(links.find((l) => l.dimensionId === "d1")?.foreignKeyColumn).toBe(
      "b",
    );
  });

  it("bumpWorkbenchReload increments the reload nonce", () => {
    // Arrange
    const store = new SchemaCanvasStore("conn-3");
    const before = store.workbenchReloadNonce;

    // Act
    store.bumpWorkbenchReload();

    // Assert
    expect(store.workbenchReloadNonce).toBe(before + 1);
  });
});

// A stand-in candidate for addTable: minimal shape the tests rely on.
function candidate(schema: string, name: string, columns: string[]) {
  return {
    schema,
    name,
    onCanvas: false,
    columns: columns.map((n) => ({ name: n, sqlType: "VARCHAR" as const })),
  };
}

describe("Create Joins Mode — pushEShiftPick (one column per table)", () => {
  it("appends a pick for a new table", () => {
    const store = new SchemaCanvasStore("conn-picks-1");
    store.pushEShiftPick("t1", "a");
    expect(store.eShiftPicks).toEqual([{ tableId: "t1", columnName: "a" }]);
  });

  it("appends a second pick when it is a different table", () => {
    const store = new SchemaCanvasStore("conn-picks-2");
    store.pushEShiftPick("t1", "a");
    store.pushEShiftPick("t2", "b");
    expect(store.eShiftPicks).toEqual([
      { tableId: "t1", columnName: "a" },
      { tableId: "t2", columnName: "b" },
    ]);
  });

  it("REPLACES the existing pick when a different column is picked on the same table", () => {
    const store = new SchemaCanvasStore("conn-picks-3");
    store.pushEShiftPick("t1", "a");
    store.pushEShiftPick("t2", "x");
    // Second pick on t1 — should REPLACE `a`, not append.
    store.pushEShiftPick("t1", "b");
    expect(store.eShiftPicks).toEqual([
      { tableId: "t1", columnName: "b" },
      { tableId: "t2", columnName: "x" },
    ]);
  });

  it("toggles off when the exact same (table, column) is re-clicked", () => {
    const store = new SchemaCanvasStore("conn-picks-4");
    store.pushEShiftPick("t1", "a");
    store.pushEShiftPick("t1", "a");
    expect(store.eShiftPicks).toEqual([]);
  });

  it("never allows two picks on the same table across many rapid clicks", () => {
    const store = new SchemaCanvasStore("conn-picks-5");
    store.pushEShiftPick("t1", "a");
    store.pushEShiftPick("t1", "b");
    store.pushEShiftPick("t1", "c");
    store.pushEShiftPick("t1", "d");
    // Only the most-recent pick per table survives.
    expect(store.eShiftPicks).toEqual([{ tableId: "t1", columnName: "d" }]);
  });
});

describe("Create Joins Mode — autoPickGroupName", () => {
  it("returns empty string when there are no picks", () => {
    const store = new SchemaCanvasStore("conn-auto-1");
    expect(store.autoPickGroupName()).toBe("");
  });

  it("uses the shared column name when every pick has the same name", () => {
    const store = new SchemaCanvasStore("conn-auto-2");
    store.pushEShiftPick("t1", "customer_id");
    store.pushEShiftPick("t2", "customer_id");
    store.pushEShiftPick("t3", "customer_id");
    expect(store.autoPickGroupName()).toBe("customer_id");
  });

  it("falls back to the most common column name when picks differ", () => {
    const store = new SchemaCanvasStore("conn-auto-3");
    store.pushEShiftPick("t1", "customer_id");
    store.pushEShiftPick("t2", "customer_id");
    store.pushEShiftPick("t3", "cust_id");
    expect(store.autoPickGroupName()).toBe("customer_id");
  });

  it("uses the first-seen column name when there is no majority", () => {
    const store = new SchemaCanvasStore("conn-auto-4");
    store.pushEShiftPick("t1", "foo");
    store.pushEShiftPick("t2", "bar");
    // bar and foo tied at 1 each — first-seen (foo) wins.
    expect(store.autoPickGroupName()).toBe("foo");
  });
});

describe("Create Joins Mode — commitEShiftPicks", () => {
  function makeStoreWithTables() {
    const store = new SchemaCanvasStore("conn-commit");
    const a = store.addTable(candidate("public", "a", ["id", "name"]), {
      x: 0,
      y: 0,
    });
    const b = store.addTable(candidate("public", "b", ["id", "a_id"]), {
      x: 200,
      y: 0,
    });
    const c = store.addTable(candidate("public", "c", ["id", "a_id"]), {
      x: 400,
      y: 0,
    });
    return { store, a, b, c };
  }

  it("is a no-op when fewer than 2 picks exist", () => {
    const { store, a } = makeStoreWithTables();
    store.pushEShiftPick(a.id, "id");
    const joinsBefore = store.doc.joins.length;
    store.commitEShiftPicks();
    expect(store.doc.joins.length).toBe(joinsBefore);
  });

  it("creates N-1 joins in a star pattern from pick[0] to each peer", () => {
    const { store, a, b, c } = makeStoreWithTables();
    store.pushEShiftPick(a.id, "id");
    store.pushEShiftPick(b.id, "a_id");
    store.pushEShiftPick(c.id, "a_id");
    const joinsBefore = store.doc.joins.length;
    store.commitEShiftPicks();
    // 3 picks → 2 joins (a→b, a→c).
    expect(store.doc.joins.length - joinsBefore).toBe(2);
    const created = store.doc.joins.slice(joinsBefore);
    expect(created.every((j) => j.sourceTableId === a.id)).toBe(true);
    expect(created.map((j) => j.targetTableId).sort()).toEqual(
      [b.id, c.id].sort(),
    );
  });

  it("clears the pick set and exits Create Joins Mode after commit", () => {
    const { store, a, b } = makeStoreWithTables();
    store.enterPickMode();
    store.pushEShiftPick(a.id, "id");
    store.pushEShiftPick(b.id, "a_id");
    store.commitEShiftPicks();
    expect(store.eShiftPicks).toEqual([]);
    expect(store.pickModeActive).toBe(false);
  });

  it("applies groupName to every created join via joinGroupRenames", () => {
    const { store, a, b, c } = makeStoreWithTables();
    store.pushEShiftPick(a.id, "id");
    store.pushEShiftPick(b.id, "a_id");
    store.pushEShiftPick(c.id, "a_id");
    store.commitEShiftPicks("Account Key");
    const renames = store.doc.joinGroupRenames ?? {};
    // Every join added on this commit shares the same custom group label.
    const labels = new Set(Object.values(renames));
    expect(labels.has("Account Key")).toBe(true);
  });

  it("leaves joinGroupRenames untouched when no groupName supplied", () => {
    const { store, a, b } = makeStoreWithTables();
    store.pushEShiftPick(a.id, "id");
    store.pushEShiftPick(b.id, "a_id");
    const before = { ...(store.doc.joinGroupRenames ?? {}) };
    store.commitEShiftPicks();
    expect(store.doc.joinGroupRenames ?? {}).toEqual(before);
  });
});

describe("Create Joins Mode — undo covers CJM commit", () => {
  it("one Undo removes every join created by a single commitEShiftPicks batch", () => {
    const store = new SchemaCanvasStore("conn-undo-commit");
    const a = store.addTable(candidate("public", "a", ["id"]), { x: 0, y: 0 });
    const b = store.addTable(candidate("public", "b", ["a_id"]), {
      x: 200,
      y: 0,
    });
    const c = store.addTable(candidate("public", "c", ["a_id"]), {
      x: 400,
      y: 0,
    });
    const joinsBefore = store.doc.joins.length;
    store.pushEShiftPick(a.id, "id");
    store.pushEShiftPick(b.id, "a_id");
    store.pushEShiftPick(c.id, "a_id");
    store.commitEShiftPicks();
    // 2 joins created.
    expect(store.doc.joins.length).toBe(joinsBefore + 2);
    // One undo reverts the whole batch.
    store.undo();
    expect(store.doc.joins.length).toBe(joinsBefore);
  });
});

describe("addJoin — single-source-endpoint invariant", () => {
  // Amelia hit this: `reserve_employee.first_name` had two joins in one
  // group, one to `customer.fname` and one to `customer.lname`. A single
  // source endpoint can only participate in ONE join per target table.
  // Role-playing (SAME target column, DIFFERENT source columns) must
  // still be legal.

  function makePair() {
    const store = new SchemaCanvasStore("conn-invariant");
    const emp = store.addTable(
      candidate("public", "reserve_employee", ["first_name", "last_name"]),
      { x: 0, y: 0 },
    );
    const cust = store.addTable(
      candidate("public", "customer", ["fname", "lname"]),
      {
        x: 300,
        y: 0,
      },
    );
    return { store, emp, cust };
  }

  it("replaces the existing join when the same source endpoint is repointed to a different target column", () => {
    const { store, emp, cust } = makePair();
    store.addJoin({
      sourceTableId: emp.id,
      sourceColumnName: "first_name",
      targetTableId: cust.id,
      targetColumnName: "fname",
      kind: "inner",
    });
    store.addJoin({
      sourceTableId: emp.id,
      sourceColumnName: "first_name",
      targetTableId: cust.id,
      targetColumnName: "lname",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(1);
    expect(store.doc.joins[0].targetColumnName).toBe("lname");
    expect(store.lastJoinFeedback?.message).toMatch(/Replaced join/i);
    expect(store.lastJoinFeedback?.message).toContain("first_name");
    expect(store.lastJoinFeedback?.message).toContain("fname");
  });

  it("also replaces when the tables are swapped (source ↔ target in the incoming join)", () => {
    const { store, emp, cust } = makePair();
    // Existing: emp.first_name → customer.fname
    store.addJoin({
      sourceTableId: emp.id,
      sourceColumnName: "first_name",
      targetTableId: cust.id,
      targetColumnName: "fname",
      kind: "inner",
    });
    // Incoming, swapped: customer.lname → emp.first_name (same emp endpoint,
    // different customer column) — treat as ambiguous, replace.
    store.addJoin({
      sourceTableId: cust.id,
      sourceColumnName: "lname",
      targetTableId: emp.id,
      targetColumnName: "first_name",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(1);
    const only = store.doc.joins[0];
    expect(only.sourceTableId).toBe(cust.id);
    expect(only.sourceColumnName).toBe("lname");
    expect(only.targetTableId).toBe(emp.id);
    expect(only.targetColumnName).toBe("first_name");
  });

  it("preserves role-playing (different source columns joined to the same target column)", () => {
    const store = new SchemaCanvasStore("conn-role-play");
    const fact = store.addTable(
      candidate("public", "sales", ["order_date", "ship_date", "invoice_date"]),
      { x: 0, y: 0 },
    );
    const date = store.addTable(candidate("public", "date_dim", ["date_id"]), {
      x: 300,
      y: 0,
    });
    store.addJoin({
      sourceTableId: fact.id,
      sourceColumnName: "order_date",
      targetTableId: date.id,
      targetColumnName: "date_id",
      kind: "inner",
    });
    store.addJoin({
      sourceTableId: fact.id,
      sourceColumnName: "ship_date",
      targetTableId: date.id,
      targetColumnName: "date_id",
      kind: "inner",
    });
    store.addJoin({
      sourceTableId: fact.id,
      sourceColumnName: "invoice_date",
      targetTableId: date.id,
      targetColumnName: "date_id",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(3);
    expect(store.doc.joins.map((j) => j.sourceColumnName).sort()).toEqual([
      "invoice_date",
      "order_date",
      "ship_date",
    ]);
  });

  it("still dedupes an exact tuple duplicate (no-op replace)", () => {
    const { store, emp, cust } = makePair();
    const first = store.addJoin({
      sourceTableId: emp.id,
      sourceColumnName: "first_name",
      targetTableId: cust.id,
      targetColumnName: "fname",
      kind: "inner",
    });
    const second = store.addJoin({
      sourceTableId: emp.id,
      sourceColumnName: "first_name",
      targetTableId: cust.id,
      targetColumnName: "fname",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(1);
    // It's the second call's id that survives (existing tuple dedupe replaces).
    expect(store.doc.joins[0].id).toBe(second.id);
    expect(first.id).not.toBe(second.id);
  });
});

// ── Undo primitives (issue #1180, bucket 1) ─────────────────────────────────
// The undo model is a single-level swap: base mutations (addTable, addJoin,
// removeTable, removeJoin, removeJoins) snapshot the PRE-mutation doc into
// `previousDoc` via maybeSnapshotForUndo, unless a withUndoBatch is active — in
// which case only the batch's entry snapshot is kept so N mutations collapse to
// one Undo step. undo() swaps previousDoc↔doc so it doubles as redo. Amelia's
// #1144 pushed the snapshot into base mutations; this locks the invariants.
describe("SchemaCanvasStore — undo primitives (#1180)", () => {
  function storeWith(...names: string[]): SchemaCanvasStore {
    const store = new SchemaCanvasStore("conn-undo");
    names.forEach((n, i) =>
      store.addTable(candidate("public", n, ["id"]), { x: i * 10, y: 0 }),
    );
    return store;
  }

  it("a base mutation is a single undoable step", () => {
    const store = new SchemaCanvasStore("conn-undo");
    store.addTable(candidate("public", "t1", ["id"]), { x: 0, y: 0 });
    expect(store.doc.tables).toHaveLength(1);

    store.undo();
    expect(store.doc.tables).toHaveLength(0);
  });

  it("undo is single-level — reverts only the most recent action", () => {
    const store = storeWith("t1", "t2");
    expect(store.doc.tables).toHaveLength(2);

    store.undo(); // reverts the t2 add only
    expect(store.doc.tables.map((t) => t.name)).toEqual(["t1"]);
  });

  it("withUndoBatch collapses N mutations into ONE undo step", () => {
    const store = new SchemaCanvasStore("conn-undo");
    store.withUndoBatch(() => {
      store.addTable(candidate("public", "t1", ["id"]), { x: 0, y: 0 });
      store.addTable(candidate("public", "t2", ["id"]), { x: 10, y: 0 });
      store.addTable(candidate("public", "t3", ["id"]), { x: 20, y: 0 });
    });
    expect(store.doc.tables).toHaveLength(3);

    store.undo(); // one snapshot at batch entry (empty) → all three revert
    expect(store.doc.tables).toHaveLength(0);
  });

  it("nested withUndoBatch only snapshots at the outermost entry", () => {
    const store = storeWith("t0"); // pre-existing table before the batch
    store.withUndoBatch(() => {
      store.addTable(candidate("public", "t1", ["id"]), { x: 10, y: 0 });
      store.withUndoBatch(() => {
        store.addTable(candidate("public", "t2", ["id"]), { x: 20, y: 0 });
      });
    });
    expect(store.doc.tables).toHaveLength(3);

    store.undo(); // reverts to the outer-batch entry state (just t0)
    expect(store.doc.tables.map((t) => t.name)).toEqual(["t0"]);
  });

  it("undo doubles as redo — a second undo re-applies", () => {
    const store = new SchemaCanvasStore("conn-undo");
    store.addTable(candidate("public", "t1", ["id"]), { x: 0, y: 0 });

    store.undo(); // remove t1
    expect(store.doc.tables).toHaveLength(0);
    store.undo(); // swap back — redo
    expect(store.doc.tables.map((t) => t.name)).toEqual(["t1"]);
  });

  it("clearPreviousDoc drops the pending snapshot so undo is a no-op", () => {
    const store = new SchemaCanvasStore("conn-undo");
    store.addTable(candidate("public", "t1", ["id"]), { x: 0, y: 0 });

    store.clearPreviousDoc();
    store.undo();
    expect(store.doc.tables).toHaveLength(1);
  });

  it("undo is a no-op on a fresh store with nothing to revert", () => {
    const store = new SchemaCanvasStore("conn-undo");
    expect(() => store.undo()).not.toThrow();
    expect(store.doc.tables).toHaveLength(0);
  });

  it("removeJoins under a batch reverts in one undo", () => {
    const store = storeWith("a", "b");
    const [a, b] = store.doc.tables;
    store.addJoin({
      sourceTableId: a.id,
      sourceColumnName: "id",
      targetTableId: b.id,
      targetColumnName: "id",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(1);

    store.removeJoins([store.doc.joins[0].id]);
    expect(store.doc.joins).toHaveLength(0);

    store.undo(); // the join comes back
    expect(store.doc.joins).toHaveLength(1);
  });
});

// ── Table lifecycle (issue #1180, bucket 3) ─────────────────────────────────
describe("SchemaCanvasStore — table lifecycle (#1180)", () => {
  function twoTables(): { store: SchemaCanvasStore; a: string; b: string } {
    const store = new SchemaCanvasStore("conn-tbl");
    const a = store.addTable(candidate("public", "a", ["id", "k"]), {
      x: 0,
      y: 0,
    });
    const b = store.addTable(candidate("public", "b", ["id", "k"]), {
      x: 100,
      y: 0,
    });
    return { store, a: a.id, b: b.id };
  }

  it("removeTable drops the table and cascades its joins", () => {
    const { store, a, b } = twoTables();
    store.addJoin({
      sourceTableId: a,
      sourceColumnName: "k",
      targetTableId: b,
      targetColumnName: "k",
      kind: "inner",
    });
    expect(store.doc.joins).toHaveLength(1);

    store.removeTable(a);
    expect(store.doc.tables.map((t) => t.name)).toEqual(["b"]);
    expect(store.doc.joins).toHaveLength(0); // join referencing `a` cascaded
  });

  it("removeTable clears the selection when the removed table was selected", () => {
    const { store, a } = twoTables();
    store.selectedTableId = a;
    store.removeTable(a);
    expect(store.selectedTableId).toBeNull();
  });

  it("moveTable updates position and is a no-op for an unknown id", () => {
    const { store, a } = twoTables();
    store.moveTable(a, { x: 42, y: 99 });
    expect(store.doc.tables.find((t) => t.id === a)?.position).toEqual({
      x: 42,
      y: 99,
    });
    expect(() => store.moveTable("nope", { x: 1, y: 1 })).not.toThrow();
  });

  it("setTableRole enforces a single fact — promoting one demotes the prior fact", () => {
    const { store, a, b } = twoTables();
    store.setTableRole(a, "fact");
    expect(store.doc.tables.find((t) => t.id === a)?.role).toBe("fact");

    store.setTableRole(b, "fact");
    expect(store.doc.tables.find((t) => t.id === b)?.role).toBe("fact");
    expect(store.doc.tables.find((t) => t.id === a)?.role).toBe("dimension");
  });

  it("toggleTableCollapsed cycles undefined ↔ true (never the force-show-all state)", () => {
    const { store, a } = twoTables();
    const t = () => store.doc.tables.find((x) => x.id === a)!;
    expect(t().collapsed).toBeUndefined();
    store.toggleTableCollapsed(a);
    expect(t().collapsed).toBe(true);
    store.toggleTableCollapsed(a);
    expect(t().collapsed).toBeUndefined();
  });

  it("expand/collapse-all + resetCollapseOverrides drive every table together", () => {
    const { store } = twoTables();
    store.collapseAllTables();
    expect(store.doc.tables.every((t) => t.collapsed === true)).toBe(true);
    store.expandAllTables();
    expect(store.doc.tables.every((t) => t.collapsed === false)).toBe(true);
    store.resetCollapseOverrides();
    expect(store.doc.tables.every((t) => t.collapsed === undefined)).toBe(true);
  });
});

// ── Join lifecycle (issue #1180, bucket 2) ──────────────────────────────────
describe("SchemaCanvasStore — join lifecycle (#1180)", () => {
  function joined(): {
    store: SchemaCanvasStore;
    a: string;
    b: string;
    joinId: string;
  } {
    const store = new SchemaCanvasStore("conn-join");
    const a = store.addTable(candidate("public", "a", ["id", "k"]), {
      x: 0,
      y: 0,
    });
    const b = store.addTable(candidate("public", "b", ["id", "k"]), {
      x: 100,
      y: 0,
    });
    store.addJoin({
      sourceTableId: a.id,
      sourceColumnName: "k",
      targetTableId: b.id,
      targetColumnName: "k",
      kind: "inner",
    });
    return { store, a: a.id, b: b.id, joinId: store.doc.joins[0].id };
  }

  it("removeJoin drops a physical join and clears its selection", () => {
    const { store, joinId } = joined();
    store.selectedJoinId = joinId;
    store.removeJoin(joinId);
    expect(store.doc.joins).toHaveLength(0);
    expect(store.selectedJoinId).toBeNull();
  });

  it("removeJoin is a no-op for cube-link joins (owned by the MeasureGroup FK)", () => {
    const { store, joinId } = joined();
    store.doc.joins[0].origin = "cube-link";
    store.removeJoin(joinId);
    expect(store.doc.joins).toHaveLength(1); // untouched
  });

  it("removeAllJoins wipes joins + resets related state; no-op when already empty", () => {
    const { store } = joined();
    store.removeAllJoins();
    expect(store.doc.joins).toHaveLength(0);
    expect(store.selectedJoinId).toBeNull();
    expect(() => store.removeAllJoins()).not.toThrow();
  });

  it("setJoinKind changes the kind in place", () => {
    const { store, joinId } = joined();
    store.setJoinKind(joinId, "left");
    expect(store.doc.joins[0].kind).toBe("left");
  });

  it("renameJoinGroup + joinGroupLabelFor round-trip; empty label reverts to the key", () => {
    const { store } = joined();
    const j = store.doc.joins[0];
    const key = store.joinGroupLabelFor(j); // canonical key (no rename yet)

    store.renameJoinGroup(key, "Geography");
    expect(store.joinGroupLabelFor(j)).toBe("Geography");

    store.renameJoinGroup(key, "   ");
    expect(store.joinGroupLabelFor(j)).toBe(key);
  });

  it("createGroup + assignTableToGroup wire a table into a group and back out", () => {
    const { store, a } = joined();
    const g = store.createGroup("Dims");
    expect(store.doc.groups.map((x) => x.label)).toContain("Dims");

    store.assignTableToGroup(a, g.id);
    expect(store.doc.tables.find((t) => t.id === a)?.groupId).toBe(g.id);
    store.assignTableToGroup(a, null);
    expect(store.doc.tables.find((t) => t.id === a)?.groupId).toBeNull();
  });

  it("linkSimilarOnCanvas joins every other table sharing the column (one undo step)", () => {
    const store = new SchemaCanvasStore("conn-link");
    const origin = store.addTable(
      candidate("public", "fact", ["sales_id", "cust_id"]),
      {
        x: 0,
        y: 0,
      },
    );
    store.addTable(candidate("public", "d1", ["cust_id"]), { x: 100, y: 0 });
    store.addTable(candidate("public", "d2", ["cust_id"]), { x: 200, y: 0 });
    store.addTable(candidate("public", "other", ["zzz"]), { x: 300, y: 0 });

    const added = store.linkSimilarOnCanvas(origin.id, "cust_id");
    expect(added).toBe(2); // d1 + d2, not `other`
    expect(store.doc.joins).toHaveLength(2);

    store.undo(); // one batch → all links revert together
    expect(store.doc.joins).toHaveLength(0);
  });

  it("tryColumnClickJoin is highlight-only — it never creates a join", () => {
    const { store, a } = joined();
    const before = store.doc.joins.length;
    store.tryColumnClickJoin(a, "k");
    expect(store.highlightedColumn).toBe("k");
    expect(store.highlightOriginTableId).toBe(a);
    expect(store.doc.joins).toHaveLength(before); // no join created
    // Clicking the same column again clears the highlight.
    store.tryColumnClickJoin(a, "k");
    expect(store.highlightedColumn).toBeNull();
  });
});

// ── Workbench: dimensions / hierarchies / levels / attributes (#1180 bucket 4)
describe("SchemaCanvasStore — dimensions / hierarchies / levels / attributes (#1180)", () => {
  it("addDimension names uniquely, sets primaryKeyTableId, and stays hierarchy-less without a column seed", () => {
    const store = new SchemaCanvasStore("conn-dim");
    const d1 = store.addDimension({ name: "Customer", tableId: "t1" });
    const d2 = store.addDimension({ name: "Customer" });
    expect(d1.name).toBe("Customer");
    expect(d1.primaryKeyTableId).toBe("t1");
    expect(d1.hierarchies).toHaveLength(0);
    expect(d2.name).not.toBe("Customer"); // deduped
  });

  it("addDimension with a column seed auto-creates one hierarchy + level", () => {
    const store = new SchemaCanvasStore("conn-dim");
    const d = store.addDimension({
      name: "Time",
      tableId: "t1",
      columnName: "the_year",
    });
    expect(d.hierarchies).toHaveLength(1);
    expect(d.hierarchies[0].levels[0].columnName).toBe("the_year");
  });

  it("updateDimension patches + removeDimension drops it", () => {
    const store = new SchemaCanvasStore("conn-dim");
    const d = store.addDimension({ name: "D" });
    store.updateDimension(d.id, { caption: "Display D" });
    expect(store.dimensions.find((x) => x.id === d.id)?.caption).toBe(
      "Display D",
    );
    store.removeDimension(d.id);
    expect(store.dimensions).toHaveLength(0);
  });

  it("hierarchy + level lifecycle: add, dedupe same column, move, update, remove", () => {
    const store = new SchemaCanvasStore("conn-dim");
    const d = store.addDimension({ name: "Geo" });
    const h = store.addHierarchy(d.id, "Region")!;
    expect(h).not.toBeNull();

    const l1 = store.addLevel(d.id, h.id, {
      tableId: "t",
      columnName: "country",
    });
    const dup = store.addLevel(d.id, h.id, {
      tableId: "t",
      columnName: "country",
    });
    expect(dup).toBe(l1); // same column returns the existing level, no duplicate
    store.addLevel(d.id, h.id, { tableId: "t", columnName: "city" });
    const levels = () => store.dimensions[0].hierarchies[0].levels;
    expect(levels().map((l) => l.columnName)).toEqual(["country", "city"]);

    store.moveLevel(d.id, h.id, levels()[1].id, -1); // city up
    expect(levels().map((l) => l.columnName)).toEqual(["city", "country"]);

    store.updateLevel(d.id, h.id, levels()[0].id, { caption: "Municipality" });
    expect(levels()[0].caption).toBe("Municipality");

    store.removeLevel(d.id, h.id, levels()[0].id);
    expect(levels().map((l) => l.columnName)).toEqual(["country"]);

    store.removeHierarchy(d.id, h.id);
    expect(store.dimensions[0].hierarchies).toHaveLength(0);
  });

  it("attribute lifecycle: add (idempotent), update, setAttributes replace, remove", () => {
    const store = new SchemaCanvasStore("conn-dim");
    const d = store.addDimension({ name: "Product" });
    store.addAttribute(d.id, "t", "name");
    store.addAttribute(d.id, "t", "name"); // no-op
    expect(store.dimensions[0].attributes).toHaveLength(1);

    store.updateAttribute(d.id, "t", "name", { nameColumn: "display_name" });
    expect(store.dimensions[0].attributes?.[0].nameColumn).toBe("display_name");

    store.setAttributes(d.id, [
      { tableId: "t", columnName: "a" },
      { tableId: "t", columnName: "b" },
    ]);
    expect(store.dimensions[0].attributes?.map((a) => a.columnName)).toEqual([
      "a",
      "b",
    ]);

    store.removeAttribute(d.id, "t", "a");
    expect(store.dimensions[0].attributes?.map((a) => a.columnName)).toEqual([
      "b",
    ]);
  });
});

// ── Workbench: measures (#1180 bucket 4) ─────────────────────────────────────
describe("SchemaCanvasStore — measures (#1180)", () => {
  it("addMeasure defaults to sum and only carries percentile for a percentile aggregator", () => {
    const store = new SchemaCanvasStore("conn-meas");
    const sum = store.addMeasure({ tableId: "f", columnName: "amount" });
    expect(sum.aggregator).toBe("sum");
    expect(sum.percentile).toBeUndefined();

    const p90 = store.addMeasure({
      tableId: "f",
      columnName: "latency",
      aggregator: "percentile",
      percentile: 90,
    });
    expect(p90.percentile).toBe(90);
  });

  it("updateMeasure patches + removeMeasure drops it", () => {
    const store = new SchemaCanvasStore("conn-meas");
    const m = store.addMeasure({ tableId: "f", columnName: "amount" });
    store.updateMeasure(m.id, { name: "Revenue" });
    expect(store.measures[0].name).toBe("Revenue");
    store.removeMeasure(m.id);
    expect(store.measures).toHaveLength(0);
  });
});

// ── Workbench: cubes / measure groups / calcs / time-calcs (#1180 bucket 4) ──
describe("SchemaCanvasStore — cubes / groups / calcs / timeCalcs (#1180)", () => {
  function cubeWithGroup() {
    const store = new SchemaCanvasStore("conn-cube");
    const cube = store.addCube({ name: "Sales" });
    const mg = store.addMeasureGroup(cube.id, {
      name: "Facts",
      factTableId: "f",
    })!;
    return { store, cubeId: cube.id, mgId: mg.id };
  }

  it("renameCube + removeCube", () => {
    const { store, cubeId } = cubeWithGroup();
    store.renameCube(cubeId, "Orders");
    expect(store.cubes[0].name).toBe("Orders");
    store.removeCube(cubeId);
    expect(store.cubes).toHaveLength(0);
  });

  it("measure-group edits: rename, setMeasureColumns, setMeasureGroupFactTable, remove", () => {
    const { store, cubeId, mgId } = cubeWithGroup();
    store.renameMeasureGroup(cubeId, mgId, "Renamed");
    store.setMeasureColumns(cubeId, mgId, ["amount", "qty"]);
    store.setMeasureGroupFactTable(cubeId, mgId, "fact2");
    const mg = store.cubes[0].measureGroups[0];
    expect(mg.name).toBe("Renamed");
    expect(mg.measureColumns).toEqual(["amount", "qty"]);
    expect(mg.factTableId).toBe("fact2");

    store.removeMeasureGroup(cubeId, mgId);
    expect(store.cubes[0].measureGroups).toHaveLength(0);
  });

  it("dim-link upsert + removeMeasureGroupDimLink", () => {
    const { store, cubeId, mgId } = cubeWithGroup();
    store.setMeasureGroupDimLink(cubeId, mgId, {
      dimensionId: "d1",
      foreignKeyColumn: "a",
    });
    store.setMeasureGroupDimLink(cubeId, mgId, {
      dimensionId: "d2",
      foreignKeyColumn: "b",
    });
    expect(store.cubes[0].measureGroups[0].dimensionLinks).toHaveLength(2);
    store.removeMeasureGroupDimLink(cubeId, mgId, "d1");
    expect(
      store.cubes[0].measureGroups[0].dimensionLinks?.map((l) => l.dimensionId),
    ).toEqual(["d2"]);
  });

  it("addCalc + removeCalc", () => {
    const { store, cubeId } = cubeWithGroup();
    const calc = store.addCalc(cubeId, { name: "Margin", formula: "[a]-[b]" })!;
    expect(store.cubes[0].calcs.map((c) => c.name)).toEqual(["Margin"]);
    store.removeCalc(cubeId, calc.id);
    expect(store.cubes[0].calcs).toHaveLength(0);
  });

  it("timeCalc lifecycle: add, update, remove", () => {
    const { store, cubeId } = cubeWithGroup();
    const tc = store.addTimeCalc(cubeId, {
      name: "Rev YoY",
      type: "yoy",
      measure: "Revenue",
    })!;
    expect(store.cubes[0].timeCalcs?.[0].type).toBe("yoy");
    store.updateTimeCalc(cubeId, tc.id, {
      type: "rolling",
      window: 3,
      function: "avg",
    });
    expect(store.cubes[0].timeCalcs?.[0]).toMatchObject({
      type: "rolling",
      window: 3,
    });
    store.removeTimeCalc(cubeId, tc.id);
    expect(store.cubes[0].timeCalcs).toHaveLength(0);
  });

  it("setCubes preserves cube-scoped timeCalcs by id (workbench write-back cannot wipe them)", () => {
    const { store, cubeId } = cubeWithGroup();
    store.addTimeCalc(cubeId, {
      name: "Rev YoY",
      type: "yoy",
      measure: "Revenue",
    });
    // Simulate the workbench persisting via cubeToDoc (which drops timeCalcs).
    store.setCubes([
      { id: cubeId, name: "Sales", measureGroups: [], calcs: [] },
    ]);
    expect(store.cubes[0].timeCalcs).toHaveLength(1); // preserved by id
    expect(store.cubes[0].timeCalcs?.[0].name).toBe("Rev YoY");
  });
});

// ── Focus + interaction (issue #1180, bucket 6) ─────────────────────────────
describe("SchemaCanvasStore — focus + interaction (#1180)", () => {
  function joined() {
    const store = new SchemaCanvasStore("conn-focus");
    const a = store.addTable(candidate("public", "a", ["k"]), { x: 0, y: 0 });
    const b = store.addTable(candidate("public", "b", ["k"]), { x: 100, y: 0 });
    store.addJoin({
      sourceTableId: a.id,
      sourceColumnName: "k",
      targetTableId: b.id,
      targetColumnName: "k",
      kind: "inner",
    });
    return { store, a: a.id, b: b.id, joinId: store.doc.joins[0].id };
  }

  it("focusTableNode toggles table focus and drives tableIdsInFocus / joinIdsInFocus", () => {
    const { store, a, b, joinId } = joined();
    store.focusTableNode(a);
    expect(store.focusKind).toBe("table");
    expect([...store.tableIdsInFocus()].sort()).toEqual([a, b].sort()); // a + its neighbour
    expect([...store.joinIdsInFocus()]).toEqual([joinId]);

    store.focusTableNode(a); // toggle off
    expect(store.focusKind).toBe("none");
    expect(store.tableIdsInFocus().size).toBe(0);
  });

  it("focusGroup focuses by label and turns on column highlighting", () => {
    const { store } = joined();
    const label = store.joinGroupLabelFor(store.doc.joins[0]);
    store.focusGroup(label);
    expect(store.focusKind).toBe("group");
    expect(store.focusKey).toBe(label);
    expect(store.highlightFocusedColumns).toBe(true);
    expect(store.joinIdsInFocus().size).toBe(1);
  });

  it("clearFocus + clearAllInteractions reset transient state (doc untouched)", () => {
    const { store, a } = joined();
    store.focusTableNode(a);
    store.highlightColumn("k");
    store.selectedTableId = a;
    store.clearAllInteractions();
    expect(store.focusKind).toBe("none");
    expect(store.highlightedColumn).toBeNull();
    expect(store.selectedTableId).toBeNull();
    expect(store.doc.tables).toHaveLength(2); // doc intact
  });

  it("highlightColumn toggles the name highlight + sidebar query", () => {
    const { store } = joined();
    store.highlightColumn("k");
    expect(store.highlightedColumn).toBe("k");
    expect(store.sidebarQuery).toBe("k");
    store.highlightColumn("k");
    expect(store.highlightedColumn).toBeNull();
  });
});

// ── Pathfinder (issue #1180, bucket 5) ──────────────────────────────────────
describe("SchemaCanvasStore — pathfinder (#1180)", () => {
  it("arm/setEndpoint/clear manage the pathfinder slots + result", () => {
    const store = new SchemaCanvasStore("conn-pf");
    store.armPathfinder("start");
    expect(store.pathfinderArmed).toBe("start");
    store.armPathfinder("start"); // toggle off
    expect(store.pathfinderArmed).toBeNull();

    store.setPathfinderEndpoint("start", { tableId: "a", columnName: "k" });
    expect(store.pathfinderStart).toEqual({ tableId: "a", columnName: "k" });
    store.clearPathfinder();
    expect(store.pathfinderStart).toBeNull();
    expect(store.pathfinderResult).toBeNull();
  });

  it("findPath walks existing joins A→B→C with no gaps", () => {
    const store = new SchemaCanvasStore("conn-pf");
    const a = store.addTable(candidate("public", "a", ["k"]), { x: 0, y: 0 });
    const b = store.addTable(candidate("public", "b", ["k", "m"]), {
      x: 100,
      y: 0,
    });
    const c = store.addTable(candidate("public", "c", ["m"]), { x: 200, y: 0 });
    store.addJoin({
      sourceTableId: a.id,
      sourceColumnName: "k",
      targetTableId: b.id,
      targetColumnName: "k",
      kind: "inner",
    });
    store.addJoin({
      sourceTableId: b.id,
      sourceColumnName: "m",
      targetTableId: c.id,
      targetColumnName: "m",
      kind: "inner",
    });

    store.setPathfinderEndpoint("start", { tableId: a.id, columnName: "k" });
    store.setPathfinderEndpoint("end", { tableId: c.id, columnName: "m" });
    store.findPath();
    expect(store.pathfinderResult?.hasGaps).toBe(false);
    expect(store.pathfinderResult?.steps).toHaveLength(2);
  });

  it("findPath proposes a virtual edge across a gap; applyPathfinderJoins commits it", () => {
    const store = new SchemaCanvasStore("conn-pf");
    const a = store.addTable(candidate("public", "a", ["shared"]), {
      x: 0,
      y: 0,
    });
    const c = store.addTable(candidate("public", "c", ["shared"]), {
      x: 200,
      y: 0,
    });
    store.setPathfinderEndpoint("start", {
      tableId: a.id,
      columnName: "shared",
    });
    store.setPathfinderEndpoint("end", { tableId: c.id, columnName: "shared" });
    store.findPath();
    expect(store.pathfinderResult?.hasGaps).toBe(true);
    expect(store.doc.joins).toHaveLength(0);

    const added = store.applyPathfinderJoins();
    expect(added).toBe(1);
    expect(store.doc.joins).toHaveLength(1);
  });
});

// ── Session lifecycle (issue #1180, bucket 7) ───────────────────────────────
describe("SchemaCanvasStore — session lifecycle (#1180)", () => {
  it("setLabel writes the cube label", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.setLabel("Q3 Revenue");
    expect(store.doc.label).toBe("Q3 Revenue");
  });

  it("setMode + setDefaultLayoutMode update the fields", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.setMode("workbench");
    expect(store.mode).toBe("workbench");
    store.setDefaultLayoutMode("star");
    expect(store.defaultLayoutMode).toBe("star");
  });

  it("switchConnection swaps the doc and resets UI state; same-connection is a no-op", () => {
    const store = new SchemaCanvasStore("conn-A");
    store.addTable(candidate("public", "t", ["id"]), { x: 0, y: 0 });
    store.selectedTableId = store.doc.tables[0].id;

    store.switchConnection("conn-B");
    expect(store.connectionId).toBe("conn-B");
    expect(store.doc.tables).toHaveLength(0); // fresh doc for conn-B
    expect(store.selectedTableId).toBeNull();
  });

  it("resetCanvas blanks the doc for the current connection", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.addTable(candidate("public", "t", ["id"]), { x: 0, y: 0 });
    store.resetCanvas();
    expect(store.doc.tables).toHaveLength(0);
    expect(store.connectionId).toBe("conn-sess");
  });

  it("loadDoc replaces the doc and is undoable", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.addTable(candidate("public", "orig", ["id"]), { x: 0, y: 0 });
    const snapshot = JSON.parse(JSON.stringify(store.doc));
    store.loadDoc({ ...snapshot, label: "Imported", tables: [] });
    expect(store.doc.label).toBe("Imported");
    expect(store.doc.tables).toHaveLength(0);
    store.undo();
    expect(store.doc.tables.map((t) => t.name)).toEqual(["orig"]);
  });

  it("setSidebarQueryFromUser sets the query and clears the click highlight", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.highlightColumn("k");
    store.setSidebarQueryFromUser("cust");
    expect(store.sidebarQuery).toBe("cust");
    expect(store.highlightedColumn).toBeNull();
  });

  it("togglePickMode / exitPickMode / cancelEShift drive pick-mode state", () => {
    const store = new SchemaCanvasStore("conn-sess");
    store.togglePickMode();
    expect(store.pickModeActive).toBe(true);
    store.exitPickMode();
    expect(store.pickModeActive).toBe(false);
    store.togglePickMode();
    store.cancelEShift();
    expect(store.pickModeActive).toBe(false);
    expect(store.eShiftPicks).toHaveLength(0);
  });
});

// ── Workbench working set (issue #1180, bucket 8) ───────────────────────────
describe("SchemaCanvasStore — workbench working set (#1180)", () => {
  it("addToWorkbench (idempotent) / removeFromWorkbench / clearWorkbench", () => {
    const store = new SchemaCanvasStore("conn-wb");
    const t = store.addTable(candidate("public", "t", ["id"]), { x: 0, y: 0 });
    store.addToWorkbench(t.id);
    store.addToWorkbench(t.id); // no-op
    expect(store.workbenchTables.map((x) => x.name)).toEqual(["t"]);

    store.removeFromWorkbench(t.id);
    expect(store.workbenchTables).toHaveLength(0);

    store.addToWorkbench(t.id);
    store.clearWorkbench();
    expect(store.workbenchTables).toHaveLength(0);
  });
});
