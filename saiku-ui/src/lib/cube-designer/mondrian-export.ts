/**
 * Canvas schema designer — Mondrian 4 export (saiku-cloud#1080).
 *
 * Translates a SchemaCanvasState into a Mondrian **4** schema — as XML or, via
 * {@link mondrianXmlToYaml}, as M4 YAML. Saiku Cloud emits Mondrian 4 only; the
 * legacy M3 emitter (`<Dimension foreignKey>` / `<Hierarchy primaryKey>` /
 * inline `<Table>`) has been retired.
 *
 * M4 shape (matches canonical `mondrian-saiku demo/FoodMart.{mondrian.xml,yaml}`
 * and the canvas Code-tab preview):
 *   - `<PhysicalSchema>` lists every table; dimension tables declare their PK
 *     (`keyColumn`), fact tables declare none.
 *   - Shared `<Dimension key="…">` elements carry `<Attributes>` (incl. the key
 *     attribute whose `keyColumn` is the PK) + `<Hierarchies>`.
 *   - The `<Cube>` references dimensions by `source` and binds the fact via a
 *     `<MeasureGroup>` whose `<DimensionLinks><ForeignKeyLink foreignKeyColumn>`
 *     resolves to the dimension key.
 *
 * Caveats: one cube per canvas (v1); cube-scope calculated members are not
 * exported yet (that state lives in the workbench, not the canvas doc).
 */
import { mondrianXmlToYaml } from './mondrian-xml-to-yaml.js';
import type {
	SchemaCanvasState,
	SchemaCanvasTable,
	SchemaCanvasJoin,
	SchemaCanvasDimension,
	SchemaCanvasHierarchy,
	SchemaCanvasMeasure,
	SchemaCanvasCube,
	SchemaCanvasCalc
} from './types.js';

function escape(s: string): string {
	return s
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;');
}

function attr(name: string, value: string | undefined | null): string {
	return value && value.length > 0 ? ` ${name}="${escape(value)}"` : '';
}

/**
 * Render a Mondrian `<Annotations>` block for the `saiku.semantic.*` keys on an
 * element (measure / level / dimension). Returns [] when there's nothing to
 * emit — so a callable can keep the element self-closing. Keys are stored bare
 * (e.g. `description`, `pii`) and namespaced here.
 */
function annotationLines(
	annotations: Record<string, string> | undefined,
	indent: string
): string[] {
	if (!annotations) return [];
	const entries = Object.entries(annotations).filter(
		([, v]) => v != null && String(v).trim() !== ''
	);
	if (entries.length === 0) return [];
	const out = [`${indent}<Annotations>`];
	for (const [k, v] of entries) {
		out.push(
			`${indent}  <Annotation name="saiku.semantic.${escape(k)}">${escape(String(v))}</Annotation>`
		);
	}
	out.push(`${indent}</Annotations>`);
	return out;
}

function titleCase(s: string): string {
	return s
		.split(/[._]/)
		.map((part) => part.charAt(0).toUpperCase() + part.slice(1))
		.join(' ');
}

const AGGREGATOR_TO_MONDRIAN: Record<SchemaCanvasMeasure['aggregator'], string> = {
	sum: 'sum',
	count: 'count',
	avg: 'avg',
	min: 'min',
	max: 'max',
	'distinct-count': 'distinct-count',
	// Non-additive leaf aggregators (mondrian-saiku #104) — pushed to SQL as
	// PERCENTILE_CONT. `median` is the implicit 50th percentile; `percentile`
	// carries a `percentile="0..100"` attribute (emitted below).
	median: 'median',
	percentile: 'percentile'
};

/**
 * Render the `percentile="N"` attribute for a percentile measure. Median needs
 * none (it's the implicit 50th). Omitted when not a percentile aggregator or
 * when no value is set — Mondrian defaults percentile to 50.
 */
function percentileAttr(m: SchemaCanvasMeasure | undefined): string {
	if (!m || m.aggregator !== 'percentile') return '';
	const v = m.percentile;
	if (v === null || v === undefined) return '';
	return ` percentile="${escape(String(v))}"`;
}

