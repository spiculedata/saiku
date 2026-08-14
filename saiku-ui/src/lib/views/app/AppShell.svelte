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
  import { onMount, type Snippet } from "svelte";
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
    firstAppCube,
  } from "$lib/views/app/appShell";
  import { appSkinCss } from "$lib/views/app/appSkin";
  import { provideAppThemeSignature } from "$lib/views/app/appThemeContext";
  import {
    MAX_LEVEL_OPTIONS,
    effectiveLabel,
    isLevelSourced,
    levelOptionsTruncated,
    optionByLabel,
    optionsFromMembers,
    selectionFor,
  } from "$lib/views/app/contextPill";
  import { fetchLevelMembers } from "$lib/views/app/levelMembers";
  import type { AppContextPillOption } from "$lib/api/apps";
  import { session } from "$lib/stores/session.svelte";
  import type { TextTokenContext } from "$lib/views/app/textTokens";
  import { decodeAppFilterState } from "$lib/dashboard/urlFilterState";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { searchMembers } from "$lib/api/aiQuery";
  import { pickMemberUniqueName } from "$lib/dashboard/clickFilterMember";
  import AppHeader from "$lib/views/app/AppHeader.svelte";
  import AppNavRail from "$lib/views/app/AppNavRail.svelte";
  import AppTopNav from "$lib/views/app/AppTopNav.svelte";
  import AppPageView from "$lib/views/app/AppPageView.svelte";
  import AppAssistant from "$lib/views/app/AppAssistant.svelte";


  interface Props {
    app: SaikuApp;
    editable?: boolean;
    /** Header controls (share / present / theme) — forwarded to AppHeader. */
    controls?: Snippet;
    /** Selection model (edit mode): a double-click on a chrome element opens
     *  the App Inspector on the matching section. */
    onEditChrome?: (section: "theme" | "header" | "nav" | "assistant" | "pages") => void;
  }

  let { app, editable = false, controls, onEditChrome }: Props = $props();

  let rootEl = $state<HTMLDivElement | null>(null);

  const inlineThemeVars = $derived(themeVarsStyle(app));
  // Canvas-based tiles can't repaint from a CSS-var change on their own; this
  // is the dependency their render effect reads to know a re-theme happened.
  provideAppThemeSignature(() => inlineThemeVars);
  const rail = $derived(isRailNav(app));
  const activeId = $derived(resolveActivePageId(app, appDoc.activePageId));
  const activePage = $derived(app.pages.find((p) => p.id === activeId) ?? null);

  // ------------------------------------------------------------------
  // Responsive: below the shared stack breakpoint the rail becomes a
  // bottom bar. Reuses DEFAULT_STACK_BREAKPOINT (no new magic number).
  // ------------------------------------------------------------------
  let narrow = $state(false);
  /** Rail runs the whole shell height with the header beside it. Never on a
   *  narrow layout — there the rail collapses to a bottom bar. */
  const railFull = $derived(rail && !narrow && !!app.nav.railFullHeight);
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

  /* ------------------------------------------------------------------
   * Header context selector (the "STORE / Portland #14 ▾" control).
   *
   * The selection is pushed as an APP-source filter under one synthetic
   * source id: the store re-uses that slot on every push, so switching store
   * replaces the previous selection rather than stacking filters. Selecting an
   * "All" entry removes the entry entirely (an empty member list is no
   * constraint).
   *
   * It is an app-level filter, NOT a click (saiku#1754): clicks are page state
   * and AppPageView wipes them on every page switch, so a pill pushed as a
   * click filtered only the page it was set on while the header went on
   * displaying the selection everywhere — a page of national numbers under a
   * header claiming a region. The app layer survives the page hydrate.
   * ------------------------------------------------------------------ */
  const CONTEXT_PILL_SOURCE = "app-context-pill";

  let contextValue = $state<string | undefined>(undefined);

  /* Options read from the bound cube level, when the pill sources them that
   * way. A hand-typed list goes stale as soon as a store opens or is renamed;
   * the cube is the thing that actually knows. Empty until loaded, and empty
   * forever if the fetch fails — the pill then renders as static text, which
   * is the same as not having been configured. */
  let contextOptions = $state<AppContextPillOption[]>([]);
  let contextTruncated = $state(false);

  $effect(() => {
    const pill = app.header?.contextPill;
    if (!isLevelSourced(pill)) {
      contextOptions = [];
      contextTruncated = false;
      return;
    }
    const cube = firstAppCube(app);
    const f = pill!.filter!;
    let cancelled = false;
    void fetchLevelMembers(cube, f.dimension, f.hierarchy, f.level).then((members) => {
      if (cancelled) return;
      contextOptions = optionsFromMembers(pill, members);
      contextTruncated = levelOptionsTruncated(members.length);
      if (contextTruncated) {
        // Never present a partial list as if it were the whole level.
        console.warn(
          `[saiku] context selector: ${f.level} has ${members.length} members; showing the first ${MAX_LEVEL_OPTIONS}. A pill is the wrong control for a level this large.`,
        );
      }
    });
    return () => {
      cancelled = true;
    };
  });

  const pillLabel = $derived(
    effectiveLabel(app.header?.contextPill, contextValue, contextOptions),
  );

  /** The live values page chrome binds against (see textTokens.ts). Recomputed
   *  whenever the selection, the app or the signed-in user changes; `now` is
   *  sampled per recompute, which is as fresh as a heading needs. */
  const tokenContext = $derived<TextTokenContext>({
    username: session.current?.username,
    appName: app.name,
    context: pillLabel,
    filters: app.header?.contextPill?.filter?.dimension
      ? { [app.header.contextPill.filter.dimension]: pillLabel }
      : {},
    allLabel: "All",
  });
  // Caption -> unique-name lookups, so re-picking a store doesn't re-hit the
  // members endpoint.
  const contextMemberCache = new Map<string, Promise<string | null>>();

  function handleContextChange(label: string): void {
    const pill = app.header?.contextPill;
    contextValue = label;
    const selection = selectionFor(pill, optionByLabel(pill, label, contextOptions));
    if (selection.kind === "none") return;
    if (selection.kind === "clear") {
      // Remove the selection rather than registering an empty one — a filter
      // with no members is no constraint, and emitting it produced a stray
      // "(any)" chip plus an invalid zero-member `in` on every tile.
      activeFilters.clearApp(CONTEXT_PILL_SOURCE);
      return;
    }
    if (selection.kind === "set") {
      activeFilters.pushApp(selection.filter, CONTEXT_PILL_SOURCE);
      return;
    }
    // "resolve": the author typed a caption, so ask the cube for its real
    // unique name before filtering. A miss leaves the filter untouched rather
    // than pushing a member no tile could match.
    const cube = firstAppCube(app);
    if (!cube) return;
    const { dimension, hierarchy, level } = selection.target;
    const key = `${dimension}/${hierarchy}/${level}|${selection.caption}`;
    let lookup = contextMemberCache.get(key);
    if (!lookup) {
      lookup = searchMembers(cube, dimension, hierarchy, level, selection.caption)
        .then((hits) => pickMemberUniqueName(hits, selection.caption))
        .catch(() => null);
      contextMemberCache.set(key, lookup);
    }
    void lookup.then((uniqueName) => {
      if (!uniqueName) {
        contextMemberCache.delete(key); // don't cache a miss — allow a retry
        console.warn(`[saiku] context pill: no member matched "${selection.caption}" in ${level}`);
        return;
      }
      activeFilters.pushApp({ ...selection.target, members: [uniqueName] }, CONTEXT_PILL_SOURCE);
    });
  }

  /* Deep-link restore for the context selection (saiku#1754). The URL carries
   * the selected LABEL (`ctx`), and we replay it through handleContextChange —
   * the same path a viewer's pick takes — so the pill's displayed value and the
   * filter it registers are produced together and cannot disagree. Deferred
   * until the pill's cube-sourced options have loaded, since resolving a label
   * to a member needs them. */
  let contextRestored = false;
  let pendingContextLabel = $state<string | null>(null);
  onMount(() => {
    if (typeof window === "undefined") return;
    const { contextLabel } = decodeAppFilterState(new URL(window.location.href).searchParams);
    if (!contextLabel) return;
    pendingContextLabel = contextLabel;
  });
  $effect(() => {
    const label = pendingContextLabel;
    if (!label || contextRestored) return;
    // A typed-list pill is ready immediately; a cube-sourced one must wait for
    // its members, or the label resolves against an empty option set.
    const pill = app.header?.contextPill;
    if (isLevelSourced(pill) && contextOptions.length === 0) return;
    contextRestored = true;
    pendingContextLabel = null;
    handleContextChange(label);
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

<!-- One definition, two possible positions: beside the header (full-height rail)
     or inside the body below it. A snippet keeps the props in a single place. -->
{#snippet navRail()}
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
    onSettings={onEditChrome ? () => onEditChrome("theme") : undefined}
  />
{/snippet}

<div
  bind:this={rootEl}
  class="saiku-app"
  class:saiku-app--narrow={narrow}
  class:saiku-app--rail-full={railFull}
  data-saiku-app={appScopeId(app)}
  data-saiku-app-edit={editable ? "" : undefined}
  style={inlineThemeVars}
>
  {#if railFull}{@render navRail()}{/if}

  <div class="saiku-app__frame">
    <AppHeader
      {app}
      {controls}
      {contextValue}
      {contextOptions}
      onContextChange={handleContextChange}
      onSelect={onEditChrome ? () => onEditChrome("header") : undefined} />

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
      {#if rail && !narrow && !railFull}{@render navRail()}{/if}

      <main class="saiku-app__main">
        {#if activePage}
          <!-- Renders the active page's grid through the EXISTING dashboard
               renderer (see AppPageView). NOT keyed on page id: AppPageView must
               stay mounted across page switches so it can preserve each page's
               filter state (its per-page memory) — it re-hydrates the shared
               dashboard store itself when the active page changes. -->
          <AppPageView
            page={activePage}
            {editable}
            tokens={tokenContext}
            contextLabel={contextValue ?? null}
          />
        {:else}
          <div class="saiku-app__page">No page</div>
        {/if}
      </main>

      {#if app.assistantSlot.enabled && !narrow}
        <AppAssistant slot={app.assistantSlot} fallbackCube={firstAppCube(app)} />
      {/if}
    </div>
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
    background: var(--saiku-app-bg, hsl(var(--bg)));
    color: var(--saiku-app-fg, hsl(var(--fg)));
    font-family: var(--saiku-app-font, inherit);
  }
  /* The header + nav + body column. Always present; when the rail is
     full-height the shell turns into a row and this becomes its second cell,
     which is what puts the rail alongside the header instead of under it. */
  .saiku-app__frame {
    display: flex;
    flex-direction: column;
    flex: 1 1 auto;
    min-width: 0;
    min-height: 0;
  }
  .saiku-app--rail-full {
    flex-direction: row;
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
