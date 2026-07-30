/*
 * App Builder document store.
 *
 * Owns the currently-edited {@code .saikuapp} document — the page/nav/theme
 * envelope — plus which page is active and the load/save persistence fields.
 * Each page's inline dashboard {@code grid} stays OPAQUE here (the dashboard
 * store is the schema authority for tile layout); this store only manipulates
 * the app-level envelope.
 *
 * Every mutator is IMMUTABLE — it replaces {@code current} with a fresh object
 * (and fresh nested arrays / objects) rather than mutating in place. That's the
 * repo-wide immutability rule AND a Svelte 5 reactivity requirement: mutating a
 * $state object in place doesn't reliably re-fire dependent effects.
 *
 * Undo / redo mirrors the OssieQueryStore machinery: a capture-before-mutating
 * snapshot stack, capped, with the redo branch cleared on any forward edit.
 *
 * Exposed as a singleton ({@code appDoc}) like {@code ossieQuery}.
 */
import {
  appFromDashboard,
  emptyApp,
  emptyPage,
  getApp,
  normaliseApp,
  saveApp as saveAppApi,
  type AppNav,
  type AppPage,
  type AppTheme,
  type SaikuApp,
} from "$lib/api/apps";

/** One entry on the undo/redo stack — the whole editable envelope plus which
 *  page was active. Deep-cloned on capture so the stack never aliases live
 *  $state. */
interface AppHistoryEntry {
  current: SaikuApp | null;
  activePageId: string | null;
  savedPath: string | null;
}

class AppDocStore {
  /** Currently-edited app. Null until {@link newApp} / {@link loadApp}. */
  current = $state<SaikuApp | null>(null);

  /** Id of the page the editor is showing. Null when there's no app. */
  activePageId = $state<string | null>(null);

  /** True while a load is in flight. */
  loading = $state(false);

  /** Sticky error surface from load or save. */
  error = $state<string | null>(null);

  /** Repository path this app is saved to. Null for unsaved / new apps. */
  savedPath = $state<string | null>(null);

  // ------------------------------------------------------------------
  // Undo / redo — same shape as OssieQueryStore: snapshot-before-mutate,
  // capped stack, redo cleared on any forward edit.
  // ------------------------------------------------------------------

  past = $state<AppHistoryEntry[]>([]);
  future = $state<AppHistoryEntry[]>([]);
  canUndo = $derived(this.past.length > 0);
  canRedo = $derived(this.future.length > 0);
  private static readonly MAX_HISTORY = 50;

  private snapshotForHistory(): AppHistoryEntry {
    return {
      current: this.current ? structuredClone($state.snapshot(this.current)) : null,
      activePageId: this.activePageId,
      savedPath: this.savedPath,
    };
  }

  /** Push the CURRENT state onto the undo stack before a mutation. Every
   *  envelope mutator calls this first. Clears the redo branch. */
  private captureForUndo(): void {
    this.past.push(this.snapshotForHistory());
    if (this.past.length > AppDocStore.MAX_HISTORY) this.past.shift();
    if (this.future.length) this.future = [];
  }

  private applyHistory(entry: AppHistoryEntry): void {
    const plain = $state.snapshot(entry) as AppHistoryEntry;
    this.current = plain.current ? structuredClone(plain.current) : null;
    this.activePageId = plain.activePageId;
    this.savedPath = plain.savedPath;
  }

  undo(): void {
    const prev = this.past.pop();
    if (!prev) return;
    this.future.push(this.snapshotForHistory());
    this.applyHistory(prev);
  }

  redo(): void {
    const next = this.future.pop();
    if (!next) return;
    this.past.push(this.snapshotForHistory());
    this.applyHistory(next);
  }

  clearHistory(): void {
    this.past = [];
    this.future = [];
  }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  /** Seed a brand-new single-page app. Starts unsaved; page 0 is active. */
  newApp(name = "New app"): void {
    const app = emptyApp(name);
    this.current = app;
    this.activePageId = app.pages[0]?.id ?? null;
    this.savedPath = null;
    this.error = null;
    this.clearHistory();
  }

  /** Seed a new app by wrapping an existing dashboard layout as page 0
   *  (parity / back-compat path). Starts unsaved; page 0 is active. */
  newAppFromDashboard(name: string, layout: unknown): void {
    const app = appFromDashboard(name, layout);
    this.current = app;
    this.activePageId = app.pages[0]?.id ?? null;
    this.savedPath = null;
    this.error = null;
    this.clearHistory();
  }

