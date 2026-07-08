/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.adapter;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.saiku.service.schema.ossie.model.Relationship;
import org.saiku.service.schema.ossie.model.SemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calcite planner rule that auto-injects an Ossie {@code relationship}'s ON predicate into any
 * Cartesian join between two datasets in the same {@link OssieSchema}.
 *
 * <p>Rewrites this shape:
 *
 * <pre>{@code
 * -- User writes:
 * SELECT c.REGION, SUM(o.AMOUNT)
 * FROM SALES.ORDERS o, SALES.CUSTOMERS c
 * GROUP BY c.REGION;
 *
 * -- Which Calcite parses as: LogicalJoin(condition=[true])(ORDERS, CUSTOMERS)
 * -- This rule rewrites to:   LogicalJoin(condition=[o.CUSTOMER_ID = c.ID])(ORDERS, CUSTOMERS)
 * -- where the columns come from the Ossie relationship on those two datasets.
 * }</pre>
 *
 * <p>Fires only when:
 *
 * <ol>
 *   <li>The join condition is trivially {@code true} (i.e. the user genuinely wrote a Cartesian
 *       join — {@code FROM A, B} with no {@code WHERE} join predicate). We never overwrite an
 *       explicit user-provided condition.
 *   <li>Both sides walk down to a {@link TableScan} whose schema name matches an OssieSchema
 *       registered in {@link OssieSchema#lookupRegistered}. We identify Ossie-backed tables by
 *       schema name rather than {@code RelOptTable#unwrap} because our datasets surface as
 *       {@code JdbcTable}s (for JDBC pushdown), so the unwrap chain lands on JdbcSchema.
 *   <li>Both scans are from the same {@link OssieSchema} instance.
 *   <li>Exactly one Ossie {@link Relationship} links the two datasets. Multiple candidates raise
 *       {@link AmbiguousJoinException} so silent-wrong-results never happens.
 * </ol>
 *
 * <p>N-way joins ({@code FROM A, B, C, …}) — the rule handles these by walking down through
 * nested Joins to reach every raw {@link TableScan} in the outer subtree, then rebuilding a
 * left-deep join chain against them using Ossie relationships to derive each pair's predicate.
 * Requires exactly one relationship for each successive link — ambiguity between the same pair
 * of datasets bails.
 *
 * <p>Non-goals for this first slice, tracked as follow-ups:
 *
 * <ul>
 *   <li>Cross-schema joins (Ossie + non-Ossie tables).
 *   <li>Self-joins ({@code FROM A a, A b}) — the rule bails when both sides resolve to the same
 *       dataset.
 * </ul>
 */
public class OssieAutoJoinRule extends RelOptRule {

    private static final Logger log = LoggerFactory.getLogger(OssieAutoJoinRule.class);

    /**
     * Singleton rule instance. Uses the older RelOptRule base class (rather than RelRule +
     * Config) because Calcite 1.41's Config machinery relies on the Immutables annotation
     * processor to synthesise the {@code Config.EMPTY} constant; we don't want that dependency
     * for one rule. RelOptRule remains fully supported and is what the JDBC adapter's own rules
     * still use in 1.41.
     */
    public static final OssieAutoJoinRule INSTANCE = new OssieAutoJoinRule();

    private OssieAutoJoinRule() {
        super(operand(LogicalJoin.class, any()), "OssieAutoJoinRule");
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalJoin join = call.rel(0);
        // Guard 1: only rewrite Cartesian joins.
        if (!join.getCondition().isAlwaysTrue()) return;

        // Guard 2: both sides must land on an Ossie-backed TableScan.
        OssieTableRef left = findOssieTable(join.getLeft());
        OssieTableRef right = findOssieTable(join.getRight());
        if (left == null || right == null) {
            // N-way case: at least one side is a nested Join. Handle via the compound-rebuild
            // path — collect every raw TableScan reachable in either subtree and build a fresh
            // left-deep join chain against them. Same tree-rebuild pattern as the two-way case,
            // just extended to N tables and N-1 relationships.
            tryCompoundRewrite(call, join);
            return;
        }
        if (left.schema != right.schema) return;
        if (left.datasetName.equals(right.datasetName)) return; // self-join — bail

        // Guard 3: find exactly one Ossie relationship linking the two datasets.
        Relationship relationship = pickRelationship(left.schema.model(), left.datasetName, right.datasetName);
        if (relationship == null) return;

        // Build the rewrite. Calcite's Volcano planner has usually pushed projections down onto
        // each side of the join before this rule fires, so `join.getLeft().getRowType()` might
        // only expose a subset of columns — often NOT including the join key. We can't simply
        // update the join condition against the current row types; we need to reach back to the
        // raw TableScans (which have every column) and rebuild the join around them.
        //
        // Structure of the rewrite:
        //   Project([<original output columns>])
        //     LogicalJoin(left.<from_col> = right.<to_col>)
        //       TableScan(left dataset)
        //       TableScan(right dataset)
        //
        // The outer Project restricts back to the columns the current join was producing, so the
        // rewrite is a drop-in substitution for the LogicalJoin node.
        RelBuilder builder = call.builder();
        RexBuilder rex = builder.getRexBuilder();
        TableScan leftScan = left.scan;
        TableScan rightScan = right.scan;
        RelDataType leftScanRow = leftScan.getRowType();
        RelDataType rightScanRow = rightScan.getRowType();

        // Build ON predicate using column indices from the raw TableScan row types.
        boolean sameDirection = relationship.getFrom().equals(left.datasetName);
        List<String> leftKeyCols = sameDirection ? relationship.getFromColumns() : relationship.getToColumns();
        List<String> rightKeyCols = sameDirection ? relationship.getToColumns() : relationship.getFromColumns();
        if (leftKeyCols.size() != rightKeyCols.size() || leftKeyCols.isEmpty()) return;

        int leftFieldCount = leftScanRow.getFieldCount();
        List<RexNode> conjuncts = new ArrayList<>();
        for (int i = 0; i < leftKeyCols.size(); i++) {
            Integer leftIdx = fieldOrdinal(leftScanRow, leftKeyCols.get(i));
            Integer rightIdx = fieldOrdinal(rightScanRow, rightKeyCols.get(i));
            if (leftIdx == null || rightIdx == null) return;
            RelDataTypeField lf = leftScanRow.getFieldList().get(leftIdx);
            RelDataTypeField rf = rightScanRow.getFieldList().get(rightIdx);
            RexNode l = rex.makeInputRef(lf.getType(), leftIdx);
            RexNode r = rex.makeInputRef(rf.getType(), leftFieldCount + rightIdx);
            conjuncts.add(rex.makeCall(SqlStdOperatorTable.EQUALS, l, r));
        }
        RexNode condition = conjuncts.size() == 1 ? conjuncts.get(0) : rex.makeCall(SqlStdOperatorTable.AND, conjuncts);

        // Compose using RelBuilder. .push(leftScan).push(rightScan).join(INNER, condition) leaves
        // the joined tables on the stack; we then Project to keep exactly the columns the
        // original join was producing (found by matching column NAMES from the original row
        // type against the joined row type, which has all columns from both TableScans).
        builder.push(leftScan).push(rightScan).join(org.apache.calcite.rel.core.JoinRelType.INNER, condition);
        RelDataType originalRow = join.getRowType();
        RelDataType joinedRow = builder.peek().getRowType();
        List<RexNode> projections = new ArrayList<>();
        List<String> projectionNames = new ArrayList<>();
        for (RelDataTypeField original : originalRow.getFieldList()) {
            // Find the column in the joined row type by name. First hit wins — deterministic
            // because RelBuilder preserves left-then-right order.
            int foundIdx = -1;
            for (int i = 0; i < joinedRow.getFieldCount(); i++) {
                if (joinedRow.getFieldList().get(i).getName().equalsIgnoreCase(original.getName())) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx < 0) {
                // Column not found by name — Calcite's projection pushdown has stripped the
                // original columns and replaced them with synthetic ones (typically "DUMMY"
                // when the outer query is COUNT(*) with no column references). Substitute a
                // zero literal of the expected type so downstream shape-matches; the value is
                // never actually read for aggregate-only queries. Zero (not NULL) because
                // Calcite's DUMMY columns are declared NOT NULL and transformTo rejects
                // nullability mismatches.
                projections.add(rex.makeZeroLiteral(original.getType()));
            } else {
                projections.add(
                        rex.makeInputRef(joinedRow.getFieldList().get(foundIdx).getType(), foundIdx));
            }
            projectionNames.add(original.getName());
        }
        builder.project(projections, projectionNames);
        RelNode rewritten = builder.build();
        log.debug(
                "OssieAutoJoinRule: injecting relationship '{}' predicate into Cartesian join {}↔{}",
                relationship.getName(),
                left.datasetName,
                right.datasetName);
        call.transformTo(rewritten);
    }

    /**
     * Rewrite path for N-way Cartesian joins. Calcite parses {@code FROM A, B, C} as
     * {@code Join(Join(A, B), C)} with both joins having {@code condition=true}. The two-way
     * rebuild path handles the inner one. This path handles the outer by walking down to
     * <b>all</b> raw TableScans in the subtree (traversing nested Joins) and building a fresh
     * left-deep join chain against them, using Ossie relationships to derive each pair's ON
     * predicate. The outer Project restricts to the original output columns.
     *
     * <p>Ambiguity guard: if the collected TableScans include tables with multiple Ossie
     * relationships between the same pair, we don't guess — the rewrite bails and the user
     * gets an explicit-ON error. Same policy as the two-way {@link AmbiguousJoinException}
     * case.
     */
    private void tryCompoundRewrite(RelOptRuleCall call, LogicalJoin join) {
        // Collect all raw TableScans reachable in either subtree — walking past LogicalProject,
        // RelSubset, and nested Joins.
        List<TableScan> scans = new ArrayList<>();
        List<String> datasetNames = new ArrayList<>();
        OssieSchema[] schemaHolder = new OssieSchema[1];
        boolean ok = collectAllTableScans(join.getLeft(), scans, datasetNames, schemaHolder);
        if (!ok || !collectAllTableScans(join.getRight(), scans, datasetNames, schemaHolder)) return;
        if (schemaHolder[0] == null || scans.size() < 3) return;
        // Deduplicate — same TableScan can appear multiple times if the plan is exploring
        // alternative shapes. Preserve first-seen order for deterministic joining.
        java.util.LinkedHashSet<String> uniqueNames = new java.util.LinkedHashSet<>();
        List<TableScan> uniqueScans = new ArrayList<>();
        for (int i = 0; i < scans.size(); i++) {
            if (uniqueNames.add(datasetNames.get(i))) {
                uniqueScans.add(scans.get(i));
            }
        }
        if (uniqueScans.size() < 3) return;
        SemanticModel model = schemaHolder[0].model();

        // Build the join chain. Start with the first scan; for each subsequent scan, find a
        // relationship linking it to some already-joined dataset, then join with that predicate.
        RelBuilder builder = call.builder();
        RexBuilder rex = builder.getRexBuilder();
        List<String> joinedNames = new ArrayList<>();
        List<Integer> baseOffsets = new ArrayList<>(); // starting column position of each joined table

        TableScan firstScan = uniqueScans.get(0);
        String firstName = uniqueNames.iterator().next();
        builder.push(firstScan);
        joinedNames.add(firstName);
        baseOffsets.add(0);
        int totalFields = firstScan.getRowType().getFieldCount();

        for (int i = 1; i < uniqueScans.size(); i++) {
            TableScan next = uniqueScans.get(i);
            String nextName = List.copyOf(uniqueNames).get(i);
            Relationship relationship = null;
            String linkedName = null;
            for (String candidate : joinedNames) {
                Relationship r = pickRelationshipSafe(model, candidate, nextName);
                if (r != null) {
                    if (relationship != null) {
                        // Multiple candidates linking the next table into the joined set —
                        // ambiguous, bail.
                        return;
                    }
                    relationship = r;
                    linkedName = candidate;
                }
            }
            if (relationship == null) return; // no relationship — can't extend the chain
            int linkedOffset = baseOffsets.get(joinedNames.indexOf(linkedName));
            TableScan linkedScan = uniqueScans.get(joinedNames.indexOf(linkedName));
            RelDataType linkedRow = linkedScan.getRowType();
            RelDataType nextRow = next.getRowType();
            boolean sameDirection = relationship.getFrom().equals(linkedName);
            List<String> linkedCols = sameDirection ? relationship.getFromColumns() : relationship.getToColumns();
            List<String> nextCols = sameDirection ? relationship.getToColumns() : relationship.getFromColumns();
            if (linkedCols.isEmpty() || linkedCols.size() != nextCols.size()) return;
            List<RexNode> conjuncts = new ArrayList<>();
            for (int k = 0; k < linkedCols.size(); k++) {
                Integer li = fieldOrdinal(linkedRow, linkedCols.get(k));
                Integer ni = fieldOrdinal(nextRow, nextCols.get(k));
                if (li == null || ni == null) return;
                RexNode l = rex.makeInputRef(linkedRow.getFieldList().get(li).getType(), linkedOffset + li);
                RexNode r = rex.makeInputRef(nextRow.getFieldList().get(ni).getType(), totalFields + ni);
                conjuncts.add(rex.makeCall(SqlStdOperatorTable.EQUALS, l, r));
            }
            RexNode condition =
                    conjuncts.size() == 1 ? conjuncts.get(0) : rex.makeCall(SqlStdOperatorTable.AND, conjuncts);
            builder.push(next);
            builder.join(org.apache.calcite.rel.core.JoinRelType.INNER, condition);
            baseOffsets.add(totalFields);
            totalFields += nextRow.getFieldCount();
            joinedNames.add(nextName);
        }

        // Outer Project restricting to the columns the current outer Join was producing.
        RelDataType originalRow = join.getRowType();
        RelDataType joinedRow = builder.peek().getRowType();
        List<RexNode> projections = new ArrayList<>();
        List<String> projectionNames = new ArrayList<>();
        for (RelDataTypeField original : originalRow.getFieldList()) {
            int foundIdx = -1;
            for (int j = 0; j < joinedRow.getFieldCount(); j++) {
                if (joinedRow.getFieldList().get(j).getName().equalsIgnoreCase(original.getName())) {
                    foundIdx = j;
                    break;
                }
            }
            if (foundIdx < 0) {
                // Same fix as the two-way path: substitute a zero literal so downstream shape
                // matches. See two-way rewrite for the DUMMY-column rationale.
                projections.add(rex.makeZeroLiteral(original.getType()));
            } else {
                projections.add(
                        rex.makeInputRef(joinedRow.getFieldList().get(foundIdx).getType(), foundIdx));
            }
            projectionNames.add(original.getName());
        }
        builder.project(projections, projectionNames);
        RelNode rewritten = builder.build();

        log.debug("OssieAutoJoinRule: n-way rebuild over datasets {} — outer Cartesian → chained Joins", joinedNames);
        call.transformTo(rewritten);
    }

    /**
     * Wraps {@link #pickRelationship} to return null instead of throwing on ambiguity. The
     * n-way builder handles ambiguity by bailing at the caller level with more context (which
     * links to which).
     */
    private Relationship pickRelationshipSafe(SemanticModel model, String a, String b) {
        try {
            return pickRelationship(model, a, b);
        } catch (AmbiguousJoinException e) {
            return null;
        }
    }

    /**
     * Walk a RelNode subtree collecting every reachable Ossie-backed TableScan. Recognises
     * TableScan, LogicalProject, Project, and Join (both sides). Returns false if any scan
     * isn't Ossie-backed or if multiple schemas appear.
     */
    private boolean collectAllTableScans(
            RelNode rel, List<TableScan> outScans, List<String> outNames, OssieSchema[] schemaHolder) {
        RelNode cursor = unwrapSubset(rel);
        if (cursor instanceof org.apache.calcite.rel.core.Project) {
            return collectAllTableScans(
                    ((org.apache.calcite.rel.core.Project) cursor).getInput(), outScans, outNames, schemaHolder);
        }
        if (cursor instanceof org.apache.calcite.rel.core.Filter) {
            // Calcite pushes WHERE predicates down into per-arm Filter nodes ahead of the join.
            // Walk past them the same way we walk past Projects — the underlying TableScan still
            // reflects the raw dataset shape; the Filter's predicate (which our rebuild
            // preserves via projection pushdown re-running after transformTo) is orthogonal to
            // the join-key rewrite.
            return collectAllTableScans(
                    ((org.apache.calcite.rel.core.Filter) cursor).getInput(), outScans, outNames, schemaHolder);
        }
        if (cursor instanceof org.apache.calcite.rel.core.Join) {
            org.apache.calcite.rel.core.Join innerJoin = (org.apache.calcite.rel.core.Join) cursor;
            return collectAllTableScans(innerJoin.getLeft(), outScans, outNames, schemaHolder)
                    && collectAllTableScans(innerJoin.getRight(), outScans, outNames, schemaHolder);
        }
        if (cursor instanceof TableScan) {
            TableScan scan = (TableScan) cursor;
            RelOptTable table = scan.getTable();
            List<String> qualifiedName = table.getQualifiedName();
            if (qualifiedName.isEmpty()) return false;
            OssieSchema schema = unwrapOssieSchema(table);
            if (schema == null) return false;
            if (schemaHolder[0] == null) schemaHolder[0] = schema;
            else if (schemaHolder[0] != schema) return false;
            outScans.add(scan);
            outNames.add(qualifiedName.get(qualifiedName.size() - 1));
            return true;
        }
        return false;
    }

    /**
     * Walk a RelNode subtree looking for a {@link TableScan} whose backing table is an Ossie
     * dataset. Recognises a direct scan or one wrapped in a {@code LogicalProject} (Calcite
     * often introduces one for column projection). Returns null when neither shape matches.
     */
    private OssieTableRef findOssieTable(RelNode rel) {
        RelNode cursor = unwrapSubset(rel);
        // Peel off wrapping layers Calcite introduces during optimisation: LogicalProject
        // (column pruning) and RelSubset (Volcano equivalence class). We iterate to unwrap
        // arbitrary chains — TableScan can sit under Project → Project → TableScan in some
        // planner states. Bounded loop to avoid runaway if the input tree is unusual.
        for (int depth = 0; depth < 8 && !(cursor instanceof TableScan); depth++) {
            RelNode next;
            if (cursor instanceof org.apache.calcite.rel.logical.LogicalProject) {
                next = ((org.apache.calcite.rel.logical.LogicalProject) cursor).getInput();
            } else if (cursor instanceof org.apache.calcite.rel.core.Project) {
                next = ((org.apache.calcite.rel.core.Project) cursor).getInput();
            } else {
                return null;
            }
            cursor = unwrapSubset(next);
        }
        if (!(cursor instanceof TableScan)) return null;
        TableScan scan = (TableScan) cursor;
        RelOptTable relOptTable = scan.getTable();
        List<String> qualifiedName = relOptTable.getQualifiedName();
        if (qualifiedName.isEmpty()) return null;
        String datasetName = qualifiedName.get(qualifiedName.size() - 1);
        OssieSchema schema = unwrapOssieSchema(relOptTable);
        if (schema == null) return null;
        return new OssieTableRef(schema, datasetName, scan);
    }

    /**
     * If {@code rel} is a Volcano {@link RelSubset}, return its best (or original) member so we
     * can inspect the shape. Otherwise return {@code rel} unchanged. Called at every layer of
     * the walk in {@link #findOssieTable}.
     */
    private static RelNode unwrapSubset(RelNode rel) {
        if (rel instanceof RelSubset) {
            RelSubset subset = (RelSubset) rel;
            RelNode best = subset.getBest();
            if (best != null) return best;
            // No best plan chosen yet — use the original (the RelNode Volcano was constructed
            // around). Correct for the equivalence class since all members produce the same
            // rowType.
            RelNode original = subset.getOriginal();
            if (original != null) return original;
        }
        return rel;
    }

    /**
     * Look up the {@link OssieSchema} that owns a {@link RelOptTable}. Our datasets surface as
     * {@link org.apache.calcite.adapter.jdbc.JdbcSchema}-owned JdbcTables (so Calcite's planner
     * can push down JDBC-native SQL), which means {@code table.unwrap(OssieSchema.class)}
     * returns null. Instead we consult {@link OssieSchema#lookupRegistered} using the qualified
     * table name's first segment (the schema name Calcite gave us at factory time).
     */
    private OssieSchema unwrapOssieSchema(RelOptTable table) {
        List<String> qualifiedName = table.getQualifiedName();
        if (qualifiedName.size() < 2) return null;
        return OssieSchema.lookupRegistered(qualifiedName.get(0));
    }

    /**
     * Return the single Ossie relationship linking two datasets, or null when none exists.
     * Directional-agnostic: matches (from=A, to=B) OR (from=B, to=A). Throws {@link
     * AmbiguousJoinException} when more than one matches — better than silent wrong results.
     */
    private Relationship pickRelationship(SemanticModel model, String left, String right) {
        List<Relationship> matches = new ArrayList<>();
        for (Relationship r : model.getRelationships()) {
            if (r.getFrom() == null || r.getTo() == null) continue;
            if ((r.getFrom().equals(left) && r.getTo().equals(right))
                    || (r.getFrom().equals(right) && r.getTo().equals(left))) {
                matches.add(r);
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Relationship r : matches) names.add(r.getName());
            throw new AmbiguousJoinException(left, right, names);
        }
        return matches.get(0);
    }

    private Integer fieldOrdinal(RelDataType row, String columnName) {
        for (int i = 0; i < row.getFieldCount(); i++) {
            if (row.getFieldList().get(i).getName().equalsIgnoreCase(columnName)) return i;
        }
        return null;
    }

    /** Tuple carrying the identity of an Ossie dataset behind a TableScan. */
    private static final class OssieTableRef {
        final OssieSchema schema;
        final String datasetName;

        @SuppressWarnings("unused") // scan retained for future extensions (e.g. re-alias)
        final TableScan scan;

        OssieTableRef(OssieSchema schema, String datasetName, TableScan scan) {
            this.schema = schema;
            this.datasetName = datasetName;
            this.scan = scan;
        }

        @Override
        public String toString() {
            return "OssieTableRef{schema=" + (schema == null ? "null" : "ok") + ", dataset=" + datasetName + "}";
        }
    }

    /**
     * Raised when a Cartesian join sits between two datasets that have MULTIPLE Ossie
     * relationships. The user must add an explicit ON clause to pick one; the rule refuses to
     * guess.
     */
    public static class AmbiguousJoinException extends RuntimeException {
        public AmbiguousJoinException(String left, String right, List<String> candidates) {
            super("OssieAutoJoinRule: multiple Ossie relationships link '" + left + "' and '"
                    + right + "': " + candidates
                    + ". Add an explicit ON clause to disambiguate.");
        }
    }
}
