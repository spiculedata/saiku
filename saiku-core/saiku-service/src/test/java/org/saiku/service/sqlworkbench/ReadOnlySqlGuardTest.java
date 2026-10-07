/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** saiku#1107 — unit coverage for the SQL workbench's read-only statement guard. */
public class ReadOnlySqlGuardTest {

    @Test
    public void allows_select() {
        ReadOnlySqlGuard.checkReadOnly("SELECT 1");
    }

    @Test
    public void allows_select_with_leading_whitespace_and_trailing_semicolon() {
        ReadOnlySqlGuard.checkReadOnly("  \n select * from customer ;  ");
    }

    @Test
    public void allows_select_case_insensitively() {
        ReadOnlySqlGuard.checkReadOnly("sElEcT 1");
    }

    @Test
    public void allows_with_cte() {
        ReadOnlySqlGuard.checkReadOnly("WITH t AS (SELECT 1 AS x) SELECT * FROM t");
    }

    @Test
    public void allows_a_leading_line_comment() {
        ReadOnlySqlGuard.checkReadOnly("-- just counting rows\nSELECT COUNT(*) FROM customer");
    }

    @Test
    public void allows_a_leading_block_comment() {
        ReadOnlySqlGuard.checkReadOnly("/* explain plan below */ SELECT 1");
    }

    @Test
    public void allows_a_semicolon_inside_a_string_literal() {
        ReadOnlySqlGuard.checkReadOnly("SELECT ';' AS separator");
    }

    @Test
    public void rejects_blank() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("   "));
    }

    @Test
    public void rejects_null() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly(null));
    }

    @Test
    public void rejects_insert() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("INSERT INTO customer (id) VALUES (1)"));
    }

    @Test
    public void rejects_update() {
        assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("UPDATE customer SET id = 1"));
    }

    @Test
    public void rejects_delete() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("DELETE FROM customer"));
    }

    @Test
    public void rejects_drop() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("DROP TABLE customer"));
    }

    @Test
    public void rejects_a_stacked_statement_after_a_valid_select() {
        assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1; DROP TABLE customer"));
    }

    @Test
    public void rejects_a_write_statement_hidden_behind_a_leading_select_comment_trick() {
        // A write statement is still a write statement even if a comment claims otherwise —
        // the guard reads the actual first keyword, not any text around it.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("/* SELECT this looks safe */ DELETE FROM customer"));
    }
}
