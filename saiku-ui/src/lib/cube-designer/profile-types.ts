/**
 * Dashboard-side mirror of the gateway's SchemaProposal IR shape
 * (saiku-cloud#444 — visual cube-card editor).
 *
 * Mirrors `bi.saiku.cloud.gateway.inference.SchemaProposal` and its
 * nested records. Keep this in sync when the IR evolves — see
 * `docs/decisions/ai-inference-emitter-shape.md` for the canonical
 * contract.
 */

export interface SchemaProposal {
	schemaName: string;
	cubes: CubeProposal[];
	/**
	 * Composite cubes that draw from multiple base cubes.
	 * Saiku-mondrian 4.8.x reads the M3-style {@code <VirtualCube>}
	 * element directly. Added 2026-05-28 (#646).
	 */
	virtualCubes?: VirtualCubeProposal[];
	/**
	 * Schema-scope MDX named sets. Mondrian 4 hardens against
	 * schema-scope; cube-scope is the canonical place. Round-trip only.
	 */
	namedSets?: NamedSetProposal[];
	/**
	 * Mondrian schema-scope access-control roles.
	 */
	roles?: RoleProposal[];
}

export interface CubeProposal {
	name: string;
	factTableSchema: string | null;
	factTableName: string;
	caption?: string | null;
	description?: string | null;
	dimensions: DimensionProposal[];
	measures: MeasureProposal[];
	/**
	 * Calculated members (Mondrian `<CalculatedMember dimension="Measures" formula="..."/>`).
	 * Composed measures expressed as MDX formulas — `[Measures].[Profit] = [Measures].[Revenue] - [Measures].[Cost]`.
	 * Optional — back-compat with pre-#646 IR shapes. Added 2026-05-28.
	 */
	calculatedMembers?: CalculatedMemberProposal[];
	/**
	 * Cube-scope MDX named sets. Mondrian 4 hardens cube-scope, so
	 * this is the canonical place. Added 2026-05-28.
	 */
	namedSets?: NamedSetProposal[];
	/**
	 * Declarative time-intelligence (Mondrian 4 `<TimeCalc>`). Desugars
	 * at engine load-time into calculated members on `[Measures]`. Optional
	 * — back-compat with pre-#112 IR shapes. Added 2026-06-09.
	 */
	timeCalcs?: TimeCalcProposal[];
	/**
	 * Declarative currency/unit conversions (Mondrian 4
	 * `<CurrencyConversion>`). A measure-group-level construct producing a
	 * converted measure `SUM(measure × rate)` via an as-of rate-table band
	 * join. The gateway IR has no measure-group node, so these attach to the
	 * cube. Optional — back-compat with pre-#112-phase-3 IR shapes. Added
	 * 2026-06-09.
	 */
	currencyConversions?: CurrencyConversionProposal[];
}

/**
 * Declarative currency conversion (Mondrian 4 `<CurrencyConversion>`,
 * saiku-cloud#112 phase 3). Every field except `formatString` is
 * required (the gateway IR rejects a blank).
 */
export interface CurrencyConversionProposal {
	name: string;
	measure: string;
	rateTable: string;
	rateColumn: string;
	rateType: string;
	rateTypeColumn: string;
	factCurrencyColumn: string;
	rateCurrencyColumn: string;
	factDateColumn: string;
	rateValidFromColumn: string;
	rateValidToColumn: string;
	formatString?: string | null;
}

/**
 * Declarative time-intelligence metric (Mondrian 4 `<TimeCalc>`,
 * saiku-cloud#112). `window` + `function` are only meaningful for
 * `type === 'rolling'` (the gateway IR drops them otherwise).
 */
export interface TimeCalcProposal {
	name: string;
	type: string;
	measure: string;
	timeDimension?: string | null;
	window?: number | null;
	function?: string | null;
	formatString?: string | null;
}

export const VALID_TIME_CALC_TYPES = ['yoy', 'pop', 'ytd', 'rolling'] as const;

export type TimeCalcType = (typeof VALID_TIME_CALC_TYPES)[number];

export const VALID_TIME_CALC_FUNCTIONS = ['sum', 'avg'] as const;

