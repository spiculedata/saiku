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

// Minimal valid props per editor — enough that each renders at least one
// design-system `.field` block. Cube-gated pickers still render their `.field`
// wrappers (they only `disabled` the control), so `cubePicked: true` is not
// required to see the structure — but we pass it where the type demands it.
const CASES: { name: string; component: unknown; props: Record<string, unknown> }[] = [
  {
    name: "TileEditorChart",
    component: TileEditorChart,
    props: { chartType: "bar", chartOptionsTouched: false, onOpenChartOptions: () => {} },
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
      const body = render(component as Parameters<typeof render>[0], { props }).body;

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
