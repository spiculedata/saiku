<script lang="ts">
  /*
   * Recursive folder-tree view of the dashboard catalogue — extracted
   * from DashboardIndex (saiku#1234 Phase 2; original feature #937).
   * Owns the folder header + per-folder expand/collapse + per-folder
   * action buttons (new sub-folder / rename) + the per-dashboard row
   * inside each folder. Recurses via Svelte snippet self-reference.
   *
   * State (expandedFolders) is bindable so the parent persists the
   * user's expand/collapse choices across re-derives. Every other
   * action (toggle favourite, move, ACL, duplicate, delete, new
   * sub-folder, rename folder) funnels back to the parent via
   * callbacks — the parent already owns the round-trips + toasts.
   */
  import { base } from "$app/paths";
  import { Button } from "$lib/components/ui";
  import {
    Copy,
    ShieldCheck,
    Star,
    Folder,
    FolderPlus,
    FolderInput,
    Pencil,
    ChevronRight,
    ChevronDown,
  } from "lucide-svelte";
  import type { FolderNode } from "$lib/dashboard/catalogueTree";
  import { favouriteDashboards } from "$lib/stores/favouriteDashboards.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    folderTree: FolderNode;
    expandedFolders: Set<string>;
    /** Per-dashboard "duplicate in flight" sentinel; row disables its
     *  Duplicate button while equal to the row's path. */
    duplicatingPath: string | null;
    /** Set true while a folder move / rename is in flight so per-row
     *  Move + per-folder Rename buttons disable. */
    movingBusy: boolean;
    /** Set true while an ACL load is in flight so per-row ACL buttons
     *  disable (prevents double-fetch). */
    aclLoading: boolean;
    onToggleFolder: (path: string) => void;
    onNewFolder: (parentPath: string) => void;
    onRenameFolder: (path: string) => void;
    onToggleFavourite: (relPath: string) => void;
    onMove: (relPath: string) => void;
    onOpenAcl: (relPath: string) => void;
    onDuplicate: (relPath: string) => void;
    onDelete: (relPath: string) => void;
  }

  let {
    folderTree,
    expandedFolders = $bindable(),
    duplicatingPath,
    movingBusy,
    aclLoading,
    onToggleFolder,
    onNewFolder,
    onRenameFolder,
    onToggleFavourite,
    onMove,
    onOpenAcl,
    onDuplicate,
    onDelete,
  }: Props = $props();

  function isExpanded(path: string): boolean {
    return expandedFolders.has(path);
  }
</script>

