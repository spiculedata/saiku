<script lang="ts">
  // Demo: what "PAGES axis support" would feel like in Saiku. The left column
  // mocks the dimension sidebar. The right column mocks the query canvas with
  // *four* drop zones — FILTER, PAGES, COLUMNS, ROWS. Drag a dim between
  // FILTER and PAGES and watch the grid reshape from a single slice to a
  // trellis. PRODUCT is pinned to ROWS and REGION to COLUMNS so the demo
  // stays focused on the third-axis question.

  interface Dim { key: string; name: string; members: string[]; }

  const PRODUCT: Dim = { key: "product",  name: "Product",  members: ["Widgets", "Gadgets", "Gizmos", "Doohickeys", "Thingamajigs"] };
  const REGION:  Dim = { key: "region",   name: "Region",   members: ["North", "South", "East", "West"] };
  const QUARTER: Dim = { key: "quarter",  name: "Quarter",  members: ["Q1 2026", "Q2 2026", "Q3 2026", "Q4 2026"] };
  const SCENARIO:Dim = { key: "scenario", name: "Scenario", members: ["Actual", "Budget", "Forecast"] };
  const CHANNEL: Dim = { key: "channel",  name: "Channel",  members: ["Retail", "Online", "Wholesale"] };

  const ALL_DIMS = [PRODUCT, REGION, QUARTER, SCENARIO, CHANNEL];
  const SWAPPABLE = [QUARTER, SCENARIO, CHANNEL]; // dims that can go on FILTER or PAGES

  type Zone = "FILTER" | "PAGES" | "COLUMNS" | "ROWS";

  // Zone assignment. Product is pinned to ROWS and Region to COLUMNS (typical
  // Saiku setup). Quarter defaults to PAGES so the "after" state is visible on
  // load; Scenario and Channel are available (dragged out of any zone =
  // available). Unassigned swappable dims lock to their first member as a
  // silent slicer (the normal Mondrian default for unused dims).
  let placement = $state<Record<string, Zone | null>>({
    product: "ROWS",
    region: "COLUMNS",
    quarter: "PAGES",
    scenario: null,
    channel: null,
  });
  let filterIdx = $state<Record<string, number>>({ quarter: 0, scenario: 0, channel: 0 });
  let silentSlicerIdx = $state<Record<string, number>>({ quarter: 0, scenario: 0, channel: 0 });
  let chartType = $state<"bar" | "line">("bar");

  function dimByKey(k: string): Dim {
    return ALL_DIMS.find((d) => d.key === k)!;
  }
  function dimsInZone(zone: Zone): Dim[] {
    return Object.entries(placement)
      .filter(([, z]) => z === zone)
      .map(([k]) => dimByKey(k));
  }
  const availableDims = $derived(SWAPPABLE.filter((d) => placement[d.key] === null));
  const pagesDim = $derived(dimsInZone("PAGES").find((d) => d.key !== "product" && d.key !== "region") ?? null);
  const filterDim = $derived(dimsInZone("FILTER").find((d) => d.key !== "product" && d.key !== "region") ?? null);

  // --- Drag + drop ---
  let dragKey = $state<string | null>(null);
  let dragOver = $state<Zone | "available" | null>(null);
  function onDimDragStart(e: DragEvent, key: string) {
    dragKey = key;
    e.dataTransfer?.setData("application/x-demo-dim", key);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
  }
  function onZoneDragOver(e: DragEvent, zone: Zone | "available") {
    if (!e.dataTransfer?.types?.includes("application/x-demo-dim")) return;
    e.preventDefault();
    dragOver = zone;
  }
  function onZoneDragLeave(zone: Zone | "available") {
    if (dragOver === zone) dragOver = null;
  }
  function onZoneDrop(e: DragEvent, zone: Zone | "available") {
    e.preventDefault();
    const key = e.dataTransfer?.getData("application/x-demo-dim");
    dragOver = null;
    dragKey = null;
    if (!key) return;
    // Pinned dims stay put.
    if (key === "product" || key === "region") return;
    // A swappable zone can only hold one swappable dim in this demo; evict the
    // previous occupant back to "available".
    if (zone === "FILTER" || zone === "PAGES") {
      for (const k of Object.keys(placement)) {
        if (k !== key && placement[k] === zone && k !== "product" && k !== "region") {
          placement[k] = null;
        }
      }
      placement[key] = zone;
    } else if (zone === "available") {
      placement[key] = null;
    }
    // ROWS and COLUMNS are pinned in this demo; silently ignore drops there.
  }
  function onZoneDragEnd() { dragKey = null; dragOver = null; }

  // --- Data ---
  function sales(product: number, region: number, quarter: number, scenario: number, channel: number): number {
    let v = 180 + product * 45 + region * 31;
    v *= 1 + 0.1 * (quarter + 1);
    if (quarter === 1) v *= 1.15;
    if (quarter === 3) v *= 1.08;
    v *= scenario === 0 ? 1 : scenario === 1 ? 0.9 : 1.1;
    v *= channel === 0 ? 1 : channel === 1 ? 1.12 : 0.82;
    v += ((product * 7 + region * 13 + quarter * 19 + scenario * 23 + channel * 29) % 37) - 18;
    return Math.round(v);
  }

  // Resolve the 5 indices for a cell given which dim is on PAGES (if any),
  // which page index we're on, and all other dims' locked values.
  function indexFor(dim: Dim, pageIdx: number): number {
    if (pagesDim && dim.key === pagesDim.key) return pageIdx;
    if (filterDim && dim.key === filterDim.key) return filterIdx[dim.key] ?? 0;
    return silentSlicerIdx[dim.key] ?? 0;
  }
  function grid(pageIdx: number): number[][] {
    const qIdx = indexFor(QUARTER,  pageIdx);
    const sIdx = indexFor(SCENARIO, pageIdx);
    const cIdx = indexFor(CHANNEL,  pageIdx);
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
  function fmt(n: number): string { return n.toLocaleString(undefined, { maximumFractionDigits: 0 }); }

  const pageMembers = $derived(pagesDim ? pagesDim.members : ["(no pages)"]);
  const panelCount = $derived(pagesDim ? pagesDim.members.length : 1);

  function globalMax(): number {
    let m = 0;
    for (let i = 0; i < panelCount; i++) {
      for (const row of grid(i)) for (const v of row) if (v > m) m = v;
    }
    return m || 1;
  }

  // --- MDX preview ---
  const mdx = $derived.by(() => {
    const rows = "[Product].[Product].Members";
    const cols = "[Region].[Region].Members";
    const pages = pagesDim ? `\n  [${pagesDim.name}].[${pagesDim.name}].Members  ON PAGES,` : "";
    const whereParts: string[] = [];
    if (filterDim) whereParts.push(`[${filterDim.name}].[${filterDim.members[filterIdx[filterDim.key] ?? 0]}]`);
    // Silent slicers (unused dims) aren't visible in MDX — they'd be in the
    // query's default members slicer, but we omit them here for clarity.
    const where = whereParts.length ? `\nWHERE (${whereParts.join(", ")})` : "";
    return `SELECT
  ${cols}  ON COLUMNS,
  ${rows}  ON ROWS,${pages}
FROM [Sales]${where}`;
  });

  // --- Chart ---
  const PANEL_W = 260;
  const PANEL_H = 160;
  const PAD_L = 30;
  const PAD_R = 8;
  const PAD_T = 8;
  const PAD_B = 20;

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
        out.push({ x, y, w: Math.max(barW - 1, 1), h, fill: `hsl(${(c * 360) / Math.max(cols, 1)} 60% 55%)` });
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
      paths.push({ d: d.trim(), stroke: `hsl(${(c * 360) / Math.max(cols, 1)} 60% 55%)` });
    }
    return paths;
  }
