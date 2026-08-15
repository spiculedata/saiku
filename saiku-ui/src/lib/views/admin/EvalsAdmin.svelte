<script lang="ts">
  /*
   * Agent-eval accuracy monitor (saiku#1424, Phase 3). Reads the admin-gated
   * /rest/saiku/admin/ai-evals surface and plots pass-rate over time per suite.
   * Ground truth is drift-proof (reference-query) — see docs/EVAL-SPEC.md.
   *
   * Mirrors StatsAdmin's shape (.pane / .kpis / .kpi + design tokens) so it
   * sits natively inside the admin panel. Signal colours come from the
   * --success/--warning/--danger tokens via scoped classes (no raw tone
   * classes — the ESLint token-only rule applies here).
   */
  import { onMount } from "svelte";
  import { Button } from "$lib/components/ui";
  import { adminEvals, type EvalRun, type EvalSuiteCard, type EvalTrendPoint } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { RefreshCw } from "@lucide/svelte";

  let suites = $state<EvalSuiteCard[]>([]);
  let selected = $state<string | null>(null);
  let trend = $state<EvalTrendPoint[]>([]);
  let runs = $state<EvalRun[]>([]);
  let loading = $state(false);
  let loaded = $state(false);

  const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
  const band = (v: number) => (v >= 0.9 ? "good" : v >= 0.7 ? "warn" : "bad");
  const fmtTs = (ms: number) =>
    ms ? new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) : "—";

  async function loadSuites() {
    loading = true;
    try {
      suites = await adminEvals.suites();
      if (suites.length && !selected) await select(suites[0].name);
      else if (selected) await select(selected);
    } catch (e) {
      toasts.danger("Eval monitor", e instanceof Error ? e.message : String(e));
    } finally {
      loading = false;
      loaded = true;
    }
  }

  async function select(name: string) {
    selected = name;
    try {
      [trend, runs] = await Promise.all([adminEvals.trend(name, 60), adminEvals.runs(name, 30)]);
    } catch (e) {
      toasts.danger("Eval monitor", e instanceof Error ? e.message : String(e));
    }
  }

  onMount(() => void loadSuites());

  const latest = $derived(runs[0] ?? null);
  const latestRate = $derived(latest && latest.total ? latest.passed / latest.total : 0);
  const firstRate = $derived(trend.length ? trend[0].passRate : latestRate);
  const deltaPts = $derived((latestRate - firstRate) * 100);
  const avgRate = $derived(trend.length ? trend.reduce((a, t) => a + t.passRate, 0) / trend.length : latestRate);

  // Hand-rolled SVG trend geometry.
  const W = 860;
  const H = 240;
  const PL = 44;
  const PR = 16;
  const PT = 16;
  const PB = 28;
  const IW = W - PL - PR;
  const IH = H - PT - PB;
  const xAt = (i: number, n: number) => (n <= 1 ? PL + IW / 2 : PL + (i / (n - 1)) * IW);
  const yAt = (v: number) => PT + IH - v * IH;
  const gridLines = [0, 0.25, 0.5, 0.75, 1];
  const linePts = $derived(trend.map((t, i) => `${xAt(i, trend.length)},${yAt(t.passRate)}`).join(" "));
  const areaPts = $derived(
    trend.length ? `${PL},${PT + IH} ${linePts} ${xAt(trend.length - 1, trend.length)},${PT + IH}` : "",
  );
  function showLabel(i: number, n: number): boolean {
    return n <= 8 || i % Math.ceil(n / 8) === 0;
  }
</script>

