/*
 * Unit tests for the pure half of the reset-filters flow (issue #927).
 *
 * Covers: snapshot capture, diff detection, restore semantics for both
 * saved widgets and in-session-added widgets, and referential identity
 * preservation when nothing would change.
 */

import { describe, expect, it } from "vitest";

import type { PanelFilter } from "$lib/api/dashboards";
import {
  panelDiffersFromDefaults,
  restoreMembersToDefaults,
  snapshotPanelDefaults,
  type SavedDefaultMembers,
} from "./filterDefaults";

/** Compact constructor — most tests vary only id + members. */
function w(id: string, members: string[]): PanelFilter {
  return {
    id,
    widget: "single-select",
    dimension: "Time",
    hierarchy: "Time",
    level: "Year",
    members,
  };
}

describe("snapshotPanelDefaults", () => {
  it("returns an empty index for an undefined panel", () => {
    expect(snapshotPanelDefaults(undefined)).toEqual({});
  });

  it("returns an empty index for an empty panel", () => {
    expect(snapshotPanelDefaults([])).toEqual({});
  });

  it("captures members[] keyed by widget id", () => {
    const snap = snapshotPanelDefaults([w("year", ["[Time].[2024]"]), w("region", [])]);
    expect(snap).toEqual({ year: ["[Time].[2024]"], region: [] });
  });

  it("deep-clones members[] so later mutations on the source don't leak in", () => {
    const original = ["[Time].[2024]"];
    const snap = snapshotPanelDefaults([w("year", original)]);
    original.push("[Time].[2025]");
    expect(snap.year).toEqual(["[Time].[2024]"]);
  });

  it("treats a widget with undefined members[] as the empty default", () => {
    // PanelFilter.members is typed as required string[] but the migration
    // path can leave it undefined briefly; snapshot must coerce.
    const widget = { ...w("year", []), members: undefined as unknown as string[] };
    const snap = snapshotPanelDefaults([widget]);
    expect(snap.year).toEqual([]);
  });
});

describe("panelDiffersFromDefaults", () => {
  it("returns false for an undefined or empty panel", () => {
    expect(panelDiffersFromDefaults(undefined, {})).toBe(false);
    expect(panelDiffersFromDefaults([], {})).toBe(false);
  });

  it("returns false when every widget matches its saved default", () => {
    const defaults: SavedDefaultMembers = { year: ["[Time].[2024]"], region: [] };
    const cur = [w("year", ["[Time].[2024]"]), w("region", [])];
    expect(panelDiffersFromDefaults(cur, defaults)).toBe(false);
  });

  it("returns true when a widget's members differ from its default", () => {
    const defaults: SavedDefaultMembers = { year: ["[Time].[2024]"] };
    const cur = [w("year", ["[Time].[2025]"])];
    expect(panelDiffersFromDefaults(cur, defaults)).toBe(true);
  });

  it("returns true for an in-session widget (not in snapshot) carrying members", () => {
    const cur = [w("new", ["[Region].[EU]"])];
    expect(panelDiffersFromDefaults(cur, {})).toBe(true);
  });

  it("returns false for an in-session widget with empty members (already at default)", () => {
    const cur = [w("new", [])];
    expect(panelDiffersFromDefaults(cur, {})).toBe(false);
  });

  it("treats member order as significant — [A,B] differs from [B,A]", () => {
    const defaults: SavedDefaultMembers = { x: ["a", "b"] };
    const cur = [w("x", ["b", "a"])];
    expect(panelDiffersFromDefaults(cur, defaults)).toBe(true);
  });
});

describe("restoreMembersToDefaults", () => {
  it("returns the same array reference when nothing would change", () => {
    const defaults: SavedDefaultMembers = { year: ["[Time].[2024]"] };
    const panel = [w("year", ["[Time].[2024]"])];
    expect(restoreMembersToDefaults(panel, defaults)).toBe(panel);
  });

  it("restores a widget's members[] to its saved default", () => {
    const defaults: SavedDefaultMembers = { year: ["[Time].[2024]"] };
    const panel = [w("year", ["[Time].[2025]"])];
    const next = restoreMembersToDefaults(panel, defaults);
    expect(next[0].members).toEqual(["[Time].[2024]"]);
  });

  it("clears in-session widgets (not in snapshot) to empty members[]", () => {
    const panel = [w("new", ["[Region].[EU]"])];
    const next = restoreMembersToDefaults(panel, {});
    expect(next[0].members).toEqual([]);
  });

  it("preserves widget identity on restore (id, widget type, cube, dim/hier/level)", () => {
    const defaults: SavedDefaultMembers = { x: [] };
    const panel: PanelFilter[] = [
      {
        id: "x",
        widget: "multi-select",
        cube: { connectionName: "c", catalog: "C", schema: "S", cubeName: "Sales" },
        dimension: "Region",
        hierarchy: "Geo",
        level: "Country",
        members: ["[Region].[EU]"],
      },
    ];
    const next = restoreMembersToDefaults(panel, defaults);
    expect(next[0].id).toBe("x");
    expect(next[0].widget).toBe("multi-select");
    expect(next[0].cube).toEqual({ connectionName: "c", catalog: "C", schema: "S", cubeName: "Sales" });
    expect(next[0].dimension).toBe("Region");
    expect(next[0].hierarchy).toBe("Geo");
    expect(next[0].level).toBe("Country");
  });

  it("returns a new array (not mutated) when any widget changes; source untouched", () => {
    const defaults: SavedDefaultMembers = { x: ["a"] };
    const panel = [w("x", ["b"])];
    const next = restoreMembersToDefaults(panel, defaults);
    expect(next).not.toBe(panel);
    expect(panel[0].members).toEqual(["b"]);
  });

  it("deep-clones restored members[] so post-restore mutations don't leak into the snapshot", () => {
    const defaults: SavedDefaultMembers = { x: ["a"] };
    const panel = [w("x", ["b"])];
    const next = restoreMembersToDefaults(panel, defaults);
    next[0].members.push("c");
    expect(defaults.x).toEqual(["a"]);
  });
});
