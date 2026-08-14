/**
 * saiku#1258 — the tile-editor field-layout unification (PR #1726) moved the
 * `.field / .field__label / .field__input` layout onto the global app.css
 * design-system pattern. The lesson that shipped WITHOUT test cover: a scoped
 * `.field { }` rule declared inside a child `TileEditor*.svelte` never reaches
 * the markup, because Svelte style scoping does not cross component boundaries —
 * the parent TileEditorModal renders the children, so their fields must be
 * styled by the *global* stylesheet, not a per-component scoped block.
 *
 * Two locks:
 *  (a) SSR-render each of the 7 child tile editors and assert their controls sit
 *      inside `.field > .field__label` + `.field__input` (the design-system shape
 *      the global CSS targets).
 *  (b) A source-regex test asserting no `TileEditor*.svelte` re-introduces a
 *      scoped `.field { }` style block — the exact mistake #1258 removed.
 *
 * DOM ordering/shape is asserted against Svelte's server render (the repo has no
 * client-mount harness — `svelte`'s client `mount` resolves to the server build
 * under vitest), mirroring src/lib/modals/SaveQueryModal.test.ts.
 */
import { describe, it, expect } from "vitest";
import { render } from "svelte/server";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";

import TileEditorChart from "./TileEditorChart.svelte";
import TileEditorFilter from "./TileEditorFilter.svelte";
import TileEditorImage from "./TileEditorImage.svelte";
import TileEditorKpi from "./TileEditorKpi.svelte";
import TileEditorTableConditional from "./TileEditorTableConditional.svelte";
import TileEditorTableSparkline from "./TileEditorTableSparkline.svelte";
import TileEditorText from "./TileEditorText.svelte";

// Loosely-typed SSR render alias: the CASES below hold heterogeneous components as
// `unknown`, so casting each to `Parameters<typeof render>[0]` would collapse its props
// type to `never` and reject the `Record<string, unknown>` props object (svelte-check
// catches this; vitest doesn't type-check). One alias keeps the call sites clean with no
// `any` and no per-component typing.
const renderSSR = render as (c: unknown, o: { props: Record<string, unknown> }) => { body: string };

// Minimal valid props per editor — enough that each renders at least one
// design-system `.field` block. Cube-gated pickers still render their `.field`
// wrappers (they only `disabled` the control), so `cubePicked: true` is not
// required to see the structure — but we pass it where the type demands it.
const CASES: { name: string; component: unknown; props: Record<string, unknown> }[] = [
  {
    name: "TileEditorChart",
    component: TileEditorChart,
    props: { chartType: "bar", chartOptionsCustomised: false, onOpenChartOptions: () => {} },
  },
  {
    name: "TileEditorFilter",
    component: TileEditorFilter,
    props: {
      widget: "single-select",
      filterTarget: { dimension: "", hierarchy: "", level: "" },
      cascadeStartLevel: "",
      cascadeDepth: 1,
      cubePicked: true,
      dimensions: [],
      hierarchies: [],
      levels: [],
    },
  },
  {
    name: "TileEditorImage",
    component: TileEditorImage,
    props: {
      imageMode: "url",
      imageUrl: "",
      imageExistingSrc: "",
      imageFit: "contain",
      imageCaption: "",
      imageAlt: "",
      imageFile: null,
    },
  },
  {
    name: "TileEditorKpi",
    component: TileEditorKpi,
    props: {
      // KPI always renders the Threshold-colouring fieldset with `thresholds!.red`,
      // so a real config always carries a thresholds object — mirror that here.
      kpiConfig: { format: "number", comparison: "none", thresholds: {} },
      cubePicked: true,
      measures: [],
      dimensions: [],
      hierarchies: [],
      levels: [],
    },
  },
  {
    name: "TileEditorTableConditional",
    component: TileEditorTableConditional,
    // The .field controls are per-rule (the empty state renders only the "add rule"
    // affordance), so seed one rule to exercise the field layout.
    props: {
      conditionalFormat: [{ column: "Store Sales", type: "background", thresholdMode: "relative" }],
    },
  },
  {
    name: "TileEditorTableSparkline",
    component: TileEditorTableSparkline,
    props: { sparklineEnabled: true, sparklineType: "line" },
  },
  {
    name: "TileEditorText",
    component: TileEditorText,
    props: { text: "" },
  },
];

