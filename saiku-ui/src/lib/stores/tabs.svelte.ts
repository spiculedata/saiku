/*
 * In-app workspace tabs.
 *
 * Each tab carries a snapshot of the {@link query} store's
 * user-visible state plus the selected cube. The singleton {@link query}
 * and {@link selection} stores are not replicated — they always hold the
 * ACTIVE tab's live state. On tab switch we cancel any in-flight query,
 * stash the outgoing tab's snapshot, and restore the incoming tab's.
 *
 * Why snapshot/restore instead of per-tab QueryStore instances? The
 * existing {@link QueryStore} is widely imported across the workspace as
 * a singleton (Workspace, WorkspaceToolbar, QueryCanvas, DimensionList,
 * CellsetTable). Keeping the singleton means none of those files have
 * to thread the active tab's instance around — they just keep reading
 * {@link query}.* and we swap the underlying state on tab switch.
 *
 * Transient flags (`running`, `error`, `runningQueryId`,
 * `runningElapsedMs`) intentionally do NOT survive tab switches — the
 * in-flight query is cancelled by {@link QueryStore.snapshotAndReset}
 * so its response can't land on the wrong tab.
 */

import {
  blankSnapshot,
  query,
  type QueryStateSnapshot,
} from "$lib/stores/query.svelte";
import { selection, type SelectionSnapshot } from "$lib/stores/selection.svelte";
import { moveSavedQuery } from "$lib/api/repository";

/** One tab in the workspace tab strip. */
export interface WorkspaceTab {
  /** Stable id used as the {#each} key. */
  id: string;
  /** Snapshot of the query store's user-visible state. */
  query: QueryStateSnapshot;
  /**
   * Snapshot of the selected datasource — MDX cube or Ossie model. Retained under the
   * `cube` name for on-disk / session-storage compatibility with pre-Ossie snapshots. The
   * discriminator lives inside the {@link SelectionSnapshot} value.
   */
  cube: SelectionSnapshot | null;
}

