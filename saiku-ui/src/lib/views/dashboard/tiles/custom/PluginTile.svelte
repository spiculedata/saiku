<script lang="ts">
  /*
   * Custom tile renderer: `plugin` — ARBITRARY author JavaScript in a
   * locked-down iframe sandbox (App Builder Phase 2, Task 7, saiku#1441).
   *
   * SECURITY-CRITICAL. This is the ONLY tile that runs untrusted author code.
   * Containment rests on four independent layers, each of which a reviewer will
   * try to break:
   *   1. iframe sandbox="allow-scripts" ONLY — no allow-same-origin (that would
   *      dissolve the origin barrier), no forms/popups/top-navigation/modals/
   *      downloads/pointer-lock. The frame runs at the opaque "null" origin, so
   *      it cannot read the parent DOM, cookies, localStorage, or the session
   *      token.
   *   2. A strict CSP (see pluginBridge.PLUGIN_CSP) injected as the first <head>
   *      element: default-src 'none' + connect-src 'none' → the plugin has NO
   *      network egress (fetch/XHR/WebSocket/EventSource/sendBeacon all blocked),
   *      no remote scripts/styles/fonts/frames/workers; only inline script/style
   *      and data: images.
   *   3. A per-mount cryptographic nonce authenticates every plugin→host
   *      message (the frame's origin is "null", so event.origin is worthless).
   *   4. event.source === iframe.contentWindow gates the listener so one tile's
   *      host never processes another frame's messages.
   *
   * The host reads ONLY the typed, validated message kinds from
   * handlePluginMessage — the raw plugin payload NEVER touches the host DOM and
   * is NEVER rendered with {@html}. A `filter` request is re-resolved against the
   * LIVE cube (searchMembers + pickMemberUniqueName, the same path the built-in
   * chart tiles use) and DROPPED if it doesn't resolve to a real member unique
   * name — a plugin cannot inject arbitrary MDX/filters.
   *
   * Data + query reuse: fetches through the SAME shared hook the built-in tiles
   * use (TileQueryState + runTileQueryEffect); records are posted INTO the frame
   * on `ready` and on every refresh.
   */

  import { onDestroy } from "svelte";
  import type { DashboardTile, DashboardFilter, CubeRef } from "$lib/api/dashboards";
  import { TileQueryState, runTileQueryEffect } from "$lib/hooks/useTileQuery.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import { type SchemaLike } from "$lib/dashboard/effectiveQuery";
  import { searchMembers } from "$lib/api/aiQuery";
  import { pickMemberUniqueName } from "$lib/dashboard/clickFilterMember";
  import {
    buildSrcdoc,
    handlePluginMessage,
    PLUGIN_MIN_H,
    type FilterSel,
  } from "$lib/dashboard/custom/pluginBridge";
  import { theme } from "$lib/stores/theme.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import TileLoading from "../TileLoading.svelte";
  import TileError from "../TileError.svelte";
  import TileEmpty from "../TileEmpty.svelte";

  interface Props {
    tile: DashboardTile;
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  // The author's self-contained plugin HTML (Task 8 wires the admin registry
  // that serves it; for now it rides on tile.custom.options.html so the tile is
  // renderable/testable). Non-string / absent → no plugin configured.
  let pluginHtml = $derived(
    typeof tile.custom?.options?.html === "string" ? (tile.custom.options.html as string) : "",
  );
  let hasPlugin = $derived(pluginHtml.trim().length > 0);
  // Non-html options the plugin may want as config — never includes the html
  // source, never any host/session data.
  let pluginOptions = $derived.by(() => {
    const opts = { ...(tile.custom?.options ?? {}) } as Record<string, unknown>;
    delete opts.html;
    return opts;
  });

  // Fresh cryptographic nonce per tile mount — NOT derived from the tile id,
  // NOT Math.random. This authenticates every message from THIS frame.
  const nonce = crypto.randomUUID();
  // Recompute the srcdoc only when the author html or nonce changes. A new
  // srcdoc reloads the frame (and re-fires its `ready`).
  let srcdoc = $derived(hasPlugin ? buildSrcdoc(pluginHtml, nonce) : "");

  let iframe = $state<HTMLIFrameElement | null>(null);
  let frameReady = $state(false);
  // null → fill the tile (100%); a number → an explicit plugin-requested height
  // (already clamped to [MIN, MAX] by the bridge).
  let frameHeight = $state<number | null>(null);
  // Last error the plugin reported — rendered as TEXT in the tile chrome.
  let pluginError = $state<string | null>(null);

  const q = new TileQueryState();
  let response = $derived(q.response);
  let loading = $derived(q.loading);
  let error = $derived(q.error);
  function retry(): void {
    q.retry();
  }

  let schema = $state<SchemaLike | null>(null);
  let resolvedCube = $state<CubeRef | null>(null);

  $effect(() => {
    if (tile.cube) resolvedCube = tile.cube;
  });

  $effect(() => {
    void schemaCache.version;
    if (!resolvedCube) {
      schema = null;
      return;
    }
    const cached = schemaCache.peek(resolvedCube) as SchemaLike | null;
    if (cached) schema = cached;
    else void schemaCache.get(resolvedCube).catch(() => {});
  });

  let isEmpty = $derived(
    !!response && response.status === "SUCCESS" && (response.data?.length ?? 0) === 0,
  );
  let hasEffectiveFilters = $derived(
    activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0),
  );
  function resetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

  // Fetch through the shared hook — reuse the exact chart/table plumbing.
  $effect(() => {
    void q.retryTick;
    void q.refreshTick;
    runTileQueryEffect(q, {
      tile,
      activeFilters: activeFilters.all,
      schema,
      sharedResponse: null,
    });
  });

  // ── host → plugin: post the typed payloads INTO the frame ────────────────
  // targetOrigin "*" is correct here: an opaque sandboxed frame has origin
  // "null", so no specific origin can be named; only THAT frame's window
  // receives the message (we hold its contentWindow directly).
  function postToFrame(type: "init" | "data" | "theme", payload: unknown): void {
    const win = iframe?.contentWindow;
    if (!win) return;
    win.postMessage({ type, nonce, payload }, "*");
  }

  let records = $derived(response?.status === "SUCCESS" ? (response.data ?? []) : []);

  // On `ready`: send init (author config), then the current data + theme. The
  // frameReady flag also lets later data/theme changes push updates in.
  $effect(() => {
    if (!frameReady) return;
    // Read reactive deps so this re-posts on change.
    const data = records;
    const eff = theme.effective;
    postToFrame("init", { options: pluginOptions });
    postToFrame("data", data);
    postToFrame("theme", { effective: eff });
  });

  // ── plugin → host: the single message listener ───────────────────────────
  function onMessage(event: MessageEvent): void {
    // GUARD 1: only messages from THIS tile's frame. Ignore every other frame
    // (other plugin tiles, the top window, injected frames). Do NOT trust
    // event.origin — a sandboxed frame's origin is the string "null".
    if (!iframe || event.source !== iframe.contentWindow) return;
    // GUARD 2 (in the bridge): nonce authentication + shape validation.
    const msg = handlePluginMessage(event.data, nonce);
    if (!msg) return;
    switch (msg.kind) {
      case "ready":
        frameReady = true;
        break;
      case "resize":
        frameHeight = msg.height; // already clamped to [MIN, MAX]
        break;
      case "filter":
        void resolveAndEmitFilter(msg.sel);
        break;
      case "error":
        pluginError = msg.message; // rendered as TEXT, never {@html}
        break;
    }
  }

  $effect(() => {
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  });

  // A frame reload (new srcdoc) invalidates readiness until the new document
  // posts its own `ready`.
  $effect(() => {
    void srcdoc;
    frameReady = false;
  });

  onDestroy(() => {
    window.removeEventListener("message", onMessage);
  });

  /**
   * Resolve a plugin-requested filter selection against the LIVE cube and emit
   * only real member unique names. Each requested member string is treated as a
   * caption and re-resolved via searchMembers + pickMemberUniqueName (the exact
   * path the built-in chart tiles use) — so a plugin can NEVER inject arbitrary
   * MDX. If nothing resolves, the filter is DROPPED (no emit).
   */
  async function resolveAndEmitFilter(sel: FilterSel): Promise<void> {
    if (!onClickFilter) return;
    const cube = resolvedCube;
    if (!cube) return;
    const { dimension, hierarchy, level, members } = sel;
    if (!dimension || !level || members.length === 0) return;
    const resolved: string[] = [];
    for (const member of members) {
      if (!member) continue;
      const hits = await searchMembers(cube, dimension, hierarchy, level, member);
      const uniqueName = pickMemberUniqueName(hits, member);
      if (uniqueName) resolved.push(uniqueName);
    }
    // Drop entirely when nothing resolved — never push a bare/invalid member.
    if (resolved.length === 0) return;
    onClickFilter({ dimension, hierarchy, level, members: resolved });
  }