export type TimeCalcFunction = (typeof VALID_TIME_CALC_FUNCTIONS)[number];

/**
 * Mondrian 4 access levels for Role / SchemaGrant / CubeGrant /
 * DimensionGrant / HierarchyGrant / MemberGrant.
 */
export const VALID_ACCESS_LEVELS = ['all', 'none', 'custom', 'all_dimensions'] as const;

export type AccessLevel = (typeof VALID_ACCESS_LEVELS)[number];

/**
 * Mondrian 4 hierarchy rollup policy — controls how the engine
 * aggregates restricted hierarchies during query rollup.
 */
export const VALID_ROLLUP_POLICIES = ['full', 'partial', 'hidden'] as const;

export type RollupPolicy = (typeof VALID_ROLLUP_POLICIES)[number];

export interface NamedSetProposal {
	name: string;
	formula: string;
	description?: string | null;
}

/**
 * Mondrian 4 virtual cube (emitted as `<Cube>` with `<MeasureGroups>`
 * per the M4 schema docs). The IR keeps the M3-style record shape
 * for editor convenience; the gateway emits M4 syntax.
 */
export interface VirtualCubeProposal {
	name: string;
	defaultMeasure?: string | null;
	cubeUsages?: CubeUsageProposal[];
	dimensions: VirtualCubeDimensionProposal[];
	measures: VirtualCubeMeasureProposal[];
	calculatedMembers?: CalculatedMemberProposal[];
	namedSets?: NamedSetProposal[];
	caption?: string | null;
	description?: string | null;
}

export interface CubeUsageProposal {
	cubeName: string;
	ignoreUnrelatedDimensions: boolean;
}

export interface VirtualCubeDimensionProposal {
	name: string;
	/** Source base cube (M4 `source="..."`). Null = shared dimension. */
	cubeName?: string | null;
	caption?: string | null;
	description?: string | null;
}

export interface VirtualCubeMeasureProposal {
	/** Fully-qualified MDX measure reference: `[Measures].[Foo]`. */
	name: string;
	cubeName: string;
	visible: boolean;
}

export interface RoleProposal {
	name: string;
	schemaGrant: SchemaGrantProposal;
	description?: string | null;
}

export interface SchemaGrantProposal {
	access: string;
	cubeGrants?: CubeGrantProposal[];
}

export interface CubeGrantProposal {
	cube: string;
	access: string;
	dimensionGrants?: DimensionGrantProposal[];
	hierarchyGrants?: HierarchyGrantProposal[];
}

export interface DimensionGrantProposal {
	dimension: string;
	access: string;
}

export interface HierarchyGrantProposal {
	hierarchy: string;
	access: string;
	topLevel?: string | null;
	bottomLevel?: string | null;
	rollupPolicy?: string | null;
	memberGrants?: MemberGrantProposal[];
}

export interface MemberGrantProposal {
	member: string;
	access: string;
}

export interface DimensionProposal {
	name: string;
	foreignKey: string;
	tableSchema: string | null;
	tableName: string;
	primaryKey: string;
	/**
	 * Top-level levels — the legacy single-hierarchy shape. When
	 * `hierarchies` is non-empty the emitter uses that and these are
	 * ignored; otherwise the emitter wraps these in a default
	 * anonymous hierarchy.
	 */
	levels: LevelProposal[];
	/**
	 * Multi-hierarchy support (Mondrian `<Hierarchy>` children). Optional
	 * — the typical cube has a single hierarchy and the LLM proposer
	 * emits everything via `levels` for back-compat. Added 2026-05-28.
	 */
	hierarchies?: HierarchyProposal[];
	/**
	 * Mondrian-4 derived Tier (binning) attributes (saiku-cloud#108). Each
	 * hangs off an `<Attribute hasHierarchy="true"><Tier>` in the M4 model.
	 * Optional — only present on dimensions that bucket a numeric column.
	 */
	tiers?: TierProposal[];
	/**
	 * Mondrian-4 derived Duration attributes (saiku-cloud#108). Each hangs
	 * off an `<Attribute hasHierarchy="true"><Duration>` in the M4 model.
	 * Optional — only present on dimensions with a date-pair column.
	 */
	durations?: DurationProposal[];
}