function freshTabId(): string {
  return `t-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
}

/** Human-readable base name for a snapshot, used to seed a duplicate's
 *  "<name> copy" label. Prefers the user-typed pending name, then the
 *  saved-file basename (sans `.saiku`), else a neutral fallback. i18n is
 *  the component's job — the store stays locale-agnostic. */
function baseNameOf(snap: QueryStateSnapshot): string {
  if (snap.pendingName && snap.pendingName.trim()) return snap.pendingName.trim();
  const p = snap.savedPath;
  if (p) {
    const base = p.split("/").pop() ?? p;
    return base.endsWith(".saiku") ? base.slice(0, -".saiku".length) : base;
  }
  return "Untitled";
}

/** Result of a tab rename. `saved` distinguishes the move-a-file path
 *  (the query had a savedPath) from the stash-a-pending-name path. */
export interface TabRenameResult {
  saved: boolean;
  path?: string;
}

class TabsStore {
  list = $state<WorkspaceTab[]>([
    { id: freshTabId(), query: blankSnapshot(), cube: null },
  ]);
  activeIndex = $state<number>(0);

  /** The currently-active tab. Live reads of query.current / selection.cube
   *  reflect this tab; the other tabs' state sits in their {@code query} /
   *  {@code cube} snapshot fields. */
  get active(): WorkspaceTab {
    return this.list[this.activeIndex];
  }

  /** Capture the live store state into the currently-active tab's slot.
   *  Used internally before any tab transition. */
  private captureActive(): void {
    const idx = this.activeIndex;
    if (idx < 0 || idx >= this.list.length) return;
    const snapshot = {
      ...this.list[idx],
      query: query.snapshotAndReset(),
      cube: selection.snapshot(),
    };
    // Immutable rewrite so Svelte 5 reactivity propagates.
    this.list = this.list.map((t, i) => (i === idx ? snapshot : t));
  }

  /** Open the live stores against the tab at {@code targetIndex}. */
  private hydrateActive(targetIndex: number): void {
    const target = this.list[targetIndex];
    query.restore(target.query);
    selection.restore(target.cube);
    this.activeIndex = targetIndex;
  }

  /** Switch to an existing tab by index. No-op when already active. */
  switchTo(index: number): void {
    if (index === this.activeIndex) return;
    if (index < 0 || index >= this.list.length) return;
    this.captureActive();
    this.hydrateActive(index);
  }

  /** Open a fresh blank tab and switch to it. Returns the new tab's id. */
  newTab(): string {
    this.captureActive();
    const t: WorkspaceTab = { id: freshTabId(), query: blankSnapshot(), cube: null };
    this.list = [...this.list, t];
    this.hydrateActive(this.list.length - 1);
    return t.id;
  }

  /** Close a tab. If only one tab remains, the close is silently
   *  ignored — the workspace always keeps at least one tab around. */
  closeTab(index: number): void {
    if (this.list.length <= 1) return;
    if (index < 0 || index >= this.list.length) return;
    const closingActive = index === this.activeIndex;
    if (!closingActive) {
      // Just remove from list; live state stays on the active tab.
      const nextActive = index < this.activeIndex ? this.activeIndex - 1 : this.activeIndex;
      this.list = this.list.filter((_, i) => i !== index);
      this.activeIndex = nextActive;
      return;
    }
    // Closing the active tab — pick neighbour, restore its snapshot, then
    // splice. Cancel any in-flight on the closing tab via
    // snapshotAndReset (we ignore the returned snapshot since the tab is
    // going away).
    query.snapshotAndReset();
    const neighbour = index === this.list.length - 1 ? index - 1 : index + 1;
    const target = this.list[neighbour];
    query.restore(target.query);
    selection.restore(target.cube);
    const remaining = this.list.filter((_, i) => i !== index);
    this.list = remaining;
    // Recompute activeIndex against the trimmed list.
    this.activeIndex = remaining.findIndex((t) => t.id === target.id);
  }

  /** The effective savedPath for tab {@code i}: the live query store for
   *  the active tab, the captured snapshot otherwise. */
  private savedPathFor(i: number): string | null {
    return i === this.activeIndex ? query.savedPath : this.list[i].query.savedPath;
  }

  /** Patch a single (non-active) tab's captured query snapshot immutably so
   *  Svelte 5 reactivity propagates. */
  private patchTabQuery(i: number, patch: Partial<QueryStateSnapshot>): void {
    this.list = this.list.map((t, idx) =>
      idx === i ? { ...t, query: { ...t.query, ...patch } } : t,
    );
  }

  /**
   * Rename tab {@code i} to {@code rawName}.
   *
   * - If the tab's query is SAVED (its snapshot `savedPath` is set), the
   *   file is moved on disk to `<same folder>/<new name>.saiku` via
   *   {@link moveSavedQuery}, then the tab's savedPath is updated (through
   *   `query.markSaved` when it's the active tab so the live label/dirty
   *   flag follow). A rejected move propagates to the caller (which toasts).
   * - If the tab is UNSAVED (`savedPath` null), there's no file to move —
   *   the typed name is stashed as the query's `pendingName` so the next
   *   Save dialog pre-fills it and the tab label reflects it immediately.
   *
   * Returns {@link TabRenameResult} so the caller can toast appropriately.
   * A blank/whitespace name is a no-op.
   */
  async rename(i: number, rawName: string): Promise<TabRenameResult> {
    if (i < 0 || i >= this.list.length) return { saved: false };
    const name = rawName.trim();
    if (!name) return { saved: false };

    const oldPath = this.savedPathFor(i);
    if (oldPath) {
      const idx = oldPath.lastIndexOf("/");
      const folder = idx > 0 ? oldPath.slice(0, idx) : "";
      const file = name.endsWith(".saiku") ? name : `${name}.saiku`;
      const newPath = folder ? `${folder}/${file}` : file;
      if (newPath === oldPath) return { saved: true, path: oldPath };
      await moveSavedQuery(oldPath, newPath);
      if (i === this.activeIndex) {
        query.markSaved(newPath);
      } else {
        this.patchTabQuery(i, { savedPath: newPath, pendingName: null });
      }
      return { saved: true, path: newPath };
    }

    // Unsaved — no file to move; stash the name for the next Save.
    if (i === this.activeIndex) {
      query.setPendingName(name);
    } else {
      this.patchTabQuery(i, { pendingName: name });
    }
    return { saved: false };
  }

  /**
   * Open a new tab holding a DEEP COPY of the active tab's query + cube,
   * marked unsaved (`savedPath = null`) with a "<name> copy" pending label,
   * and switch to it. The copy is structurally independent of the source —
   * mutating one tab's query never bleeds into the other.
   *
   * Reuses the same capture/new/hydrate machinery as {@link newTab}:
   * snapshot the live active state, clone it, capture the outgoing tab, push
   * the clone, then hydrate the live stores against it. Returns the new id.
   */
  duplicateActive(): string {
    // Deep-clone the LIVE active state. $state.snapshot unwraps the Svelte 5
    // proxies (structuredClone throws on proxies) into plain objects; the
    // clone shares no references with the source snapshot.
    const srcQuery = query.snapshot();
    const srcCube = selection.snapshot();
    const clonedQuery = structuredClone($state.snapshot(srcQuery)) as QueryStateSnapshot;
    const clonedCube = structuredClone($state.snapshot(srcCube)) as SelectionSnapshot;

    // A duplicate is an unsaved working copy: drop the saved path, seed the
    // label, start it dirty (there are unpersisted contents), and give it a
    // fresh (empty) undo history rather than sharing the source's stacks.
    clonedQuery.savedPath = null;
    clonedQuery.pendingName = `${baseNameOf(srcQuery)} copy`;
    clonedQuery.dirty = true;
    clonedQuery.dirtyCount = 0;
    clonedQuery.past = [];
    clonedQuery.future = [];

    this.captureActive();
    const t: WorkspaceTab = { id: freshTabId(), query: clonedQuery, cube: clonedCube };
    this.list = [...this.list, t];
    this.hydrateActive(this.list.length - 1);
    return t.id;
  }
}

export const tabs = new TabsStore();
