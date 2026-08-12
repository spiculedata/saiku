import { describe, expect, it } from "vitest";
import { treeActions } from "./treeActions.svelte";

/**
 * saiku#1095 — the cube-tree → QueryCanvas intent bridge. The tree emits
 * Top-N / Sort requests here; QueryCanvas consumes them and opens its
 * existing axis modals. `seq` must make every request distinct so repeated
 * clicks on the same node re-trigger the consuming effect.
 */
describe("treeActions store", () => {
  it("opens a level-launched request (no measure pre-target)", () => {
    treeActions.open("topcount");
    expect(treeActions.request?.kind).toBe("topcount");
    expect(treeActions.request?.measureUniqueName).toBeUndefined();
    treeActions.clear();
  });

  it("opens a measure-launched request carrying the unique name", () => {
    treeActions.open("sort", "[Measures].[Store Sales]");
    expect(treeActions.request?.kind).toBe("sort");
    expect(treeActions.request?.measureUniqueName).toBe("[Measures].[Store Sales]");
    treeActions.clear();
  });

  it("repeat identical requests get distinct seq values", () => {
    treeActions.open("topcount", "[Measures].[Unit Sales]");
    const first = treeActions.request?.seq;
    treeActions.open("topcount", "[Measures].[Unit Sales]");
    const second = treeActions.request?.seq;
    expect(first).toBeDefined();
    expect(second).toBeDefined();
    expect(second).not.toBe(first);
    treeActions.clear();
  });

  it("clear nulls the request", () => {
    treeActions.open("sort");
    treeActions.clear();
    expect(treeActions.request).toBeNull();
  });
});