{#snippet dashboardRow(relPath: string, label: string)}
  {@const isFav = favouriteDashboards.isFavourite(relPath)}
  <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
    <span class="name">{label}</span>
    <span class="text-fg-muted text-xs overflow-hidden text-ellipsis whitespace-nowrap">{relPath}</span>
  </a>
  <Button variant="outline" class="icon-only star {isFav ? 'star--on' : ''}" onclick={() => onToggleFavourite(relPath)} title={isFav ? "Remove from favourites" : "Add to favourites"} aria-label={isFav ? "Remove from favourites" : "Add to favourites"} aria-pressed={isFav}>
    <Star size={14} fill={isFav ? "currentColor" : "none"} />
  </Button>
  <Button variant="outline" disabled={movingBusy} onclick={() => onMove(relPath)} title={i18n.t("dashboard.folder.move")} aria-label={i18n.t("dashboard.folder.move")}>
    <FolderInput size={14} />
  </Button>
  {#if session.isAdmin}
    <Button variant="outline" disabled={aclLoading} onclick={() => onOpenAcl(relPath)} title={i18n.t("saved.permissions")} aria-label={i18n.t("saved.permissions")}>
      <ShieldCheck size={14} />
    </Button>
  {/if}
  <Button variant="outline" disabled={duplicatingPath === relPath} onclick={() => onDuplicate(relPath)} title="Duplicate" aria-label="Duplicate dashboard">
    <Copy size={14} />
  </Button>
  <Button variant="destructive" onclick={() => onDelete(relPath)} title="Delete">
    Delete
  </Button>
{/snippet}

{#snippet folderBranch(node: FolderNode, depth: number)}
  {@const open = isExpanded(node.path)}
  <li class="tree-folder" style="--depth: {depth}">
    <div
      class="tree-folder-head {open ? 'tree-folder-head--open' : ''}"
      role="button"
      tabindex="0"
      aria-expanded={open}
      onclick={() => onToggleFolder(node.path)}
      onkeydown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onToggleFolder(node.path);
        }
      }}
    >
      <span class="inline-flex items-center text-fg-muted flex-none" aria-hidden="true">
        {#if open}
          <ChevronDown size={14} />
        {:else}
          <ChevronRight size={14} />
        {/if}
      </span>
      <Folder size={15} aria-hidden="true" />
      <span class="font-semibold text-fg">{node.name}</span>
      <span class="tree-folder-count"
        >{node.folders.length + node.dashboards.length}</span
      >
      <span class="tree-folder-actions">
        <Button variant="outline" class="icon-only" onclick={(e) => { e.stopPropagation(); onNewFolder(node.path); }} title={i18n.t("dashboard.folder.new")} aria-label={i18n.t("dashboard.folder.new")}>
          <FolderPlus size={14} />
        </Button>
        <Button variant="outline" class="icon-only" disabled={movingBusy} onclick={(e) => { e.stopPropagation(); onRenameFolder(node.path); }} title={i18n.t("dashboard.folder.rename")} aria-label={i18n.t("dashboard.folder.rename")}>
          <Pencil size={14} />
        </Button>
      </span>
    </div>
    {#if open}
      <ul class="list-none m-0 p-0 flex flex-col gap-1">
        {#each node.folders as child (child.path)}
          {@render folderBranch(child, depth + 1)}
        {/each}
        {#if node.dashboards.length === 0 && node.folders.length === 0}
          <li class="tree-empty" style="--depth: {depth + 1}">
            {i18n.t("dashboard.folder.empty")}
          </li>
        {/if}
        {#each node.dashboards as d (d.path)}
          <li class="row tree-row" style="--depth: {depth + 1}">
            {@render dashboardRow(d.path, d.title ?? d.basename)}
          </li>
        {/each}
      </ul>
    {/if}
  </li>
{/snippet}

<ul class="list-none m-0 p-0 flex flex-col gap-1 tree" aria-label={i18n.t("dashboard.view.folders")}>
  {#each folderTree.dashboards as d (d.path)}
    <li class="row">
      {@render dashboardRow(d.path, d.title ?? d.basename)}
    </li>
  {/each}
  {#each folderTree.folders as node (node.path)}
    {@render folderBranch(node, 0)}
  {/each}
  {#if folderTree.folders.length === 0 && folderTree.dashboards.length === 0}
    <li class="tree-empty" style="--depth: 0">
      {i18n.t("dashboard.folder.empty")}
    </li>
  {/if}
</ul>

<style>
/* Mirrors DashboardIndex's row + list styles plus the tree-specific
     overlay. Duplicated rather than inherited because Svelte scoped
     CSS does not cross component boundaries. */
  .row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0.625rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
  }
  .link {
    flex: 1;
    display: flex;
    flex-direction: column;
    text-decoration: none;
    color: var(--fg);
    min-width: 0;
  }
  .link:hover .name {
    color: var(--accent);
  }
  .name {
    font-weight: var(--weight-semibold);
    font-size: 0.9375rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* Tree-specific styles (#937) */
  .tree {
    gap: 0.25rem;
  }
  .tree-folder {
    list-style: none;
  }
  .tree-folder-head {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0.625rem;
    padding-left: calc(0.625rem + var(--depth, 0) * 1.25rem);
    border-radius: 6px;
    cursor: pointer;
    user-select: none;
  }
  .tree-folder-head:hover {
    background: var(--bg-subtle);
  }
  .tree-folder-head:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
  .tree-folder-head:hover .tree-folder-actions,
  .tree-folder-head:focus-within .tree-folder-actions {
    opacity: 1;
  }
  .tree-folder-head > :global(svg) {
    color: var(--accent);
    flex: 0 0 auto;
  }
  .tree-folder-count {
    font-size: 0.6875rem;
    color: var(--fg-muted);
    background: var(--bg-subtle);
    border-radius: 999px;
    padding: 0.0625rem 0.4375rem;
  }
  .tree-folder-actions {
    margin-left: auto;
    display: inline-flex;
    gap: 0.25rem;
    opacity: 0;
    transition: opacity 0.12s ease;
  }
  .tree-row {
    margin-left: calc(var(--depth, 0) * 1.25rem);
  }
  .tree-empty {
    list-style: none;
    font-size: 0.8125rem;
    color: var(--fg-muted);
    font-style: italic;
    padding: 0.375rem 0.5rem;
    padding-left: calc(0.5rem + var(--depth, 0) * 1.25rem);
  }
</style>
