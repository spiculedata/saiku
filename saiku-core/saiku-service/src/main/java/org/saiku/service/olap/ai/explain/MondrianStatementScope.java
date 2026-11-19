/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.sql.SQLException;
import mondrian.server.Execution;
import mondrian.server.Locus;
import org.olap4j.OlapStatement;

/**
 * Builds the {@link SqlCaptureProbe.Scope} for one explained cell query (saiku#2328).
 *
 * <p>Mondrian runs the query on its shepherd pool and loads segments on a second pool, so the SQL
 * for the explained cell is logged on threads this class cannot name in advance. What those
 * threads do share is the {@link Locus} Mondrian pushes while it works for an execution, and the
 * execution knows the statement it belongs to. An event on any other thread is therefore claimed
 * only when the Locus active on it belongs to {@code statement}; another user's query carries a
 * different statement, and is dropped.
 *
 * <p>The opening thread is always claimed, with or without a Locus: while the probe is open it
 * runs nothing but this request. That matters because Mondrian parses and validates the cell
 * query on the caller's thread under the connection's internal statement ({@code parseQuery} uses
 * {@code Locus.execute(RolapConnection, ...)}), so when the cell's segment is already cached the
 * only SQL in the window carries that internal statement's id, not {@code statement}'s. The
 * internal statement is shared by everyone on the connection, so it is never matched by id; it is
 * claimed only through the opening thread. When the statement is not a Mondrian one (no id to
 * compare), the scope degrades to the opening thread alone, which may miss the SQL but never shows
 * a stranger's.
 */
final class MondrianStatementScope {

    private MondrianStatementScope() {}

    /** Scope claiming events logged on behalf of {@code statement}'s Mondrian execution. */
    static SqlCaptureProbe.Scope forStatement(OlapStatement statement) {
        return forStatementId(statementId(statement), Thread.currentThread());
    }

    static SqlCaptureProbe.Scope forStatementId(Long statementId, Thread openingThread) {
        SqlCaptureProbe.Scope sameThread = SqlCaptureProbe.Scope.thread(openingThread);
        if (statementId == null) {
            return sameThread;
        }
        return () -> {
            if (sameThread.owns()) {
                return true;
            }
            Locus locus = Locus.peekOrNull();
            if (locus == null) {
                return false;
            }
            Execution execution = locus.execution;
            return execution != null
                    && execution.getMondrianStatement() != null
                    && execution.getMondrianStatement().getId() == statementId;
        };
    }

    private static Long statementId(OlapStatement statement) {
        if (statement == null) {
            return null;
        }
        try {
            return statement.unwrap(mondrian.server.Statement.class).getId();
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }
}
