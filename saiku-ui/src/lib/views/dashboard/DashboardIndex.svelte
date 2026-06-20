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
  import { Button } from "$lib/components/ui";
  import { goto } from "$app/navigation";
  import { base } from "$app/paths";
  import {
    flatten,
    getResourceAcl,
    listRepository,
    moveResource,
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
  import AddFolderModal from "$lib/modals/AddFolderModal.svelte";
  import MoveRepositoryObject from "$lib/modals/MoveRepositoryObject.svelte";
  import RenameFolderModal from "$lib/modals/RenameFolderModal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";
  import EmptyState from "$lib/components/EmptyState.svelte";
  import DashboardIndexFilters from "$lib/views/dashboard/DashboardIndexFilters.svelte";
  import DashboardCatalogueTree from "$lib/views/dashboard/DashboardCatalogueTree.svelte";
  import DashboardCataloguePinned from "$lib/views/dashboard/DashboardCataloguePinned.svelte";
  import {
    Copy,
    ShieldCheck,
    LayoutDashboard,
    Star,
    FolderInput,
  } from "lucide-svelte";
  import {
    applyCatalogueFilters,
    collectOwners,
    collectTags,
    type CatalogueEntry,
    type SortKey,
  } from "$lib/dashboard/catalogueFilter";
  import {
    buildFolderTree,
    collectFolderPaths,
    joinPath,
    moveDashboardPath,
    parentFolder,
    renameFolderMoves,
    type CatalogueLeaf,
    type FolderNode,
  } from "$lib/dashboard/catalogueTree";

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

  /* --- issue #937: folder / hierarchy view ----------------------------- */
  /** Catalogue layout: the flat "list" (existing behaviour) or the folder
   *  "tree" that groups dashboards by their repository path. */
  let viewMode = $state<"list" | "tree">("list");
  /** Folder paths the user has expanded in the tree. Starts empty; the
   *  top-level folders auto-expand via {@link autoExpandedFolders}. */
  let expandedFolders = $state<Set<string>>(new Set());
  /** Freshly-created empty folders that hold no dashboards yet — seeded
   *  into the tree so a "New folder" action shows up immediately even
   *  before anything is moved into it. Cleared on refresh (the repo
   *  listing is the source of truth once a dashboard lands inside). */
  let extraFolders = $state<string[]>([]);

  /** "New folder" modal: the parent folder it will be created under. */
  let newFolderParent = $state<string | null>(null);
  /** "Move dashboard" modal: the relative path of the dashboard to move. */
  let movingPath = $state<string | null>(null);
  /** "Rename folder" modal: the folder path being renamed. */
  let renamingFolder = $state<string | null>(null);
  /** Set true while a move / rename round-trip is in flight to disable
   *  the triggering controls. */
  let movingBusy = $state<boolean>(false);
  /* --- end issue #937 block -------------------------------------------- */

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

  /* --- issue #937: folder-tree derivations + actions ------------------- */

  /** Project the (already search/tag/owner-filtered, but otherwise the
   *  same) catalogue entries into folder-tree leaves keyed by their
   *  repo-relative path. Reuses {@link filteredEntries} so the tree
   *  honours the search box + chips exactly like the list does. */
  let folderTree = $derived<FolderNode>(
    buildFolderTree(
      filteredEntries.map(
        (e): CatalogueLeaf => ({
          path: toRepoRelative(e.path),
          title: e.title,
          basename: e.basename,
        }),
      ),
      extraFolders,
    ),
  );

  /** Full folder set (built from ALL entries, ignoring the active search /
   *  tag / owner filters) plus any freshly-created empty folders, so the
   *  move-target picker always lists every destination — not just folders
   *  that happen to match the current filter. The leading "" is the root,
   *  letting a dashboard be moved up to the top level. */
  let allFolderPaths = $derived<string[]>([
    "",
    ...collectFolderPaths(
      buildFolderTree(
        entries.map(
          (n): CatalogueLeaf => ({
            path: toRepoRelative(n.path),
            title: null,
            basename: basename(toRepoRelative(n.path)),
          }),
        ),
        extraFolders,
      ),
    ),
  ]);

  /** First entry into the tree view seeds the expanded set with the
   *  top-level folders so the catalogue opens to a useful depth without
   *  fighting the user's subsequent toggles. Guarded so it only runs once
   *  per view-mode switch (not on every tree re-derive). */
  let treeSeeded = $state<boolean>(false);
  $effect(() => {
    if (viewMode === "tree" && !treeSeeded) {
      expandedFolders = new Set(folderTree.folders.map((f) => f.path));
      treeSeeded = true;
    } else if (viewMode === "list") {
      treeSeeded = false;
    }
  });

  function isExpanded(path: string): boolean {
    return expandedFolders.has(path);
  }

  function toggleFolder(path: string): void {
    const next = new Set(expandedFolders);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    expandedFolders = next;
  }

  function openNewFolder(parent: string): void {
    newFolderParent = parent;
  }

  function onCreateFolder(name: string): void {
    const parent = newFolderParent ?? "";
    newFolderParent = null;
    const path = joinPath(parent, name.trim());
    // Repository folders are implicit (created on first file save). We add
    // the new folder to the local seed list + expand it so the user can
    // immediately move dashboards into it. It persists server-side as soon
    // as a dashboard is saved/moved under it.
    if (!extraFolders.includes(path)) extraFolders = [...extraFolders, path];
    const next = new Set(expandedFolders);
    next.add(parent);
    next.add(path);
    expandedFolders = next;
    toasts.success(i18n.t("dashboard.folder.created"), path);
  }

  function openMove(relPath: string): void {
    movingPath = relPath;
  }

  async function onMoveDashboard(targetFolder: string): Promise<void> {
    const src = movingPath;
    movingPath = null;
    if (!src) return;
    const dest = moveDashboardPath(src, targetFolder);
    if (dest === src) return; // no-op move (already in target folder)
    movingBusy = true;
    try {
      await moveResource(src, dest);
      // Drop the now-stale extra-folder seed for the source's old parent if
      // it became empty — refresh re-derives folders from the listing.
      recentDashboards.remove(src);
      favouriteDashboards.remove(src);
      await refresh();
      toasts.success(i18n.t("dashboard.folder.moved"), dest);
    } catch (e: unknown) {
      toasts.danger(
        i18n.t("dashboard.folder.moveFailed"),
        e instanceof Error ? e.message : String(e),
      );
    } finally {
      movingBusy = false;
    }
  }

  function openRenameFolder(path: string): void {
    renamingFolder = path;
  }

  async function onRenameFolder(newName: string): Promise<void> {
    const folder = renamingFolder;
    renamingFolder = null;
    if (!folder) return;
    // A repository folder has no rename primitive — renaming a folder is
    // the set of moves of every dashboard under it. Compute them purely,
    // then issue one move per file.
    const dashPaths = entries.map((n) => toRepoRelative(n.path));
    const moves = renameFolderMoves(folder, newName, dashPaths);
    if (moves.length === 0) {
      // An empty folder (only in the local seed list) — just rewrite the
      // seed entry so the rename is reflected without any server round-trip.
      const parent = parentFolder(folder);
      const renamed = joinPath(parent, newName.trim());
      extraFolders = extraFolders.map((f) => (f === folder ? renamed : f));
      toasts.success(i18n.t("toast.renamed"), renamed);
      return;
    }
    movingBusy = true;
    try {
      for (const m of moves) {
        await moveResource(m.from, m.to);
        recentDashboards.remove(m.from);
        favouriteDashboards.remove(m.from);
      }
      await refresh();
      toasts.success(i18n.t("toast.renamed"), newName.trim());
    } catch (e: unknown) {
      toasts.danger(
        i18n.t("toast.renameFailed"),
        e instanceof Error ? e.message : String(e),
      );
    } finally {
      movingBusy = false;
    }
  }
  /* --- end issue #937 block -------------------------------------------- */
</script>

<div class="page">
  <header class="head">
    <h1>Dashboards</h1>
    <Button onclick={handleNew} disabled={creating}>
      {creating ? "Creating…" : "+ New dashboard"}
    </Button>
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
    <DashboardIndexFilters
      bind:searchQuery
      bind:sortKey
      bind:viewMode
      showClearFilters={!!searchQuery || selectedTags.length > 0 || selectedOwners.length > 0}
      onClearFilters={clearFilters}
      onNewFolder={() => openNewFolder("")}
    />

    {#if viewMode === "list"}
      <DashboardCataloguePinned
        favourites={favouriteEntries}
        recents={recentEntries}
        onToggleFavourite={toggleFavourite}
        {basename}
      />
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
                class="chip {selected ? 'chip--on' : ''}"
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
                class="chip {selected ? 'chip--on' : ''}"
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

    <!-- Shared per-dashboard action row, used by both the flat list and the
         folder tree. `showMove` adds the #937 "move to folder" button. -->
    {#snippet dashboardRow(relPath: string, label: string, showMove: boolean)}
      {@const isFav = favouriteDashboards.isFavourite(relPath)}
      <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
        <span class="name">{label}</span>
        <span class="path">{relPath}</span>
      </a>
      <Button variant="outline" class="icon-only star {isFav ? 'star--on' : ''}" onclick={() => toggleFavourite(relPath)} title={isFav ? "Remove from favourites" : "Add to favourites"} aria-label={isFav ? "Remove from favourites" : "Add to favourites"} aria-pressed={isFav}>
        <Star size={14} fill={isFav ? "currentColor" : "none"} />
      </Button>
      {#if showMove}
        <Button variant="outline" disabled={movingBusy} onclick={() => openMove(relPath)} title={i18n.t("dashboard.folder.move")} aria-label={i18n.t("dashboard.folder.move")}>
          <FolderInput size={14} />
        </Button>
      {/if}
      {#if session.isAdmin}
        <Button variant="outline" disabled={aclLoading} onclick={() => void openAcl(relPath)} title={i18n.t("saved.permissions")} aria-label={i18n.t("saved.permissions")}>
          <ShieldCheck size={14} />
        </Button>
      {/if}
      <Button variant="outline" disabled={duplicatingPath === relPath} onclick={() => void handleDuplicate(relPath)} title="Duplicate" aria-label="Duplicate dashboard">
        <Copy size={14} />
      </Button>
      <Button variant="destructive" onclick={() => handleDelete(relPath)} title="Delete">
        Delete
      </Button>
    {/snippet}

    {#if viewMode === "list"}
      <ul class="list">
        {#each filteredEntries as e (e.path)}
          {@const relPath = toRepoRelative(e.path)}
          <li class="row">
            {@render dashboardRow(relPath, e.title ?? e.basename, false)}
          </li>
        {/each}
      </ul>
    {:else}
      <!-- Tree rendering lives in the dedicated delegate (saiku#1234). -->
      <DashboardCatalogueTree
        {folderTree}
        bind:expandedFolders
        {duplicatingPath}
        {movingBusy}
        {aclLoading}
        onToggleFolder={toggleFolder}
        onNewFolder={openNewFolder}
        onRenameFolder={openRenameFolder}
        onToggleFavourite={toggleFavourite}
        onMove={openMove}
        onOpenAcl={(p) => void openAcl(p)}
        onDuplicate={(p) => void handleDuplicate(p)}
        onDelete={handleDelete}
      />
    {/if}
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

<!-- #937 folder dialogs -->
<AddFolderModal
  parentPath={newFolderParent ?? ""}
  open={newFolderParent !== null}
  onCreate={onCreateFolder}
  onCancel={() => (newFolderParent = null)}
/>

{#if movingPath !== null}
  <MoveRepositoryObject
    sourcePath={movingPath}
    folders={allFolderPaths}
    open={true}
    onMove={(f) => void onMoveDashboard(f)}
    onCancel={() => (movingPath = null)}
  />
{/if}

{#if renamingFolder !== null}
  <RenameFolderModal
    folderPath={renamingFolder}
    open={true}
    onRename={(n) => void onRenameFolder(n)}
    onCancel={() => (renamingFolder = null)}
  />
{/if}

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
  /* .pinned / .pinned-heading moved to DashboardCataloguePinned. */
  /* Icon-only buttons are square and sit on the same baseline as the
     Delete button; the star colour shifts to --accent when active so
     a glance tells you which dashboards you've pinned. */
  /* .catalogue-filters / .view-toggle / .view-btn styles relocated to
     DashboardIndexFilters.svelte (saiku#1234) — Svelte's scoped CSS
     does not cross component boundaries. */
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

  /* #937 folder tree + #1234 pinned styles moved into their delegates
     (DashboardCatalogueTree / DashboardCataloguePinned). */
</style>
