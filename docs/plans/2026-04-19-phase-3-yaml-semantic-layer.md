# Phase 3 — YAML semantic layer (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** Cubes defined in ~20 lines of YAML. Mondrian XML becomes a compile target, not an authoring surface. New users go from "Postgres DB" to "working cube in UI" in < 10 min.

**Base branch:** `development` (post Phase 2 merge; Phase 1 Group D/E optional but convenient — the CLI hosts `saiku infer/lint/convert`).

**Tech stack:** Jackson YAML, Jakarta Validation, Picocli CLI, JSON Schema (for IDE autocompletion), optional OpenRewrite for import tools.

**Effort:** 3–4 focused sessions.

---

## Tasks

### Task 3.1 — Internal IR (Java records)

Create a clean schema IR in a new package `org.saiku.semantic.model`:
- `CubeDefinition(name, source, dimensions, measures, security)`
- `DimensionDefinition(name, source, levels[], hierarchy?)`
- `LevelDefinition(name, column, type)`
- `MeasureDefinition(name, agg | expr, format?)`
- `JoinDefinition(fk, pk)`
- `SecurityDefinition(rowFilter, roleMap)`

Immutable records, builder-style constructors, Jackson-bindable.

### Task 3.2 — YAML parser + validator

Jackson's `YAMLMapper` with strict mode. Validate with Jakarta Bean Validation (`@NotBlank`, `@Pattern`, etc.) + a small custom `SchemaValidator` that checks:
- No duplicate dimension/measure names
- Every foreign key has a matching dimension source
- Measure `expr` references existing measures
- Security predicates are valid SQL/template strings

Line-accurate error messages via `JsonLocation`.

### Task 3.3 — Mondrian compiler (YAML → Mondrian XML)

New class `MondrianCompiler` in `org.saiku.semantic.compile`. Consumes `CubeDefinition`, emits valid Mondrian 4 schema XML.

Golden-file test harness: `src/test/resources/semantic/compiler/<case>/input.yml` + `expected.xml`. `mvn verify` diff-fails on drift.

### Task 3.4 — `saiku lint cubes/`

Picocli subcommand. For each YAML in the directory:
- Parse + validate (3.2)
- Open a JDBC connection to the cube's source (from `datasource.ref` in the YAML)
- Verify every referenced table/column exists via `DatabaseMetaData`
- Report missing references with file + line number

Exit 0 on success, 1 on any lint errors.

### Task 3.5 — `saiku infer --source <connection> --table <name>`

Pull table metadata from the connection. Heuristics:
- Numeric columns → measure with `sum` aggregation
- FK-shaped columns (name ends in `_id`, `_fk`, or type is foreign-key via JDBC metadata) → dimension with the referenced table as source
- Date/time columns → dimension with levels `[Year, Quarter, Month, Day]`

Emit starter YAML to stdout (or `-o <file>`). Not perfect; documented as "a seed, not a spec."

### Task 3.6 — `saiku convert mondrian-to-yaml <schema.xml>`

Parse a Mondrian 3 or 4 schema XML, emit equivalent YAML. Round-trip test:
1. Convert sample Mondrian XML → YAML
2. Compile that YAML → Mondrian XML
3. Parse both XMLs into Mondrian's `MondrianSchema` objects
4. Compare semantic trees (not text)

Passes iff no semantic drift.

### Task 3.7 — Hot reload

A `WatchService` on the `cubes/` directory. On change:
- Recompile affected cubes
- Swap the in-memory schema via Mondrian's cache invalidation API
- UI picks up the new schema within ~2s without server restart

### Task 3.8 — Import tools

- `saiku import dbt <dbt-project-dir>` — read `models/**/schema.yml` or `semantic_models/**/*.yml`, emit equivalent Saiku YAML
- `saiku import cube <cube-dir>` — read Cube.js `.js`/`.yml` schema files, emit Saiku YAML

LookML is a stretch; skip unless someone asks.

### Task 3.9 — Docs cookbook

`docs/semantic-layer/` with pages for:
- Getting started (point at a DB, run `infer`, edit, serve)
- Slowly-changing dimensions
- Degenerate dimensions
- Virtual cubes
- Role-playing dimensions
- Security predicates + row-level filters

### Task 3.10 — Reference adapter: DuckDB

Working example at `examples/duckdb-sales/`:
- A DuckDB file seeded via SQL
- `cubes/sales.yml`
- 3-minute demo script showing point-infer-edit-serve

---

## Exit criteria

- A new user points `saiku infer` at a live Postgres table, edits one measure, runs `saiku serve`, and drags fields in the UI within 10 minutes
- `saiku lint cubes/` catches every common schema error class (unknown column, orphan join, duplicate names, invalid measure expr)
- Golden-file suite covers YAML → Mondrian XML compilation with zero drift
- Hot reload: edit YAML, see change in UI within 2 seconds
- 3-minute demo video recorded
- `saiku import dbt` works on at least one non-trivial dbt project

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 3.1 IR records | Trivial | Yes | Yes |
| 3.2 Parser/validator | Low | Yes | Yes |
| 3.3 Mondrian compiler | Med (semantic drift) | Yes | Yes |
| 3.4 lint | Low | Yes | Yes |
| 3.5 infer | Low | Yes | Yes |
| 3.6 convert (round-trip) | Med | Yes | Yes |
| 3.7 Hot reload | Med | Yes | Yes |
| 3.8 Importers | Low | Yes | Ship one at a time |
| 3.9 Docs | Trivial | Yes | Yes |
| 3.10 DuckDB example | Low | Yes | Last |
