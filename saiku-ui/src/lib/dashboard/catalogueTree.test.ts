/*
 * Unit tests for the catalogue folder-tree / path helpers (#937).
 */

import { describe, expect, it } from "vitest";

import {
  buildFolderTree,
  collectFolderPaths,
  isDescendantOf,
  joinPath,
  lastSegment,
  moveDashboardPath,
  parentFolder,
  pathSegments,
  renameFolderMoves,
  validateFolderName,
  type CatalogueLeaf,
} from "./catalogueTree";

function leaf(path: string, title: string | null = null): CatalogueLeaf {
  return { path, title, basename: lastSegment(path).replace(/\.saikudash$/, "") };
}

describe("pathSegments / parentFolder / lastSegment / joinPath", () => {
  it("splits and drops empty segments", () => {
    expect(pathSegments("/a//b/c/")).toEqual(["a", "b", "c"]);
    expect(pathSegments("")).toEqual([]);
  });

  it("derives the parent folder of a dashboard path", () => {
    expect(parentFolder("homes/admin/Sales/q3.saikudash")).toBe("homes/admin/Sales");
    expect(parentFolder("q3.saikudash")).toBe("");
  });

  it("returns the last segment", () => {
    expect(lastSegment("homes/admin/q3.saikudash")).toBe("q3.saikudash");
    expect(lastSegment("")).toBe("");
  });

  it("joins folder + child, handling the root", () => {
    expect(joinPath("homes/admin", "q3.saikudash")).toBe("homes/admin/q3.saikudash");
    expect(joinPath("", "q3.saikudash")).toBe("q3.saikudash");
    expect(joinPath("/homes//admin/", "x")).toBe("homes/admin/x");
  });
});

describe("buildFolderTree", () => {
  it("returns an empty synthetic root for no leaves", () => {
    const root = buildFolderTree([]);
    expect(root.path).toBe("");
    expect(root.name).toBe("");
    expect(root.folders).toEqual([]);
    expect(root.dashboards).toEqual([]);
  });

  it("materialises every ancestor folder implied by a deep path", () => {
    const root = buildFolderTree([leaf("Sales/2026/Q3/x.saikudash")]);
    const sales = root.folders[0];
    expect(sales.name).toBe("Sales");
    expect(sales.path).toBe("Sales");
    const y2026 = sales.folders[0];
    expect(y2026.path).toBe("Sales/2026");
    const q3 = y2026.folders[0];
    expect(q3.path).toBe("Sales/2026/Q3");
    expect(q3.dashboards.map((d) => d.path)).toEqual(["Sales/2026/Q3/x.saikudash"]);
  });

  it("places root-level dashboards directly on the root node", () => {
    const root = buildFolderTree([leaf("top.saikudash")]);
    expect(root.dashboards.map((d) => d.path)).toEqual(["top.saikudash"]);
    expect(root.folders).toEqual([]);
  });

  it("reuses a folder shared by sibling dashboards", () => {
    const root = buildFolderTree([leaf("Sales/a.saikudash"), leaf("Sales/b.saikudash")]);
    expect(root.folders).toHaveLength(1);
    expect(root.folders[0].dashboards).toHaveLength(2);
  });

  it("sorts folders by name and dashboards by display name, case-insensitively", () => {
    const root = buildFolderTree([
      leaf("Zeta/b.saikudash", "Beta"),
      leaf("Zeta/a.saikudash", "alpha"),
      leaf("alpha-folder/x.saikudash"),
    ]);
    expect(root.folders.map((f) => f.name)).toEqual(["alpha-folder", "Zeta"]);
    expect(root.folders[1].dashboards.map((d) => d.title)).toEqual(["alpha", "Beta"]);
  });

  it("seeds extra empty folders even with no dashboards inside", () => {
    const root = buildFolderTree([], ["homes/admin/NewFolder"]);
    expect(collectFolderPaths(root)).toEqual(["homes", "homes/admin", "homes/admin/NewFolder"]);
  });
});

describe("collectFolderPaths", () => {
  it("lists every folder path depth-first", () => {
    const root = buildFolderTree([leaf("A/B/x.saikudash"), leaf("C/y.saikudash")]);
    expect(collectFolderPaths(root)).toEqual(["A", "A/B", "C"]);
  });
});

describe("moveDashboardPath", () => {
  it("swaps the parent folder, preserving the filename", () => {
    expect(moveDashboardPath("homes/admin/Sales/q3.saikudash", "homes/admin/Archive")).toBe(
      "homes/admin/Archive/q3.saikudash",
    );
  });

  it("moves to the root when the target folder is blank", () => {
    expect(moveDashboardPath("homes/admin/q3.saikudash", "")).toBe("q3.saikudash");
  });
});

describe("renameFolderMoves", () => {
  it("rewrites the folder prefix for every descendant dashboard", () => {
    const moves = renameFolderMoves("homes/admin/Sales", "Revenue", [
      "homes/admin/Sales/q3.saikudash",
      "homes/admin/Sales/2026/q4.saikudash",
      "homes/admin/Other/z.saikudash",
    ]);
    expect(moves).toEqual([
      { from: "homes/admin/Sales/q3.saikudash", to: "homes/admin/Revenue/q3.saikudash" },
      { from: "homes/admin/Sales/2026/q4.saikudash", to: "homes/admin/Revenue/2026/q4.saikudash" },
    ]);
  });

  it("keeps the folder under the same parent", () => {
    const moves = renameFolderMoves("a/b/c", "renamed", ["a/b/c/x.saikudash"]);
    expect(moves[0].to).toBe("a/b/renamed/x.saikudash");
  });

  it("uses only the last segment when given a path-like new name", () => {
    const moves = renameFolderMoves("a/b", "x/y/z", ["a/b/d.saikudash"]);
    expect(moves[0].to).toBe("a/z/d.saikudash");
  });

  it("returns no moves for a no-op rename, blank name, or root", () => {
    expect(renameFolderMoves("a/b", "b", ["a/b/x.saikudash"])).toEqual([]);
    expect(renameFolderMoves("a/b", "   ", ["a/b/x.saikudash"])).toEqual([]);
    expect(renameFolderMoves("", "x", ["x.saikudash"])).toEqual([]);
  });
});

describe("isDescendantOf", () => {
  it("treats a folder as a descendant of itself and the root", () => {
    expect(isDescendantOf("a/b", "a/b")).toBe(true);
    expect(isDescendantOf("a/b", "")).toBe(true);
  });

  it("detects nested descendants", () => {
    expect(isDescendantOf("a/b/c", "a/b")).toBe(true);
    expect(isDescendantOf("a/bc", "a/b")).toBe(false);
    expect(isDescendantOf("a", "a/b")).toBe(false);
  });
});

describe("validateFolderName", () => {
  it("accepts a plain name", () => {
    expect(validateFolderName("Sales 2026")).toBeNull();
  });

  it("rejects blank, slash, and reserved-extension names", () => {
    expect(validateFolderName("  ")).toBe("empty");
    expect(validateFolderName("a/b")).toBe("slash");
    expect(validateFolderName("foo.saikudash")).toBe("reserved");
  });
});
