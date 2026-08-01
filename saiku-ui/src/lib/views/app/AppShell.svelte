<script lang="ts">
  /*
   * App Builder shell — the top-level presentational frame for a .saikuapp:
   * branded header, configurable navigation (left rail OR top tabs), the active
   * page region, a reserved assistant column (Phase 5), and the theme /
   * custom-CSS application.
   *
   * Theme: `themeVars(app.theme)` is serialised to an inline `style` on the root
   * so `var(--saiku-app-*)` resolves for everything inside.
   *
   * Custom CSS (SECURITY CONTRACT — Task 3): author CSS is ALWAYS routed through
   * sanitiseAndScopeCss() (scoped to this app's `[data-saiku-app]` root, and
   * fail-closed) and applied by setting a style element's textContent. It is
   * NEVER html-injected and never touches innerHTML.
   *
   * Active page is driven by the appDoc store (activePageId, with a fallback to
   * page 0); nav selection routes back through appDoc.setActivePage.
   */
  import type { Snippet } from "svelte";
  import type { SaikuApp } from "$lib/api/apps";
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { DEFAULT_STACK_BREAKPOINT } from "$lib/dashboard/responsiveLayout";
  import {
    themeVarsStyle,
    scopedCustomCss,
    appScopeId,
    rootSelectorFor,
    isRailNav,
    resolveActivePageId,
  } from "$lib/views/app/appShell";
  import { appSkinCss } from "$lib/views/app/appSkin";
  import AppHeader from "$lib/views/app/AppHeader.svelte";
  import AppNavRail from "$lib/views/app/AppNavRail.svelte";
  import AppTopNav from "$lib/views/app/AppTopNav.svelte";
  import AppPageView from "$lib/views/app/AppPageView.svelte";
  import AppAssistant from "$lib/views/app/AppAssistant.svelte";

  /** First cube bound to any tile across the app — the assistant's fallback
   *  scope when the assistant slot doesn't name one explicitly. */
  function firstAppCube(a: SaikuApp): AppAssistantCube | null {
    for (const p of a.pages) {
      const grid = p.grid as { tiles?: Array<{ cube?: AppAssistantCube }> } | null;
      for (const t of grid?.tiles ?? []) {
        if (t.cube?.connectionName && t.cube?.cubeName) return t.cube;
      }
    }
    return null;
  }
  type AppAssistantCube = { connectionName: string; catalog: string; schema: string; cubeName: string };

  interface Props {
    app: SaikuApp;
    editable?: boolean;
    /** Header controls (share / present / theme) — forwarded to AppHeader. */
    controls?: Snippet;
    /** Selection model (edit mode): a double-click on a chrome element opens
     *  the App Inspector on the matching section. */
    onEditChrome?: (section: "header" | "nav" | "assistant") => void;
  }

  let { app, editable = false, controls, onEditChrome }: Props = $props();

  let rootEl = $state<HTMLDivElement | null>(null);

  const inlineThemeVars = $derived(themeVarsStyle(app));
  const rail = $derived(isRailNav(app));
  const activeId = $derived(resolveActivePageId(app, appDoc.activePageId));
  const activePage = $derived(app.pages.find((p) => p.id === activeId) ?? null);

  // ------------------------------------------------------------------
  // Responsive: below the shared stack breakpoint the rail becomes a
  // bottom bar. Reuses DEFAULT_STACK_BREAKPOINT (no new magic number).
  // ------------------------------------------------------------------
  let narrow = $state(false);
  $effect(() => {
    if (typeof window === "undefined" || !window.matchMedia) return;
    const mq = window.matchMedia(`(max-width: ${DEFAULT_STACK_BREAKPOINT}px)`);
    const sync = () => (narrow = mq.matches);
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  });

  // ------------------------------------------------------------------
  // Custom CSS injection. Recomputes whenever the (sanitised, scoped) CSS
  // or the shell root changes; sets textContent (never innerHTML / html
  // injection) and removes the style node on teardown. Writing to the DOM (not to
  // $state) keeps this clear of the effect re-entrancy trap.
  // ------------------------------------------------------------------
  $effect(() => {
    const host = rootEl;
    if (!host) return;
    // Built-in token-driven skin FIRST (lower precedence)…
    const skinEl = document.createElement("style");
    skinEl.setAttribute("data-saiku-app-skin", appScopeId(app));
    skinEl.textContent = appSkinCss(rootSelectorFor(app));
    host.appendChild(skinEl);
    // …then the author's advanced customCss (higher precedence, escape hatch).
    const css = scopedCustomCss(app);
    const styleEl = document.createElement("style");
    styleEl.setAttribute("data-saiku-app-css", appScopeId(app));
    styleEl.textContent = css;
    host.appendChild(styleEl);
    return () => {
      skinEl.remove();
      styleEl.remove();
    };
  });

  function handleSelect(id: string): void {
    appDoc.setActivePage(id);
  }
  function handleAddPage(): void {
    appDoc.addPage();
  }
  function handleRename(id: string, title: string): void {
    appDoc.renamePage(id, title);
  }
