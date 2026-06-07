<script lang="ts">
  /*
   * Per-tile edit modal. Opens when the user clicks ⚙ on a tile in the
   * grid. Shows fields appropriate to the tile's type:
   *
   *   all      → title
   *   chart    → cube picker + chartType + inline AiQueryRequest body
   *              (JSON textarea — QueryCanvas embed is a later slice)
   *   table    → cube picker + inline AiQueryRequest body
   *   filter   → cube picker + dim/hier/level + widget kind
   *   text     → markdown content
   *
   * Save writes through dashboardStore.updateTile(id, patch), which
   * marks the dashboard dirty so the toolbar's Save button activates.
   * Cancel discards in-modal edits.
   */

  import { onMount, untrack } from "svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    listAiCubes,
    type AiCubeSummary,
    type CubeRef,
    type DashboardFilter,
    type DashboardTile,
    type FilterWidget,
    type KpiConfig,
    type ImageSource,
    type ImageFit,
    uploadImageAsset,
    type TileQuery,
    // Issue #919 — conditional formatting on table tiles.
    type ConditionalFormatRule,
    type ConditionalFormatType,
    type ConditionalThresholdMode,
  } from "$lib/api/dashboards";
  import { flatten, listRepository, type RepositoryNode } from "$lib/api/repository";
  import { repositionTile } from "$lib/dashboard/tilePlacement";
  import { CHART_TYPES } from "$lib/views/chartTypes";

  interface Props {
    tile: DashboardTile;
    onClose: () => void;
  }

  let { tile, onClose }: Props = $props();

  // Per-tile editable form state — initialised from the source tile.
  // untrack() so the inits read the prop without subscribing; the modal
  // never re-renders for the same tile, so the one-shot read is the
  // intended behaviour (Svelte 5 warns by default).
  let title = $state(untrack(() => tile.title ?? ""));
  let cube = $state<CubeRef | null>(untrack(() => tile.cube ?? null));
  let chartType = $state(untrack(() => tile.chartType ?? "bar"));
  let text = $state(untrack(() => tile.text ?? ""));
  // Position + size — numeric controls per the design's "no drag-resize"
  // call. Users rearrange via these fields; the grid auto-places tiles
  // on add and re-renders to the new (x, y, w, h) on save.
  let tileX = $state<number>(untrack(() => tile.x));
  let tileY = $state<number>(untrack(() => tile.y));
  let tileW = $state<number>(untrack(() => tile.w));
  let tileH = $state<number>(untrack(() => tile.h));
  let widget = $state<FilterWidget>(untrack(() => tile.widget ?? "single-select"));
  // --- issue #922: cascading-select config (start level + depth). Held in
  // a self-contained working copy so the rebase against #919 stays clean;
  // only consulted when widget === "cascading-select". ---
  let cascadeStartLevel = $state<string>(untrack(() => tile.cascading?.startLevel ?? ""));
  let cascadeDepth = $state<number>(untrack(() => tile.cascading?.depth ?? 3));
  // --- end issue #922 ---
  let filterTarget = $state<{ dimension: string; hierarchy: string; level: string }>(
    untrack(() => ({
      dimension: tile.target?.dimension ?? "",
      hierarchy: tile.target?.hierarchy ?? "",
      level: tile.target?.level ?? "",
    })),
  );
  // KPI tile config — single working-copy object; nested fields bind
  // directly via Svelte 5's $state proxy. Defaults fill in unset fields
  // so the form has stable controls even for a fresh KPI tile.
  let kpiConfig = $state<KpiConfig>(
    untrack(() => ({
      measure: tile.kpi?.measure ?? "",
      measureCaption: tile.kpi?.measureCaption ?? "",
      format: tile.kpi?.format ?? "number",
      customFormat: tile.kpi?.customFormat ?? "",
      comparison: tile.kpi?.comparison ?? "none",
      target: tile.kpi?.target,
      timeLevel: tile.kpi?.timeLevel ?? { dimension: "", hierarchy: "", level: "" },
      sparkline: tile.kpi?.sparkline ?? false,
      thresholds: tile.kpi?.thresholds ?? {},
      direction: tile.kpi?.direction ?? "higher-is-better",
    })),
  );
  // ── Issue #918: image tile config (image only) ──
  // mode "url" → imageUrl is an external http(s) URL; mode "upload" → a file
  // is uploaded on save and its returned download path becomes the src
  // (imageExistingSrc preserves the already-stored path when no new file is
  // picked). Held as a self-contained working copy, persisted in handleSave.
  let imageMode = $state<ImageSource>(untrack(() => tile.image?.source ?? "url"));
  let imageUrl = $state<string>(
    untrack(() => ((tile.image?.source ?? "url") === "url" ? (tile.image?.src ?? "") : "")),
  );
  let imageExistingSrc = $state<string>(untrack(() => tile.image?.src ?? ""));
  let imageFit = $state<ImageFit>(untrack(() => tile.image?.fit ?? "contain"));
  let imageCaption = $state<string>(untrack(() => tile.image?.caption ?? ""));
  let imageAlt = $state<string>(untrack(() => tile.image?.alt ?? ""));
  let imageFile = $state<File | null>(null);
  let imageUploading = $state(false);
  // ── end issue #918 ──
  let inlineBodyJson = $state<string>(
    untrack(() => (tile.query?.kind === "inline" ? JSON.stringify(tile.query.body, null, 2) : "")),
  );
  let bodyError = $state<string | null>(null);
  // ── Issue #919: per-column conditional-formatting rules (table only) ──
  // Self-contained working copy, deep-cloned from the source so edits are
  // discardable on Cancel. Persisted in handleSave under a table-guarded
  // block. Isolated here so the parallel #922 edit rebases cleanly.
  let conditionalFormat = $state<ConditionalFormatRule[]>(
    untrack(() =>
      (tile.conditionalFormat ?? []).map((r) => ({
        ...r,
        colors: r.colors ? { ...r.colors } : undefined,
      })),
    ),
  );
  // Query source — "reference" picks a saved .saiku from the repo,
  // "inline" pastes an AiQueryRequest body. Default to "reference" so
  // non-technical authors aren't dropped into a JSON textarea on a
  // fresh tile.
  let queryMode = $state<"reference" | "inline">(
    untrack(() => (tile.query?.kind === "inline" ? "inline" : "reference")),
  );
  let referencePath = $state<string>(untrack(() => (tile.query?.kind === "reference" ? tile.query.path : "")));

  // Cube catalogue + saved-query catalogue, fetched once on open.
  let cubes = $state<AiCubeSummary[]>([]);
  let cubesError = $state<string | null>(null);
  let cubesLoading = $state(false);
  let savedQueries = $state<RepositoryNode[]>([]);
  let savedQueriesError = $state<string | null>(null);
  let savedQueriesLoading = $state(false);

  onMount(async () => {
    if (tile.type === "text") return; // text tiles don't need the catalogue
    cubesLoading = true;
    const needSavedQueries = tile.type === "chart" || tile.type === "table";
    if (needSavedQueries) savedQueriesLoading = true;
    try {
      const tasks: Array<Promise<void>> = [
        listAiCubes()
          .then((c) => {
            cubes = c;
          })
          .catch((e: unknown) => {
            cubesError = e instanceof Error ? e.message : String(e);
          }),
      ];
      if (needSavedQueries) {
        tasks.push(
          listRepository(["saiku"])
            .then((tree) => {
              savedQueries = flatten(tree).filter((n) => n.type === "FILE");
            })
            .catch((e: unknown) => {
              savedQueriesError = e instanceof Error ? e.message : String(e);
            }),
        );
      }
      await Promise.all(tasks);
    } finally {
      cubesLoading = false;
      savedQueriesLoading = false;
    }
  });

  // Filter and KPI tiles need the cube's schema — filters for the
  // dim/hier/level dropdowns, KPI for the measure picker and (when
  // prior-period or sparkline are on) the time-level picker. Prime
  // whenever a cube is selected so the dropdowns aren't empty.
  $effect(() => {
    if (tile.type !== "filter" && tile.type !== "kpi") return;
    if (!cube) return;
    void schemaCache.get(cube).catch(() => {
      // Surface failure inline below
    });
  });

  let schema = $derived(() => {
    if (!cube) return null;
    void schemaCache.version;
    return schemaCache.peek(cube);
  });

  // Build dim/hier/level option lists from the schema. Loose record
  // walks because AiSchemaLike is intentionally untyped at the store
  // level — narrowing happens here at the read site.
  let dimensionOptions = $derived(() => {
    const s = schema() as { dimensions?: Record<string, { name: string }> } | null;
    if (!s?.dimensions) return [] as string[];
    return Object.values(s.dimensions).map((d) => d.name);
  });

  let hierarchyOptions = $derived(() => {
    const s = schema() as
      | { dimensions?: Record<string, { name: string; hierarchies?: Record<string, { name: string }> }> }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name === filterTarget.dimension && d.hierarchies) {
        return Object.values(d.hierarchies).map((h) => h.name);
      }
    }
    return [];
  });

  // KPI tile derivers — keyed on kpiConfig.timeLevel.* so they're
  // independent of the filter-tile dropdowns above. Same loose-schema
  // narrowing as the filter ones.
  let measureOptions = $derived(() => {
    const s = schema() as
      | { measures?: Record<string, { name?: string; displayName?: string | null; visible?: boolean }> }
      | null;
    if (!s?.measures) return [] as { name: string; label: string }[];
    const out: { name: string; label: string }[] = [];
    for (const m of Object.values(s.measures)) {
      if (m?.visible === false) continue;
      const name = m?.name;
      if (!name) continue;
      out.push({ name, label: m?.displayName ?? name });
    }
    return out;
  });

  let kpiHierarchyOptions = $derived(() => {
    const s = schema() as
      | { dimensions?: Record<string, { name: string; hierarchies?: Record<string, { name: string }> }> }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name === kpiConfig.timeLevel?.dimension && d.hierarchies) {
        return Object.values(d.hierarchies).map((h) => h.name);
      }
    }
    return [];
  });

  let kpiLevelOptions = $derived(() => {
    const s = schema() as
      | {
          dimensions?: Record<
            string,
            {
              name: string;
              hierarchies?: Record<string, { name: string; levels?: Record<string, { name: string }> }>;
            }
          >;
        }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name !== kpiConfig.timeLevel?.dimension) continue;
      if (!d.hierarchies) return [];
      for (const h of Object.values(d.hierarchies)) {
        if (h.name === kpiConfig.timeLevel?.hierarchy && h.levels) {
          return Object.values(h.levels).map((l) => l.name);
        }
      }
    }
    return [];
  });

  let levelOptions = $derived(() => {
    const s = schema() as
      | {
          dimensions?: Record<
            string,
            {
              name: string;
              hierarchies?: Record<string, { name: string; levels?: Record<string, { name: string }> }>;
            }
          >;
        }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name !== filterTarget.dimension) continue;
      if (!d.hierarchies) return [];
      for (const h of Object.values(d.hierarchies)) {
        if (h.name === filterTarget.hierarchy && h.levels) {
          return Object.values(h.levels).map((l) => l.name);
        }
      }
    }
    return [];
  });

  function cubeKey(c: CubeRef): string {
    return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
  }

  function handleCubeChange(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    const picked = cubes.find((c) => cubeKey(c) === v);
    // Project the AiCubeSummary down to the four CubeRef fields the
    // server's AiCubeRef accepts. Extra summary fields (cubeCaption,
    // defaultMeasure, measureCount) get serialised onto the dashboard
    // JSON otherwise, and Jackson rejects them with
    // "Unknown field 'cubeCaption' on AiCubeRef" on save.
    cube = picked
      ? {
          connectionName: picked.connectionName,
          catalog: picked.catalog,
          schema: picked.schema,
          cubeName: picked.cubeName,
        }
      : null;
    // Clear stale filter target when the cube changes — the level
    // names won't apply to the new cube's schema.
    if (tile.type === "filter") {
      filterTarget = { dimension: "", hierarchy: "", level: "" };
    }
    // Same reasoning for KPI: measure + time-level names are cube-scoped.
    if (tile.type === "kpi") {
      kpiConfig.measure = "";
      kpiConfig.measureCaption = "";
      kpiConfig.timeLevel = { dimension: "", hierarchy: "", level: "" };
    }
  }

  let positionError = $state<string | null>(null);

  // ── Issue #919: rule-editor helpers (table only) ──
  /** Append a fresh rule with sensible defaults. */
  function addConditionalRule(): void {
    conditionalFormat = [
      ...conditionalFormat,
      {
        column: "",
        type: "background",
        thresholdMode: "relative",
        lowThreshold: 25,
        highThreshold: 75,
      },
    ];
  }
  function removeConditionalRule(index: number): void {
    conditionalFormat = conditionalFormat.filter((_, i) => i !== index);
  }
  /** A rule needs explicit thresholds for background; font/icon may run in
   *  sign mode (no thresholds). bar never uses thresholds. */
  function ruleUsesThresholds(r: ConditionalFormatRule): boolean {
    return r.type === "background" || r.type === "font" || r.type === "icon";
  }
  const CONDITIONAL_TYPES: { id: ConditionalFormatType; label: string }[] = [
    { id: "background", label: "Background colour" },
    { id: "bar", label: "Data bar" },
    { id: "font", label: "Font colour" },
    { id: "icon", label: "Icon (↑ ↓ →)" },
  ];
  const CONDITIONAL_MODES: { id: ConditionalThresholdMode; label: string }[] = [
    { id: "relative", label: "Relative (percentile)" },
    { id: "absolute", label: "Absolute (fixed value)" },
  ];

  async function handleSave(): Promise<void> {
    bodyError = null;
    positionError = null;

    // Resolve the new position via the layout-aware helper: validates
    // x+w <= cols, refuses sub-1 sizes, and cascade-pushes siblings if
    // the new rectangle overlaps them. Returns either a full new tiles
    // array or an inline error to surface in the modal.
    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const reposition = repositionTile(layout, tile.id, {
      x: Math.floor(tileX),
      y: Math.floor(tileY),
      w: Math.floor(tileW),
      h: Math.floor(tileH),
    });
    if (!reposition.ok) {
      positionError = reposition.error ?? "Invalid position or size.";
      return;
    }

    // Build the non-position patch (title, content, cube, query, etc.)
    // and apply it on top of the repositioned source tile, then commit
    // the whole tiles array in one go via replaceTiles so dirty bumps
    // once for the entire change.
    const patch: Partial<DashboardTile> = {
      title: title || undefined,
    };

    if (tile.type === "text") {
      patch.text = text;
    } else if (cube) {
      patch.cube = cube;
    }

    if (tile.type === "chart") {
      patch.chartType = chartType;
    }

    if (tile.type === "chart" || tile.type === "table") {
      if (queryMode === "reference") {
        if (referencePath) {
          const q: TileQuery = { kind: "reference", path: referencePath };
          patch.query = q;
        }
      } else if (inlineBodyJson.trim()) {
        try {
          const parsed = JSON.parse(inlineBodyJson);
          const q: TileQuery = { kind: "inline", body: parsed };
          patch.query = q;
        } catch (e) {
          bodyError = `JSON parse error: ${(e as Error).message}`;
          return;
        }
      }
    }

    if (tile.type === "filter") {
      patch.widget = widget;
      if (filterTarget.dimension && filterTarget.hierarchy && filterTarget.level) {
        const target: DashboardFilter = {
          dimension: filterTarget.dimension,
          hierarchy: filterTarget.hierarchy,
          level: filterTarget.level,
          members: [],
        };
        patch.target = target;
      }
      // issue #922: persist cascade config only for the cascading variant;
      // clear it otherwise so the saved JSON stays tidy across widget swaps.
      patch.cascading =
        widget === "cascading-select"
          ? {
              startLevel: cascadeStartLevel || undefined,
              depth: cascadeDepth,
            }
          : undefined;
    }

    if (tile.type === "kpi") {
      // Drop the timeLevel placeholder when none of the time-aware
      // features need it — keeps the saved JSON tidy.
      const needsTime =
        kpiConfig.comparison === "prior-period" ||
        kpiConfig.comparison === "year-over-year" ||
        kpiConfig.sparkline === true;
      const tl = kpiConfig.timeLevel;
      const cleaned: KpiConfig = {
        ...kpiConfig,
        timeLevel:
          needsTime && tl && tl.dimension && tl.hierarchy && tl.level ? tl : undefined,
        // Drop empty thresholds object so the JSON diff is empty when
        // the analyst doesn't touch the threshold inputs.
        thresholds:
          kpiConfig.thresholds &&
          (kpiConfig.thresholds.red != null ||
            kpiConfig.thresholds.yellow != null ||
            kpiConfig.thresholds.green != null)
            ? kpiConfig.thresholds
            : undefined,
        target: kpiConfig.comparison === "target" ? kpiConfig.target : undefined,
        customFormat: kpiConfig.format === "custom" ? kpiConfig.customFormat : undefined,
      };
      patch.kpi = cleaned;
    }

    // ── Issue #919: persist conditional-formatting rules (table only) ──
    // Drop incomplete rules (no column) and normalise the threshold
    // fields away for sign-mode font/icon and for bar. Persist undefined
    // when no usable rule remains so the saved JSON stays tidy.
    if (tile.type === "table") {
      const cleaned = conditionalFormat
        .filter((r) => r.column.trim() !== "")
        .map((r) => {
          const out: ConditionalFormatRule = {
            column: r.column.trim(),
            type: r.type,
            thresholdMode: r.thresholdMode,
          };
          if (ruleUsesThresholds(r)) {
            if (r.lowThreshold != null) out.lowThreshold = r.lowThreshold;
            if (r.highThreshold != null) out.highThreshold = r.highThreshold;
          }
          if (r.colors && (r.colors.low || r.colors.mid || r.colors.high)) {
            out.colors = { ...r.colors };
          }
          if (r.type === "bar" && r.barColor) out.barColor = r.barColor;
          return out;
        });
      patch.conditionalFormat = cleaned.length > 0 ? cleaned : undefined;
    }

    // ── Issue #918: image tile. URL mode persists the typed URL; upload mode
    // POSTs the picked file to the (auth + content-type + size + path-traversal
    // hardened) endpoint on save and persists the returned download path,
    // keeping the existing path when no new file was picked. Upload failures
    // surface in the modal and abort the save. ──
    if (tile.type === "image") {
      let src = imageExistingSrc;
      if (imageMode === "url") {
        src = imageUrl.trim();
      } else if (imageFile) {
        imageUploading = true;
        try {
          src = await uploadImageAsset(tile.id, imageFile);
        } catch (e) {
          bodyError = `Image upload failed: ${(e as Error).message}`;
          imageUploading = false;
          return;
        }
        imageUploading = false;
      }
      patch.image = {
        source: imageMode,
        src: src || undefined,
        fit: imageFit,
        caption: imageCaption.trim() || undefined,
        alt: imageAlt.trim() || undefined,
      };
    }

    // Merge the patch into the repositioned source tile, then commit
    // the whole cascade in one go.
    const tiles = reposition.tiles!.map((t) => (t.id === tile.id ? { ...t, ...patch } : t));
    dashboardStore.replaceTiles(tiles);
    onClose();
  }
