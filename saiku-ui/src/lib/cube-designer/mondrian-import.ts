/**
 * Canvas schema designer — Mondrian XML import.
 *
 * Parses a Mondrian 3- OR 4-style Schema XML back into a
 * SchemaCanvasState so the user can round-trip a schema:
 * canvas → Export XML → … → Import XML → canvas.
 *
 * Mondrian 3 (Pentaho legacy) shape — fact + dims inline in <Cube>:
 *
 *   <Schema name="…">
 *     <Cube name="…">
 *       <Table name="fact" schema="public" />
 *       <Dimension name="…" foreignKey="…">
 *         <Hierarchy hasAll="true" primaryKey="…">
 *           <Table name="dim" schema="public" />
 *           <Level name="…" column="…" />
 *           …
 *         </Hierarchy>
 *       </Dimension>
 *       <Measure … />
 *     </Cube>
 *   </Schema>
 *
 * Mondrian 4 (Saiku Cloud native) shape — physical schema + cube linkage:
 *
 *   <Schema name="…" metamodelVersion="4.0">
 *     <PhysicalSchema>
 *       <Table name="sales_fact" schema="public">
 *         <Key name="key0"><Column name="id" /></Key>
 *       </Table>
 *       <Table name="customer" schema="public">
 *         <Key name="key0"><Column name="customer_id" /></Key>
 *       </Table>
 *       <Link source="customer" target="sales_fact">
 *         <ForeignKey>
 *           <Column table="sales_fact" name="customer_id" />
 *         </ForeignKey>
 *       </Link>
 *     </PhysicalSchema>
 *     <Cube name="Sales">
 *       <Dimensions>
 *         <Dimension name="Customer" table="customer" key="key0">
 *           <Attributes>
 *             <Attribute name="Name" keyColumn="fullname" />
 *           </Attributes>
 *         </Dimension>
 *       </Dimensions>
 *       <MeasureGroups>
 *         <MeasureGroup name="Sales" table="sales_fact">
 *           <Measures>
 *             <Measure name="Amount" column="amount" aggregator="sum" />
 *           </Measures>
 *           <DimensionLinks>
 *             <ForeignKeyLink dimension="Customer" foreignKeyColumn="customer_id" />
 *           </DimensionLinks>
 *         </MeasureGroup>
 *       </MeasureGroups>
 *     </Cube>
 *   </Schema>
 *
 * Detection: `metamodelVersion="4.0"` on <Schema>, or the presence of a
 * <PhysicalSchema> / <MeasureGroups> child. Out-of-band shapes are
 * skipped with a warning rather than silently dropped.
 */
import type {
  SchemaCanvasState,
  SchemaCanvasTable,
  SchemaCanvasJoin,
  SchemaCanvasColumn,
  SchemaCanvasDimension,
  SchemaCanvasHierarchy,
  SchemaCanvasLevel,
  SchemaCanvasMeasure,
  SchemaCanvasTimeCalc,
  SourceTableCandidate,
} from "./types.js";

/**
 * Read a schema element's `saiku.semantic.*` annotations into a bare-keyed map
 * (e.g. `{ description, pii }`). Returns undefined when there are none, so the
 * caller can leave the field unset. Non-saiku annotations are ignored.
 */
