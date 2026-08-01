/**
 * Workbench drag-and-drop payload contract (saiku-cloud#1039 split).
 *
 * Single source of truth for the MIME types the left rail (producer) and
 * the dimension / measure / hierarchy drop zones (consumers) agree on, plus
 * the pure payload parsers and drag-kind predicates. Extracted from
 * `WorkbenchView.svelte` so the serialise/parse contract is unit-testable
 * (a fake DataTransfer is all that's needed) — the drag *handlers* that
 * mutate component state stay in the component and import these.
 */
import type { SchemaCanvasColumnKind } from "./types.js";

// Left-rail drag sources.
//   column     — a single column → adds one level.
//   table      — a table → adds a hierarchy with all its columns as levels.
//   join-group — every column from every participating table.
export const COLUMN_MIME = "application/x-saiku-column";
export const TABLE_MIME = "application/x-saiku-table";
export const JOINGROUP_MIME = "application/x-saiku-joingroup";
// Fact-side: picked measure columns drop into a measure group's Content
// column. Plain column-name string payload.
export const FACTS_MEASURE_MIME = "application/x-saiku-facts-measure";
// Pane-reorder MIMEs — separate per side so a dim-pane drag can't drop on a
// facts pane and vice versa.
export const FACTS_PANE_REORDER_MIME = "application/x-saiku-facts-pane-index";
export const PANE_REORDER_MIME = "application/x-saiku-pane-index";
// Whole measure group dragged into a calc formula — payload is the group id.
export const FACTS_MEASURE_GROUP_MIME =
  "application/x-saiku-facts-measure-group";
// Attribute / level drags (Col 2 → Col 4, Col 4 → Col 4). Distinct so they
// don't collide with the column / table / join-group / pane-reorder MIMEs.
export const ATTRIBUTE_MIME = "application/x-saiku-attribute";
export const LEVEL_MOVE_MIME = "application/x-saiku-level-move";

export interface ColumnDragPayload {
  tableId: string;
  columnName: string;
  columnType?: SchemaCanvasColumnKind;
}
export interface TableDragPayload {
  tableId: string;
}
export interface JoinGroupDragPayload {
  key: string;
  tableIds: string[];
}
export interface AttributeDragPayload {
  tableId: string;
  columnName: string;
}
export interface LevelMoveDragPayload {
  dimId: string;
  hierId: string;
  levelId: string;
}

function dragTypes(e: DragEvent): readonly string[] {
  return Array.from(e.dataTransfer?.types ?? []);
}

// ── Parsers ─────────────────────────────────────────────────────────
export function readColumnPayload(e: DragEvent): ColumnDragPayload | null {
  if (!e.dataTransfer) return null;
  const raw = e.dataTransfer.getData(COLUMN_MIME);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ColumnDragPayload;
  } catch {
    return null;
  }
}

export function readTablePayload(e: DragEvent): TableDragPayload | null {
  if (!e.dataTransfer) return null;
  const raw = e.dataTransfer.getData(TABLE_MIME);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as TableDragPayload;
  } catch {
    return null;
  }
}

export function readJoinGroupPayload(
  e: DragEvent,
): JoinGroupDragPayload | null {
  if (!e.dataTransfer) return null;
  const raw = e.dataTransfer.getData(JOINGROUP_MIME);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as JoinGroupDragPayload;
  } catch {
    return null;
  }
}

export function readAttributeDrag(e: DragEvent): AttributeDragPayload | null {
  if (!e.dataTransfer?.types.includes(ATTRIBUTE_MIME)) return null;
  try {
    return JSON.parse(
      e.dataTransfer.getData(ATTRIBUTE_MIME),
    ) as AttributeDragPayload;
  } catch {
    return null;
  }
}

export function readLevelMoveDrag(e: DragEvent): LevelMoveDragPayload | null {
  if (!e.dataTransfer?.types.includes(LEVEL_MOVE_MIME)) return null;
  try {
    return JSON.parse(
      e.dataTransfer.getData(LEVEL_MOVE_MIME),
    ) as LevelMoveDragPayload;
  } catch {
    return null;
  }
}

export function readFactsMeasureDrag(e: DragEvent): string | null {
  if (dragTypes(e).includes(FACTS_MEASURE_MIME)) {
    return e.dataTransfer!.getData(FACTS_MEASURE_MIME);
  }
  return null;
}

// ── Drag-kind predicates ────────────────────────────────────────────
export function isColumnDrag(e: DragEvent): boolean {
  return dragTypes(e).includes(COLUMN_MIME);
}

export function isAnyWorkbenchDrag(e: DragEvent): boolean {
  const types = dragTypes(e);
  return (
    types.includes(COLUMN_MIME) ||
    types.includes(TABLE_MIME) ||
    types.includes(JOINGROUP_MIME)
  );
}

export function isContentDrag(e: DragEvent): boolean {
  return (
    (e.dataTransfer?.types.includes(ATTRIBUTE_MIME) ?? false) ||
    (e.dataTransfer?.types.includes(LEVEL_MOVE_MIME) ?? false)
  );
}

export function isPaneReorderDrag(e: DragEvent): boolean {
  return e.dataTransfer?.types.includes(PANE_REORDER_MIME) ?? false;
}

export function isFactsPaneReorderDrag(e: DragEvent): boolean {
  return e.dataTransfer?.types.includes(FACTS_PANE_REORDER_MIME) ?? false;
}