/** Per-dimension resolution: the tables + columns M4 needs to wire it up. */
interface DimResolution {
	dim: SchemaCanvasDimension;
	table: SchemaCanvasTable | undefined;
	/** dimension-side PK column (target of the fact's FK). */
	pkColumn: string | null;
	/** fact-side FK column. */
	fkColumn: string | null;
	/** name of the key attribute (referenced by `<Dimension key=...>`). */
	keyAttrName: string | null;
}

function resolveDimension(
	dim: SchemaCanvasDimension,
	tables: SchemaCanvasTable[],
	joins: SchemaCanvasJoin[],
	factTableId: string
): DimResolution {
	const table = tables.find((t) => t.id === (dim.primaryKeyTableId ?? dim.sourceTableId));

	// FK (fact side) + PK (dim side): explicit dim fields win, else derive from
	// the canvas join between the fact and this dim's table.
	let fkColumn = dim.foreignKey ?? null;
	let pkColumn = resolvePkColumn(dim);
	if ((!fkColumn || !pkColumn) && table) {
		for (const j of joins) {
			if (j.sourceTableId === factTableId && j.targetTableId === table.id) {
				fkColumn = fkColumn ?? j.sourceColumnName;
				pkColumn = pkColumn ?? j.targetColumnName;
				break;
			}
			if (j.targetTableId === factTableId && j.sourceTableId === table.id) {
				fkColumn = fkColumn ?? j.targetColumnName;
				pkColumn = pkColumn ?? j.sourceColumnName;
				break;
			}
		}
	}

	// Key attribute: reuse an attribute already bound to the PK column, else a
	// synthetic one gets emitted (see emitDimension).
	const existing = pkColumn
		? (dim.attributes ?? []).find((a) => a.columnName === pkColumn)
		: undefined;
	const keyAttrName = pkColumn
		? (existing?.name ?? existing?.columnName ?? `${dim.name} Key`)
		: null;

	return { dim, table, pkColumn, fkColumn, keyAttrName };
}

/** `dim.primaryKey` stores an attribute's logical NAME; resolve it to a column. */
function resolvePkColumn(dim: SchemaCanvasDimension): string | null {
	if (!dim.primaryKey) return null;
	const byName = (dim.attributes ?? []).find((a) => (a.name ?? a.columnName) === dim.primaryKey);
	return byName?.columnName ?? dim.primaryKey;
}

interface SynthAttr {
	name: string;
	keyColumn?: string;
	keyColumns?: string[];
	isKey?: boolean;
}

