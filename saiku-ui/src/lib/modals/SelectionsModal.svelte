<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { SaikuMember } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/SelectionsModal.js — member
   * selections for a level, with include/exclude + search + show-unique. */
  export type SelectionType = "INCLUSION" | "EXCLUSION";

  interface Props {
    levelCaption: string;
    available: SaikuMember[];
    initialSelected: string[];
    initialType: SelectionType;
    open: boolean;
    /** If false, the "Open date filter" button is hidden. Usually set from a
     *  caption heuristic on the hierarchy (see `looksLikeTimeHierarchy`). */
    showDateFilter?: boolean;
    onSave: (uniqueNames: string[], type: SelectionType) => void;
    onOpenDateFilter: () => void;
    onCancel: () => void;
  }

  let {
    levelCaption,
    available,
    initialSelected,
    initialType,
    open,
    showDateFilter = true,
    onSave,
    onOpenDateFilter,
    onCancel,
  }: Props = $props();

  let search = $state("");
  let selected = $state<Set<string>>(new Set(initialSelected));
  let type = $state<SelectionType>(initialType);

  $effect(() => {
    if (open) {
      selected = new Set(initialSelected);
      type = initialType;
      search = "";
    }
  });

  const filtered = $derived(
    available.filter((m) =>
      !search ||
      (m.caption || m.name).toLowerCase().includes(search.toLowerCase()),
    ),
  );

  function toggle(un: string) {
    if (selected.has(un)) selected.delete(un);
    else selected.add(un);
    selected = new Set(selected);
  }

  function selectAll() {
    selected = new Set(filtered.map((m) => m.uniqueName));
  }

  function clear() {
    selected = new Set();
  }
</script>

<Modal title={`${i18n.t("modal.selections.title")} ${levelCaption}`} {open} size="lg" onClose={onCancel}>
  <div class="row">
    <label class="field field--grow">
      <span class="field__label">{i18n.t("modal.selections.filterMembers")}</span>
      <input class="field__input" bind:value={search} placeholder={i18n.t("modal.selections.searchPlaceholder")} />
    </label>
    <label class="field">
      <span class="field__label">{i18n.t("modal.selections.mode")}</span>
      <select class="field__input" bind:value={type}>
        <option value="INCLUSION">{i18n.t("modal.selections.include")}</option>
        <option value="EXCLUSION">{i18n.t("modal.selections.exclude")}</option>
      </select>
    </label>
  </div>
  <div class="bar">
    <button type="button" class="btn" onclick={selectAll}>{i18n.t("modal.selections.selectAll")}</button>
    <button type="button" class="btn" onclick={clear}>{i18n.t("modal.selections.clear")}</button>
    <span class="count">{selected.size} {i18n.t("modal.selections.selected")}</span>
  </div>
  <ul class="members">
    {#each filtered as m}
      <li>
        <label>
          <input type="checkbox" checked={selected.has(m.uniqueName)} onchange={() => toggle(m.uniqueName)} />
          <span class="name">{m.caption || m.name}</span>
          {#if m.description}<span class="desc">{m.description}</span>{/if}
        </label>
      </li>
    {/each}
    {#if filtered.length === 0}
      <li class="empty">{i18n.t("modal.selections.noMatch")}</li>
    {/if}
  </ul>
  {#snippet footer()}
    {#if showDateFilter}
      <button type="button" class="btn" onclick={onOpenDateFilter}>{i18n.t("modal.selections.openDate")}</button>
    {/if}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(Array.from(selected), type)}>{i18n.t("modal.ok")}</button>
  {/snippet}
</Modal>

<style>
  .row { display: flex; gap: var(--space-3); align-items: end; }
  .field--grow { flex: 1; }
  .bar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: var(--space-3) 0;
  }
  .count { margin-left: auto; color: var(--fg-muted); font-size: var(--fs-sm); }
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
  .name { flex: 1; }
  .desc { color: var(--fg-subtle); font-size: var(--fs-xs); }
</style>
