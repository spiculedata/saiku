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

    // ---- saiku#2318: statements whose leading keyword is read-only but whose body writes ----

    @Test
    public void explainAnalyzeDelete_isRejected() {
        // EXPLAIN ANALYZE EXECUTES the DML it explains on PostgreSQL, H2, DuckDB and MySQL.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("EXPLAIN ANALYZE DELETE FROM customer WHERE 1=1"));
    }

    @Test
    public void explainPlanIsRejectedToo() {
        // Calcite parses EXPLAIN PLAN FOR ...; the plan is not worth the risk and the ANALYZE
        // distinction is a per-dialect trap, so EXPLAIN is rejected outright.
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("EXPLAIN PLAN FOR SELECT 1"));
    }

    @Test
    public void dataModifyingCte_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("WITH d AS (DELETE FROM customer RETURNING 1) SELECT * FROM d"));
    }

    @Test
    public void insertInsideAWithIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("WITH a AS (SELECT 1 AS x) INSERT INTO t SELECT * FROM a"));
    }

    @Test
    public void h2FileWriteSelect_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT FILE_WRITE(STRINGTOUTF8('pwned'), '/tmp/saiku-pwned')"));
    }

    @Test
    public void h2FileRead_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT FILE_READ('/etc/passwd', NULL)"));
    }

    @Test
    public void h2CsvRead_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM CSVREAD('/etc/passwd')"));
    }

    @Test
    public void h2CsvWrite_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT CSVWRITE('/tmp/x', 'SELECT 1', 'charset=UTF-8')"));
    }

    @Test
    public void mysqlLoadFile_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT LOAD_FILE('/etc/passwd')"));
    }

    @Test
    public void duckDbReadText_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM read_text('/etc/shadow')"));
    }

    @Test
    public void duckDbReadCsvWithNamedArgs_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM read_csv('/etc/passwd', header = true)"));
    }

    @Test
    public void duckDbQueryFunction_isRejected() {
        // DuckDB's query('...') executes the SQL handed to it as a string.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM query('DELETE FROM t')"));
    }

    @Test
    public void quotedFunctionName_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT \"read_text\"('/etc/shadow')"));
    }

    @Test
    public void mysqlIntoOutfile_isRejected() {
        // Not parseable at all — fails closed.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT id INTO OUTFILE '/tmp/x' FROM customer"));
    }

    @Test
    public void createTable_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("CREATE TABLE x (a INT)"));
    }

    @Test
    public void merge_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "MERGE INTO customer USING src ON customer.id = src.id WHEN MATCHED THEN UPDATE SET id = 2"));
    }

    @Test
    public void call_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("CALL some_proc(1)"));
    }

    @Test
    public void setStatement_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SET SESSION TRANSACTION READ WRITE"));
    }

    @Test
    public void unparseableStatement_failsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM customer WHERE name = 'unterminated"));
    }

    // ---- legitimate read-only statements keep working (the fix must not narrow them away) ----

    @Test
    public void allows_describe() {
        ReadOnlySqlGuard.checkReadOnly("DESCRIBE customer");
    }

    @Test
    public void allows_desc_lexically() {
        // Calcite has no DESC grammar; the lexical allowance keeps MySQL's read-only synonym working.
        ReadOnlySqlGuard.checkReadOnly("DESC customer");
    }

    @Test
    public void allows_show_lexically() {
        ReadOnlySqlGuard.checkReadOnly("SHOW TABLES");
    }

    @Test
    public void allows_show_evenWithAStackedStatementCheckStillApplied() {
        assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SHOW TABLES; DELETE FROM t"));
    }

    @Test
    public void allows_backtickQuotedMysqlIdentifiers() {
        ReadOnlySqlGuard.checkReadOnly("SELECT `id` FROM `customer` WHERE `id` > 1");
    }

    @Test
    public void allows_doubleQuotedIdentifiers() {
        ReadOnlySqlGuard.checkReadOnly("SELECT \"id\" FROM \"customer\"");
    }

    @Test
    public void allows_tableStatement() {
        ReadOnlySqlGuard.checkReadOnly("TABLE customer");
    }

    @Test
    public void allows_valuesList() {
        ReadOnlySqlGuard.checkReadOnly("VALUES (1),(2)");
    }

    @Test
    public void allows_multipleCtes() {
        ReadOnlySqlGuard.checkReadOnly("WITH a AS (SELECT 1 AS x), b AS (SELECT 2 AS y) SELECT * FROM a, b");
    }

    @Test
    public void allows_union() {
        ReadOnlySqlGuard.checkReadOnly("SELECT 1 UNION SELECT 2");
    }

    @Test
    public void allows_orderByAndLimit() {
        ReadOnlySqlGuard.checkReadOnly("SELECT * FROM customer ORDER BY id LIMIT 5");
    }

    @Test
    public void allows_subqueries() {
        ReadOnlySqlGuard.checkReadOnly("SELECT * FROM (SELECT id FROM customer) sub WHERE id = 1");
    }

    @Test
    public void allows_groupByHaving() {
        ReadOnlySqlGuard.checkReadOnly("SELECT id, COUNT(*) FROM customer GROUP BY id HAVING COUNT(*) > 0");
    }

    @Test
    public void rejects_aHttpScanTarget() {
        // DuckDB reads network URLs from a bare string literal; not parseable — fails closed.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT * FROM 'http://169.254.169.254/latest/meta-data/'"));
    }
}
