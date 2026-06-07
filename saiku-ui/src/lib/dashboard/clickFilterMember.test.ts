import { describe, it, expect } from "vitest";
import {
  leafSegment,
  pickMemberUniqueName,
  pickMemberUniqueNameForPath,
} from "./clickFilterMember";

describe("leafSegment", () => {
  it("returns the last bracketed segment unwrapped", () => {
    expect(leafSegment("[Product].[Drink].[Beer]")).toBe("Beer");
    expect(leafSegment("[Time].[Time].[Year].&[1997]")).toBe("1997");
  });

  it("handles a single segment", () => {
    expect(leafSegment("[All Products]")).toBe("All Products");
  });

  it("returns empty string when there is no bracketed tail", () => {
    expect(leafSegment("Beer")).toBe("");
    expect(leafSegment("")).toBe("");
  });

  it("preserves spaces / dots inside a segment", () => {
    expect(leafSegment("[Store].[Store City].[San Francisco]")).toBe("San Francisco");
  });
});

describe("pickMemberUniqueName", () => {
  const hits = [
    { uniqueName: "[Product].[Drink].[Beverages].[Pure Juice]", caption: "Pure Juice" },
    { uniqueName: "[Product].[Drink].[Alcoholic Beverages].[Beer]", caption: "Beer" },
    { uniqueName: "[Product].[Drink].[Alcoholic Beverages].[Root Beer]", caption: "Root Beer" },
  ];

  it("returns the unique name for an exact caption match", () => {
    expect(pickMemberUniqueName(hits, "Beer")).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Beer]",
    );
  });

  it("prefers the exact caption over a leaf-segment collision", () => {
    // "Root Beer"'s leaf is "Root Beer", but a click on "Beer" must not
    // resolve to it — the exact-caption "Beer" hit wins.
    expect(pickMemberUniqueName(hits, "Beer")).toContain(".[Beer]");
    expect(pickMemberUniqueName(hits, "Beer")).not.toContain("Root Beer");
  });

  it("matches case-insensitively and trims/collapses whitespace", () => {
    expect(pickMemberUniqueName(hits, "  beer ")).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Beer]",
    );
    expect(pickMemberUniqueName(hits, "ROOT   BEER")).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Root Beer]",
    );
  });

  it("falls back to the unique name's leaf segment when no caption matches", () => {
    const noCaptions = [{ uniqueName: "[Product].[Drink].[Alcoholic Beverages].[Beer]", caption: "" }];
    expect(pickMemberUniqueName(noCaptions, "Beer")).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Beer]",
    );
  });

  it("returns null when nothing matches", () => {
    expect(pickMemberUniqueName(hits, "Wine")).toBeNull();
    expect(pickMemberUniqueName([], "Beer")).toBeNull();
  });

  it("returns null for a blank caption", () => {
    expect(pickMemberUniqueName(hits, "")).toBeNull();
    expect(pickMemberUniqueName(hits, "   ")).toBeNull();
  });

  it("skips malformed hits without a unique name", () => {
    const messy = [
      { uniqueName: "", caption: "Beer" },
      { uniqueName: "[Product].[Drink].[Alcoholic Beverages].[Beer]", caption: "Beer" },
    ];
    expect(pickMemberUniqueName(messy, "Beer")).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Beer]",
    );
  });
});

describe("pickMemberUniqueNameForPath", () => {
  // Two members share the leaf caption "Q2" — one under 1997, one under 1998.
  const ambiguous = [
    { uniqueName: "[Time].[Time].[1997].[Q2]", caption: "Q2" },
    { uniqueName: "[Time].[Time].[1998].[Q2]", caption: "Q2" },
  ];

  it("disambiguates an ambiguous leaf by the parent caption path", () => {
    expect(pickMemberUniqueNameForPath(ambiguous, ["1997", "Q2"])).toBe(
      "[Time].[Time].[1997].[Q2]",
    );
    expect(pickMemberUniqueNameForPath(ambiguous, ["1998", "Q2"])).toBe(
      "[Time].[Time].[1998].[Q2]",
    );
  });

  it("returns the sole match when the leaf is unambiguous", () => {
    const one = [{ uniqueName: "[Product].[Drink].[Alcoholic Beverages].[Beer]", caption: "Beer" }];
    expect(pickMemberUniqueNameForPath(one, ["Drink", "Beer"])).toBe(
      "[Product].[Drink].[Alcoholic Beverages].[Beer]",
    );
  });

  it("falls back to the first candidate when parents don't pin one", () => {
    // Parent "2099" matches neither → stable first candidate.
    expect(pickMemberUniqueNameForPath(ambiguous, ["2099", "Q2"])).toBe(
      "[Time].[Time].[1997].[Q2]",
    );
  });

  it("returns null when nothing matches the leaf", () => {
    expect(pickMemberUniqueNameForPath(ambiguous, ["1997", "Q3"])).toBeNull();
    expect(pickMemberUniqueNameForPath([], ["Beer"])).toBeNull();
    expect(pickMemberUniqueNameForPath(ambiguous, [])).toBeNull();
  });

  it("matches case-insensitively on both leaf and parents", () => {
    expect(pickMemberUniqueNameForPath(ambiguous, ["1998", "q2"])).toBe(
      "[Time].[Time].[1998].[Q2]",
    );
  });
});
