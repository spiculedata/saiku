/**
 * DimSum tool executors — pure logic lifted out of SchemaCanvasView.svelte
 * (audit finding #1040).
 *
 * Each executor takes the {@link SchemaCanvasStore}, a tool name, and the
 * tool input, mutates the store *through its own methods* (the store stays
 * the mutation boundary — no direct doc mutation here), and returns a JSON
 * string ready to drop into an Anthropic `tool_result` block. Keeping these
 * in a plain `.ts` module makes the whole AI-call surface unit-testable
 * against a constructed store — the point of the finding.
 *
 * The `.svelte` component keeps ownership of reactivity + the agent loop; it
 * imports {@link executeDimSumTool} + {@link buildCanvasSummary} +
 * {@link reconcileMeasureGroupLinks} and calls them from `runDraftWithAI`.
 */
import { SchemaCanvasStore } from './state.svelte.js';
import type { LayoutMode } from './layout.js';
import type { SchemaCanvasCube, SchemaCanvasMeasure, SchemaCanvasMeasureGroup } from './types.js';

/** Tool names that MUTATE the canvas — `runDraftWithAI` tracks whether any
 *  succeeded so it can raise the Confirm/Cancel banner after the loop. */
export const DIMSUM_MUTATION_TOOLS: ReadonlySet<string> = new Set([
	'add_table_to_canvas',
	'remove_table_from_canvas',
	'add_join',
	'remove_join',
	'set_fact_table',
	'create_dimension',
	'add_hierarchy',
	'add_level',
	'add_measure',
	'add_measure_group'
]);

/** Side-effecting dependencies the executor can't own (they touch component
 *  `$state` / the SvelteFlow viewport). Passed in so the executor stays pure
 *  over the store. */
export interface DimSumToolDeps {
	/** Runs the same layout path as the toolbar Arrange button. */
	arrangeCanvas: (mode: LayoutMode) => Promise<void>;
}

/** `schema.name` or bare `name` for a table-shaped record. */
function qname(t: { schema: string | null; name: string }): string {
	return t.schema ? `${t.schema}.${t.name}` : t.name;
}

/**
 * Resolve a `schema.name` or `name` string against the store's on-canvas
 * tables — matches case-insensitively so DimSum doesn't need to worry about
 * UPPER vs lower case.
 */
export function findTableByName(store: SchemaCanvasStore, qualified: string) {
	const q = qualified.trim().toLowerCase();
	return store.doc.tables.find((t) => {
		const full = qname(t).toLowerCase();
		return full === q || t.name.toLowerCase() === q;
	});
}

/** Same as {@link findTableByName} but against the connection's source catalog. */
export function findSourceTableByName(store: SchemaCanvasStore, qualified: string) {
	const q = qualified.trim().toLowerCase();
	return store.sourceTables.find((t) => {
		const full = qname(t).toLowerCase();
		return full === q || t.name.toLowerCase() === q;
	});
}

function tableNameById(store: SchemaCanvasStore, id: string | null | undefined): string | null {
	const t = id ? store.doc.tables.find((x) => x.id === id) : undefined;
	return t ? qname(t) : null;
}

function findDimByName(store: SchemaCanvasStore, n: string) {
	return (store.doc.dimensions ?? []).find((d) => d.name.toLowerCase() === n.trim().toLowerCase());
}

// ── Cube helpers for grouped measure writes ──────────────────────
// DimSum authors measures INTO a measure group on the selected cube
// (the durable doc model), not just the flat `store.measures` list, so
// the workbench + export show a grouped measure.  Pick the first cube
// (creating one if none); reuse the group already bound to the fact, or
// spin up a default one.
function ensureCube(store: SchemaCanvasStore): SchemaCanvasCube {
	return store.cubes[0] ?? store.addCube({ name: 'Cube 1' });
}

