/**
 * SvelteFlow node/edge builders — pure derivations lifted out of
 * SchemaCanvasView.svelte (audit finding #1040, template-decompose pass).
 *
 * The component keeps the `$derived`/`$state`/`$effect` reactivity; these
 * functions own the pure transform from the store + focus state into the
 * `Node[]` / `Edge[]` arrays SvelteFlow renders. Callbacks close over the
 * store (the mutation boundary) exactly as they did inline.
 */
import type { Node, Edge } from "@xyflow/svelte";
import { SchemaCanvasStore } from "./state.svelte.js";
import type { SchemaCanvasJoin } from "./types.js";

/** Focus/selection state the builders read (derived in the component). */
export interface FlowFocus {
  focusActive: boolean;
  focusedTableIds: Set<string>;
  focusedColumnKeys: Set<string>;
  connectedColumnKeys: Set<string>;
  focusedJoinIds: Set<string>;
}

/**
 * Derive SvelteFlow nodes from the store's tables. Focused/selected tables
 * lift to the front; faded peers dim. Column-click / promote / remove /
 * collapse callbacks route through the store.
 */
export function buildFlowNodes(
  store: SchemaCanvasStore,
  focus: FlowFocus,
): Node[] {
  const {
    focusActive,
    focusedTableIds,
    focusedColumnKeys,
    connectedColumnKeys,
  } = focus;
  return store.doc.tables.map((t) => {
    const isInFocus = focusActive && focusedTableIds.has(t.id);
    return {
      id: t.id,
      type: "table",
      position: t.position,
      draggable: true,
      deletable: true,
      // Lift focused/selected tables to the front so they never hide
      // behind faded peers when cards overlap.
      zIndex: isInFocus || store.selectedTableId === t.id ? 100 : 0,
      data: {
        table: t,
        highlightedColumn: store.highlightedColumn,
        pendingJoinSource: store.pendingJoinSource,
        eShiftPicks: store.eShiftPicks,
        defaultColumnsShown: store.defaultColumnsShown,
        focusedColumnKeys,
        connectedColumnKeys,
        /** When the table is in focus we auto-expand it so the user
         *  can see WHICH columns are wired without manually
         *  toggling. TableNode reads this to bypass the global
         *  Show-N-cols cap. */
        // Auto-expand on focus only when the green-highlight toggle
        // is on. Off-by-default keeps the layout stable — a focus
        // tap won't grow a card into space the arrange reserved.
        forceExpandedByFocus: isInFocus && store.highlightFocusedColumns,
        isFaded: focusActive && !focusedTableIds.has(t.id),
        onPromoteToFact: (id: string) => store.setTableRole(id, "fact"),
        onRemove: (id: string) => store.removeTable(id),
        onColumnClick: (tableId: string, col: string, metaOrCtrl: boolean) => {
          // Two ways a column click accumulates into the pick set:
          //   1. Create Joins Mode is on (toggled by ⌘J).
          //   2. Cmd/Ctrl is held during the click — an accelerator
          //      that skips the mode toggle so you can pick columns
          //      across tables without turning the mode on first.
          // Re-clicking an already-picked column removes it.  Plain
          // clicks outside the mode go through the normal
          // highlight / click-to-join flow.
          if (store.pickModeActive || metaOrCtrl) {
            store.pushEShiftPick(tableId, col);
          } else {
            store.tryColumnClickJoin(tableId, col);
          }
        },
        onToggleCollapsed: (id: string) => store.toggleTableCollapsed(id),
        onExpandFully: (id: string) => store.expandTableFully(id),
      },
      selected: store.selectedTableId === t.id,
    };
  });
}

/**
 * Aggregate joins by table-pair so role-playing dimensions (fact joining
 * `date` via order_date_id + ship_date_id + invoice_date_id) don't stack
 * overlapping labels at the same bezier midpoint. One Edge per pair; its
 * `data.joins` array carries every join in the pair (CanvasEdge renders a
 * popover when >1). Returns `[]` when join lines are hidden.
 */