/**
 * Mondrian-4 Tier (binning) derived attribute (saiku-cloud#108). Buckets a
 * numeric column into labelled bins by ascending boundary. The FINAL bin
 * omits its boundary (catch-all for everything above the last boundary).
 */
export interface TierProposal {
	name: string;
	column: string;
	bins: BinProposal[];
}

/**
 * One bin within a {@link TierProposal}. `boundary` (a numeric upper bound
 * carried as a string) is omitted ONLY on the final catch-all bin; `label`
 * is always required.
 */
export interface BinProposal {
	boundary?: string | null;
	label: string;
}

/**
 * Mondrian-4 Duration derived attribute (saiku-cloud#108). Computes the
 * elapsed time between two date columns at a chosen unit.
 */
export interface DurationProposal {
	name: string;
	startColumn: string;
	endColumn: string;
	unit: string;
}

/** Mondrian-4 duration units (coarsest → finest). */
export const VALID_DURATION_UNITS = ['YEAR', 'QUARTER', 'MONTH', 'WEEK', 'DAY'] as const;

export type DurationUnit = (typeof VALID_DURATION_UNITS)[number];

export interface HierarchyProposal {
	name?: string | null;
	hasAll: boolean;
	allMemberName?: string | null;
	primaryKey: string;
	tableSchema?: string | null;
	tableName?: string | null;
	levels: LevelProposal[];
}

export interface LevelProposal {
	name: string;
	column: string;
	nameColumn?: string | null;
	captionColumn?: string | null;
	ordinalColumn?: string | null;
	type: string;
	uniqueMembers: boolean;
	levelType?: string | null;
	description?: string | null;
	/**
	 * Member properties (Mondrian `<Property>` children). Surface as
	 * `[Dim].CurrentMember.Properties("name")` in MDX. Added 2026-05-28.
	 */
	properties?: PropertyProposal[];
	annotations?: Record<string, string[]>;
}

export interface PropertyProposal {
	name: string;
	column: string;
	type?: string | null;
	description?: string | null;
}

export interface MeasureProposal {
	name: string;
	column: string;
	aggregator: string;
	/**
	 * Integer 0–100, only meaningful when `aggregator === 'percentile'`
	 * (median is the implicit 50th percentile and carries no value).
	 * Mondrian-4 non-additive leaf aggregators (saiku-cloud#104).
	 */
	percentile?: number | null;
	formatString?: string | null;
	description?: string | null;
	annotations?: Record<string, string[]>;
}

export interface CalculatedMemberProposal {
	name: string;
	formula: string;
	formatString?: string | null;
	description?: string | null;
}

export const VALID_AGGREGATORS = [
	'sum',
	'count',
	'avg',
	'min',
	'max',
	'distinct-count',
	'median',
	'percentile'
] as const;

export type Aggregator = (typeof VALID_AGGREGATORS)[number];

/**
 * Mondrian Level `type` attribute. Drives JDBC value parsing
 * (member.getKey() coercion + ordinal comparisons). The Mondrian DTD
 * accepts a wider set, but these are the ones our XML emitter knows
 * how to round-trip and the ones surfaced in the level editor.
 */
export const VALID_LEVEL_TYPES = [
	'String',
	'Numeric',
	'Integer',
	'Boolean',
	'Date',
	'Time',
	'Timestamp'
] as const;

export type LevelType = (typeof VALID_LEVEL_TYPES)[number];

/**
 * Mondrian Level `levelType` attribute. Time-* values tell Mondrian
 * which time grain a level represents — this drives the time-related
 * MDX functions (PARALLELPERIOD, OPENINGPERIOD, etc.). `Regular` is
 * the default for non-time hierarchies.
 */
export const VALID_LEVEL_LEVELTYPES = [
	'Regular',
	'TimeYears',
	'TimeQuarters',
	'TimeMonths',
	'TimeWeeks',
	'TimeDays',
	'TimeHours',
	'TimeMinutes',
	'TimeSeconds'
] as const;