function emitDimension(r: DimResolution): string[] {
	const { dim, table, pkColumn, keyAttrName } = r;
	const lines: string[] = [];

	// Build the full attribute set. M4 <Level attribute="X"/> references an
	// attribute by NAME, so every hierarchy level needs a backing attribute —
	// synthesise one for any level (or the key) not already declared.
	const attrs = new Map<string, SynthAttr>();
	const explicit = dim.attributes ?? [];
	if (keyAttrName && pkColumn && !explicit.some((a) => a.columnName === pkColumn)) {
		attrs.set(keyAttrName, {
			name: keyAttrName,
			keyColumn: pkColumn,
			isKey: true
		});
	}
	for (const a of explicit) {
		const name = a.name ?? a.columnName;
		attrs.set(name, {
			name,
			keyColumn: a.keyColumns && a.keyColumns.length > 1 ? undefined : a.columnName,
			keyColumns: a.keyColumns && a.keyColumns.length > 1 ? a.keyColumns : undefined
		});
	}
	// Resolve each hierarchy's levels to attribute names, synthesising as needed.
	// Carry the level's caption alongside so the <Level> emit can round-trip it.
	const hierarchyLevels: {
		h: SchemaCanvasHierarchy;
		levels: { name: string; caption?: string }[];
	}[] = [];
	for (const h of dim.hierarchies) {
		const levels: { name: string; caption?: string }[] = [];
		for (const lvl of h.levels) {
			const fromCol = explicit.find((a) => a.columnName === lvl.columnName);
			const name = fromCol?.name ?? fromCol?.columnName ?? lvl.name;
			if (!attrs.has(name)) attrs.set(name, { name, keyColumn: lvl.columnName });
			levels.push({ name, caption: lvl.caption });
		}
		hierarchyLevels.push({ h, levels });
	}

	const tableAttr = table ? ` table="${escape(table.name)}"` : '';
	const keyAttr = keyAttrName ? ` key="${escape(keyAttrName)}"` : '';
	// Mondrian 4 spells a time dimension `type="TIME"` (M3 used "TimeDimension").
	const typeAttr = dim.dimensionType === 'Time' ? ' type="TIME"' : '';
	lines.push(
		`  <Dimension name="${escape(dim.name)}"${tableAttr}${keyAttr}${typeAttr}` +
			`${attr('caption', dim.caption)}${attr('description', dim.description)}>`
	);

	// M4 carries a level's levelType on its <Attribute>; map marked columns.
	const legacyLevelTypeByColumn = new Map<string, string>();
	for (const h of dim.hierarchies) {
		for (const lvl of h.levels) {
			if (lvl.levelType) legacyLevelTypeByColumn.set(lvl.columnName, lvl.levelType);
		}
	}

	if (attrs.size > 0) {
		lines.push('    <Attributes>');
		for (const a of attrs.values()) {
			const hasHier = a.isKey ? ' hasHierarchy="false"' : '';
			const lt = a.keyColumn ? legacyLevelTypeByColumn.get(a.keyColumn) : undefined;
			const ltAttr = lt ? ` levelType="${escape(lt)}"` : '';
			if (a.keyColumns) {
				lines.push(`      <Attribute name="${escape(a.name)}"${hasHier}>`);
				lines.push('        <Key>');
				for (const c of a.keyColumns) lines.push(`          <Column name="${escape(c)}"/>`);
				lines.push('        </Key>');
				lines.push('      </Attribute>');
			} else {
				lines.push(
					`      <Attribute name="${escape(a.name)}" keyColumn="${escape(a.keyColumn ?? a.name)}"${hasHier}${ltAttr} />`
				);
			}
		}
		lines.push('    </Attributes>');
	}

	// Hierarchies — emit authored ones; if none, synthesise a single hierarchy
	// over the non-key attributes so the dimension is sliceable.
	if (hierarchyLevels.length > 0) {
		lines.push('    <Hierarchies>');
		for (const { h, levels } of hierarchyLevels) {
			lines.push(
				`      <Hierarchy name="${escape(h.name)}" hasAll="${h.hasAll ? 'true' : 'false'}"` +
					`${attr('allMemberName', h.allMemberName)}${attr('defaultMember', h.defaultMember)}>`
			);
			for (const l of levels)
				lines.push(`        <Level attribute="${escape(l.name)}"${attr('caption', l.caption)} />`);
			lines.push('      </Hierarchy>');
		}
		lines.push('    </Hierarchies>');
	} else {
		const levelNames = [...attrs.values()]
			.filter((a) => !a.isKey && a.name !== keyAttrName)
			.map((a) => a.name);
		const levels = levelNames.length > 0 ? levelNames : keyAttrName ? [keyAttrName] : [];
		if (levels.length > 0) {
			lines.push('    <Hierarchies>');
			lines.push(`      <Hierarchy name="${escape(dim.name)}" hasAll="true">`);
			for (const n of levels) lines.push(`        <Level attribute="${escape(n)}" />`);
			lines.push('      </Hierarchy>');
			lines.push('    </Hierarchies>');
		}
	}
	lines.push('  </Dimension>');
	return lines;
}

/**
 * Fallback dimensions for a canvas where the workbench hasn't curated any —
 * one per non-fact table joined to the fact. Emitted in the SAME M4 shape.
 */
function inferDimensions(
	fact: SchemaCanvasTable,
	tables: SchemaCanvasTable[],
	joins: SchemaCanvasJoin[]
): SchemaCanvasDimension[] {
	const dims: SchemaCanvasDimension[] = [];
	for (const t of tables) {
		if (t.id === fact.id) continue;
		const joined = joins.some(
			(j) =>
				(j.sourceTableId === fact.id && j.targetTableId === t.id) ||
				(j.targetTableId === fact.id && j.sourceTableId === t.id)
		);
		if (!joined) continue;
		dims.push({
			id: `inferred-${t.id}`,
			name: titleCase(t.name),
			sourceTableId: t.id,
			primaryKeyTableId: t.id,
			attributes: t.columns.map((c) => ({ tableId: t.id, columnName: c.name })),
			hierarchies: []
		} as SchemaCanvasDimension);
	}
	return dims;
}

