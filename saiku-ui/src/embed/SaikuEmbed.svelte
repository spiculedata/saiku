<!--
  `npm run check` will warn `options_missing_custom_element` on this
  file. It's a false positive: svelte-check sweeps the whole project
  against the SvelteKit tsconfig which doesn't set the customElement
  compile flag — only `vite.config.embed.ts` does, and that's the
  config that actually builds this file (`build:embed`). Warning is
  exit-0 so CI's `npm run check` still passes; the bundle works.
-->
<svelte:options
  customElement={{
    tag: "saiku-embed",
    shadow: "open",
    props: {
      server: { type: "String", attribute: "server", reflect: false },
      token: { type: "String", attribute: "token", reflect: false },
      kind: { type: "String", attribute: "kind", reflect: false },
      path: { type: "String", attribute: "path", reflect: false },
      render: { type: "String", attribute: "render", reflect: false },
      mode: { type: "String", attribute: "mode", reflect: false },
      height: { type: "String", attribute: "height", reflect: false },
    },
  }}
/>

<script lang="ts">
  /*
   * <saiku-embed> Web Component — drops into React / Vue / vanilla HTML
   * host pages identically (Svelte 5 customElement compile target).
   *
   * v3 attribute surface (cumulative):
   *   server  — origin of the Saiku launcher, e.g. https://demo.saiku.bi
   *   token   — opaque embed token from POST /saiku/api/embed/tokens.
   *             Omit for anonymous public reads.
   *   kind    — "query" (default) or "dashboard"
   *   path    — repository path; ends .saiku for kind=query,
   *             .saikudash for kind=dashboard
   *   render  — for kind=query only: "table" (default) or "chart"
   *   mode    — for render=chart only: "bar" (default), "line", "pie"
   *   height  — CSS height for the rendered surface (default 400px)
   *
   * The kind-aware fetch path lets dashboards walk their own tiles
   * and chart mode swaps the table for an ECharts canvas. Everything
   * is data-driven from $effect so attribute changes drive re-renders.
   */
  import EmbedTable from "./EmbedTable.svelte";
  import EmbedChart from "./EmbedChart.svelte";
  import EmbedDashboard from "./EmbedDashboard.svelte";
  import { fetchSavedQuery, EmbedFetchError } from "./api";
  import type { EmbedRow } from "./types";

  interface Props {
    server?: string;
    token?: string;
    kind?: string;
    path?: string;
    render?: string;
    mode?: string;
    height?: string;
  }

  let {
    server = "",
    token = "",
    kind = "query",
    path = "",
    render = "table",
    mode = "bar",
    height = "400px",
  }: Props = $props();

  let rows = $state<EmbedRow[] | null>(null);
  let error = $state<string | null>(null);
  let loading = $state(false);

  /* Re-fetch whenever the load-bearing inputs change. Empty server / path
   * holds the component in an idle state — useful while a host React
   * tree is hydrating attributes asynchronously. Dashboard kind defers
   * to <EmbedDashboard /> which manages its own lifecycle, so we only
   * run the query fetch here for kind=query. */
  $effect(() => {
    const s = server.trim();
    const p = path.trim();
    const t = token.trim();
    const k = kind.trim() || "query";
    if (k !== "query") {
      // Dashboards manage their own fetches (one for the layout, then
      // one per tile under runAs). Reset so a kind switch doesn't show
      // stale query rows.
      rows = null;
      error = null;
      return;
    }
    if (!s || !p) {
      rows = null;
      error = null;
      return;
    }
    let cancelled = false;
    loading = true;
    error = null;
    fetchSavedQuery(s, p, t || undefined)
      .then((resp) => {
        if (cancelled) return;
        rows = resp.data ?? [];
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        error = friendlyError(e);
        rows = null;
      })
      .finally(() => {
        if (!cancelled) loading = false;
      });
    return () => {
      cancelled = true;
    };
  });

  /** Turn a fetch / server error into one user-facing line. We deliberately
   *  avoid surfacing the raw EMBED_INVALID body — the host page is a third
   *  party that doesn't need to know whether the failure was an expired
   *  token, a wrong path, or a revoke. */
  function friendlyError(e: unknown): string {
    if (e instanceof EmbedFetchError) {
      if (e.status === 401) return "This embed is unavailable.";
      return e.body.error ?? `Embed failed (${e.status}).`;
    }
    return "Embed failed to load.";
  }
</script>

<div class="w-full h-full overflow-auto" style="min-height: {height};">
  {#if kind === "dashboard"}
    <EmbedDashboard {server} {token} {path} />
  {:else if loading}
    <div class="state">Loading…</div>
  {:else if error}
    <div class="state error" role="alert">{error}</div>
  {:else if rows !== null}
    {#if render === "chart"}
      <EmbedChart {rows} {mode} />
    {:else}
      <EmbedTable {rows} />
    {/if}
  {:else}
    <div class="state muted">
      Configure the embed: <code>server</code> + <code>path</code>.
    </div>
  {/if}
</div>

<style>
/* Shadow-DOM scoped — host page CSS can't leak in and vice versa.
   * Host page can recolour via the --saiku-embed-* CSS variables we
   * surface on :host. */
  :host {
    display: block;
    color: var(--saiku-embed-fg, #1f2937);
    background: var(--saiku-embed-bg, transparent);
  }
  .state {
    padding: 16px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
  }
  code {
    background: rgba(0, 0, 0, 0.05);
    padding: 1px 4px;
    border-radius: 3px;
    font-family: ui-monospace, monospace;
  }
</style>
