/**
 * Canvas schema designer — data model.
 *
 * The user picks a fact table + a set of dimension/bridge tables, visually
 * positions them on a canvas, and wires column-to-column joins between
 * them. Result is serialisable to localStorage and ultimately exportable
 * to a Mondrian schema.
 *
 * Glossary:
 *   - SchemaCanvasTable — a table the user has pulled onto the canvas.
 *   - SchemaCanvasJoin  — an edge between two columns on two tables.
 *   - SchemaCanvasState — the whole document for one in-progress schema.
 *
 * Source-table catalog (the sidebar contents) is loaded separately from
 * the existing /api/inference/profile-connection endpoint and isn't part
 * of this document.
 */
import type {
  ProfileTableSummary,
  ProfileColumnSummary,
} from "./profile-types";

export type SchemaCanvasTableRole = "fact" | "dimension" | "bridge";

export interface SchemaCanvasTable {
  /** Unique within a canvas document. Generated locally. */
  id: string;
  /** Schema-qualified name from the source warehouse — e.g. `public.sales_fact_1997`. */
  schema: string | null;
  name: string;
  role: SchemaCanvasTableRole;
  /** Columns mirrored from the source-table catalog at the moment of pull-in. */
  columns: SchemaCanvasColumn[];
  /** Canvas position. ELK auto-layout writes here; manual drag also writes here. */
  position: { x: number; y: number };
  /** Group id this table belongs to, or null for ungrouped. */
  groupId: string | null;
  /** Collapsed = header only (no column list). Defaults to expanded. */
  collapsed?: boolean;
}

export interface SchemaCanvasColumn {
  name: string;
  sqlType: string;
  /** Inferred by name-match against other tables; user can override. */
  isPrimaryKey?: boolean;
  /** Used by the validation pass — "this column is required but isn't joined to anything." */
  required?: boolean;
}

export type SchemaCanvasJoinKind = "inner" | "left" | "right" | "full";

export interface SchemaCanvasJoin {
  id: string;
  /** Always references SchemaCanvasTable.id, not the source-warehouse name. */
  sourceTableId: string;
  sourceColumnName: string;
  targetTableId: string;
  targetColumnName: string;
  kind: SchemaCanvasJoinKind;
  /**
   * Where this join came from.  Drives canvas visibility + editability:
   *  - `physical` (default) — user-authored OR imported from Mondrian-4
   *    `<PhysicalSchema><Link>` / Mondrian-3 nested `<Hierarchy><Table>`.
   *    Editable, always rendered.
   *  - `cube-link` — auto-inferred from a Mondrian-4 `<MeasureGroup>`'s
   *    `<ForeignKeyLink>`.  Cube-side semantic link, NOT a physical-schema
   *    statement.  Hidden by default; rendered dashed when shown; not
   *    user-editable (delete the FK link on the MG instead).
   *  - `inferred-fk` — legacy fallback when nothing explicit existed.
   *    Same treatment as cube-link.
   *  Undefined is treated as `physical` for back-compat with older docs.
   */
  origin?: "physical" | "cube-link" | "inferred-fk";
}

export interface SchemaCanvasGroup {
  id: string;
  label: string;
  /** Optional colour token override; otherwise the default group palette is used. */
  color?: string;
  collapsed: boolean;
}

/**
 * Coarse column-type bucket used by the Dimensions Workbench to decide
 * how a dragged column wants to be wired up (a numeric column drops in
 * as a measure with `sum` by default; a string column drops in as a
 * level). Mirrors a Mondrian `Level type` attribute when present.
 */
export type SchemaCanvasColumnKind =
  "String" | "Numeric" | "Integer" | "Boolean" | "Date";

/**
 * One Mondrian-style level inside a hierarchy. Levels are ordered
 * coarse → fine (Year → Quarter → Month) — that's the Mondrian
 * convention and what the workbench renders top-to-bottom.
 */