/**
 * Render a calculated member's Mondrian formula.  Mirrors the workbench's
 * `renderCalcTokens` / `calcMode` so the exported `<CalculatedMember>`
 * matches the Code-tab preview exactly.
 */
function renderCalcFormula(c: SchemaCanvasCalc): string {
	const mode = c.mode ?? 'build';
	if (mode === 'expression') return (c.formula ?? '').trim();
	return c.tokens
		.map((t) => (t.kind === 'measure' ? `[${t.name ?? ''}]` : ` ${t.op ?? '+'} `))
		.join('')
		.replace(/\s+/g, ' ')
		.trim();
}

/**
 * Cubes-based export (matches `workbenchToMondrianPreview`): emits one
 * `<Cube>` per `state.cubes[]` entry, each with `<Dimensions>` refs, one
 * `<MeasureGroup>` per group (measures resolved from `state.measures`,
 * `<DimensionLinks>` from the group's `dimensionLinks`) and cube-scope
 * `<CalculatedMembers>`.  Used whenever the doc carries cubes; otherwise
 * {@link exportSingleFactFallback} keeps the legacy single-fact behaviour.
 */
function exportCubesBased(state: SchemaCanvasState, cubes: SchemaCanvasCube[]): string {
	const tables = state.tables;
	const dims = state.dimensions ?? [];
	const measures = state.measures ?? [];
	const schemaName = state.label.trim() || 'Untitled';

	// Dimension tables — one physical <Table> each (keyColumn = its PK).
	const dimTables = new Map<string, SchemaCanvasTable>();
	for (const d of dims) {
		const dSrc = d.sourceTableId ?? d.primaryKeyTableId;
		if (!dSrc) continue;
		const t = tables.find((x) => x.id === dSrc);
		if (t) dimTables.set(t.id, t);
	}

	// Per-cube fact fallback: the first MG carrying a factTableId.
	const legacyFact = tables.find((t) => t.role === 'fact') ?? null;
	const cubeFactOf = (c: SchemaCanvasCube): string | null =>
		c.measureGroups.find((mg) => mg.factTableId)?.factTableId ?? legacyFact?.id ?? null;

	// Every fact table referenced by any cube / measure group.
	const factTables = new Map<string, SchemaCanvasTable>();
	if (legacyFact) factTables.set(legacyFact.id, legacyFact);
	for (const c of cubes) {
		const cf = cubeFactOf(c);
		if (cf) {
			const t = tables.find((x) => x.id === cf);
			if (t) factTables.set(t.id, t);
		}
		for (const mg of c.measureGroups) {
			if (mg.factTableId) {
				const t = tables.find((x) => x.id === mg.factTableId);
				if (t) factTables.set(t.id, t);
			}
		}
	}

	// Dimension-table PK column (#1080) — used by BOTH the PhysicalSchema
	// keyColumn and the <Dimension key>.  Prefer explicit dim.primaryKey,
	// else the dim-side column of a join to a fact, else an id-like guess.
	const dimTablePk = new Map<string, string>();
	for (const dim of dims) {
		const bt = tables.find((t) => t.id === (dim.sourceTableId ?? dim.primaryKeyTableId));
		if (!bt || dimTablePk.has(bt.id)) continue;
		let pk: string | null = dim.primaryKey ?? null;
		if (!pk) {
			for (const j of state.joins) {
				if (j.sourceTableId === bt.id && factTables.has(j.targetTableId)) {
					pk = j.sourceColumnName;
					break;
				}
				if (j.targetTableId === bt.id && factTables.has(j.sourceTableId)) {
					pk = j.targetColumnName;
					break;
				}
			}
		}
		if (!pk) {
			const cols = bt.columns ?? [];
			const name = bt.name.toLowerCase();
			const idCol =
				cols.find((c) => c.name.toLowerCase() === `${name}_id`) ??
				cols.find((c) => c.name.toLowerCase() === 'id') ??
				cols.find((c) => /_id$/i.test(c.name));
			if (idCol) pk = idCol.name;
		}
		if (pk) dimTablePk.set(bt.id, pk);
	}

	const out: string[] = [];
	out.push('<?xml version="1.0" encoding="UTF-8"?>');
	out.push(`<Schema name="${escape(schemaName)}" metamodelVersion="4.0">`);

	// ── PhysicalSchema — facts (no key) + dim tables (keyColumn = PK) ──
	out.push('  <PhysicalSchema>');
	for (const ft of factTables.values()) {
		out.push(
			`    <Table name="${escape(ft.name)}"${ft.schema ? ` schema="${escape(ft.schema)}"` : ''} />`
		);
	}
	for (const t of dimTables.values()) {
		if (factTables.has(t.id)) continue;
		const pk = dimTablePk.get(t.id);
		const keyColAttr = pk ? ` keyColumn="${escape(pk)}"` : '';
		out.push(
			`    <Table name="${escape(t.name)}"${t.schema ? ` schema="${escape(t.schema)}"` : ''}${keyColAttr} />`
		);
	}
	out.push('  </PhysicalSchema>');

	// ── Shared Dimensions ──
	for (const dim of dims) {
		const boundTable = tables.find((t) => t.id === (dim.sourceTableId ?? dim.primaryKeyTableId));
		const tableAttr = boundTable ? ` table="${escape(boundTable.name)}"` : '';
		// Mondrian 4 spells a time dimension `type="TIME"` (M3 used
		// "TimeDimension"). Only Time has a defined M4 dimension type.
		const typeAttr =
			dim.dimensionType === 'Time'
				? ' type="TIME"'
				: dim.dimensionType && dim.dimensionType !== 'Standard'
					? ` type="${dim.dimensionType}Dimension"`
					: '';
		const hangerAttr = dim.hanger ? ' hanger="true"' : '';
		// M4 carries a level's `levelType` on its <Attribute>, not the <Level>.
		// Map each column that a hierarchy level marks (TimeYears/…) so the
		// attribute below can inherit it — drives time intelligence.
		const levelTypeByColumn = new Map<string, string>();
		for (const h of dim.hierarchies) {
			for (const lvl of h.levels) {
				if (lvl.levelType) levelTypeByColumn.set(lvl.columnName, lvl.levelType);
			}
		}
		const levelTypeAttr = (columnName: string): string => {
			const lt = levelTypeByColumn.get(columnName);
			return lt ? ` levelType="${escape(lt)}"` : '';
		};
		// Level `saiku.semantic.*` annotations ride on the level's <Attribute>,
		// keyed by column (like levelType).
		const annotationsByColumn = new Map<string, Record<string, string>>();
		for (const h of dim.hierarchies) {
			for (const lvl of h.levels) {
				if (lvl.annotations) annotationsByColumn.set(lvl.columnName, lvl.annotations);
			}
		}
		const A = dim.attributes ?? [];
		// M4 `<Level attribute="X">` references an <Attribute> by its NAME, not by
		// column. Resolve each level's column to the backing attribute's logical
		// name so renaming an attribute cascades to every level that uses it
		// (inspector #959). Falls back to the column name when unbound.
		const attrNameByColumn = new Map<string, string>();
		for (const a of A) attrNameByColumn.set(a.columnName, a.name ?? a.columnName);
		const levelAttrName = (columnName: string): string =>
			attrNameByColumn.get(columnName) ?? columnName;
		const pkColumn: string | null = boundTable ? (dimTablePk.get(boundTable.id) ?? null) : null;
		const existingKeyAttr = pkColumn ? A.find((a) => a.columnName === pkColumn) : undefined;
		const keyAttrName = pkColumn
			? (existingKeyAttr?.name ?? existingKeyAttr?.columnName ?? `${dim.name} Key`)
			: null;
		const needSyntheticKey = !!pkColumn && !existingKeyAttr && !dim.hanger;
		const keyAttr = keyAttrName && !dim.hanger ? ` key="${escape(keyAttrName)}"` : '';
		out.push(
			`  <Dimension name="${escape(dim.name)}"${tableAttr}${keyAttr}${typeAttr}${hangerAttr}` +
				`${attr('caption', dim.caption)}${attr('description', dim.description)}>`
		);
		out.push(...annotationLines(dim.annotations, '    '));
		if (A.length > 0 || needSyntheticKey) {
			out.push('    <Attributes>');
			if (needSyntheticKey) {
				out.push(
					`      <Attribute name="${escape(keyAttrName!)}" keyColumn="${escape(pkColumn!)}" hasHierarchy="false" />`
				);
			}
			for (const a of A) {
				const attrLabel = a.name ?? a.columnName;
				const lt = levelTypeAttr(a.columnName);
				const anns = annotationLines(annotationsByColumn.get(a.columnName), '        ');
				// Optional M4 attribute overrides (inspector #959): display / sort /
				// caption columns + long-form description. Mirror the gateway's
				// MondrianXmlEmitter spellings.
				const overrides =
					attr('nameColumn', a.nameColumn) +
					attr('captionColumn', a.captionColumn) +
					attr('orderByColumn', a.orderByColumn) +
					attr('description', a.description);
				if (a.keyColumns && a.keyColumns.length > 1) {
					out.push(`      <Attribute name="${escape(attrLabel)}"${lt}${overrides}>`);
					out.push('        <Key>');
					for (const c of a.keyColumns) out.push(`          <Column name="${escape(c)}"/>`);
					out.push('        </Key>');
					out.push(...anns);
					out.push('      </Attribute>');
				} else if (anns.length > 0) {
					out.push(
						`      <Attribute name="${escape(attrLabel)}" keyColumn="${escape(a.columnName)}"${lt}${overrides}>`
					);
					out.push(...anns);
					out.push('      </Attribute>');
				} else {
					out.push(
						`      <Attribute name="${escape(attrLabel)}" keyColumn="${escape(a.columnName)}"${lt}${overrides} />`
					);
				}
			}
			out.push('    </Attributes>');
		}
		if (dim.hierarchies.length > 0) {
			out.push('    <Hierarchies>');
			for (const hier of dim.hierarchies) {
				out.push(
					`      <Hierarchy name="${escape(hier.name)}" hasAll="${hier.hasAll ? 'true' : 'false'}"` +
						`${attr('allMemberName', hier.allMemberName)}${attr('defaultMember', hier.defaultMember)}` +
						`${attr('caption', hier.caption)}${attr('description', hier.description)}>`
				);
				for (let li = 0; li < hier.levels.length; li++) {
					const lvl = hier.levels[li];
					if (li === 0 && hier.closure) {
						out.push(
							`        <Level attribute="${escape(levelAttrName(lvl.columnName))}"${attr('caption', lvl.caption)}>`
						);
						const cl = hier.closure;
						out.push(
							`          <Closure table="${escape(cl.table)}" parentColumn="${escape(cl.parentColumn)}" childColumn="${escape(cl.childColumn)}"/>`
						);
						out.push('        </Level>');
					} else {
						out.push(
							`        <Level attribute="${escape(levelAttrName(lvl.columnName))}"${attr('caption', lvl.caption)} />`
						);
					}
				}
				out.push('      </Hierarchy>');
			}
			out.push('    </Hierarchies>');
		}
		out.push('  </Dimension>');
	}

	// ── Cubes ──
	for (const cube of cubes) {
		const cubeFact = cubeFactOf(cube);
		out.push(`  <Cube name="${escape(cube.name || 'Untitled')}">`);
		if (dims.length > 0) {
			out.push('    <Dimensions>');
			for (const dim of dims) out.push(`      <Dimension source="${escape(dim.name)}" />`);
			out.push('    </Dimensions>');
		}
		if (cube.measureGroups.length > 0) {
			out.push('    <MeasureGroups>');
			for (const mg of cube.measureGroups) {
				const mgFactId = mg.factTableId ?? cubeFact;
				const mgFact = mgFactId ? tables.find((t) => t.id === mgFactId) : null;
				if (!mgFact) continue;
				out.push(`      <MeasureGroup name="${escape(mg.name)}" table="${escape(mgFact.name)}">`);
				if (mg.measureColumns.length > 0) {
					out.push('        <Measures>');
					for (const col of mg.measureColumns) {
						const m = measures.find((mm) => mm.tableId === mgFact.id && mm.columnName === col);
						const aggregator = m ? (AGGREGATOR_TO_MONDRIAN[m.aggregator] ?? 'sum') : 'sum';
						const captionRaw = mg.measureCaptions?.[col]?.trim();
						const captionAttr =
							captionRaw && captionRaw !== col ? ` caption="${escape(captionRaw)}"` : '';
						const attrs = `name="${escape(col)}" column="${escape(col)}" aggregator="${aggregator}"${captionAttr}${attr('formatString', m?.formatString)}${percentileAttr(m)}`;
						const anns = annotationLines(m?.annotations, '            ');
						if (anns.length > 0) {
							out.push(`          <Measure ${attrs}>`);
							out.push(...anns);
							out.push('          </Measure>');
						} else {
							out.push(`          <Measure ${attrs} />`);
						}
					}
					out.push('        </Measures>');
				}
				const links = mg.dimensionLinks ?? [];
				if (links.length > 0) {
					out.push('        <DimensionLinks>');
					for (const link of links) {
						const dim = dims.find((d) => d.id === link.dimensionId);
						if (!dim) continue;
						const kind = link.linkKind ?? 'foreign-key';
						if (kind === 'fact') {
							out.push(`          <FactLink dimension="${escape(dim.name)}" />`);
						} else if (kind === 'reference') {
							out.push(
								`          <ReferenceLink dimension="${escape(dim.name)}"${attr('viaDimension', link.viaDimension)}${attr('viaAttribute', link.viaAttribute)} />`
							);
						} else {
							out.push(
								`          <ForeignKeyLink dimension="${escape(dim.name)}" foreignKeyColumn="${escape(link.foreignKeyColumn)}" />`
							);
						}
					}
					out.push('        </DimensionLinks>');
				}
				out.push('      </MeasureGroup>');
			}
			out.push('    </MeasureGroups>');
		}
		const liveCalcs = cube.calcs.filter((c) => renderCalcFormula(c).length > 0);
		if (liveCalcs.length > 0) {
			out.push('    <CalculatedMembers>');
			for (const c of liveCalcs) {
				const formula = renderCalcFormula(c);
				out.push(
					`      <CalculatedMember name="${escape(c.name || 'Untitled')}" dimension="Measures" formula="${escape(formula)}"/>`
				);
			}
			out.push('    </CalculatedMembers>');
		}
		// Declarative time-intelligence metrics (yoy/pop/ytd/rolling). Only emit
		// well-formed ones (name + measure; rolling needs a positive window) so a
		// half-authored row never ships an invalid <TimeCalc> the engine rejects.
		const liveTimeCalcs = (cube.timeCalcs ?? []).filter(
			(t) => t.name.trim() && t.measure.trim() && (t.type !== 'rolling' || (t.window ?? 0) > 0)
		);
		if (liveTimeCalcs.length > 0) {
			out.push('    <TimeCalcs>');
			for (const t of liveTimeCalcs) {
				const parts = [
					`name="${escape(t.name)}"`,
					`type="${escape(t.type)}"`,
					`measure="${escape(t.measure)}"`
				];
				if (t.timeDimension) parts.push(`timeDimension="${escape(t.timeDimension)}"`);
				if (t.type === 'rolling') {
					if (t.window != null) parts.push(`window="${escape(String(t.window))}"`);
					if (t.function) parts.push(`function="${escape(t.function)}"`);
				}
				if (t.formatString) parts.push(`formatString="${escape(t.formatString)}"`);
				out.push(`      <TimeCalc ${parts.join(' ')}/>`);
			}
			out.push('    </TimeCalcs>');
		}
		out.push('  </Cube>');
	}

	out.push('</Schema>');
	return out.join('\n');
}