</script>

<div
  class="modal-backdrop"
  role="presentation"
  onclick={(e) => {
    if (e.target === e.currentTarget) onClose();
  }}
  onkeydown={(e) => {
    if (e.key === "Escape") onClose();
  }}
>
  <div class="modal" role="dialog" aria-label="Edit tile">
    <header class="modal-header">
      <h2>Edit {tile.type} tile</h2>
      <button type="button" class="close" aria-label="Close" onclick={onClose}>×</button>
    </header>
    <div class="modal-body">
      <label class="field">
        <span>Title</span>
        <input type="text" bind:value={title} placeholder={`Untitled ${tile.type}`} />
      </label>

      <fieldset class="size">
        <legend>Position &amp; size (12-col grid)</legend>
        <label class="field inline">
          <span>x</span>
          <input type="number" min="0" max="11" bind:value={tileX} />
        </label>
        <label class="field inline">
          <span>y</span>
          <input type="number" min="0" bind:value={tileY} />
        </label>
        <label class="field inline">
          <span>w</span>
          <input type="number" min="1" max="12" bind:value={tileW} />
        </label>
        <label class="field inline">
          <span>h</span>
          <input type="number" min="1" bind:value={tileH} />
        </label>
      </fieldset>
      {#if positionError}
        <div class="position-error" role="alert">{positionError}</div>
      {/if}

      {#if tile.type === "text"}
        <label class="field">
          <span>Markdown / HTML (sanitised on render)</span>
          <textarea bind:value={text} rows="10"></textarea>
        </label>
      {/if}

      {#if tile.type === "image"}
        <fieldset class="mode">
          <legend>Image source</legend>
          <label class="radio">
            <input type="radio" bind:group={imageMode} value="url" />
            <span>URL</span>
          </label>
          <label class="radio">
            <input type="radio" bind:group={imageMode} value="upload" />
            <span>Upload file</span>
          </label>
        </fieldset>

        {#if imageMode === "url"}
          <label class="field">
            <span>Image URL (http/https)</span>
            <input
              type="url"
              bind:value={imageUrl}
              placeholder="https://example.com/logo.png"
            />
          </label>
        {:else}
          <label class="field">
            <span>Upload image{imageExistingSrc ? " (replaces current)" : ""}</span>
            <input
              type="file"
              accept="image/png,image/jpeg,image/gif,image/webp"
              onchange={(e) => {
                imageFile = (e.currentTarget as HTMLInputElement).files?.[0] ?? null;
                bodyError = null;
              }}
            />
            <span class="hint">
              PNG, JPEG, GIF or WebP, up to 2&nbsp;MB. Stored privately in your
              repository.{imageExistingSrc && !imageFile ? " An image is already set." : ""}
            </span>
          </label>
        {/if}

        <label class="field">
          <span>Fit</span>
          <select bind:value={imageFit}>
            <option value="contain">Contain (whole image, letterboxed)</option>
            <option value="cover">Cover (fill tile, crop edges)</option>
            <option value="fill">Fill (stretch to tile)</option>
            <option value="scale-down">Scale down (never upscale)</option>
          </select>
        </label>

        <label class="field">
          <span>Caption (optional)</span>
          <input type="text" bind:value={imageCaption} placeholder="e.g. Q4 campaign" />
        </label>

        <label class="field">
          <span>Alt text (accessibility)</span>
          <input type="text" bind:value={imageAlt} placeholder="Describe the image" />
        </label>
      {/if}

      {#if tile.type !== "text" && tile.type !== "image"}
        <label class="field">
          <span>Cube</span>
          {#if cubesLoading}
            <span class="hint">Loading…</span>
          {:else if cubesError}
            <span class="hint error">{cubesError}</span>
          {:else}
            <select onchange={handleCubeChange} disabled={cubes.length === 0}>
              <option value="">— pick a cube —</option>
              {#each cubes as c (cubeKey(c))}
                <option value={cubeKey(c)} selected={cube ? cubeKey(c) === cubeKey(cube) : false}>
                  {c.cubeCaption ?? c.cubeName} ({c.connectionName})
                </option>
              {/each}
            </select>
          {/if}
        </label>
      {/if}

      {#if tile.type === "chart"}
        <label class="field">
          <span>Chart type</span>
          <select bind:value={chartType}>
            {#each CHART_TYPES as ct (ct.id)}
              <option value={ct.id}>{ct.label} ({ct.group})</option>
            {/each}
          </select>
        </label>
      {/if}

      {#if tile.type === "chart" || tile.type === "table"}
        <fieldset class="mode">
          <legend>Query source</legend>
          <label class="radio">
            <input type="radio" bind:group={queryMode} value="reference" />
            <span>Saved query</span>
          </label>
          <label class="radio">
            <input type="radio" bind:group={queryMode} value="inline" />
            <span>Inline JSON</span>
          </label>
        </fieldset>

        {#if queryMode === "reference"}
          <label class="field">
            <span>Saved query (.saiku file)</span>
            {#if savedQueriesLoading}
              <span class="hint">Loading…</span>
            {:else if savedQueriesError}
              <span class="hint error">{savedQueriesError}</span>
            {:else if savedQueries.length === 0}
              <span class="hint">
                No saved queries in the repository yet — switch to Inline JSON, or
                save a query from the main workspace first.
              </span>
            {:else}
              <select bind:value={referencePath}>
                <option value="">— pick a saved query —</option>
                {#each savedQueries as q (q.path)}
                  <option value={q.path}>{q.path}</option>
                {/each}
              </select>
              <span class="hint">
                Runtime fetch of saved queries lands in a follow-up — the
                reference is persisted, the tile renderer will pick it up
                once the resolver endpoint is wired.
              </span>
            {/if}
          </label>
        {:else}
          <label class="field">
            <span>Inline query body (AiQueryRequest JSON)</span>
            <textarea
              bind:value={inlineBodyJson}
              rows="10"
              spellcheck="false"
              class="json"
              placeholder={JSON.stringify(
                { cube: cube ?? null, measures: [{ name: "..." }], rows: [] },
                null,
                2,
              )}
            ></textarea>
            {#if bodyError}
              <span class="hint error">{bodyError}</span>
            {:else}
              <span class="hint">
                Paste an AiQueryRequest. A no-code editor lands in a later slice.
              </span>
            {/if}
          </label>
        {/if}
      {/if}

      {#if tile.type === "filter"}
        <label class="field">
          <span>Widget</span>
          <select bind:value={widget}>
            <option value="single-select">single-select</option>
            <option value="multi-select">multi-select</option>
            <option value="date-range">date-range</option>
            <option value="cascading-select">cascading-select</option>
          </select>
        </label>
        <label class="field">
          <span>Dimension</span>
          <select bind:value={filterTarget.dimension} disabled={!cube}>
            <option value="">— pick —</option>
            {#each dimensionOptions() as d (d)}
              <option value={d}>{d}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Hierarchy</span>
          <select bind:value={filterTarget.hierarchy} disabled={!filterTarget.dimension}>
            <option value="">— pick —</option>
            {#each hierarchyOptions() as h (h)}
              <option value={h}>{h}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Level</span>
          <select bind:value={filterTarget.level} disabled={!filterTarget.hierarchy}>
            <option value="">— pick —</option>
            {#each levelOptions() as l (l)}
              <option value={l}>{l}</option>
            {/each}
          </select>
        </label>
        <!-- issue #922: cascading-select config. Self-contained block,
             guarded by the widget check so the #919 rebase is clean. -->
        {#if widget === "cascading-select"}
          <fieldset class="size">
            <legend>Cascade (walk the hierarchy level-by-level)</legend>
            <label class="field inline">
              <span>Start level</span>
              <select bind:value={cascadeStartLevel} disabled={!filterTarget.hierarchy}>
                <option value="">— use level above —</option>
                {#each levelOptions() as l (l)}
                  <option value={l}>{l}</option>
                {/each}
              </select>
            </label>
            <label class="field inline">
              <span>Depth</span>
              <input type="number" min="1" max="6" bind:value={cascadeDepth} />
            </label>
          </fieldset>
          <span class="hint">
            Renders one dropdown per level from the start level down. Picking a
            parent reveals its children; choosing “All” resets the levels below.
          </span>
        {/if}
        <!-- end issue #922 -->
      {/if}

      {#if tile.type === "kpi"}
        <label class="field">
          <span>Measure</span>
          <select
            value={kpiConfig.measure ?? ""}
            disabled={!cube || measureOptions().length === 0}
            onchange={(e) => {
              const picked = (e.target as HTMLSelectElement).value;
              const opt = measureOptions().find((m) => m.name === picked);
              kpiConfig.measure = picked || undefined;
              kpiConfig.measureCaption = opt?.label;
            }}
          >
            <option value="">— pick a measure —</option>
            {#each measureOptions() as m (m.name)}
              <option value={m.name}>{m.label}</option>
            {/each}
          </select>
        </label>

        <label class="field">
          <span>Format</span>
          <select bind:value={kpiConfig.format}>
            <option value="number">Number</option>
            <option value="currency">Currency</option>
            <option value="percent">Percent</option>
            <option value="custom">Custom pattern</option>
          </select>
        </label>
        {#if kpiConfig.format === "custom"}
          <label class="field">
            <span>Custom pattern</span>
            <input
              type="text"
              bind:value={kpiConfig.customFormat}
              placeholder="e.g. $2 / 2% / 3"
            />
            <span class="hint">
              $N / €N / £N for currency, N% for percent, plain N for fractional digits.
            </span>
          </label>
        {/if}

        <fieldset class="size">
          <legend>Comparison</legend>
          <label class="field inline">
            <span>Mode</span>
            <select bind:value={kpiConfig.comparison}>
              <option value="none">None</option>
              <option value="prior-period">Prior period</option>
              <option value="year-over-year">Year over year</option>
              <option value="target">Target value</option>
            </select>
          </label>
          {#if kpiConfig.comparison === "target"}
            <label class="field inline">
              <span>Target</span>
              <input
                type="number"
                bind:value={kpiConfig.target}
                placeholder="e.g. 100000"
              />
            </label>
          {/if}
          <label class="field inline">
            <span>Direction</span>
            <select bind:value={kpiConfig.direction}>
              <option value="higher-is-better">Higher is better</option>
              <option value="lower-is-better">Lower is better</option>
            </select>
          </label>
        </fieldset>

        <label class="checkbox">
          <input type="checkbox" bind:checked={kpiConfig.sparkline} />
          <span>Sparkline (mini line chart under the number)</span>
        </label>

        {#if kpiConfig.comparison === "prior-period" || kpiConfig.comparison === "year-over-year" || kpiConfig.sparkline}
          <fieldset class="size">
            <legend>Time level (for comparison + sparkline)</legend>
            <label class="field inline">
              <span>Dimension</span>
              <select bind:value={kpiConfig.timeLevel!.dimension} disabled={!cube}>
                <option value="">— pick —</option>
                {#each dimensionOptions() as d (d)}
                  <option value={d}>{d}</option>
                {/each}
              </select>
            </label>
            <label class="field inline">
              <span>Hierarchy</span>
              <select
                bind:value={kpiConfig.timeLevel!.hierarchy}
                disabled={!kpiConfig.timeLevel?.dimension}
              >
                <option value="">— pick —</option>
                {#each kpiHierarchyOptions() as h (h)}
                  <option value={h}>{h}</option>
                {/each}
              </select>
            </label>
            <label class="field inline">
              <span>Level</span>
              <select
                bind:value={kpiConfig.timeLevel!.level}
                disabled={!kpiConfig.timeLevel?.hierarchy}
              >
                <option value="">— pick —</option>
                {#each kpiLevelOptions() as l (l)}
                  <option value={l}>{l}</option>
                {/each}
              </select>
            </label>
          </fieldset>
        {/if}

        <fieldset class="size">
          <legend>Threshold colouring (optional)</legend>
          <label class="field inline">
            <span>Red ≤ / ≥</span>
            <input
              type="number"
              bind:value={kpiConfig.thresholds!.red}
              placeholder="off"
            />
          </label>
          <label class="field inline">
            <span>Yellow</span>
            <input
              type="number"
              bind:value={kpiConfig.thresholds!.yellow}
              placeholder="off"
            />
          </label>
          <label class="field inline">
            <span>Green</span>
            <input
              type="number"
              bind:value={kpiConfig.thresholds!.green}
              placeholder="off"
            />
          </label>
        </fieldset>
      {/if}

      <!-- ════════════════════════════════════════════════════════════
           Issue #919 — Conditional formatting (TABLE tiles only).
           Self-contained block guarded by tile.type === "table" so the
           parallel #922 (cascading filter) edit rebases cleanly. All
           rule maths lives in $lib/dashboard/conditionalFormat.ts; this
           is config capture only.
           ════════════════════════════════════════════════════════════ -->
      {#if tile.type === "table"}
        <fieldset class="cf-section">
          <legend>Conditional formatting (per column)</legend>
          <span class="hint">
            Display-only — the underlying data is unchanged. Match a rule to a
            column by its header caption.
          </span>

          {#if conditionalFormat.length === 0}
            <span class="hint">No rules yet.</span>
          {/if}

          {#each conditionalFormat as cfRule, i (i)}
            <div class="cf-rule">
              <div class="cf-rule-head">
                <span class="cf-rule-label">Rule {i + 1}</span>
                <button
                  type="button"
                  class="cf-remove"
                  aria-label="Remove rule"
                  onclick={() => removeConditionalRule(i)}>×</button
                >
              </div>

              <label class="field">
                <span>Column (header caption)</span>
                <input type="text" bind:value={cfRule.column} placeholder="e.g. Unit Sales" />
              </label>

              <div class="cf-row">
                <label class="field inline">
                  <span>Format</span>
                  <select bind:value={cfRule.type}>
                    {#each CONDITIONAL_TYPES as t (t.id)}
                      <option value={t.id}>{t.label}</option>
                    {/each}
                  </select>
                </label>

                {#if ruleUsesThresholds(cfRule)}
                  <label class="field inline">
                    <span>Threshold mode</span>
                    <select bind:value={cfRule.thresholdMode}>
                      {#each CONDITIONAL_MODES as m (m.id)}
                        <option value={m.id}>{m.label}</option>
                      {/each}
                    </select>
                  </label>
                {/if}
              </div>

              {#if ruleUsesThresholds(cfRule)}
                <div class="cf-row">
                  <label class="field inline">
                    <span>
                      Low {cfRule.thresholdMode === "relative" ? "(percentile)" : "(value)"}
                    </span>
                    <input type="number" bind:value={cfRule.lowThreshold} placeholder="off" />
                  </label>
                  <label class="field inline">
                    <span>
                      High {cfRule.thresholdMode === "relative" ? "(percentile)" : "(value)"}
                    </span>
                    <input type="number" bind:value={cfRule.highThreshold} placeholder="off" />
                  </label>
                </div>
                {#if cfRule.type === "font" || cfRule.type === "icon"}
                  <span class="hint">
                    Leave both thresholds empty to colour / point by sign
                    (negative = red / ↓, positive = green / ↑).
                  </span>
                {/if}
              {/if}

              {#if cfRule.type === "bar"}
                <label class="field">
                  <span>Bar colour</span>
                  <input type="text" bind:value={cfRule.barColor} placeholder="#4c8dff" />
                </label>
              {/if}

              {#if cfRule.type === "background" || cfRule.type === "font" || cfRule.type === "icon"}
                <div class="cf-row">
                  <label class="field inline">
                    <span>Low colour</span>
                    <input
                      type="text"
                      value={cfRule.colors?.low ?? ""}
                      placeholder="default red"
                      oninput={(e) => {
                        const v = (e.target as HTMLInputElement).value;
                        cfRule.colors = { ...cfRule.colors, low: v || undefined };
                      }}
                    />
                  </label>
                  <label class="field inline">
                    <span>Mid colour</span>
                    <input
                      type="text"
                      value={cfRule.colors?.mid ?? ""}
                      placeholder="default amber"
                      oninput={(e) => {
                        const v = (e.target as HTMLInputElement).value;
                        cfRule.colors = { ...cfRule.colors, mid: v || undefined };
                      }}
                    />
                  </label>
                  <label class="field inline">
                    <span>High colour</span>
                    <input
                      type="text"
                      value={cfRule.colors?.high ?? ""}
                      placeholder="default green"
                      oninput={(e) => {
                        const v = (e.target as HTMLInputElement).value;
                        cfRule.colors = { ...cfRule.colors, high: v || undefined };
                      }}
                    />
                  </label>
                </div>
              {/if}
            </div>
          {/each}

          <button type="button" class="btn cf-add" onclick={addConditionalRule}>
            + Add column rule
          </button>
        </fieldset>
      {/if}
    </div>
    <footer class="modal-footer">
      <button type="button" class="btn" onclick={onClose} disabled={imageUploading}>Cancel</button>
      <button type="button" class="btn primary" onclick={handleSave} disabled={imageUploading}>
        {imageUploading ? "Uploading…" : "Save"}
      </button>
    </footer>
  </div>
</div>

<style>
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 50;
  }
  .modal {
    background: var(--bg);
    border-radius: 8px;
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2);
    width: min(560px, 92vw);
    max-height: 90vh;
    display: flex;
    flex-direction: column;
  }
  .modal-header {
    display: flex;
    align-items: center;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid var(--border);
  }
  .modal-header h2 {
    margin: 0;
    font-size: 1rem;
    flex: 1;
    text-transform: capitalize;
  }
  .close {
    border: none;
    background: transparent;
    font-size: 1.25rem;
    cursor: pointer;
    color: var(--fg-muted);
  }
  .modal-body {
    padding: 1rem;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }
  .field span:first-child {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .size {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .size legend {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .field.inline {
    flex: 1;
    min-width: 4rem;
  }
  .mode {
    display: flex;
    gap: 1rem;
    align-items: center;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .mode legend {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .radio,
  .checkbox {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.875rem;
    cursor: pointer;
  }
  .position-error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
    border-radius: 4px;
    font-size: 0.8125rem;
  }
  .field.inline input {
    width: 100%;
  }
  input, select, textarea {
    padding: 0.375rem 0.5rem;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    font-size: 0.875rem;
    font-family: inherit;
  }
  textarea.json {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
    font-size: 0.8125rem;
    white-space: pre;
  }
  .hint {
    font-size: 0.75rem;
    color: var(--fg-muted);
  }
  .hint.error { color: var(--danger); }
  .modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
    border-top: 1px solid var(--border);
  }
  .btn {
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn.primary {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
  /* ── Issue #919: conditional-formatting rule editor (table only) ── */
  .cf-section {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .cf-section legend {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .cf-rule {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.625rem;
    background: var(--bg-subtle);
  }
  .cf-rule-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .cf-rule-label {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .cf-remove {
    border: none;
    background: transparent;
    font-size: 1.125rem;
    line-height: 1;
    cursor: pointer;
    color: var(--fg-muted);
  }
  .cf-row {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
  }
  .cf-add {
    align-self: flex-start;
  }
</style>