  /** Load an app from the repository, normalise it, and select page 0. */
  async loadApp(path: string): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      const app = await getApp(path);
      this.current = normaliseApp(app);
      this.activePageId = this.current.pages[0]?.id ?? null;
      this.savedPath = path;
      this.clearHistory();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      this.current = null;
      this.activePageId = null;
    } finally {
      this.loading = false;
    }
  }

  /** Persist the current app to {@code path} under {@code name}. No-op with a
   *  null error surface when there's nothing loaded. */
  async saveApp(path: string, name: string): Promise<void> {
    if (!this.current) return;
    this.error = null;
    const app: SaikuApp = { ...this.current, name };
    try {
      await saveAppApi(path, app);
      this.current = app;
      this.savedPath = path;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      throw e;
    }
  }

  // ------------------------------------------------------------------
  // Navigation
  // ------------------------------------------------------------------

  /** Set the active page. No history capture — pure view state. */
  setActivePage(id: string): void {
    this.activePageId = id;
  }

  // ------------------------------------------------------------------
  // Page CRUD (all immutable)
  // ------------------------------------------------------------------

  /** Append a fresh blank page and make it active. */
  addPage(title?: string): void {
    if (!this.current) return;
    this.captureForUndo();
    const page = emptyPage(title ?? `Page ${this.current.pages.length + 1}`);
    this.current = { ...this.current, pages: [...this.current.pages, page] };
    this.activePageId = page.id;
  }

  /** Replace one page's opaque {@code grid} — used when the embedded dashboard
   *  grid is edited in place (AppPageView write-back in editable mode) so
   *  in-grid tile edits survive a page switch. Immutable (fresh pages array +
   *  page object). No history capture: the dashboard grid renderer owns its
   *  own undo stack, so recording app-level undo here would double up. No-op
   *  when the id isn't present or the grid is referentially unchanged. */
  updatePageGrid(id: string, grid: unknown): void {
    if (!this.current) return;
    const target = this.current.pages.find((p) => p.id === id);
    if (!target || target.grid === grid) return;
    const pages: AppPage[] = this.current.pages.map((p) => (p.id === id ? { ...p, grid } : p));
    this.current = { ...this.current, pages };
  }

  /** Rename the page with {@code id}. No-op when the id isn't present. */
  renamePage(id: string, title: string): void {
    if (!this.current) return;
    if (!this.current.pages.some((p) => p.id === id)) return;
    this.captureForUndo();
    const pages: AppPage[] = this.current.pages.map((p) => (p.id === id ? { ...p, title } : p));
    this.current = { ...this.current, pages };
  }

  /** Move the page at {@code fromIdx} to {@code toIdx}. No-op when the indices
   *  are equal or out of range. */
  reorderPage(fromIdx: number, toIdx: number): void {
    if (!this.current) return;
    const pages = this.current.pages;
    if (fromIdx === toIdx) return;
    if (fromIdx < 0 || fromIdx >= pages.length) return;
    if (toIdx < 0 || toIdx >= pages.length) return;
    this.captureForUndo();
    const next = [...pages];
    const [moved] = next.splice(fromIdx, 1);
    next.splice(toIdx, 0, moved);
    this.current = { ...this.current, pages: next };
  }

  /** Delete the page with {@code id}. REFUSES to delete the last remaining page
   *  (a one-page app must always have a page) — that's a no-op. When the active
   *  page is deleted, the active selection falls back to the first survivor. */
  deletePage(id: string): void {
    if (!this.current) return;
    if (this.current.pages.length <= 1) return; // refuse to delete the last page
    if (!this.current.pages.some((p) => p.id === id)) return;
    this.captureForUndo();
    const pages: AppPage[] = this.current.pages.filter((p) => p.id !== id);
    this.current = { ...this.current, pages };
    if (this.activePageId === id) {
      this.activePageId = pages[0]?.id ?? null;
    }
  }

  // ------------------------------------------------------------------
  // Nav / theme (immutable)
  // ------------------------------------------------------------------

  /** Set the nav position (rail / top). */
  setNav(position: AppNav["position"]): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, nav: { ...this.current.nav, position } };
  }

  /** Merge a partial theme patch into a fresh theme object. */
  setTheme(patch: Partial<AppTheme>): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, theme: { ...this.current.theme, ...patch } };
  }

  // ------------------------------------------------------------------
  // Reset
  // ------------------------------------------------------------------

  /** Wipe every field back to first-load state. */
  reset(): void {
    this.current = null;
    this.activePageId = null;
    this.loading = false;
    this.error = null;
    this.savedPath = null;
    this.clearHistory();
  }
}

export const appDoc = new AppDocStore();
