<script lang="ts">
  /*
   * Horizontal tab-bar navigation for the App Builder shell — the top-nav
   * counterpart to AppNavRail. Same props/behaviour (one tab per page, active
   * highlighted, editable add-page + inline rename) but laid out as a
   * horizontal, overflow-scrolling bar under the header. Wraps/scrolls on
   * narrow viewports rather than collapsing to a bottom bar.
   *
   * Presentational: emits `onSelect` / `onAddPage` / `onRename`; the shell
   * wires those to the appDoc store.
   *
   * Deferred: reorder-drag of tabs is not implemented (store has reorderPage()).
   */
  import type { AppPage } from "$lib/api/apps";
  import { LayoutDashboard, Plus } from "lucide-svelte";

  interface Props {
    pages: AppPage[];
    activeId: string | null;
    editable: boolean;
    onSelect: (id: string) => void;
    onAddPage?: () => void;
    onRename?: (id: string, title: string) => void;
  }

  let { pages, activeId, editable, onSelect, onAddPage, onRename }: Props = $props();

  let renamingId = $state<string | null>(null);
  let draft = $state("");

  function startRename(page: AppPage): void {
    if (!editable) return;
    renamingId = page.id;
    draft = page.title;
  }

  function commitRename(): void {
    const id = renamingId;
    if (id) {
      const title = draft.trim();
      if (title) onRename?.(id, title);
    }
    renamingId = null;
  }

  function onRenameKey(e: KeyboardEvent): void {
    if (e.key === "Enter") {
      e.preventDefault();
      commitRename();
    } else if (e.key === "Escape") {
      e.preventDefault();
      renamingId = null;
    }
  }
</script>

<nav class="saiku-app__topnav" aria-label="App pages">
  <ul class="saiku-app__topnav-list">
    {#each pages as page (page.id)}
      <li>
        {#if editable && renamingId === page.id}
          <!-- svelte-ignore a11y_autofocus -->
          <input
            class="saiku-app__topnav-rename"
            bind:value={draft}
            onkeydown={onRenameKey}
            onblur={commitRename}
            autofocus
            aria-label="Rename page"
          />
        {:else}
          <button
            type="button"
            class="saiku-app__topnav-tab"
            class:is-active={page.id === activeId}
            aria-current={page.id === activeId ? "page" : undefined}
            title={page.title}
            onclick={() => onSelect(page.id)}
            ondblclick={() => startRename(page)}
          >
            <LayoutDashboard size={15} aria-hidden="true" />
            <span class="saiku-app__topnav-label">{page.title}</span>
          </button>
        {/if}
      </li>
    {/each}
    {#if editable && onAddPage}
      <li>
        <button
          type="button"
          class="saiku-app__topnav-tab saiku-app__topnav-add"
          title="Add page"
          aria-label="Add page"
          onclick={() => onAddPage?.()}
        >
          <Plus size={15} aria-hidden="true" />
        </button>
      </li>
    {/if}
  </ul>
</nav>

<style>
  .saiku-app__topnav {
    display: flex;
    align-items: stretch;
    padding: 0 0.75rem;
    background: var(--saiku-app-bg, var(--bg-subtle, hsl(var(--bg))));
    border-bottom: 1px solid hsl(var(--border));
    overflow-x: auto;
    font-family: var(--saiku-app-font, inherit);
  }
  .saiku-app__topnav-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    gap: 0.25rem;
    align-items: stretch;
  }
  .saiku-app__topnav-tab {
    display: inline-flex;
    align-items: center;
    gap: 0.4375rem;
    padding: 0.625rem 0.75rem;
    border: none;
    border-bottom: 2px solid transparent;
    background: transparent;
    color: var(--saiku-app-fg, hsl(var(--fg-muted)));
    font: inherit;
    white-space: nowrap;
    cursor: pointer;
  }
  .saiku-app__topnav-tab:hover {
    color: var(--saiku-app-fg, hsl(var(--fg)));
  }
  .saiku-app__topnav-tab.is-active {
    color: var(--saiku-app-primary, hsl(var(--primary)));
    border-bottom-color: var(--saiku-app-primary, hsl(var(--primary)));
    font-weight: 600;
  }
  .saiku-app__topnav-add {
    color: hsl(var(--fg-muted));
  }
  .saiku-app__topnav-rename {
    margin: 0.375rem 0;
    padding: 0.375rem 0.5rem;
    border: 1px solid hsl(var(--border-strong));
    border-radius: 6px;
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font: inherit;
  }
</style>
