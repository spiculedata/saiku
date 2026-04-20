<script lang="ts">
  // Standalone, no-backend demo of how an N-axis OLAP cellset could be rendered
  // using a role-based pivot model. Change axis count + roles, watch the
  // tabular + graphical views reshape from the same underlying tensor.

  interface AxisDef { name: string; members: string[]; }

  // 5 potential axes. Axis count slider picks the first N.
  const ALL_AXES: AxisDef[] = [
    { name: "Product",  members: ["Widgets", "Gadgets", "Gizmos", "Doohickeys", "Thingamajigs"] },
    { name: "Region",   members: ["North", "South", "East", "West"] },
    { name: "Quarter",  members: ["Q1 2026", "Q2 2026", "Q3 2026", "Q4 2026"] },
    { name: "Scenario", members: ["Actual", "Budget", "Forecast"] },
    { name: "Channel",  members: ["Retail", "Online", "Wholesale"] },
  ];

  type Role = "rows" | "columns" | "facet-cols" | "facet-rows" | "slicer";
  const ROLE_LABELS: Record<Role, string> = {
    rows: "Rows",
    columns: "Columns",
    "facet-cols": "Facet (panel columns)",
    "facet-rows": "Facet (panel rows)",
    slicer: "Slicer (dropdown)",
  };

  function defaultRoles(n: number): Role[] {
    const preset: Role[] = ["rows", "columns", "facet-cols", "facet-rows", "slicer", "slicer", "slicer"];
    return preset.slice(0, n);
  }

  let axisCount = $state(4);
  const axes = $derived(ALL_AXES.slice(0, axisCount));
  let roles = $state<Role[]>(defaultRoles(4));
  let slicerIdx = $state<number[]>([0, 0, 0, 0, 0]);
  let chartType = $state<"bar" | "line" | "heat">("bar");

  // Keep roles length in sync with axisCount.
  $effect(() => {
    if (roles.length !== axisCount) roles = defaultRoles(axisCount);
  });

  // Deterministic synthetic measure (stands in for the cellset a cube would
  // return). Realistic-ish shape: grows with each dimension index, with some
  // seasonal + scenario skew so the numbers vary meaningfully.
  function measure(indices: number[]): number {
    let v = 120;
    for (let a = 0; a < indices.length; a++) {
      v *= 1 + (indices[a] + 1) * 0.08;
      v += (a * 7 + indices[a] * 13 + 11) % 23;
    }
    // Quarter bump.
    if (axes[2] && indices[2] === 1) v *= 1.18;
    // Scenario skew: Forecast > Actual > Budget.
    if (axes[3]) {
      const s = indices[3];
      v *= s === 0 ? 1 : s === 1 ? 0.92 : 1.12;
    }
    return Math.round(v);
  }

  function indicesForRole(role: Role): number[] {
    return roles.map((r, i) => (r === role ? i : -1)).filter((i) => i >= 0);
  }
  const rowAxis = $derived(indicesForRole("rows")[0] ?? -1);
  const colAxis = $derived(indicesForRole("columns")[0] ?? -1);
  const facetRowAxis = $derived(indicesForRole("facet-rows")[0] ?? -1);
  const facetColAxis = $derived(indicesForRole("facet-cols")[0] ?? -1);
  const slicerAxes = $derived(indicesForRole("slicer"));

  const rowMembers = $derived(rowAxis >= 0 ? axes[rowAxis].members : [""]);
  const colMembers = $derived(colAxis >= 0 ? axes[colAxis].members : [""]);
  const panelRowMembers = $derived(facetRowAxis >= 0 ? axes[facetRowAxis].members : [""]);
  const panelColMembers = $derived(facetColAxis >= 0 ? axes[facetColAxis].members : [""]);

  function buildTuple(pr: number, pc: number, r: number, c: number): number[] {
    const t: number[] = new Array(axes.length).fill(0);
    if (rowAxis >= 0) t[rowAxis] = r;
    if (colAxis >= 0) t[colAxis] = c;
    if (facetRowAxis >= 0) t[facetRowAxis] = pr;
    if (facetColAxis >= 0) t[facetColAxis] = pc;
    for (const s of slicerAxes) t[s] = slicerIdx[s] ?? 0;
    return t;
  }

  function panelData(pr: number, pc: number): number[][] {
    const grid: number[][] = [];
    for (let r = 0; r < rowMembers.length; r++) {
      const row: number[] = [];
      for (let c = 0; c < colMembers.length; c++) {
        row.push(measure(buildTuple(pr, pc, r, c)));
      }
      grid.push(row);
    }
    return grid;
  }

  function fmt(n: number): string {
    return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  // Validate role assignments. User can only have one Rows and one Columns axis;
  // facets are optional and there is at most one of each facet axis; everything
  // else must be a slicer.
  function swapRole(i: number, role: Role) {
    const prev = roles[i];
    if (prev === role) return;
    const next = [...roles];
    // If role is a unique role and someone else already holds it, push them to slicer
    if (role === "rows" || role === "columns" || role === "facet-rows" || role === "facet-cols") {
      for (let k = 0; k < next.length; k++) {
        if (k !== i && next[k] === role) next[k] = "slicer";
      }
    }
    next[i] = role;
    roles = next;
  }

  // Chart drawing helpers — plain SVG, no external lib.
  const PANEL_W = 260;
  const PANEL_H = 170;
  const PAD_L = 30;
  const PAD_R = 8;
  const PAD_T = 10;
  const PAD_B = 22;

  function barBars(data: number[][]): { x: number; y: number; w: number; h: number; fill: string }[] {
    const out: { x: number; y: number; w: number; h: number; fill: string }[] = [];
    let max = 0;
    for (const row of data) for (const v of row) if (v > max) max = v;
    if (max === 0) max = 1;
    const rows = data.length;
    const cols = data[0]?.length ?? 0;
    const innerW = PANEL_W - PAD_L - PAD_R;
    const innerH = PANEL_H - PAD_T - PAD_B;
    const groupW = innerW / Math.max(rows, 1);
    const barW = (groupW - 4) / Math.max(cols, 1);
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const v = data[r][c];
        const h = (v / max) * innerH;
        const x = PAD_L + r * groupW + c * barW + 2;
        const y = PAD_T + innerH - h;
        const hue = (c * 360) / Math.max(cols, 1);
        out.push({ x, y, w: Math.max(barW - 1, 1), h, fill: `hsl(${hue} 60% 55%)` });
      }
    }
    return out;
  }

  function linePaths(data: number[][]): { d: string; stroke: string }[] {
    const paths: { d: string; stroke: string }[] = [];
    let max = 0;
    for (const row of data) for (const v of row) if (v > max) max = v;
    if (max === 0) max = 1;
    const rows = data.length;
    const cols = data[0]?.length ?? 0;
    const innerW = PANEL_W - PAD_L - PAD_R;
    const innerH = PANEL_H - PAD_T - PAD_B;
    const xStep = innerW / Math.max(rows - 1, 1);
    for (let c = 0; c < cols; c++) {
      let d = "";
      for (let r = 0; r < rows; r++) {
        const v = data[r][c];
        const x = PAD_L + r * xStep;
        const y = PAD_T + innerH - (v / max) * innerH;
        d += (r === 0 ? "M" : "L") + x.toFixed(1) + "," + y.toFixed(1) + " ";
      }
      const hue = (c * 360) / Math.max(cols, 1);
      paths.push({ d: d.trim(), stroke: `hsl(${hue} 60% 55%)` });
    }
    return paths;
  }

  function heatCells(data: number[][]): { x: number; y: number; w: number; h: number; fill: string; label: string }[] {
    let max = 0;
    for (const row of data) for (const v of row) if (v > max) max = v;
    if (max === 0) max = 1;
    const rows = data.length;
    const cols = data[0]?.length ?? 0;
    const innerW = PANEL_W - PAD_L - PAD_R;
    const innerH = PANEL_H - PAD_T - PAD_B;
    const cw = innerW / Math.max(cols, 1);
    const ch = innerH / Math.max(rows, 1);
    const out = [];
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const v = data[r][c];
        const t = v / max;
        out.push({
          x: PAD_L + c * cw,
          y: PAD_T + r * ch,
          w: cw - 1,
          h: ch - 1,
          fill: `hsl(210 60% ${90 - t * 60}%)`,
          label: fmt(v),
        });
      }
    }
    return out;
  }

  // Human-readable caption for a given panel: which facet tuples is this slice?
  function panelCaption(pr: number, pc: number): string {
    const parts: string[] = [];
    if (facetRowAxis >= 0) parts.push(`${axes[facetRowAxis].name} = ${panelRowMembers[pr]}`);
    if (facetColAxis >= 0) parts.push(`${axes[facetColAxis].name} = ${panelColMembers[pc]}`);
    return parts.join(" · ");
  }