export function exportToMondrianXml(state: SchemaCanvasState): string {
	// Cubes on the doc → emit exactly what the workbench Code-tab previews.
	// No cubes → keep the legacy single-fact fallback so nothing regresses.
	const cubes = (state.cubes ?? []).filter((c) => c.measureGroups.length > 0 || c.calcs.length > 0);
	if (cubes.length > 0) {
		return exportCubesBased(state, cubes);
	}
	return exportSingleFactFallback(state);
}

function exportSingleFactFallback(state: SchemaCanvasState): string {
	const fact = state.tables.find((t) => t.role === 'fact');
	if (!fact) {
		throw new Error('Cannot export: no fact table designated on the canvas.');
	}
	const schemaName = state.label.trim() || `Schema for ${fact.name}`;
	const cubeName = titleCase(fact.name);

	const dims = state.dimensions?.length
		? state.dimensions
		: inferDimensions(fact, state.tables, state.joins);
	const resolved = dims.map((d) => resolveDimension(d, state.tables, state.joins, fact.id));

	const lines: string[] = [];
	lines.push('<?xml version="1.0" encoding="UTF-8"?>');
	lines.push(`<Schema name="${escape(schemaName)}" metamodelVersion="4.0">`);

	// ── PhysicalSchema — fact (no key) + each dim table (keyColumn=PK) ──
	lines.push('  <PhysicalSchema>');
	lines.push(
		`    <Table name="${escape(fact.name)}"${fact.schema ? ` schema="${escape(fact.schema)}"` : ''} />`
	);
	const seen = new Set<string>([fact.id]);
	for (const r of resolved) {
		if (!r.table || seen.has(r.table.id)) continue;
		seen.add(r.table.id);
		const keyColAttr = r.pkColumn ? ` keyColumn="${escape(r.pkColumn)}"` : '';
		lines.push(
			`    <Table name="${escape(r.table.name)}"${r.table.schema ? ` schema="${escape(r.table.schema)}"` : ''}${keyColAttr} />`
		);
	}
	lines.push('  </PhysicalSchema>');

	// ── Shared Dimensions ──
	for (const r of resolved) {
		for (const line of emitDimension(r)) lines.push(line);
	}

	// ── Cube: dimension usages + one MeasureGroup bound to the fact ──
	lines.push(`  <Cube name="${escape(cubeName)}">`);
	if (resolved.length > 0) {
		lines.push('    <Dimensions>');
		for (const r of resolved) lines.push(`      <Dimension source="${escape(r.dim.name)}" />`);
		lines.push('    </Dimensions>');
	}
	lines.push('    <MeasureGroups>');
	lines.push(`      <MeasureGroup name="${escape(cubeName)}" table="${escape(fact.name)}">`);
	lines.push('        <Measures>');
	const userMeasures = state.measures ?? [];
	if (userMeasures.length > 0) {
		for (const m of userMeasures) {
			const aggregator = AGGREGATOR_TO_MONDRIAN[m.aggregator] ?? 'sum';
			lines.push(
				`          <Measure name="${escape(m.name)}" column="${escape(m.columnName)}" aggregator="${aggregator}"${attr('formatString', m.formatString)}${percentileAttr(m)} />`
			);
		}
	} else {
		// Every cube needs at least one measure; a count is the safe default.
		lines.push('          <Measure name="Row count" aggregator="count" formatString="#,##0" />');
	}
	lines.push('        </Measures>');
	if (resolved.length > 0) {
		lines.push('        <DimensionLinks>');
		for (const r of resolved) {
			const fkAttr = r.fkColumn ? ` foreignKeyColumn="${escape(r.fkColumn)}"` : '';
			lines.push(`          <ForeignKeyLink dimension="${escape(r.dim.name)}"${fkAttr} />`);
		}
		lines.push('        </DimensionLinks>');
	}
	lines.push('      </MeasureGroup>');
	lines.push('    </MeasureGroups>');
	lines.push('  </Cube>');
	lines.push('</Schema>');
	return lines.join('\n');
}

/** Same schema as {@link exportToMondrianXml}, serialised as Mondrian 4 YAML. */
export function exportToMondrianYaml(state: SchemaCanvasState): string {
	return mondrianXmlToYaml(exportToMondrianXml(state));
}
