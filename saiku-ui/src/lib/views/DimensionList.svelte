<script lang="ts">
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { query } from "$lib/stores/query.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { listLevelMembers, listRootMembers, type SaikuMember } from "$lib/api/discover";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import MeasuresModal from "$lib/modals/MeasuresModal.svelte";
  import CalculatedMemberModal, { type CalculatedMember } from "$lib/modals/CalculatedMemberModal.svelte";
  import SelectionsModal from "$lib/modals/SelectionsModal.svelte";
  import {
    Sigma,
    Folder,
    GitFork,
    ChevronDown,
    ChevronRight,
    Settings2,
    Plus,
    Minus,
    Filter,
  } from "lucide-svelte";
  import type { CubeMetadata } from "$lib/stores/datasources.svelte";
  import type { SaikuCube, SaikuDimension, SaikuHierarchy, SaikuLevel, SaikuMeasure } from "$lib/api/discover";
  import type { ThinMeasure } from "$lib/api/query";

  interface Props {
    username: string;
  }

  let { username }: Props = $props();

  function onLevelDragStart(
    e: DragEvent,
    dim: SaikuDimension,
    hier: SaikuHierarchy,
    lvl: SaikuLevel,
  ): void {
    const payload = {
      dimensionName: dim.name,
      dimensionUniqueName: dim.uniqueName,
      hierarchyName: hier.name,
      hierarchyUniqueName: hier.uniqueName,
      hierarchyCaption: hier.caption || hier.name,
      levelName: lvl.name,
      levelCaption: lvl.caption || lvl.name,
    };
    e.dataTransfer?.setData("application/x-saiku-level", JSON.stringify(payload));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
  }

  function onMeasureDragStart(e: DragEvent, m: SaikuMeasure): void {
    const thin: ThinMeasure = {
      name: m.name,
      uniqueName: m.uniqueName,
      caption: m.caption || m.name,
      type: m.calculated ? "CALCULATED" : "EXACT",
    };
    e.dataTransfer?.setData("application/x-saiku-measure", JSON.stringify(thin));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
  }

  /** Click-to-drop: add measure onto COLUMNS as a measure. Mirrors the
   *  drag-drop payload but skips the DnD machinery. Old UI behaviour. */
  function onMeasureClick(m: SaikuMeasure): void {
    query.addMeasure({
      name: m.name,
      uniqueName: m.uniqueName,
      caption: m.caption || m.name,
      type: m.calculated ? "CALCULATED" : "EXACT",
    });
  }

  /** Click-to-drop: drop a level onto ROWS by default. */
  function onLevelClick(dim: SaikuDimension, hier: SaikuHierarchy, lvl: SaikuLevel): void {
    query.includeLevel("ROWS", {
      dimensionName: dim.name,
      dimensionUniqueName: dim.uniqueName,
      hierarchyName: hier.name,
      hierarchyUniqueName: hier.uniqueName,
      hierarchyCaption: hier.caption || hier.name,
      levelName: lvl.name,
      levelCaption: lvl.caption || lvl.name,
    });
  }

  let metadata = $state<CubeMetadata | null>(null);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let expanded = $state<Record<string, boolean>>({});
  let cubeSignature = $state<string | null>(null);

  function keyFor(cube: SaikuCube): string {
    return `${cube.connection}/${cube.catalog}/${cube.schema}/${cube.name}`;
  }

  $effect(() => {
    const cube = selection.cube;
    if (!cube) {
      metadata = null;
      cubeSignature = null;
      return;
    }
    const sig = keyFor(cube);
    if (sig === cubeSignature && metadata) return;
    cubeSignature = sig;
    loading = true;
    error = null;
    datasources
      .metadata(username, cube)
      .then((m) => (metadata = m))
      .catch((err: unknown) => {
        error = err instanceof Error ? err.message : String(err);
      })
      .finally(() => (loading = false));
  });

  function toggle(id: string) {
    expanded[id] = !(expanded[id] ?? true);
  }

  function measureGroups(): Record<string, typeof metadata extends null ? never : NonNullable<typeof metadata>["measures"]> {
    const groups: Record<string, NonNullable<typeof metadata>["measures"]> = {};
    if (!metadata) return groups;
    for (const m of metadata.measures) {
      const key = "Measures";
      (groups[key] ??= []).push(m);
    }
    return groups;
  }

  // ---- Measures modal (bulk pick) + Calculated member modal ----
  let measuresOpen = $state(false);
  let calculatedOpen = $state(false);
  let calculatedInitial = $state<CalculatedMember | undefined>(undefined);

  function openMeasuresModal(): void {
    measuresOpen = true;
  }
  function onMeasuresSave(uniqueNames: string[]): void {
    const picks = new Set(uniqueNames);
    const next: ThinMeasure[] = [];
    for (const m of metadata?.measures ?? []) {
      if (!picks.has(m.uniqueName)) continue;
      next.push({
        name: m.name,
        uniqueName: m.uniqueName,
        caption: m.caption || m.name,
        type: m.calculated ? "CALCULATED" : "EXACT",
      });
    }
    query.setMeasures(next);
    measuresOpen = false;
  }

  function openCalculatedModal(existing?: CalculatedMember): void {
    calculatedInitial = existing;
    calculatedOpen = true;
  }
  function onCalculatedSave(m: CalculatedMember): void {
    const model = query.current?.queryModel;
    if (!model) return;
    const next = (model.calculatedMeasures ?? []).filter(
      (x) => (x as { name?: string }).name !== m.name,
    );
    next.push({
      name: m.name,
      formula: m.formula,
      properties: m.formatString
        ? { FORMAT_STRING: m.formatString, SOLVE_ORDER: "200" }
        : { SOLVE_ORDER: "200" },
    });
    model.calculatedMeasures = next;
    query.addMeasure({
      name: m.name,
      uniqueName: `[Measures].[${m.name}]`,
      caption: m.name,
      type: "CALCULATED",
    });
    calculatedOpen = false;
    toasts.success(i18n.t("toast.calcMeasure"), i18n.t("toast.calcMeasure.body").replace("{name}", m.name));
  }

  // ---- Dimensions quick-filter modal ----
  interface DimModalTarget {
    axis: "ROWS" | "COLUMNS";
    hierarchyName: string;
    hierarchyCaption: string;
    levelName: string;
    members: SaikuMember[];
    initialSelected: string[];
    initialType: "INCLUSION" | "EXCLUSION";
  }
  let dimModalOpen = $state(false);
  let dimModalLoading = $state(false);
  let dimModalTarget = $state<DimModalTarget | null>(null);

  async function openDimensionFilter(
    dim: SaikuDimension,
    hier: SaikuHierarchy,
    lvl: SaikuLevel,
  ): Promise<void> {
    if (!selection.cube || !session.current) return;
    dimModalLoading = true;
    dimModalOpen = true;
    const existing = query.getLevelSelection(hier.uniqueName, lvl.name);
    let members: SaikuMember[] = [];
    try {
      members = await listLevelMembers(
        session.current.username,
        selection.cube,
        dim.name,
        hier.uniqueName,
        lvl.name,
      );
    } catch {
      try {
        members = await listRootMembers(
          session.current.username,
          selection.cube,
          hier.uniqueName,
        );
      } catch (err) {
        toasts.danger(i18n.t("toast.loadMembersFailed"), err instanceof Error ? err.message : String(err));
      }
    }
    dimModalTarget = {
      axis: "ROWS",
      hierarchyName: hier.uniqueName,
      hierarchyCaption: hier.caption || hier.name,
      levelName: lvl.name,
      members,
      initialSelected: existing.memberUniqueNames,
      initialType: existing.type,
    };
    dimModalLoading = false;
  }

  function onDimSelectionsSave(uniqueNames: string[], type: "INCLUSION" | "EXCLUSION"): void {
    const t = dimModalTarget;
    if (!t) return;
    // Ensure the level is on ROWS so the selection applies.
    const model = query.current?.queryModel;
    if (model) {
      const already = model.axes.ROWS.hierarchies.some((h) => h.name === t.hierarchyName);
      if (!already) {
        query.includeLevel(t.axis, {
          dimensionName: t.hierarchyCaption,
          dimensionUniqueName: t.hierarchyName,
          hierarchyName: t.hierarchyName,
          hierarchyUniqueName: t.hierarchyName,
          hierarchyCaption: t.hierarchyCaption,
          levelName: t.levelName,
          levelCaption: t.levelName,
        });
      }
    }
    query.setLevelSelection(t.hierarchyName, t.levelName, uniqueNames, type);
    dimModalOpen = false;
  }

  function selectedMeasureUniqueNames(): string[] {
    const details = query.current?.queryModel?.details.measures ?? [];
    return details.map((m) => m.uniqueName);
  }

  function hierarchyUniqueNames(): string[] {
    return (metadata?.dimensions ?? []).flatMap((d) =>
      (d.hierarchies ?? []).map((h) => h.uniqueName),
    );
  }