export type LevelLevelType = (typeof VALID_LEVEL_LEVELTYPES)[number];

/**
 * Best-effort parse of a proposal JSON string. Returns null on any
 * shape mismatch — the caller can fall back to the raw-JSON view in
 * that case rather than crashing the page. We accept Optional-ish
 * fields (gateway emits `null` or omits, dashboard reads either).
 */
export function parseProposal(raw: unknown): SchemaProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	const schemaName = typeof r.schemaName === 'string' ? r.schemaName : null;
	const cubesRaw = Array.isArray(r.cubes) ? r.cubes : null;
	if (!schemaName || !cubesRaw) return null;
	const cubes: CubeProposal[] = [];
	for (const c of cubesRaw) {
		const cube = parseCube(c);
		if (!cube) return null;
		cubes.push(cube);
	}
	const virtualCubes = Array.isArray(r.virtualCubes)
		? r.virtualCubes.map(parseVirtualCube).filter(isNotNull)
		: [];
	const namedSets = Array.isArray(r.namedSets)
		? r.namedSets.map(parseNamedSet).filter(isNotNull)
		: [];
	const roles = Array.isArray(r.roles) ? r.roles.map(parseRole).filter(isNotNull) : [];
	if (cubes.length === 0) return null;
	return {
		schemaName,
		cubes,
		virtualCubes: virtualCubes.length > 0 ? virtualCubes : undefined,
		namedSets: namedSets.length > 0 ? namedSets : undefined,
		roles: roles.length > 0 ? roles : undefined
	};
}

function parseCube(raw: unknown): CubeProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.factTableName !== 'string') return null;
	const measures = Array.isArray(r.measures) ? r.measures.map(parseMeasure).filter(isNotNull) : [];
	const dimensions = Array.isArray(r.dimensions)
		? r.dimensions.map(parseDimension).filter(isNotNull)
		: [];
	const calculatedMembers = Array.isArray(r.calculatedMembers)
		? r.calculatedMembers.map(parseCalculatedMember).filter(isNotNull)
		: [];
	const namedSets = Array.isArray(r.namedSets)
		? r.namedSets.map(parseNamedSet).filter(isNotNull)
		: [];
	const timeCalcs = Array.isArray(r.timeCalcs)
		? r.timeCalcs.map(parseTimeCalc).filter(isNotNull)
		: [];
	const currencyConversions = Array.isArray(r.currencyConversions)
		? r.currencyConversions.map(parseCurrencyConversion).filter(isNotNull)
		: [];
	return {
		name: r.name,
		factTableSchema: typeof r.factTableSchema === 'string' ? r.factTableSchema : null,
		factTableName: r.factTableName,
		caption: optionalString(r.caption),
		description: optionalString(r.description),
		dimensions,
		measures,
		calculatedMembers,
		namedSets: namedSets.length > 0 ? namedSets : undefined,
		timeCalcs: timeCalcs.length > 0 ? timeCalcs : undefined,
		currencyConversions: currencyConversions.length > 0 ? currencyConversions : undefined
	};
}

/**
 * Parse a single currency-conversion IR node. Returns null on a shape
 * mismatch (the caller filters nulls out). Every field except
 * `formatString` is required, mirroring the gateway IR.
 */
export function parseCurrencyConversion(raw: unknown): CurrencyConversionProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	const required = [
		'name',
		'measure',
		'rateTable',
		'rateColumn',
		'rateType',
		'rateTypeColumn',
		'factCurrencyColumn',
		'rateCurrencyColumn',
		'factDateColumn',
		'rateValidFromColumn',
		'rateValidToColumn'
	] as const;
	for (const key of required) {
		if (typeof r[key] !== 'string' || (r[key] as string).length === 0) return null;
	}
	return {
		name: r.name as string,
		measure: r.measure as string,
		rateTable: r.rateTable as string,
		rateColumn: r.rateColumn as string,
		rateType: r.rateType as string,
		rateTypeColumn: r.rateTypeColumn as string,
		factCurrencyColumn: r.factCurrencyColumn as string,
		rateCurrencyColumn: r.rateCurrencyColumn as string,
		factDateColumn: r.factDateColumn as string,
		rateValidFromColumn: r.rateValidFromColumn as string,
		rateValidToColumn: r.rateValidToColumn as string,
		formatString: optionalString(r.formatString)
	};
}

