<script lang="ts">
  import type { CellEntry, QueryResult } from "$lib/api/query";
  import { parseCellset, rowHeaderDisplay } from "$lib/views/cellsetUtils";
  import { query as queryStore } from "$lib/stores/query.svelte";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { listLevelMembers, listRootMembers } from "$lib/api/discover";
  import { toasts } from "$lib/stores/toasts.svelte";

  interface Props {
    result: QueryResult;
  }

  let { result }: Props = $props();

  let parsed = $derived(parseCellset(result));
  let rowDisplay = $derived(rowHeaderDisplay(parsed));

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
        toasts.danger("Could not load members", err instanceof Error ? err.message : String(err));
        return [];
      }
    }
  }

  let wrapperEl: HTMLDivElement | null = null;

  function isNumeric(v: unknown): boolean {
    if (typeof v !== "string") return typeof v === "number";
    return /^-?\d[\d,. ]*$/.test(v);
  }

  function onDocumentClick(e: MouseEvent) {
    if (!menu.open) return;
    const target = e.target as Node | null;
    const host = document.querySelector(".cellset-ctx-menu");
    if (host && target && host.contains(target)) return;
    closeMenu();
  }
</script>

<svelte:window onclick={onDocumentClick} />

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if (result.cellset?.length ?? 0) === 0}
  <p class="empty">No rows returned.</p>
{:else}
  <div class="cellset-wrap" bind:this={wrapperEl}>
    <table class="cellset">
      <thead>
        {#each parsed.columnHeaderRows as hdrRow, rIdx}
          <tr>
            {#each hdrRow as c, cIdx}
              {#if cIdx < parsed.rowHeaderColCount}
                <th class="all_null"></th>
              {:else if c.type === "COLUMN_HEADER"}
                {@const isLastHeader = rIdx === parsed.columnHeaderRows.length - 1}
                <th
                  class={isLastHeader ? "col col--last" : "col"}
                  title={c.value}
                  oncontextmenu={(e) => openMenu(e, c, "COLUMNS")}
                >{c.value || "\u00A0"}</th>
              {:else}
                <th class="col_null"></th>
              {/if}
            {/each}
          </tr>
        {/each}
      </thead>
      <tbody>
        {#each parsed.bodyRows as rowCells, r}
          <tr>
            {#each rowCells as c, cIdx}
              {@const display = rowDisplay[r][cIdx]}
              {#if display.isNull}
                <th class="row_null"></th>
              {:else}
                <th
                  class="row"
                  title={c.value}
                  oncontextmenu={(e) => openMenu(e, c, "ROWS")}
                >{c.value}</th>
              {/if}
            {/each}
            {#each parsed.dataRows[r] as dc}
              {@const num = isNumeric(dc.value)}
              <td class={num ? "data data-num" : "data"}>{dc.value}</td>
            {/each}
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  {#if result.runtime != null}
    <p class="runtime">
      Runtime: {result.runtime} ms · {result.height ?? 0} rows × {result.width ?? 0} cols
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
    >Keep Only</button>
    {#if menu.dimensionName && menu.dimensionName !== "Measures"}
      <div class="cellset-ctx-menu__item cellset-ctx-menu__item--parent">
        <button type="button" onclick={() => (menu.sub = menu.sub === "include" ? null : "include")}>
          Include Level ▸
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
              <div class="cellset-ctx-menu__empty">No levels available</div>
            {/if}
          </div>
        {/if}
      </div>
      <div class="cellset-ctx-menu__item cellset-ctx-menu__item--parent">
        <button type="button" onclick={() => (menu.sub = menu.sub === "remove" ? null : "remove")}>
          Remove Level ▸
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
              <div class="cellset-ctx-menu__empty">Nothing to remove</div>
            {/if}
          </div>
        {/if}
      </div>
      <button type="button" class="cellset-ctx-menu__item" onclick={filterLevel}>
        Filter Level…
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
    font-weight: 600;
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
    font-weight: 600;
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