export interface SchemaCanvasLevel {
  id: string;
  /** User-visible label, e.g. "Year". Defaults to the column name. */
  name: string;
  /** Mondrian 4 `caption` — display label shown to end users, distinct from
   *  the logical `name`. Optional; when unset the engine falls back to `name`. */
  caption?: string;
  /** Canvas table id the column lives on. */
  tableId: string;
  /** Column referenced — the level's `column` attribute in Mondrian. */
  columnName: string;
  /** Coarse type bucket — drives downstream Mondrian export. */
  type?: SchemaCanvasColumnKind;
  /**
   * Mondrian-4 `levelType` — marks a level as a time grain so the engine can
   * drive time-intelligence (`<TimeCalc>` requires a `TimeYears` level).
   * Only meaningful on a Time dimension; emitted onto the M4 `<Attribute>`.
   * See docs.saiku.bi/mondrian/time-intelligence.
   */
  levelType?: "TimeYears" | "TimeQuarters" | "TimeMonths" | "TimeDays";
  /**
   * Optional `saiku.semantic.*` annotations (description, synonyms, cardinality,
   * grain, required_filters, pii) — drive the AI Ask layer + PII protection.
   * Keyed by the bare suffix (e.g. `description`, `pii`); emitted as
   * `<Annotation name="saiku.semantic.<key>">`. See
   * docs.saiku.bi/mondrian/semantic-annotations.
   */
  annotations?: Record<string, string>;
}

/**
 * One Mondrian hierarchy inside a dimension. A dimension always has
 * at least one hierarchy (Mondrian requires it); the workbench
 * auto-creates a first hierarchy whenever a level is dropped onto a
 * dimension card.
 */
export interface SchemaCanvasHierarchy {
  id: string;
  /** User-visible label. Defaults to the dimension name on creation. */
  name: string;
  /** Mondrian `hasAll` attribute — default true. */
  hasAll: boolean;
  /** Mondrian 4 `allMemberName` — overrides "All" with a custom label
   *  for the synthetic top member.  Optional. */
  allMemberName?: string;
  /** Mondrian 4 `defaultMember` — MDX-style member reference that
   *  becomes the default when this hierarchy is queried without an
   *  explicit member.  Optional. */
  defaultMember?: string;
  /** Mondrian 4 `caption` — display label distinct from `name`.
   *  Optional. */
  caption?: string;
  /** Mondrian 4 `description` — long-form documentation.  Optional. */
  description?: string;
  /** Ordered coarse → fine. */
  levels: SchemaCanvasLevel[];
  /** Mondrian `<Closure>` — parent-child hierarchy optimisation that
   *  pre-materialises (ancestor, descendant) pairs so MDX queries don't
   *  have to walk the parent chain at query time.  Used by FoodMart's
   *  HR Employee hierarchy. */
  closure?: {
    table: string;
    parentColumn: string;
    childColumn: string;
  };
}

/**
 * One Mondrian dimension. A dimension corresponds to one column on
 * the fact table (the `foreignKey`) joining to a `primaryKey` column
 * on a dimension table. Degenerate dimensions (no FK, lives on the
 * fact) leave foreignKey/primaryKey unset.
 */
export interface SchemaCanvasDimension {
  id: string;
  /** User-visible label, e.g. "Customer". */
  name: string;
  /** Column on the fact table linking to this dimension. Undefined
   *  for degenerate dimensions. */
  foreignKey?: string;
  /** The physical table this dim is authored against — pick it FIRST
   *  in the Dimensions pane before choosing attributes.  Every non-hanger
   *  dim needs one; the Attributes pane shows this table's columns.
   *  Historical note: previously conflated with `primaryKeyTableId`; the
   *  import + store hydration both back-fill from `primaryKeyTableId` for
   *  old docs that only carried the merged field. */
  sourceTableId?: string;
  /** PK column on the dim's source table. Undefined for degenerate dims.
   *  Chosen from the attributes AFTER `sourceTableId` is set. */
  primaryKey?: string;
  /** Which on-canvas table holds the PK column.  ONLY set explicitly
   *  when the dim is a snowflake (attribute lives on a table other than
   *  `sourceTableId`).  For normal dims, treat as `sourceTableId`. */
  primaryKeyTableId?: string;
  /** Ordered hierarchies. */
  hierarchies: SchemaCanvasHierarchy[];
  /** Set theory: A ⊆ T.  The user-chosen subset of the source's
   *  columns that this dim is allowed to draw from when composing
   *  hierarchies.  Each attribute is a {tableId, columnName} pair so
   *  the same data can come from any of the source's tables (for
   *  join-group sources).
   *  - `name` is the Mondrian `<Attribute name="...">` logical label;
   *    used by `<Level attribute="X"/>` references inside hierarchies.
   *  - `keyColumns` (optional) carries composite-key columns when the
   *    Mondrian `<Key><Column/><Column/></Key>` had more than one column.
   *    Empty / absent → use `columnName` alone.  Round-trip preserved.
   *  Undefined means "no attributes picked yet" — distinct from the
   *  empty array, which means "all excluded". */
  attributes?: {
    tableId: string;
    columnName: string;
    name?: string;
    keyColumns?: string[];
    /** Mondrian 4 `nameColumn` — the column whose value is DISPLAYED for a
     *  member, when it differs from the key column (e.g. key on `product_id`,
     *  display `product_name`). Optional. */
    nameColumn?: string;
    /** Mondrian 4 `captionColumn` — column supplying a per-member caption.
     *  Optional. */
    captionColumn?: string;
    /** Mondrian 4 `orderByColumn` — column members are sorted by, when it
     *  differs from the key. Optional. */
    orderByColumn?: string;
    /** Mondrian 4 `description` — long-form documentation. Optional. */
    description?: string;
  }[];
  /** Mondrian 4 `hanger='true'` — a dim with no underlying table whose
   *  members are sliced via MDX expressions ("Actual vs Budget" style).
   *  No primaryKey / no FK link to a fact.  Cube authors use these for
   *  scenario filters that aren't backed by data. */
  hanger?: boolean;
  /** When the dim was sourced from a join group (rather than a single
   *  table), this is the group's key — used to label the dim as a
   *  join-source in UIs even after the user renames it. */
  sourceJoinGroupKey?: string;
  /** Mondrian 4 `caption` — display label distinct from `name`. */
  caption?: string;
  /** Mondrian 4 `description` — long-form documentation. */
  description?: string;
  /** Mondrian 4 `type` — Standard | Time | Geographic.  Default
   *  Standard.  Drives engine-side semantics (Time dimensions get
   *  special MDX functions like ParallelPeriod). */
  dimensionType?: "Standard" | "Time" | "Geographic";
  /**
   * Optional `saiku.semantic.*` annotations (description, synonyms) — business
   * context for the AI Ask layer. Keyed by bare suffix; emitted as
   * `<Annotation name="saiku.semantic.<key>">`.
   */
  annotations?: Record<string, string>;
}

