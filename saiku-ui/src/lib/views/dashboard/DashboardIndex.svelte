<script lang="ts">
  /*
   * Dashboards landing page. Lists every .saikudash file in the JCR
   * repository, surfaces "Open" / "New dashboard" / "Delete" actions,
   * and is the natural entry point from the topbar.
   *
   * The new-dashboard flow prompts for a name + relative path, creates a
   * fresh Dashboard via newDashboard(), saves it through DashboardResource,
   * and navigates to the editor. The save path is the same one the editor
   * uses (.saikudash extension required by the server).
   */

  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { base } from "$app/paths";
  import {
    flatten,
    getResourceAcl,
    listRepository,
    setResourceAcl,
    type AclEntry,
    type RepositoryNode,
  } from "$lib/api/repository";
  import { listAllRoles } from "$lib/api/admin";
  import {
    deleteDashboard,
    duplicateDashboard,
    loadDashboard,
    newDashboard,
    newTileId,
    normaliseDashboardPath,
    toRepoRelative,
    saveDashboard,
  } from "$lib/api/dashboards";
  import { getTemplate, instantiateTemplate } from "$lib/dashboard/templates";
  import { session } from "$lib/stores/session.svelte";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { favouriteDashboards } from "$lib/stores/favouriteDashboards.svelte";
  import { recentDashboards } from "$lib/stores/recentDashboards.svelte";
  import PermissionsModal from "$lib/modals/PermissionsModal.svelte";
  import NewDashboardModal from "$lib/modals/NewDashboardModal.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";
  import EmptyState from "$lib/components/EmptyState.svelte";
  import { Copy, ShieldCheck, LayoutDashboard, Star } from "lucide-svelte";
  import {
    applyCatalogueFilters,
    collectOwners,
    collectTags,
    type CatalogueEntry,
    type SortKey,
  } from "$lib/dashboard/catalogueFilter";

  let entries = $state<RepositoryNode[]>([]);
  let loading = $state<boolean>(true);
  let loadError = $state<string | null>(null);
  let creating = $state<boolean>(false);
  let createError = $state<string | null>(null);
  let aclEditingPath = $state<string | null>(null);
  let aclInitial = $state<AclEntry | null>(null);
  let aclRoles = $state<string[]>([]);
  let aclLoading = $state<boolean>(false);
  /** Path of the dashboard currently being duplicated — used to disable
   *  the per-row Duplicate button while the load + save round-trip is
   *  in flight, so a double-click doesn't fire two duplicates. */
  let duplicatingPath = $state<string | null>(null);

  let newModalOpen = $state<boolean>(false);
  let deletingPath = $state<string | null>(null);

  /** Title + tags pulled out of each dashboard's JSON. Populated by
   *  {@link enrichEntries} after the listing arrives so the catalogue
   *  can show real names (not filename slugs) and filter on tags. (#935) */
  let catalogueMeta = $state<Map<string, { title: string | null; tags: string[] }>>(new Map());

  let searchQuery = $state<string>("");
  let selectedTags = $state<string[]>([]);
  let selectedOwners = $state<string[]>([]);
  let sortKey = $state<SortKey>("name");

  async function refresh(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      const tree = await listRepository(["saikudash"]);
      const flat = flatten(tree);
      entries = flat.filter(
        (n) => n.type === "FILE" && (n.fileType === "saikudash" || n.path.endsWith(".saikudash")),
      );
      // Kick off the metadata enrichment — the catalogue renders
      // immediately with basename-only labels and lights up real names
      // + tags as each fetch resolves. Doesn't block the UI thread
      // and tolerates per-entry failures (one bad dashboard doesn't
      // poison the whole catalogue). (#935)
      void enrichEntries(entries);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  /** Load each dashboard's JSON in parallel and stash the title + tags
   *  in {@link catalogueMeta}. Per-entry errors are swallowed — a
   *  dashboard with a malformed body just stays "title:null, tags:[]"
   *  and falls back to its basename in the UI. */
  async function enrichEntries(items: ReadonlyArray<RepositoryNode>): Promise<void> {
    const fresh = new Map<string, { title: string | null; tags: string[] }>();
    await Promise.all(
      items.map(async (n) => {
        const relPath = toRepoRelative(n.path);
        try {
          const d = await loadDashboard(relPath);
          fresh.set(n.path, { title: d.name ?? null, tags: d.tags ?? [] });
        } catch {
          fresh.set(n.path, { title: null, tags: [] });
        }
      }),
    );
    catalogueMeta = fresh;
  }

  onMount(() => {
    void refresh();
  });

  function defaultHomePath(): string {
    // Suggest /homes/<user> as the starting prefix so a brand-new user
    // doesn't pick the repo root (which the save endpoint trips on —
    // saiku#878 follow-up known issue).
    const u = session.current?.username;
    return u ? `homes/${u}` : "homes";
  }

  function handleNew(): void {
    createError = null;
    newModalOpen = true;
  }

  async function onNewModalCreate(
    path: string,
    name: string,
    templateId: string | null,
  ): Promise<void> {
    newModalOpen = false;
    creating = true;
    createError = null;
    try {
      const finalPath = normaliseDashboardPath(path, session.current?.username ?? "");
      if (!finalPath.endsWith(".saikudash")) {
        createError = "Path must end with .saikudash.";
        return;
      }
      // Blank → empty dashboard; otherwise seed the chosen starter
      // template's tiles into a fresh dashboard (#938). A stale/unknown
      // template id falls back to blank rather than failing the create.
      const template = templateId ? getTemplate(templateId) : undefined;
      const fresh = template ? instantiateTemplate(template, newTileId, name) : newDashboard(name);
      await saveDashboard(finalPath, fresh);
      await goto(`${base}/dashboards/${finalPath}`);
    } catch (e: unknown) {
      createError = e instanceof Error ? e.message : String(e);
    } finally {
      creating = false;
    }
  }

  function handleDelete(path: string): void {
    deletingPath = path;
  }

  async function confirmDelete(): Promise<void> {
    const path = deletingPath;
    if (!path) return;
    deletingPath = null;
    try {
      await deleteDashboard(path);
      // Drop the path from the per-user recents + favourites so a
      // deleted dashboard doesn't linger as a dead link in either
      // section on next render (#936).
      recentDashboards.remove(path);
      favouriteDashboards.remove(path);
      await refresh();
      toasts.success(i18n.t("toast.deleted"), path);
    } catch (e: unknown) {
      toasts.danger(
        i18n.t("toast.deleteFailed"),
        e instanceof Error ? e.message : String(e),
      );
    }
  }

  /** Duplicate the dashboard at {@code srcPath}: load it so we can
   *  default the new-name prompt to the real {@code Dashboard.name}
   *  (not the filename slug), prompt for a name + path, then call
   *  duplicateDashboard which clones with fresh ids and saves. Issue #939. */
  async function handleDuplicate(srcPath: string): Promise<void> {
    duplicatingPath = srcPath;
    createError = null;
    try {
      const source = await loadDashboard(srcPath);
      // eslint-disable-next-line no-alert
      const rawName = window.prompt("Copy name", `${source.name} (copy)`);
      if (rawName == null) return; // cancelled
      const name = rawName.trim() || `${source.name} (copy)`;
      // eslint-disable-next-line no-alert
      const rawPath = window.prompt(
        "Repository path for the copy",
        defaultHomePath() + "/" + slugify(name) + ".saikudash",
      );
      if (rawPath == null) return;
      let path: string;
      try {
        path = normaliseDashboardPath(rawPath, session.current?.username ?? "");
      } catch (e: unknown) {
        createError = e instanceof Error ? e.message : String(e);
        return;
      }
      if (!path.endsWith(".saikudash")) {
        createError = "Path must end with .saikudash.";
        return;
      }
      await duplicateDashboard(srcPath, path, name);
      await goto(`${base}/dashboards/${path}`);
    } catch (e: unknown) {
      createError = e instanceof Error ? e.message : String(e);
    } finally {
      duplicatingPath = null;
    }
  }

  function slugify(s: string): string {
    return s
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 60) || "dashboard";
  }

  async function openAcl(path: string): Promise<void> {
    aclLoading = true;
    try {
      const [acl, roles] = await Promise.all([getResourceAcl(path), listAllRoles()]);
      aclInitial = acl;
      aclRoles = roles;
      aclEditingPath = path;
    } catch (e: unknown) {
      toasts.danger(i18n.t("toast.aclLoadFailed"), e instanceof Error ? e.message : String(e));
    } finally {
      aclLoading = false;
    }
  }

  async function saveAcl(acl: AclEntry): Promise<void> {
    const path = aclEditingPath;
    if (!path) return;
    try {
      await setResourceAcl(path, acl);
      toasts.success(i18n.t("toast.aclSaved"), path);
      aclEditingPath = null;
      aclInitial = null;
    } catch (e: unknown) {
      toasts.danger(i18n.t("toast.aclSaveFailed"), e instanceof Error ? e.message : String(e));
    }
  }

  function basename(path: string): string {
    const p = path.split("/").pop() ?? path;
    return p.endsWith(".saikudash") ? p.slice(0, -".saikudash".length) : p;
  }

  /** Resolve {@code recentDashboards.all()} / {@code favouriteDashboards.all()}
   *  against the live entry list so paths that no longer exist (or that
   *  the user can't see for ACL reasons) don't show as broken links.
   *  Preserves the source order (most-recent-first for recents,
   *  alphabetical-by-name for favourites). (#936) */
  function resolveEntries(paths: string[]): RepositoryNode[] {
    const byPath = new Map(entries.map((e) => [e.path, e]));
    const out: RepositoryNode[] = [];
    for (const p of paths) {
      const found = byPath.get(p);
      if (found) out.push(found);
    }
    return out;
  }

  let recentEntries = $derived(resolveEntries(recentDashboards.all()));
  let favouriteEntries = $derived(
    resolveEntries(
      favouriteDashboards
        .all()
        .slice()
        .sort((a, b) => basename(a).localeCompare(basename(b))),
    ),
  );

  function toggleFavourite(path: string): void {
    favouriteDashboards.toggle(path);
  }

  /** Project the raw repo listing into the enriched CatalogueEntry shape
   *  the filter helpers expect. Title/tags come from {@link catalogueMeta}
   *  when the per-dashboard fetch has landed; otherwise nulls/empties.
   *  Owner + modified pass straight through from the listing response. */
  let catalogueEntries = $derived<CatalogueEntry[]>(
    entries.map((n) => {
      const meta = catalogueMeta.get(n.path);
      return {
        path: n.path,
        basename: basename(toRepoRelative(n.path)),
        title: meta?.title ?? null,
        tags: meta?.tags ?? [],
        owner: n.owner ?? null,
        modified: n.modified ?? 0,
      };
    }),
  );

  let filteredEntries = $derived(
    applyCatalogueFilters(catalogueEntries, {
      search: searchQuery,
      tags: selectedTags,
      owners: selectedOwners,
      sort: sortKey,
    }),
  );

  let availableTags = $derived(collectTags(catalogueEntries));
  let availableOwners = $derived(collectOwners(catalogueEntries));

  function toggleSelected(list: string[], value: string): string[] {
    return list.includes(value) ? list.filter((x) => x !== value) : [...list, value];
  }

  function clearFilters(): void {
    searchQuery = "";
    selectedTags = [];
    selectedOwners = [];
  }