</script>

<div class="panels">
  {#if !selection.cube}
    <p class="panels__hint">{i18n.t("panels.select")}</p>
  {:else if loading}
    <p class="panels__hint">{i18n.t("panels.loading")}</p>
  {:else if error}
    <p class="callout callout--danger">{error}</p>
  {:else if metadata}
    <section class="panel">
      <header class="panel__header panel__header--row">
        <span>{i18n.t("panels.measures")}</span>
        <span class="panel__actions">
          <button type="button" class="panel__action" title={i18n.t("panels.manageMeasures")} onclick={openMeasuresModal}>
            <Settings2 size={14} />
          </button>
          <button type="button" class="panel__action" title={i18n.t("panels.newCalcMeasure")} onclick={() => openCalculatedModal()}>
            <Plus size={14} />
          </button>
        </span>
      </header>
      <ul class="tree">
        {#each Object.entries(measureGroups()) as [group, items]}
          {@const gid = `m:${group}`}
          <li class="tree__node">
            <button type="button" class="tree__row tree__row--group" onclick={() => toggle(gid)}>
              <span class="tree__twisty">
                {#if expanded[gid] === false}<ChevronRight size={12} />{:else}<ChevronDown size={12} />{/if}
              </span>
              <span class="tree__label">{group}</span>
              <span class="tree__count">{items.length}</span>
            </button>
            {#if expanded[gid] !== false}
              <ul class="tree">
                {#each items as measure}
                  <li class="tree__node">
                    <button
                      type="button"
                      class="tree__row tree__row--measure"
                      draggable="true"
                      title={measure.caption}
                      ondragstart={(e) => onMeasureDragStart(e, measure)}
                      onclick={() => onMeasureClick(measure)}
                    >
                      <span class="tree__icon tree__icon--measure" aria-hidden="true"><Sigma size={13} /></span>
                      <span class="tree__label">{measure.caption || measure.name}</span>
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </li>
        {/each}
        {#if metadata.measures.length === 0}
          <li class="tree__empty">{i18n.t("panels.noMeasures")}</li>
        {/if}
      </ul>
    </section>

    <section class="panel">
      <header class="panel__header">{i18n.t("panels.dimensions")}</header>
      <ul class="tree">
        {#each metadata.dimensions.filter((d) => d.name !== "Measures") as dim}
          {@const did = `d:${dim.uniqueName}`}
          <li class="tree__node">
            <button type="button" class="tree__row tree__row--dim" onclick={() => toggle(did)} title={dim.caption}>
              <span class="tree__twisty">
                {#if expanded[did] === false}<ChevronRight size={12} />{:else}<ChevronDown size={12} />{/if}
              </span>
              <span class="tree__icon" aria-hidden="true"><Folder size={13} /></span>
              <span class="tree__label">{dim.caption || dim.name}</span>
            </button>
            {#if expanded[did] !== false}
              <ul class="tree">
                {#each dim.hierarchies ?? [] as hier}
                  {@const hid = `h:${dim.uniqueName}:${hier.name}`}
                  {@const singleHier = (dim.hierarchies ?? []).length === 1}
                  {#if singleHier}
                    {#each hier.levels ?? [] as lvl}
                      <li class="tree__node">
                        <span class="tree__row tree__row--level">
                          <button
                            type="button"
                            class="tree__drag"
                            draggable="true"
                            title={lvl.caption}
                            ondragstart={(e) => onLevelDragStart(e, dim, hier, lvl)}
                            onclick={() => onLevelClick(dim, hier, lvl)}
                          >
                            <span class="tree__icon tree__icon--level" aria-hidden="true"><Minus size={11} /></span>
                            <span class="tree__label">{lvl.caption || lvl.name}</span>
                          </button>
                          <button
                            type="button"
                            class="tree__gear"
                            title={i18n.t("panels.filterMembers")}
                            onclick={() => openDimensionFilter(dim, hier, lvl)}
                          ><Filter size={11} /></button>
                        </span>
                      </li>
                    {/each}
                  {:else}
                    <li class="tree__node">
                      <button type="button" class="tree__row tree__row--hier" onclick={() => toggle(hid)}>
                        <span class="tree__twisty">
                          {#if expanded[hid] === false}<ChevronRight size={12} />{:else}<ChevronDown size={12} />{/if}
                        </span>
                        <span class="tree__icon" aria-hidden="true"><GitFork size={13} /></span>
                        <span class="tree__label">{hier.caption || hier.name}</span>
                      </button>
                      {#if expanded[hid] !== false}
                        <ul class="tree">
                          {#each hier.levels ?? [] as lvl}
                            <li class="tree__node">
                              <span class="tree__row tree__row--level">
                                <button
                                  type="button"
                                  class="tree__drag"
                                  draggable="true"
                                  title={lvl.caption}
                                  ondragstart={(e) => onLevelDragStart(e, dim, hier, lvl)}
                                  onclick={() => onLevelClick(dim, hier, lvl)}
                                >
                                  <span class="tree__icon tree__icon--level" aria-hidden="true"><Minus size={11} /></span>
                                  <span class="tree__label">{lvl.caption || lvl.name}</span>
                                </button>
                                <button
                                  type="button"
                                  class="tree__gear"
                                  title={i18n.t("panels.filterMembers")}
                                  onclick={() => openDimensionFilter(dim, hier, lvl)}
                                ><Filter size={11} /></button>
                              </span>
                            </li>
                          {/each}
                        </ul>
                      {/if}
                    </li>
                  {/if}
                {/each}
              </ul>
            {/if}
          </li>
        {/each}
        {#if metadata.dimensions.length === 0}
          <li class="tree__empty">{i18n.t("panels.noDimensions")}</li>
        {/if}
      </ul>
    </section>
  {/if}
</div>

{#if metadata}
  <MeasuresModal
    available={metadata.measures}
    selectedUniqueNames={selectedMeasureUniqueNames()}
    open={measuresOpen}
    onSave={onMeasuresSave}
    onCancel={() => (measuresOpen = false)}
  />
  <CalculatedMemberModal
    initial={calculatedInitial}
    hierarchies={hierarchyUniqueNames()}
    measures={(metadata.measures ?? []).map((m) => ({ caption: m.caption || m.name, uniqueName: m.uniqueName }))}
    open={calculatedOpen}
    onSave={onCalculatedSave}
    onCancel={() => (calculatedOpen = false)}
  />
{/if}

{#if dimModalOpen && dimModalTarget}
  <SelectionsModal
    levelCaption={`${dimModalTarget.hierarchyCaption} › ${dimModalTarget.levelName}`}
    available={dimModalTarget.members}
    initialSelected={dimModalTarget.initialSelected}
    initialType={dimModalTarget.initialType}
    open={dimModalOpen}
    onSave={onDimSelectionsSave}
    onOpenDateFilter={() => (dimModalOpen = false)}
    onCancel={() => (dimModalOpen = false)}
  />
{/if}
{#if dimModalLoading && dimModalOpen}
  <p class="callout">{i18n.t("canvas.loadingMembers")}</p>
{/if}

<style>
  .panels {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    margin-top: var(--space-4);
  }
  .panels__hint {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
    margin: var(--space-3) 0 0;
  }
  .panel {
    background: var(--bg);
    border-top: 1px solid var(--border);
    padding-top: var(--space-3);
  }
  .panel__header {
    font-size: var(--fs-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: var(--space-2);
  }
  .panel__header--row {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .panel__actions { display: inline-flex; gap: 4px; }
  .panel__action {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: 1px solid transparent;
    color: var(--fg-muted);
    padding: 4px;
    border-radius: 4px;
    cursor: pointer;
  }
  .panel__action:hover { background: var(--bg-subtle); color: var(--fg); }
  .tree__drag {
    flex: 1;
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    background: transparent;
    border: 0;
    color: inherit;
    font: inherit;
    cursor: grab;
    text-align: left;
    padding: 0;
  }
  .tree__gear {
    opacity: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: 0;
    color: var(--fg-subtle);
    cursor: pointer;
    padding: 2px 6px;
    border-radius: 3px;
    transition: opacity 120ms ease;
  }
  .tree__row--level:hover .tree__gear { opacity: 1; }
  .tree__gear:hover { color: var(--fg); }
  .tree {
    list-style: none;
    margin: 0;
    padding-left: 0;
  }
  .tree .tree {
    padding-left: var(--space-4);
  }
  .tree__empty {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
    padding: var(--space-2);
  }
  .tree__row {
    display: flex;
    width: 100%;
    align-items: center;
    gap: var(--space-2);
    padding: 2px var(--space-1);
    background: transparent;
    border: 0;
    color: var(--fg);
    cursor: pointer;
    font: inherit;
    text-align: left;
    border-radius: var(--radius-sm);
  }
  .tree__row:hover { background: var(--bg-subtle); }
  .tree__row--group { font-weight: 600; }
  .tree__row--measure {
    color: #e06c75;
    cursor: grab;
  }
  .tree__row--measure .tree__icon--measure { color: #e06c75; }
  .tree__row--dim { color: var(--fg); }
  .tree__row--level { color: var(--fg-muted); }
  .tree__row--level .tree__drag { cursor: grab; }
  .tree__row--hier { color: #9aa8bc; }
  .tree__twisty {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    color: var(--fg-subtle);
  }
  .tree__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 16px;
    color: var(--fg-subtle);
  }
  .tree__icon--level { color: var(--fg-subtle); }
  .tree__label {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tree__count {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
  }
</style>
