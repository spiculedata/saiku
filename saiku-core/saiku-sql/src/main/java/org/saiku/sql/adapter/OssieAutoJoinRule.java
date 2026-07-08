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
 * <p>Non-goals for this first slice, tracked as follow-ups:
 *
 * <ul>
 *   <li>Cross-schema joins (Ossie + non-Ossie tables).
 *   <li>Deeper subtree walk — today we only recognise a direct {@link TableScan} child of the
 *       Join, or a {@link org.apache.calcite.rel.logical.LogicalProject} wrapper thereof.
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
        if (left == null || right == null) return;
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
            if (foundIdx < 0) return; // shouldn't happen — original columns must appear in the joined row
            projections.add(
                    rex.makeInputRef(joinedRow.getFieldList().get(foundIdx).getType(), foundIdx));
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