/**
 * Parse a single time-calc IR node. Returns null on a shape mismatch
 * (the caller filters nulls out). `window` / `function` survive only for
 * `type === 'rolling'`, mirroring the gateway IR.
 */
export function parseTimeCalc(raw: unknown): TimeCalcProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.type !== 'string' || typeof r.measure !== 'string')
		return null;
	const isRolling = r.type === 'rolling';
	return {
		name: r.name,
		type: r.type,
		measure: r.measure,
		timeDimension: optionalString(r.timeDimension),
		window: isRolling ? optionalInt(r.window) : null,
		function: isRolling ? optionalString(r.function) : null,
		formatString: optionalString(r.formatString)
	};
}

function parseNamedSet(raw: unknown): NamedSetProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.formula !== 'string') return null;
	return {
		name: r.name,
		formula: r.formula,
		description: optionalString(r.description)
	};
}

function parseVirtualCube(raw: unknown): VirtualCubeProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string') return null;
	const cubeUsages = Array.isArray(r.cubeUsages)
		? r.cubeUsages
				.map((u) => {
					if (!u || typeof u !== 'object') return null;
					const ur = u as Record<string, unknown>;
					if (typeof ur.cubeName !== 'string') return null;
					return {
						cubeName: ur.cubeName,
						ignoreUnrelatedDimensions: ur.ignoreUnrelatedDimensions !== false
					};
				})
				.filter(isNotNull)
		: [];
	const dimensions: VirtualCubeDimensionProposal[] = Array.isArray(r.dimensions)
		? r.dimensions
				.map((d) => {
					if (!d || typeof d !== 'object') return null;
					const dr = d as Record<string, unknown>;
					if (typeof dr.name !== 'string') return null;
					return {
						name: dr.name,
						cubeName: optionalString(dr.cubeName),
						caption: optionalString(dr.caption),
						description: optionalString(dr.description)
					};
				})
				.filter(isNotNull)
		: [];
	const measures: VirtualCubeMeasureProposal[] = Array.isArray(r.measures)
		? r.measures
				.map((m) => {
					if (!m || typeof m !== 'object') return null;
					const mr = m as Record<string, unknown>;
					if (typeof mr.name !== 'string' || typeof mr.cubeName !== 'string') return null;
					return { name: mr.name, cubeName: mr.cubeName, visible: mr.visible !== false };
				})
				.filter(isNotNull)
		: [];
	const calculatedMembers = Array.isArray(r.calculatedMembers)
		? r.calculatedMembers.map(parseCalculatedMember).filter(isNotNull)
		: [];
	const namedSets = Array.isArray(r.namedSets)
		? r.namedSets.map(parseNamedSet).filter(isNotNull)
		: [];
	return {
		name: r.name,
		defaultMeasure: optionalString(r.defaultMeasure),
		cubeUsages: cubeUsages.length > 0 ? cubeUsages : undefined,
		dimensions,
		measures,
		calculatedMembers: calculatedMembers.length > 0 ? calculatedMembers : undefined,
		namedSets: namedSets.length > 0 ? namedSets : undefined,
		caption: optionalString(r.caption),
		description: optionalString(r.description)
	};
}

function parseRole(raw: unknown): RoleProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string') return null;
	const sg = parseSchemaGrant(r.schemaGrant);
	if (!sg) return null;
	return {
		name: r.name,
		schemaGrant: sg,
		description: optionalString(r.description)
	};
}

function parseSchemaGrant(raw: unknown): SchemaGrantProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.access !== 'string') return null;
	const cubeGrants = Array.isArray(r.cubeGrants)
		? r.cubeGrants.map(parseCubeGrant).filter(isNotNull)
		: [];
	return { access: r.access, cubeGrants: cubeGrants.length > 0 ? cubeGrants : undefined };
}