</script>

{#if !hasPlugin}
  <div class="p-3 text-fg-muted text-sm">
    No plugin configured yet — open ⚙ to paste the plugin HTML.
  </div>
{:else if !tile.query}
  <div class="p-3 text-fg-muted text-sm">
    Tile has no query binding — open ⚙ to set one.
  </div>
{:else}
  <div class="plugin-tile">
    <!--
      sandbox is EXACTLY "allow-scripts" — nothing else. srcdoc carries the
      strict-CSP document from buildSrcdoc. referrerpolicy no-referrer as a
      belt-and-braces measure (the CSP already blocks all egress).
    -->
    <iframe
      bind:this={iframe}
      title={tile.title ?? "Plugin tile"}
      sandbox="allow-scripts"
      referrerpolicy="no-referrer"
      {srcdoc}
      style={frameHeight === null
        ? "height:100%"
        : `height:${Math.max(PLUGIN_MIN_H, frameHeight)}px`}
    ></iframe>
    {#if pluginError}
      <div class="plugin-error" role="alert">{pluginError}</div>
    {/if}
    {#if loading && !response}
      <div class="overlay solid"><TileLoading variant="chart" /></div>
    {:else if error}
      <div class="overlay solid"><TileError message={error} onRetry={retry} /></div>
    {:else if isEmpty}
      <div class="overlay solid">
        <TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} />
      </div>
    {/if}
  </div>
{/if}

<style>
  .plugin-tile {
    position: relative;
    height: 100%;
    width: 100%;
    overflow: auto;
  }
  iframe {
    display: block;
    width: 100%;
    min-height: 40px;
    border: 0;
    background: var(--bg);
  }
  .plugin-error {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    padding: 4px 8px;
    font-size: 12px;
    color: var(--danger, #b91c1c);
    background: var(--bg);
    border-top: 1px solid var(--border, #e5e7eb);
    z-index: 2;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg);
    color: var(--fg-muted);
    z-index: 1;
  }
  .overlay.solid {
    pointer-events: auto;
  }
</style>