function readSemanticAnnotations(
  el: Element,
): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const aEl of Array.from(
    el.querySelectorAll(":scope > Annotations > Annotation"),
  )) {
    const name = aEl.getAttribute("name") ?? "";
    if (!name.startsWith("saiku.semantic.")) continue;
    const key = name.slice("saiku.semantic.".length);
    if (key) out[key] = aEl.textContent?.trim() ?? "";
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function makeId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Per-cube workbench shape — serialisable mirror of `WorkbenchCube` from
 * WorkbenchView.svelte, minus the UI-only edit flags.  Importer fills
 * this from `<Cube>` blocks; +page.svelte maps it to the trimmed doc cube
 * shape and writes it via `store.setCubes`, then remounts the WorkbenchView
 * so it hydrates from `doc.cubes`.
 */
/**
 * Mondrian 4 measure-group → dim link.  Three flavours:
 *
 *   - `foreign-key` (default) — `<ForeignKeyLink dimension='X' foreignKeyColumn='Y'/>`.
 *     The fact-side column joining to the dim's primary key.
 *   - `fact` — `<FactLink dimension='X'/>`.  Degenerate dim whose level
 *     columns ARE on the fact table; no FK column needed.
 *   - `reference` — `<ReferenceLink dimension='X' viaDimension='Y'
 *     viaAttribute='Z'/>`.  Multi-hop: the dim is reached through another
 *     dim's attribute (FoodMart HR's Store via Employee.store_id).
 *
 * v1 only modelled `foreign-key`; v2 carries the link kind through
 * round-trip so authors who hand-author FactLink / ReferenceLink don't
 * lose them on export.
 */
export interface ImportedWorkbenchMeasureGroup {
  id: string;
  name: string;
  factTableId: string | null;
  measureColumns: string[];
  dimensionLinks: {
    dimensionId: string;
    foreignKeyColumn: string;
    linkKind?: "foreign-key" | "fact" | "reference";
    viaDimension?: string;
    viaAttribute?: string;
  }[];
}
export interface ImportedWorkbenchCalc {
  id: string;
  name: string;
  tokens: never[];
  formula?: string;
}
export interface ImportedWorkbenchCube {
  id: string;
  name: string;
  measureGroups: ImportedWorkbenchMeasureGroup[];
  factsCalcs: ImportedWorkbenchCalc[];
  /** Declarative <TimeCalc> metrics read back from the schema. */
  timeCalcs?: SchemaCanvasTimeCalc[];
}

export interface MondrianImportResult {
  state: SchemaCanvasState;
  warnings: string[];
  /** Populated by the Mondrian-4 importer: one workbench cube per `<Cube>`
   *  in the schema, with MGs/measures/dim-links/calcs ready to seed
   *  the workbench.  Empty array on Mondrian-3 imports or
   *  PhysicalSchema-only imports. */
  workbenchCubes: ImportedWorkbenchCube[];
}

/**
 * Thrown when the XML referenced one or more tables without a schema
 * attribute AND those table names exist in more than one schema in the
 * active catalog. The caller is expected to prompt the user to pick a
 * schema per table, then re-run the import with `schemaOverrides` set.
 */
export class AmbiguousSchemaError extends Error {
  readonly name = "AmbiguousSchemaError";
  constructor(
    public readonly ambiguities: Array<{
      tableName: string;
      candidateSchemas: string[];
    }>,
  ) {
    super(
      `Schema is ambiguous for: ${ambiguities.map((a) => a.tableName).join(", ")}.`,
    );
  }
}

interface ImportOpts {
  /** Connection id the imported doc will live under. */
  connectionId: string;
  /** Schema label override (defaults to the <Schema name="…"> attribute). */
  label?: string;
  /** Live source catalog — used to enrich columns when the XML only
   *  names a few. If a referenced table isn't in the catalog the
   *  importer falls back to whatever columns the XML mentions. */
  sourceTables?: SourceTableCandidate[];
  /**
   * Per-table schema overrides keyed by lowercased table name. Used
   * after the user resolves an `AmbiguousSchemaError` — the importer
   * applies the picked schema instead of guessing or throwing again.
   */
  schemaOverrides?: Record<string, string>;
}

export function importFromMondrianXml(
  xml: string,
  opts: ImportOpts,
): MondrianImportResult {
  if (typeof window === "undefined") {
    throw new Error("XML import is only available in the browser.");
  }
  const parser = new DOMParser();
  const dom = parser.parseFromString(xml, "application/xml");
  const err = dom.querySelector("parsererror");
  if (err)
    throw new Error(
      `XML parse error: ${err.textContent?.slice(0, 240) ?? "unknown"}`,
    );

  // Accept either a full `<Schema>` doc OR a bare `<PhysicalSchema>`
  // block.  The bare-PhysicalSchema route is for "I only care about the
  // table layout" pastes; a full <Schema> ALWAYS has a <PhysicalSchema>
  // child too, so we route by ROOT element tag, not by presence of a
  // <PhysicalSchema> descendant.  Previously the dispatcher fell through
  // to parsePhysicalSchemaOnly for every M4 schema, silently skipping
  // the cube walker.
  const rootEl = dom.documentElement;
  if (rootEl?.tagName === "PhysicalSchema") {
    return parsePhysicalSchemaOnly(rootEl, opts);
  }

  const schemaEl = dom.querySelector("Schema");
  if (!schemaEl) {
    throw new Error(
      "Expected a <Schema> or <PhysicalSchema> element at the root.",
    );
  }

  const isM4 =
    schemaEl.getAttribute("metamodelVersion") === "4.0" ||
    schemaEl.querySelector(":scope > Cube > MeasureGroups") !== null;

  return isM4 ? parseM4(schemaEl, opts) : parseM3(schemaEl, opts);
}

/**
 * True when the pasted XML is a legacy Mondrian 3 `<Schema>` — i.e. it has no
 * Mondrian-4 marker (`metamodelVersion="4…"`) and no `<MeasureGroups>`. Used by
 * the import flow (saiku-cloud#1133) to decide whether to upgrade the paste to
 * faithful M4 (via the gateway) BEFORE handing it to {@link importFromMondrianXml},
 * because the canvas editor is Mondrian-4 native and can't meaningfully open an
 * M3 schema.
 *
 * Robust to any 4.x (the engine's upgrader emits `4.8`, not just `4.0`). A bare
 * `<PhysicalSchema>` paste, a non-Schema document, or malformed XML all return
 * `false` (not M3) — those are handled unchanged by the importer, which surfaces
 * its own clear error.
 */
export function isMondrian3Xml(xml: string): boolean {
  if (typeof window === "undefined") return false;
  let dom: Document;
  try {
    dom = new DOMParser().parseFromString(xml, "application/xml");
  } catch {
    return false;
  }
  if (dom.querySelector("parsererror")) return false;
  if (dom.documentElement?.tagName === "PhysicalSchema") return false;
  const schemaEl = dom.querySelector("Schema");
  if (!schemaEl) return false;
  const metamodelVersion = (
    schemaEl.getAttribute("metamodelVersion") ?? ""
  ).trim();
  if (metamodelVersion.startsWith("4")) return false;
  if (schemaEl.querySelector(":scope > Cube > MeasureGroups") !== null)
    return false;
  return true;
}

/**
 * The physical column a `<Measure>` aggregates. Mondrian 4 carries it in
 * `<Arguments><Column name="…"/></Arguments>`; legacy/hand-written schemas
 * may instead use a bare `column="…"` attribute. Returns null for measures
 * with no column (e.g. count-of-rows measures) — the caller decides.
 */
function measureColumnOf(measureEl: Element): string | null {
  const attr = measureEl.getAttribute("column");
  if (attr) return attr;
  const argCol = measureEl.querySelector(":scope > Arguments > Column");
  return argCol?.getAttribute("name") ?? null;
}

// ─── PhysicalSchema-only parser (peer model) ───────────────────────────────

/**
 * Parses just the `<PhysicalSchema>` block — every `<Table>` becomes a
 * peer canvas node, every `<Link>` becomes a join. No fact/dim
 * distinction, no cube parsing. Matches the canvas's peer model where
 * tables are equal and joins are arbitrary.
 *
 * Source side of each Link uses the source table's `<Key><Column>` as
 * its join column; target side uses the `<ForeignKey><Column>` named
 * column.
 */
function parsePhysicalSchemaOnly(
  physEl: Element,
  opts: ImportOpts,
): MondrianImportResult {
  const warnings: string[] = [];
  // PhysicalSchema-only imports don't carry cube metadata — return an
  // empty array so the +page.svelte hydration path is unchanged.
  const workbenchCubes: ImportedWorkbenchCube[] = [];

  // Phase 1 — harvest tables.
  interface PhysTable {
    schema: string | null;
    name: string;
    columns: Set<string>;
    keyColumn: string | null;
  }
  const physTables = new Map<string, PhysTable>();
  for (const tEl of Array.from(physEl.querySelectorAll(":scope > Table"))) {
    const name = tEl.getAttribute("name");
    if (!name) continue;
    const schema = tEl.getAttribute("schema");
    const columns = new Set<string>();
    for (const cEl of Array.from(tEl.querySelectorAll("Column"))) {
      const col = cEl.getAttribute("name");
      if (col) columns.add(col);
    }
    const keyEl = tEl.querySelector(":scope > Key > Column");
    physTables.set(name, {
      schema,
      name,
      columns,
      keyColumn: keyEl?.getAttribute("name") ?? null,
    });
  }
  if (physTables.size === 0) {
    throw new Error("<PhysicalSchema> contained no <Table> elements.");
  }

  // Detect ambiguous schemas — names without `schema=` (and no override)
  // that exist in MULTIPLE schemas in the live catalog. The host has to
  // prompt the user to pick before we can proceed.
  const overrides = opts.schemaOverrides ?? {};
  if (opts.sourceTables && opts.sourceTables.length > 0) {
    const ambiguities: Array<{
      tableName: string;
      candidateSchemas: string[];
    }> = [];
    for (const [name, phys] of physTables) {
      const lower = name.toLowerCase();
      if (phys.schema) continue; // already qualified
      if (overrides[lower]) continue; // user resolved it
      const candidates: string[] = [];
      for (const t of opts.sourceTables) {
        if (t.name.toLowerCase() === lower) {
          const sch = t.schema ?? "";
          if (sch && !candidates.includes(sch)) candidates.push(sch);
        }
      }
      if (candidates.length >= 2) {
        ambiguities.push({
          tableName: name,
          candidateSchemas: candidates.sort(),
        });
      }
    }
    if (ambiguities.length > 0) {
      throw new AmbiguousSchemaError(ambiguities);
    }
  }

  // Phase 2 — drop the tables onto the canvas. We arrange them in a
  // loose grid so the user has somewhere to start; they can re-arrange.
  const tables: SchemaCanvasTable[] = [];
  const tableIdByName = new Map<string, string>();
  const namesSorted = [...physTables.keys()].sort();
  const cols = Math.max(1, Math.ceil(Math.sqrt(namesSorted.length)));
  namesSorted.forEach((name, i) => {
    const phys = physTables.get(name)!;
    // Pre-resolve schema using the user's override (set after they
    // resolved an AmbiguousSchemaError) before falling back to
    // what the XML provided.
    const overriddenSchema = overrides[name.toLowerCase()] ?? phys.schema;
    const catalogHit = lookupTableInCatalog(
      opts.sourceTables,
      overriddenSchema,
      name,
    );
    const resolvedSchema = catalogHit?.schema ?? overriddenSchema;
    const initialCols: SchemaCanvasColumn[] = catalogHit
      ? catalogHit.columns.map((c) => ({ name: c.name, sqlType: c.sqlType }))
      : [...phys.columns].map((n) => ({ name: n, sqlType: "UNKNOWN" }));
    if (!catalogHit) {
      warnings.push(
        `"${name}" wasn't found in the active connection's catalog — imported with only the columns the XML referenced. Switch to the right connection to enrich it.`,
      );
    } else if (
      (phys.schema ?? "") !== (catalogHit.schema ?? "") &&
      phys.schema
    ) {
      warnings.push(
        `"${name}" was schema-qualified as "${phys.schema}" in the XML but matched "${catalogHit.schema}" in the catalog. Using the catalog's schema.`,
      );
    }
    const id = makeId("tbl");
    tables.push({
      id,
      schema: resolvedSchema,
      name,
      role: "dimension",
      columns: initialCols,
      position: {
        x: 120 + (i % cols) * 320,
        y: 120 + Math.floor(i / cols) * 280,
      },
      groupId: null,
    });
    tableIdByName.set(name, id);
  });

  // Phase 3 — parse <Link> rows into joins. Each Link is `source` →
  // `target` with a `<ForeignKey><Column>` naming the FK column on
  // the target. Source side uses its declared Key column.
  const joins: SchemaCanvasJoin[] = [];
  for (const linkEl of Array.from(physEl.querySelectorAll(":scope > Link"))) {
    const sourceName = linkEl.getAttribute("source");
    const targetName = linkEl.getAttribute("target");
    if (!sourceName || !targetName) {
      warnings.push("<Link> with missing source/target attribute — skipped.");
      continue;
    }
    const fkColEl = linkEl.querySelector("ForeignKey > Column");
    const fkColName = fkColEl?.getAttribute("name");
    if (!fkColName) {
      warnings.push(
        `<Link source="${sourceName}" target="${targetName}"> has no <ForeignKey><Column> — skipped.`,
      );
      continue;
    }
    const sourceId = tableIdByName.get(sourceName);
    const targetId = tableIdByName.get(targetName);
    if (!sourceId || !targetId) {
      warnings.push(
        `<Link source="${sourceName}" target="${targetName}"> references an undefined table — skipped.`,
      );
      continue;
    }
    const sourcePhys = physTables.get(sourceName);
    const sourceKey = sourcePhys?.keyColumn ?? "id";
    // Make sure both columns appear on their tables for the canvas
    // to render the join endpoints meaningfully.
    const sourceTable = tables.find((t) => t.id === sourceId);
    if (sourceTable && !sourceTable.columns.some((c) => c.name === sourceKey)) {
      sourceTable.columns.push({ name: sourceKey, sqlType: "UNKNOWN" });
    }
    const targetTable = tables.find((t) => t.id === targetId);
    if (targetTable && !targetTable.columns.some((c) => c.name === fkColName)) {
      targetTable.columns.push({ name: fkColName, sqlType: "UNKNOWN" });
    }
    joins.push({
      id: makeId("join"),
      sourceTableId: sourceId,
      sourceColumnName: sourceKey,
      targetTableId: targetId,
      targetColumnName: fkColName,
      kind: "inner",
      // PhysicalSchema-only parse — these are explicit <Link> joins.
      origin: "physical",
    });
  }

  const state: SchemaCanvasState = {
    version: 1,
    connectionId: opts.connectionId,
    label: opts.label ?? "",
    tables,
    joins,
    groups: [],
    updatedAt: new Date().toISOString(),
  };
  return { state, warnings, workbenchCubes };
}

// ─── shared helpers ────────────────────────────────────────────────────────

function lookupColumnsFromCatalog(
  sourceTables: SourceTableCandidate[] | undefined,
  schema: string | null,
  name: string,
): SchemaCanvasColumn[] | null {
  const hit = lookupTableInCatalog(sourceTables, schema, name);
  return hit
    ? hit.columns.map((c) => ({ name: c.name, sqlType: c.sqlType }))
    : null;
}

/**
 * Resolve a (schema, name) pair against the live source-table catalog.
 *
 * Match precedence:
 *   1. Exact `schema.name` (case-insensitive) — preferred.
 *   2. Just `name` (case-insensitive) — for XML that omits `schema=`,
 *      or names something with a different schema than what's in the
 *      catalog. If multiple tables across schemas share the same name,
 *      the FIRST one wins (caller gets a warning hook if it cares).
 *
 * Returns the matched `SourceTableCandidate` so the caller can inherit
 * both the schema attribute AND the full column list, so an imported
 * `product_class` becomes `public.product_class` with every column
 * present instead of just the few the XML happened to name.
 */
function lookupTableInCatalog(
  sourceTables: SourceTableCandidate[] | undefined,
  schema: string | null,
  name: string,
): SourceTableCandidate | null {
  if (!sourceTables || sourceTables.length === 0) return null;
  const lowerName = name.toLowerCase();
  const lowerSchema = schema?.toLowerCase() ?? null;
  let nameOnlyHit: SourceTableCandidate | null = null;
  for (const t of sourceTables) {
    if (t.name.toLowerCase() !== lowerName) continue;
    if (
      lowerSchema !== null &&
      (t.schema?.toLowerCase() ?? null) === lowerSchema
    ) {
      return t;
    }
    if (nameOnlyHit === null) nameOnlyHit = t;
  }
  return nameOnlyHit;
}

// ─── Mondrian 3 — existing path ────────────────────────────────────────────

function parseM3(schemaEl: Element, opts: ImportOpts): MondrianImportResult {
  const warnings: string[] = [];
  const schemaName = schemaEl.getAttribute("name") ?? "";

  const cubeEls = Array.from(schemaEl.querySelectorAll(":scope > Cube"));
  if (cubeEls.length === 0) throw new Error("Schema has no <Cube>.");

  const keyFor = (schema: string | null, name: string) =>
    `${(schema ?? "").toLowerCase()}::${name.toLowerCase()}`;

  // ── Phase 0 — collect schema-level shared <Dimension> blocks ──
  // FoodMart-style: <Schema><Dimension name="Time"><Hierarchy…><Table/>
  // </Hierarchy></Dimension>…<Cube><DimensionUsage source="Time" …/></Cube>
  // Cubes reference shared dims via <DimensionUsage source=X foreignKey=Y>
  // so Phase 3 has to resolve X → the shared dim's PK table + PK column.
  interface HierarchyResolved {
    primaryKey: string | null;
    // PK-bearing table — where the fact's foreignKey joins.  Comes
    // from Hierarchy@primaryKeyTable when set, otherwise the first
    // <Table> in the hierarchy (bare or leftmost inside a <Join>).
    pkTable: { schema: string | null; name: string } | null;
    tables: Array<{ schema: string | null; name: string; xmlEl: Element }>;
    // Snowflake internal joins from <Join leftKey="X" rightKey="Y">
    // with two Tables inside.  Only the simple 2-table form is
    // supported; deeper nesting is warned about and dropped.
    snowflakeJoins: Array<{
      leftTable: { schema: string | null; name: string };
      leftCol: string;
      rightTable: { schema: string | null; name: string };
      rightCol: string;
    }>;
  }
  function resolveHierarchy(
    hierEl: Element,
    dimNameForWarn: string,
  ): HierarchyResolved | null {
    const primaryKey = hierEl.getAttribute("primaryKey");
    const pkTableAttr = hierEl.getAttribute("primaryKeyTable");
    const tables: HierarchyResolved["tables"] = [];
    const snowflakeJoins: HierarchyResolved["snowflakeJoins"] = [];

    // Simple form: <Hierarchy><Table name=…/></Hierarchy>
    const bareTableEl = hierEl.querySelector(":scope > Table");
    if (bareTableEl) {
      const name = bareTableEl.getAttribute("name");
      if (name) {
        tables.push({
          schema: bareTableEl.getAttribute("schema"),
          name,
          xmlEl: bareTableEl,
        });
      }
    }

    // Snowflake form: <Hierarchy><Join leftKey=… rightKey=…>
    //   <Table name="a"/><Table name="b"/></Join></Hierarchy>
    const joinEl = hierEl.querySelector(":scope > Join");
    if (joinEl) {
      const leftKey = joinEl.getAttribute("leftKey");
      const rightKey = joinEl.getAttribute("rightKey");
      const joinTables = Array.from(joinEl.querySelectorAll(":scope > Table"));
      const nestedJoins = Array.from(joinEl.querySelectorAll(":scope > Join"));
      if (nestedJoins.length > 0) {
        warnings.push(
          `Dimension "${dimNameForWarn}" uses nested <Join> — only simple 2-table snowflakes are wired for now.`,
        );
      }
      if (joinTables.length >= 1) {
        const t0 = joinTables[0];
        const t0name = t0.getAttribute("name");
        if (t0name) {
          tables.push({
            schema: t0.getAttribute("schema"),
            name: t0name,
            xmlEl: t0,
          });
        }
      }
      if (joinTables.length >= 2 && leftKey && rightKey) {
        const t0 = joinTables[0];
        const t1 = joinTables[1];
        const t0name = t0.getAttribute("name");
        const t1name = t1.getAttribute("name");
        if (t1name) {
          tables.push({
            schema: t1.getAttribute("schema"),
            name: t1name,
            xmlEl: t1,
          });
        }
        if (t0name && t1name) {
          snowflakeJoins.push({
            leftTable: { schema: t0.getAttribute("schema"), name: t0name },
            leftCol: leftKey,
            rightTable: { schema: t1.getAttribute("schema"), name: t1name },
            rightCol: rightKey,
          });
        }
      }
    }

    if (tables.length === 0) return null;

    // PK-bearing table — primaryKeyTable attr overrides; else first.
    let pkTable: HierarchyResolved["pkTable"] = tables[0];
    if (pkTableAttr) {
      const hit = tables.find(
        (t) => t.name.toLowerCase() === pkTableAttr.toLowerCase(),
      );
      if (hit) pkTable = hit;
    }
    return { primaryKey, pkTable, tables, snowflakeJoins };
  }

  // Schema-level dims — keyed by <Dimension name="…">.
  interface SharedDim {
    name: string;
    hierarchy: HierarchyResolved;
  }
  const sharedDims = new Map<string, SharedDim>();
  for (const dimEl of Array.from(
    schemaEl.querySelectorAll(":scope > Dimension"),
  )) {
    const dimName = dimEl.getAttribute("name");
    if (!dimName) continue;
    const hierarchyEl = dimEl.querySelector(":scope > Hierarchy");
    if (!hierarchyEl) continue;
    const hier = resolveHierarchy(hierarchyEl, dimName);
    if (hier) sharedDims.set(dimName, { name: dimName, hierarchy: hier });
  }

  // ── Phase 1 — derive the PHYSICAL SCHEMA ──
  interface PhysDescriptor {
    schema: string | null;
    name: string;
    role: "fact" | "dimension";
    xmlHost: Element; // for column enrichment from XML
  }
  const phys = new Map<string, PhysDescriptor>();

  function ensurePhys(
    schema: string | null,
    name: string,
    role: "fact" | "dimension",
    xmlHost: Element,
  ) {
    const k = keyFor(schema, name);
    const existing = phys.get(k);
    if (existing) {
      if (role === "fact") existing.role = "fact"; // fact wins
    } else {
      phys.set(k, { schema, name, role, xmlHost });
    }
  }

  for (const cubeEl of cubeEls) {
    const cubeName = cubeEl.getAttribute("name") ?? "(unnamed)";

    const factEl = cubeEl.querySelector(":scope > Table");
    if (!factEl) {
      warnings.push(`Cube "${cubeName}" has no <Table> (fact); ignored.`);
      continue;
    }
    const factName = factEl.getAttribute("name");
    if (!factName) {
      warnings.push(
        `Cube "${cubeName}" fact <Table> has no name attribute; ignored.`,
      );
      continue;
    }
    ensurePhys(factEl.getAttribute("schema"), factName, "fact", cubeEl);

    // Inline <Dimension> in the cube (rare but possible).
    for (const dimEl of Array.from(
      cubeEl.querySelectorAll(":scope > Dimension"),
    )) {
      const dimName = dimEl.getAttribute("name") ?? "(inline)";
      const hierarchyEl = dimEl.querySelector(":scope > Hierarchy");
      if (!hierarchyEl) continue;
      const hier = resolveHierarchy(hierarchyEl, dimName);
      if (!hier) continue;
      for (const t of hier.tables) {
        ensurePhys(t.schema, t.name, "dimension", t.xmlEl);
      }
    }

    // <DimensionUsage source="X"> → resolve to schema-level shared dim.
    for (const usageEl of Array.from(
      cubeEl.querySelectorAll(":scope > DimensionUsage"),
    )) {
      const source = usageEl.getAttribute("source");
      if (!source) continue;
      const shared = sharedDims.get(source);
      if (!shared) {
        warnings.push(
          `Cube "${cubeName}" references dimension "${source}" via <DimensionUsage>, but no schema-level <Dimension name="${source}"> was found.`,
        );
        continue;
      }
      for (const t of shared.hierarchy.tables) {
        ensurePhys(t.schema, t.name, "dimension", t.xmlEl);
      }
    }
  }

  // ── Phase 2 — emit canvas tables from the physical schema ──
  // Inherit the CATALOG's schema attribute when the XML omitted it.
  // FoodMart-style XML writes bare `<Table name="sales_fact_1997"/>`
  // so p.schema is null; but the catalog stores it as `public::…`.
  // Without this inheritance the sidebar "on canvas" markers don't
  // light up (identity mismatch), Phase-3 join lookups miss, and
  // any downstream schema-qualified equality fails silently.
  const tables: SchemaCanvasTable[] = [];
  const tableIdByKey = new Map<string, string>();
  const physKeys = [...phys.keys()];
  const gridCols = Math.max(1, Math.ceil(Math.sqrt(physKeys.length)));
  physKeys.forEach((k, i) => {
    const p = phys.get(k)!;
    const catalogHit = lookupTableInCatalog(
      opts.sourceTables,
      p.schema,
      p.name,
    );
    const resolvedSchema = catalogHit?.schema ?? p.schema;
    const cols =
      (catalogHit
        ? catalogHit.columns.map((c) => ({ name: c.name, sqlType: c.sqlType }))
        : null) ?? collectColumnsFromM3Xml(p.xmlHost, p.name, p.role, warnings);
    const id = makeId("tbl");
    tables.push({
      id,
      schema: resolvedSchema,
      name: p.name,
      role: p.role,
      columns: cols,
      position: {
        x: 200 + (i % gridCols) * 320,
        y: 160 + Math.floor(i / gridCols) * 280,
      },
      groupId: null,
    });
    // Register under BOTH the original XML key (Phase 3 lookups use
    // the XML-declared identity) AND the catalog-resolved key (so
    // snowflake-join lookups matching resolved schemas hit too).
    tableIdByKey.set(k, id);
    const resolvedKey = keyFor(resolvedSchema, p.name);
    if (resolvedKey !== k) tableIdByKey.set(resolvedKey, id);
  });

  // ── Phase 3 — walk cubes to emit joins + workbench cubes ──
  const joins: SchemaCanvasJoin[] = [];
  const joinKeys = new Set<string>();
  const workbenchCubes: ImportedWorkbenchCube[] = [];

  function pushJoin(
    sourceTableId: string,
    sourceColumnName: string,
    targetTableId: string,
    targetColumnName: string,
  ) {
    const joinKey = `${sourceTableId}::${sourceColumnName}->${targetTableId}::${targetColumnName}`;
    if (joinKeys.has(joinKey)) return;
    joinKeys.add(joinKey);
    joins.push({
      id: makeId("join"),
      sourceTableId,
      sourceColumnName,
      targetTableId,
      targetColumnName,
      kind: "inner",
      origin: "physical",
    });
  }

  // Snowflake joins from schema-level shared dims land ONCE (they're
  // part of the physical schema, not tied to any specific cube).
  for (const shared of sharedDims.values()) {
    for (const sj of shared.hierarchy.snowflakeJoins) {
      const leftId = tableIdByKey.get(
        keyFor(sj.leftTable.schema, sj.leftTable.name),
      );
      const rightId = tableIdByKey.get(
        keyFor(sj.rightTable.schema, sj.rightTable.name),
      );
      if (leftId && rightId) pushJoin(leftId, sj.leftCol, rightId, sj.rightCol);
    }
  }

  for (const cubeEl of cubeEls) {
    const cubeName = cubeEl.getAttribute("name") ?? "(unnamed)";
    const factEl = cubeEl.querySelector(":scope > Table");
    const factName = factEl?.getAttribute("name");
    const factSchema = factEl?.getAttribute("schema") ?? null;
    if (!factName) continue;
    const factId = tableIdByKey.get(keyFor(factSchema, factName));
    if (!factId) continue;

    const measureCols: string[] = [];
    const dimensionLinks: ImportedWorkbenchMeasureGroup["dimensionLinks"] = [];
    for (const measureEl of Array.from(
      cubeEl.querySelectorAll(":scope > Measure"),
    )) {
      const col = measureEl.getAttribute("column");
      if (col) measureCols.push(col);
    }

    // Inline <Dimension> — existing path.
    for (const dimEl of Array.from(
      cubeEl.querySelectorAll(":scope > Dimension"),
    )) {
      const fk = dimEl.getAttribute("foreignKey");
      const dimName = dimEl.getAttribute("name") ?? "(inline)";
      const hierarchyEl = dimEl.querySelector(":scope > Hierarchy");
      if (!hierarchyEl) {
        warnings.push(
          `<Dimension name="${dimName}"> in cube "${cubeName}" has no <Hierarchy>; skipped.`,
        );
        continue;
      }
      const hier = resolveHierarchy(hierarchyEl, dimName);
      if (!hier || !hier.pkTable) continue;
      const dimId = tableIdByKey.get(
        keyFor(hier.pkTable.schema, hier.pkTable.name),
      );
      if (!dimId) continue;
      if (fk && hier.primaryKey) {
        pushJoin(dimId, hier.primaryKey, factId, fk);
        dimensionLinks.push({
          dimensionId: dimId,
          foreignKeyColumn: fk,
          linkKind: "foreign-key",
        });
      } else {
        warnings.push(
          `Dimension "${dimName}" in cube "${cubeName}" missing foreignKey/primaryKey; join skipped.`,
        );
      }
    }

    // <DimensionUsage> — resolve source → shared dim → PK table.
    for (const usageEl of Array.from(
      cubeEl.querySelectorAll(":scope > DimensionUsage"),
    )) {
      const usageName =
        usageEl.getAttribute("name") ??
        usageEl.getAttribute("source") ??
        "(usage)";
      const source = usageEl.getAttribute("source");
      const fk = usageEl.getAttribute("foreignKey");
      if (!source) {
        warnings.push(
          `<DimensionUsage name="${usageName}"> in cube "${cubeName}" has no source= attribute; skipped.`,
        );
        continue;
      }
      const shared = sharedDims.get(source);
      if (!shared || !shared.hierarchy.pkTable || !shared.hierarchy.primaryKey)
        continue;
      const dimId = tableIdByKey.get(
        keyFor(shared.hierarchy.pkTable.schema, shared.hierarchy.pkTable.name),
      );
      if (!dimId) continue;
      if (fk) {
        pushJoin(dimId, shared.hierarchy.primaryKey, factId, fk);
        dimensionLinks.push({
          dimensionId: dimId,
          foreignKeyColumn: fk,
          linkKind: "foreign-key",
        });
      } else {
        warnings.push(
          `<DimensionUsage name="${usageName}"> in cube "${cubeName}" has no foreignKey=; join skipped.`,
        );
      }
    }

    workbenchCubes.push({
      id: makeId("cube"),
      name: cubeName,
      measureGroups: [
        {
          id: makeId("mg"),
          name: cubeName,
          factTableId: factId,
          measureColumns: measureCols,
          dimensionLinks,
        },
      ],
      factsCalcs: [],
    });
  }

  const state: SchemaCanvasState = {
    version: 1,
    connectionId: opts.connectionId,
    label: opts.label ?? schemaName,
    tables,
    joins,
    groups: [],
    updatedAt: new Date().toISOString(),
  };
  return { state, warnings, workbenchCubes };
}

function collectColumnsFromM3Xml(
  scope: Element,
  tableName: string,
  role: "fact" | "dimension",
  warnings: string[],
): SchemaCanvasColumn[] {
  const names = new Set<string>();
  if (role === "fact") {
    for (const dim of Array.from(
      scope.querySelectorAll(":scope > Dimension"),
    )) {
      const fk = dim.getAttribute("foreignKey");
      if (fk) names.add(fk);
    }
  } else {
    const hier =
      scope.tagName === "Hierarchy"
        ? scope
        : scope.querySelector(":scope > Hierarchy");
    if (hier) {
      const pk = hier.getAttribute("primaryKey");
      if (pk) names.add(pk);
      for (const lvl of Array.from(hier.querySelectorAll(":scope > Level"))) {
        const col = lvl.getAttribute("column") ?? lvl.getAttribute("name");
        if (col) names.add(col);
      }
    }
  }
  if (names.size === 0) {
    warnings.push(
      `No columns could be inferred for "${tableName}" from XML; canvas node will render empty.`,
    );
  }
  return [...names].map((name) => ({ name, sqlType: "UNKNOWN" }));
}

// ─── Mondrian 4 — new path ─────────────────────────────────────────────────

function parseM4(schemaEl: Element, opts: ImportOpts): MondrianImportResult {
  const warnings: string[] = [];
  const schemaName = schemaEl.getAttribute("name") ?? "";
  // Populated by Phase 6 from <Cube> blocks — handed back to +page.svelte
  // for localStorage seeding.
  const workbenchCubes: ImportedWorkbenchCube[] = [];

  const physSchemaEl = schemaEl.querySelector(":scope > PhysicalSchema");

  // Phase 1: harvest every physical table — schema-qualified, with the
  // columns scraped from any <Column> descendants (Key declarations,
  // inline column defs, etc.).
  interface PhysTable {
    schema: string | null;
    name: string;
    columns: Set<string>;
    keyColumn: string | null;
  }
  const physTables = new Map<string, PhysTable>();
  if (physSchemaEl) {
    for (const tEl of Array.from(
      physSchemaEl.querySelectorAll(":scope > Table"),
    )) {
      const name = tEl.getAttribute("name");
      if (!name) continue;
      const schema = tEl.getAttribute("schema");
      const columns = new Set<string>();
      for (const cEl of Array.from(tEl.querySelectorAll("Column"))) {
        const col = cEl.getAttribute("name");
        if (col) columns.add(col);
      }
      const keyEl = tEl.querySelector(":scope > Key > Column");
      physTables.set(name, {
        schema,
        name,
        columns,
        keyColumn: keyEl?.getAttribute("name") ?? null,
      });
    }
  }

  // Phase 2: collect every <Cube> — the workbench needs them all.
  const cubeEls = Array.from(schemaEl.querySelectorAll(":scope > Cube"));
  if (cubeEls.length === 0) throw new Error("Schema has no <Cube>.");

  const tables: SchemaCanvasTable[] = [];
  const joins: SchemaCanvasJoin[] = [];
  const tableIdByName = new Map<string, string>();

  function ensureTable(
    tableName: string,
    role: "fact" | "dimension",
    position: { x: number; y: number },
  ): string {
    const existing = tableIdByName.get(tableName);
    if (existing) {
      // Upgrade dim → fact if it gets referenced as a MeasureGroup later.
      if (role === "fact") {
        const t = tables.find((tt) => tt.id === existing);
        if (t) t.role = "fact";
      }
      return existing;
    }
    const phys = physTables.get(tableName);
    const fromCatalog = phys
      ? lookupColumnsFromCatalog(opts.sourceTables, phys.schema, tableName)
      : lookupColumnsFromCatalog(opts.sourceTables, null, tableName);
    const initialCols: SchemaCanvasColumn[] =
      fromCatalog ??
      (phys
        ? [...phys.columns].map((n) => ({ name: n, sqlType: "UNKNOWN" }))
        : []);
    const id = makeId("tbl");
    tables.push({
      id,
      schema: phys?.schema ?? null,
      name: tableName,
      role,
      columns: initialCols,
      position,
      groupId: null,
    });
    tableIdByName.set(tableName, id);
    return id;
  }

  function ensureColumn(tableId: string, colName: string) {
    const t = tables.find((tt) => tt.id === tableId);
    if (!t) return;
    if (t.columns.some((c) => c.name === colName)) return;
    t.columns.push({ name: colName, sqlType: "UNKNOWN" });
  }

  // Phase 3: MeasureGroups → mark fact tables, harvest measure columns.
  // Walks EVERY cube's MGs, not just the first cube's, so multi-cube
  // schemas (FoodMart has 6) register every fact table.  Flat `mgEls`
  // stays around for the join-inference pass further down.
  const mgEls: Element[] = [];
  for (const c of cubeEls) {
    for (const m of Array.from(
      c.querySelectorAll(":scope > MeasureGroups > MeasureGroup"),
    )) {
      mgEls.push(m);
    }
  }
  mgEls.forEach((mgEl) => {
    const tableName = mgEl.getAttribute("table");
    if (!tableName) {
      warnings.push(
        `<MeasureGroup name="${mgEl.getAttribute("name")}"> has no table; skipped.`,
      );
      return;
    }
    const factId = ensureTable(tableName, "fact", { x: 600, y: 400 });
    for (const mEl of Array.from(
      mgEl.querySelectorAll(":scope > Measures > Measure"),
    )) {
      const col = measureColumnOf(mEl);
      if (col) ensureColumn(factId, col);
    }
  });
  if (mgEls.length === 0) {
    warnings.push(
      "No <MeasureGroup> found in any cube — fact tables won't be marked.",
    );
  }

  // Phase 4: Dimensions → register dim tables, harvest attribute columns.
  // Walks SCHEMA-level <Dimension>s (Mondrian 4's shared-dim model) AND
  // cube-private <Dimension>s, so every dim's underlying table is on the
  // canvas before the workbench-side parser tries to resolve attributes
  // against it.  Per-attribute `table=` overrides are also registered so
  // cross-table dims (e.g. Customer with attributes on `customer` + dim
  // joins to `customer_class`) get their secondary tables too.
  const dimByName = new Map<string, string>(); // dim "logical" name → physical table name
  const schemaDimEls = Array.from(
    schemaEl.querySelectorAll(":scope > Dimension"),
  );
  const cubeDimEls: Element[] = [];
  for (const c of cubeEls) {
    for (const dEl of Array.from(
      c.querySelectorAll(":scope > Dimensions > Dimension"),
    )) {
      // source='X' refers to a shared dim that's already in schemaDimEls;
      // skip duplicate ensureTable.
      if (dEl.getAttribute("source")) continue;
      cubeDimEls.push(dEl);
    }
  }
  const allDimEls = [...schemaDimEls, ...cubeDimEls];
  const n = Math.max(1, allDimEls.length);
  allDimEls.forEach((dimEl, i) => {
    const logical = dimEl.getAttribute("name") ?? "";
    const dimTableName = dimEl.getAttribute("table");
    const gridCols = n <= 4 ? 2 : n <= 9 ? 3 : 4;
    const gridRow = Math.floor(i / gridCols);
    const gridCol = i % gridCols;
    const posBase = { x: 320 + gridCol * 320, y: 200 + gridRow * 280 };
    let dimTableId: string | null = null;
    if (dimTableName) {
      dimByName.set(logical, dimTableName);
      dimTableId = ensureTable(dimTableName, "dimension", posBase);
    } else {
      // Dim has no top-level table=; pick the FIRST attribute's table=
      // as the canonical physical table.  Suppresses the legacy
      // "references an undefined dimension" inference warning for dims
      // like Store2 / Product whose tables only appear at attribute scope.
      const firstAttrTable = dimEl
        .querySelector(":scope > Attributes > Attribute[table]")
        ?.getAttribute("table");
      if (firstAttrTable) dimByName.set(logical, firstAttrTable);
    }
    // Each Attribute may override `table=`; register every referenced
    // table so attribute-level table refs (e.g. Product attrs on both
    // product + product_class) resolve.
    for (const aEl of Array.from(
      dimEl.querySelectorAll(":scope > Attributes > Attribute"),
    )) {
      const attrTable = aEl.getAttribute("table");
      let attrTableId = dimTableId;
      if (attrTable) {
        attrTableId = ensureTable(attrTable, "dimension", {
          x: posBase.x + 40,
          y: posBase.y + 40,
        });
      }
      const col =
        aEl.getAttribute("keyColumn") ??
        aEl.getAttribute("nameColumn") ??
        aEl.querySelector(":scope > Key > Column")?.getAttribute("name");
      if (attrTableId && col) ensureColumn(attrTableId, col);
    }
    // No warning when dimTableName is absent — the workbench parser
    // handles per-attribute table= refs (Store2 / Product follow this
    // pattern), and we just inferred the canonical physical table from
    // the first attribute above for the legacy join-inference path.
    // The previous "has no table attribute; skipped" message was
    // misleading — the dim was NOT skipped, just registered differently.
  });

  // Phase 5: PhysicalSchema Links → table-pair joins. A Link's <ForeignKey>
  // <Column> sits on the TARGET side; the SOURCE side uses its declared <Key>.
  function joinExists(
    srcId: string,
    srcCol: string,
    tgtId: string,
    tgtCol: string,
  ): boolean {
    return joins.some(
      (j) =>
        (j.sourceTableId === srcId &&
          j.targetTableId === tgtId &&
          j.sourceColumnName === srcCol &&
          j.targetColumnName === tgtCol) ||
        (j.sourceTableId === tgtId &&
          j.targetTableId === srcId &&
          j.sourceColumnName === tgtCol &&
          j.targetColumnName === srcCol),
    );
  }

  if (physSchemaEl) {
    for (const linkEl of Array.from(
      physSchemaEl.querySelectorAll(":scope > Link"),
    )) {
      const sourceName = linkEl.getAttribute("source");
      const targetName = linkEl.getAttribute("target");
      if (!sourceName || !targetName) continue;
      const fkColEl = linkEl.querySelector("ForeignKey > Column");
      const fkColName = fkColEl?.getAttribute("name");
      if (!fkColName) {
        warnings.push(
          `<Link source="${sourceName}" target="${targetName}"> has no <ForeignKey><Column>; skipped.`,
        );
        continue;
      }
      const sourceId = ensureTable(sourceName, "dimension", { x: 0, y: 0 });
      const targetId = ensureTable(targetName, "dimension", { x: 0, y: 0 });
      const sourcePhys = physTables.get(sourceName);
      const sourceKey = sourcePhys?.keyColumn ?? "id";
      ensureColumn(sourceId, sourceKey);
      ensureColumn(targetId, fkColName);
      if (!joinExists(sourceId, sourceKey, targetId, fkColName)) {
        joins.push({
          id: makeId("join"),
          sourceTableId: sourceId,
          sourceColumnName: sourceKey,
          targetTableId: targetId,
          targetColumnName: fkColName,
          kind: "inner",
          // Mondrian-4 <PhysicalSchema><Link> — the canonical
          // physical-schema statement.  Editable on canvas.
          origin: "physical",
        });
      }
    }
  }

  // Phase 6 (REMOVED): cube-level <DimensionLinks><ForeignKeyLink> no
  // longer becomes a canvas join.  The schema canvas is the *physical
  // schema* view — it reflects only the <Link>s declared inside
  // <PhysicalSchema>.  Cube-side FK/Fact/Reference links round-trip
  // through the workbench cube's dimensionLinks (built separately in
  // the per-cube walker below), so nothing is lost — they just don't
  // pollute the canvas join count with derived edges.  Amelia's rule:
  // "only read joins from the physical schema for the joins on the
  // schema canvas summary, ignore all links inside the cubes."

  // Phase 7 (removed): we deliberately DO NOT drop orphan physical tables
  // (declared in <PhysicalSchema> but referenced by no cube) onto the
  // canvas. A schema whose warehouse also contains leftover tables from a
  // prior provision (e.g. a `test2_`-prefixed run) would otherwise litter
  // the canvas with disconnected orphans the user never asked for. The
  // canvas shows only the tables a cube actually uses (fact tables +
  // dimension tables + their links). `physTables` still backs column
  // enrichment for those used tables.

  if (tables.length === 0) {
    throw new Error("Mondrian 4 schema produced no tables — check the file.");
  }

  // ── Phase 5: Schema-level <Dimension>s → SchemaCanvasDimension[].
  // Walks every <Dimension> directly under <Schema>: each becomes a
  // shared dim with attributes, hierarchies, levels, and a primary key
  // derived from the `key=` attribute → matching <Attribute>'s
  // keyColumn.  Inline Dimensions inside Cubes (Mondrian 4 lets you
  // declare private dims there) are walked when we process Cubes
  // below; <Dimension source='X'/> references just link the cube to
  // the shared dim by name.
  const dimensions: SchemaCanvasDimension[] = [];
  const dimensionsByName = new Map<string, SchemaCanvasDimension>();

  function parseAttributes(
    dimEl: Element,
    fallbackTableId: string | null,
  ): {
    tableId: string;
    columnName: string;
    logicalName: string;
    keyColumns?: string[];
    levelType?: string;
    annotations?: Record<string, string>;
    nameColumn?: string;
    captionColumn?: string;
    orderByColumn?: string;
    description?: string;
  }[] {
    const out: {
      tableId: string;
      columnName: string;
      logicalName: string;
      keyColumns?: string[];
      levelType?: string;
      annotations?: Record<string, string>;
      nameColumn?: string;
      captionColumn?: string;
      orderByColumn?: string;
      description?: string;
    }[] = [];
    for (const aEl of Array.from(
      dimEl.querySelectorAll(":scope > Attributes > Attribute"),
    )) {
      const logicalName = aEl.getAttribute("name") ?? "";
      // M4 carries a level's levelType on its <Attribute> — read it so the
      // resolved <Level> below inherits it (round-trips time intelligence).
      const levelType = aEl.getAttribute("levelType") ?? undefined;
      const annotations = readSemanticAnnotations(aEl);
      const attrTable = aEl.getAttribute("table");
      // Attribute can override the dim's table via `table=`; falls back
      // to the dim's own table when omitted.
      const tableName = attrTable ?? null;
      const tableId =
        (tableName ? tableIdByName.get(tableName) : null) ??
        fallbackTableId ??
        null;
      if (!tableId) {
        warnings.push(
          `<Attribute name="${logicalName}"> couldn't resolve its table — skipped.`,
        );
        continue;
      }
      // keyColumn= is the simple case; <Key><Column/></Key> is the
      // multi-col case.  v2: PRESERVE the full column list so composite
      // keys round-trip (FoodMart Store City has key=(store_state,
      // store_city) — collapsing to last column loses the parent-scope
      // disambiguation Mondrian uses to keep CA's "Los Angeles" distinct
      // from another state's).  `columnName` is the canonical naming
      // column (last); `keyColumns` holds the full list when > 1.
      let columnName = aEl.getAttribute("keyColumn");
      let keyColumns: string[] | undefined;
      if (!columnName) {
        const keyEl = aEl.querySelector(":scope > Key");
        if (keyEl) {
          const cols = Array.from(keyEl.querySelectorAll(":scope > Column"))
            .map((c) => c.getAttribute("name") ?? "")
            .filter((c) => c.length > 0);
          if (cols.length > 1) {
            keyColumns = cols;
          }
          columnName = cols[cols.length - 1] ?? null;
        }
      }
      // Last-resort: nameColumn (handles the FoodMart Customer.Name
      // pattern where keyColumn=customer_id but the display is full_name).
      if (!columnName) columnName = aEl.getAttribute("nameColumn");
      if (!columnName) {
        warnings.push(
          `<Attribute name="${logicalName}"> has no resolvable column — skipped.`,
        );
        continue;
      }
      ensureColumn(tableId, columnName);
      // Composite-key columns also live on this table — ensure them too
      // so the canvas card shows every referenced column.
      if (keyColumns) {
        for (const c of keyColumns) ensureColumn(tableId, c);
      }
      // Optional M4 attribute overrides (#959). `nameColumn` doubles as a
      // column-resolution fallback above, so only treat it as a display
      // override when it genuinely differs from the resolved key column.
      const rawNameColumn = aEl.getAttribute("nameColumn") ?? undefined;
      const nameColumn =
        rawNameColumn && rawNameColumn !== columnName
          ? rawNameColumn
          : undefined;
      const captionColumn = aEl.getAttribute("captionColumn") ?? undefined;
      const orderByColumn = aEl.getAttribute("orderByColumn") ?? undefined;
      const description = aEl.getAttribute("description") ?? undefined;
      out.push({
        tableId,
        columnName,
        logicalName,
        keyColumns,
        levelType,
        annotations,
        nameColumn,
        captionColumn,
        orderByColumn,
        description,
      });
    }
    return out;
  }

  function parseDimensionInto(
    dimEl: Element,
    opts: { fallbackTableName: string | null },
  ): SchemaCanvasDimension | null {
    const name = dimEl.getAttribute("name") ?? "";
    // Hanger dims (`hanger='true'`) — no underlying table, no attributes
    // resolved from columns.  Levels are MDX expressions slicing scenario
    // values like "Actual vs Budget".  Round-trip parses + re-emits the
    // hanger flag but leaves attributes empty (which is what Mondrian
    // expects for hangers).
    const isHanger = dimEl.getAttribute("hanger") === "true";
    // Dim can declare a table at the top OR delegate to per-attribute
    // `table=`.  Either path lands the dim on some table — the picked
    // "primary" tableId is the one carrying the key attribute.
    //
    // The M3→M4 upgrader emits the latter shape: NO `table=` on <Dimension>,
    // `table="store"` only on the key <Attribute>, and level-property
    // attributes (e.g. "Store Name$Store Type") with no `table=` at all,
    // meant to inherit the dim's table. So when <Dimension> has no `table=`,
    // fall back to the KEY attribute's table — otherwise every property
    // attribute resolves to null and is dropped with "couldn't resolve its
    // table" (saiku-cloud#1133).
    const keyAttrName = dimEl.getAttribute("key");
    let keyAttrTable: string | null = null;
    if (keyAttrName) {
      for (const aEl of Array.from(
        dimEl.querySelectorAll(":scope > Attributes > Attribute"),
      )) {
        if (aEl.getAttribute("name") === keyAttrName) {
          keyAttrTable = aEl.getAttribute("table");
          break;
        }
      }
    }
    const dimTableName =
      dimEl.getAttribute("table") ??
      keyAttrTable ??
      opts.fallbackTableName ??
      null;
    const fallbackTableId = dimTableName
      ? (tableIdByName.get(dimTableName) ?? null)
      : null;
    const attrs = parseAttributes(dimEl, fallbackTableId);
    if (attrs.length === 0 && !isHanger) {
      warnings.push(
        `<Dimension name="${name}"> has no resolvable attributes — skipped.`,
      );
      return null;
    }

    // Resolve the dim's PRIMARY KEY: key='X' on <Dimension> refers to
    // the Attribute logical name; map back to the underlying column.
    // (keyAttrName resolved above, where it also seeds the fallback table.)
    let primaryKey: string | undefined;
    let primaryKeyTableId: string | undefined;
    if (keyAttrName) {
      const keyAttr = attrs.find((a) => a.logicalName === keyAttrName);
      if (keyAttr) {
        // Store the attribute's LOGICAL name (guaranteed unique per
        // Mondrian) rather than the columnName — two attributes on the
        // same table can share a column (FoodMart's Product has both
        // "Product Name" and "Product Id" on product.product_id).
        primaryKey = keyAttr.logicalName || keyAttr.columnName;
        primaryKeyTableId = keyAttr.tableId;
      } else {
        warnings.push(
          `<Dimension name="${name}"> key="${keyAttrName}" — no matching Attribute; primary key left unset.`,
        );
      }
    }

    // Walk <Hierarchies>/<Hierarchy>/<Level> — each Level references
    // an Attribute by logical name, which we resolve back to its
    // (tableId, columnName) tuple.  Levels stay in document order.
    // `<Closure table='employee_closure' parentColumn='supervisor_id'
    // childColumn='employee_id'/>` on a Level marks the parent-child
    // optimisation table; we capture it at hierarchy scope (Mondrian
    // only allows one closure per hierarchy in practice) and round-trip
    // it on export.  HR's Employee hierarchy uses this.
    const hierarchies: SchemaCanvasHierarchy[] = [];
    for (const hEl of Array.from(
      dimEl.querySelectorAll(":scope > Hierarchies > Hierarchy"),
    )) {
      const hName = hEl.getAttribute("name") ?? name;
      const hasAllRaw = hEl.getAttribute("hasAll");
      const hasAll = hasAllRaw === null ? true : hasAllRaw === "true";
      const allMemberName = hEl.getAttribute("allMemberName") ?? undefined;
      const defaultMember = hEl.getAttribute("defaultMember") ?? undefined;
      const caption = hEl.getAttribute("caption") ?? undefined;
      const description = hEl.getAttribute("description") ?? undefined;
      const levels: SchemaCanvasLevel[] = [];
      let closure: SchemaCanvasHierarchy["closure"] | undefined;
      for (const lEl of Array.from(hEl.querySelectorAll(":scope > Level"))) {
        const attrName = lEl.getAttribute("attribute") ?? "";
        const attr = attrs.find((a) => a.logicalName === attrName);
        if (!attr) {
          warnings.push(
            `<Level attribute="${attrName}"> in <Hierarchy name="${hName}"> — no matching Attribute; skipped.`,
          );
          continue;
        }
        levels.push({
          id: makeId("lvl"),
          name: lEl.getAttribute("name") ?? attr.logicalName,
          caption: lEl.getAttribute("caption") ?? undefined,
          tableId: attr.tableId,
          columnName: attr.columnName,
          // levelType round-trips from the <Attribute> (M4 carries it there).
          levelType:
            attr.levelType === "TimeYears" ||
            attr.levelType === "TimeQuarters" ||
            attr.levelType === "TimeMonths" ||
            attr.levelType === "TimeDays"
              ? attr.levelType
              : undefined,
          // saiku.semantic.* annotations also ride on the <Attribute>.
          annotations: attr.annotations,
        });
        // Closure lives on a Level as a child element in Mondrian 4 —
        // pull it up to hierarchy scope where round-trip and the
        // export layer expect it.
        const cEl = lEl.querySelector(":scope > Closure");
        if (cEl) {
          closure = {
            table: cEl.getAttribute("table") ?? "",
            parentColumn: cEl.getAttribute("parentColumn") ?? "",
            childColumn: cEl.getAttribute("childColumn") ?? "",
          };
        }
      }
      hierarchies.push({
        id: makeId("hier"),
        name: hName,
        hasAll,
        allMemberName,
        defaultMember,
        caption,
        description,
        levels,
        closure,
      });
    }

    const typeRaw = dimEl.getAttribute("type");
    const dimensionType =
      typeRaw === "TIME"
        ? ("Time" as const)
        : typeRaw === "StandardDimension"
          ? ("Standard" as const)
          : typeRaw === "TimeDimension"
            ? ("Time" as const)
            : typeRaw === "GeographicDimension"
              ? ("Geographic" as const)
              : undefined;

    // Source table binding — set from <Dimension table="X"> when
    // present, else fall back to the KEY attribute's table (matches
    // how the workbench treats the "source table" concept).  Prior
    // to this the parser only wrote primaryKeyTableId; sourceTableId
    // stayed undefined even when the XML clearly declared a table,
    // so the "No table bound" empty state fired for imported dims.
    const sourceTableId = dimTableName
      ? (tableIdByName.get(dimTableName) ?? primaryKeyTableId)
      : primaryKeyTableId;

    const dim: SchemaCanvasDimension = {
      id: makeId("dim"),
      name,
      attributes: attrs.map((a) => ({
        tableId: a.tableId,
        columnName: a.columnName,
        name: a.logicalName,
        keyColumns: a.keyColumns,
        nameColumn: a.nameColumn,
        captionColumn: a.captionColumn,
        orderByColumn: a.orderByColumn,
        description: a.description,
      })),
      hierarchies,
      primaryKey,
      primaryKeyTableId,
      sourceTableId,
      hanger: isHanger || undefined,
      caption: dimEl.getAttribute("caption") ?? undefined,
      description: dimEl.getAttribute("description") ?? undefined,
      dimensionType,
      annotations: readSemanticAnnotations(dimEl),
    };
    return dim;
  }

  // Schema-scope shared dimensions (Mondrian 4's first-class dim model).
  for (const dimEl of Array.from(
    schemaEl.querySelectorAll(":scope > Dimension"),
  )) {
    const dim = parseDimensionInto(dimEl, {
      fallbackTableName: dimEl.getAttribute("table"),
    });
    if (!dim) continue;
    dimensions.push(dim);
    dimensionsByName.set(dim.name, dim);
  }

  // ── Phase 6: <Cube>s → workbench cubes + measures.
  // Measures (flat) accumulate into state.measures.  Per-cube workbench
  // data goes into `workbenchCubes` for the localStorage seed.
  const measures: SchemaCanvasMeasure[] = [];
  const seenMeasureKeys = new Set<string>();

  for (const cubeEl of cubeEls) {
    const cubeName = cubeEl.getAttribute("name") ?? "Cube";
    const workCube: ImportedWorkbenchCube = {
      id: makeId("cube"),
      name: cubeName,
      measureGroups: [],
      factsCalcs: [],
    };

    // Cube-private Dimensions (declared inline, not source='X' refs)
    // also become shared dims — append them so DimensionLinks below
    // can resolve.
    for (const dimEl of Array.from(
      cubeEl.querySelectorAll(":scope > Dimensions > Dimension"),
    )) {
      // source='X' is a REFERENCE to a schema-level dim (Mondrian 4's
      // DimensionUsage), optionally RENAMED (e.g. name="Order Date"
      // source="Date"). Register the local name as an alias to the shared
      // dim so DimensionLinks that reference the local name resolve.
      const sourceRef = dimEl.getAttribute("source");
      if (sourceRef) {
        const localName = dimEl.getAttribute("name");
        if (
          localName &&
          localName !== sourceRef &&
          !dimensionsByName.has(localName)
        ) {
          const shared = dimensionsByName.get(sourceRef);
          if (shared) dimensionsByName.set(localName, shared);
        }
        continue;
      }
      const dim = parseDimensionInto(dimEl, {
        fallbackTableName: dimEl.getAttribute("table"),
      });
      if (!dim) continue;
      // Avoid double-registering a dim name (e.g. a cube-private "Time"
      // when a schema "Time" also exists — warn the user, keep the
      // cube-private one under a synthetic name).
      if (dimensionsByName.has(dim.name)) {
        dim.name = `${dim.name} (${cubeName})`;
        warnings.push(
          `<Cube name="${cubeName}"> redefines dimension "${dimEl.getAttribute("name")}"; renamed to "${dim.name}" to avoid collision.`,
        );
      }
      dimensions.push(dim);
      dimensionsByName.set(dim.name, dim);
    }

    // MeasureGroups → workbench MGs + flat measures.
    for (const mgEl of Array.from(
      cubeEl.querySelectorAll(":scope > MeasureGroups > MeasureGroup"),
    )) {
      const mgName =
        mgEl.getAttribute("name") ??
        mgEl.getAttribute("table") ??
        `MG${workCube.measureGroups.length + 1}`;
      const factTableName = mgEl.getAttribute("table");
      const factTableId = factTableName
        ? (tableIdByName.get(factTableName) ?? null)
        : null;
      if (factTableName && !factTableId) {
        warnings.push(
          `<MeasureGroup name="${mgName}"> table="${factTableName}" wasn't on the canvas — fact unlinked.`,
        );
      }

      const measureColumns: string[] = [];
      for (const measureEl of Array.from(
        mgEl.querySelectorAll(":scope > Measures > Measure"),
      )) {
        const measureName = measureEl.getAttribute("name") ?? "";
        const column = measureColumnOf(measureEl);
        const aggRaw = measureEl.getAttribute("aggregator") ?? "sum";
        const aggregator: SchemaCanvasMeasure["aggregator"] =
          aggRaw === "sum" ||
          aggRaw === "count" ||
          aggRaw === "avg" ||
          aggRaw === "min" ||
          aggRaw === "max" ||
          aggRaw === "distinct-count" ||
          aggRaw === "median" ||
          aggRaw === "percentile"
            ? aggRaw
            : "sum";
        // Percentile fraction (mondrian-saiku #104) — only on percentile
        // measures; a non-numeric or absent value leaves it unset (→ 50).
        const percentileRaw =
          aggregator === "percentile"
            ? measureEl.getAttribute("percentile")
            : null;
        const percentileVal =
          percentileRaw !== null &&
          percentileRaw.trim() !== "" &&
          !Number.isNaN(Number(percentileRaw))
            ? Number(percentileRaw)
            : undefined;
        if (!column) {
          warnings.push(
            `<Measure name="${measureName}"> has no column — skipped.`,
          );
          continue;
        }
        measureColumns.push(column);
        if (factTableId) {
          const key = `${factTableId}::${column}`;
          if (!seenMeasureKeys.has(key)) {
            seenMeasureKeys.add(key);
            measures.push({
              id: makeId("m"),
              name: measureName || column,
              aggregator,
              tableId: factTableId,
              columnName: column,
              formatString: measureEl.getAttribute("formatString") ?? undefined,
              percentile: percentileVal,
              annotations: readSemanticAnnotations(measureEl),
            });
          }
        }
      }

      // DimensionLinks → mg.dimensionLinks resolving dimension name
      // to the parsed dim's id.  v2 supports all three Mondrian-4 link
      // kinds — ForeignKeyLink (default), FactLink (degenerate dim on
      // the fact table; no FK column), and ReferenceLink (multi-hop
      // via another dim's attribute).  `linkKind` is preserved through
      // round-trip so the exporter re-emits the original tag.
      const dimensionLinks: ImportedWorkbenchMeasureGroup["dimensionLinks"] =
        [];
      const linksEl = mgEl.querySelector(":scope > DimensionLinks");
      if (linksEl) {
        for (const linkEl of Array.from(linksEl.children)) {
          const tag = linkEl.tagName;
          const dimName = linkEl.getAttribute("dimension") ?? "";
          const dim = dimensionsByName.get(dimName);
          if (!dim) {
            warnings.push(
              `<${tag} dimension="${dimName}"> on cube "${cubeName}" — no matching dim; skipped.`,
            );
            continue;
          }
          if (tag === "ForeignKeyLink") {
            let fk = linkEl.getAttribute("foreignKeyColumn") ?? "";
            if (!fk) {
              const colEl = linkEl.querySelector(
                ":scope > ForeignKey > Column",
              );
              fk = colEl?.getAttribute("name") ?? "";
            }
            dimensionLinks.push({
              dimensionId: dim.id,
              foreignKeyColumn: fk,
              linkKind: "foreign-key",
            });
          } else if (tag === "FactLink") {
            // Degenerate dim — its level columns ARE on the fact
            // table.  No FK column to carry; `factTableId` IS the
            // dim's table.
            dimensionLinks.push({
              dimensionId: dim.id,
              foreignKeyColumn: "",
              linkKind: "fact",
            });
          } else if (tag === "ReferenceLink") {
            // Multi-hop: dim is reached through `viaDimension`'s
            // `viaAttribute`.  Carry both through so the export
            // can re-emit the original.
            dimensionLinks.push({
              dimensionId: dim.id,
              foreignKeyColumn: "",
              linkKind: "reference",
              viaDimension: linkEl.getAttribute("viaDimension") ?? undefined,
              viaAttribute: linkEl.getAttribute("viaAttribute") ?? undefined,
            });
          }
        }
      }

      workCube.measureGroups.push({
        id: makeId("mg"),
        name: mgName,
        factTableId,
        measureColumns,
        dimensionLinks,
      });
    }

    // CalculatedMembers → factsCalcs (formula text only — chip tokens stay empty).
    for (const calcEl of Array.from(
      cubeEl.querySelectorAll(":scope > CalculatedMembers > CalculatedMember"),
    )) {
      const calcName = calcEl.getAttribute("name") ?? "";
      // Formula can be an attribute OR a child <Formula> element.
      let formula = calcEl.getAttribute("formula") ?? "";
      if (!formula) {
        const formulaEl = calcEl.querySelector(":scope > Formula");
        formula = formulaEl?.textContent?.trim() ?? "";
      }
      workCube.factsCalcs.push({
        id: makeId("calc"),
        name: calcName,
        tokens: [],
        formula: formula || undefined,
      });
    }

    // TimeCalcs → cube.timeCalcs (declarative time-intelligence metrics).
    const timeCalcs: SchemaCanvasTimeCalc[] = [];
    for (const tcEl of Array.from(
      cubeEl.querySelectorAll(":scope > TimeCalcs > TimeCalc"),
    )) {
      const rawType = tcEl.getAttribute("type") ?? "";
      const type =
        rawType === "yoy" ||
        rawType === "pop" ||
        rawType === "ytd" ||
        rawType === "rolling"
          ? rawType
          : "yoy";
      const rawWindow = tcEl.getAttribute("window");
      const rawFn = tcEl.getAttribute("function");
      timeCalcs.push({
        id: makeId("timecalc"),
        name: tcEl.getAttribute("name") ?? "",
        type,
        measure: tcEl.getAttribute("measure") ?? "",
        timeDimension: tcEl.getAttribute("timeDimension") ?? undefined,
        window:
          type === "rolling" &&
          rawWindow != null &&
          !Number.isNaN(Number(rawWindow))
            ? Number(rawWindow)
            : undefined,
        function: rawFn === "avg" ? "avg" : rawFn === "sum" ? "sum" : undefined,
        formatString: tcEl.getAttribute("formatString") ?? undefined,
      });
    }
    if (timeCalcs.length > 0) workCube.timeCalcs = timeCalcs;

    workbenchCubes.push(workCube);
  }

  const state: SchemaCanvasState = {
    version: 1,
    connectionId: opts.connectionId,
    label: opts.label ?? schemaName,
    tables,
    joins,
    groups: [],
    dimensions,
    measures,
    updatedAt: new Date().toISOString(),
  };
  return { state, warnings, workbenchCubes };
}
