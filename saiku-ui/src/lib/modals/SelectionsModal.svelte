<script lang="ts">
  /**
   * Member-selections modal — picks which members to include / exclude
   * from a hierarchy's cellset axis. Now hierarchy-wide rather than
   * single-level: when the bound hierarchy has multiple levels (e.g.
   * Year / Quarter / Month / Day), the modal surfaces all of them as
   * tabs so the user can filter at any level without closing and
   * re-opening.
   *
   * Lazy-loads each level's members on first tab-click rather than
   * pre-fetching everything — a time hierarchy's Day level can carry
   * thousands of members and would freeze the modal open if all
   * levels loaded eagerly.
   */
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import type { SaikuMember } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  export type SelectionType = "INCLUSION" | "EXCLUSION";

  export interface LevelSelection {
    levelName: string;
    selected: string[];
    type: SelectionType;
  }

  interface Props {
    /** Display caption for the hierarchy (modal title). */
    hierarchyCaption: string;
    /** Level names in schema order (Year, Quarter, Month, Day for Time). */
    levelNames: string[];
    /** Pre-existing per-level selections — keyed by levelName. */
    initialPerLevel: Record<string, { selected: string[]; type: SelectionType }>;
    /** Initial active tab (the level the user clicked to open the modal). */
    initialLevelName: string;
    open: boolean;
    /** If false, the "Open date filter" button is hidden. Set from a
     *  caption heuristic on the hierarchy (see `looksLikeTimeHierarchy`). */
    showDateFilter?: boolean;
    /** Async load callback — the parent fetches members for the named
     *  level and returns them. The modal calls this on first tab-click
     *  per level. */
    loadMembers: (levelName: string) => Promise<SaikuMember[]>;
    /** Save fires once with the full per-level selection map. Levels
     *  the user didn't visit pass through unchanged from initialPerLevel. */
    onSave: (perLevel: LevelSelection[]) => void;
    onOpenDateFilter: () => void;
    onCancel: () => void;
  }

  let {
    hierarchyCaption,
    levelNames,
    initialPerLevel,
    initialLevelName,
    open,
    showDateFilter = true,
    loadMembers,
    onSave,
    onOpenDateFilter,
    onCancel,
  }: Props = $props();

  // Per-level working state. Selections / type seed from initial; members
  // load lazily on first tab-click for that level.
  let perLevel = $state<
    Record<
      string,
      {
        members: SaikuMember[] | null;
        loading: boolean;
        error: string | null;
        selected: Set<string>;
        type: SelectionType;
      }
    >
  >({});

  let activeLevel = $state<string>(initialLevelName);
  let search = $state("");

  $effect(() => {
    if (!open) return;
    // Reset working state on every open from whatever the parent passed.
    const next: typeof perLevel = {};
    for (const lvl of levelNames) {
      const prior = initialPerLevel[lvl] ?? { selected: [], type: "INCLUSION" };
      next[lvl] = {
        members: null,
        loading: false,
        error: null,
        selected: new Set(prior.selected),
        type: prior.type,
      };
    }
    perLevel = next;
    activeLevel = initialLevelName;
    search = "";
    void ensureLoaded(initialLevelName);
  });

  async function ensureLoaded(lvl: string): Promise<void> {
    const slot = perLevel[lvl];
    if (!slot || slot.members !== null || slot.loading) return;
    slot.loading = true;
    slot.error = null;
    perLevel = { ...perLevel, [lvl]: { ...slot } };
    try {
      const members = await loadMembers(lvl);
      perLevel[lvl] = { ...perLevel[lvl], members, loading: false };
    } catch (e) {
      perLevel[lvl] = {
        ...perLevel[lvl],
        loading: false,
        error: e instanceof Error ? e.message : String(e),
      };
    }
  }

  function pickTab(lvl: string): void {
    if (lvl === activeLevel) return;
    activeLevel = lvl;
    search = "";
    void ensureLoaded(lvl);
  }

  const activeSlot = $derived(perLevel[activeLevel]);
  const filtered = $derived.by<SaikuMember[]>(() => {
    if (!activeSlot?.members) return [];
    const q = search.toLowerCase();
    if (!q) return activeSlot.members;
    return activeSlot.members.filter((m) =>
      (m.caption || m.name).toLowerCase().includes(q),
    );
  });

  function toggle(un: string): void {
    const slot = perLevel[activeLevel];
    if (!slot) return;
    if (slot.selected.has(un)) slot.selected.delete(un);
    else slot.selected.add(un);
    perLevel[activeLevel] = { ...slot, selected: new Set(slot.selected) };
  }

  function selectAll(): void {
    const slot = perLevel[activeLevel];
    if (!slot?.members) return;
    perLevel[activeLevel] = {
      ...slot,
      selected: new Set(filtered.map((m) => m.uniqueName)),
    };
  }

  function clear(): void {
    const slot = perLevel[activeLevel];
    if (!slot) return;
    perLevel[activeLevel] = { ...slot, selected: new Set() };
  }

  function setType(t: SelectionType): void {
    const slot = perLevel[activeLevel];
    if (!slot) return;
    perLevel[activeLevel] = { ...slot, type: t };
  }

  /** Tab badge shows how many members are selected at that level. */
  function tabCount(lvl: string): number {
    return perLevel[lvl]?.selected.size ?? 0;
  }

  function save(): void {
    const out: LevelSelection[] = [];
    for (const lvl of levelNames) {
      const slot = perLevel[lvl];
      if (!slot) continue;
      out.push({
        levelName: lvl,
        selected: Array.from(slot.selected),
        type: slot.type,
      });
    }
    onSave(out);
  }