<div class="pane">
  <header class="flex justify-between items-center flex-wrap gap-2">
    <div>
      <h2>Agent evals</h2>
      <p class="text-fg-muted text-sm" style="margin:2px 0 0">
        Pass-rate over time · drift-proof reference-query ground truth
      </p>
    </div>
    <Button onclick={loadSuites} disabled={loading}>
      <RefreshCw size={14} /><span>{loading ? "Loading…" : "Refresh"}</span>
    </Button>
  </header>

  {#if loaded && suites.length === 0}
    <div class="empty">
      <h3 style="text-transform:none;color:hsl(var(--fg));font-size:var(--fs-md)">No eval runs recorded yet</h3>
      <p class="text-fg-muted text-sm">
        This monitor plots agent accuracy over time from suites in <code>saiku-home/evals/</code>.
        Nothing has run against this deployment yet.
      </p>
      <p class="text-fg-muted text-sm">
        Author a suite (see <code>docs/EVAL-SPEC.md</code>) — prefer a <code>referenceQuery</code> so ground
        truth tracks the live data — then run the sweep. Each run appends a point here.
      </p>
    </div>
  {:else if suites.length}
    <div class="suite-bar" role="tablist">
      {#each suites as s (s.name)}
        {@const r = s.latest && s.latest.total ? s.latest.passed / s.latest.total : null}
        <button
          type="button"
          role="tab"
          class="suite-chip"
          class:active={s.name === selected}
          onclick={() => select(s.name)}
        >
          <span class="dot {r === null ? '' : band(r)}"></span>
          <span class="nm">{s.name}</span>
          <span class="pc">{r === null ? "—" : pct(r)}</span>
        </button>
      {/each}
    </div>

    <section class="kpis">
      <div class="kpi">
        <span class="kpi__label">Latest pass rate</span>
        <span class="text-2xl font-bold rate {band(latestRate)}">{pct(latestRate)}</span>
        <span class="text-xs text-fg-muted">
          {latest ? `${latest.passed}/${latest.total} cases · ${fmtTs(latest.startedAt)}` : "—"}
        </span>
      </div>
      <div class="kpi">
        <span class="kpi__label">Trend</span>
        <span class="text-2xl font-bold rate {deltaPts >= 0 ? 'good' : 'bad'}">
          {deltaPts >= 0 ? "▲ +" : "▼ "}{Math.abs(deltaPts).toFixed(1)}pts
        </span>
        <span class="text-xs text-fg-muted">vs first of {trend.length} runs</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">Avg pass rate</span>
        <span class="text-2xl font-bold text-fg">{pct(avgRate)}</span>
        <span class="text-xs text-fg-muted">across window</span>
      </div>
      <div class="kpi">
        <span class="kpi__label">Runs recorded</span>
        <span class="text-2xl font-bold text-fg">{runs.length}</span>
        <span class="text-xs text-fg-muted">{latest ? `cube ${latest.cubeRef || "—"}` : ""}</span>
      </div>
    </section>

    <section class="flex flex-col gap-2">
      <h3>Pass-rate over time <span class="text-fg-muted text-sm">· each point is one run</span></h3>
      {#if trend.length}
        <div class="chart-wrap">
          <svg viewBox="0 0 {W} {H}" width="100%" preserveAspectRatio="xMidYMid meet">
            <defs>
              <linearGradient id="evalArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" stop-color="hsl(var(--primary))" stop-opacity="0.18" />
                <stop offset="1" stop-color="hsl(var(--primary))" stop-opacity="0" />
              </linearGradient>
            </defs>
            {#each gridLines as f (f)}
              <line class="grid" x1={PL} y1={PT + IH * (1 - f)} x2={PL + IW} y2={PT + IH * (1 - f)} />
              <text class="axt" x={PL - 8} y={PT + IH * (1 - f) + 3} text-anchor="end">{(f * 100) | 0}%</text>
            {/each}
            <polygon points={areaPts} fill="url(#evalArea)" />
            <polyline points={linePts} fill="none" stroke="hsl(var(--primary))" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
            {#each trend as t, i (i)}
              <circle class="dot-{band(t.passRate)}" cx={xAt(i, trend.length)} cy={yAt(t.passRate)} r={i === trend.length - 1 ? 4 : 2.6}>
                <title>{pct(t.passRate)} · {t.total} cases · {fmtTs(t.startedAt)}</title>
              </circle>
              {#if showLabel(i, trend.length)}
                <text class="axt" x={xAt(i, trend.length)} y={H - 9} text-anchor="middle">
                  {new Date(t.startedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
                </text>
              {/if}
            {/each}
          </svg>
        </div>
      {:else}
        <p class="text-fg-muted text-sm">No runs for this suite yet.</p>
      {/if}
    </section>

    <section class="flex flex-col gap-2">
      <h3>Recent runs <span class="text-fg-muted text-sm">({runs.length})</span></h3>
      {#if runs.length}
        <div class="overflow-auto border border-border rounded-sm">
          <table>
            <thead>
              <tr>
                <th>When</th><th>Pass rate</th><th>Pass</th><th>Fail</th><th>Degraded</th><th>Cases</th><th>Elapsed</th>
              </tr>
            </thead>
            <tbody>
              {#each runs as r (r.runId)}
                {@const rate = r.total ? r.passed / r.total : 0}
                <tr>
                  <td>{fmtTs(r.startedAt)}</td>
                  <td class="rate {band(rate)}">{pct(rate)}</td>
                  <td>{r.passed}</td>
                  <td class:rate={r.failed > 0} class:bad={r.failed > 0}>{r.failed || "—"}</td>
                  <td class="text-fg-muted">{r.degraded || 0}</td>
                  <td class="text-fg-muted">{r.total}</td>
                  <td class="text-fg-muted">{r.elapsedMs}ms</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {:else}
        <p class="text-fg-muted text-sm">No runs recorded.</p>
      {/if}
    </section>
  {/if}
</div>

<style>
  .pane { display: flex; flex-direction: column; gap: var(--space-4); }
  h2 { margin: 0; }
  h3 { margin: 0 0 var(--space-2); font-size: var(--fs-sm); text-transform: uppercase; letter-spacing: 0.04em; color: hsl(var(--fg-muted)); }

  .empty {
    padding: var(--space-6);
    background: hsl(var(--bg-muted));
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .empty code { font-family: var(--font-mono, monospace); font-size: var(--fs-xs); background: hsl(var(--bg-subtle)); padding: 1px 5px; border-radius: 4px; }

  .suite-bar { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .suite-chip {
    display: inline-flex; align-items: center; gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: hsl(var(--bg-muted)); border: 1px solid hsl(var(--border)); border-radius: var(--radius);
    color: hsl(var(--fg-muted)); font: inherit; cursor: pointer;
  }
  .suite-chip:hover { color: hsl(var(--fg)); }
  .suite-chip.active { color: hsl(var(--fg)); border-color: hsl(var(--primary)); box-shadow: inset 0 -2px 0 hsl(var(--primary)); }
  .suite-chip .nm { font-weight: var(--weight-medium); }
  .suite-chip .pc { font-variant-numeric: tabular-nums; font-size: var(--fs-xs); }
  .suite-chip .dot { width: 8px; height: 8px; border-radius: 50%; background: hsl(var(--fg-subtle)); }
  .suite-chip .dot.good { background: hsl(var(--success)); }
  .suite-chip .dot.warn { background: hsl(var(--warning)); }
  .suite-chip .dot.bad { background: hsl(var(--danger)); }

  .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-3); }
  .kpi { display: flex; flex-direction: column; gap: 2px; padding: var(--space-3); background: hsl(var(--bg-muted)); border: 1px solid hsl(var(--border)); border-radius: var(--radius); }
  .kpi__label { font-size: var(--fs-xs); color: hsl(var(--fg-muted)); text-transform: uppercase; letter-spacing: 0.05em; }

  .rate { font-variant-numeric: tabular-nums; }
  .rate.good { color: hsl(var(--success)); }
  .rate.warn { color: hsl(var(--warning)); }
  .rate.bad { color: hsl(var(--danger)); }

  .chart-wrap { background: hsl(var(--bg-muted)); border: 1px solid hsl(var(--border)); border-radius: var(--radius); padding: var(--space-3); }
  svg .grid { stroke: hsl(var(--border)); stroke-width: 1; }
  svg .axt { fill: hsl(var(--fg-muted)); font-size: 10px; font-variant-numeric: tabular-nums; }
  svg .dot-good { fill: hsl(var(--success)); }
  svg .dot-warn { fill: hsl(var(--warning)); }
  svg .dot-bad { fill: hsl(var(--danger)); }

  table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  th, td { padding: 6px 10px; text-align: left; white-space: nowrap; border-bottom: 1px solid hsl(var(--border)); font-variant-numeric: tabular-nums; }
  th { background: hsl(var(--bg-muted)); font-weight: var(--weight-semibold); }
  tr:last-child td { border-bottom: 0; }
</style>
