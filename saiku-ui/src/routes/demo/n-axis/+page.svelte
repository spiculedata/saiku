<script lang="ts">
  // Demo: what "PAGES axis support" gets you, compared with today's 2-axis +
  // single-tuple FILTER model. Same synthetic dataset rendered two ways, side
  // by side, so the comparison is direct.

  interface Dim { name: string; members: string[]; }

  // Fixed 3D cube: Product × Region × {Quarter | Scenario | Channel}.
  const PRODUCT: Dim = { name: "Product", members: ["Widgets", "Gadgets", "Gizmos", "Doohickeys", "Thingamajigs"] };
  const REGION:  Dim = { name: "Region",  members: ["North", "South", "East", "West"] };
  const QUARTER: Dim = { name: "Quarter", members: ["Q1 2026", "Q2 2026", "Q3 2026", "Q4 2026"] };
  const SCENARIO:Dim = { name: "Scenario", members: ["Actual", "Budget", "Forecast"] };
  const CHANNEL: Dim = { name: "Channel", members: ["Retail", "Online", "Wholesale"] };

  // The third "thing" the user wants to compare across is the PAGES-axis
  // candidate. Quarter and Scenario are the two most common real-world cases.
  const PAGES_CANDIDATES: { key: string; dim: Dim; description: string }[] = [
    { key: "quarter",  dim: QUARTER,  description: "Compare this year's four quarters side-by-side." },
    { key: "scenario", dim: SCENARIO, description: "Compare Actual vs Budget vs Forecast on the same grid." },
    { key: "channel",  dim: CHANNEL,  description: "Compare sales channels (retail / online / wholesale)." },
  ];

  let pagesKey = $state("quarter");
  let mode = $state<"slicer" | "trellis">("trellis");
  let chartType = $state<"bar" | "line">("bar");

  const pagesDim = $derived(PAGES_CANDIDATES.find((p) => p.key === pagesKey)!.dim);
  const pagesDesc = $derived(PAGES_CANDIDATES.find((p) => p.key === pagesKey)!.description);

  // In slicer mode the user picks one tuple on the pages dim.
  let pagesTuple = $state(0);
  $effect(() => { if (pagesTuple >= pagesDim.members.length) pagesTuple = 0; });

  // The two non-PAGES dims become silent "all other" fixed slicers. In a real
  // query builder these would be whatever the WHERE clause already pins.
  // For the demo we pick a fixed member per non-pages dim.
  function nonPagesSlicers(): { dim: Dim; idx: number }[] {
    const out: { dim: Dim; idx: number }[] = [];
    for (const c of PAGES_CANDIDATES) {
      if (c.key !== pagesKey) out.push({ dim: c.dim, idx: 0 });
    }
    return out;
  }
  const extraSlicers = $derived(nonPagesSlicers());

  // Measure: deterministic, shaped like sales data with mild seasonality.
  function sales(product: number, region: number, quarter: number, scenario: number, channel: number): number {
    let v = 180 + product * 45 + region * 31;
    v *= 1 + 0.1 * (quarter + 1); // quarterly growth
    if (quarter === 1) v *= 1.15; // Q2 bump
    if (quarter === 3) v *= 1.08; // Q4 holiday
    v *= scenario === 0 ? 1 : scenario === 1 ? 0.9 : 1.1; // Budget low, Forecast high
    v *= channel === 0 ? 1 : channel === 1 ? 1.12 : 0.82; // Online > Retail > Wholesale
    // tiny deterministic noise so values aren't too smooth
    v += ((product * 7 + region * 13 + quarter * 19 + scenario * 23 + channel * 29) % 37) - 18;
    return Math.round(v);
  }

  // Pull a 2D grid (product × region) for a given pages tuple.
  function grid(pagesIdx: number): number[][] {
    const qIdx = pagesKey === "quarter"  ? pagesIdx : 0;
    const sIdx = pagesKey === "scenario" ? pagesIdx : 0;
    const cIdx = pagesKey === "channel"  ? pagesIdx : 0;
    const out: number[][] = [];
    for (let p = 0; p < PRODUCT.members.length; p++) {
      const row: number[] = [];
      for (let r = 0; r < REGION.members.length; r++) {
        row.push(sales(p, r, qIdx, sIdx, cIdx));
      }
      out.push(row);
    }
    return out;
  }

  function fmt(n: number): string {
    return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  // ---- MDX shown for each mode, so users can see the query shape. ----
  const mdxSlicer = $derived.by(() => {
    const pagesHier = pagesDim.name;
    const pagesMember = pagesDim.members[pagesTuple];
    return (
`SELECT
  [Region].[Region].Members              ON COLUMNS,
  [Product].[Product].Members            ON ROWS
FROM [Sales]
WHERE ([${pagesHier}].[${pagesMember}])`
    );
  });
  const mdxTrellis = $derived.by(() => {
    const pagesHier = pagesDim.name;
    return (
`SELECT
  [Region].[Region].Members              ON COLUMNS,
  [Product].[Product].Members            ON ROWS,
  [${pagesHier}].[${pagesHier}].Members  ON PAGES
FROM [Sales]`
    );
  });

  // ---- SVG bar/line chart helpers (trellis panels). ----
  const PANEL_W = 280;
  const PANEL_H = 170;
  const PAD_L = 34;
  const PAD_R = 8;
  const PAD_T = 10;
  const PAD_B = 22;

  // Shared across all trellis panels so bar heights are comparable.
  function globalMax(): number {
    let m = 0;
    for (let i = 0; i < pagesDim.members.length; i++) {
      for (const row of grid(i)) for (const v of row) if (v > m) m = v;
    }
    return m || 1;
  }

  function barBars(data: number[][], maxVal: number) {
    const out: { x: number; y: number; w: number; h: number; fill: string }[] = [];
    const rows = data.length;
    const cols = data[0]?.length ?? 0;
    const innerW = PANEL_W - PAD_L - PAD_R;
    const innerH = PANEL_H - PAD_T - PAD_B;
    const groupW = innerW / Math.max(rows, 1);
    const barW = (groupW - 4) / Math.max(cols, 1);
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const v = data[r][c];
        const h = (v / maxVal) * innerH;
        const x = PAD_L + r * groupW + c * barW + 2;
        const y = PAD_T + innerH - h;
        const hue = (c * 360) / Math.max(cols, 1);
        out.push({ x, y, w: Math.max(barW - 1, 1), h, fill: `hsl(${hue} 60% 55%)` });
      }
    }
    return out;
  }

  function linePaths(data: number[][], maxVal: number) {
    const paths: { d: string; stroke: string }[] = [];
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
        const y = PAD_T + innerH - (v / maxVal) * innerH;
        d += (r === 0 ? "M" : "L") + x.toFixed(1) + "," + y.toFixed(1) + " ";
      }
      const hue = (c * 360) / Math.max(cols, 1);
      paths.push({ d: d.trim(), stroke: `hsl(${hue} 60% 55%)` });
    }
    return paths;
  }

  // Totals across all panels — shown as a header strip in trellis mode so
  // users get a cross-page comparison at a glance.
  function panelTotal(pagesIdx: number): number {
    let t = 0;
    for (const row of grid(pagesIdx)) for (const v of row) t += v;
    return t;
  }
