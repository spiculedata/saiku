/*
 * Unit tests for the pure cascading single-select filter logic
 * (issue #922).
 *
 * Covers: depth bounds (clampDepth / effectiveDepth); which dropdowns
 * are visible given selections at levels [0..k]; selecting "All" at
 * level k truncating deeper selections; computing the emitted leaf
 * member (deepest non-"All", or none); the parent that feeds the next
 * dropdown; and idempotent re-selection.
 */

import { describe, expect, it } from "vitest";

import {
  applySelection,
  clampDepth,
  effectiveDepth,
  emittedLeafMember,
  emittedMembers,
  parentForLevel,
  selectionsEqual,
  visibleDropdownCount,
  type CascadeSelections,
} from "./cascadingFilter";

// Realistic Mondrian-style member unique names for a Geography walk:
// Region -> Country -> State -> City.
const USA = "[Geo].[Geography].[USA]";
const OR = "[Geo].[Geography].[USA].[OR]";
const PORTLAND = "[Geo].[Geography].[USA].[OR].[Portland]";
const MEXICO = "[Geo].[Geography].[Mexico]";

describe("clampDepth", () => {
  it("defaults a nullish / NaN depth to 1", () => {
    expect(clampDepth(undefined)).toBe(1);
    expect(clampDepth(NaN)).toBe(1);
  });

  it("floors a fractional depth", () => {
    expect(clampDepth(3.9)).toBe(3);
  });

  it("never returns below 1", () => {
    expect(clampDepth(0)).toBe(1);
    expect(clampDepth(-5)).toBe(1);
  });

  it("caps at the default max of 6", () => {
    expect(clampDepth(99)).toBe(6);
  });

  it("respects a custom max", () => {
    expect(clampDepth(10, 3)).toBe(3);
  });
});

describe("effectiveDepth", () => {
  it("is 0 when no levels are available", () => {
    expect(effectiveDepth(4, 0)).toBe(0);
    expect(effectiveDepth(4, -1)).toBe(0);
  });

  it("never exceeds the available level count", () => {
    expect(effectiveDepth(4, 2)).toBe(2);
  });

  it("uses the configured depth when levels are plentiful", () => {
    expect(effectiveDepth(3, 5)).toBe(3);
  });

  it("clamps the configured depth before bounding by levels", () => {
    expect(effectiveDepth(99, 4)).toBe(4); // clamp -> 6, then min(6,4)
    expect(effectiveDepth(0, 4)).toBe(1); // clamp -> 1, then min(1,4)
  });
});

describe("visibleDropdownCount", () => {
  it("is 0 for a zero-depth cascade", () => {
    expect(visibleDropdownCount([], 0)).toBe(0);
  });

  it("shows just the first dropdown when nothing is selected", () => {
    expect(visibleDropdownCount([], 4)).toBe(1);
    expect(visibleDropdownCount([null], 4)).toBe(1);
  });

  it("reveals the next dropdown once a parent is concretely chosen", () => {
    expect(visibleDropdownCount([USA], 4)).toBe(2);
  });

  it("reveals one dropdown per concrete selection, in order", () => {
    expect(visibleDropdownCount([USA, OR], 4)).toBe(3);
    expect(visibleDropdownCount([USA, OR, PORTLAND], 4)).toBe(4);
  });

  it("hides deeper dropdowns when an intermediate level is 'All'", () => {
    // dropdown 0 = USA, dropdown 1 = All -> dropdown 2+ hidden.
    expect(visibleDropdownCount([USA, null], 4)).toBe(2);
  });

  it("never exceeds the configured depth even with deeper selections", () => {
    expect(visibleDropdownCount([USA, OR, PORTLAND], 2)).toBe(2);
  });
});

