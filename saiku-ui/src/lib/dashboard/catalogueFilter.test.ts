/*
 * Unit tests for catalogue filter / sort helpers (#935).
 */

import { describe, expect, it } from "vitest";

import {
  UNKNOWN_OWNER,
  applyCatalogueFilters,
  collectOwners,
  collectTags,
  ownerPredicate,
  searchPredicate,
  sortComparator,
  tagPredicate,
  type CatalogueEntry,
} from "./catalogueFilter";

function entry(partial: Partial<CatalogueEntry> & { path: string }): CatalogueEntry {
  return {
    title: null,
    tags: [],
    owner: null,
    modified: 0,
    basename: partial.basename ?? partial.path.split("/").pop()!.replace(/\.saikudash$/, ""),
    ...partial,
  };
}

describe("searchPredicate", () => {
  it("matches everything when query is blank or whitespace", () => {
    const p = searchPredicate("   ");
    expect(p(entry({ path: "anything.saikudash" }))).toBe(true);
  });

  it("matches the title case-insensitively", () => {
    const p = searchPredicate("SALES");
    expect(p(entry({ path: "x.saikudash", title: "Quarterly Sales" }))).toBe(true);
  });

  it("falls back to basename when title is null", () => {
    const p = searchPredicate("sales");
    expect(p(entry({ path: "homes/a/sales.saikudash", title: null }))).toBe(true);
  });

  it("matches the path", () => {
    const p = searchPredicate("marketing");
    expect(p(entry({ path: "homes/a/marketing/x.saikudash" }))).toBe(true);
  });

  it("returns false when no field contains the query", () => {
    const p = searchPredicate("zzz");
    expect(p(entry({ path: "homes/a/sales.saikudash", title: "Sales" }))).toBe(false);
  });
});

describe("tagPredicate", () => {
  it("matches everything when selection is empty", () => {
    const p = tagPredicate([]);
    expect(p(entry({ path: "x.saikudash", tags: [] }))).toBe(true);
  });

  it("requires all selected tags to be present (AND)", () => {
    const p = tagPredicate(["exec", "finance"]);
    expect(p(entry({ path: "a", tags: ["exec", "finance"] }))).toBe(true);
    expect(p(entry({ path: "b", tags: ["exec"] }))).toBe(false);
    expect(p(entry({ path: "c", tags: ["exec", "finance", "extra"] }))).toBe(true);
    expect(p(entry({ path: "d", tags: [] }))).toBe(false);
  });
});

describe("ownerPredicate", () => {
  it("matches everything when selection is empty", () => {
    const p = ownerPredicate([]);
    expect(p(entry({ path: "x", owner: "alice" }))).toBe(true);
    expect(p(entry({ path: "y", owner: null }))).toBe(true);
  });

  it("matches OR-semantics on selected owners", () => {
    const p = ownerPredicate(["alice", "bob"]);
    expect(p(entry({ path: "a", owner: "alice" }))).toBe(true);
    expect(p(entry({ path: "b", owner: "bob" }))).toBe(true);
    expect(p(entry({ path: "c", owner: "carol" }))).toBe(false);
  });

  it("excludes null-owner entries unless UNKNOWN_OWNER is selected", () => {
    const p = ownerPredicate(["alice"]);
    expect(p(entry({ path: "a", owner: null }))).toBe(false);
    const q = ownerPredicate(["alice", UNKNOWN_OWNER]);
    expect(q(entry({ path: "b", owner: null }))).toBe(true);
    expect(q(entry({ path: "c", owner: "alice" }))).toBe(true);
    expect(q(entry({ path: "d", owner: "bob" }))).toBe(false);
  });
});

describe("sortComparator", () => {
  const a = entry({ path: "a.saikudash", title: "Apple", modified: 100 });
  const b = entry({ path: "b.saikudash", title: "Banana", modified: 300 });
  const c = entry({ path: "c.saikudash", title: "Cherry", modified: 200 });

  it("name (default) is case-insensitive alphabetical on title/basename", () => {
    const sorted = [c, a, b].sort(sortComparator("name"));
    expect(sorted.map((e) => e.title)).toEqual(["Apple", "Banana", "Cherry"]);
  });

  it("modified-desc puts most-recent first", () => {
    const sorted = [a, b, c].sort(sortComparator("modified-desc"));
    expect(sorted.map((e) => e.title)).toEqual(["Banana", "Cherry", "Apple"]);
  });

  it("modified-asc puts oldest first", () => {
    const sorted = [a, b, c].sort(sortComparator("modified-asc"));
    expect(sorted.map((e) => e.title)).toEqual(["Apple", "Cherry", "Banana"]);
  });

  it("name is the tiebreaker when modified ties", () => {
    const x = entry({ path: "x.saikudash", title: "Xenon", modified: 100 });
    const y = entry({ path: "y.saikudash", title: "Argon", modified: 100 });
    const sorted = [x, y].sort(sortComparator("modified-desc"));
    expect(sorted.map((e) => e.title)).toEqual(["Argon", "Xenon"]);
  });
});

describe("applyCatalogueFilters", () => {
  const entries: CatalogueEntry[] = [
    entry({
      path: "homes/alice/sales.saikudash",
      title: "Sales Q4",
      tags: ["exec", "finance"],
      owner: "alice",
      modified: 300,
    }),
    entry({
      path: "homes/bob/ops.saikudash",
      title: "Ops dashboard",
      tags: ["ops"],
      owner: "bob",
      modified: 100,
    }),
    entry({
      path: "homes/alice/marketing.saikudash",
      title: "Marketing",
      tags: ["marketing", "exec"],
      owner: "alice",
      modified: 200,
    }),
  ];

  it("returns everything sorted by name when no filters apply", () => {
    const out = applyCatalogueFilters(entries, {});
    expect(out.map((e) => e.title)).toEqual(["Marketing", "Ops dashboard", "Sales Q4"]);
  });

  it("combines search + tag + owner + sort", () => {
    const out = applyCatalogueFilters(entries, {
      search: "marketing",
      tags: ["exec"],
      owners: ["alice"],
      sort: "modified-desc",
    });
    expect(out.map((e) => e.title)).toEqual(["Marketing"]);
  });

  it("does not mutate the input array", () => {
    const snapshot = entries.slice();
    applyCatalogueFilters(entries, { sort: "modified-desc" });
    expect(entries).toEqual(snapshot);
  });
});

describe("collectTags / collectOwners", () => {
  const entries: CatalogueEntry[] = [
    entry({ path: "a", tags: ["exec", "finance"], owner: "alice" }),
    entry({ path: "b", tags: ["ops"], owner: "bob" }),
    entry({ path: "c", tags: ["exec"], owner: null }),
  ];

  it("collectTags returns the union alphabetised", () => {
    expect(collectTags(entries)).toEqual(["exec", "finance", "ops"]);
  });

  it("collectTags skips empty / falsy tag values", () => {
    const noisy = [entry({ path: "x", tags: ["", "real"] })];
    expect(collectTags(noisy)).toEqual(["real"]);
  });

  it("collectOwners returns the union, with UNKNOWN_OWNER appended when any are null", () => {
    expect(collectOwners(entries)).toEqual(["alice", "bob", UNKNOWN_OWNER]);
  });

  it("collectOwners omits UNKNOWN_OWNER when all entries have a real owner", () => {
    const onlyOwners = entries.filter((e) => e.owner != null);
    expect(collectOwners(onlyOwners)).toEqual(["alice", "bob"]);
  });
});
