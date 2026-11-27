/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("WITH d AS (DELETE FROM customer RETURNING 1) SELECT * FROM d"));
        assertFalse(ex instanceof ReadOnlySqlGuard.SqlSyntaxException);
        assertTrue(ex.getMessage().contains("Only read-only queries"));
    }

    @Test
    public void malformedCteWithQuotedDmlText_isStillASyntaxError() {
        assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "WITH d AS (SELECT 'AS (DELETE FROM customer)' FROM) SELECT * FROM d"));
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

    // ---- saiku#2522: the validated statement is the statement the engine executes ----

    @Test
    public void crEndedLineComment_hidingExtraColumns_isRejected() {
        // H2/PostgreSQL/DuckDB end a -- comment at \r as well as \n, so the executed statement
        // has TWO columns while a comment-stripped copy shows only "SELECT 1 AS a".
        assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1 AS a --\r, 2 AS b"));
    }

    @Test
    public void stackedDdl_behindACrEndedComment_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "SELECT 1 AS a --\r; CREATE LOCAL TEMPORARY TABLE T(X INT); DROP TABLE T"));
    }

    @Test
    public void backtickIdentifier_hidingABlockCommentOpener_withStackedDdl_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "SELECT 1 AS `x/*`; CREATE LOCAL TEMPORARY TABLE T2(X INT); DROP TABLE T2; SELECT 2 AS `*/y`"));
    }

    @Test
    public void showShortcut_carryingStackedDdlBehindACrComment_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "SHOW TABLES --\r; CREATE LOCAL TEMPORARY TABLE T3(X INT); DROP TABLE T3"));
    }

    @Test
    public void jdbcFunctionEscape_hidingADeniedFunction_isRejected() {
        // The parsed operator name of a {fn …} escape is not the bare function name the
        // deny-list matches, and the driver rewrites the escape before executing — so escapes
        // are rejected outright, before a parse can even bless them.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly(
                        "SELECT CAST({fn FILE_READ('/tmp/canary.txt', NULL)} AS VARCHAR) AS c"));
    }

    @Test
    public void jdbcDatabasePathEscape_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT {fn DATABASE_PATH()} AS p"));
    }

    @Test
    public void mysqlExecutableConditionalComment_isRejected() {
        // Calcite skips /*! … */ as a comment; MySQL executes it as code.
        assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1 /*!; DROP TABLE t */"));
    }

    @Test
    public void mysqlNumberedExecutableComment_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1 /*40101 SET NAMES utf8 */"));
    }

    @Test
    public void mariaDbExecutableComment_isRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1 /*M!100100; DROP TABLE t */"));
    }

    @Test
    public void optimizerHintComment_isStillAllowed() {
        ReadOnlySqlGuard.checkReadOnly("SELECT 1 /*+ USE_HASH_JOIN(t) */");
    }

    @Test
    public void showWithATrailingSemicolon_isStillAllowed() {
        ReadOnlySqlGuard.checkReadOnly("SHOW TABLES; ");
    }

    @Test
    public void unterminatedQuoteOnALexicalStatement_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("DESC customer 'x"));
    }

    @Test
    public void showWithAComment_isStrippedAndAllowed() {
        // A comment is stripped before anything is parsed or executed, so the engine cannot
        // re-read it differently — the executed text is the clean statement.
        assertEquals("SHOW TABLES", ReadOnlySqlGuard.validatedStatement("SHOW TABLES -- note"));
        assertEquals("SHOW TABLES", ReadOnlySqlGuard.validatedStatement("/* ok */ SHOW TABLES"));
        assertEquals("DESC customer", ReadOnlySqlGuard.validatedStatement("DESC customer -- x\n"));
    }

    @Test
    public void backtickQuotedIdentifierContainingCommentMarkers_isAllowedWhenAlone() {
        // MySQL treats a backtick-quoted identifier as one token: the comment markers inside it
        // are data, not code — safe on its own (it is the STACKED form above that is rejected).
        ReadOnlySqlGuard.checkReadOnly("SELECT 1 AS `x/*y`");
    }

    // ---- the validated statement is the executed statement: validatedStatement returns the text ----

    @Test
    public void validatedStatement_isTheSubmittedTextWhenThereIsNothingToStrip() {
        assertEquals("SELECT 1 AS a", ReadOnlySqlGuard.validatedStatement("SELECT 1 AS a"));
        assertEquals("SELECT 1", ReadOnlySqlGuard.validatedStatement("  SELECT 1 ;  "));
    }

    @Test
    public void validatedStatement_stripsComments_soTheEngineCannotReinterpretThem() {
        assertEquals("SELECT 1", ReadOnlySqlGuard.validatedStatement("/* plan */ SELECT 1 -- note\n"));
        // A -- comment H2 would end at \r (here as part of a CRLF) is stripped the same way,
        // so what runs is what was read:
        assertEquals("SELECT 1", ReadOnlySqlGuard.validatedStatement("SELECT 1 -- c\r\n"));
    }

    @Test
    public void validatedStatement_loneCrComment_isRejected() {
        // H2/PostgreSQL/DuckDB end the comment at \r; other grammars do not — the text cannot be
        // validated reliably, so it is rejected outright.
        assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.validatedStatement("SELECT 1 AS a --\r, 2 AS b"));
    }

    @Test
    public void validatedStatement_quotedContentIsPreservedVerbatim() {
        assertEquals("SELECT ';' AS separator", ReadOnlySqlGuard.validatedStatement("SELECT ';' AS separator"));
    }

    // ---- saiku#2598: a statement that cannot be parsed is a SYNTAX error, not a permission problem ----

    @Test
    public void quotedTable_isASyntaxErrorWithTheParserPosition_andTheIdentifierHint() {
        ReadOnlySqlGuard.SqlSyntaxException ex = assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("select * from 'account'"));
        assertTrue(ex.getMessage().contains("SQL syntax error at line 1, column 15"));
        assertTrue(ex.getMessage().contains("near \"'account'\""));
        assertTrue(ex.getMessage().contains("table names are identifiers — use account or \"account\", not 'account'"));
        assertTrue(ex.getMessage().contains("Only read-only statements can be run"));
    }

    @Test
    public void typo_isASyntaxErrorWithTheParserPosition() {
        ReadOnlySqlGuard.SqlSyntaxException ex = assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class, () -> ReadOnlySqlGuard.checkReadOnly("selec 1"));
        assertTrue(ex.getMessage().contains("SQL syntax error at line 1, column 1"));
        assertTrue(ex.getMessage().contains("Only read-only statements can be run"));
    }

    @Test
    public void unbalancedQuote_getsItsOwnMessage() {
        ReadOnlySqlGuard.SqlSyntaxException ex = assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("select * from account where name = 'o"));
        assertTrue(ex.getMessage().contains("Unterminated string or identifier quote"));
        assertTrue(ex.getMessage().contains("Only read-only statements can be run"));
    }

    @Test
    public void unbalancedDoubleQuote_getsTheSameUnterminatedMessage() {
        ReadOnlySqlGuard.SqlSyntaxException ex = assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT \"unterminated FROM customer"));
        assertTrue(ex.getMessage().contains("Unterminated string or identifier quote"));
    }

    @Test
    public void syntaxMessage_isTruncated_andCarriesNoClassNamesOrStackFrames() {
        // "SELECT … INTO OUTFILE" is unparseable; whatever the parser reports, the caller-facing
        // message must stay a bounded, class-name-free sentence.
        ReadOnlySqlGuard.SqlSyntaxException ex = assertThrows(
                ReadOnlySqlGuard.SqlSyntaxException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT id INTO OUTFILE '/tmp/x' FROM customer"));
        assertTrue(ex.getMessage().length() <= 400);
        assertFalse(ex.getMessage().matches("(?s).*\\w+\\.\\w+\\.\\w+.*")); // no a.b.c dotted class path
        assertFalse(ex.getMessage().contains("Exception"));
        assertFalse(ex.getMessage().contains("at line \n")); // no stack frame shape
    }

    @Test
    public void aRealWrite_isStillAReadOnlyViolation_notASyntaxError() {
        // DELETE parses fine — the refusal is about permissions, and keeps its own wording/code.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("DELETE FROM customer"));
        assertFalse(ex instanceof ReadOnlySqlGuard.SqlSyntaxException);
        assertTrue(ex.getMessage().contains("Only read-only queries"));
    }

    @Test
    public void theBypassRefusals_keepTheirOwnWording_notTheSyntaxMessage() {
        // #2318/#2522 refusals are statements the parser CAN read (or scans reject) — they are
        // permission problems, not syntax problems.
        IllegalArgumentException cr = assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1 AS a --\r, 2 AS b"));
        assertFalse(cr instanceof ReadOnlySqlGuard.SqlSyntaxException);
        assertTrue(cr.getMessage().contains("Line comments"));

        IllegalArgumentException escape = assertThrows(
                IllegalArgumentException.class,
                () -> ReadOnlySqlGuard.checkReadOnly("SELECT {fn DATABASE_PATH()} AS p"));
        assertFalse(escape instanceof ReadOnlySqlGuard.SqlSyntaxException);
        assertTrue(escape.getMessage().contains("JDBC escape"));

        IllegalArgumentException stacked = assertThrows(
                IllegalArgumentException.class, () -> ReadOnlySqlGuard.checkReadOnly("SELECT 1; DROP TABLE t"));
        assertFalse(stacked instanceof ReadOnlySqlGuard.SqlSyntaxException);
        assertTrue(stacked.getMessage().contains("single SQL statement"));
    }
}
