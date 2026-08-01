<script lang="ts">
  /*
   * Left icon+label navigation rail for the App Builder shell — one item per
   * page, the active page highlighted, collapsible to icons-only. In `editable`
   * mode it exposes an add-page button and inline rename (double-click a label).
   * Below the responsive breakpoint the shell renders it as a bottom bar
   * (`narrow` prop) — collapse is suppressed there.
   *
   * Presentational: it emits `onSelect` / `onAddPage` / `onRename`; the shell
   * wires those to the appDoc store.
   *
   * Deferred: reorder-drag of pages is not implemented here (the store already
   * has reorderPage(); wiring drag is a follow-up task).
   */
  import type { AppPage } from "$lib/api/apps";
  import {
    LayoutDashboard,
    Plus,
    PanelLeftClose,
    PanelLeftOpen,
    House,
    ChartColumnBig,
    Boxes,
    Users,
    Settings,
    Sparkles,
    TrendingUp,
    Table,
  } from "lucide-svelte";

  /** Named page icons an author can set via AppPage.icon. Falls back to the
   *  generic dashboard glyph. Keeps the rail legible when collapsed to icons. */
  const ICONS: Record<string, typeof LayoutDashboard> = {
    home: House,
    house: House,
    chart: ChartColumnBig,
    trend: TrendingUp,
    cube: Boxes,
    boxes: Boxes,
    users: Users,
    people: Users,
    settings: Settings,
    sparkles: Sparkles,
    table: Table,
  };

  interface Props {
    pages: AppPage[];
    activeId: string | null;
    editable: boolean;
    onSelect: (id: string) => void;
    onAddPage?: () => void;
    onRename?: (id: string, title: string) => void;
    /** Render as a horizontal bottom bar (narrow viewport). */
    narrow?: boolean;
    /** Start collapsed to icons-only (app.nav.railCollapsed). */
    defaultCollapsed?: boolean;
    /** Brand mark shown at the rail top: a logo URL or a short letter. */
    brand?: { logo?: string | null; label?: string } | null;
    /** Pinned footer: a settings gear and/or a user-avatar disc (initials). */
    footer?: { settings?: boolean; avatar?: string } | null;
  }

  let {
    pages,
    activeId,
    editable,
    onSelect,
    onAddPage,
    onRename,
    narrow = false,
    defaultCollapsed = false,
    brand = null,
    footer = null,
  }: Props = $props();

  let collapsed = $state(defaultCollapsed);
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

<nav
  class="saiku-app__rail"
  class:saiku-app__rail--collapsed={collapsed && !narrow}
  class:saiku-app__rail--narrow={narrow}
  aria-label="App pages"
