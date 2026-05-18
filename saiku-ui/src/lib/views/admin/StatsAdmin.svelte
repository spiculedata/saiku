<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { adminStats, type MondrianStats } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { RefreshCw, Pause, Play } from "lucide-svelte";

  let stats = $state<MondrianStats | null>(null);
  let loading = $state(false);
  let lastLoaded = $state<number | null>(null);
  let auto = $state(true);
  const POLL_MS = 5000;
  let timer: ReturnType<typeof setInterval> | null = null;

  async function load() {
    loading = true;
    try {
      stats = await adminStats.mondrian();
      lastLoaded = Date.now();
    } catch (e) {
      toasts.danger(i18n.t("admin.stats.fetchFailed"), e instanceof Error ? e.message : String(e));
    } finally {
      loading = false;
    }
  }

  function startPolling() {
    if (timer) return;
    timer = setInterval(() => { if (!loading) void load(); }, POLL_MS);
  }
  function stopPolling() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  $effect(() => {
    if (auto) startPolling();
    else stopPolling();
  });

  onMount(() => { void load(); });
  onDestroy(() => stopPolling());

  function hitRate(s: MondrianStats | null): string {
    if (!s) return "—";
    const reqs = s.server.cellCacheRequestCount ?? 0;
    const hits = s.server.cellCacheHitCount ?? 0;
    if (reqs === 0) return "—";
    return `${((hits / reqs) * 100).toFixed(1)}%`;
  }

  function fmtNum(n: number | undefined): string {
    if (n === undefined || n === null) return "—";
    return n.toLocaleString();
  }

  function fmtTime(ms: number | undefined): string {
    if (!ms) return "—";
    return new Date(ms).toLocaleString();
  }

  function durationMs(start: number | undefined, end: number | undefined): string {
    if (!start) return "—";
    const e = end && end > 0 ? end : Date.now();
    return `${(e - start)}ms`;
  }

  function truncateMdx(mdx: string | undefined, max = 80): string {
    if (!mdx) return "—";
    return mdx.length <= max ? mdx : mdx.slice(0, max - 1) + "…";
  }

  /** Jackson sends NaN as the literal string "NaN" (Java float NaN doesn't
   *  round-trip through JSON cleanly). Coerce numeric-or-"NaN" fields here
   *  so .toFixed doesn't blow up the entire render. */
  function asFloat(v: unknown): number | null {
    if (typeof v === "number" && Number.isFinite(v)) return v;
    if (typeof v === "string") {
      const n = Number(v);
      return Number.isFinite(n) ? n : null;
    }
    return null;
  }
  function fmtFloat(v: unknown, digits = 2): string {
    const n = asFloat(v);
    return n === null ? "—" : n.toFixed(digits);
  }
</script>