</script>

<div
  bind:this={rootEl}
  class="saiku-app"
  class:saiku-app--narrow={narrow}
  data-saiku-app={appScopeId(app)}
  style={inlineThemeVars}
>
  <AppHeader {app} {controls} onSelect={onEditChrome ? () => onEditChrome("header") : undefined} />

  {#if !rail}
    <AppTopNav
      pages={app.pages}
      {activeId}
      {editable}
      onSelect={handleSelect}
      onAddPage={editable ? handleAddPage : undefined}
      onRename={editable ? handleRename : undefined}
    />
  {/if}

  <div class="saiku-app__body">
    {#if rail && !narrow}
      <AppNavRail
        pages={app.pages}
        {activeId}
        {editable}
        onSelect={handleSelect}
        onAddPage={editable ? handleAddPage : undefined}
        onRename={editable ? handleRename : undefined}
        defaultCollapsed={app.nav.railCollapsed}
        brand={{ logo: app.logo, label: app.name.slice(0, 1) }}
        footer={app.nav.footer ?? null}
      />
    {/if}

    <main class="saiku-app__main">
      {#if activePage}
        <!-- Renders the active page's grid through the EXISTING dashboard
             renderer (see AppPageView). NOT keyed on page id: AppPageView must
             stay mounted across page switches so it can preserve each page's
             filter state (its per-page memory) — it re-hydrates the shared
             dashboard store itself when the active page changes. -->
        <AppPageView page={activePage} {editable} />
      {:else}
        <div class="saiku-app__page">No page</div>
      {/if}
    </main>

    {#if app.assistantSlot.enabled && !narrow}
      <AppAssistant slot={app.assistantSlot} fallbackCube={firstAppCube(app)} />
    {/if}
  </div>

  {#if rail && narrow}
    <AppNavRail
      pages={app.pages}
      {activeId}
      {editable}
      onSelect={handleSelect}
      onAddPage={editable ? handleAddPage : undefined}
      onRename={editable ? handleRename : undefined}
      narrow
    />
  {/if}
</div>

<style>
  .saiku-app {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    /* Fill the layout main (a flex row) horizontally — without this the shell
       sizes to its content width, which lets a stacked grid ratchet the whole
       app narrow instead of laying tiles out across the full canvas. */
    flex: 1 1 auto;
    width: 100%;
    min-width: 0;
    box-sizing: border-box;
    background: var(--saiku-app-bg, var(--bg));
    color: var(--saiku-app-fg, var(--fg));
    font-family: var(--saiku-app-font, inherit);
  }
  .saiku-app__body {
    display: flex;
    flex-direction: row;
    flex: 1;
    min-height: 0;
  }
  .saiku-app__main {
    flex: 1;
    min-width: 0;
    overflow: auto;
  }
  .saiku-app__page {
    padding: 1rem;
    height: 100%;
    box-sizing: border-box;
  }
</style>
