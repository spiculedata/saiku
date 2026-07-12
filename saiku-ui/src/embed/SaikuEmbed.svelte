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
      space: { type: "String", attribute: "space", reflect: false },
      filter: { type: "String", attribute: "filter", reflect: false },
      theme: { type: "String", attribute: "theme", reflect: true },
    },
  }}
/>

<script lang="ts">
  /*
   * <saiku-embed> Web Component — drops into React / Vue / vanilla HTML
   * host pages identically (Svelte 5 customElement compile target).
   *
   * v4 attribute surface (cumulative):
   *   server  — origin of the Saiku launcher, e.g. https://demo.saiku.bi
   *   token   — opaque embed token from POST /saiku/api/embed/tokens.
   *             Omit for anonymous public reads.
   *   kind    — "query" (default), "dashboard", or "ai"
   *   path    — kind="query": repository path ending .saiku
   *             kind="dashboard": path ending .saikudash
   *             kind="ai": cube ref connection/catalog/schema/cubeName
   *   render  — for kind=query only: "table" (default), "matrix", "chart", "kpi"
   *   mode    — for render=chart only: "bar" (default), "line", "pie"
   *   height  — CSS height for the rendered surface (default 400px)
   *   space   — for kind=ai: Agent Space persona id; scopes the ask
   *             server-side (prompt + skill filter + cube allowlist).
   *   filter  — for kind=query: JSON array of slicer overrides applied at
   *             embed time, e.g. filter='[{"dimension":"Time","level":
   *             "Year","members":["[Time].[2024]"]}]'. Rides the same
   *             validated slicer path the dashboard filter tiles use.
   *   theme   — "dark", "light", or "auto" (follow prefers-color-scheme).
   *             Unset keeps the original light palette so existing embeds
   *             are unchanged.
   *
   * Outbound events (CustomEvent, bubbles + composed so a host listener on
   * the <saiku-embed> element catches them):
   *   saiku:load      — detail {kind, rows} after a query/matrix/kpi loads
   *   saiku:error     — detail {message} when a query load fails
   *   saiku:select    — detail {row} when a table row is clicked
   *   saiku:ai-query  — detail {question, degraded} after an AI ask resolves
   *
   * Everything is data-driven from $effect so attribute changes drive
   * re-renders.
   */
  import EmbedTable from "./EmbedTable.svelte";
  import EmbedChart from "./EmbedChart.svelte";
  import EmbedKpi from "./EmbedKpi.svelte";
  import EmbedDashboard from "./EmbedDashboard.svelte";
  import EmbedMatrix from "./EmbedMatrix.svelte";
  import EmbedAsk from "./EmbedAsk.svelte";
  import { fetchSavedQuery, EmbedFetchError, type EmbedFilterOverride } from "./api";
  import type { EmbedCaption, EmbedMatrixRow, EmbedRow } from "./types";

  interface Props {
    server?: string;
    token?: string;
    kind?: string;
    path?: string;
    render?: string;
    mode?: string;
    height?: string;
    space?: string;
    filter?: string;
    theme?: string;
  }

  let {
    server = "",
    token = "",
    kind = "query",
    path = "",
    render = "table",
    mode = "bar",
    height = "400px",
    space = "",
    filter = "",
  }: Props = $props();

  let rows = $state<EmbedRow[] | null>(null);
  let matrixRows = $state<EmbedMatrixRow[] | null>(null);
  let matrixRowCaptions = $state<EmbedCaption[]>([]);
  let matrixColumnCaptions = $state<EmbedCaption[]>([]);
  let error = $state<string | null>(null);
  let loading = $state(false);
  let rootEl = $state<HTMLDivElement | undefined>(undefined);

  /** Dispatch a namespaced CustomEvent from the shadow root. bubbles + composed
   *  means a host listener attached to the <saiku-embed> element (the event's
   *  ancestor on the composed path) receives it. Guarded so a missing root during
   *  teardown can't throw into the render. */
  function emit(type: string, detail: unknown): void {
    rootEl?.dispatchEvent(new CustomEvent(type, { detail, bubbles: true, composed: true }));
  }

  /** Parse the `filter` attribute (a JSON array of slicer overrides). Returns an
   *  empty array on anything unparseable so a malformed attribute degrades to an
   *  unfiltered query rather than throwing. */
  function parseFilter(raw: string): EmbedFilterOverride[] {
    const s = raw.trim();
    if (!s) return [];
    try {
      const v = JSON.parse(s);
      return Array.isArray(v) ? (v as EmbedFilterOverride[]) : [];
    } catch {
      return [];
    }
  }

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
    const r = (render || "").trim().toLowerCase();
    const f = parseFilter(filter);
    if (k !== "query") {
      // Dashboards + AI ask both manage their own fetches. Reset so a kind
      // switch doesn't show stale query rows.
      rows = null;
      matrixRows = null;
      error = null;
      return;
    }
    // Empty server means same-origin: the fetch helper builds a relative path
    // and the browser resolves it against the host page. Only path is required.
    if (!p) {
      rows = null;
      matrixRows = null;
      error = null;
      return;
    }
    let cancelled = false;
    loading = true;
    error = null;
    const wantMatrix = r === "matrix";
    fetchSavedQuery(s, p, t || undefined, wantMatrix ? "matrix" : "records", f)
      .then((resp) => {
        if (cancelled) return;
        if (wantMatrix) {
          matrixRows = resp.matrix ?? [];
          matrixRowCaptions = resp.metadata?.rows ?? [];
          matrixColumnCaptions = resp.metadata?.columns ?? [];
          rows = null;
          emit("saiku:load", { kind: "matrix", rows: matrixRows.length });
        } else {
          rows = resp.data ?? [];
          matrixRows = null;
          emit("saiku:load", { kind: r === "kpi" ? "kpi" : r || "records", rows: rows.length });
        }
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = friendlyError(e);
        error = msg;
        rows = null;
        matrixRows = null;
        emit("saiku:error", { message: msg });
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

<div class="w-full h-full overflow-auto" style="min-height: {height};" bind:this={rootEl}>
  {#if kind === "dashboard"}
    <EmbedDashboard {server} {token} {path} />
  {:else if kind === "ai"}
    <EmbedAsk
      {server}
      {token}
      cubeId={path}
      {space}
      onResult={(d) => emit("saiku:ai-query", d)}
    />
  {:else if loading}
    <div class="state">Loading…</div>
  {:else if error}
    <div class="state error" role="alert">{error}</div>
  {:else if matrixRows !== null}
    <EmbedMatrix
      rows={matrixRows}
      rowCaptions={matrixRowCaptions}
      columnCaptions={matrixColumnCaptions}
    />
  {:else if rows !== null}
    {#if render === "chart"}
      <EmbedChart {rows} {mode} />
    {:else if render === "kpi"}
      <EmbedKpi {rows} />
    {:else}
      <EmbedTable {rows} onSelect={(row) => emit("saiku:select", { row })} />
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

  /* theme="dark" forces the dark palette; theme="auto" adopts it only when
   * the viewer's OS is in dark mode. Unset / theme="light" keep the original
   * light defaults so existing embeds don't shift. Host per-var overrides still
   * work in the light theme; in dark, the palette below wins. */
  :host([theme="dark"]) {
    --saiku-embed-fg: #e6e8f0;
    --saiku-embed-bg: #0f1420;
    --saiku-embed-muted: #9aa2b4;
    --saiku-embed-border: #2a3140;
    --saiku-embed-header-bg: #171d2a;
    --saiku-embed-row-hover: #1b2230;
    --saiku-embed-error: #f87171;
    --saiku-embed-negative: #f87171;
    --saiku-embed-positive: #34d399;
    --saiku-embed-accent: #6d7dff;
  }
  @media (prefers-color-scheme: dark) {
    :host([theme="auto"]) {
      --saiku-embed-fg: #e6e8f0;
      --saiku-embed-bg: #0f1420;
      --saiku-embed-muted: #9aa2b4;
      --saiku-embed-border: #2a3140;
      --saiku-embed-header-bg: #171d2a;
      --saiku-embed-row-hover: #1b2230;
      --saiku-embed-error: #f87171;
      --saiku-embed-negative: #f87171;
      --saiku-embed-positive: #34d399;
      --saiku-embed-accent: #6d7dff;
    }
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
