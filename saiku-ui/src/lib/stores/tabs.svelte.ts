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
import { selection } from "$lib/stores/selection.svelte";
import type { SaikuCube } from "$lib/api/discover";

/** One tab in the workspace tab strip. */
export interface WorkspaceTab {
  /** Stable id used as the {#each} key. */
  id: string;
  /** Snapshot of the query store's user-visible state. */
  query: QueryStateSnapshot;
  /** Snapshot of the selected cube. */
  cube: SaikuCube | null;
}

function freshTabId(): string {
  return `t-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`;
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

  /** Sync any in-memory changes from the live stores back to the active
   *  tab's slot. Used by the URL writer so the tab's snapshot stays
   *  current for code that reads {@link list} without going through the
   *  live stores (e.g. label derivation). Cheap — just one assignment. */
  syncFromLive(): void {
    const idx = this.activeIndex;
    if (idx < 0 || idx >= this.list.length) return;
    this.list = this.list.map((t, i) =>
      i === idx
        ? {
            ...t,
            query: query.snapshot(),
            cube: selection.snapshot(),
          }
        : t,
    );
  }
}

export const tabs = new TabsStore();