function parseCubeGrant(raw: unknown): CubeGrantProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.cube !== 'string' || typeof r.access !== 'string') return null;
	const dimensionGrants = Array.isArray(r.dimensionGrants)
		? r.dimensionGrants
				.map((d) => {
					if (!d || typeof d !== 'object') return null;
					const dr = d as Record<string, unknown>;
					if (typeof dr.dimension !== 'string' || typeof dr.access !== 'string') return null;
					return { dimension: dr.dimension, access: dr.access };
				})
				.filter(isNotNull)
		: [];
	const hierarchyGrants = Array.isArray(r.hierarchyGrants)
		? r.hierarchyGrants.map(parseHierarchyGrant).filter(isNotNull)
		: [];
	return {
		cube: r.cube,
		access: r.access,
		dimensionGrants: dimensionGrants.length > 0 ? dimensionGrants : undefined,
		hierarchyGrants: hierarchyGrants.length > 0 ? hierarchyGrants : undefined
	};
}

function parseHierarchyGrant(raw: unknown): HierarchyGrantProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.hierarchy !== 'string' || typeof r.access !== 'string') return null;
	const memberGrants = Array.isArray(r.memberGrants)
		? r.memberGrants
				.map((m) => {
					if (!m || typeof m !== 'object') return null;
					const mr = m as Record<string, unknown>;
					if (typeof mr.member !== 'string' || typeof mr.access !== 'string') return null;
					return { member: mr.member, access: mr.access };
				})
				.filter(isNotNull)
		: [];
	return {
		hierarchy: r.hierarchy,
		access: r.access,
		topLevel: optionalString(r.topLevel),
		bottomLevel: optionalString(r.bottomLevel),
		rollupPolicy: optionalString(r.rollupPolicy),
		memberGrants: memberGrants.length > 0 ? memberGrants : undefined
	};
}

function parseCalculatedMember(raw: unknown): CalculatedMemberProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.formula !== 'string') return null;
	return {
		name: r.name,
		formula: r.formula,
		formatString: optionalString(r.formatString),
		description: optionalString(r.description)
	};
}

function parseMeasure(raw: unknown): MeasureProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (
		typeof r.name !== 'string' ||
		typeof r.column !== 'string' ||
		typeof r.aggregator !== 'string'
	)
		return null;
	return {
		name: r.name,
		column: r.column,
		aggregator: r.aggregator,
		percentile: r.aggregator === 'percentile' ? optionalInt(r.percentile) : null,
		formatString: optionalString(r.formatString),
		description: optionalString(r.description),
		annotations: parseAnnotations(r.annotations)
	};
}

function parseDimension(raw: unknown): DimensionProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (
		typeof r.name !== 'string' ||
		typeof r.foreignKey !== 'string' ||
		typeof r.tableName !== 'string' ||
		typeof r.primaryKey !== 'string'
	)
		return null;
	const levels = Array.isArray(r.levels) ? r.levels.map(parseLevel).filter(isNotNull) : [];
	const hierarchies = Array.isArray(r.hierarchies)
		? r.hierarchies.map(parseHierarchy).filter(isNotNull)
		: [];
	const tiers = Array.isArray(r.tiers) ? r.tiers.map(parseTier).filter(isNotNull) : [];
	const durations = Array.isArray(r.durations)
		? r.durations.map(parseDuration).filter(isNotNull)
		: [];
	// A dimension must surface something queryable: levels, hierarchies, OR
	// Mondrian-4 derived attributes (tiers/durations — the degenerate-on-fact
	// Bank "Account" shape, saiku-cloud#108).
	if (
		levels.length === 0 &&
		hierarchies.length === 0 &&
		tiers.length === 0 &&
		durations.length === 0
	)
		return null;
	return {
		name: r.name,
		foreignKey: r.foreignKey,
		tableSchema: typeof r.tableSchema === 'string' ? r.tableSchema : null,
		tableName: r.tableName,
		primaryKey: r.primaryKey,
		levels,
		hierarchies: hierarchies.length > 0 ? hierarchies : undefined,
		tiers: tiers.length > 0 ? tiers : undefined,
		durations: durations.length > 0 ? durations : undefined
	};
}