</script>

<svelte:window ondragend={onZoneDragEnd} />

<div class="page">
  <header>
    <h1>PAGES axis — the interaction</h1>
    <p class="sub">
      The left column is a mock of Saiku's dimension sidebar. The right column
      is a mock of the query canvas with four drop zones: <code>FILTER</code>,
      <code>PAGES</code> (new!), <code>COLUMNS</code>, <code>ROWS</code>.
      Drag <strong>Quarter</strong>, <strong>Scenario</strong>, or <strong>Channel</strong>
      between the <em>FILTER</em> and <em>PAGES</em> zones and watch the result
      reshape. <code>Product</code> and <code>Region</code> are pinned for
      clarity.
    </p>
  </header>

  <section class="layout">
    <!-- Left: dimension sidebar mock -->
    <aside class="sidebar">
      <h3>Cube dimensions</h3>
      <p class="hint">Drag these into a zone →</p>

      <div class="dim dim--pinned">
        <span class="dim__badge">PINNED</span>
        <span class="dim__name">Product</span>
        <span class="dim__card">{PRODUCT.members.length} members</span>
      </div>
      <div class="dim dim--pinned">
        <span class="dim__badge">PINNED</span>
        <span class="dim__name">Region</span>
        <span class="dim__card">{REGION.members.length} members</span>
      </div>

      <h4>Available</h4>
      <p class="hint">Unassigned dims silently lock to their first member.</p>
      <div
        class={dragOver === "available" ? "available-list is-dragover" : "available-list"}
        ondragover={(e) => onZoneDragOver(e, "available")}
        ondragleave={() => onZoneDragLeave("available")}
        ondrop={(e) => onZoneDrop(e, "available")}
      >
        {#if availableDims.length === 0}
          <span class="hint hint--muted">All dims assigned.</span>
        {/if}
        {#each availableDims as d}
          <div
            class="dim dim--drag"
            draggable="true"
            ondragstart={(e) => onDimDragStart(e, d.key)}
            title="Drag me into FILTER or PAGES"
          >
            <span class="dim__badge dim__badge--grab">⋮⋮</span>
            <span class="dim__name">{d.name}</span>
            <span class="dim__card">{d.members.length} members</span>
          </div>
        {/each}
      </div>
    </aside>

    <!-- Right: query canvas mock -->
    <main class="canvas">
      <div class="zones">
        <!-- FILTER -->
        <div
          class={(dragOver === "FILTER" ? "zone is-dragover " : "zone ") + "zone--filter"}
          ondragover={(e) => onZoneDragOver(e, "FILTER")}
          ondragleave={() => onZoneDragLeave("FILTER")}
          ondrop={(e) => onZoneDrop(e, "FILTER")}
        >
          <header>FILTER</header>
          <div class="zone__body">
            {#if dimsInZone("FILTER").length === 0}
              <span class="placeholder">Drop a dim here to lock it to one member.</span>
            {:else}
              {#each dimsInZone("FILTER") as d}
                <div class="chip chip--filter" draggable="true" ondragstart={(e) => onDimDragStart(e, d.key)}>
                  <span>{d.name} =</span>
                  <select bind:value={filterIdx[d.key]}>
                    {#each d.members as m, i}<option value={i}>{m}</option>{/each}
                  </select>
                </div>
              {/each}
            {/if}
          </div>
        </div>

        <!-- PAGES (new) -->
        <div
          class={(dragOver === "PAGES" ? "zone is-dragover " : "zone ") + "zone--pages"}
          ondragover={(e) => onZoneDragOver(e, "PAGES")}
          ondragleave={() => onZoneDragLeave("PAGES")}
          ondrop={(e) => onZoneDrop(e, "PAGES")}
        >
          <header>
            <span>PAGES</span>
            <span class="zone__tag">new</span>
          </header>
          <div class="zone__body">
            {#if dimsInZone("PAGES").length === 0}
              <span class="placeholder">Drop a dim here to compare across all its members (trellis).</span>
            {:else}
              {#each dimsInZone("PAGES") as d}
                <div class="chip chip--pages" draggable="true" ondragstart={(e) => onDimDragStart(e, d.key)}>
                  <span>{d.name} ({d.members.length} panels)</span>
                </div>
              {/each}
            {/if}
          </div>
        </div>

        <!-- COLUMNS (pinned) -->
        <div class="zone zone--pinned">
          <header><span>COLUMNS</span><span class="zone__tag">pinned</span></header>
          <div class="zone__body">
            <div class="chip chip--pinned">{REGION.name}</div>
          </div>
        </div>

        <!-- ROWS (pinned) -->
        <div class="zone zone--pinned">
          <header><span>ROWS</span><span class="zone__tag">pinned</span></header>
          <div class="zone__body">
            <div class="chip chip--pinned">{PRODUCT.name}</div>
          </div>
        </div>
      </div>

      <div class="view-header">
        <div>
          <h3 class="view-title">
            {#if pagesDim}
              Result — trellis across {pagesDim.name} ({pagesDim.members.length} panels)
            {:else}
              Result — single 2D slice
            {/if}
          </h3>
          <p class="view-sub">
            {#if pagesDim}
              One query, every {pagesDim.name.toLowerCase()} visible at once. Bars share a Y-scale so panels are comparable.
            {:else if filterDim}
              {filterDim.name} is locked to <strong>{filterDim.members[filterIdx[filterDim.key] ?? 0]}</strong>. To compare across {filterDim.name.toLowerCase()}s, drag it to PAGES.
            {:else}
              No third-axis dim active. Drag Quarter, Scenario, or Channel into a zone.
            {/if}
          </p>
        </div>
        <div class="chart-pick">
          <button class:active={chartType === "bar"}  onclick={() => (chartType = "bar")}>Bar</button>
          <button class:active={chartType === "line"} onclick={() => (chartType = "line")}>Line</button>
        </div>
      </div>

      <div class="trellis" style="grid-template-columns: repeat({panelCount}, max-content);">
        {#each pageMembers as pm, p}
          <div class="panel">
            {#if pagesDim}<div class="panel__caption">{pagesDim.name} = {pm}</div>{/if}
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

      <details>
        <summary>Generated MDX for this state</summary>
        <pre class="mdx">{mdx}</pre>
      </details>
    </main>
  </section>

  <section class="notes">
    <h2>What this demo is really showing</h2>
    <ol>
      <li>
        <strong>Adding PAGES is one drop zone, not a new subsystem.</strong>
        The interaction is exactly the same as ROWS/COLUMNS/FILTER already are.
        The code change is: (a) add a PAGES entry to the axis enum Saiku already has,
        (b) let the query canvas render a drop-zone for it, (c) let the MDX generator
        emit <code>ON PAGES</code> when non-empty.
      </li>
      <li>
        <strong>The view follows the query, automatically.</strong>
        PAGES non-empty → trellis mode. PAGES empty → current single-grid view.
        No extra user setting needed, though we should still expose a
        <em>"show PAGES as slicer instead"</em> toggle for when the cardinality
        is too high.
      </li>
      <li>
        <strong>Moving a dim between FILTER and PAGES is a re-query.</strong>
        Different MDX, different cellset. The Phase 5 Arrow cache keys include axis
        order, so a FILTER↔PAGES move misses the cache (correctly) and a trellis↔slicer
        view-only toggle hits it (correctly).
      </li>
      <li>
        <strong>Cardinality guardrail.</strong>
        Drop a 500-member dim on PAGES and the browser dies. The real UI should
        warn at ~12 panels and hard-cap at ~30, offering a paged trellis
        (&quot;showing 12 of 47&quot;) above that.
      </li>
    </ol>
  </section>
</div>

<style>
  .page { max-width: 1500px; margin: 0 auto; padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-5); }
  h1 { margin: 0 0 var(--space-2) 0; }
  h2 { margin: 0 0 var(--space-3) 0; font-size: var(--fs-md); }
  h3 { margin: 0 0 var(--space-2) 0; font-size: var(--fs-sm); color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  h4 { margin: var(--space-3) 0 var(--space-1) 0; font-size: var(--fs-sm); color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  .sub { color: var(--fg-muted); max-width: 900px; line-height: 1.5; }
  .hint { color: var(--fg-muted); font-size: var(--fs-xs); margin: 0 0 var(--space-2) 0; }
  .hint--muted { opacity: 0.6; }

  .layout { display: grid; grid-template-columns: 260px 1fr; gap: var(--space-4); align-items: start; }

  .sidebar {
    display: flex; flex-direction: column; gap: 8px;
    padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-muted);
    position: sticky; top: var(--space-3);
  }
  .dim {
    display: flex; align-items: center; gap: 8px;
    padding: 8px 10px; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-sm);
    font-size: var(--fs-sm);
  }
  .dim--drag { cursor: grab; }
  .dim--drag:active { cursor: grabbing; }
  .dim--pinned { opacity: 0.9; }
  .dim__badge { font-size: 10px; padding: 2px 6px; background: var(--bg-subtle); border-radius: 3px; color: var(--fg-muted); font-weight: 600; letter-spacing: 0.06em; }
  .dim__badge--grab { color: var(--accent); }
  .dim__name { flex: 1; font-weight: 600; }
  .dim__card { color: var(--fg-muted); font-size: var(--fs-xs); }
  .available-list {
    display: flex; flex-direction: column; gap: 6px;
    padding: 6px; border: 1px dashed var(--border); border-radius: var(--radius-sm); background: transparent; min-height: 80px;
    transition: background 120ms, border-color 120ms;
  }
  .available-list.is-dragover { border-color: var(--accent); background: color-mix(in srgb, var(--accent) 10%, transparent); border-style: solid; }

  .canvas { display: flex; flex-direction: column; gap: var(--space-3); min-width: 0; }
  .zones { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
  .zone {
    padding: var(--space-3);
    border: 1.5px dashed var(--border-strong); border-radius: var(--radius-md);
    background: var(--bg-muted); display: flex; flex-direction: column; gap: 6px; min-height: 70px;
    transition: background 120ms, border-color 120ms, box-shadow 120ms;
  }
  .zone.is-dragover {
    border-style: solid; border-color: var(--accent);
    background: color-mix(in srgb, var(--accent) 12%, var(--bg-muted));
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 30%, transparent);
  }
  .zone header { display: flex; align-items: center; gap: 8px; font-size: var(--fs-xs); text-transform: uppercase; letter-spacing: 0.08em; font-weight: 700; color: var(--fg-muted); }
  .zone__tag { font-size: 9px; padding: 1px 5px; background: var(--accent); color: var(--accent-fg); border-radius: 3px; letter-spacing: 0.04em; font-weight: 700; }
  .zone--pages { border-color: var(--accent); }
  .zone--pages header { color: var(--accent); }
  .zone--pinned { opacity: 0.75; }
  .zone__body { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; min-height: 30px; }
  .placeholder { color: var(--fg-muted); font-size: var(--fs-xs); font-style: italic; }

  .chip {
    display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px;
    border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--bg); font-size: var(--fs-sm); cursor: grab;
  }
  .chip:active { cursor: grabbing; }
  .chip select { padding: 2px 6px; font: inherit; color: var(--fg); background: transparent; border: 1px solid var(--border); border-radius: 3px; }
  .chip--pages { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  .chip--pinned { opacity: 0.85; cursor: default; }

  .view-header { display: flex; justify-content: space-between; align-items: flex-end; gap: var(--space-3); margin-top: var(--space-3); }
  .view-title { font-size: var(--fs-md); text-transform: none; letter-spacing: 0; color: var(--fg); }
  .view-sub { color: var(--fg-muted); margin: 4px 0 0 0; font-size: var(--fs-sm); max-width: 700px; }
  .chart-pick { display: flex; gap: 6px; }
  .chart-pick button { padding: 4px 12px; border: 1px solid var(--border-strong); background: transparent; color: var(--fg-muted); border-radius: var(--radius-sm); cursor: pointer; font: inherit; }
  .chart-pick button.active { background: var(--accent); color: var(--accent-fg); border-color: var(--accent); }

  .trellis { display: grid; gap: var(--space-3); overflow-x: auto; padding-bottom: var(--space-2); }
  .panel { border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg); padding: var(--space-3); display: flex; flex-direction: column; gap: 6px; min-width: max-content; }
  .panel__caption { font-size: var(--fs-xs); color: var(--fg-muted); font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; }
  .panel table { border-collapse: collapse; font-size: var(--fs-sm); }
  .panel th, .panel td { padding: 4px 10px; border-bottom: 1px solid var(--border); text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .panel thead th { background: var(--bg-muted); color: var(--fg); text-align: left; font-weight: 600; }
  .panel tbody th.rh { background: var(--bg-muted); text-align: left; font-weight: 500; }

  .legend { display: flex; flex-wrap: wrap; gap: var(--space-3); font-size: var(--fs-xs); color: var(--fg-muted); }
  .legend__dot { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }

  details { border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); background: var(--bg-muted); }
  details[open] { padding-bottom: 0; }
  summary { cursor: pointer; color: var(--fg-muted); font-size: var(--fs-sm); }
  .mdx { background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 12px; font-size: var(--fs-xs); color: var(--fg); overflow: auto; white-space: pre; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; margin-top: var(--space-2); }

  .notes { padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--bg-muted); }
  .notes ol { margin: 0; padding-left: 22px; color: var(--fg-muted); line-height: 1.6; }
  .notes li { margin-bottom: 8px; }
  .notes strong { color: var(--fg); }
  .notes code { background: var(--bg); padding: 1px 6px; border-radius: 3px; font-size: 0.9em; }
</style>
