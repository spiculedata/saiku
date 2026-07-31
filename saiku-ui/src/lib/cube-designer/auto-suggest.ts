/**
 * Canvas schema designer — join auto-suggest.
 *
 * Given a pair of tables on the canvas, propose candidate join columns
 * based on name match heuristics. The user confirms or rejects each
 * suggestion; this is not auto-creation, just a starting point.
 *
 * Heuristics, in order:
 *   1. Exact column-name match on both sides (e.g. `customer_id` ↔ `customer_id`).
 *   2. Table-named match — column on dim equals `<dim_name>_id` and fact
 *      has a column matching that pattern (e.g. dim `customer` with `id` ↔
 *      fact's `customer_id`).
 *   3. PK-style: any column ending in `_id` on the fact that matches the
 *      dim's name root.
 *
 * Returns an ordered list of candidate joins, highest-confidence first.
 */
import type { SchemaCanvasTable, SchemaCanvasJoinKind } from "./types.js";

export interface JoinSuggestion {
  sourceColumnName: string;
  targetColumnName: string;
  kind: SchemaCanvasJoinKind;
  confidence: "high" | "medium" | "low";
  reason: string;
}

export function suggestJoins(
  source: SchemaCanvasTable,
  target: SchemaCanvasTable,
): JoinSuggestion[] {
  const out: JoinSuggestion[] = [];
  const seen = new Set<string>();

  const sourceColumns = source.columns.map((c) => c.name.toLowerCase());
  const targetColumns = target.columns.map((c) => c.name.toLowerCase());
  const sourceColumnsOriginal = new Map(
    source.columns.map((c) => [c.name.toLowerCase(), c.name]),
  );
  const targetColumnsOriginal = new Map(
    target.columns.map((c) => [c.name.toLowerCase(), c.name]),
  );

  const push = (
    src: string,
    tgt: string,
    confidence: JoinSuggestion["confidence"],
    reason: string,
  ) => {
    const key = `${src}=${tgt}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push({
      sourceColumnName: src,
      targetColumnName: tgt,
      kind: "inner",
      confidence,
      reason,
    });
  };

  // 1. Exact matches.
  for (const sc of sourceColumns) {
    if (targetColumns.includes(sc)) {
      push(
        sourceColumnsOriginal.get(sc)!,
        targetColumnsOriginal.get(sc)!,
        "high",
        `Exact column-name match (${sc})`,
      );
    }
  }

  // 2. <table_name>_id pattern — source column matches `<target_name>_id` AND target has the canonical id.
  const targetNameLower = target.name.toLowerCase();
  const targetSingular = targetNameLower.endsWith("s")
    ? targetNameLower.slice(0, -1)
    : targetNameLower;
  const sourceNameLower = source.name.toLowerCase();
  const sourceSingular = sourceNameLower.endsWith("s")
    ? sourceNameLower.slice(0, -1)
    : sourceNameLower;

  const candidateIdColsTgt = [
    "id",
    `${targetSingular}_id`,
    `${targetNameLower}_id`,
  ];
  for (const srcCol of sourceColumns) {
    if (
      srcCol === `${targetSingular}_id` ||
      srcCol === `${targetNameLower}_id`
    ) {
      for (const tgtCandidate of candidateIdColsTgt) {
        if (targetColumns.includes(tgtCandidate)) {
          push(
            sourceColumnsOriginal.get(srcCol)!,
            targetColumnsOriginal.get(tgtCandidate)!,
            "high",
            `Foreign-key naming convention (${srcCol} → ${tgtCandidate})`,
          );
        }
      }
    }
  }
  // reverse direction — target side has the FK
  const candidateIdColsSrc = [
    "id",
    `${sourceSingular}_id`,
    `${sourceNameLower}_id`,
  ];
  for (const tgtCol of targetColumns) {
    if (
      tgtCol === `${sourceSingular}_id` ||
      tgtCol === `${sourceNameLower}_id`
    ) {
      for (const srcCandidate of candidateIdColsSrc) {
        if (sourceColumns.includes(srcCandidate)) {
          push(
            sourceColumnsOriginal.get(srcCandidate)!,
            targetColumnsOriginal.get(tgtCol)!,
            "high",
            `Foreign-key naming convention (${srcCandidate} → ${tgtCol})`,
          );
        }
      }
    }
  }

  // 3. Looser — any pair of `<root>_id` columns on each side where the
  // root matches between them. e.g. `customer_id` ↔ `customer_id`
  // already covered above; this picks up softer cases.
  for (const sc of sourceColumns) {
    const m = sc.match(/^(.+)_id$/);
    if (!m) continue;
    const root = m[1];
    const tgtCandidate = `${root}_id`;
    if (
      sourceColumnsOriginal.has(sc) &&
      targetColumnsOriginal.has(tgtCandidate)
    ) {
      push(
        sourceColumnsOriginal.get(sc)!,
        targetColumnsOriginal.get(tgtCandidate)!,
        "medium",
        `Both sides have ${tgtCandidate}`,
      );
    }
  }

  return out;
}