/**
 * One Mondrian measure. Measures aggregate a numeric column on the
 * fact table — the workbench enforces only that `tableId` references
 * an on-canvas table; promoting one to the fact role is Step 1's job.
 */
export interface SchemaCanvasMeasure {
  id: string;
  /** User-visible label, e.g. "Sales". */
  name: string;
  aggregator:
    | "sum"
    | "count"
    | "avg"
    | "min"
    | "max"
    | "distinct-count"
    | "median"
    | "percentile";
  /** Canvas table id the column lives on (typically the fact). */
  tableId: string;
  /** Column to aggregate. */
  columnName: string;
  /** Mondrian formatString, e.g. "$#,##0". */
  formatString?: string;
  /**
   * Percentile fraction (0–100). Only meaningful when
   * {@link aggregator} === 'percentile' — median is the implicit 50th
   * percentile and carries no value. Emitted as `percentile="N"`; defaults
   * to 50 when omitted.
   */
  percentile?: number | null;
  /**
   * Optional `saiku.semantic.*` annotations (description, synonyms, unit,
   * currency, aggregation_kind, pii) — drive the AI Ask layer + PII
   * protection. Keyed by bare suffix; emitted as
   * `<Annotation name="saiku.semantic.<key>">`.
   */
  annotations?: Record<string, string>;
}

/**
 * Cube model — the serialisable half of the Facts & Measures workbench.
 *
 * The workbench (`WorkbenchView.svelte`) owns a richer per-cube state that
 * also carries UI-only flags (edit modes, confirm flags, selection cursors,
 * search text, seq counters).  Those stay component-local.  What lives in the
 * doc is only the durable model an external caller (e.g. DimSum) needs to
 * create and the exporter needs to emit: cubes → measure groups → measure
 * columns + dimension links + calculated members.  A measure group's
 * `measureColumns` map to `SchemaCanvasState.measures` by
 * (tableId === factTableId, columnName === col) — NOT by id.
 */
export interface SchemaCanvasDimensionLink {
  dimensionId: string;
  foreignKeyColumn: string;
  /** Undefined ≡ 'foreign-key' (the v1 default). */
  linkKind?: "foreign-key" | "fact" | "reference";
  viaDimension?: string;
  viaAttribute?: string;
}

export interface SchemaCanvasCalcToken {
  kind: "measure" | "op";
  /** Present for `kind === 'measure'`. */
  name?: string;
  /**
   * Whether a measure token references a whole measure GROUP rather than a
   * single measure.  Boolean to match the workbench's calc builder — the
   * chip highlights differently and the group expands to its members on
   * emit.
   */
  fromGroup?: boolean;
  /** Present for `kind === 'op'`. */
  op?: "+" | "-" | "*" | "/";
}