function ensureMeasureGroup(
	store: SchemaCanvasStore,
	cube: SchemaCanvasCube,
	factTableId: string
): SchemaCanvasMeasureGroup | null {
	const existing =
		cube.measureGroups.find((g) => g.factTableId === factTableId) ?? cube.measureGroups[0];
	if (existing) return existing;
	return store.addMeasureGroup(cube.id, { name: 'Measures', factTableId });
}

/**
 * Build the JSON canvas snapshot the backend prepends as a synthetic priming
 * turn so Claude has fresh state before it decides whether to call tools.
 * Rebuilt every turn so mid-loop mutations by earlier tool calls reflect back
 * into the next Claude call as well.
 */
export function buildCanvasSummary(store: SchemaCanvasStore): string {
	const tableNameFor = (id: string): string => {
		const t = store.doc.tables.find((x) => x.id === id);
		if (!t) return id;
		return qname(t);
	};
	const canvasTableLines = store.doc.tables.map((t) => {
		const qualified = qname(t);
		const cols = t.columns.map((c) => `${c.name}${c.sqlType ? `:${c.sqlType}` : ''}`).join(', ');
		return `  - ${qualified}: [${cols || '(no columns)'}]`;
	});
	const userMadeJoins = store.doc.joins.filter((j) => (j.origin ?? 'physical') === 'physical');
	const semanticJoins = store.doc.joins.filter(
		(j) => j.origin === 'cube-link' || j.origin === 'inferred-fk'
	);
	const userMadeLines = userMadeJoins.map(
		(j) =>
			`  - ${tableNameFor(j.sourceTableId)}.${j.sourceColumnName}  ↔  ${tableNameFor(
				j.targetTableId
			)}.${j.targetColumnName}`
	);
	const semanticLines = semanticJoins.map(
		(j) =>
			`  - ${tableNameFor(j.sourceTableId)}.${j.sourceColumnName}  ↔  ${tableNameFor(
				j.targetTableId
			)}.${j.targetColumnName}  (origin: ${j.origin ?? 'inferred-fk'})`
	);
	return [
		`ON-CANVAS TABLES (${store.doc.tables.length}):`,
		...(canvasTableLines.length === 0 ? ['  (none)'] : canvasTableLines),
		'',
		`USER-MADE JOINS (${userMadeJoins.length}):`,
		...(userMadeLines.length === 0 ? ['  (none)'] : userMadeLines),
		'',
		`SEMANTIC (cube-link, read-only) JOINS (${semanticJoins.length}):`,
		...(semanticLines.length === 0 ? ['  (none)'] : semanticLines)
	].join('\n');
}

/**
 * Link every dimension to each measure group bound to a fact table (the M4
 * `<ForeignKeyLink>`s). Without these a DimSum-built cube shows "LINKED
 * DIMENSIONS" all unchecked and the group is incomplete. Resolve the
 * fact-side FK column, in order:
 *   1. `dim.foreignKey` (set by create_dimension's foreignKeyColumn);
 *   2. a canvas join between the fact and the dimension's table;
 *   3. star-schema convention — a fact column whose name matches the
 *      dimension's primary key (`product_id` on both sides) or `<table>_id`.
 * A dimension on the fact table itself links as a degenerate `fact` link.
 * Idempotent — setMeasureGroupDimLink upserts by dimensionId.
 */
