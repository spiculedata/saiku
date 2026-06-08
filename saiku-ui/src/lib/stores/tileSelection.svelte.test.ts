/*
 * Unit tests for the pure tile-selection helpers (#915).
 *
 * Only the DOM-free reducers are exercised here — the reactive store
 * wrapper holds $state and isn't testable under the node vitest env.
 */

import { describe, expect, it } from "vitest";
import { add, applyClick, clear, isSelected, prune, toggle } from "./tileSelection.svelte";

describe("tileSelection helpers", () => {
  describe("isSelected", () => {
    it("reports membership", () => {
      const set = new Set(["a", "b"]);
      expect(isSelected(set, "a")).toBe(true);
      expect(isSelected(set, "z")).toBe(false);
    });
  });

  describe("add", () => {
    it("adds an id and returns a fresh set", () => {
      const set = new Set(["a"]);
      const next = add(set, "b");
      expect([...next].sort()).toEqual(["a", "b"]);
      expect(next).not.toBe(set);
      expect([...set]).toEqual(["a"]); // input untouched
    });

    it("is idempotent when the id is already present", () => {
      const set = new Set(["a"]);
      const next = add(set, "a");
      expect([...next]).toEqual(["a"]);
      expect(next).not.toBe(set); // still a fresh clone
    });
  });

  describe("toggle", () => {
    it("adds a missing id", () => {
      expect([...toggle(new Set(["a"]), "b")].sort()).toEqual(["a", "b"]);
    });

    it("removes a present id", () => {
      expect([...toggle(new Set(["a", "b"]), "a")]).toEqual(["b"]);
    });

    it("does not mutate the input", () => {
      const set = new Set(["a"]);
      toggle(set, "b");
      expect([...set]).toEqual(["a"]);
    });
  });

  describe("clear", () => {
    it("returns an empty set", () => {
      expect(clear().size).toBe(0);
    });
  });

  describe("applyClick", () => {
    it("replace collapses to just the clicked id", () => {
      expect([...applyClick(new Set(["a", "b"]), "c", "replace")]).toEqual(["c"]);
    });

    it("replace on an already-selected tile keeps only that tile", () => {
      expect([...applyClick(new Set(["a", "b"]), "a", "replace")]).toEqual(["a"]);
    });

    it("toggle flips the clicked id, keeping the rest", () => {
      expect([...applyClick(new Set(["a", "b"]), "b", "toggle")]).toEqual(["a"]);
      expect([...applyClick(new Set(["a"]), "b", "toggle")].sort()).toEqual(["a", "b"]);
    });

    it("extend adds the clicked id to the current selection", () => {
      expect([...applyClick(new Set(["a"]), "b", "extend")].sort()).toEqual(["a", "b"]);
    });

    it("extend on an already-selected tile is a no-op set-wise", () => {
      expect([...applyClick(new Set(["a", "b"]), "a", "extend")].sort()).toEqual(["a", "b"]);
    });

    it("never mutates the input set", () => {
      const set = new Set(["a", "b"]);
      applyClick(set, "c", "toggle");
      applyClick(set, "c", "extend");
      applyClick(set, "c", "replace");
      expect([...set].sort()).toEqual(["a", "b"]);
    });
  });

  describe("prune", () => {
    it("drops ids absent from the live set", () => {
      const next = prune(new Set(["a", "b", "gone"]), new Set(["a", "b"]));
      expect([...next].sort()).toEqual(["a", "b"]);
    });

    it("returns the SAME reference when nothing changed", () => {
      const set = new Set(["a", "b"]);
      const next = prune(set, new Set(["a", "b", "c"]));
      expect(next).toBe(set);
    });

    it("returns an empty set when nothing survives", () => {
      const next = prune(new Set(["x"]), new Set(["a"]));
      expect(next.size).toBe(0);
    });
  });
});
