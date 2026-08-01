/**
 * OSS edit-existing-cube hydration (saiku#1634).
 *
 * When the Cube Designer is opened for a datasource that already has a Mondrian
 * schema attached, the host fetches that schema's XML (see
 * `oss-backend.ts#fetchDatasourceSchema`) and calls this helper to parse it and
 * populate the canvas + workbench — mirroring saiku-cloud's
 * `ImportController.loadFromLibrary` commit path, minus the library modal UI.
 *
 * Kept as host glue (not a shared `$lib/cube-designer` component) so saiku-cloud,
 * which has its own library-based edit flow, is unaffected. Extracted from the
 * route's `onMount` so the parse → hydrate transform is unit-testable.
 */
import type { SchemaCanvasStore } from "$lib/cube-designer/state.svelte";
import { importFromMondrianXml } from "$lib/cube-designer/mondrian-import";
import { mapWorkbenchDocCubes } from "./workbench-seed";

export interface HydrateResult {
  tableCount: number;
  joinCount: number;
  warnings: string[];
}

/**
 * Parse `xml` and load it into the store as the canvas doc + workbench cubes,
 * recentering the viewport on the freshly-loaded nodes. `connectionId` is the
 * datasource the canvas is authoring against; `now` is injectable for tests.
 *
 * Throws whatever `importFromMondrianXml` throws (malformed XML,
 * `AmbiguousSchemaError`) so the caller can surface a message.
 */
export function hydrateFromMondrianXml(
  store: SchemaCanvasStore,
  xml: string,
  connectionId: string,
  now: () => number = Date.now,
): HydrateResult {
  const { state, warnings, workbenchCubes } = importFromMondrianXml(xml, {
    connectionId,
    sourceTables: store.sourceTables,
  });
  store.loadDoc(state);
  if (workbenchCubes.length > 0) {
    store.setCubes(mapWorkbenchDocCubes(workbenchCubes));
  }
  if (state.tables.length > 0) {
    // Recenter so the loaded nodes aren't rendered off-screen with only a
    // minimap dot (saiku-cloud#1035 keeps fit_view on every import path).
    store.requestedCanvasAction = { kind: "fit_view", ts: now() };
  }
  return {
    tableCount: state.tables.length,
    joinCount: state.joins.length,
    warnings,
  };
}