export function reconcileMeasureGroupLinks(store: SchemaCanvasStore): void {
	for (const cube of store.cubes) {
		for (const mg of cube.measureGroups) {
			const factId = mg.factTableId ?? null;
			if (!factId) continue;
			const factTable = store.doc.tables.find((t) => t.id === factId);
			const factCol = (name: string | null | undefined): string | null => {
				if (!name || !factTable) return null;
				return (
					factTable.columns.find((c) => c.name.toLowerCase() === name.toLowerCase())?.name ?? null
				);
			};
			for (const dim of store.doc.dimensions ?? []) {
				const dimTableId = dim.primaryKeyTableId ?? dim.sourceTableId ?? null;
				let fk = dim.foreignKey ?? null;
				if (!fk && dimTableId) {
					for (const j of store.doc.joins) {
						if (j.sourceTableId === factId && j.targetTableId === dimTableId) {
							fk = j.sourceColumnName;
							break;
						}
						if (j.targetTableId === factId && j.sourceTableId === dimTableId) {
							fk = j.targetColumnName;
							break;
						}
					}
				}
				if (!fk && dimTableId !== factId) {
					// Star-schema fallback: the fact usually carries a column named
					// like the dimension's key (product_id) or `<dim table>_id`.
					const dimTable = store.doc.tables.find((t) => t.id === dimTableId);
					fk = factCol(dim.primaryKey) ?? (dimTable ? factCol(`${dimTable.name}_id`) : null);
				}
				if (!fk) continue;
				store.setMeasureGroupDimLink(cube.id, mg.id, {
					dimensionId: dim.id,
					foreignKeyColumn: fk,
					linkKind: dimTableId === factId ? 'fact' : 'foreign-key'
				});
			}
		}
	}
}

/**
 * Execute one DimSum tool call locally against the store. Returns a JSON
 * string ready to be dropped into an Anthropic tool_result block, plus an
 * `isError` flag.
 */
