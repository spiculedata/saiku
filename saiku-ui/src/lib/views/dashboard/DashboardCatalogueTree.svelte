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
    <span class="path">{relPath}</span>
  </a>
  <button
    type="button"
    class="btn icon-only star"
    class:star--on={isFav}
    onclick={() => onToggleFavourite(relPath)}
    title={isFav ? "Remove from favourites" : "Add to favourites"}
    aria-label={isFav ? "Remove from favourites" : "Add to favourites"}
    aria-pressed={isFav}
  >
    <Star size={14} fill={isFav ? "currentColor" : "none"} />
  </button>
  <button
    type="button"
    class="btn"
    disabled={movingBusy}
    onclick={() => onMove(relPath)}
    title={i18n.t("dashboard.folder.move")}
    aria-label={i18n.t("dashboard.folder.move")}
  >
    <FolderInput size={14} />
  </button>
  {#if session.isAdmin}
    <button
      type="button"
      class="btn"
      disabled={aclLoading}
      onclick={() => onOpenAcl(relPath)}
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
    onclick={() => onDuplicate(relPath)}
    title="Duplicate"
    aria-label="Duplicate dashboard"
  >
    <Copy size={14} />
  </button>
  <button
    type="button"
    class="btn danger"
    onclick={() => onDelete(relPath)}
    title="Delete"
  >
    Delete
  </button>
{/snippet}

{#snippet folderBranch(node: FolderNode, depth: number)}
  {@const open = isExpanded(node.path)}
  <li class="tree-folder" style="--depth: {depth}">
    <div
      class="tree-folder-head"
      class:tree-folder-head--open={open}
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
      <span class="tree-chevron" aria-hidden="true">
        {#if open}
          <ChevronDown size={14} />
        {:else}
          <ChevronRight size={14} />
        {/if}
      </span>
      <Folder size={15} aria-hidden="true" />
      <span class="tree-folder-name">{node.name}</span>
      <span class="tree-folder-count"
        >{node.folders.length + node.dashboards.length}</span
      >
      <span class="tree-folder-actions">
        <button
          type="button"
          class="btn icon-only"
          onclick={(e) => {
            e.stopPropagation();
            onNewFolder(node.path);
          }}
          title={i18n.t("dashboard.folder.new")}
          aria-label={i18n.t("dashboard.folder.new")}
        >
          <FolderPlus size={14} />
        </button>
        <button
          type="button"
          class="btn icon-only"
          disabled={movingBusy}
          onclick={(e) => {
            e.stopPropagation();
            onRenameFolder(node.path);
          }}
          title={i18n.t("dashboard.folder.rename")}
          aria-label={i18n.t("dashboard.folder.rename")}
        >
          <Pencil size={14} />
        </button>
      </span>
    </div>
    {#if open}
      <ul class="tree-children">
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

<ul class="list tree" aria-label={i18n.t("dashboard.view.folders")}>
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
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }
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
  .path {
    color: var(--fg-muted);
    font-size: 0.75rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .btn {
    padding: 0.375rem 0.625rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.8125rem;
    color: var(--fg);
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
  }
  .btn:hover {
    background: var(--bg-subtle);
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .btn.icon-only {
    padding: 0.375rem;
  }
  .btn.danger {
    color: var(--danger);
    border-color: var(--danger);
  }
  .btn.danger:hover {
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }
  .star {
    color: var(--fg-muted);
  }
  .star--on {
    color: var(--accent);
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
  .tree-folder-head--open {
    background: var(--bg-subtle);
  }
  .tree-folder-head:hover .tree-folder-actions,
  .tree-folder-head:focus-within .tree-folder-actions {
    opacity: 1;
  }
  .tree-chevron {
    display: inline-flex;
    align-items: center;
    color: var(--fg-muted);
    flex: 0 0 auto;
  }
  .tree-folder-head > :global(svg) {
    color: var(--accent);
    flex: 0 0 auto;
  }
  .tree-folder-name {
    font-weight: var(--weight-semibold);
    color: var(--fg);
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
  .tree-children {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
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