export function buildFlowEdges(
  store: SchemaCanvasStore,
  focus: FlowFocus,
): Edge[] {
  const { focusActive, focusedJoinIds } = focus;
  // Joins-hidden toggle — return nothing so SvelteFlow paints zero
  // edges. Underlying joins stay intact in the doc.
  if (store.joinsHidden) return [];
  // Cube-link / inferred-fk joins are auto-derived from MeasureGroup
  // <ForeignKeyLink>s — semantic, not physical.  Hidden by default so
  // the canvas matches the source PhysicalSchema's <Link> set.  Toggle
  // on via store.showCubeLinks to render them (dashed/muted).
  const visibleJoins = store.doc.joins.filter((j) => {
    const origin = j.origin ?? "physical";
    return origin === "physical" || store.showCubeLinks;
  });
  const groups = new Map<string, SchemaCanvasJoin[]>();
  for (const j of visibleJoins) {
    const key = [j.sourceTableId, j.targetTableId].sort().join("::");
    const arr = groups.get(key) ?? [];
    arr.push(j);
    groups.set(key, arr);
  }
  const tableById = new Map(store.doc.tables.map((t) => [t.id, t]));
  const APPROX_TABLE_WIDTH = 240;
  const out: Edge[] = [];
  for (const [, joins] of groups) {
    const head = joins[0];
    const isSelected = joins.some((j) => store.selectedJoinId === j.id);
    // Pick handle sides so the line takes the shortest path between
    // tables. Compare table CENTRES: if source sits to the LEFT of
    // target, exit source right and enter target left; otherwise
    // the opposite. Tables with no position default to (0,0).
    const sourceT = tableById.get(head.sourceTableId);
    const targetT = tableById.get(head.targetTableId);
    const sourceCx = (sourceT?.position.x ?? 0) + APPROX_TABLE_WIDTH / 2;
    const targetCx = (targetT?.position.x ?? 0) + APPROX_TABLE_WIDTH / 2;
    const sourceOnLeft = sourceCx <= targetCx;
    const sourceSide = sourceOnLeft ? "right" : "left";
    const targetSide = sourceOnLeft ? "left" : "right";
    // Style by origin: physical (default) gets the full primary
    // stroke; cube-link / inferred-fk renders muted + dashed so
    // the visual distinction is unmistakable at a glance.
    const isCubeLink = joins.every(
      (j) => j.origin === "cube-link" || j.origin === "inferred-fk",
    );
    const edgeStyle = isCubeLink
      ? "stroke: hsl(var(--muted-foreground)); stroke-width: 1.5; stroke-dasharray: 6 3; opacity: 0.65;"
      : "stroke: hsl(var(--primary)); stroke-width: 1.5;";
    out.push({
      id: `edge:${head.sourceTableId}::${head.targetTableId}`,
      type: "canvasJoin",
      source: head.sourceTableId,
      target: head.targetTableId,
      sourceHandle: `${head.sourceTableId}:${head.sourceColumnName}:out-${sourceSide}`,
      targetHandle: `${head.targetTableId}:${head.targetColumnName}:in-${targetSide}`,
      style: edgeStyle,
      selected: isSelected,
      // Default xyflow stacking — edges under nodes. True
      // route-around-obstacles requires a custom orthogonal
      // router (queued); zIndex hacks only swap which problem
      // you see.
      data: {
        joins,
        edgeStyle: store.edgeStyle,
        isFaded: focusActive && !joins.some((j) => focusedJoinIds.has(j.id)),
        focusActive,
        // Cube-link joins are read-only on the canvas — the
        // MeasureGroup's DimensionLinks own them.  Delete via
        // the MG editor instead.  `readOnly` reaches the edge
        // component which suppresses delete + drag handles.
        readOnly: isCubeLink,
        onDelete: (joinId: string) => {
          if (isCubeLink) return;
          store.removeJoin(joinId);
        },
        onSelect: (joinId: string) => {
          if (isCubeLink) return;
          store.selectedJoinId = joinId;
        },
      },
    });
  }
  return out;
}
