<script lang="ts">
  import type { CellEntry, QueryResult } from "$lib/api/query";
  import { parseCellset, rowHeaderDisplay } from "$lib/views/cellsetUtils";
  import { query as queryStore } from "$lib/stores/query.svelte";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { listLevelMembers, listRootMembers } from "$lib/api/discover";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    result: QueryResult;
    /** Inline chart rendered in an extra column per body row. */
    spark?: "none" | "line" | "bar";
  }

  let { result, spark = "none" }: Props = $props();

  let parsed = $derived(parseCellset(result));
  let rowDisplay = $derived(rowHeaderDisplay(parsed));

  /** Depth of a row-header member, derived from how many `].[` segments its
   *  uniqueName contains. `[Customers].[USA]` → 0, `[Customers].[USA].[CA]` → 1,
   *  `[Customers].[USA].[CA].[Altadena]` → 2. Used to indent nested members so
   *  parent/child relationships are visually distinct. */
  function depthOf(c: CellEntry | undefined): number {
    const un = c?.properties?.uniquename;
    if (!un) return 0;
    const m = un.match(/\]\.\[/g);
    return Math.max(0, (m?.length ?? 0) - 1);
  }

  function sparkRange(): { min: number; max: number } {
    let min = Infinity, max = -Infinity;
    for (const row of parsed.dataRows) {
      for (const cell of row) {
        const n = Number(String(cell.value ?? "").replace(/[, ]/g, ""));
        if (Number.isFinite(n)) {
          if (n < min) min = n;
          if (n > max) max = n;
        }
      }
    }
    if (min === Infinity) min = 0;
    if (max === -Infinity) max = 1;
    if (min === max) { max = min + 1; }
    return { min, max };
  }

  function sparkValues(rowIdx: number): (number | null)[] {
    const row = parsed.dataRows[rowIdx] ?? [];
    return row.map((c) => {
      const n = Number(String(c.value ?? "").replace(/[, ]/g, ""));
      return Number.isFinite(n) ? n : null;
    });
  }

  function linePath(vals: (number | null)[], min: number, max: number, w: number, h: number): string {
    if (vals.length === 0) return "";
    const span = max - min || 1;
    const step = vals.length > 1 ? w / (vals.length - 1) : 0;
    const parts: string[] = [];
    for (let i = 0; i < vals.length; i++) {
      const v = vals[i];
      if (v == null) continue;
      const x = i * step;
      const y = h - ((v - min) / span) * h;
      parts.push(`${parts.length === 0 ? "M" : "L"}${x.toFixed(1)} ${y.toFixed(1)}`);
    }
    return parts.join(" ");
  }

  function barRects(vals: (number | null)[], min: number, max: number, w: number, h: number): { x: number; y: number; w: number; h: number }[] {
    const span = max - min || 1;
    const step = w / Math.max(vals.length, 1);
    const out: { x: number; y: number; w: number; h: number }[] = [];
    for (let i = 0; i < vals.length; i++) {
      const v = vals[i];
      if (v == null) continue;
      const bh = ((v - min) / span) * h;
      out.push({ x: i * step + 1, y: h - bh, w: Math.max(1, step - 2), h: bh });
    }
    return out;
  }

  const SPARK_W = 120;
  const SPARK_H = 20;

  // Context menu state
  interface LevelItem {
    name: string;
    caption: string;
    uniqueName: string;
    used: boolean;
  }
  interface MenuState {
    open: boolean;
    x: number;
    y: number;
    cell: CellEntry | null;
    axis: "ROWS" | "COLUMNS" | null;
    hierarchyUniqueName: string | null;
    dimensionName: string | null;
    levelName: string | null;
    levelCaption: string | null;
    memberUniqueName: string | null;
    memberCaption: string | null;
    levels: LevelItem[];
    sub: "include" | "remove" | "keep" | null;
  }
  let menu = $state<MenuState>({
    open: false,
    x: 0,
    y: 0,
    cell: null,
    axis: null,
    hierarchyUniqueName: null,
    dimensionName: null,
    levelName: null,
    levelCaption: null,
    memberUniqueName: null,
    memberCaption: null,
    levels: [],
    sub: null,
  });

  async function openMenu(event: MouseEvent, cell: CellEntry, axis: "ROWS" | "COLUMNS") {
    event.preventDefault();
    event.stopPropagation();
    const h = cell.properties?.hierarchy ?? "";
    const d = cell.properties?.dimension ?? "";
    const lvlUn = cell.properties?.level ?? "";
    const memberUn = cell.properties?.uniquename ?? cell.value ?? "";

    // Enumerate levels in this hierarchy using the cube metadata already cached for the picker.
    const levels: LevelItem[] = [];
    if (selection.cube && session.current) {
      try {
        const md = await datasources.metadata(session.current.username, selection.cube);
        const dim = md.dimensions.find((dd) => dd.name === d || dd.uniqueName === d);
        const hier = dim?.hierarchies?.find((hh) => hh.uniqueName === h);
        const used = usedLevelUniqueNames(h);
        for (const l of hier?.levels ?? []) {
          levels.push({
            name: l.name,
            caption: l.caption || l.name,
            uniqueName: l.uniqueName,
            used: used.has(l.uniqueName),
          });
        }
      } catch {
        // fall through with empty levels list
      }
    }

    let levelName: string | null = null;
    let levelCaption: string | null = null;
    for (const l of levels) {
      if (l.uniqueName === lvlUn) {
        levelName = l.name;
        levelCaption = l.caption;
        break;
      }
    }

    menu = {
      open: true,
      x: event.clientX,
      y: event.clientY,
      cell,
      axis,
      hierarchyUniqueName: h,
      dimensionName: d,
      levelName,
      levelCaption,
      memberUniqueName: memberUn,
      memberCaption: cell.value ?? memberUn,
      levels,
      sub: null,
    };
  }

  function usedLevelUniqueNames(hierarchyUniqueName: string): Set<string> {
    const out = new Set<string>();
    const model = queryStore.current?.queryModel;
    if (!model) return out;
    for (const loc of ["FILTER", "COLUMNS", "ROWS", "PAGES"] as const) {
      const hier = model.axes[loc].hierarchies.find((h) => h.name === hierarchyUniqueName);
      if (!hier) continue;
      for (const levelName of Object.keys(hier.levels)) {
        // Rebuild the level unique name from hierarchy unique name + level name.
        out.add(`${hierarchyUniqueName}.[${levelName}]`);
      }
    }
    return out;
  }

  function closeMenu() {
    menu.open = false;
    menu.sub = null;
  }

  function keepOnly() {
    if (!menu.axis || !menu.hierarchyUniqueName || !menu.levelName || !menu.memberUniqueName) {
      closeMenu();
      return;
    }
    queryStore.setLevelSelection(
      menu.hierarchyUniqueName,
      menu.levelName,
      [menu.memberUniqueName],
      "INCLUSION",
    );
    closeMenu();
    void queryStore.run();
  }

  function includeLevel(lvl: LevelItem) {
    if (!menu.axis || !menu.hierarchyUniqueName || !menu.dimensionName) {
      closeMenu();
      return;
    }
    queryStore.includeLevel(menu.axis, {
      dimensionName: menu.dimensionName,
      dimensionUniqueName: menu.dimensionName,
      hierarchyName: menu.hierarchyUniqueName,
      hierarchyUniqueName: menu.hierarchyUniqueName,
      hierarchyCaption: menu.hierarchyUniqueName,
      levelName: lvl.name,
      levelCaption: lvl.caption,
    });
    closeMenu();
    void queryStore.run();
  }

  function removeLevel(lvl: LevelItem) {
    if (!menu.hierarchyUniqueName) {
      closeMenu();
      return;
    }
    const model = queryStore.current?.queryModel;
    if (model) {
      for (const loc of ["FILTER", "COLUMNS", "ROWS", "PAGES"] as const) {
        const hier = model.axes[loc].hierarchies.find((h) => h.name === menu.hierarchyUniqueName);
        if (!hier) continue;
        delete hier.levels[lvl.name];
        if (Object.keys(hier.levels).length === 0) {
          queryStore.removeHierarchy(menu.hierarchyUniqueName);
        }
      }
      queryStore.current!.queryModel = { ...model };
    }
    closeMenu();
    void queryStore.run();
  }

  async function filterLevel() {
    // Emit a CustomEvent on the wrapper so QueryCanvas can open the SelectionsModal —
    // that component already owns the modal state.
    if (!wrapperEl || !menu.axis || !menu.hierarchyUniqueName || !menu.levelName) {
      closeMenu();
      return;
    }
    const members = await loadLevelMembers();
    wrapperEl.dispatchEvent(
      new CustomEvent("saiku-filter-level", {
        bubbles: true,
        detail: {
          axis: menu.axis,
          hierarchyName: menu.hierarchyUniqueName,
          hierarchyCaption: menu.hierarchyUniqueName,
          levelName: menu.levelName,
          members,
        },
      }),
    );
    closeMenu();
  }

  async function loadLevelMembers() {
    if (!selection.cube || !session.current || !menu.hierarchyUniqueName || !menu.levelName) {
      return [];
    }
    const dim = menu.hierarchyUniqueName.split(".")[0]?.replace(/[[\]]/g, "") ?? "";
    try {
      return await listLevelMembers(
        session.current.username,
        selection.cube,
        dim,
        menu.hierarchyUniqueName,
        menu.levelName,
      );
    } catch {
      try {
        return await listRootMembers(
          session.current.username,
          selection.cube,
          menu.hierarchyUniqueName,
        );
      } catch (err) {
        toasts.danger(i18n.t("toast.loadMembersFailed"), err instanceof Error ? err.message : String(err));
        return [];
      }
    }
  }

  let wrapperEl: HTMLDivElement | null = null;

  function isNumeric(v: unknown): boolean {
    if (typeof v !== "string") return typeof v === "number";
    return /^-?\d[\d,. ]*$/.test(v);
  }

  // --- Drag-to-select range on data cells ---
  interface CellCoord { r: number; c: number }
  let sel = $state<{ anchor: CellCoord | null; focus: CellCoord | null; dragging: boolean }>({
    anchor: null,
    focus: null,
    dragging: false,
  });

  // --- Keyboard navigation: focused cell is the "cursor" ---
  let focused = $state<CellCoord | null>(null);

  function gridBounds(): { rows: number; cols: number } {
    const rows = parsed.dataRows.length;
    const cols = parsed.dataRows[0]?.length ?? 0;
    return { rows, cols };
  }

  /** Seed focus to (0,0) the first time a non-empty cellset arrives, and
   *  drop stale focus when the cellset changes out from under us. */
  $effect(() => {
    const { rows, cols } = gridBounds();
    if (rows === 0 || cols === 0) {
      focused = null;
      return;
    }
    if (!focused) {
      focused = { r: 0, c: 0 };
    } else if (focused.r >= rows || focused.c >= cols) {
      focused = { r: Math.min(focused.r, rows - 1), c: Math.min(focused.c, cols - 1) };
    }
  });

  function moveFocus(dr: number, dc: number, extendSelection: boolean): void {
    const { rows, cols } = gridBounds();
    if (rows === 0 || cols === 0) return;
    const cur = focused ?? { r: 0, c: 0 };
    const nr = Math.max(0, Math.min(rows - 1, cur.r + dr));
    const nc = Math.max(0, Math.min(cols - 1, cur.c + dc));
    setFocus({ r: nr, c: nc }, extendSelection);
  }

  function setFocus(to: CellCoord, extendSelection: boolean): void {
    focused = to;
    if (extendSelection) {
      if (!sel.anchor) {
        // Seed the anchor at the previous focus / current cell so shift-arrow
        // extends from where the user started.
        sel = { anchor: to, focus: to, dragging: false };
      } else {
        sel = { ...sel, focus: to };
      }
    }
    scrollFocusedIntoView();
  }

  function scrollFocusedIntoView(): void {
    if (!wrapperEl || !focused) return;
    // When virtualising, the target row may not be in the DOM yet — compute
    // the desired scrollTop directly based on the estimated row height so the
    // focused row lands inside the viewport.
    if (virtualize && rowH > 0) {
      const targetTop = focused.r * rowH;
      const vh = wrapperEl.clientHeight;
      if (targetTop < wrapperEl.scrollTop) {
        wrapperEl.scrollTop = Math.max(0, targetTop);
      } else if (targetTop + rowH > wrapperEl.scrollTop + vh) {
        wrapperEl.scrollTop = targetTop - vh + rowH;
      }
    }
    // Defer a tick so the DOM reflects the new focused class / row before we
    // try to find the <td>. Then use scrollIntoView to handle horizontal
    // scroll (and vertical when not virtualising).
    queueMicrotask(() => {
      if (!wrapperEl || !focused) return;
      const cell = wrapperEl.querySelector<HTMLTableCellElement>(
        `td.data[data-r="${focused.r}"][data-c="${focused.c}"]`,
      );
      cell?.scrollIntoView({ block: "nearest", inline: "nearest" });
    });
  }

  function onGridKeydown(e: KeyboardEvent) {
    // Ignore when typing into a descendant form control (e.g. the context
    // menu submenu buttons don't accept text, but be safe).
    const target = e.target as HTMLElement | null;
    if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
    if (menu.open) return;

    const { rows, cols } = gridBounds();
    if (rows === 0 || cols === 0) return;

    const shift = e.shiftKey;
    const mod = e.ctrlKey || e.metaKey;
    let handled = true;
    switch (e.key) {
      case "ArrowUp": moveFocus(-1, 0, shift); break;
      case "ArrowDown": moveFocus(1, 0, shift); break;
      case "ArrowLeft": moveFocus(0, -1, shift); break;
      case "ArrowRight": moveFocus(0, 1, shift); break;
      case "PageUp": moveFocus(-10, 0, shift); break;
      case "PageDown": moveFocus(10, 0, shift); break;
      case "Home":
        if (mod) setFocus({ r: 0, c: 0 }, shift);
        else setFocus({ r: focused?.r ?? 0, c: 0 }, shift);
        break;
      case "End":
        if (mod) setFocus({ r: rows - 1, c: cols - 1 }, shift);
        else setFocus({ r: focused?.r ?? 0, c: cols - 1 }, shift);
        break;
      case "Escape":
        clearSelection();
        break;
      default:
        handled = false;
    }
    if (handled) e.preventDefault();
  }

  function isFocused(r: number, c: number): boolean {
    return !!focused && focused.r === r && focused.c === c;
  }

  // --- Row virtualization (windowing) ---
  // Only kicks in for >500 rows; below that the DOM cost is negligible and
  // not virtualizing keeps mouse-scroll smooth without the math overhead.
  const VIRT_THRESHOLD = 500;
  const OVERSCAN = 5;
  const DEFAULT_ROW_H = 24; // tolerable estimate; replaced once we probe DOM.

  let scrollTop = $state(0);
  let viewportH = $state(0);
  let rowH = $state(DEFAULT_ROW_H);

  let virtualize = $derived(parsed.bodyRows.length > VIRT_THRESHOLD);

  /** Compute the window of row indices to render. Includes an overscan on
   *  each side and always includes the focused row even if off-window so
   *  scrollIntoView from keyboard nav works. */
  let renderWindow = $derived.by(() => {
    const total = parsed.bodyRows.length;
    if (!virtualize || total === 0 || rowH <= 0) {
      return { first: 0, last: total - 1, topPad: 0, bottomPad: 0 };
    }
    const vh = viewportH || 400;
    const rawFirst = Math.floor(scrollTop / rowH);
    const visibleCount = Math.ceil(vh / rowH);
    const first = Math.max(0, rawFirst - OVERSCAN);
    const last = Math.min(total - 1, rawFirst + visibleCount + OVERSCAN);
    const topPad = first * rowH;
    const bottomPad = (total - 1 - last) * rowH;
    return { first, last, topPad, bottomPad };
  });

  function onWrapperScroll(e: Event) {
    const el = e.currentTarget as HTMLDivElement;
    scrollTop = el.scrollTop;
    viewportH = el.clientHeight;
  }

  /** Probe the first rendered body row to get its real height, once on mount
   *  and whenever the cellset shape changes. Uniform-height assumption: we
   *  treat every row as the same height as the first one — acceptable for
   *  our grid, where only left-padding (depth-indent) varies. */
  $effect(() => {
    // re-run when the dataset changes shape
    void parsed.bodyRows.length;
    if (!wrapperEl) return;
    queueMicrotask(() => {
      if (!wrapperEl) return;
      viewportH = wrapperEl.clientHeight;
      const row = wrapperEl.querySelector<HTMLTableRowElement>("tbody tr.vrow");
      if (row) {
        const h = row.getBoundingClientRect().height;
        if (h > 0) rowH = h;
      }
    });
  });

  function selRect(): { r0: number; r1: number; c0: number; c1: number } | null {
    if (!sel.anchor || !sel.focus) return null;
    return {
      r0: Math.min(sel.anchor.r, sel.focus.r),
      r1: Math.max(sel.anchor.r, sel.focus.r),
      c0: Math.min(sel.anchor.c, sel.focus.c),
      c1: Math.max(sel.anchor.c, sel.focus.c),
    };
  }
  function isSelected(r: number, c: number): boolean {
    const b = selRect();
    return !!b && r >= b.r0 && r <= b.r1 && c >= b.c0 && c <= b.c1;
  }
  function onCellMouseDown(e: MouseEvent, r: number, c: number) {
    if (e.button !== 0) return; // only left-click
    sel = { anchor: { r, c }, focus: { r, c }, dragging: true };
    focused = { r, c };
    e.preventDefault(); // suppress text selection while dragging
  }
  function onCellMouseEnter(r: number, c: number) {
    if (!sel.dragging) return;
    sel = { ...sel, focus: { r, c } };
    focused = { r, c };
  }
  function endDrag() {
    if (sel.dragging) sel = { ...sel, dragging: false };
  }
  function clearSelection() {
    sel = { anchor: null, focus: null, dragging: false };
  }
  function toNumber(cell: CellEntry | undefined): number | null {
    if (!cell) return null;
    const raw = cell.properties?.raw;
    const src = raw ?? cell.value;
    if (src == null || src === "") return null;
    const n = Number(String(src).replace(/[, ]/g, ""));
    return Number.isFinite(n) ? n : null;
  }
  let selStats = $derived.by(() => {
    const b = selRect();
    if (!b) return null;
    let count = 0, sum = 0, min = Infinity, max = -Infinity;
    const cellCount = (b.r1 - b.r0 + 1) * (b.c1 - b.c0 + 1);
    for (let r = b.r0; r <= b.r1; r++) {
      const row = parsed.dataRows[r];
      if (!row) continue;
      for (let c = b.c0; c <= b.c1; c++) {
        const n = toNumber(row[c]);
        if (n == null) continue;
        count++;
        sum += n;
        if (n < min) min = n;
        if (n > max) max = n;
      }
    }
    if (count === 0) return { cells: cellCount, count: 0, sum: 0, avg: 0, min: 0, max: 0 };
    return { cells: cellCount, count, sum, avg: sum / count, min, max };
  });
  function fmtNum(n: number): string {
    return Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 2 }) : "—";
  }

  function onDataCellContextMenu(e: MouseEvent, row: number, col: number) {
    e.preventDefault();
    e.stopPropagation();
    // row/col are indices into parsed.bodyRows / dataRows. Map to cellset absolute position:
    // absolute row = headerRowCount + row ; absolute col = rowHeaderColCount + col
    const absRow = parsed.headerRowCount + row;
    const absCol = parsed.rowHeaderColCount + col;
    wrapperEl?.dispatchEvent(
      new CustomEvent("saiku-drillthrough", {
        bubbles: true,
        detail: { row: absRow, col: absCol, clientX: e.clientX, clientY: e.clientY },
      }),
    );
  }

  function onDocumentClick(e: MouseEvent) {
    if (!menu.open) return;
    const target = e.target as Node | null;
    const host = document.querySelector(".cellset-ctx-menu");
    if (host && target && host.contains(target)) return;
    closeMenu();
  }