</script>

<Modal
  title={`${i18n.t("modal.selections.title")} ${hierarchyCaption}`}
  {open}
  size="lg"
  onClose={onCancel}
>
  {#if levelNames.length > 1}
    <div class="tabs" role="tablist" aria-label={i18n.t("modal.selections.levelTabs")}>
      {#each levelNames as lvl}
        {@const count = tabCount(lvl)}
        <button
          type="button"
          role="tab"
          class={"tabs__btn " + (activeLevel === lvl ? "is-active" : "")}
          aria-selected={activeLevel === lvl}
          onclick={() => pickTab(lvl)}
        >
          {lvl}
          {#if count > 0}<span class="tabs__count">{count}</span>{/if}
        </button>
      {/each}
    </div>
  {/if}

  <div class="row">
    <label class="field flex-1">
      <span class="field__label">{i18n.t("modal.selections.filterMembers")}</span>
      <input
        class="field__input"
        bind:value={search}
        placeholder={i18n.t("modal.selections.searchPlaceholder")}
      />
    </label>
    <label class="field">
      <span class="field__label">{i18n.t("modal.selections.mode")}</span>
      <select
        class="field__input"
        value={activeSlot?.type ?? "INCLUSION"}
        onchange={(e) =>
          setType((e.currentTarget as HTMLSelectElement).value as SelectionType)}
      >
        <option value="INCLUSION">{i18n.t("modal.selections.include")}</option>
        <option value="EXCLUSION">{i18n.t("modal.selections.exclude")}</option>
      </select>
    </label>
  </div>

  <div class="bar">
    <Button variant="outline" onclick={selectAll}>
      {i18n.t("modal.selections.selectAll")}
    </Button>
    <Button variant="outline" onclick={clear}>
      {i18n.t("modal.selections.clear")}
    </Button>
    <span class="ml-auto text-fg-muted text-sm">
      {activeSlot?.selected.size ?? 0} {i18n.t("modal.selections.selected")}
    </span>
  </div>

  <ul class="members">
    {#if activeSlot?.loading}
      <li class="empty">{i18n.t("modal.selections.loading", "Loading members…")}</li>
    {:else if activeSlot?.error}
      <li class="empty text-danger">{activeSlot.error}</li>
    {:else if filtered.length === 0}
      <li class="empty">{i18n.t("modal.selections.noMatch")}</li>
    {:else}
      {#each filtered as m}
        <li>
          <label>
            <input
              type="checkbox"
              checked={activeSlot?.selected.has(m.uniqueName) ?? false}
              onchange={() => toggle(m.uniqueName)}
            />
            <span class="flex-1">{m.caption || m.name}</span>
            {#if m.description}
              <span class="text-fg-subtle text-xs">{m.description}</span>
            {/if}
          </label>
        </li>
      {/each}
    {/if}
  </ul>

  {#snippet footer()}
    {#if showDateFilter}
      <Button variant="outline" onclick={onOpenDateFilter}>
        {i18n.t("modal.selections.openDate")}
      </Button>
    {/if}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={save}>{i18n.t("modal.ok")}</Button>
  {/snippet}
</Modal>

<style>
  /* Level tabs — one per level of the hierarchy. Active tab gets an
     underline accent; selection-count badge shows when non-zero. */
  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    border-bottom: 1px solid var(--border);
    margin-bottom: var(--space-3);
  }
  .tabs__btn {
    background: transparent;
    border: 0;
    color: var(--fg-muted);
    padding: var(--space-2) var(--space-3);
    font: inherit;
    font-size: var(--fs-sm);
    cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .tabs__btn:hover { color: var(--fg); }
  .tabs__btn.is-active {
    color: var(--fg);
    border-bottom-color: var(--accent);
    font-weight: var(--weight-semibold);
  }
  .tabs__count {
    background: var(--accent-soft);
    color: var(--accent-strong);
    border-radius: 999px;
    padding: 1px 6px;
    font-size: 11px;
    line-height: 1.4;
  }

  .row { display: flex; gap: var(--space-3); align-items: end; }
  .bar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: var(--space-3) 0;
  }
  .members {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 45vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .members li + li { border-top: 1px solid var(--border); }
  .members li.empty { padding: var(--space-4); color: var(--fg-muted); text-align: center; }
  .members label {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
  }
  .members label:hover { background: var(--bg-subtle); }
</style>
