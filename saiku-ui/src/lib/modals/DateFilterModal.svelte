<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";
  import {
    buildRelativeMdx,
    buildAbsoluteMdx,
    buildCompareMdx,
    isRelativeValid,
    isAbsoluteValid,
    grainFromLevelType,
    grainAtLeast,
    PRESET_MIN_GRAIN,
    type RelativePreset,
    type TimeGrain,
  } from "./dateFilterMdx";
  import type { SaikuTimeCalc } from "$lib/api/discover";

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

  type Tab = "relative" | "absolute" | "compare";
  type Compare = "NONE" | "PRIOR_PERIOD" | "PRIOR_YEAR";
  type ShiftUnit = "year" | "quarter" | "month" | "week" | "day";

  /** Hierarchy level snapshot (from cubeMetadata) — narrowed to just the fields
   *  the modal needs to resolve grain + find the parallel-period anchor. */
  interface HierLevel {
    uniqueName: string;
    levelType?: string;
  }

  interface Props {
    open: boolean;
    hierarchyCaption: string;
    hierarchyName: string;
    levelName: string;
    /** Mondrian-native level type of the level being filtered (saiku#1221).
     *  Used to gate which Relative presets show up — "Last N days" doesn't
     *  appear on a TimeYears level. Optional for legacy schemas. */
    levelType?: string;
    /** Sibling levels in the same hierarchy, for resolving the parallel-period
     *  anchor in the Compare tab (e.g. find [Year] when the chip is [Month]).
     *  Optional — if omitted the Compare tab falls back to .Lag() shift. */
    hierarchyLevels?: HierLevel[];
    /** Declarative {@code <TimeCalc>} directives shipped on the cube (saiku
     *  #1221 Phase 3). When present and the user is in the Compare tab, a
     *  list of one-click "Apply Revenue YoY" buttons appears; clicking
     *  invokes {@link onAddCalcMeasure} so the host can splice the
     *  calculated measure into the active query. */
    timeCalcs?: SaikuTimeCalc[];
    /** Fired when the user clicks a TimeCalc button. Receives the calc's
     *  display name; the host is responsible for adding the matching
     *  {@code [Measures].[<name>]} reference to the query's COLUMNS axis. */
    onAddCalcMeasure?: (calcName: string) => void;
    onApply: (mdx: string) => void;
    onCancel: () => void;
  }

  let {
    open,
    hierarchyCaption,
    hierarchyName,
    levelName,
    levelType,
    hierarchyLevels,
    timeCalcs,
    onAddCalcMeasure,
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
  // Compare-tab state.
  let cmpFrom = $state<string>("");
  let cmpTo = $state<string>("");
  let cmpShiftUnit = $state<ShiftUnit>("year");
  let cmpShiftCount = $state<number>(1);

  $effect(() => {
    if (open) {
      tab = "relative";
      // Land on a preset the level's grain actually supports — landing
      // on LAST_N_DAYS on a TimeYears level shows a disabled state immediately.
      preset = (DEFAULT_PRESET_FOR_GRAIN[currentGrain ?? "day"] ?? "LAST_N_DAYS") as RelativePreset;
      n = 7;
      const today = new Date().toISOString().slice(0, 10);
      const weekAgo = new Date(Date.now() - 6 * 86_400_000).toISOString().slice(0, 10);
      fromDate = weekAgo;
      toDate = today;
      compare = "NONE";
      cmpFrom = weekAgo;
      cmpTo = today;
      cmpShiftUnit = "year";
      cmpShiftCount = 1;
    }
  });

  /** Mondrian-native grain for the chip's level; null when the schema
   *  hasn't marked it (legacy). */
  const currentGrain = $derived<TimeGrain | null>(grainFromLevelType(levelType));

  /** Presets whose minimum required grain the chip's level supports.
   *  E.g. on TimeYears we keep YEAR_TO_DATE / LAST_N_YEARS and drop
   *  LAST_N_DAYS. */
  const presetsAllowed = $derived(
    PRESETS.filter((p) => grainAtLeast(currentGrain, PRESET_MIN_GRAIN[p.id] ?? "day")),
  );

  /** Default preset to land on for a given grain — the coarsest preset
   *  that makes sense on a level of that grain. */
  const DEFAULT_PRESET_FOR_GRAIN: Record<TimeGrain, RelativePreset> = {
    year: "LAST_N_YEARS",
    halfYear: "LAST_N_QUARTERS",
    quarter: "LAST_N_QUARTERS",
    month: "LAST_N_MONTHS",
    week: "LAST_N_WEEKS",
    day: "LAST_N_DAYS",
    hour: "LAST_N_DAYS",
    minute: "LAST_N_DAYS",
    second: "LAST_N_DAYS",
  };

  /** Best anchor level for ParallelPeriod in Compare mode — the deepest
   *  level on this hierarchy whose grain matches the shift unit. For "year"
   *  we want the [Year] level; "month" wants [Month]. Null when none of the
   *  sibling levels carries the right levelType.
   *
   *  Returning null forces buildCompareMdx onto the .Lag fallback — still
   *  correct on day-grain hierarchies, less correct on coarser ones, but
   *  always emits SOMETHING the user can preview. */
  const parallelLevel = $derived.by(() => {
    if (!hierarchyLevels) return null;
    const targetGrain = cmpShiftUnit;
    const match = hierarchyLevels.find(
      (l) => grainFromLevelType(l.levelType) === targetGrain,
    );
    return match?.uniqueName ?? null;
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
    if (tab === "compare") {
      if (!isAbsoluteValid({ from: cmpFrom, to: cmpTo })) return "";
      return buildCompareMdx({
        hierarchy: hierarchyName,
        level: levelName,
        from: cmpFrom,
        to: cmpTo,
        shiftUnit: cmpShiftUnit,
        shiftCount: cmpShiftCount,
        parallelLevel,
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
    if (tab === "compare") {
      return (
        isAbsoluteValid({ from: cmpFrom, to: cmpTo }) &&
        Number.isFinite(cmpShiftCount) &&
        cmpShiftCount >= 1
      );
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
    <button
      type="button"
      role="tab"
      class:active={tab === "compare"}
      onclick={() => (tab = "compare")}
    >{i18n.t("modal.dateFilter.tab.compare")}</button>
  </div>

  {#if tab === "relative"}
    <label class="field">
      <span class="field__label">{i18n.t("modal.dateFilter.preset")}</span>
      <select class="field__input" bind:value={preset}>
        {#each presetsAllowed as p}
          <option value={p.id}>{i18n.t(p.label)}</option>
        {/each}
      </select>
      {#if currentGrain && (presetsAllowed.length !== PRESETS.length)}
        <span class="hint">{i18n.t("modal.dateFilter.grainHint").replace("{grain}", currentGrain)}</span>
      {/if}
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
  {:else if tab === "absolute"}
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
    <!-- saiku#1221 Phase 4: legacy compare dropdown retired. The Compare
         tab supersedes PRIOR_PERIOD / PRIOR_YEAR with grain-aware
         ParallelPeriod via buildCompareMdx; buildAbsoluteMdx still honours
         the older `compare` field for saved queries that round-trip
         through. Always-NONE here keeps the local state simple. -->
    <p class="hint">{i18n.t("modal.dateFilter.compareMoved")}</p>
    {#if !applyEnabled}
      <p class="hint hint--err">{i18n.t("modal.dateFilter.rangeInvalid")}</p>
    {/if}
  {:else if tab === "compare"}
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.from")}</span>
        <input class="field__input" type="date" bind:value={cmpFrom} />
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.to")}</span>
        <input class="field__input" type="date" bind:value={cmpTo} />
      </label>
    </div>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.compare.shiftUnit")}</span>
        <select class="field__input" bind:value={cmpShiftUnit}>
          <option value="year">{i18n.t("modal.dateFilter.compare.unit.year")}</option>
          <option value="quarter">{i18n.t("modal.dateFilter.compare.unit.quarter")}</option>
          <option value="month">{i18n.t("modal.dateFilter.compare.unit.month")}</option>
          <option value="week">{i18n.t("modal.dateFilter.compare.unit.week")}</option>
          <option value="day">{i18n.t("modal.dateFilter.compare.unit.day")}</option>
        </select>
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.compare.shiftCount")}</span>
        <input class="field__input" type="number" min="1" bind:value={cmpShiftCount} />
      </label>
    </div>
    <p class="hint">
      {#if parallelLevel}
        {i18n.t("modal.dateFilter.compare.parallelLevelOk").replace("{level}", parallelLevel)}
      {:else}
        {i18n.t("modal.dateFilter.compare.parallelLevelFallback")}
      {/if}
    </p>
    {#if !applyEnabled}
      <p class="hint hint--err">{i18n.t("modal.dateFilter.rangeInvalid")}</p>
    {/if}
    {#if timeCalcs && timeCalcs.length > 0 && onAddCalcMeasure}
      <div class="timecalcs">
        <div class="timecalcs__label">{i18n.t("modal.dateFilter.compare.timeCalcs")}</div>
        <p class="hint">{i18n.t("modal.dateFilter.compare.timeCalcsHint")}</p>
        <div class="timecalcs__buttons">
          {#each timeCalcs as tc}
            <Button
              variant="outline"
              size="sm"
              onclick={() => onAddCalcMeasure?.(tc.name)}
              title={`${tc.type.toUpperCase()} · ${tc.measure}${tc.window ? ` · window ${tc.window}` : ""}`}
            >
              {tc.name}
            </Button>
          {/each}
        </div>
      </div>
    {/if}
  {/if}

  <div class="preview">
    <span class="preview__label">{i18n.t("modal.dateFilter.preview")}</span>
    <pre class="preview__mdx">{previewMdx || "—"}</pre>
  </div>

  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={apply} disabled={!applyEnabled}>{i18n.t("modal.apply")}</Button>
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
  .timecalcs {
    margin-top: var(--space-3);
    padding-top: var(--space-2);
    border-top: 1px solid var(--border);
  }
  .timecalcs__label {
    font-size: var(--fs-xs);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: 4px;
  }
  .timecalcs__buttons {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }
</style>