<div class="pane">
  <header class="pane__header">
    <h2>{i18n.t("admin.tabs.stats")}</h2>
    <div class="controls">
      {#if lastLoaded}
        <span class="muted">{i18n.t("admin.stats.updatedAt")} {fmtTime(lastLoaded)}</span>
      {/if}
      <button type="button" class="btn" onclick={() => (auto = !auto)} aria-pressed={auto}>
        {#if auto}
          <Pause size={14} /><span>{i18n.t("admin.stats.pause")}</span>
        {:else}
          <Play size={14} /><span>{i18n.t("admin.stats.resume")}</span>
        {/if}
      </button>
      <button type="button" class="btn btn--primary" onclick={load} disabled={loading}>
        <RefreshCw size={14} /><span>{loading ? i18n.t("admin.stats.loading") : i18n.t("admin.stats.refresh")}</span>
      </button>
    </div>
  </header>

  {#if !stats}
    <p class="muted">{i18n.t("admin.stats.idle")}</p>
  {:else}
    <section class="kpis">
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.cacheHitRate")}</span>
        <span class="kpi__value">{hitRate(stats)}</span>
        <span class="kpi__sub">{fmtNum(stats.server.cellCacheHitCount)} / {fmtNum(stats.server.cellCacheRequestCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.cellCount")}</span>
        <span class="kpi__value">{fmtNum(stats.server.cellCount)}</span>
        <span class="kpi__sub">{i18n.t("admin.stats.kpi.avgDim")}: {fmtFloat(stats.avgCellDimensionality)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.segments")}</span>
        <span class="kpi__value">{fmtNum(stats.server.segmentCount)}</span>
        <span class="kpi__sub">{i18n.t("admin.stats.kpi.created")}: {fmtNum(stats.server.segmentCreateCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.sql")}</span>
        <span class="kpi__value">{fmtNum(stats.server.sqlStatementExecuteCount)}</span>
        <span class="kpi__sub">{i18n.t("admin.stats.kpi.cellRequests")}: {fmtNum(stats.server.sqlStatementCellRequestCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.openConnections")}</span>
        <span class="kpi__value">{stats.openConnectionCount ?? 0}</span>
        <span class="kpi__sub">{stats.connections?.length ?? 0} {i18n.t("admin.stats.kpi.lifetime")}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.openMdx")}</span>
        <span class="kpi__value">{stats.openMdxStatementCount ?? 0}</span>
        <span class="kpi__sub">{i18n.t("admin.stats.kpi.executing")}: {stats.executingMdxStatementCount ?? 0}</span>
      </div>
    </section>

    <section class="block">
      <h3>{i18n.t("admin.stats.version")}</h3>
      <p class="muted">
        {stats.version?.productName ?? "Mondrian"} {stats.version?.versionString ?? ""}
        {#if stats.server.startTimeMillis}
          · {i18n.t("admin.stats.startedAt")} {fmtTime(stats.server.startTimeMillis)}
        {/if}
      </p>
    </section>

    <section class="block">
      <h3>{i18n.t("admin.stats.statements")} <span class="muted">({stats.statements?.length ?? 0})</span></h3>
      {#if !stats.statements?.length}
        <p class="muted">{i18n.t("admin.stats.empty")}</p>
      {:else}
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>id</th>
                <th>{i18n.t("admin.stats.col.state")}</th>
                <th>{i18n.t("admin.stats.col.duration")}</th>
                <th>{i18n.t("admin.stats.col.cacheHits")}</th>
                <th>{i18n.t("admin.stats.col.mdx")}</th>
              </tr>
            </thead>
            <tbody>
              {#each stats.statements as s}
                <tr>
                  <td>{s.statementId ?? "—"}</td>
                  <td>{s.state ?? "—"}</td>
                  <td>{durationMs(s.startTimeMillis, s.endTimeMillis)}</td>
                  <td>{fmtNum(s.cellCacheHitCount)} / {fmtNum(s.cellCacheRequestCount)}</td>
                  <td class="mdx" title={s.mdx}>{truncateMdx(s.mdx)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </section>

    <section class="block">
      <h3>{i18n.t("admin.stats.connections")} <span class="muted">({stats.connections?.length ?? 0})</span></h3>
      {#if !stats.connections?.length}
        <p class="muted">{i18n.t("admin.stats.empty")}</p>
      {:else}
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>id</th>
                <th>{i18n.t("admin.stats.col.catalog")}</th>
                <th>{i18n.t("admin.stats.col.duration")}</th>
              </tr>
            </thead>
            <tbody>
              {#each stats.connections as c}
                <tr>
                  <td>{c.connectionId ?? "—"}</td>
                  <td>{c.catalogName ?? "—"}</td>
                  <td>{durationMs(c.startTimeMillis, c.endTimeMillis)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </section>
  {/if}
</div>

<style>
  .pane { display: flex; flex-direction: column; gap: var(--space-4); }
  .pane__header { display: flex; justify-content: space-between; align-items: center; }
  h2 { margin: 0; }
  h3 { margin: 0 0 var(--space-2); font-size: var(--fs-sm); text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-muted); }
  .controls { display: flex; gap: var(--space-2); align-items: center; }
  .muted { color: var(--fg-muted); font-size: var(--fs-sm); }
  .kpis {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: var(--space-3);
  }
  .kpi {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: var(--space-3);
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius);
  }
  .kpi__label { font-size: var(--fs-xs); color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.05em; }
  .kpi__value { font-size: 24px; font-weight: var(--weight-bold); color: var(--fg); }
  .kpi__sub { font-size: var(--fs-xs); color: var(--fg-muted); }
  .block { display: flex; flex-direction: column; gap: var(--space-2); }
  .table-wrap { overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); }
  table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  th, td { padding: 6px 10px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--border); }
  th { background: var(--bg-muted); font-weight: var(--weight-semibold); }
  tr:last-child td { border-bottom: 0; }
  .mdx { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--fg-muted); }
</style>