>
  {#if brand && !narrow}
    <div class="saiku-app__rail-brand" aria-hidden="true">
      {#if brand.logo}
        <img src={brand.logo} alt="" />
      {:else if brand.label}
        <span>{brand.label}</span>
      {/if}
    </div>
  {/if}
  <ul class="saiku-app__rail-list">
    {#each pages as page (page.id)}
      {@const Icon = (page.icon && ICONS[page.icon]) || LayoutDashboard}
      <li>
        {#if editable && renamingId === page.id}
          <!-- svelte-ignore a11y_autofocus -->
          <input
            class="saiku-app__rail-rename"
            bind:value={draft}
            onkeydown={onRenameKey}
            onblur={commitRename}
            autofocus
            aria-label="Rename page"
          />
        {:else}
          <button
            type="button"
            class="saiku-app__rail-item"
            class:is-active={page.id === activeId}
            aria-current={page.id === activeId ? "page" : undefined}
            title={page.title}
            onclick={() => onSelect(page.id)}
            ondblclick={() => startRename(page)}
          >
            <Icon size={18} aria-hidden="true" />
            <span class="saiku-app__rail-label">{page.title}</span>
          </button>
        {/if}
      </li>
    {/each}
    {#if editable && onAddPage}
      <li>
        <button
          type="button"
          class="saiku-app__rail-item saiku-app__rail-add"
          title="Add page"
          onclick={() => onAddPage?.()}
        >
          <Plus size={16} aria-hidden="true" />
          <span class="saiku-app__rail-label">Add page</span>
        </button>
      </li>
    {/if}
  </ul>

  {#if footer && !narrow}
    <div class="saiku-app__rail-footer">
      {#if footer.settings}
        <button type="button" class="saiku-app__rail-gear" title="Settings" aria-label="Settings">
          <Settings size={18} aria-hidden="true" />
        </button>
      {/if}
      {#if footer.avatar}
        <div class="saiku-app__rail-avatar" title={footer.avatar} aria-hidden="true">
          {footer.avatar}
        </div>
      {/if}
    </div>
  {/if}

  {#if !narrow}
    <button
      type="button"
      class="saiku-app__rail-collapse"
      title={collapsed ? "Expand navigation" : "Collapse navigation"}
      aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}
      aria-pressed={collapsed}
      onclick={() => (collapsed = !collapsed)}
    >
      {#if collapsed}
        <PanelLeftOpen size={16} aria-hidden="true" />
      {:else}
        <PanelLeftClose size={16} aria-hidden="true" />
      {/if}
    </button>
  {/if}
</nav>

<style>
  .saiku-app__rail {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    width: 12rem;
    flex-shrink: 0;
    padding: 0.5rem;
    box-sizing: border-box;
    background: var(--saiku-app-bg, var(--bg-subtle, hsl(var(--bg))));
    border-right: 1px solid hsl(var(--border));
    overflow-y: auto;
    font-family: var(--saiku-app-font, inherit);
  }
  .saiku-app__rail--collapsed {
    width: 3.5rem;
    align-items: center;
  }
  .saiku-app__rail-brand {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border-radius: 9px;
    flex-shrink: 0;
    margin: 4px 0 10px;
    overflow: hidden;
    background: var(--saiku-app-accent, #2e5e43);
    color: #fff;
    font-family: Georgia, "Times New Roman", serif;
    font-weight: 700;
    font-size: 1.15rem;
    line-height: 1;
  }
  .saiku-app__rail-brand img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
  .saiku-app__rail-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    flex: 1;
  }
  .saiku-app__rail-item {
    display: flex;
    align-items: center;
    gap: 0.625rem;
    width: 100%;
    padding: 0.5rem 0.625rem;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: var(--saiku-app-fg, hsl(var(--fg-muted)));
    font: inherit;
    text-align: left;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
  }
  .saiku-app__rail-item:hover {
    background: hsl(var(--bg-hover));
    color: var(--saiku-app-fg, hsl(var(--fg)));
  }
  .saiku-app__rail-item.is-active {
    background: var(--saiku-app-accent, hsl(var(--accent)));
    color: var(--saiku-app-primary, hsl(var(--primary)));
    font-weight: 600;
  }
  .saiku-app__rail-label {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .saiku-app__rail--collapsed .saiku-app__rail-label {
    display: none;
  }
  .saiku-app__rail-add {
    color: hsl(var(--fg-muted));
  }
  .saiku-app__rail-rename {
    width: 100%;
    padding: 0.4375rem 0.5rem;
    border: 1px solid hsl(var(--border-strong));
    border-radius: 6px;
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font: inherit;
    box-sizing: border-box;
  }
  /* Pinned footer: settings gear + avatar disc at the rail bottom. */
  .saiku-app__rail-footer {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0 0.25rem;
    margin-top: 0.25rem;
  }
  .saiku-app__rail-gear {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 2rem;
    height: 2rem;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: var(--saiku-app-rail-muted, #5f7a68);
    cursor: pointer;
  }
  .saiku-app__rail-gear:hover {
    background: rgba(255, 255, 255, 0.06);
    color: #e6eee8;
  }
  .saiku-app__rail-avatar {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 2rem;
    height: 2rem;
    border-radius: 50%;
    background: var(--saiku-app-avatar-bg, #3f5a49);
    color: #eaf3ec;
    font-family: -apple-system, "Segoe UI", sans-serif;
    font-size: 0.66rem;
    font-weight: 700;
    letter-spacing: 0.02em;
    flex-shrink: 0;
  }
  .saiku-app__rail-collapse {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0.375rem;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: hsl(var(--fg-muted));
    cursor: pointer;
  }
  .saiku-app__rail-collapse:hover {
    background: hsl(var(--bg-hover));
    color: hsl(var(--fg));
  }

  /* Narrow viewport: horizontal bottom bar pinned to the foot of the shell. */
  .saiku-app__rail--narrow {
    flex-direction: row;
    width: 100%;
    border-right: none;
    border-top: 1px solid hsl(var(--border));
    overflow-x: auto;
    overflow-y: hidden;
  }
  .saiku-app__rail--narrow .saiku-app__rail-list {
    flex-direction: row;
  }
  .saiku-app__rail--narrow .saiku-app__rail-item {
    flex-direction: column;
    gap: 0.25rem;
    padding: 0.375rem 0.75rem;
    font-size: 0.75rem;
  }
</style>