export interface SchemaCanvasCalc {
  id: string;
  name: string;
  tokens: SchemaCanvasCalcToken[];
  /** Free-text formula for `mode === 'expression'`. */
  formula?: string;
  mode?: "build" | "expression";
}

/**
 * One Mondrian-4 declarative `<TimeCalc>` — a cube-scoped time-intelligence
 * metric that the engine desugars into a `<CalculatedMember>` on `[Measures]`.
 * Requires a typed Time dimension with a `TimeYears` level in the cube. See
 * docs.saiku.bi/mondrian/time-intelligence.
 */
export interface SchemaCanvasTimeCalc {
  id: string;
  /** Generated calculated-member name, e.g. "Revenue YoY". */
  name: string;
  /** Metric type. */
  type: "yoy" | "pop" | "ytd" | "rolling";
  /** Name of an existing measure in the cube. */
  measure: string;
  /** Time dimension name — required only when the cube has >1 Time dimension. */
  timeDimension?: string;
  /** Rolling only: number of periods in the window (> 0). */
  window?: number;
  /** Rolling only: aggregation over the window (default sum). */
  function?: "sum" | "avg";
  /** MDX format string, e.g. "0.0%". */
  formatString?: string;
}

export interface SchemaCanvasMeasureGroup {
  id: string;
  name: string;
  /** Fact-table column names this group aggregates.  Resolve aggregator /
   *  format from `SchemaCanvasState.measures` by (factTableId, column). */
  measureColumns: string[];
  /** Canvas table id this group's measures live on. */
  factTableId?: string | null;
  dimensionLinks?: SchemaCanvasDimensionLink[];
  /** Human captions per measure column — emitted as `<Measure caption>`. */
  measureCaptions?: Record<string, string>;
}

export interface SchemaCanvasCube {
  id: string;
  name: string;
  measureGroups: SchemaCanvasMeasureGroup[];
  calcs: SchemaCanvasCalc[];
  /** Declarative time-intelligence metrics (yoy/pop/ytd/rolling), #time-intel. */
  timeCalcs?: SchemaCanvasTimeCalc[];
}

export interface SchemaCanvasState {
  /** Persistence schema version — bump when shape changes. */
  version: 1;
  /** Connection id this canvas is being authored against. */
  connectionId: string;
  /** Optional human label for the in-progress schema. */
  label: string;
  /**
   * Lineage id of the schema this canvas is refining (#877 sibling).
   * Set when the author picks "Open" on an existing library entry so
   * the next Save action bumps THAT lineage's version rather than
   * minting a new one.  Null / undefined when the canvas is a fresh
   * cube or a duplicate.
   */
  lineageId?: string | null;
  tables: SchemaCanvasTable[];
  joins: SchemaCanvasJoin[];
  groups: SchemaCanvasGroup[];
  /**
   * Human-renamed labels for join groups, keyed by the join's
   * canonical key (shared column name when source.col === target.col,
   * otherwise the sorted column-pair). Lets Amelia rename a
   * `customer_id` group to "Customer" and have every existing AND
   * future join with that canonical key inherit the label.
   */
  joinGroupRenames?: Record<string, string>;
  /**
   * Dimensions Workbench output — the Mondrian-shaped dimensions
   * authored against the join graph in Step 1. Optional for
   * back-compat with older docs in localStorage; the store fills
   * in an empty array on load.
   */
  dimensions?: SchemaCanvasDimension[];
  /** Mondrian measures — fact-side aggregations. Optional for
   *  back-compat with older docs. */
  measures?: SchemaCanvasMeasure[];
  /**
   * Cubes — measure groups + calculated members authored in the Facts &
   * Measures workbench.  Optional for back-compat with older docs; the
   * store fills in an empty array on load (and migrates the workbench's
   * former localStorage `saiku.workbench.cubesState` on first load).
   */
  cubes?: SchemaCanvasCube[];
  /** ISO timestamp; bumped on every mutation so we can show "last edited X minutes ago". */
  updatedAt: string;
}

/** A row in the sidebar's source-table catalog — a candidate the user can drag onto the canvas. */
export interface SourceTableCandidate {
  schema: string | null;
  name: string;
  columns: ProfileColumnSummary[];
  /** True if the user has already pulled this onto the canvas (drives the sidebar's "on canvas" indicator). */
  onCanvas: boolean;
}

/** Re-export for convenience. */
export type { ProfileTableSummary, ProfileColumnSummary };
