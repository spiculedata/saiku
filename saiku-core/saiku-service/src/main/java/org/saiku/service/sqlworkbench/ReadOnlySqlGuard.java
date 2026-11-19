/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * Read-only guard for the SQL workbench (saiku#1107 phase 1, hardened by saiku#2318). Rejects
 * anything that isn't a single pure query before it ever reaches a JDBC driver.
 *
 * <p>saiku#2318: the original guard only checked the statement's <em>leading keyword</em> against an
 * allowlist, which any statement whose first word looks read-only defeats: {@code EXPLAIN ANALYZE
 * DELETE …} actually executes the DML on PostgreSQL/H2/DuckDB/MySQL, a data-modifying CTE
 * ({@code WITH d AS (DELETE … RETURNING 1) SELECT …} on PostgreSQL) starts with {@code WITH}, and
 * H2/DuckDB/MySQL host-file primitives ({@code SELECT FILE_WRITE(...)}, {@code SELECT * FROM
 * CSVREAD(...)}, {@code SELECT LOAD_FILE(...)}) start with {@code SELECT}. The guard now
 * <b>parses</b> the statement (Apache Calcite's {@link SqlParser}, already on the classpath) and
 * walks the parse tree:
 *
 * <ol>
 *   <li>only a top-level pure query is accepted — {@code SELECT} / set operations / {@code VALUES}
 *       / {@code TABLE} / {@code DESCRIBE}, optionally wrapped in {@code WITH} or {@code ORDER BY};
 *   <li>{@code EXPLAIN} is rejected outright (at minimum its {@code ANALYZE} form executes the
 *       statement it explains — and the plan output was never worth the risk);
 *   <li>every node in the tree is checked for DML/DDL ({@code INSERT}/{@code UPDATE}/{@code
 *       DELETE}/{@code MERGE}) and for known side-effecting functions (host-file read/write, H2
 *       {@code CSV*}, DuckDB {@code read_*} / {@code query()}, MySQL {@code LOAD_FILE});
 *   <li>statements the parser cannot read at all are <b>rejected</b> (fail closed), except the
 *       mainstream read-only introspection forms the parser has no grammar for — {@code SHOW …},
 *       {@code DESC <table>} — which keep the single-statement check only. Those are read-only in
 *       every dialect Saiku ships drivers for.
 * </ol>
 *
 * <p>This remains one layer of a multi-layer defense: {@link SqlWorkbenchService} additionally
 * enforces a session-level read-only mode on the backends that support one (fail closed), and the
 * datasource can only be reached by a user holding {@code ROLE_SQL_EXEC}/{@code ROLE_ADMIN}.
 */
public final class ReadOnlySqlGuard {

    /**
     * Introspection keywords kept on a lexical allowance because Calcite has no grammar for them
     * ({@code DESC}) or Saiku dialects use forms Calcite cannot read ({@code SHOW}). They are
     * read-only in every mainstream dialect: SHOW/DESCRIBE never modify data on PostgreSQL, MySQL,
     * H2 or DuckDB. Everything the parser CAN read must parse as a pure query — there is no lexical
     * allowance for SELECT/WITH, because that is exactly the hole saiku#2318 describes.
     */
    private static final Set<String> LEXICAL_ALLOWLIST = Set.of("SHOW", "DESCRIBE", "DESC");

    /** Parse-tree node kinds accepted as the top-level statement. All pure queries. */
    private static final Set<SqlKind> ALLOWED_TOP_LEVEL_KINDS = EnumSet.of(
            SqlKind.SELECT,
            SqlKind.VALUES,
            SqlKind.WITH,
            SqlKind.ORDER_BY,
            SqlKind.EXPLICIT_TABLE,
            SqlKind.DESCRIBE_TABLE,
            SqlKind.UNION,
            SqlKind.INTERSECT,
            SqlKind.EXCEPT,
            SqlKind.MINUS);

    /** Node kinds that modify data or a schema wherever they appear in the tree. */
    private static final Set<SqlKind> FORBIDDEN_KINDS = EnumSet.of(
            SqlKind.INSERT,
            SqlKind.UPDATE,
            SqlKind.DELETE,
            SqlKind.MERGE,
            SqlKind.EXPLAIN,
            SqlKind.CREATE_TABLE,
            SqlKind.CREATE_VIEW,
            SqlKind.CREATE_INDEX,
            SqlKind.DROP_TABLE,
            SqlKind.DROP_VIEW,
            SqlKind.DROP_INDEX,
            SqlKind.TRUNCATE_TABLE,
            SqlKind.ALTER_TABLE,
            SqlKind.ALTER_VIEW);

    /**
     * Backend built-ins that read or write host files, reach the network or execute SQL handed to
     * them as a string — all reachable from an innocent-looking {@code SELECT} on the engines Saiku
     * ships (H2, DuckDB) or the warehouses it connects to (MySQL/MariaDB). Matched case-insensitively
     * against the call's operator name, so quoted spellings ({@code "read_text"(…)}) are covered too.
     */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "FILE_WRITE",
            "FILE_READ",
            "CSVREAD",
            "CSVWRITE",
            "LOAD_FILE",
            "READ_TEXT",
            "READ_CSV",
            "READ_JSON",
            "READ_XLSX",
            "READ_BLOB",
            "LINK_SCHEMA",
            "QUERY",
            "QUERY_TABLE");

    /** Second parse pass: MySQL backtick quoting, which the default (double-quote) grammar rejects. */
    private static final SqlParser.Config BACKTICK_CONFIG =
            SqlParser.configBuilder().setQuoting(Quoting.BACK_TICK).build();

    private ReadOnlySqlGuard() {}

    /**
     * @throws IllegalArgumentException when {@code sql} is blank, carries more than one statement,
     *     is not a pure read-only query, or cannot be parsed at all. The message is safe to return
     *     to the caller — it never echoes the offending SQL.
     */
    public static void checkReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL statement is empty");
        }
        String singleStatement = singleTopLevelStatement(sql);

        // Mainstream introspection forms the parser has no grammar for. SHOW/DESC/DESCRIBE are
        // read-only statements in every dialect Saiku ships a driver for, and the single-statement
        // check above already rules out statement stacking.
        if (LEXICAL_ALLOWLIST.contains(firstWord(singleStatement).toUpperCase(Locale.ROOT))) {
            return;
        }

        SqlNode statement = parse(singleStatement);
        if (statement == null) {
            throw new IllegalArgumentException(
                    "Only read-only queries are allowed here; the statement could not be parsed as one");
        }
        if (!ALLOWED_TOP_LEVEL_KINDS.contains(statement.getKind())) {
            throw new IllegalArgumentException(
                    "Only read-only queries (SELECT / WITH / VALUES / TABLE / DESCRIBE) are allowed here");
        }
        checkTree(statement);
    }

    /**
     * Parse under the standard (double-quote) grammar first, then under MySQL backtick quoting —
     * the two identifier-quote styles are mutually exclusive in a single parser config, and a
     * statement using both is rejected rather than guessed at. Returns {@code null} when neither
     * grammar accepts the statement (fail closed).
     */
    private static SqlNode parse(String sql) {
        try {
            return SqlParser.create(sql, SqlParser.Config.DEFAULT).parseStmt();
        } catch (SqlParseException standard) {
            try {
                return SqlParser.create(sql, BACKTICK_CONFIG).parseStmt();
            } catch (SqlParseException backtick) {
                return null;
            }
        }
    }

    /**
     * Walks the whole parse tree: any DML/DDL node or known side-effecting function rejects the
     * statement, no matter how deeply it is nested (CTE bodies, subqueries, table functions).
     */
    private static void checkTree(SqlNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof SqlNodeList list) {
            for (SqlNode item : list) {
                checkTree(item);
            }
            return;
        }
        if (node instanceof SqlCall call) {
            if (FORBIDDEN_KINDS.contains(call.getKind())) {
                throw new IllegalArgumentException(
                        "Only read-only queries are allowed here; the statement modifies data");
            }
            String operator = call.getOperator().getName();
            if (operator != null && FORBIDDEN_FUNCTIONS.contains(operator.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Only read-only queries are allowed here; the statement uses a function that "
                                + "reads or writes outside the database");
            }
            for (SqlNode operand : call.getOperandList()) {
                checkTree(operand);
            }
        }
        // SqlIdentifier, SqlLiteral, SqlDataTypeSpec, SqlDynamicParam: leaves — nothing inside them
        // can execute.
    }

    /**
     * Walks the SQL once, skipping {@code --} / {@code /* *&#47;} comments and quoted text (' and
     * "), and returns the trimmed text of the first top-level statement. Throws when a second,
     * non-blank top-level statement follows a {@code ;} — stacked statements are how a read-only
     * guard on statement #1 gets bypassed by statement #2. The returned text carries no top-level
     * semicolons, which is what the parser pass consumes.
     */
    private static String singleTopLevelStatement(String sql) {
        StringBuilder first = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        boolean sawTerminator = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (!inSingleQuote && !inDoubleQuote && c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            if (!inDoubleQuote && c == '\'') {
                inSingleQuote = !inSingleQuote;
            } else if (!inSingleQuote && c == '"') {
                inDoubleQuote = !inDoubleQuote;
            }
            if (!inSingleQuote && !inDoubleQuote && c == ';') {
                sawTerminator = true;
                i++;
                continue;
            }
            (sawTerminator ? rest : first).append(c);
            i++;
        }
        if (!rest.toString().isBlank()) {
            throw new IllegalArgumentException("Only a single SQL statement is allowed in the SQL workbench");
        }
        return first.toString().trim();
    }

    private static String firstWord(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i);
    }
}