</script>

<div class="page">
  <header>
    <h1>N-axis OLAP rendering — demo</h1>
    <p class="sub">
      A synthetic cellset with 2–5 axes. Each axis has a <em>role</em>: rows, columns,
      facet (panel rows / panel cols), or slicer. Change roles on the fly — the same
      data tensor reshapes instantly. The underlying query (MDX) wouldn't re-execute;
      only the client-side view changes. This pattern scales to Mondrian's full 128
      axes — the extras just become more slicer dropdowns.
    </p>
  </header>

  <section class="controls">
    <label class="axis-count">
      <span>Query axis count</span>
      <input type="range" min="2" max="5" bind:value={axisCount} />
      <strong>{axisCount}</strong>
    </label>

    <div class="roles">
      {#each axes as ax, i}
        <div class="role">
          <div class="role__axis">
            <span class="tag">AXIS({i})</span>
            <span class="role__name">{ax.name}</span>
            <span class="role__card">{ax.members.length} members</span>
          </div>
          <select value={roles[i]} onchange={(e) => swapRole(i, (e.currentTarget as HTMLSelectElement).value as Role)}>
            {#each Object.entries(ROLE_LABELS) as [k, label]}
              <option value={k}>{label}</option>
            {/each}
          </select>
          {#if roles[i] === "slicer"}
            <select bind:value={slicerIdx[i]} class="slicer-val">
              {#each ax.members as m, j}
                <option value={j}>{m}</option>
              {/each}
            </select>
          {/if}
        </div>
      {/each}
    </div>

    <div class="chart-pick">
      <span>Chart:</span>
      <button class:active={chartType === "bar"}  onclick={() => (chartType = "bar")}>Bar</button>
      <button class:active={chartType === "line"} onclick={() => (chartType = "line")}>Line</button>
      <button class:active={chartType === "heat"} onclick={() => (chartType = "heat")}>Heatmap</button>
    </div>
  </section>

  <section class="panel-grid">
    <h2>Tabular — trellis of tables</h2>
    <div class="trellis" style="grid-template-columns: repeat({panelColMembers.length}, max-content);">
      {#each panelRowMembers as _, pr}
        {#each panelColMembers as _, pc}
          <div class="panel">
            {#if facetRowAxis >= 0 || facetColAxis >= 0}
              <div class="panel__caption">{panelCaption(pr, pc)}</div>
            {/if}
            <table>
              <thead>
                <tr>
                  <th class="corner">
                    {#if rowAxis >= 0 && colAxis >= 0}
                      <small>{axes[rowAxis].name} ▾ {axes[colAxis].name} ▸</small>
                    {/if}
                  </th>
                  {#each colMembers as m}
                    <th>{m || "value"}</th>
                  {/each}
                </tr>
              </thead>
              <tbody>
                {#each rowMembers as rm, r}
                  <tr>
                    <th class="rh">{rm || "value"}</th>
                    {#each colMembers as _, c}
                      <td>{fmt(panelData(pr, pc)[r][c])}</td>
                    {/each}
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {/each}
      {/each}
    </div>
  </section>

  <section class="panel-grid">
    <h2>Graphical — same data, trellis of charts</h2>
    <div class="trellis" style="grid-template-columns: repeat({panelColMembers.length}, max-content);">
      {#each panelRowMembers as _, pr}
        {#each panelColMembers as _, pc}
          <div class="panel">
            {#if facetRowAxis >= 0 || facetColAxis >= 0}
              <div class="panel__caption">{panelCaption(pr, pc)}</div>
            {/if}
            <svg viewBox="0 0 {PANEL_W} {PANEL_H}" width={PANEL_W} height={PANEL_H}>
              <rect x={PAD_L} y={PAD_T} width={PANEL_W - PAD_L - PAD_R} height={PANEL_H - PAD_T - PAD_B} fill="none" stroke="var(--border)" />
              {#if chartType === "bar"}
                {#each barBars(panelData(pr, pc)) as b}
                  <rect x={b.x} y={b.y} width={b.w} height={b.h} fill={b.fill} />
                {/each}
              {:else if chartType === "line"}
                {#each linePaths(panelData(pr, pc)) as p}
                  <path d={p.d} stroke={p.stroke} stroke-width="1.5" fill="none" />
                {/each}
              {:else}
                {#each heatCells(panelData(pr, pc)) as h}
                  <g>
                    <rect x={h.x} y={h.y} width={h.w} height={h.h} fill={h.fill} />
                    <text x={h.x + h.w / 2} y={h.y + h.h / 2} text-anchor="middle" dominant-baseline="middle" font-size="9" fill="var(--fg)">{h.label}</text>
                  </g>
                {/each}
              {/if}
            </svg>
            <div class="legend">
              {#if colAxis >= 0 && chartType !== "heat"}
                {#each colMembers as m, c}
                  <span class="legend__item">
                    <span class="legend__dot" style="background: hsl({(c * 360) / Math.max(colMembers.length, 1)} 60% 55%)"></span>
                    {m}
                  </span>
                {/each}
              {/if}
            </div>
          </div>
        {/each}
      {/each}
    </div>
  </section>

  <section class="meta">
    <h2>What you just configured</h2>
    <pre>{JSON.stringify({
      axes: axes.map((a, i) => ({ axis: i, name: a.name, role: roles[i], locked: roles[i] === "slicer" ? a.members[slicerIdx[i]] : undefined })),
      panels: panelRowMembers.length * panelColMembers.length,
      cellsPerPanel: rowMembers.length * colMembers.length,
    }, null, 2)}</pre>
  </section>
</div>

<style>
  .page { max-width: 1400px; margin: 0 auto; padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-5); }
  h1 { margin: 0 0 var(--space-2) 0; }
  h2 { margin: 0 0 var(--space-3) 0; font-size: var(--fs-md); }
  .sub { color: var(--fg-muted); max-width: 900px; line-height: 1.5; }

  .controls {
    display: flex; flex-direction: column; gap: var(--space-3);
    padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-muted);
  }
  .axis-count { display: flex; align-items: center; gap: var(--space-3); }
  .axis-count strong { min-width: 1.5em; text-align: right; font-variant-numeric: tabular-nums; }
  .roles { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: var(--space-2); }
  .role { display: flex; align-items: center; gap: var(--space-2); padding: 6px 10px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg); }
  .role__axis { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
  .tag { font-size: 10px; padding: 2px 6px; background: var(--bg-subtle); border-radius: 3px; color: var(--fg-muted); }
  .role__name { font-weight: 600; }
  .role__card { color: var(--fg-muted); font-size: var(--fs-xs); margin-left: auto; }
  .role select { padding: 4px 8px; background: var(--bg); color: var(--fg); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); font: inherit; }
  .slicer-val { min-width: 120px; }

  .chart-pick { display: flex; align-items: center; gap: var(--space-2); }
  .chart-pick button { padding: 4px 12px; border: 1px solid var(--border-strong); background: transparent; color: var(--fg-muted); border-radius: var(--radius-sm); cursor: pointer; font: inherit; }
  .chart-pick button.active { background: var(--accent); color: var(--accent-fg); border-color: var(--accent); }

  .panel-grid {
    display: flex; flex-direction: column; gap: var(--space-3);
  }
  .trellis {
    display: grid;
    gap: var(--space-3);
    overflow: auto;
    padding-bottom: var(--space-2);
  }
  .panel {
    border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-muted); padding: var(--space-3); display: flex; flex-direction: column; gap: 6px;
    min-width: max-content;
  }
  .panel__caption {
    font-size: var(--fs-xs); color: var(--fg-muted); font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase;
  }
  .panel table { border-collapse: collapse; font-size: var(--fs-sm); }
  .panel th, .panel td { padding: 4px 10px; border-bottom: 1px solid var(--border); text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .panel thead th { background: var(--bg); color: var(--fg); text-align: left; }
  .panel tbody th.rh { background: var(--bg); text-align: left; font-weight: 500; }
  .panel th.corner small { color: var(--fg-muted); font-weight: 400; }

  .legend { display: flex; flex-wrap: wrap; gap: var(--space-2); font-size: var(--fs-xs); color: var(--fg-muted); }
  .legend__dot { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }

  .meta pre { background: var(--bg-muted); color: var(--fg); padding: var(--space-3); border-radius: var(--radius-sm); border: 1px solid var(--border); overflow: auto; font-size: var(--fs-xs); }
</style>
