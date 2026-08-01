/*
 * Safe-subset validator for a declarative ECharts `option` (App Builder Phase 2,
 * saiku#1441 — the `echarts-option` custom tile renderer).
 *
 * An app author supplies a plain, declarative ECharts `option` object — NO code.
 * Before it is ever handed to `chart.setOption()` it is validated against a
 * curated safe subset. The posture MIRRORS `$lib/dashboard/cssSanitiser`: reject
 * hostile constructs and FAIL CLOSED — anything the validator is not sure about
 * is rejected, and the whole option is dropped rather than partially sanitised.
 *
 * Three independent reject rules, enforced across the WHOLE tree at any depth:
 *
 *  1. NO FUNCTION VALUES. A function anywhere in the option (e.g.
 *     `tooltip.formatter`, `label.formatter`, an `onclick`) is an exec / XSS
 *     vector — ECharts calls these with live DOM/data. If any value is a
 *     function at any depth, the entire option is rejected. (JSON authoring
 *     can't even express a function; this guards the `unknown` boundary where a
 *     live object could carry one.)
 *  2. NO REMOTE URLs. Any string value that references a remote resource — an
 *     absolute `http(s):` / other scheme, a protocol-relative `//host`, a
 *     `url(...)` pointing off-origin, or a non-image `data:` URI — is a
 *     data-exfil / SSRF-ish vector (backgrounds, `image://` symbols, rich-text
 *     images). Only same-origin/relative refs and `data:image/<raster>` are
 *     allowed. Detection runs on url() targets AND on bare scheme'd strings,
 *     mirroring the cssSanitiser url() posture.
 *  3. ALLOWLIST. Only a curated set of top-level keys is accepted, and each
 *     `series` entry may only carry allowlisted fields. Unknown keys are
 *     REJECTED (fail closed), not silently dropped.
 *
 * Prototype-pollution keys (`__proto__` / `constructor` / `prototype`) are
 * rejected wherever they appear. Depth + node budgets bound the walk so a
 * pathological or circular input terminates as a rejection rather than hanging.
 *
 * Pure: no DOM, no fetches, no ECharts import — so the self-contained embed
 * bundle can import it too (it has no `$lib` alias). Never throws: every path
 * returns a discriminated result.
 */

/** A validated, safe ECharts option. Structurally a plain object; the brand is
 *  documentation only (the validator guarantees the safe-subset invariants). */
export type SafeEChartsOption = Record<string, unknown>;

/** Result of {@link validateEchartsOption}. Shape-compatible with the tile
 *  registry's {@code ValidateOptionsResult} so it can back a
 *  {@code TileRenderer.validateOptions}. */
export type ValidateEchartsOptionResult =
  | { ok: true; value: SafeEChartsOption }
  | { ok: false; error: string };

/** Curated chart-safe top-level option keys. Anything outside this set is
 *  rejected. Deliberately excludes `graphic` (arbitrary DOM/elements),
 *  `media` (responsive rule bodies), and event-handler-shaped keys. */
const TOP_LEVEL_ALLOWLIST: ReadonlySet<string> = new Set([
  "title",
  "grid",
  "xAxis",
  "yAxis",
  "series",
  "legend",
  "tooltip",
  "color",
  "backgroundColor",
  "textStyle",
  "dataZoom",
  "axisPointer",
  "visualMap",
  "polar",
  "radiusAxis",
  "angleAxis",
  "radar",
  "aria",
  "animation",
]);

/** Allowlisted fields on each `series[i]`. Covers the common cartesian / pie /
 *  radar / scatter shapes without opening arbitrary keys. */
const SERIES_FIELD_ALLOWLIST: ReadonlySet<string> = new Set([
  "type",
  "name",
  "data",
  "encode",
  "stack",
  "coordinateSystem",
  "xAxisIndex",
  "yAxisIndex",
  "polarIndex",
  "radarIndex",
  "itemStyle",
  "lineStyle",
  "areaStyle",
  "label",
  "labelLine",
  "emphasis",
  "tooltip",
  "symbol",
  "symbolSize",
  "showSymbol",
  "smooth",
  "step",
  "connectNulls",
  "barWidth",
  "barMaxWidth",
  "barMinWidth",
  "barGap",
  "barCategoryGap",
  "showBackground",
  "backgroundStyle",
  "radius",
  "center",
  "roseType",
  "startAngle",
  "clockwise",
  "colorBy",
  "color",
  "large",
  "sampling",
  "clip",
  "z",
  "zlevel",
  "markLine",
  "markPoint",
  "markArea",
  "seriesLayoutBy",
  "datasetIndex",
]);

/** Keys that enable prototype pollution — rejected wherever they appear. */
const POLLUTION_KEYS: ReadonlySet<string> = new Set(["__proto__", "constructor", "prototype"]);

/** Budgets that bound the recursive walk (DoS / circular-ref guard). */
const MAX_DEPTH = 16;
const MAX_NODES = 20_000;

/** Same-origin `data:` images that are safe to embed. SVG is excluded on
 *  purpose — an SVG data URI can carry script. */
