/*
 * Tests for the header context selector's pure logic.
 *
 * Before this the pill was decorative — it rendered "STORE / Portland #14 ▾"
 * and did nothing when clicked. These cover the three ways an author can name a
 * member (unique name, caption, or the ALL sentinel) and the "not bound to a
 * level" case where the pill is purely cosmetic.
 */

import { describe, expect, it } from "vitest";
import type { AppContextPill } from "$lib/api/apps";
import {
  ALL_MEMBER,
  effectiveLabel,
  isSelectable,
  optionByLabel,
  optionsFor,
  selectionFor,
} from "./contextPill";

const TARGET = { dimension: "Store", hierarchy: "Stores", level: "Store Name" };

const PILL: AppContextPill = {
  label: "Store",
  value: "Portland #14",
  filter: TARGET,
  options: [
    { label: "Portland #14" },
    { label: "Seattle #3", member: "[Store].[Stores].[USA].[WA].[Seattle].[Store 3]" },
    { label: "All stores · National", member: ALL_MEMBER },
  ],
};

describe("isSelectable", () => {
  it("is false without options — the pill stays static text", () => {
    expect(isSelectable(undefined)).toBe(false);
    expect(isSelectable({ label: "Store", value: "Portland #14" })).toBe(false);
    expect(isSelectable({ label: "Store", value: "x", options: [] })).toBe(false);
  });

  it("is true once the author lists options", () => {
    expect(isSelectable(PILL)).toBe(true);
  });
});

describe("optionsFor", () => {
  it("returns the author's list when it already contains the current value", () => {
    expect(optionsFor(PILL).map((o) => o.label)).toEqual([
      "Portland #14",
      "Seattle #3",
      "All stores · National",
    ]);
  });

  /* Otherwise the pill would display a value its own dropdown doesn't offer,
   * and the selector would look wrong the moment it opened. */
  it("prepends the current value when the list omits it", () => {
    const pill: AppContextPill = { label: "Store", value: "Denver #9", options: [{ label: "A" }] };
    expect(optionsFor(pill).map((o) => o.label)).toEqual(["Denver #9", "A"]);
  });

  it("returns nothing for a pill with no options", () => {
    expect(optionsFor(undefined)).toEqual([]);
    expect(optionsFor({ label: "Store", value: "x" })).toEqual([]);
  });
});

describe("effectiveLabel", () => {
  it("shows the live selection", () => {
    expect(effectiveLabel(PILL, "Seattle #3")).toBe("Seattle #3");
  });

  it("falls back to the default when nothing is selected", () => {
    expect(effectiveLabel(PILL, undefined)).toBe("Portland #14");
    expect(effectiveLabel(PILL, null)).toBe("Portland #14");
  });

  /* A selection carried over from a different app — or from options the author
   * has since removed — must not display a value the pill no longer offers. */
  it("falls back when the selection is no longer an option", () => {
    expect(effectiveLabel(PILL, "Denver #9")).toBe("Portland #14");
  });

  it("is empty for a pill that doesn't exist", () => {
    expect(effectiveLabel(undefined, "x")).toBe("");
  });
});

describe("optionByLabel", () => {
  it("finds an option by its label", () => {
    expect(optionByLabel(PILL, "Seattle #3")?.member).toContain("Store 3");
  });

  it("returns undefined for an unknown label", () => {
    expect(optionByLabel(PILL, "Nope")).toBeUndefined();
  });
});

describe("selectionFor", () => {
  it("uses an explicit unique name verbatim", () => {
    const s = selectionFor(PILL, optionByLabel(PILL, "Seattle #3"));
    expect(s.kind).toBe("set");
    if (s.kind === "set") {
      expect(s.filter.members).toEqual(["[Store].[Stores].[USA].[WA].[Seattle].[Store 3]"]);
      expect(s.filter.dimension).toBe("Store");
    }
  });

  it("asks the caller to resolve a caption when no member is given", () => {
    const s = selectionFor(PILL, optionByLabel(PILL, "Portland #14"));
    expect(s.kind).toBe("resolve");
    if (s.kind === "resolve") {
      expect(s.caption).toBe("Portland #14");
      expect(s.target).toEqual(TARGET);
    }
  });

  it("clears the filter for the ALL sentinel", () => {
    const s = selectionFor(PILL, optionByLabel(PILL, "All stores · National"));
    expect(s.kind).toBe("clear");
  });

  /* A pill with options but no bound level is a legitimate configuration — the
   * author just wants the header text to change. It must never fabricate a
   * filter against an empty dimension. */
  it("does nothing when the pill is not bound to a level", () => {
    const cosmetic: AppContextPill = { ...PILL, filter: undefined };
    expect(selectionFor(cosmetic, optionByLabel(cosmetic, "Seattle #3")).kind).toBe("none");
  });

  it("does nothing when the binding is incomplete", () => {
    const partial: AppContextPill = {
      ...PILL,
      filter: { dimension: "Store", hierarchy: "", level: "Store Name" },
    };
    expect(selectionFor(partial, optionByLabel(partial, "Seattle #3")).kind).toBe("none");
  });

  it("treats a blank-labelled, memberless option as ALL rather than resolving ''", () => {
    const pill: AppContextPill = {
      label: "Store",
      value: "x",
      filter: TARGET,
      options: [{ label: "  " }],
    };
    expect(selectionFor(pill, pill.options?.[0]).kind).toBe("clear");
  });

  it("returns none for a missing option", () => {
    expect(selectionFor(PILL, undefined).kind).toBe("none");
  });
});