</script>

<svelte:window onclick={onDocumentClick} onmouseup={endDrag} />

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if (result.cellset?.length ?? 0) === 0}
  <p class="empty">{i18n.t("cellset.noRows")}</p>
{:else}
  <div
    class="cellset-wrap"
    bind:this={wrapperEl}
    tabindex="0"
    role="grid"
    aria-label={i18n.t("a11y.cellsetGrid")}
    aria-rowcount={parsed.columnHeaderRows.length + parsed.dataRows.length}
    aria-colcount={parsed.rowHeaderColCount + (parsed.dataRows[0]?.length ?? 0)}
    onkeydown={onGridKeydown}
    onscroll={onWrapperScroll}
  >
    <table class="cellset" role="presentation">
      <thead>
        {#each parsed.columnHeaderRows as hdrRow, rIdx}
          <tr role="row" aria-rowindex={rIdx + 1}>
            {#each hdrRow as c, cIdx}
              {#if cIdx < parsed.rowHeaderColCount}
                <th class="all_null" role="columnheader" aria-colindex={cIdx + 1}></th>
              {:else if c.type === "COLUMN_HEADER"}
                {@const isLastHeader = rIdx === parsed.columnHeaderRows.length - 1}
                <th
                  class={isLastHeader ? "col col--last" : "col"}
                  role="columnheader"
                  aria-colindex={cIdx + 1}
                  title={c.value}
                  oncontextmenu={(e) => openMenu(e, c, "COLUMNS")}
                >{c.value || "\u00A0"}</th>
              {:else}
                <th class="col_null" role="columnheader" aria-colindex={cIdx + 1}></th>
              {/if}
            {/each}
            {#if spark !== "none"}
              <th
                class={rIdx === parsed.columnHeaderRows.length - 1 ? "col col--last spark-col" : "col spark-col"}
                role="columnheader"
                aria-colindex={hdrRow.length + 1}
              >
                {rIdx === parsed.columnHeaderRows.length - 1 ? (spark === "bar" ? "Sparkbar" : "Sparkline") : "\u00A0"}
              </th>
            {/if}
          </tr>
        {/each}
      </thead>
      <tbody>
        {#if renderWindow.topPad > 0}
          <tr class="virt-spacer" aria-hidden="true"><td colspan={parsed.rowHeaderColCount + (parsed.dataRows[0]?.length ?? 0) + (spark !== "none" ? 1 : 0)} style="height: {renderWindow.topPad}px; padding: 0; border: 0;"></td></tr>
        {/if}
        {#each parsed.bodyRows.slice(renderWindow.first, renderWindow.last + 1) as rowCells, iOffset}
          {@const r = renderWindow.first + iOffset}
          <tr class="vrow" role="row" aria-rowindex={parsed.columnHeaderRows.length + r + 1}>
            {#each rowCells as c, cIdx}
              {@const display = rowDisplay[r][cIdx]}
              {#if display.isNull}
                <th class="row_null" role="rowheader" aria-colindex={cIdx + 1}></th>
              {:else}
                {@const d = depthOf(c)}
                <th
                  class={d > 0 ? "row row--nested" : "row"}
                  role="rowheader"
                  aria-colindex={cIdx + 1}
                  style={d > 0 ? `padding-left: calc(12px + ${d}em);` : ""}
                  title={c.value}
                  oncontextmenu={(e) => openMenu(e, c, "ROWS")}
                >{c.value}</th>
              {/if}
            {/each}
            {#each parsed.dataRows[r] as dc, cIdx}
              {@const num = isNumeric(dc.value)}
              {@const selected = isSelected(r, cIdx)}
              {@const hasFocus = isFocused(r, cIdx)}
              <td
                class={(num ? "data data-num" : "data")
                  + (selected ? " data--selected" : "")
                  + (hasFocus ? " data--focused" : "")}
                role="gridcell"
                aria-colindex={parsed.rowHeaderColCount + cIdx + 1}
                aria-selected={selected ? "true" : undefined}
                data-r={r}
                data-c={cIdx}
                onmousedown={(e) => onCellMouseDown(e, r, cIdx)}
                onmouseenter={() => onCellMouseEnter(r, cIdx)}
                oncontextmenu={(e) => onDataCellContextMenu(e, r, cIdx)}
              >{dc.value}</td>
            {/each}
            {#if spark !== "none"}
              {@const range = sparkRange()}
              {@const vals = sparkValues(r)}
              <td class="data spark-cell" role="gridcell" aria-colindex={parsed.rowHeaderColCount + (parsed.dataRows[r]?.length ?? 0) + 1}>
                <svg viewBox={`0 0 ${SPARK_W} ${SPARK_H}`} width={SPARK_W} height={SPARK_H} aria-hidden="true">
                  {#if spark === "line"}
                    <path d={linePath(vals, range.min, range.max, SPARK_W, SPARK_H)} stroke="var(--accent)" stroke-width="1.5" fill="none" />
                  {:else}
                    {#each barRects(vals, range.min, range.max, SPARK_W, SPARK_H) as b}
                      <rect x={b.x} y={b.y} width={b.w} height={b.h} fill="var(--accent)" />
                    {/each}
                  {/if}
                </svg>
              </td>
            {/if}
          </tr>
        {/each}
        {#if renderWindow.bottomPad > 0}
          <tr class="virt-spacer" aria-hidden="true"><td colspan={parsed.rowHeaderColCount + (parsed.dataRows[0]?.length ?? 0) + (spark !== "none" ? 1 : 0)} style="height: {renderWindow.bottomPad}px; padding: 0; border: 0;"></td></tr>
        {/if}
      </tbody>
    </table>
  </div>
  {#if selStats}
    <div class="sel-stats" role="status" aria-live="polite">
      <span>{i18n.t("stats.selected")}: <strong>{selStats.cells}</strong> {selStats.cells === 1 ? i18n.t("stats.cell") : i18n.t("stats.cells")}</span>
      <span>{i18n.t("stats.count")}: <strong>{selStats.count}</strong></span>
      <span>{i18n.t("stats.sum")}: <strong>{fmtNum(selStats.sum)}</strong></span>
      <span>{i18n.t("stats.avg")}: <strong>{fmtNum(selStats.avg)}</strong></span>
      <span>{i18n.t("stats.min")}: <strong>{fmtNum(selStats.min)}</strong></span>
      <span>{i18n.t("stats.max")}: <strong>{fmtNum(selStats.max)}</strong></span>
      <button type="button" class="sel-stats__clear" onclick={clearSelection} title={i18n.t("stats.clearSelection")}>×</button>
    </div>
  {/if}
  {#if result.runtime != null}
    <p class="runtime">
      {i18n.t("stats.runtime")}: {result.runtime} {i18n.t("units.ms")} · {result.height ?? 0} {i18n.t("units.rows")} × {result.width ?? 0} {i18n.t("units.cols")}
    </p>
  {/if}
{/if}

{#if menu.open}
  <div class="cellset-ctx-menu" style="left:{menu.x}px;top:{menu.y}px" role="menu">
    <div class="cellset-ctx-menu__header">{menu.memberCaption}</div>
    <div class="cellset-ctx-menu__sep"></div>
    <button
      type="button"
      class="cellset-ctx-menu__item"
      disabled={!menu.memberUniqueName}
      onclick={keepOnly}
    >{i18n.t("cellset.menu.keepOnly")}</button>
    {#if menu.dimensionName && menu.dimensionName !== "Measures"}
      <div class="cellset-ctx-menu__item cellset-ctx-menu__item--parent">
        <button type="button" onclick={() => (menu.sub = menu.sub === "include" ? null : "include")}>
          {i18n.t("cellset.menu.includeLevel")} ▸
        </button>
        {#if menu.sub === "include"}
          <div class="cellset-ctx-menu__sub">
            {#each menu.levels as lvl}
              <button
                type="button"
                class="cellset-ctx-menu__item"
                disabled={lvl.used}
                onclick={() => includeLevel(lvl)}
              >{lvl.caption}</button>
            {/each}
            {#if menu.levels.length === 0}
              <div class="cellset-ctx-menu__empty">{i18n.t("cellset.menu.noLevels")}</div>
            {/if}
          </div>
        {/if}
      </div>
      <div class="cellset-ctx-menu__item cellset-ctx-menu__item--parent">
        <button type="button" onclick={() => (menu.sub = menu.sub === "remove" ? null : "remove")}>
          {i18n.t("cellset.menu.removeLevel")} ▸
        </button>
        {#if menu.sub === "remove"}
          <div class="cellset-ctx-menu__sub">
            {#each menu.levels.filter((l) => l.used) as lvl}
              <button
                type="button"
                class="cellset-ctx-menu__item"
                onclick={() => removeLevel(lvl)}
              >{lvl.caption}</button>
            {/each}
            {#if menu.levels.filter((l) => l.used).length === 0}
              <div class="cellset-ctx-menu__empty">{i18n.t("cellset.menu.nothingToRemove")}</div>
            {/if}
          </div>
        {/if}
      </div>
      <button type="button" class="cellset-ctx-menu__item" onclick={filterLevel}>
        {i18n.t("cellset.menu.filterLevel")}
      </button>
    {/if}
  </div>
{/if}

<style>
  .cellset-wrap {
    flex: 1;
    min-height: 0;
    overflow: auto;
    border: 1px solid var(--border);
    background: var(--bg);
  }
  .cellset {
    border-collapse: separate;
    border-spacing: 0;
    font-size: var(--fs-sm);
    line-height: 1.3;
    table-layout: auto;
  }
  .cellset th,
  .cellset td {
    padding: 3px 9px 3px 6px;
    border-right: 1px solid var(--border);
    border-bottom: 1px solid var(--border);
    vertical-align: top;
  }
  .cellset thead th {
    position: sticky;
    top: 0;
    z-index: 2;
    background: var(--bg-muted);
    font-weight: var(--weight-semibold);
    text-align: left;
    white-space: nowrap;
  }
  .cellset th.all_null {
    background: var(--bg-muted);
    border-right: 1px solid var(--border);
  }
  .cellset th.col {
    cursor: context-menu;
  }
  .cellset th.col:hover {
    background: var(--bg-subtle);
  }
  .cellset th.col_null {
    background: var(--bg-muted);
  }
  .cellset tbody th.row {
    background: var(--bg-muted);
    text-align: left;
    white-space: nowrap;
    cursor: context-menu;
    position: sticky;
    left: 0;
    z-index: 1;
  }
  .cellset tbody th.row--nested { font-weight: var(--weight-medium); color: var(--fg-muted); }
  .cellset tbody th.row:hover {
    background: var(--bg-subtle);
  }
  .cellset tbody th.row_null {
    background: var(--bg-muted);
    position: sticky;
    left: 0;
    z-index: 1;
  }
  .cellset td.data {
    color: var(--fg);
    text-align: left;
  }
  .cellset td.data-num {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .cellset td.data { cursor: cell; user-select: none; }
  .cellset td.data--selected { background: color-mix(in srgb, var(--accent) 28%, transparent); }
  .cellset tr.virt-spacer td {
    background: transparent;
    border: 0;
    padding: 0;
  }
  .cellset td.data--focused {
    box-shadow: inset 0 0 0 2px var(--accent);
    position: relative;
  }
  .cellset-wrap:focus {
    outline: 2px solid color-mix(in srgb, var(--accent) 50%, transparent);
    outline-offset: -2px;
  }
  .cellset-wrap:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
  .sel-stats {
    flex: 0 0 auto;
    display: flex;
    align-items: center;
    gap: var(--space-4);
    padding: 6px 12px;
    border: 1px solid var(--border);
    border-top: 0;
    background: var(--bg-muted);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    font-variant-numeric: tabular-nums;
  }
  .sel-stats strong { color: var(--fg); font-weight: var(--weight-semibold); }
  .sel-stats__clear {
    margin-left: auto;
    border: none;
    background: transparent;
    color: var(--fg-muted);
    cursor: pointer;
    font-size: 16px;
    line-height: 1;
    padding: 2px 6px;
    border-radius: var(--radius-sm);
  }
  .sel-stats__clear:hover { color: var(--danger); background: var(--bg-subtle); }
  .cellset th.spark-col {
    min-width: 140px;
  }
  .cellset td.spark-cell {
    padding: 2px 6px;
  }
  .cellset td.spark-cell svg {
    display: block;
  }
  .cellset tbody tr:hover td.data,
  .cellset tbody tr:hover th.row {
    background: var(--bg-subtle);
  }
  .empty {
    color: var(--fg-muted);
    padding: var(--space-4);
  }
  .runtime {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
    margin: var(--space-2) 0 0;
    flex: 0 0 auto;
  }

  .cellset-ctx-menu {
    position: fixed;
    min-width: 180px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
    padding: var(--space-1) 0;
    z-index: 1000;
    font-size: var(--fs-sm);
  }
  .cellset-ctx-menu__header {
    padding: var(--space-1) var(--space-3);
    font-weight: var(--weight-semibold);
    color: var(--fg-muted);
    max-width: 320px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cellset-ctx-menu__sep {
    height: 1px;
    background: var(--border);
    margin: 2px 0;
  }
  .cellset-ctx-menu__item {
    display: block;
    width: 100%;
    text-align: left;
    padding: 4px var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
  }
  .cellset-ctx-menu__item:hover:not(:disabled),
  .cellset-ctx-menu__item--parent > button:hover {
    background: var(--bg-subtle);
  }
  .cellset-ctx-menu__item:disabled {
    color: var(--fg-subtle);
    cursor: default;
  }
  .cellset-ctx-menu__item--parent {
    position: relative;
  }
  .cellset-ctx-menu__item--parent > button {
    display: block;
    width: 100%;
    text-align: left;
    padding: 4px var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
  }
  .cellset-ctx-menu__sub {
    margin-left: var(--space-2);
    border-left: 2px solid var(--border);
    background: var(--bg-muted);
  }
  .cellset-ctx-menu__empty {
    padding: 4px var(--space-3);
    color: var(--fg-subtle);
  }
</style>
