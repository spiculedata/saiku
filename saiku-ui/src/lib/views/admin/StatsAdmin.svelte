<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { Button } from "$lib/components/ui";
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

  /**
   * Mondrian StatementInfo doesn't carry wall-clock start / end; it
   * carries `sqlStatementExecuteNanos` (cumulative SQL execute time
   * for the statement). Render that as ms — it's the closest
   * available proxy for "how long did this query spend in the DB".
   * Returns "—" when the statement hasn't run any SQL yet (a cache
   * hit, or still building the plan).
   */
  function sqlDurationMs(nanos: number | undefined): string {
    if (!nanos || nanos <= 0) return "—";
    const ms = nanos / 1_000_000;
    return ms < 1 ? "<1ms" : `${Math.round(ms).toLocaleString()}ms`;
  }

  /** Statement lifecycle inferred from the executing flag + start/end
   *  counters. Mondrian doesn't model an enum, but the boolean +
   *  ratio is enough for an at-a-glance "is it running". */
  function statementState(
    executing: boolean | undefined,
    starts: number | undefined,
    ends: number | undefined,
  ): string {
    if (executing) return "Running";
    if ((starts ?? 0) > 0 && (ends ?? 0) >= (starts ?? 0)) return "Done";
    if ((starts ?? 0) > 0) return "Pending";
    return "Idle";
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
  <header class="flex justify-between items-center">
    <h2>{i18n.t("admin.tabs.stats")}</h2>
    <div class="flex gap-2 items-center">
      {#if lastLoaded}
        <span class="text-fg-muted text-sm">{i18n.t("admin.stats.updatedAt")} {fmtTime(lastLoaded)}</span>
      {/if}
      <Button variant="outline" onclick={() => (auto = !auto)} aria-pressed={auto}>
        {#if auto}
          <Pause size={14} /><span>{i18n.t("admin.stats.pause")}</span>
        {:else}
          <Play size={14} /><span>{i18n.t("admin.stats.resume")}</span>
        {/if}
      </Button>
      <Button onclick={load} disabled={loading}>
        <RefreshCw size={14} /><span>{loading ? i18n.t("admin.stats.loading") : i18n.t("admin.stats.refresh")}</span>
      </Button>
    </div>
  </header>

  {#if !stats}
    <p class="text-fg-muted text-sm">{i18n.t("admin.stats.idle")}</p>
  {:else}
    <section class="kpis">
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.cacheHitRate")}</span>
        <span class="text-2xl font-bold text-fg">{hitRate(stats)}</span>
        <span class="text-xs text-fg-muted">{fmtNum(stats.server.cellCacheHitCount)} / {fmtNum(stats.server.cellCacheRequestCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.cellCount")}</span>
        <span class="text-2xl font-bold text-fg">{fmtNum(stats.server.cellCount)}</span>
        <span class="text-xs text-fg-muted">{i18n.t("admin.stats.kpi.avgDim")}: {fmtFloat(stats.avgCellDimensionality)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.segments")}</span>
        <span class="text-2xl font-bold text-fg">{fmtNum(stats.server.segmentCount)}</span>
        <span class="text-xs text-fg-muted">{i18n.t("admin.stats.kpi.created")}: {fmtNum(stats.server.segmentCreateCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.sql")}</span>
        <span class="text-2xl font-bold text-fg">{fmtNum(stats.server.sqlStatementExecuteCount)}</span>
        <span class="text-xs text-fg-muted">{i18n.t("admin.stats.kpi.cellRequests")}: {fmtNum(stats.server.sqlStatementCellRequestCount)}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.openConnections")}</span>
        <span class="text-2xl font-bold text-fg">{stats.openConnectionCount ?? 0}</span>
        <span class="text-xs text-fg-muted">{stats.connections?.length ?? 0} {i18n.t("admin.stats.kpi.lifetime")}</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">{i18n.t("admin.stats.kpi.openMdx")}</span>
        <span class="text-2xl font-bold text-fg">{stats.openMdxStatementCount ?? 0}</span>
        <span class="text-xs text-fg-muted">{i18n.t("admin.stats.kpi.executing")}: {stats.executingMdxStatementCount ?? 0}</span>
      </div>
    </section>

    <section class="flex flex-col gap-2">
      <h3>{i18n.t("admin.stats.version")}</h3>
      <p class="text-fg-muted text-sm">
        {stats.version?.productName ?? "Mondrian"} {stats.version?.versionString ?? ""}
        {#if stats.server.startTimeMillis}
          · {i18n.t("admin.stats.startedAt")} {fmtTime(stats.server.startTimeMillis)}
        {/if}
      </p>
    </section>

    <section class="flex flex-col gap-2">
      <h3>{i18n.t("admin.stats.statements")} <span class="text-fg-muted text-sm">({stats.statements?.length ?? 0})</span></h3>
      {#if !stats.statements?.length}
        <p class="text-fg-muted text-sm">{i18n.t("admin.stats.empty")}</p>
      {:else}
        <!-- Mondrian StatementInfo carries counters, not a snapshot of
             the MDX text or wall-clock timestamps — see the live JSON
             at /rest/saiku/statistics/mondrian. The earlier columns
             (`state` / `duration` from start/end / `mdx`) read fields
             that don't exist in the response, so every row rendered
             "—". Reshape the table around the fields that ARE there:
             a derived state from the `executing` flag + start/end
             counters, SQL execute time as duration, cache hit ratio,
             and row-fetch / phase counts. -->
        <div class="overflow-auto border border-border rounded-sm">
          <table>
            <thead>
              <tr>
                <th>id</th>
                <th>{i18n.t("admin.stats.col.state")}</th>
                <th>{i18n.t("admin.stats.col.duration")}</th>
                <th>{i18n.t("admin.stats.col.cacheHits")}</th>
                <th>{i18n.t("admin.stats.col.rowsFetched")}</th>
                <th>{i18n.t("admin.stats.col.phases")}</th>
              </tr>
            </thead>
            <tbody>
              {#each stats.statements as s, i (s.statementId ?? i)}
                <tr>
                  <td>{s.statementId ?? "—"}</td>
                  <td>{statementState(s.executing, s.executeStartCount, s.executeEndCount)}</td>
                  <td>{sqlDurationMs(s.sqlStatementExecuteNanos)}</td>
                  <td>{fmtNum(s.cellCacheHitCount)} / {fmtNum(s.cellCacheRequestCount)}</td>
                  <td>{fmtNum(s.sqlStatementRowFetchCount)}</td>
                  <td>{fmtNum(s.phaseCount)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </section>

    <section class="flex flex-col gap-2">
      <h3>{i18n.t("admin.stats.connections")} <span class="text-fg-muted text-sm">({stats.connections?.length ?? 0})</span></h3>
      {#if !stats.connections?.length}
        <p class="text-fg-muted text-sm">{i18n.t("admin.stats.empty")}</p>
      {:else}
        <!-- ConnectionInfo also carries only counters (no id / catalog
             / wall-clock). Display the activity counters instead so
             the panel actually conveys something. -->
        <div class="overflow-auto border border-border rounded-sm">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>{i18n.t("admin.stats.col.statements")}</th>
                <th>{i18n.t("admin.stats.col.executes")}</th>
                <th>{i18n.t("admin.stats.col.cacheHits")}</th>
              </tr>
            </thead>
            <tbody>
              {#each stats.connections as c, i (i)}
                <tr>
                  <td>{i + 1}</td>
                  <td>{fmtNum(c.statementEndCount)} / {fmtNum(c.statementStartCount)}</td>
                  <td>{fmtNum(c.executeEndCount)} / {fmtNum(c.executeStartCount)}</td>
                  <td>{fmtNum(c.cellCacheHitCount)} / {fmtNum(c.cellCacheRequestCount)}</td>
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
  h2 { margin: 0; }
  h3 { margin: 0 0 var(--space-2); font-size: var(--fs-sm); text-transform: uppercase; letter-spacing: 0.04em; color: var(--fg-muted); }
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
  table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  th, td { padding: 6px 10px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--border); }
  th { background: var(--bg-muted); font-weight: var(--weight-semibold); }
  tr:last-child td { border-bottom: 0; }
  /* .mdx selector dropped — Mondrian StatementInfo doesn't surface
     the source MDX, so the column was removed in the 2026-06 rework. */
</style>
