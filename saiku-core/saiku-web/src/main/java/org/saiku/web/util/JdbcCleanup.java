/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.util;

import java.sql.ResultSet;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drillthrough cleanup helper. Closes a {@link ResultSet} and its parent {@link Statement}
 * without ever throwing — the previous in-line {@code finally} blocks in
 * {@code Query2Resource} would rethrow as {@code SaikuServiceException} and shadow whatever
 * real failure surfaced inside the {@code try}, masking the actual query error in logs and
 * leaking the statement.
 */
public final class JdbcCleanup {

    private static final Logger log = LoggerFactory.getLogger(JdbcCleanup.class);

    private JdbcCleanup() {}

    /**
     * Best-effort close: extract the parent statement (if obtainable), close the
     * {@link ResultSet}, then close the {@link Statement}. Each step is independently
     * try/catched so a failure in one does not skip the next, and nothing escapes.
     */
    public static void closeQuietly(ResultSet rs) {
        if (rs == null) {
            return;
        }
        Statement stmt = null;
        try {
            stmt = rs.getStatement();
        } catch (Throwable t) {
            log.debug("Could not obtain Statement from drillthrough ResultSet during cleanup", t);
        }
        try {
            rs.close();
        } catch (Throwable t) {
            log.debug("Failed to close drillthrough ResultSet", t);
        }
        if (stmt != null) {
            try {
                stmt.close();
            } catch (Throwable t) {
                log.debug("Failed to close drillthrough Statement", t);
            }
        }
    }
}