describe("tile editors use the global .field design-system shape (saiku#1258)", () => {
  for (const { name, component, props } of CASES) {
    it(`${name}: controls sit inside .field > .field__label + .field__input`, () => {
      const body = renderSSR(component, { props }).body;

      // The design-system trio the global app.css targets must all be present.
      expect(body, `${name} should render a .field wrapper`).toContain('class="field');
      expect(body, `${name} should render a .field__label`).toContain("field__label");
      expect(body, `${name} should render a .field__input`).toContain("field__input");

      // The label must precede its input within the field (label-then-control order).
      const labelIdx = body.indexOf("field__label");
      const inputIdx = body.indexOf("field__input");
      expect(labelIdx).toBeGreaterThanOrEqual(0);
      expect(inputIdx).toBeGreaterThanOrEqual(0);
      expect(labelIdx, `${name}: label should come before the input`).toBeLessThan(inputIdx);
    });
  }
});

/**
 * saiku#1789 — the custom-renderer config forms (ranked list, graph) are declared
 * INLINE in TileEditorModal.svelte rather than as child `TileEditor*.svelte`
 * components, so the SSR sweep above never sees them. The ranked-list fieldset
 * shipped with bare `<span>` / `<input>` and no `.field__label` / `.field__input`,
 * and because `app.css` declares `.field { display: block }` and does the stacking
 * on the CHILD classes, every label overprinted its own control.
 *
 * Asserted against source rather than SSR: rendering TileEditorModal needs a cube,
 * a live query store and a mounted client harness the repo doesn't have.
 */
describe("inline custom-renderer forms use the .field design-system shape (saiku#1789)", () => {
  const src = readFileSync(
    fileURLToPath(new URL("./TileEditorModal.svelte", import.meta.url)),
    "utf8",
  );

  /** Pull one `<fieldset …><legend>NAME</legend> … </fieldset>` block out of the source. */
  function fieldsetByLegend(legend: string): string {
    const start = src.indexOf(`<legend>${legend}</legend>`);
    expect(start, `fieldset with <legend>${legend}</legend> not found`).toBeGreaterThan(-1);
    const end = src.indexOf("</fieldset>", start);
    expect(end, `unterminated fieldset for ${legend}`).toBeGreaterThan(start);
    return src.slice(start, end);
  }

  for (const legend of ["Ranked list", "Graph columns"]) {
    it(`${legend}: every .field labels with .field__label and controls with .field__input`, () => {
      const block = fieldsetByLegend(legend);

      // Each `<label class="field">` opens a field row; count them so a future
      // refactor that drops the fieldset can't make this vacuously pass.
      const fields = block.match(/<label class="field"/g) ?? [];
      expect(fields.length, `${legend} should declare at least one .field row`).toBeGreaterThan(2);

      // The label span carrying the caption must be classed, or it lays out
      // inline alongside the control instead of stacking above it.
      const bareLabelSpan = /<label class="field">\s*<span>(?!\s*<)/;
      expect(
        bareLabelSpan.test(block),
        `${legend}: a .field row opens with an unclassed <span> — add class="field__label" ` +
          `or it renders on the same line as its control`,
      ).toBe(false);

      // Every control inside the fieldset needs .field__input for its width.
      const controls = block.match(/<(input|select|textarea)\b[^>]*>/g) ?? [];
      const unclassed = controls.filter(
        (c) => !c.includes("field__input") && !c.includes('type="checkbox"'),
      );
      expect(
        unclassed,
        `${legend}: ${unclassed.length} control(s) lack class="field__input"`,
      ).toEqual([]);
    });
  }
});

describe("no tile editor re-introduces a scoped .field style block (saiku#1258)", () => {
  const dir = fileURLToPath(new URL(".", import.meta.url));
  const editors = readdirSync(dir).filter(
    (f) => /^TileEditor.*\.svelte$/.test(f),
  );

  it("finds the expected set of tile editors", () => {
    // Guard against the glob silently matching nothing (which would make the
    // scoped-style assertion below vacuously pass).
    expect(editors.length).toBeGreaterThanOrEqual(7);
  });

  for (const file of editors) {
    it(`${file} has no scoped .field { } / .field.<mod> { } rule`, () => {
      const src = readFileSync(fileURLToPath(new URL(`./${file}`, import.meta.url)), "utf8");
      const styleMatch = src.match(/<style[^>]*>([\s\S]*?)<\/style>/);
      const styleBlock = styleMatch ? styleMatch[1] : "";
      // A scoped `.field { }` or `.field.<modifier> { }` selector — the pattern
      // that never crosses the component boundary. `:global(.field ...)` is fine
      // and is NOT matched here (it carries the `:global(` prefix).
      const scopedField = /(^|[^(])\.field(\.[\w-]+)?\s*\{/m;
      expect(
        scopedField.test(styleBlock),
        `${file} declares a scoped .field rule — it will not reach the markup; ` +
          `rely on the global app.css .field pattern (or use :global(...))`,
      ).toBe(false);
    });
  }
});
