<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import {
    buildRelativeMdx,
    buildAbsoluteMdx,
    isRelativeValid,
    isAbsoluteValid,
    type RelativePreset,
  } from "./dateFilterMdx";

  /** Date-range picker that emits a Mondrian MDX set expression. The modal
   *  is pure UI — translation lives in `dateFilterMdx.ts` so we can unit-test
   *  the MDX shapes without spinning up DOM.
   *
   *  Two tabs:
   *   - Relative: preset + optional N (e.g. "Last 7 days", "Year to date").
   *   - Absolute: from/to dates with an optional prior-period comparison.
   *
   *  Preview MDX is rendered reactively at the bottom so the user sees what
   *  will be applied before committing. */

  type Tab = "relative" | "absolute";
  type Compare = "NONE" | "PRIOR_PERIOD" | "PRIOR_YEAR";

  interface Props {
    open: boolean;
    hierarchyCaption: string;
    hierarchyName: string;
    levelName: string;
    onApply: (mdx: string) => void;
    onCancel: () => void;
  }

  let {
    open,
    hierarchyCaption,
    hierarchyName,
    levelName,
    onApply,
    onCancel,
  }: Props = $props();

  const PRESETS: { id: RelativePreset; label: string; takesN: boolean }[] = [
    { id: "TODAY", label: "modal.dateFilter.preset.today", takesN: false },
    { id: "YESTERDAY", label: "modal.dateFilter.preset.yesterday", takesN: false },
    { id: "LAST_N_DAYS", label: "modal.dateFilter.preset.lastNDays", takesN: true },
    { id: "LAST_N_WEEKS", label: "modal.dateFilter.preset.lastNWeeks", takesN: true },
    { id: "LAST_N_MONTHS", label: "modal.dateFilter.preset.lastNMonths", takesN: true },
    { id: "LAST_N_QUARTERS", label: "modal.dateFilter.preset.lastNQuarters", takesN: true },
    { id: "LAST_N_YEARS", label: "modal.dateFilter.preset.lastNYears", takesN: true },
    { id: "ROLLING_N", label: "modal.dateFilter.preset.rolling", takesN: true },
    { id: "MONTH_TO_DATE", label: "modal.dateFilter.preset.mtd", takesN: false },
    { id: "QUARTER_TO_DATE", label: "modal.dateFilter.preset.qtd", takesN: false },
    { id: "YEAR_TO_DATE", label: "modal.dateFilter.preset.ytd", takesN: false },
  ];

  let tab = $state<Tab>("relative");
  let preset = $state<RelativePreset>("LAST_N_DAYS");
  let n = $state<number>(7);
  let fromDate = $state<string>("");
  let toDate = $state<string>("");
  let compare = $state<Compare>("NONE");

  $effect(() => {
    if (open) {
      tab = "relative";
      preset = "LAST_N_DAYS";
      n = 7;
      const today = new Date().toISOString().slice(0, 10);
      const weekAgo = new Date(Date.now() - 6 * 86_400_000).toISOString().slice(0, 10);
      fromDate = weekAgo;
      toDate = today;
      compare = "NONE";
    }
  });

  const takesN = $derived(PRESETS.find((p) => p.id === preset)?.takesN ?? false);

  const previewMdx = $derived.by(() => {
    if (tab === "relative") {
      return buildRelativeMdx({
        preset,
        n: takesN ? n : undefined,
        hierarchy: hierarchyName,
        level: levelName,
      });
    }
    if (!isAbsoluteValid({ from: fromDate, to: toDate })) return "";
    return buildAbsoluteMdx({
      from: fromDate,
      to: toDate,
      hierarchy: hierarchyName,
      level: levelName,
      compare,
    });
  });

  const applyEnabled = $derived.by(() => {
    if (tab === "relative") {
      return isRelativeValid({
        preset,
        n: takesN ? n : undefined,
        hierarchy: hierarchyName,
        level: levelName,
      });
    }
    return isAbsoluteValid({ from: fromDate, to: toDate });
  });

  function apply() {
    if (!applyEnabled || !previewMdx) return;
    onApply(previewMdx);
  }
</script>

<Modal title={`${i18n.t("modal.dateFilter.title")} — ${hierarchyCaption}`} {open} size="md" onClose={onCancel}>
  <div class="tabs" role="tablist">
    <button
      type="button"
      role="tab"
      class:active={tab === "relative"}
      onclick={() => (tab = "relative")}
    >{i18n.t("modal.dateFilter.tab.relative")}</button>
    <button
      type="button"
      role="tab"
      class:active={tab === "absolute"}
      onclick={() => (tab = "absolute")}
    >{i18n.t("modal.dateFilter.tab.absolute")}</button>
  </div>

  {#if tab === "relative"}
    <label class="field">
      <span class="field__label">{i18n.t("modal.dateFilter.preset")}</span>
      <select class="field__input" bind:value={preset}>
        {#each PRESETS as p}
          <option value={p.id}>{i18n.t(p.label)}</option>
        {/each}
      </select>
    </label>
    {#if takesN}
      <label class="field">
        <span class="field__label">{i18n.t("modal.dateFilter.n")}</span>
        <input class="field__input" type="number" min="1" bind:value={n} />
        {#if !applyEnabled}
          <span class="hint hint--err">{i18n.t("modal.dateFilter.nInvalid")}</span>
        {/if}
      </label>
    {/if}
  {:else}
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.from")}</span>
        <input class="field__input" type="date" bind:value={fromDate} />
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.to")}</span>
        <input class="field__input" type="date" bind:value={toDate} />
      </label>
    </div>
    <label class="field">
      <span class="field__label">{i18n.t("modal.dateFilter.compare")}</span>
      <select class="field__input" bind:value={compare}>
        <option value="NONE">{i18n.t("modal.dateFilter.compare.none")}</option>
        <option value="PRIOR_PERIOD">{i18n.t("modal.dateFilter.compare.priorPeriod")}</option>
        <option value="PRIOR_YEAR">{i18n.t("modal.dateFilter.compare.priorYear")}</option>
      </select>
    </label>
    {#if !applyEnabled}
      <p class="hint hint--err">{i18n.t("modal.dateFilter.rangeInvalid")}</p>
    {/if}
  {/if}

  <div class="preview">
    <span class="preview__label">{i18n.t("modal.dateFilter.preview")}</span>
    <pre class="preview__mdx">{previewMdx || "—"}</pre>
  </div>

  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!applyEnabled}
      onclick={apply}
    >{i18n.t("modal.apply")}</button>
  {/snippet}
</Modal>

<style>
  .tabs {
    display: flex;
    gap: var(--space-1);
    border-bottom: 1px solid var(--border);
    margin-bottom: var(--space-3);
  }
  .tabs button {
    background: transparent;
    border: 0;
    border-bottom: 2px solid transparent;
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
    font: inherit;
    color: var(--fg-muted);
  }
  .tabs button.active {
    color: var(--accent);
    border-bottom-color: var(--accent);
  }
  .row { display: flex; gap: var(--space-3); }
  .field--grow { flex: 1; }
  .hint { display: block; font-size: var(--fs-xs); margin-top: 4px; color: var(--fg-subtle); }
  .hint--err { color: var(--danger); }
  .preview {
    margin-top: var(--space-3);
    padding: var(--space-2);
    background: var(--bg-subtle);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .preview__label {
    display: block;
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    margin-bottom: 4px;
  }
  .preview__mdx {
    margin: 0;
    font-family: var(--font-mono, monospace);
    font-size: var(--fs-xs);
    white-space: pre-wrap;
    word-break: break-all;
    color: var(--fg);
  }
</style>
