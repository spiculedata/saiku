/**
 * Map freshly parsed Mondrian-4 import cubes into the trimmed `doc.cubes`
 * model the workbench hydrates from (measure groups, factTableId,
 * measureColumns, dimensionLinks, calcs, time-intelligence metrics). UI-only
 * flags are re-derived when the workbench mounts.
 *
 * OSS host-glue mirror of saiku-cloud's
 * `routes/schemas/new/canvas/workbench-seed.ts` — kept structurally identical
 * so the edit-existing-cube flow behaves the same on both hosts (saiku#1634).
 * Extracted from the route so the transform is unit-testable.
 */
import type { importFromMondrianXml } from "$lib/cube-designer/mondrian-import";
import type { SchemaCanvasCube } from "$lib/cube-designer/types";

type WorkbenchImportCubes = ReturnType<typeof importFromMondrianXml>["workbenchCubes"];

export function mapWorkbenchDocCubes(cubes: WorkbenchImportCubes): SchemaCanvasCube[] {
  return cubes.map((c) => ({
    id: c.id,
    name: c.name,
    measureGroups: c.measureGroups.map((mg) => ({
      id: mg.id,
      name: mg.name,
      measureColumns: mg.measureColumns,
      factTableId: mg.factTableId,
      // Preserve linkKind / viaDimension / viaAttribute so FactLink and
      // ReferenceLink round-trip through the workbench → export.
      dimensionLinks: mg.dimensionLinks.map((l) => ({
        dimensionId: l.dimensionId,
        foreignKeyColumn: l.foreignKeyColumn,
        linkKind: l.linkKind,
        viaDimension: l.viaDimension,
        viaAttribute: l.viaAttribute,
      })),
      measureCaptions: {},
    })),
    calcs: c.factsCalcs.map((cc) => ({
      id: cc.id,
      name: cc.name,
      tokens: [],
      formula: cc.formula,
    })),
    // Carry declarative time-intelligence metrics onto the doc cube so they
    // survive import (and setCubes preserves them across workbench syncs).
    timeCalcs: c.timeCalcs,
  }));
}