</script>

<div class="page">
  <header class="head">
    <h1>Dashboards</h1>
    <button type="button" class="btn primary" onclick={handleNew} disabled={creating}>
      {creating ? "Creating…" : "+ New dashboard"}
    </button>
  </header>

  {#if createError}
    <div class="error">{createError}</div>
  {/if}

  {#if loading}
    <Skeleton rows={4} variant="list" />
  {:else if loadError}
    <div class="error">{loadError}</div>
  {:else if entries.length === 0}
    <EmptyState
      icon={LayoutDashboard}
      title="No dashboards yet"
      description="Build your first dashboard from cubes, queries, and KPIs."
      action={{ label: "+ New dashboard", onClick: handleNew }}
    />
  {:else}
    <section class="catalogue-filters" aria-label="Catalogue filters">
      <input
        type="search"
        class="search"
        placeholder="Search dashboards by name or path…"
        bind:value={searchQuery}
        aria-label="Search dashboards"
      />
      <label class="sort">
        <span>Sort:</span>
        <select bind:value={sortKey} aria-label="Sort dashboards">
          <option value="name">Name</option>
          <option value="modified-desc">Last modified ↓</option>
          <option value="modified-asc">Last modified ↑</option>
        </select>
      </label>
      {#if searchQuery || selectedTags.length > 0 || selectedOwners.length > 0}
        <button type="button" class="btn btn--ghost" onclick={clearFilters}>
          Clear filters
        </button>
      {/if}
    </section>

    {#if favouriteEntries.length > 0}
      <section class="pinned" aria-labelledby="favourites-heading">
        <h2 id="favourites-heading" class="pinned-heading">
          <Star size={14} aria-hidden="true" /> Favourites
        </h2>
        <ul class="list">
          {#each favouriteEntries as e (e.path)}
            {@const relPath = toRepoRelative(e.path)}
            <li class="row">
              <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
                <span class="name">{basename(relPath)}</span>
                <span class="path">{relPath}</span>
              </a>
              <button
                type="button"
                class="btn icon-only star star--on"
                onclick={() => toggleFavourite(relPath)}
                title="Remove from favourites"
                aria-label="Remove from favourites"
                aria-pressed="true"
              >
                <Star size={14} fill="currentColor" />
              </button>
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if recentEntries.length > 0}
      <section class="pinned" aria-labelledby="recents-heading">
        <h2 id="recents-heading" class="pinned-heading">🕒 Recently viewed</h2>
        <ul class="list">
          {#each recentEntries as e (e.path)}
            {@const relPath = toRepoRelative(e.path)}
            <li class="row">
              <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
                <span class="name">{basename(relPath)}</span>
                <span class="path">{relPath}</span>
              </a>
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if availableTags.length > 0 || availableOwners.length > 0}
      <section class="filter-chips" aria-label="Tag and owner filters">
        {#if availableTags.length > 0}
          <div class="chip-group" aria-label="Filter by tag">
            <span class="chip-group-label">Tags</span>
            {#each availableTags as t (t)}
              {@const selected = selectedTags.includes(t)}
              <button
                type="button"
                class="chip"
                class:chip--on={selected}
                aria-pressed={selected}
                onclick={() => (selectedTags = toggleSelected(selectedTags, t))}
              >
                {t}
              </button>
            {/each}
          </div>
        {/if}
        {#if availableOwners.length > 0}
          <div class="chip-group" aria-label="Filter by owner">
            <span class="chip-group-label">Owners</span>
            {#each availableOwners as o (o)}
              {@const selected = selectedOwners.includes(o)}
              <button
                type="button"
                class="chip"
                class:chip--on={selected}
                aria-pressed={selected}
                onclick={() => (selectedOwners = toggleSelected(selectedOwners, o))}
              >
                {o}
              </button>
            {/each}
          </div>
        {/if}
      </section>
    {/if}

    <ul class="list">
      {#each filteredEntries as e (e.path)}
        {@const relPath = toRepoRelative(e.path)}
        {@const isFav = favouriteDashboards.isFavourite(relPath)}
        <li class="row">
          <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
            <span class="name">{e.title ?? e.basename}</span>
            <span class="path">{relPath}</span>
          </a>
          <button
            type="button"
            class="btn icon-only star"
            class:star--on={isFav}
            onclick={() => toggleFavourite(relPath)}
            title={isFav ? "Remove from favourites" : "Add to favourites"}
            aria-label={isFav ? "Remove from favourites" : "Add to favourites"}
            aria-pressed={isFav}
          >
            <Star size={14} fill={isFav ? "currentColor" : "none"} />
          </button>
          {#if session.isAdmin}
            <button
              type="button"
              class="btn"
              disabled={aclLoading}
              onclick={() => void openAcl(relPath)}
              title={i18n.t("saved.permissions")}
              aria-label={i18n.t("saved.permissions")}
            >
              <ShieldCheck size={14} />
            </button>
          {/if}
          <button
            type="button"
            class="btn"
            disabled={duplicatingPath === relPath}
            onclick={() => void handleDuplicate(relPath)}
            title="Duplicate"
            aria-label="Duplicate dashboard"
          >
            <Copy size={14} />
          </button>
          <button type="button" class="btn danger" onclick={() => handleDelete(relPath)} title="Delete">
            Delete
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

{#if aclEditingPath && aclInitial}
  <PermissionsModal
    open={true}
    path={aclEditingPath}
    allRoles={aclRoles}
    initial={aclInitial}
    onSave={saveAcl}
    onCancel={() => {
      aclEditingPath = null;
      aclInitial = null;
    }}
  />
{/if}

<NewDashboardModal
  defaultName="Untitled dashboard"
  defaultFolder={defaultHomePath()}
  open={newModalOpen}
  onCreate={onNewModalCreate}
  onCancel={() => (newModalOpen = false)}
/>

<ConfirmModal
  title="Delete dashboard"
  message={deletingPath ? `Delete "${deletingPath}"? This cannot be undone.` : ""}
  confirmLabel="Delete"
  variant="danger"
  open={deletingPath !== null}
  onConfirm={confirmDelete}
  onCancel={() => (deletingPath = null)}
/>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    padding: 1.5rem;
    flex: 1;
    min-width: 0;
    height: 100%;
    box-sizing: border-box;
    overflow-y: auto;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }
  .head h1 {
    margin: 0;
    font-size: 1.25rem;
  }
  .btn {
    padding: 0.5rem 0.875rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .btn.primary {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
  .btn.danger {
    color: var(--danger);
  }
  .btn.danger:hover {
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }
  .error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
    border-radius: 4px;
    font-size: 0.875rem;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
  }
  .row:hover {
    background: var(--bg-subtle);
  }
  .link {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 0.125rem;
    text-decoration: none;
    color: inherit;
    min-width: 0;
  }
  .name {
    font-weight: var(--weight-medium);
  }
  .path {
    font-size: 0.75rem;
    color: var(--fg-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .pinned {
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
  }
  .pinned-heading {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    margin: 0;
    font-size: 0.8125rem;
    font-weight: var(--weight-medium);
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  /* Icon-only buttons are square and sit on the same baseline as the
     Delete button; the star colour shifts to --accent when active so
     a glance tells you which dashboards you've pinned. */
  .btn.icon-only {
    padding: 0.375rem;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .btn.star {
    color: var(--fg-muted);
  }
  .btn.star:hover {
    color: var(--fg);
  }
  .btn.star--on {
    color: var(--accent);
  }
  .btn.star--on:hover {
    color: var(--accent);
  }
  .catalogue-filters {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
  }
  .catalogue-filters .search {
    flex: 1;
    min-width: 12rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.875rem;
  }
  .catalogue-filters .sort {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.8125rem;
    color: var(--fg-muted);
  }
  .catalogue-filters select {
    padding: 0.375rem 0.5rem;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
  }
  .btn--ghost {
    background: transparent;
    border-color: var(--border);
    color: var(--fg-muted);
    font-size: 0.8125rem;
  }
  .filter-chips {
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
  }
  .chip-group {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0.375rem;
  }
  .chip-group-label {
    font-size: 0.6875rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
    font-weight: var(--weight-medium);
    margin-right: 0.25rem;
  }
  .chip {
    padding: 0.25rem 0.625rem;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--bg);
    cursor: pointer;
    font-size: 0.75rem;
    color: var(--fg);
  }
  .chip:hover {
    background: var(--bg-subtle);
  }
  .chip--on {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
  .chip--on:hover {
    background: var(--accent);
  }
</style>
