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
  import { Badge } from "$lib/design-system";
  import { measuresHiddenToggle } from "$lib/stores/measuresHiddenToggle.svelte";
  import {
    Sigma,
    FunctionSquare,
    Folder,
    GitFork,
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
      // saiku#1019: schema-defined calc members (e.g. FoodMart Profit)
      // show as calculated:true in /discover, but Fat.convertDetails'
      // CALCULATED branch looks them up via query.getCalculatedMeasure()
      // — a map that only holds USER-added Saiku calc measures, so the
      // schema one returns null, gets added to details, and trips a
      // NullPointerException on the next m.getName() roundtrip. Schema
      // calc members live in cube.getMeasures() like any normal measure,
      // so the EXACT path (query.getMeasure → cube.getMeasures lookup)
      // resolves them correctly. The CALCULATED tag stays reserved for
      // the modal-created measures in QueryCanvas.svelte.
      type: "EXACT",
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
      // saiku#1019: schema-defined calc members (e.g. FoodMart Profit)
      // show as calculated:true in /discover, but Fat.convertDetails'
      // CALCULATED branch looks them up via query.getCalculatedMeasure()
      // — a map that only holds USER-added Saiku calc measures, so the
      // schema one returns null, gets added to details, and trips a
      // NullPointerException on the next m.getName() roundtrip. Schema
      // calc members live in cube.getMeasures() like any normal measure,
      // so the EXACT path (query.getMeasure → cube.getMeasures lookup)
      // resolves them correctly. The CALCULATED tag stays reserved for
      // the modal-created measures in QueryCanvas.svelte.
      type: "EXACT",
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
    // Include the admin "show hidden measures" toggle (#834) in the
    // signature so flipping it triggers a refetch instead of returning
    // the prior visible-only metadata that's cached against the cube
    // alone. Non-admins can't flip the toggle, so this only re-fires
    // for admin sessions.
    return `${cube.connection}/${cube.catalog}/${cube.schema}/${cube.name}#h=${measuresHiddenToggle.enabled ? "1" : "0"}`;
  }

  $effect(() => {
    const cube = selection.cube;
    if (!cube) {
      metadata = null;
      cubeSignature = null;
      return;
    }
    // `measuresHiddenToggle.enabled` is read inside keyFor() — Svelte
    // 5 picks it up as a $effect dep so toggling triggers a refetch.
    const includeHidden = session.isAdmin && measuresHiddenToggle.enabled;
    const sig = keyFor(cube);
    if (sig === cubeSignature && metadata) return;
    cubeSignature = sig;
    loading = true;
    error = null;
    datasources
      .metadata(username, cube, includeHidden)
      .then((m) => (metadata = m))
      .catch((err: unknown) => {
        const msg = err instanceof Error ? err.message : String(err);
        // Auth failures are surfaced by the global SessionExpiredBanner
        // (#944); echoing the raw "/rest/saiku/admin/discover/... -> 401"
        // here would both be redundant AND leak the internal admin REST
        // path. Same suppression pattern as datasources.silenceAuth.
        error = /->\s*40[13]\b/.test(msg) ? null : msg;
      })
      .finally(() => (loading = false));
  });

  function toggle(id: string) {
    expanded[id] = !(expanded[id] ?? true);
  }

  /** Fallback header used when the backend exposes no measure-group
   *  metadata (non-Mondrian providers) or when every measure of a cube
   *  has the same group — in both cases we render a single flat list
   *  under this label, matching the historical UX. */
  const DEFAULT_MEASURE_GROUP = "Measures";
  /** Bucket for `calculated: true` measures (Mondrian CalculatedMembers
   *  have no MeasureGroup) — separates derived KPIs from raw aggregates
   *  visually and signals to the user that these compose underlying
   *  measures rather than reading from a fact table. */
  const CALCULATED_GROUP = "Calculated";

  /** Compute the group label for a single measure.
   *  Precedence:
   *    1. Calculated members → CALCULATED_GROUP
   *    2. Mondrian-supplied measureGroup string → that string
   *    3. Anything else (null/empty/missing) → DEFAULT_MEASURE_GROUP
   */
  function groupLabel(m: SaikuMeasure): string {
    if (m.calculated) return CALCULATED_GROUP;
    const mg = m.measureGroup;
    if (typeof mg === "string" && mg.length > 0) return mg;
    return DEFAULT_MEASURE_GROUP;
  }

  /** Group measures by their resolved label, preserving the order
   *  measures arrive in (the backend already returns them in schema
   *  order). The Calculated bucket — when present — is always last so
   *  derived KPIs cluster at the bottom regardless of how many base
   *  MeasureGroups there are.
   *
   *  Returns an array of [label, measures] pairs (not an object) so
   *  iteration order is preserved across Svelte renders. */
  // Reactive bucket-by-MG of the loaded cube's measures. Recomputed
  // whenever metadata changes; `$derived` ensures the template can
  // reference the value directly without re-bucketing on every render.
  const measureGroupsDerived = $derived<Array<[string, SaikuMeasure[]]>>(measureGroups());

  function measureGroups(): Array<[string, SaikuMeasure[]]> {
    if (!metadata) return [];
    const groups = new Map<string, SaikuMeasure[]>();
    for (const m of metadata.measures) {
      const key = groupLabel(m);
      const bucket = groups.get(key);
      if (bucket) bucket.push(m);
      else groups.set(key, [m]);
    }
    // Push CALCULATED_GROUP to the end if it exists.
    const calc = groups.get(CALCULATED_GROUP);
    if (calc) {
      groups.delete(CALCULATED_GROUP);
      groups.set(CALCULATED_GROUP, calc);
    }
    return Array.from(groups.entries());
  }

  // ---- Dim-applicability (saiku#TODO virtual cube UX) ----

  /**
   * Set of MeasureGroup names the currently-selected measures span. Empty
   * when the user has no measures selected or every selected measure is
   * calculated (calc members have no MG of their own — their applicability
   * derives from the base measures they reference, which we don't introspect
   * statically; treating them as no-constraint is the safe default).
   *
   * Rebuilt reactively from the query's measure details + the cube metadata's
   * measure list so we can resolve a uniqueName → measureGroup without
   * re-walking the wire.
   */
  const requiredMeasureGroups = $derived.by<Set<string>>(() => {
    const out = new Set<string>();
    const selected = query.current?.queryModel?.details.measures ?? [];
    if (selected.length === 0 || !metadata) return out;
    const byUniqueName = new Map<string, SaikuMeasure>();
    for (const m of metadata.measures) byUniqueName.set(m.uniqueName, m);
    for (const sel of selected) {
      const m = byUniqueName.get(sel.uniqueName);
      if (!m) continue;
      if (m.calculated) continue;
      const mg = m.measureGroup;
      if (typeof mg === "string" && mg.length > 0) out.add(mg);
    }
    return out;
  });

  /**
   * True when this dimension is applicable to every currently-selected base
   * measure — i.e. it has a real (non-NoLink) join to every required MG.
   *
   *  - dim.measureGroups null → backend can't tell (non-Mondrian) →
   *    assume applicable; don't mute.
   *  - requiredMeasureGroups empty → no constraints yet → applicable.
   *  - otherwise → applicable iff dim.measureGroups ⊇ required.
   */
  function dimApplicable(dim: SaikuDimension): boolean {
    if (!dim.measureGroups) return true;
    if (requiredMeasureGroups.size === 0) return true;
    for (const mg of requiredMeasureGroups) {
      if (!dim.measureGroups.includes(mg)) return false;
    }
    return true;
  }

  /**
   * Human-readable list of the MGs the dim does NOT join, scoped to the
   * currently-required set. Drives the tooltip on muted dim rows so the
   * user sees WHY a dim is greyed out without having to inspect the schema.
   */
  function unjoinedMeasureGroups(dim: SaikuDimension): string[] {
    if (!dim.measureGroups || requiredMeasureGroups.size === 0) return [];
    const joined = new Set(dim.measureGroups);
    return Array.from(requiredMeasureGroups).filter((mg) => !joined.has(mg));
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
        // saiku#1019: schema-defined calc members (e.g. FoodMart Profit)
      // show as calculated:true in /discover, but Fat.convertDetails'
      // CALCULATED branch looks them up via query.getCalculatedMeasure()
      // — a map that only holds USER-added Saiku calc measures, so the
      // schema one returns null, gets added to details, and trips a
      // NullPointerException on the next m.getName() roundtrip. Schema
      // calc members live in cube.getMeasures() like any normal measure,
      // so the EXACT path (query.getMeasure → cube.getMeasures lookup)
      // resolves them correctly. The CALCULATED tag stays reserved for
      // the modal-created measures in QueryCanvas.svelte.
      type: "EXACT",
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
      <header class="panel__header flex items-center justify-between">
        <span>{i18n.t("panels.measures")}</span>
        <span class="inline-flex gap-1">
          <button type="button" class="panel__action" title={i18n.t("panels.manageMeasures")} aria-label={i18n.t("panels.manageMeasures")} onclick={openMeasuresModal}>
            <Settings2 size={14} />
          </button>
          <button type="button" class="panel__action" title={i18n.t("panels.newCalcMeasure")} aria-label={i18n.t("panels.newCalcMeasure")} onclick={() => openCalculatedModal()}>
            <Plus size={14} />
          </button>
        </span>
      </header>
      <ul class="tree">
        {#if measureGroupsDerived.length <= 1}
          {#each measureGroupsDerived[0]?.[1] ?? [] as measure}
            <li class="tree__node">
              <button
                type="button"
                class="tree__row tree__row--measure"
                draggable="true"
                title={measure.caption}
                ondragstart={(e) => onMeasureDragStart(e, measure)}
                onclick={() => onMeasureClick(measure)}
              >
                <span class="tree__icon tree__icon--measure" aria-hidden="true">
                  {#if measure.calculated}<FunctionSquare size={13} />{:else}<Sigma size={13} />{/if}
                </span>
                <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{measure.caption || measure.name}</span>
              </button>
            </li>
          {/each}
        {:else}
          {#each measureGroupsDerived as [group, items]}
            {@const gid = `m:${group}`}
            <li class="tree__node">
              <button type="button" class="tree__row font-semibold" onclick={() => toggle(gid)}>
                <span class="tree__twisty" class:tree__twisty--open={expanded[gid] !== false}>
                  <ChevronRight size={12} />
                </span>
                <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{group}</span>
                <span class="text-fg-subtle text-xs">{items.length}</span>
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
                        <span class="tree__icon tree__icon--measure" aria-hidden="true">
                          {#if measure.calculated}<FunctionSquare size={13} />{:else}<Sigma size={13} />{/if}
                        </span>
                        <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{measure.caption || measure.name}</span>
                      </button>
                    </li>
                  {/each}
                </ul>
              {/if}
            </li>
          {/each}
        {/if}
        {#if metadata.measures.length === 0}
          <li class="text-fg-subtle text-sm p-2">{i18n.t("panels.noMeasures")}</li>
        {/if}
      </ul>
    </section>

    <section class="panel">
      <header class="panel__header">{i18n.t("panels.dimensions")}</header>
      <ul class="tree">
        {#each metadata.dimensions.filter((d) => d.name !== "Measures") as dim}
          {@const did = `d:${dim.uniqueName}`}
          {@const applicable = dimApplicable(dim)}
          {@const unjoined = unjoinedMeasureGroups(dim)}
          <li class="tree__node" class:tree__node--muted={!applicable}>
            <button
              type="button"
              class="tree__row text-fg"
              onclick={() => toggle(did)}
              title={applicable
                ? (dim.caption ?? "")
                : `${dim.caption || dim.name} — does not join to the ${unjoined.join(" or ")} measure group${unjoined.length > 1 ? "s" : ""}. Selecting levels here will roll those measures up to All.`}
            >
              <span class="tree__twisty" class:tree__twisty--open={expanded[did] !== false}>
                <ChevronRight size={12} />
              </span>
              <span class="tree__icon" aria-hidden="true"><Folder size={13} /></span>
              <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{dim.caption || dim.name}</span>
              {#if !applicable}
                <Badge tone="warning" shape="pill" testid="dim-applicability-warn">⚠</Badge>
              {/if}
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
                            <span class="tree__icon text-fg-subtle" aria-hidden="true"><Minus size={11} /></span>
                            <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{lvl.caption || lvl.name}</span>
                          </button>
                          <button
                            type="button"
                            class="tree__gear"
                            title={i18n.t("panels.filterMembers")}
                            aria-label={i18n.t("panels.filterMembers")}
                            onclick={() => openDimensionFilter(dim, hier, lvl)}
                          ><Filter size={11} /></button>
                        </span>
                      </li>
                    {/each}
                  {:else}
                    <li class="tree__node">
                      <button type="button" class="tree__row text-fg-subtle" onclick={() => toggle(hid)}>
                        <span class="tree__twisty" class:tree__twisty--open={expanded[hid] !== false}>
                          <ChevronRight size={12} />
                        </span>
                        <span class="tree__icon" aria-hidden="true"><GitFork size={13} /></span>
                        <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{hier.caption || hier.name}</span>
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
                                  <span class="tree__icon text-fg-subtle" aria-hidden="true"><Minus size={11} /></span>
                                  <span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{lvl.caption || lvl.name}</span>
                                </button>
                                <button
                                  type="button"
                                  class="tree__gear"
                                  title={i18n.t("panels.filterMembers")}
                                  aria-label={i18n.t("panels.filterMembers")}
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
          <li class="text-fg-subtle text-sm p-2">{i18n.t("panels.noDimensions")}</li>
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
    refreshing={loading}
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
    font-weight: var(--weight-semibold);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: var(--space-2);
  }
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
    /* Tighter per-level indent + a vertical guide line, file-browser
       style. Helps deep cubes (Stores → Country → State → City → ...)
       stay readable without consuming the whole sidebar width. */
    padding-left: var(--space-3);
    margin-left: var(--space-2);
    border-left: 1px solid var(--border);
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
  .tree__row--measure {
    color: var(--accent);
    cursor: grab;
  }
  .tree__row--measure .tree__icon--measure { color: var(--accent); }
  .tree__row--level { color: var(--fg-muted); }
  .tree__row--level .tree__drag { cursor: grab; }
  .tree__twisty {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    color: var(--fg-subtle);
  }
  .tree__twisty :global(svg) {
    transition: transform var(--duration-fast) ease;
  }
  .tree__twisty--open :global(svg) {
    transform: rotate(90deg);
  }
  .tree__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 16px;
    color: var(--fg-subtle);
  }
  /* Dim-applicability hint for virtual cubes. When a measure from a fact
     table that doesn't join the dim is selected, the dim row (and its
     entire subtree) is rendered at reduced opacity. Click-through still
     works — this is signal, not blocking. The warning badge itself is
     a design-system Badge (tone="warning"), not bespoke CSS. */
  .tree__node--muted > .tree__row { opacity: 0.45; }
  .tree__node--muted > .tree { opacity: 0.45; }
</style>
