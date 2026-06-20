<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import type { SaikuMeasure } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { measuresHiddenToggle } from "$lib/stores/measuresHiddenToggle.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MeasuresModal.js. */
  interface Props {
    available: SaikuMeasure[];
    selectedUniqueNames: string[];
    open: boolean;
    onSave: (uniqueNames: string[]) => void;
    onCancel: () => void;
    /** Refetch the measure list when the admin flips "Show hidden measures".
     *  Owner (DimensionList) re-runs the cube-metadata fetch with
     *  `includeHidden=true|false` and pipes the new array into `available`.
     *  Optional so legacy callers (none today) still compile. */
    onIncludeHiddenChange?: (includeHidden: boolean) => void;
    /** Optional loading flag while the host refetches. Suppresses the
     *  empty-state flash between toggle-flip and new data arrival. */
    refreshing?: boolean;
  }

  let {
    available,
    selectedUniqueNames,
    open,
    onSave,
    onCancel,
    onIncludeHiddenChange,
    refreshing = false,
  }: Props = $props();

  let picks = $state<Set<string>>(untrack(() => new Set(selectedUniqueNames)));
  let search = $state("");

  $effect(() => {
    if (open) picks = new Set(selectedUniqueNames);
  });

  const filtered = $derived(
    available.filter((m) =>
      !search ||
      (m.caption || m.name).toLowerCase().includes(search.toLowerCase()),
    ),
  );

  function toggle(un: string) {
    if (picks.has(un)) picks.delete(un);
    else picks.add(un);
    picks = new Set(picks);
  }

  function onHiddenToggleChange(e: Event): void {
    const next = (e.currentTarget as HTMLInputElement).checked;
    measuresHiddenToggle.set(next);
    onIncludeHiddenChange?.(next);
  }
</script>

<Modal title={i18n.t("panels.measures")} {open} size="md" onClose={onCancel}>
  <input class="field__input" placeholder={i18n.t("modal.calc.filterMeasures")} bind:value={search} />
  {#if session.isAdmin}
    <!-- Admin-only opt-in. Hiding from non-admins is a UI convenience;
         the server-side filter on /discover/.../measures/?includeHidden=
         (saiku#778) is the real gate. -->
    <label class="hidden-toggle">
      <input
        type="checkbox"
        checked={measuresHiddenToggle.enabled}
        onchange={onHiddenToggleChange}
      />
      <span class="hidden-toggle__label">
        {i18n.t("modal.measures.showHidden")}
      </span>
      <span class="hidden-toggle__hint">
        {i18n.t("modal.measures.showHiddenHint")}
      </span>
    </label>
  {/if}
  {#if refreshing}
    <p class="hidden-toggle__loading">{i18n.t("modal.measures.loading")}</p>
  {/if}
  <ul class="list">
    {#each filtered as m}
      <li>
        <label>
          <input type="checkbox" checked={picks.has(m.uniqueName)} onchange={() => toggle(m.uniqueName)} />
          <span class="name">{m.caption || m.name}</span>
          {#if m.calculated}<span class="badge">{i18n.t("modal.measures.calcBadge")}</span>{/if}
          {#if m.visible === false}<span class="badge badge--hidden">{i18n.t("modal.measures.hiddenBadge")}</span>{/if}
        </label>
      </li>
    {/each}
  </ul>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={() => onSave(Array.from(picks))}>{i18n.t("modal.save")}</Button>
  {/snippet}
</Modal>

<style>
  .list {
    list-style: none;
    margin: var(--space-3) 0 0;
    padding: 0;
    max-height: 50vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .list li + li { border-top: 1px solid var(--border); }
  .list label {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
  }
  .list label:hover { background: var(--bg-subtle); }
  .badge {
    font-size: var(--fs-xs);
    color: var(--accent);
    padding: 0 var(--space-1);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
  }
  .badge--hidden {
    color: var(--fg-muted);
    border-color: var(--fg-muted);
  }
  .name { flex: 1; }

  .hidden-toggle {
    display: grid;
    grid-template-columns: auto 1fr;
    column-gap: var(--space-2);
    row-gap: 2px;
    align-items: center;
    margin-top: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px dashed var(--border);
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  .hidden-toggle:hover { background: var(--bg-subtle); }
  .hidden-toggle input { grid-row: 1 / span 2; }
  .hidden-toggle__label {
    font-size: var(--fs-sm);
    font-weight: var(--weight-medium);
  }
  .hidden-toggle__hint {
    font-size: var(--fs-xs);
    color: var(--fg-muted);
  }
  .hidden-toggle__loading {
    margin: var(--space-2) 0 0;
    font-size: var(--fs-xs);
    color: var(--fg-muted);
  }
</style>
