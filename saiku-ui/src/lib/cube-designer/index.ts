/**
 * Public API of `@concepttocloud/saiku-cube-designer`.
 *
 * The visual cube/schema designer, host-agnostic: mount the views, provide a
 * {@link CubeDesignerBackend} (and optional {@link CubeDesignerAI}) via context,
 * and the designer profiles / authors / emits Mondrian 4. Both Saiku OSS and
 * Saiku Cloud consume this one source of truth with their own adapters.
 */

// ── Mount points ────────────────────────────────────────────────────────────
export { default as SchemaCanvasView } from "./SchemaCanvasView.svelte";
export { default as WorkbenchView } from "./WorkbenchView.svelte";

// ── Store ───────────────────────────────────────────────────────────────────
export { SchemaCanvasStore } from "./state.svelte";

// ── Backend seam (host provides the adapter) ─────────────────────────────────
export {
  setCubeDesignerBackend,
  setCubeDesignerAI,
  getCubeDesignerBackend,
  getCubeDesignerAI,
  type CubeDesignerBackend,
  type CubeDesignerAI,
} from "./backend";

// ── Mondrian emit / import ───────────────────────────────────────────────────
export { exportToMondrianXml, exportToMondrianYaml } from "./mondrian-export";
export { importFromMondrianXml } from "./mondrian-import";

// ── Source profiling ─────────────────────────────────────────────────────────
export { parseProfileTables } from "./profile-types";
export type {
  ProfileTableSummary,
  ProfileColumnSummary,
} from "./profile-types";

// ── Core types ───────────────────────────────────────────────────────────────
export type {
  SchemaCanvasState,
  SourceTableCandidate,
  SchemaCanvasTable,
  SchemaCanvasJoin,
  SchemaCanvasDimension,
  SchemaCanvasHierarchy,
  SchemaCanvasLevel,
  SchemaCanvasMeasure,
  SchemaCanvasCube,
} from "./types";