export async function executeDimSumTool(
	store: SchemaCanvasStore,
	name: string,
	input: Record<string, unknown>,
	deps: DimSumToolDeps
): Promise<{ content: string; isError: boolean }> {
	try {
		if (name === 'list_tables') {
			const onCanvas = store.doc.tables.map((t) => ({
				qualifiedName: qname(t),
				columnCount: t.columns.length
			}));
			const onCanvasNames = new Set(onCanvas.map((t) => t.qualifiedName.toLowerCase()));
			const available = store.sourceTables
				.map((t) => ({
					qualifiedName: qname(t),
					columnCount: t.columns.length
				}))
				.filter((t) => !onCanvasNames.has(t.qualifiedName.toLowerCase()));
			return {
				content: JSON.stringify({ onCanvas, available }),
				isError: false
			};
		}
		if (name === 'describe_table') {
			const qualified = String(input.qualifiedName ?? '');
			const t = findTableByName(store, qualified) ?? findSourceTableByName(store, qualified);
			if (!t) {
				return {
					content: JSON.stringify({
						error: `No table named "${qualified}" is on the canvas or in the connection catalog.`
					}),
					isError: true
				};
			}
			const canvasT = findTableByName(store, qualified);
			const joinsInvolving = canvasT
				? store.doc.joins
						.filter((j) => j.sourceTableId === canvasT.id || j.targetTableId === canvasT.id)
						.map((j) => {
							const other =
								j.sourceTableId === canvasT.id
									? store.doc.tables.find((tt) => tt.id === j.targetTableId)
									: store.doc.tables.find((tt) => tt.id === j.sourceTableId);
							return {
								neighbor: other ? qname(other) : '(unknown)',
								thisColumn:
									j.sourceTableId === canvasT.id ? j.sourceColumnName : j.targetColumnName,
								neighborColumn:
									j.sourceTableId === canvasT.id ? j.targetColumnName : j.sourceColumnName,
								origin: j.origin ?? 'physical'
							};
						})
				: [];
			return {
				content: JSON.stringify({
					qualifiedName: qname(t),
					onCanvas: !!canvasT,
					columns: t.columns.map((c) => ({ name: c.name, sqlType: c.sqlType })),
					joinsInvolving
				}),
				isError: false
			};
		}
		if (name === 'list_joins') {
			const tableNameFor = (id: string) => {
				const t = store.doc.tables.find((x) => x.id === id);
				if (!t) return id;
				return qname(t);
			};
			const userMade: Array<{
				from: { table: string; column: string };
				to: { table: string; column: string };
			}> = [];
			const semantic: Array<{
				from: { table: string; column: string };
				to: { table: string; column: string };
				origin: string;
			}> = [];
			for (const j of store.doc.joins) {
				const dto = {
					from: {
						table: tableNameFor(j.sourceTableId),
						column: j.sourceColumnName
					},
					to: {
						table: tableNameFor(j.targetTableId),
						column: j.targetColumnName
					}
				};
				if ((j.origin ?? 'physical') === 'physical') userMade.push(dto);
				else semantic.push({ ...dto, origin: j.origin ?? 'inferred-fk' });
			}
			return {
				content: JSON.stringify({ userMade, semantic }),
				isError: false
			};
		}
		if (name === 'add_table_to_canvas') {
			const qualified = String(input.qualifiedName ?? '');
			const existing = findTableByName(store, qualified);
			if (existing) {
				return {
					content: JSON.stringify({
						added: false,
						reason: 'already_on_canvas',
						tableId: existing.id
					}),
					isError: false
				};
			}
			const candidate = findSourceTableByName(store, qualified);
			if (!candidate) {
				return {
					content: JSON.stringify({
						added: false,
						reason: `No table named "${qualified}" in the connection catalog.`
					}),
					isError: true
				};
			}
			// Position new tables in a rough grid to the right of existing ones.
			const rightmost = store.doc.tables.reduce((mx, t) => Math.max(mx, t.position.x + 200), 0);
			const added = store.addTable(candidate, {
				x: rightmost + 40,
				y: 80 + (store.doc.tables.length % 4) * 240
			});
			return {
				content: JSON.stringify({
					added: true,
					tableId: added.id,
					qualifiedName: qname(added)
				}),
				isError: false
			};
		}
		if (name === 'remove_table_from_canvas') {
			const qualified = String(input.qualifiedName ?? '');
			const t = findTableByName(store, qualified);
			if (!t) {
				return {
					content: JSON.stringify({
						removed: false,
						reason: `No table "${qualified}" on canvas.`
					}),
					isError: true
				};
			}
			const joinsRemoved = store.doc.joins.filter(
				(j) => j.sourceTableId === t.id || j.targetTableId === t.id
			).length;
			store.removeTable(t.id);
			return {
				content: JSON.stringify({ removed: true, joinsRemoved }),
				isError: false
			};
		}
		if (name === 'add_join') {
			const fromIn = (input.from ?? {}) as {
				table?: unknown;
				column?: unknown;
			};
			const toIn = (input.to ?? {}) as { table?: unknown; column?: unknown };
			const fromTable = findTableByName(store, String(fromIn.table ?? ''));
			const toTable = findTableByName(store, String(toIn.table ?? ''));
			if (!fromTable || !toTable) {
				return {
					content: JSON.stringify({
						added: false,
						reason: `Both tables must be on the canvas first. Missing: ${
							!fromTable ? fromIn.table : ''
						}${!fromTable && !toTable ? ', ' : ''}${!toTable ? toIn.table : ''}`
					}),
					isError: true
				};
			}
			const fromCol = String(fromIn.column ?? '');
			const toCol = String(toIn.column ?? '');
			if (!fromTable.columns.some((c) => c.name === fromCol)) {
				return {
					content: JSON.stringify({
						added: false,
						reason: `Column "${fromCol}" not found on ${fromTable.name}.`
					}),
					isError: true
				};
			}
			if (!toTable.columns.some((c) => c.name === toCol)) {
				return {
					content: JSON.stringify({
						added: false,
						reason: `Column "${toCol}" not found on ${toTable.name}.`
					}),
					isError: true
				};
			}
			const created = store.addJoin({
				sourceTableId: fromTable.id,
				sourceColumnName: fromCol,
				targetTableId: toTable.id,
				targetColumnName: toCol,
				kind: 'inner',
				origin: 'physical'
			});
			return {
				content: JSON.stringify({ added: true, joinId: created.id }),
				isError: false
			};
		}
		if (name === 'remove_join') {
			const fromIn = (input.from ?? {}) as {
				table?: unknown;
				column?: unknown;
			};
			const toIn = (input.to ?? {}) as { table?: unknown; column?: unknown };
			const fromTable = findTableByName(store, String(fromIn.table ?? ''));
			const toTable = findTableByName(store, String(toIn.table ?? ''));
			if (!fromTable || !toTable) {
				return {
					content: JSON.stringify({
						removed: false,
						reason: 'One or both tables not on canvas.'
					}),
					isError: true
				};
			}
			const target = store.doc.joins.find(
				(j) =>
					(j.origin ?? 'physical') === 'physical' &&
					((j.sourceTableId === fromTable.id &&
						j.sourceColumnName === fromIn.column &&
						j.targetTableId === toTable.id &&
						j.targetColumnName === toIn.column) ||
						(j.sourceTableId === toTable.id &&
							j.sourceColumnName === toIn.column &&
							j.targetTableId === fromTable.id &&
							j.targetColumnName === fromIn.column))
			);
			if (!target) {
				return {
					content: JSON.stringify({
						removed: false,
						reason: 'No matching physical join found (semantic cube-links are read-only).'
					}),
					isError: true
				};
			}
			store.removeJoin(target.id);
			return { content: JSON.stringify({ removed: true }), isError: false };
		}
		if (name === 'arrange_canvas') {
			// Same code path as the toolbar Arrange button — reuses the
			// component's handleLayout via deps so layout tweaks stay in one place.
			const mode = (String(input.mode ?? 'star') as LayoutMode) || 'star';
			if (store.doc.tables.length === 0) {
				return {
					content: JSON.stringify({
						arranged: false,
						reason: 'No tables on canvas to arrange.'
					}),
					isError: false
				};
			}
			await deps.arrangeCanvas(mode);
			return {
				content: JSON.stringify({
					arranged: true,
					mode,
					tableCount: store.doc.tables.length
				}),
				isError: false
			};
		}
		if (name === 'perform_action') {
			// Generic action registry — every button that DimSum should
			// be able to trigger has an id here.  Adding a new UI
			// button = add a case here + list it in the system prompt
			// server-side.  Keeps the "AI parity with every button"
			// principle in one place.
			const actionId = String(input.action ?? '');
			switch (actionId) {
				case 'zoom_in':
				case 'zoom_out':
				case 'fit_view':
				case 'zoom_to_100':
				case 'center_view':
					store.requestedCanvasAction = { kind: actionId, ts: Date.now() };
					return {
						content: JSON.stringify({ performed: actionId }),
						isError: false
					};
				case 'undo_last_change':
					if (!store.previousDoc) {
						return {
							content: JSON.stringify({
								performed: false,
								reason:
									'No prior state to undo — nothing has changed since the last confirmed edit.'
							}),
							isError: false
						};
					}
					store.undo();
					return {
						content: JSON.stringify({ performed: 'undo_last_change' }),
						isError: false
					};
				case 'center_view_on_table': {
					const qualified = String(input.qualifiedName ?? '');
					const t = findTableByName(store, qualified);
					if (!t) {
						return {
							content: JSON.stringify({
								error: `No canvas table named "${qualified}" — add it first, or check the spelling.`
							}),
							isError: true
						};
					}
					store.requestedJumpTarget = { tableId: t.id, ts: Date.now() };
					return {
						content: JSON.stringify({
							performed: 'center_view_on_table',
							qualifiedName: qualified
						}),
						isError: false
					};
				}
				default:
					return {
						content: JSON.stringify({
							error: `Unknown action id: "${actionId}". Available: zoom_in, zoom_out, fit_view, zoom_to_100, center_view, undo_last_change, center_view_on_table.`
						}),
						isError: true
					};
			}
		}
		// ── Logical layer (Mondrian 4 cube: dimensions, hierarchies,
		//    levels, fact + measures) ──────────────────────────────────
		if (name === 'list_dimensions') {
			const fact = store.doc.tables.find((t) => t.role === 'fact');
			const dimensions = (store.doc.dimensions ?? []).map((d) => ({
				name: d.name,
				table: tableNameById(store, d.primaryKeyTableId),
				primaryKey: d.primaryKey ?? null,
				foreignKey: d.foreignKey ?? null,
				type: d.dimensionType ?? 'Standard',
				hierarchies: d.hierarchies.map((h) => ({
					name: h.name,
					hasAll: h.hasAll,
					levels: h.levels.map((l) => l.name)
				}))
			}));
			const measures = (store.doc.measures ?? []).map((m) => ({
				name: m.name,
				column: m.columnName,
				aggregator: m.aggregator,
				table: tableNameById(store, m.tableId)
			}));
			return {
				content: JSON.stringify({
					factTable: fact ? qname(fact) : null,
					dimensions,
					measures
				}),
				isError: false
			};
		}
		if (name === 'set_fact_table') {
			const t = findTableByName(store, String(input.qualifiedName ?? ''));
			if (!t) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `No table "${input.qualifiedName}" on canvas — add_table_to_canvas first.`
					}),
					isError: true
				};
			}
			store.setTableRole(t.id, 'fact');
			return {
				content: JSON.stringify({ ok: true, factTable: qname(t) }),
				isError: false
			};
		}
		if (name === 'create_dimension') {
			const t = findTableByName(store, String(input.table ?? ''));
			if (!t) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `No table "${input.table}" on canvas — add_table_to_canvas first.`
					}),
					isError: true
				};
			}
			const pk = String(input.primaryKeyColumn ?? '');
			if (pk && !t.columns.some((c) => c.name === pk)) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `Primary-key column "${pk}" not found on ${t.name}.`
					}),
					isError: true
				};
			}
			const dimName = input.name ? String(input.name) : undefined;
			if (dimName && findDimByName(store, dimName)) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `Dimension "${dimName}" already exists.`
					}),
					isError: true
				};
			}
			const dim = store.addDimension({ name: dimName, tableId: t.id });
			const patch: Record<string, unknown> = { primaryKeyTableId: t.id };
			if (pk) patch.primaryKey = pk;
			if (input.foreignKeyColumn) patch.foreignKey = String(input.foreignKeyColumn);
			if (input.type) patch.dimensionType = String(input.type);
			store.updateDimension(dim.id, patch);
			// Seed the primary-key column as an attribute so the dimension
			// has a resolvable KEY (resolveKeyAttribute matches an attribute
			// whose columnName === dim.primaryKey) — otherwise the workbench
			// shows "KEY MISSING" and 0 attributes.
			if (pk) store.addAttribute(dim.id, t.id, pk);
			// Link this dimension into any existing measure group (covers the
			// dimensions-after-measures order) + refresh the workbench.
			reconcileMeasureGroupLinks(store);
			store.bumpWorkbenchReload();
			return {
				content: JSON.stringify({
					ok: true,
					dimension: dim.name,
					table: qname(t),
					primaryKey: pk || null,
					foreignKey: input.foreignKeyColumn ? String(input.foreignKeyColumn) : null
				}),
				isError: false
			};
		}
		if (name === 'add_hierarchy') {
			const d = findDimByName(store, String(input.dimension ?? ''));
			if (!d) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `No dimension "${input.dimension}" — create_dimension first.`
					}),
					isError: true
				};
			}
			const h = store.addHierarchy(d.id, input.name ? String(input.name) : undefined);
			if (h && input.hasAll === false) store.updateHierarchy(d.id, h.id, { hasAll: false });
			return {
				content: JSON.stringify({
					ok: true,
					dimension: d.name,
					hierarchy: h?.name
				}),
				isError: false
			};
		}
		if (name === 'add_level') {
			const d = findDimByName(store, String(input.dimension ?? ''));
			if (!d) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `No dimension "${input.dimension}" — create_dimension first.`
					}),
					isError: true
				};
			}
			const h = d.hierarchies.find(
				(x) =>
					x.name.toLowerCase() ===
					String(input.hierarchy ?? '')
						.trim()
						.toLowerCase()
			);
			if (!h) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `No hierarchy "${input.hierarchy}" on ${d.name} — add_hierarchy first.`
					}),
					isError: true
				};
			}
			const dimTable = store.doc.tables.find((x) => x.id === d.primaryKeyTableId);
			const col = String(input.column ?? '');
			if (!dimTable || !dimTable.columns.some((c) => c.name === col)) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `Column "${col}" not found on the dimension's table ${dimTable?.name ?? '(unknown)'}.`
					}),
					isError: true
				};
			}
			const lvl = store.addLevel(d.id, h.id, {
				tableId: dimTable.id,
				columnName: col,
				name: input.name ? String(input.name) : undefined
			});
			// M4 levels reference an attribute — materialise the column as a
			// dimension attribute so the level resolves and the Attrs count
			// reflects it.
			store.addAttribute(d.id, dimTable.id, col);
			return {
				content: JSON.stringify({
					ok: true,
					dimension: d.name,
					hierarchy: h.name,
					level: lvl?.name
				}),
				isError: false
			};
		}
		if (name === 'add_measure') {
			const fact = store.doc.tables.find((t) => t.role === 'fact');
			if (!fact) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: 'No fact table set — call set_fact_table first.'
					}),
					isError: true
				};
			}
			const agg = String(input.aggregator ?? 'sum');
			const col = input.column ? String(input.column) : '';
			if (agg !== 'count' && !fact.columns.some((c) => c.name === col)) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: `Column "${col}" not found on the fact table ${fact.name}.`
					}),
					isError: true
				};
			}
			// Percentile fraction (0–100) — only for aggregator 'percentile'
			// (median is the implicit 50th). Clamped; invalid/absent → default 50.
			const percentile =
				agg === 'percentile' && input.percentile != null && !Number.isNaN(Number(input.percentile))
					? Math.max(0, Math.min(100, Math.round(Number(input.percentile))))
					: undefined;
			const m = store.addMeasure({
				tableId: fact.id,
				// count(*) has no column; leave it empty so the emitter drops it.
				columnName: col,
				aggregator: agg as SchemaCanvasMeasure['aggregator'],
				name: input.name ? String(input.name) : col ? undefined : 'Row count',
				percentile
			});
			// Also fold the column into a measure group on the selected
			// cube so the workbench + export show a grouped measure.  A
			// count(*) row-count measure has no column — skip the group
			// add for it (nothing to fold).
			let measureGroupName: string | null = null;
			if (col) {
				const cube = ensureCube(store);
				const mg = ensureMeasureGroup(store, cube, fact.id);
				if (mg) {
					if (!mg.measureColumns.includes(col)) {
						store.toggleMeasureColumn(cube.id, mg.id, col);
					}
					measureGroupName = mg.name;
				}
			}
			reconcileMeasureGroupLinks(store);
			store.bumpWorkbenchReload();
			return {
				content: JSON.stringify({
					ok: true,
					measure: m.name,
					aggregator: agg,
					measureGroup: measureGroupName
				}),
				isError: false
			};
		}
		if (name === 'add_measure_group') {
			const fact = store.doc.tables.find((t) => t.role === 'fact');
			if (!fact) {
				return {
					content: JSON.stringify({
						ok: false,
						reason: 'No fact table set — call set_fact_table first.'
					}),
					isError: true
				};
			}
			const cube = ensureCube(store);
			const mg = store.addMeasureGroup(cube.id, {
				name: input.name ? String(input.name) : undefined,
				factTableId: fact.id
			});
			reconcileMeasureGroupLinks(store);
			store.bumpWorkbenchReload();
			return {
				content: JSON.stringify({
					ok: true,
					measureGroup: mg?.name ?? null,
					cube: cube.name,
					factTable: qname(fact)
				}),
				isError: false
			};
		}
		return {
			content: JSON.stringify({ error: `Unknown tool: ${name}` }),
			isError: true
		};
	} catch (err) {
		return {
			content: JSON.stringify({
				error: err instanceof Error ? err.message : 'tool execution threw'
			}),
			isError: true
		};
	}
}