</script>

<div class="page">
  <header>
    <h1>PAGES axis support — why it would pay off</h1>
    <p class="sub">
      Saiku today has ROWS, COLUMNS, and a single-tuple FILTER. Adding a third
      visible axis — PAGES — lets you compare <em>across</em> a dimension
      instead of locking it to one value. This demo shows the same cube rendered
      both ways so you can feel the difference. Nothing is hitting a real
      cube; the numbers are synthetic.
    </p>
  </header>

  <section class="controls">
    <div class="picker">
      <label>
        <span class="lbl">What do you want to compare?</span>
        <select bind:value={pagesKey}>
          {#each PAGES_CANDIDATES as c}
            <option value={c.key}>{c.dim.name} — {c.description}</option>
          {/each}
        </select>
      </label>
    </div>
    <div class="picker">
      <span class="lbl">Chart style</span>
      <button class:active={chartType === "bar"}  onclick={() => (chartType = "bar")}>Bar</button>
      <button class:active={chartType === "line"} onclick={() => (chartType = "line")}>Line</button>
    </div>
    <div class="picker slicer-summary">
      <span class="lbl">Fixed slicers (locked by WHERE)</span>
      {#each extraSlicers as s}
        <span class="pill">{s.dim.name} = {s.dim.members[s.idx]}</span>
      {/each}
    </div>
  </section>

  <!-- STATUS QUO: 2 axes + a slicer you have to pick -->
  <section class="block block--before">
    <div class="block__header">
      <div>
        <h2>Status quo — {pagesDim.name} as a FILTER slicer</h2>
        <p>
          You pick one {pagesDim.name.toLowerCase()}, Saiku executes one query,
          you see one grid. Comparing across {pagesDim.name.toLowerCase()}s
          means swapping the slicer and remembering numbers in your head.
        </p>
      </div>
      <label class="slicer-pick">
        <span>{pagesDim.name}:</span>
        <select bind:value={pagesTuple}>
          {#each pagesDim.members as m, i}
            <option value={i}>{m}</option>
          {/each}
        </select>
      </label>
    </div>
    <div class="panel single">
      <div class="panel__caption">{pagesDim.name} = {pagesDim.members[pagesTuple]}</div>
      <div class="panel__body">
        <table>
          <thead>
            <tr>
              <th></th>
              {#each REGION.members as m}<th>{m}</th>{/each}
            </tr>
          </thead>
          <tbody>
            {#each PRODUCT.members as p, r}
              <tr>
                <th class="rh">{p}</th>
                {#each REGION.members as _, c}
                  <td>{fmt(grid(pagesTuple)[r][c])}</td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
        <svg viewBox="0 0 {PANEL_W} {PANEL_H}" width={PANEL_W} height={PANEL_H}>
          <rect x={PAD_L} y={PAD_T} width={PANEL_W - PAD_L - PAD_R} height={PANEL_H - PAD_T - PAD_B} fill="none" stroke="var(--border)" />
          {#if chartType === "bar"}
            {#each barBars(grid(pagesTuple), globalMax()) as b}
              <rect x={b.x} y={b.y} width={b.w} height={b.h} fill={b.fill} />
            {/each}
          {:else}
            {#each linePaths(grid(pagesTuple), globalMax()) as p}
              <path d={p.d} stroke={p.stroke} stroke-width="1.5" fill="none" />
            {/each}
          {/if}
        </svg>
      </div>
      <pre class="mdx">{mdxSlicer}</pre>
    </div>
  </section>

  <!-- PROPOSED: PAGES axis renders as trellis -->
  <section class="block block--after">
    <div class="block__header">
      <div>
        <h2>Proposed — {pagesDim.name} as PAGES, shown as a trellis</h2>
        <p>
          One query, every {pagesDim.name.toLowerCase()} visible at once. Cross-page
          comparison happens <em>visually</em>: same row, same column, just scan
          across panels. Bar heights share a scale so the comparison is honest.
        </p>
      </div>
      <div class="totals">
        {#each pagesDim.members as m, i}
          <span class="pill totals__pill">{m}: <strong>{fmt(panelTotal(i))}</strong></span>
        {/each}
      </div>
    </div>

    <h3 class="section-label">Tabular trellis</h3>
    <div class="trellis" style="grid-template-columns: repeat({pagesDim.members.length}, max-content);">
      {#each pagesDim.members as pageMember, p}
        <div class="panel">
          <div class="panel__caption">{pagesDim.name} = {pageMember}</div>
          <table>
            <thead>
              <tr>
                <th></th>
                {#each REGION.members as m}<th>{m}</th>{/each}
              </tr>
            </thead>
            <tbody>
              {#each PRODUCT.members as prod, r}
                <tr>
                  <th class="rh">{prod}</th>
                  {#each REGION.members as _, c}
                    <td>{fmt(grid(p)[r][c])}</td>
                  {/each}
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/each}
    </div>

    <h3 class="section-label">Graphical trellis <span class="section-sub">(shared Y-axis scale so panels compare honestly)</span></h3>
    <div class="trellis" style="grid-template-columns: repeat({pagesDim.members.length}, max-content);">
      {#each pagesDim.members as pageMember, p}
        <div class="panel">
          <div class="panel__caption">{pagesDim.name} = {pageMember}</div>
          <svg viewBox="0 0 {PANEL_W} {PANEL_H}" width={PANEL_W} height={PANEL_H}>
            <rect x={PAD_L} y={PAD_T} width={PANEL_W - PAD_L - PAD_R} height={PANEL_H - PAD_T - PAD_B} fill="none" stroke="var(--border)" />
            {#if chartType === "bar"}
              {#each barBars(grid(p), globalMax()) as b}
                <rect x={b.x} y={b.y} width={b.w} height={b.h} fill={b.fill} />
              {/each}
            {:else}
              {#each linePaths(grid(p), globalMax()) as pp}
                <path d={pp.d} stroke={pp.stroke} stroke-width="1.5" fill="none" />
              {/each}
            {/if}
          </svg>
        </div>
      {/each}
    </div>
    <div class="legend">
      {#each REGION.members as m, c}
        <span class="legend__item">
          <span class="legend__dot" style="background: hsl({(c * 360) / REGION.members.length} 60% 55%)"></span>
          {m}
        </span>
      {/each}
    </div>

    <pre class="mdx">{mdxTrellis}</pre>
  </section>

  <section class="block block--takeaway">
    <h2>When a PAGES axis earns its keep</h2>
    <ul>
      <li><strong>Period comparison.</strong> Last 4 quarters, last 12 months, YoY same period. With a slicer you flip; with PAGES you see.</li>
      <li><strong>Actual vs Budget vs Forecast.</strong> Executives want all three on one page, not a three-click spelunk.</li>
      <li><strong>A/B or channel splits.</strong> Retail vs Online vs Wholesale next to each other, same rows and columns.</li>
      <li><strong>What it <em>doesn't</em> replace.</strong> When the third dimension has 500 members (every SKU), trellis dies. That's where slicer stays — or a paged trellis that caps at ~12 panels.</li>
    </ul>

    <h2>What shipping this actually takes in Saiku</h2>
    <ul>
      <li>One new drop zone — <code>PAGES</code> — in the query canvas. Same drag/drop + chip UX you already have for ROWS/COLUMNS.</li>
      <li>MDX generator learns to emit <code>ON PAGES</code> when that zone is non-empty; everything else unchanged.</li>
      <li>Arrow schema metadata (Phase 5) extends from <code>rowHeaderColCount</code>/<code>columnHeaderRows</code> to an <code>axes[]</code> array: axis 0 columns, axis 1 rows, axis 2 pages.</li>
      <li>Result view gains a pages toggle above the grid: <em>Trellis</em> (panels) or <em>Slicer</em> (dropdown + single grid) — same data, client-side choice, no re-query.</li>
      <li>Guardrail: refuse trellis mode (or offer paged trellis) when the PAGES axis has &gt; ~12 tuples. Honest UX beats a frozen browser.</li>
    </ul>

    <p class="closing">
      Stopping at 3 axes is the sweet spot. Axis 4 (CHAPTERS) and beyond can
      wait until someone actually asks. The model remains open to N, but the
      product investment lives here.
    </p>
  </section>
</div>

<style>
  .page { max-width: 1400px; margin: 0 auto; padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-5); }
  h1 { margin: 0 0 var(--space-2) 0; }
  h2 { margin: 0 0 var(--space-2) 0; font-size: var(--fs-md); }
  h3.section-label { margin: var(--space-3) 0 var(--space-2) 0; font-size: var(--fs-sm); color: var(--fg-muted); font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; }
  .section-sub { color: var(--fg-muted); font-weight: 400; text-transform: none; letter-spacing: 0; margin-left: 8px; font-size: var(--fs-xs); }
  .sub { color: var(--fg-muted); max-width: 900px; line-height: 1.5; }

  .controls {
    display: grid; grid-template-columns: 1fr auto auto; gap: var(--space-4); align-items: center;
    padding: var(--space-3) var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-muted);
  }
  .picker { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
  .picker .lbl { color: var(--fg-muted); font-size: var(--fs-sm); font-weight: 600; }
  .picker select { padding: 6px 10px; background: var(--bg); color: var(--fg); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); font: inherit; }
  .picker button { padding: 4px 12px; border: 1px solid var(--border-strong); background: transparent; color: var(--fg-muted); border-radius: var(--radius-sm); cursor: pointer; font: inherit; }
  .picker button.active { background: var(--accent); color: var(--accent-fg); border-color: var(--accent); }
  .slicer-summary { grid-column: 1 / -1; }
  .pill { display: inline-block; padding: 2px 10px; background: var(--bg); border: 1px solid var(--border); border-radius: 999px; font-size: var(--fs-xs); color: var(--fg-muted); }

  .block {
    padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: var(--space-3);
  }
  .block--before { background: var(--bg-muted); }
  .block--after { border-color: var(--accent); box-shadow: inset 3px 0 0 var(--accent); }
  .block--takeaway { background: var(--bg-muted); }
  .block__header { display: flex; justify-content: space-between; gap: var(--space-4); align-items: flex-start; }
  .block__header p { color: var(--fg-muted); max-width: 720px; margin: 4px 0 0 0; line-height: 1.4; }
  .block__header .totals { display: flex; flex-wrap: wrap; gap: 6px; max-width: 380px; justify-content: flex-end; }
  .totals__pill strong { color: var(--fg); }
  .slicer-pick { display: inline-flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--bg); white-space: nowrap; }
  .slicer-pick select { border: none; background: transparent; color: var(--fg); font: inherit; cursor: pointer; }

  .trellis {
    display: grid; gap: var(--space-3); overflow-x: auto; padding-bottom: var(--space-2);
  }
  .panel {
    border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg); padding: var(--space-3);
    display: flex; flex-direction: column; gap: 6px; min-width: max-content;
  }
  .panel.single { width: max-content; }
  .panel__caption { font-size: var(--fs-xs); color: var(--fg-muted); font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; }
  .panel__body { display: flex; gap: var(--space-3); align-items: flex-start; }
  .panel table { border-collapse: collapse; font-size: var(--fs-sm); }
  .panel th, .panel td { padding: 4px 10px; border-bottom: 1px solid var(--border); text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .panel thead th { background: var(--bg-muted); color: var(--fg); text-align: left; font-weight: 600; }
  .panel tbody th.rh { background: var(--bg-muted); text-align: left; font-weight: 500; }

  .legend { display: flex; flex-wrap: wrap; gap: var(--space-3); font-size: var(--fs-xs); color: var(--fg-muted); padding: 0 var(--space-2); }
  .legend__dot { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }

  .mdx {
    background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 12px; font-size: var(--fs-xs); color: var(--fg); overflow: auto; white-space: pre; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }

  .block--takeaway ul { margin: 0; padding-left: 18px; color: var(--fg-muted); line-height: 1.6; }
  .block--takeaway li { margin-bottom: 6px; }
  .block--takeaway strong { color: var(--fg); }
  .block--takeaway code { background: var(--bg); padding: 1px 6px; border-radius: 3px; font-size: 0.9em; }
  .closing { color: var(--fg-muted); font-style: italic; margin: var(--space-3) 0 0 0; }
</style>