const ALLOWED_DATA_IMAGE = /^data:image\/(?:png|jpe?g|gif|webp);/i;

/**
 * Extract every `url(...)` target from a value string, matching the
 * cssSanitiser extractor so url() hidden inside a bigger value is caught.
 */
function extractUrlTargets(value: string): string[] {
  const targets: string[] = [];
  const re = /url\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^)]*)\s*\)/gi;
  let match: RegExpExecArray | null;
  while ((match = re.exec(value)) !== null) {
    targets.push(match[1]);
  }
  return targets;
}

/**
 * True when a resource reference (a full string value, or a `url()` target) is
 * safe: empty, a same-origin/relative path, or a `data:image/<raster>` URI.
 * Absolute schemes, protocol-relative `//host`, `image://<remote>`, and any
 * other `data:` payload are unsafe. Fails closed.
 */
function resourceRefAllowed(raw: string): boolean {
  let s = raw.trim().replace(/^['"]|['"]$/g, "").trim();
  // ECharts image-symbol prefix — validate whatever it points at.
  if (/^image:\/\//i.test(s)) {
    s = s.slice("image://".length).trim();
  }
  if (s === "") return true;
  if (ALLOWED_DATA_IMAGE.test(s)) return true;
  // Protocol-relative host reference.
  if (s.startsWith("//")) return false;
  // Any explicit scheme (http:, https:, javascript:, data:<non-image>, blob:,
  // file:, …) is rejected.
  if (/^[a-z][a-z0-9+.-]*:/i.test(s)) return false;
  // No scheme and not protocol-relative → same-origin / relative → allowed.
  return true;
}

/**
 * True when a string value is hostile: it references a remote resource, either
 * as a bare scheme'd/protocol-relative value, via a disallowed `url()` target,
 * or by embedding an absolute `http(s)` / `image://` URL anywhere inside it.
 */
function stringIsHostile(value: string): boolean {
  const v = value.trim();
  // 1. Bare resource reference as the whole value.
  if (!resourceRefAllowed(v)) return true;
  // 2. Any disallowed url() target embedded in the value.
  for (const target of extractUrlTargets(v)) {
    if (!resourceRefAllowed(target)) return true;
  }
  // 3. An absolute remote URL embedded ANYWHERE in the string (rich text /
  //    concatenated values). Protocol-relative refs are only treated as hostile
  //    at the start of the value (rule 1) to avoid false positives on prose.
  const lower = v.toLowerCase();
  if (/https?:\/\//.test(lower)) return true;
  if (/image:\/\//.test(lower)) return true;
  return false;
}

/**
 * Deep-scan an arbitrary value for the three reject rules. Returns an error
 * string on the first violation, or {@code null} when the subtree is clean.
 */
function scanValue(
  value: unknown,
  path: string,
  ctx: { nodes: number },
  depth: number,
): string | null {
  if (depth > MAX_DEPTH) return `Option nesting is too deep (at ${path || "root"}).`;
  if (++ctx.nodes > MAX_NODES) return "Option is too large to validate.";

  const t = typeof value;
  if (t === "function") return `Function values are not allowed (at ${path || "root"}).`;
  if (t === "symbol" || t === "bigint" || t === "undefined") {
    // undefined can legitimately appear as an omitted key's value; only flag it
    // when it is an explicit array element / property value we can't serialise.
    if (t === "undefined") return null;
    return `Unsupported value type at ${path || "root"}.`;
  }
  if (value === null) return null;
  if (t === "string") {
    return stringIsHostile(value as string)
      ? `Remote or unsafe URL is not allowed (at ${path || "root"}).`
      : null;
  }
  if (t === "number" || t === "boolean") return null;

  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      const err = scanValue(value[i], `${path}[${i}]`, ctx, depth + 1);
      if (err) return err;
    }
    return null;
  }

  if (t === "object") {
    for (const key of Object.keys(value as object)) {
      if (POLLUTION_KEYS.has(key)) return `Disallowed key "${key}" (at ${path || "root"}).`;
      const childPath = path ? `${path}.${key}` : key;
      const err = scanValue((value as Record<string, unknown>)[key], childPath, ctx, depth + 1);
      if (err) return err;
    }
    return null;
  }

  return `Unsupported value type at ${path || "root"}.`;
}

/** JSON-safe deep clone. Inputs reaching here have already been scanned, so they
 *  contain only JSON-cloneable values; a circular ref (caught by the node
 *  budget earlier) would make this throw, which the caller turns into a
 *  rejection. Returns a fresh object so the validated value is never an alias of
 *  the caller's input (immutability). */
function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

/**
 * Validate an author-supplied ECharts `option` against the safe subset.
 * Returns {@code {ok:true, value}} with a fresh, safe copy, or
 * {@code {ok:false, error}} with a human-readable reason. Never throws.
 */
export function validateEchartsOption(input: unknown): ValidateEchartsOptionResult {
  try {
    if (typeof input !== "object" || input === null || Array.isArray(input)) {
      return { ok: false, error: "ECharts option must be a JSON object." };
    }
    const obj = input as Record<string, unknown>;

    // 1. Top-level allowlist (fail closed on unknown keys).
    for (const key of Object.keys(obj)) {
      if (POLLUTION_KEYS.has(key)) {
        return { ok: false, error: `Disallowed key "${key}".` };
      }
      if (!TOP_LEVEL_ALLOWLIST.has(key)) {
        return {
          ok: false,
          error: `Unknown top-level key "${key}". Allowed keys: ${[...TOP_LEVEL_ALLOWLIST].join(", ")}.`,
        };
      }
    }

    // 2. Per-series field allowlist.
    if ("series" in obj && obj.series !== undefined && obj.series !== null) {
      const list = Array.isArray(obj.series) ? obj.series : [obj.series];
      for (let i = 0; i < list.length; i++) {
        const s = list[i];
        if (typeof s !== "object" || s === null || Array.isArray(s)) {
          return { ok: false, error: `series[${i}] must be an object.` };
        }
        for (const key of Object.keys(s as object)) {
          if (POLLUTION_KEYS.has(key)) {
            return { ok: false, error: `Disallowed key "${key}" in series[${i}].` };
          }
          if (!SERIES_FIELD_ALLOWLIST.has(key)) {
            return {
              ok: false,
              error: `Unknown series field "${key}" in series[${i}]. Allowed: ${[...SERIES_FIELD_ALLOWLIST].join(", ")}.`,
            };
          }
        }
      }
    }

    // 3. Deep scan for functions / remote URLs / pollution across the whole tree.
    const err = scanValue(obj, "", { nodes: 0 }, 0);
    if (err) return { ok: false, error: err };

    return { ok: true, value: deepClone(obj) };
  } catch (e: unknown) {
    // Fail closed on anything unexpected (e.g. a circular structure).
    return { ok: false, error: e instanceof Error ? e.message : "Invalid ECharts option." };
  }
}

/* ------------------------------------------------------------------ *
 * Data merge — inject the tile's query data into the author's option. *
 * ------------------------------------------------------------------ */

/** Generic chart projection the data-merge understands. Both the in-app tile
 *  (from an AiQueryResponse) and the embed tile (from token-scoped rows) build
 *  one of these, so the merge logic is shared. */
export interface EChartsDataProjection {
  /** Category-axis labels (one per data row). */
  categories: string[];
  /** One entry per measure column: its name + the column of values. */
  series: { name: string; data: (number | null)[] }[];
}

function asObject(v: unknown): Record<string, unknown> {
  return v && typeof v === "object" && !Array.isArray(v) ? { ...(v as Record<string, unknown>) } : {};
}

/** Set category `data` on a single axis object (only when it is a category axis
 *  and the author didn't already supply data). Returns a fresh object. */
function withCategoryData(axis: unknown, categories: string[]): Record<string, unknown> {
  const a = asObject(axis);
  const type = a.type;
  if (type === undefined || type === "category") {
    a.type = "category";
    if (a.data === undefined) a.data = categories;
  }
  return a;
}

/** Apply the category axis to `xAxis`, tolerating a single axis or an array. */
function applyCategoryAxis(axis: unknown, categories: string[]): unknown {
  if (Array.isArray(axis)) {
    return axis.map((a, i) => (i === 0 ? withCategoryData(a, categories) : a));
  }
  return withCategoryData(axis, categories);
}

/**
 * Merge a data projection into a validated author option, returning a fresh
 * render-ready option. The author owns the chart shape + styling; this only
 * fills in axis categories and series data:
 *
 *  - Category `xAxis.data` is populated from {@link EChartsDataProjection.categories}
 *    (unless the author already set axis data), and a default value `yAxis` is
 *    added when absent — skipped entirely for pie charts.
 *  - Author-declared `series` keep their type/style; each gets its `data`
 *    filled from the projection by index (pie series get `{name,value}` pairs).
 *  - When the author declared no series, one bar series per measure is synthesised.
 *
 * Pure; never mutates its inputs.
 */
export function applyDataToEchartsOption(
  option: SafeEChartsOption,
  projection: EChartsDataProjection,
): Record<string, unknown> {
  const opt = deepClone(option) as Record<string, unknown>;
  const { categories, series } = projection;

  const declared =
    opt.series === undefined || opt.series === null
      ? []
      : Array.isArray(opt.series)
        ? opt.series
        : [opt.series];

  const isPie = declared.some(
    (s) => s && typeof s === "object" && (s as Record<string, unknown>).type === "pie",
  );

  if (!isPie) {
    opt.xAxis = applyCategoryAxis(opt.xAxis, categories);
    if (opt.yAxis === undefined || opt.yAxis === null) opt.yAxis = { type: "value" };
  }

  if (declared.length === 0) {
    opt.series = series.map((s) => ({ type: "bar", name: s.name, data: s.data }));
  } else {
    opt.series = declared.map((raw, i) => {
      const s = asObject(raw);
      const src = series[i] ?? series[0];
      if (!src) return s;
      if (s.type === "pie") {
        s.data = categories.map((c, ci) => ({ name: c, value: src.data[ci] ?? null }));
      } else {
        s.data = src.data;
        if (s.name === undefined) s.name = src.name;
      }
      return s;
    });
  }

  return opt;
}