/**
 * Parse a single Tier IR node. Returns null on a shape mismatch. Mirrors
 * the gateway IR: every bin needs a label; only the final bin may omit its
 * boundary.
 */
export function parseTier(raw: unknown): TierProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.column !== 'string') return null;
	if (!Array.isArray(r.bins) || r.bins.length === 0) return null;
	const bins: BinProposal[] = [];
	for (const b of r.bins) {
		if (!b || typeof b !== 'object') return null;
		const br = b as Record<string, unknown>;
		if (typeof br.label !== 'string' || br.label.length === 0) return null;
		bins.push({ boundary: optionalString(br.boundary), label: br.label });
	}
	// Only the final bin may omit its boundary.
	for (let i = 0; i < bins.length - 1; i++) {
		if (!bins[i].boundary) return null;
	}
	return { name: r.name, column: r.column, bins };
}

/** Parse a single Duration IR node. Returns null on a shape mismatch. */
export function parseDuration(raw: unknown): DurationProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (
		typeof r.name !== 'string' ||
		typeof r.startColumn !== 'string' ||
		typeof r.endColumn !== 'string' ||
		typeof r.unit !== 'string'
	)
		return null;
	return {
		name: r.name,
		startColumn: r.startColumn,
		endColumn: r.endColumn,
		unit: r.unit
	};
}

function parseHierarchy(raw: unknown): HierarchyProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.primaryKey !== 'string') return null;
	const levels = Array.isArray(r.levels) ? r.levels.map(parseLevel).filter(isNotNull) : [];
	if (levels.length === 0) return null;
	return {
		name: optionalString(r.name),
		hasAll: r.hasAll === false ? false : true, // default true
		allMemberName: optionalString(r.allMemberName),
		primaryKey: r.primaryKey,
		tableSchema: optionalString(r.tableSchema),
		tableName: optionalString(r.tableName),
		levels
	};
}

function parseLevel(raw: unknown): LevelProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.column !== 'string') return null;
	const properties = Array.isArray(r.properties)
		? r.properties.map(parseProperty).filter(isNotNull)
		: [];
	return {
		name: r.name,
		column: r.column,
		nameColumn: optionalString(r.nameColumn),
		captionColumn: optionalString(r.captionColumn),
		ordinalColumn: optionalString(r.ordinalColumn),
		type: typeof r.type === 'string' ? r.type : 'String',
		uniqueMembers: !!r.uniqueMembers,
		levelType: optionalString(r.levelType),
		description: optionalString(r.description),
		properties: properties.length > 0 ? properties : undefined,
		annotations: parseAnnotations(r.annotations)
	};
}

function parseProperty(raw: unknown): PropertyProposal | null {
	if (!raw || typeof raw !== 'object') return null;
	const r = raw as Record<string, unknown>;
	if (typeof r.name !== 'string' || typeof r.column !== 'string') return null;
	return {
		name: r.name,
		column: r.column,
		type: optionalString(r.type),
		description: optionalString(r.description)
	};
}

function parseAnnotations(raw: unknown): Record<string, string[]> | undefined {
	if (!raw || typeof raw !== 'object') return undefined;
	const out: Record<string, string[]> = {};
	for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
		if (!Array.isArray(v)) continue;
		const stringValues = v.filter((x): x is string => typeof x === 'string');
		if (stringValues.length === 0) continue;
		out[k] = stringValues;
	}
	return Object.keys(out).length > 0 ? out : undefined;
}

function optionalString(value: unknown): string | null {
	if (typeof value === 'string') return value;
	if (value && typeof value === 'object' && 'value' in value) {
		const v = (value as { value: unknown }).value;
		return typeof v === 'string' ? v : null;
	}
	return null;
}

/**
 * Best-effort parse of an optional integer. Accepts a bare number, a
 * numeric string, or the gateway's serialised `Optional` shape
 * (`{ value: N }`). Returns null on anything else (mirrors how the IR
 * drops a missing/irrelevant percentile).
 */
