/*
 * Unit tests for the tab rename / duplicate feature (#1571).
 *
 * Exercises the tabs-store + query-store logic that the workspace tab
 * strip's context menu / inline rename drive:
 *   - rename a SAVED tab → moves the file via moveSavedQuery + updates savedPath
 *   - rename an UNSAVED tab → stashes the typed name as query.pendingName so
 *     the next Save dialog pre-fills it
 *   - duplicateActive() → new unsaved tab holding a DEEP COPY of the source
 *     (mutating one tab's query must not affect the other)
 *
 * The tabs/query stores are module singletons that read $state at
 * construction, so each test re-imports them fresh via vi.resetModules().
 * moveSavedQuery is mocked so no real /resource/move fetch fires.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

// Replace the repository API so the SAVED-rename path never hits fetch. The
// factory is hoisted; after resetModules the registry re-runs it, minting a
// fresh spy per test — we grab the current one through a fresh import.
vi.mock("$lib/api/repository", () => ({
  moveSavedQuery: vi.fn(async () => {}),
}));

type TabsModule = typeof import("./tabs.svelte");
type QueryModule = typeof import("./query.svelte");
type SelectionModule = typeof import("./selection.svelte");
type RepoModule = typeof import("$lib/api/repository");

let tabsMod: TabsModule;
let queryMod: QueryModule;
let selectionMod: SelectionModule;
let repoMod: RepoModule;

async function loadFresh(): Promise<void> {
  vi.resetModules();
  // Order matters only in that all four resolve against the same fresh graph.
  queryMod = await import("./query.svelte");
  selectionMod = await import("./selection.svelte");
  tabsMod = await import("./tabs.svelte");
  repoMod = await import("$lib/api/repository");
}

/** Minimal SaikuCube stand-in — the stores only pass it around structurally. */
function fakeCube(name = "Sales") {
  return { name, uniqueName: `[${name}]`, caption: name } as never;
}

/** Seed the live query store with a runnable-shaped-ish query that has one
 *  measure, so we have nested content to prove deep-copy independence. */
function seedActiveQuery(savedPath: string | null): void {
  const { query } = queryMod;
  const { selection } = selectionMod;
  selection.select(fakeCube());
  query.initFor(fakeCube());
  query.addMeasure({ uniqueName: "[Measures].[Unit Sales]", name: "Unit Sales" } as never);
  if (savedPath) query.markSaved(savedPath);
}

beforeEach(async () => {
  await loadFresh();
  // The mocked moveSavedQuery is a hoisted singleton spy — resetModules
  // re-imports the store graph but keeps the same spy, so clear its call
  // history (and any *Once implementations) between tests.
  vi.clearAllMocks();
});

describe("tabs.rename — saved query", () => {
  it("moves the file (same folder, .saiku) and updates the live savedPath", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");

    const res = await tabs.rename(tabs.activeIndex, "Quarterly");

    expect(repoMod.moveSavedQuery).toHaveBeenCalledTimes(1);
    expect(repoMod.moveSavedQuery).toHaveBeenCalledWith(
      "homes/admin/Report.saiku",
      "homes/admin/Quarterly.saiku",
    );
    expect(res).toEqual({ saved: true, path: "homes/admin/Quarterly.saiku" });
    expect(query.savedPath).toBe("homes/admin/Quarterly.saiku");
  });

  it("keeps a root-level query at the root and appends .saiku", async () => {
    const { tabs } = tabsMod;
    seedActiveQuery("Report.saiku");

    await tabs.rename(tabs.activeIndex, "Renamed");

    expect(repoMod.moveSavedQuery).toHaveBeenCalledWith("Report.saiku", "Renamed.saiku");
  });

  it("is a no-op move when the name is unchanged", async () => {
    const { tabs } = tabsMod;
    seedActiveQuery("homes/admin/Report.saiku");

    const res = await tabs.rename(tabs.activeIndex, "Report");

    expect(repoMod.moveSavedQuery).not.toHaveBeenCalled();
    expect(res).toEqual({ saved: true, path: "homes/admin/Report.saiku" });
  });

  it("renames a NON-active saved tab by updating its captured snapshot", async () => {
    const { tabs } = tabsMod;
    // Tab 0 is saved; open a second tab so tab 0 becomes inactive (snapshot).
    seedActiveQuery("homes/admin/Report.saiku");
    tabs.newTab();
    expect(tabs.activeIndex).toBe(1);

    const res = await tabs.rename(0, "Archived");

    expect(repoMod.moveSavedQuery).toHaveBeenCalledWith(
      "homes/admin/Report.saiku",
      "homes/admin/Archived.saiku",
    );
    expect(res.saved).toBe(true);
    expect(tabs.list[0].query.savedPath).toBe("homes/admin/Archived.saiku");
  });

  it("propagates a failed move to the caller (no state change)", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");
    (repoMod.moveSavedQuery as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("move -> 500"),
    );

    await expect(tabs.rename(tabs.activeIndex, "Boom")).rejects.toThrow("move -> 500");
    // savedPath untouched because markSaved never ran.
    expect(query.savedPath).toBe("homes/admin/Report.saiku");
  });
});