describe("applySelection", () => {
  it("sets a concrete member at the root level", () => {
    expect(applySelection([], 0, USA)).toEqual([USA]);
  });

  it("appends a child selection below a chosen parent", () => {
    expect(applySelection([USA], 1, OR)).toEqual([USA, OR]);
  });

  it("truncates deeper selections when re-choosing a shallower level", () => {
    // user had USA/OR/Portland, then changes the country dropdown.
    expect(applySelection([USA, OR, PORTLAND], 0, MEXICO)).toEqual([MEXICO]);
  });

  it("selecting 'All' (null) at level k truncates to length k", () => {
    expect(applySelection([USA, OR, PORTLAND], 1, null)).toEqual([USA]);
  });

  it("selecting 'All' at the root clears everything", () => {
    expect(applySelection([USA, OR], 0, null)).toEqual([]);
  });

  it("pads sparse upstream gaps with explicit nulls", () => {
    // choosing a value at level 2 with nothing above keeps the indices dense.
    expect(applySelection([], 2, PORTLAND)).toEqual([null, null, PORTLAND]);
  });

  it("does not mutate the input array", () => {
    const src: CascadeSelections = [USA, OR];
    const out = applySelection(src, 1, null);
    expect(src).toEqual([USA, OR]);
    expect(out).not.toBe(src);
  });

  it("is a no-op-equivalent on idempotent re-selection of the same value", () => {
    const src: CascadeSelections = [USA, OR];
    const out = applySelection(src, 1, OR);
    expect(selectionsEqual(src, out)).toBe(true);
  });

  it("ignores a negative level (defensive clone)", () => {
    const src: CascadeSelections = [USA];
    const out = applySelection(src, -1, OR);
    expect(out).toEqual([USA]);
    expect(out).not.toBe(src);
  });
});

describe("emittedLeafMember", () => {
  it("returns null when every level is 'All'", () => {
    expect(emittedLeafMember([])).toBeNull();
    expect(emittedLeafMember([null, null])).toBeNull();
  });

  it("returns the only concrete selection", () => {
    expect(emittedLeafMember([USA])).toBe(USA);
  });

  it("returns the deepest concrete selection", () => {
    expect(emittedLeafMember([USA, OR, PORTLAND])).toBe(PORTLAND);
  });

  it("ignores trailing 'All' levels", () => {
    expect(emittedLeafMember([USA, OR, null])).toBe(OR);
  });

  it("returns the deepest concrete even with an intermediate 'All'", () => {
    // pathological but well-defined: deepest non-null wins.
    expect(emittedLeafMember([USA, null, PORTLAND])).toBe(PORTLAND);
  });
});

describe("emittedMembers", () => {
  it("is an empty array when nothing concrete is selected", () => {
    expect(emittedMembers([null])).toEqual([]);
  });

  it("wraps the leaf member in a single-element array", () => {
    expect(emittedMembers([USA, OR])).toEqual([OR]);
  });
});

describe("parentForLevel", () => {
  it("returns the concrete member at the requested level", () => {
    expect(parentForLevel([USA, OR], 0)).toBe(USA);
    expect(parentForLevel([USA, OR], 1)).toBe(OR);
  });

  it("returns null for an 'All' level", () => {
    expect(parentForLevel([USA, null], 1)).toBeNull();
  });

  it("returns null out of range", () => {
    expect(parentForLevel([USA], 5)).toBeNull();
    expect(parentForLevel([USA], -1)).toBeNull();
  });
});

describe("selectionsEqual", () => {
  it("treats trailing nulls as absence", () => {
    expect(selectionsEqual([USA], [USA, null, null])).toBe(true);
    expect(selectionsEqual([], [null, null])).toBe(true);
  });

  it("distinguishes different concrete selections", () => {
    expect(selectionsEqual([USA], [MEXICO])).toBe(false);
  });

  it("distinguishes different depths of concrete selection", () => {
    expect(selectionsEqual([USA], [USA, OR])).toBe(false);
  });

  it("distinguishes an intermediate 'All' from a concrete member", () => {
    expect(selectionsEqual([USA, null, PORTLAND], [USA, OR, PORTLAND])).toBe(false);
  });
});