function optionalInt(value: unknown): number | null {
	if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
	if (typeof value === 'string' && value.trim() !== '') {
		const n = Number(value);
		return Number.isFinite(n) ? Math.trunc(n) : null;
	}
	if (value && typeof value === 'object' && 'value' in value) {
		return optionalInt((value as { value: unknown }).value);
	}
	return null;
}

function isNotNull<T>(value: T | null): value is T {
	return value !== null;
}

/**
 * Qualified fact-table label for display: `schema.table` when both
 * present, just the table name otherwise. Used by the CubeCard
 * header.
 */
export function qualifiedTable(schema: string | null | undefined, name: string): string {
	return schema && schema.length > 0 ? `${schema}.${name}` : name;
}

/**
 * Dashboard-side summary of a {@code TableProfile} — only the fields
 * the Add-Dimension table picker (saiku-cloud#449 slice 2) needs.
 * We deliberately don't mirror the full {@code SchemaProfile} surface
 * because the picker doesn't need sample values, cardinality, etc.
 */
export interface ProfileTableSummary {
	schema: string | null;
	name: string;
	columns: ProfileColumnSummary[];
}

export interface ProfileColumnSummary {
	name: string;
	sqlType: string;
}

/**
 * Best-effort parse of a {@code SchemaProfile} JSON string into the
 * minimal summary the Add-Dimension picker needs. Returns an empty
 * array on any shape mismatch — picker falls back to "no tables
 * available" UI.
 */
export function parseProfileTables(rawJson: string | null | undefined): ProfileTableSummary[] {
	if (!rawJson || rawJson.trim().length === 0) return [];
	let parsed: unknown;
	try {
		parsed = JSON.parse(rawJson);
	} catch {
		return [];
	}
	if (!parsed || typeof parsed !== 'object') return [];
	// Two response shapes in the wild:
	//   (a) bare SchemaProfile — `{tables: [...]}` (what the manual +page.server
	//       extracts via `body.profile` before stringifying).
	//   (b) the wrapped envelope the gateway returns from
	//       `POST /me/inference/profile/connection/{id}` after #448 added
	//       cube-library matches: `{profile: {tables: [...]}, connection, libraryMatches}`.
	// Callers that pass the raw fetch body land on (b); accept either so a
	// missed unwrap on the caller side doesn't silently zero the table list.
	const top = parsed as { tables?: unknown; profile?: unknown };
	let tablesRaw: unknown = top.tables;
	if (!Array.isArray(tablesRaw) && top.profile && typeof top.profile === 'object') {
		tablesRaw = (top.profile as { tables?: unknown }).tables;
	}
	if (!Array.isArray(tablesRaw)) {
		// Shape drift early-warning — non-trivial JSON came in but neither
		// `tables` nor `profile.tables` exists. Next time the gateway
		// changes the envelope, this fires in the browser console (and
		// the SSR dashboard container log) the moment the page loads,
		// instead of a silent "0 tables" mystery.
		if (rawJson.length > 16) {
			const keys = Object.keys(top).slice(0, 8).join(',');

			console.warn(
				`[parseProfileTables] non-empty input (${rawJson.length} chars) ` +
					`produced 0 tables — expected \`tables\` or \`profile.tables\`, ` +
					`got keys=[${keys}]`
			);
		}
		return [];
	}
	const out: ProfileTableSummary[] = [];
	for (const t of tablesRaw) {
		if (!t || typeof t !== 'object') continue;
		const r = t as Record<string, unknown>;
		if (typeof r.name !== 'string') continue;
		const colsRaw = Array.isArray(r.columns) ? r.columns : [];
		const columns: ProfileColumnSummary[] = [];
		for (const c of colsRaw) {
			if (!c || typeof c !== 'object') continue;
			const cr = c as Record<string, unknown>;
			if (typeof cr.name !== 'string') continue;
			columns.push({
				name: cr.name,
				sqlType: typeof cr.sqlType === 'string' ? cr.sqlType : 'unknown'
			});
		}
		out.push({
			schema: typeof r.schema === 'string' ? r.schema : null,
			name: r.name,
			columns
		});
	}
	return out;
}