describe("tabs.rename — unsaved query", () => {
  it("stashes the typed name as query.pendingName (no file move)", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery(null);
    expect(query.savedPath).toBeNull();

    const res = await tabs.rename(tabs.activeIndex, "My Draft");

    expect(repoMod.moveSavedQuery).not.toHaveBeenCalled();
    expect(res).toEqual({ saved: false });
    expect(query.pendingName).toBe("My Draft");
  });

  it("pendingName is what a subsequent Save default would use (Untitled fallback otherwise)", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery(null);

    await tabs.rename(tabs.activeIndex, "Weekly Rollup");

    // Mirrors deriveDefaults() in WorkspaceToolbar: pending name + .saiku.
    const saveDefault = query.pendingName ? `${query.pendingName}.saiku` : "Untitled.saiku";
    expect(saveDefault).toBe("Weekly Rollup.saiku");
  });

  it("markSaved() clears a stashed pendingName once the query is persisted", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery(null);
    await tabs.rename(tabs.activeIndex, "Draft");
    expect(query.pendingName).toBe("Draft");

    query.markSaved("homes/admin/Draft.saiku");

    expect(query.pendingName).toBeNull();
    expect(query.savedPath).toBe("homes/admin/Draft.saiku");
  });

  it("stashes pendingName on a NON-active unsaved tab's snapshot", async () => {
    const { tabs } = tabsMod;
    seedActiveQuery(null);
    tabs.newTab();
    expect(tabs.activeIndex).toBe(1);

    await tabs.rename(0, "Old Draft");

    expect(tabs.list[0].query.pendingName).toBe("Old Draft");
  });

  it("ignores a blank/whitespace name", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery(null);

    const res = await tabs.rename(tabs.activeIndex, "   ");

    expect(res).toEqual({ saved: false });
    expect(query.pendingName).toBeNull();
    expect(repoMod.moveSavedQuery).not.toHaveBeenCalled();
  });
});

describe("tabs.duplicateActive", () => {
  it("opens a new unsaved tab, switches to it, and seeds a '<name> copy' label", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");
    const before = tabs.list.length;

    const newId = tabs.duplicateActive();

    expect(tabs.list.length).toBe(before + 1);
    expect(tabs.activeIndex).toBe(tabs.list.length - 1);
    expect(tabs.list[tabs.activeIndex].id).toBe(newId);
    // The copy is unsaved with a derived pending label.
    expect(query.savedPath).toBeNull();
    expect(query.pendingName).toBe("Report copy");
  });

  it("derives the copy label from an unsaved source's pending name", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery(null);
    await tabs.rename(tabs.activeIndex, "Scratch");

    tabs.duplicateActive();

    expect(query.pendingName).toBe("Scratch copy");
  });

  it("produces a DEEP COPY — mutating the copy does not affect the source", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");
    const sourceIndex = tabs.activeIndex;

    tabs.duplicateActive();

    // Source snapshot lives at its old index; the copy is the live active query.
    const sourceMeasures = tabs.list[sourceIndex].query.current!.queryModel!.details.measures;
    const copyMeasures = query.current!.queryModel!.details.measures;
    expect(copyMeasures).toHaveLength(1);
    expect(sourceMeasures).toHaveLength(1);

    // Mutate the COPY (live store) — the source snapshot must not change.
    query.current!.queryModel!.details.measures.push({
      uniqueName: "[Measures].[Store Cost]",
      name: "Store Cost",
    } as never);

    expect(query.current!.queryModel!.details.measures).toHaveLength(2);
    expect(tabs.list[sourceIndex].query.current!.queryModel!.details.measures).toHaveLength(1);
  });

  it("produces a DEEP COPY — mutating the source does not affect the copy", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");
    const sourceIndex = tabs.activeIndex;

    tabs.duplicateActive();
    const copyMeasures = query.current!.queryModel!.details.measures;

    // Mutate the SOURCE snapshot directly.
    tabs.list[sourceIndex].query.current!.queryModel!.details.measures.push({
      uniqueName: "[Measures].[Store Sales]",
      name: "Store Sales",
    } as never);

    expect(copyMeasures).toHaveLength(1);
  });

  it("gives the copy its own (empty) undo history rather than sharing the source's", async () => {
    const { tabs } = tabsMod;
    const { query } = queryMod;
    seedActiveQuery("homes/admin/Report.saiku");
    // Seed some undo history on the source.
    query.addMeasure({ uniqueName: "[Measures].[Store Cost]", name: "Store Cost" } as never);
    expect(query.past.length).toBeGreaterThan(0);

    tabs.duplicateActive();

    expect(query.past).toHaveLength(0);
    expect(query.future).toHaveLength(0);
  });
});
