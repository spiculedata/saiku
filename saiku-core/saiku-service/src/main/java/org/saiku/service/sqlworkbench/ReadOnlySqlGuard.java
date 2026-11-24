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
 * Read-only guard for the SQL workbench (saiku#1107 phase 1, hardened by saiku#2318, closed by
 * saiku#2522). Rejects anything that isn't a single pure query before it ever reaches a JDBC
 * driver, and returns the exact statement text the caller must execute — the validated statement
 * IS the executed statement.
 *
 * <p>saiku#2318: the original guard only checked the statement's <em>leading keyword</em> against
 * an allowlist, which any statement whose first word looks read-only defeats: {@code EXPLAIN
 * ANALYZE DELETE …} actually executes the DML on PostgreSQL/H2/DuckDB/MySQL, a data-modifying CTE
 * ({@code WITH d AS (DELETE … RETURNING 1) SELECT …} on PostgreSQL) starts with {@code WITH}, and
 * H2/DuckDB/MySQL host-file primitives ({@code SELECT FILE_WRITE(...)}, {@code SELECT * FROM
 * CSVREAD(...)}, {@code SELECT LOAD_FILE(...)}) start with {@code SELECT}. The guard now parses
 * the statement (Apache Calcite's {@link SqlParser}, already on the classpath) and walks the parse
 * tree:
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
 *       {@code DESC <table>} — which keep a single-statement lexical check only. Those are
 *       read-only in every dialect Saiku ships drivers for.
 * </ol>
 *
 * <p>saiku#2522: a parse of the raw text is NOT enough, because the validated statement and the
 * executed one can still differ where a dialect's comment or escape semantics disagree with the
 * parser's. The guard therefore normalises the submitted text with <b>conservative engine
 * semantics</b> before parsing, and the service executes the normalised, parse-validated text:
 *
 * <ul>
 *   <li><b>comments are stripped</b> — the scanner ends a {@code --} comment at the earliest
 *       place any shipped engine ends one ({@code \r}, {@code \n} or EOF; H2, PostgreSQL and
 *       DuckDB all end it at {@code \r}, while Calcite's lexer does not), and a block comment at
 *       the first {@code *&#47;}. The stripped text is what is parsed and what is executed, so no
 *       engine can re-interpret a comment that the guard has already read past;
 *   <li><b>a {@code --} comment terminated by a bare {@code \r} (not {@code \r\n}) is rejected</b>
 *       — engines disagree on where such a comment ends, so the text cannot be validated
 *       reliably. Fail closed;
 *   <li><b>JDBC escapes are rejected</b> — any {@code {} outside quotes/comments ({@code {fn
 *       FILE_READ(...)}}, {@code {ts …}}, {@code {oj …}}). Calcite parses {@code {fn …}} into a
 *       call whose operator name is not the bare function name the deny-list matches, and the
 *       driver rewrites the escape before executing, so the validated statement and the executed
 *       one would differ;
 *   <li><b>MySQL/MariaDB executable conditional comments are rejected</b> — {@code /*!…*&#47;},
 *       {@code /*NNNNN…*&#47;} and {@code /*M!…*&#47;}, which parsers skip as comments but the
 *       engine runs as code. Optimizer hints ({@code /*+ … *&#47;}) are ordinary comments here;
 *   <li><b>MySQL backtick identifiers are quoted text</b> to the scanner, so a `` ` ``-quoted
 *       identifier cannot hide a block-comment opener;
 *   <li><b>stacked statements are rejected</b> — anything non-blank after a top-level {@code ;},
 *       computed on the stripped text so a comment cannot hide it;
 *   <li><b>unbalanced quotes are rejected</b> — a quote still open at end of input.
 * </ul>
 *
 * <p>This remains one layer of a multi-layer defense: {@link SqlWorkbenchService} additionally
 * disables JDBC escape processing on the {@code Statement}, enforces a session-level read-only
 * mode on the backends that support one (fail closed), and the datasource can only be reached by
 * a user holding {@code ROLE_SQL_EXEC}/{@code ROLE_ADMIN}.
 */
public final class ReadOnlySqlGuard {

    /**
     * Introspection keywords kept on a lexical allowance because Calcite has no grammar for them
     * ({@code DESC}) or Saiku dialects use forms Calcite cannot read ({@code SHOW}). They are
     * read-only in every mainstream dialect: SHOW/DESCRIBE never modify data on PostgreSQL, MySQL,
     * H2 or DuckDB. The allowance applies only to a statement that survived the strict raw scan
     * (single statement, quotes balanced, no escapes, no executable comments). Everything the
     * parser CAN read must parse as a pure query — there is no lexical allowance for SELECT/WITH,
     * because that is exactly the hole saiku#2318 describes.
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
     * What the comment- and quote-aware raw scan found about the submitted text. The scan never
     * changes quoting or token order — it strips comments and flags the constructs that would let
     * the engine execute something other than what a parse of the stripped text validates.
     *
     * @param strippedText      the submitted text with comments removed (each replaced by one
     *                          space); this is the text that is parsed and executed
     * @param jdbcEscape        a {@code {} appears outside quotes/comments (JDBC escape syntax)
     * @param executableComment a MySQL/MariaDB executable conditional comment ({@code /*!}, {@code
     *                          /*NNNNN}, {@code /*M!}) appears outside quotes
     * @param crTerminatedComment a {@code --} comment terminated by a bare {@code \r} (not {@code
     *                          \r\n}) — engines disagree on where it ends
     * @param stacked           a non-blank statement follows a top-level {@code ;} (on the stripped
     *                          text)
     * @param unbalancedQuote   a {@code '}/{@code "}/{@code `} quote is still open at end of input
     */
    private record ScanResult(
            String strippedText,
            boolean jdbcEscape,
            boolean executableComment,
            boolean crTerminatedComment,
            boolean stacked,
            boolean unbalancedQuote) {}

    /**
     * Validates {@code sql} and returns the statement text the caller must execute: the submitted
     * text with comments stripped, guaranteed single, pure read-only, and free of constructs a
     * dialect could re-interpret behind a parse. The returned text is the validated statement —
     * the service executes it verbatim, so the check and the execution can never disagree.
     *
     * @throws IllegalArgumentException when {@code sql} is blank, carries more than one statement,
     *     is not a pure read-only query, contains a JDBC escape, an executable conditional comment
     *     or a CR-terminated line comment, or cannot be parsed at all. The message is safe to
     *     return to the caller — it never echoes the offending SQL.
     */
    public static String validatedStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL statement is empty");
        }
        ScanResult scan = scanRaw(sql);
        if (scan.jdbcEscape()) {
            throw new IllegalArgumentException(
                    "JDBC escape sequences ({fn …}, {ts …}) are not allowed in the SQL workbench");
        }
        if (scan.executableComment()) {
            throw new IllegalArgumentException("Executable conditional comments are not allowed in the SQL workbench");
        }
        if (scan.crTerminatedComment()) {
            throw new IllegalArgumentException(
                    "Line comments ending in a lone carriage return are not allowed in the SQL workbench");
        }
        if (scan.stacked()) {
            throw new IllegalArgumentException("Only a single SQL statement is allowed in the SQL workbench");
        }
        if (scan.unbalancedQuote()) {
            throw new IllegalArgumentException(
                    "Only read-only queries are allowed here; the statement could not be parsed as one");
        }

        String statement = stripTrailingSemicolon(scan.strippedText()).strip();
        SqlNode parsed = parse(statement);
        if (parsed != null) {
            if (!ALLOWED_TOP_LEVEL_KINDS.contains(parsed.getKind())) {
                throw new IllegalArgumentException(
                        "Only read-only queries (SELECT / WITH / VALUES / TABLE / DESCRIBE) are allowed here");
            }
            checkTree(parsed);
            return statement;
        }

        // Neither grammar reads it. The only allowance left is a strict lexical one: a single,
        // comment-free statement starting with a read-only introspection keyword. Anything else
        // fails closed.
        if (LEXICAL_ALLOWLIST.contains(firstWord(statement).toUpperCase(Locale.ROOT))) {
            return statement;
        }
        throw new IllegalArgumentException(
                "Only read-only queries are allowed here; the statement could not be parsed as one");
    }

    /**
     * Convenience for callers that only need the verdict (tests, future callers): validates as
     * {@link #validatedStatement(String)} and discards the returned text.
     */
    public static void checkReadOnly(String sql) {
        validatedStatement(sql);
    }

    /** Removes trailing {@code ;} and whitespace — a statement terminator, not a stack. */
    private static String stripTrailingSemicolon(String sql) {
        int end = sql.length();
        while (end > 0 && (sql.charAt(end - 1) == ';' || Character.isWhitespace(sql.charAt(end - 1)))) {
            end--;
        }
        return sql.substring(0, end);
    }

    /**
     * Walks the raw text once, tracking quotes ({@code '}, {@code "}, MySQL {@code `} — saiku#2522,
     * so a quoted identifier cannot hide a comment opener) and comments, and produces the stripped
     * text plus the constructs that would let the engine execute something a parse cannot vouch
     * for. {@code --} comments end at {@code \r}, {@code \n} or EOF — the earliest place any
     * shipped engine ends one — never later, so the stripped text contains every character an
     * engine could treat as code.
     */
    private static ScanResult scanRaw(String sql) {
        StringBuilder stripped = new StringBuilder();
        boolean jdbcEscape = false;
        boolean executableComment = false;
        boolean crTerminatedComment = false;
        boolean stacked = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean pastTerminator = false;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (inSingleQuote) {
                stripped.append(c);
                if (c == '\'') {
                    inSingleQuote = false;
                }
                i++;
                continue;
            }
            if (inDoubleQuote) {
                stripped.append(c);
                if (c == '"') {
                    inDoubleQuote = false;
                }
                i++;
                continue;
            }
            if (inBacktick) {
                stripped.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                if (i < n && sql.charAt(i) == '\r' && (i + 1 >= n || sql.charAt(i + 1) != '\n')) {
                    crTerminatedComment = true;
                }
                stripped.append(' ');
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (isExecutableConditionalComment(sql, i)) {
                    executableComment = true;
                }
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                stripped.append(' ');
                continue;
            }
            stripped.append(c);
            if (c == '\'') {
                inSingleQuote = true;
                if (pastTerminator) {
                    stacked = true;
                }
            } else if (c == '"') {
                inDoubleQuote = true;
                if (pastTerminator) {
                    stacked = true;
                }
            } else if (c == '`') {
                inBacktick = true;
                if (pastTerminator) {
                    stacked = true;
                }
            } else if (c == ';') {
                pastTerminator = true;
            } else if (c == '{') {
                jdbcEscape = true;
            } else if (pastTerminator && !Character.isWhitespace(c)) {
                stacked = true;
            }
            i++;
        }
        boolean unbalancedQuote = inSingleQuote || inDoubleQuote || inBacktick;
        return new ScanResult(
                stripped.toString(), jdbcEscape, executableComment, crTerminatedComment, stacked, unbalancedQuote);
    }

    /**
     * Whether the {@code /*} at {@code pos} opens a comment MySQL/MariaDB would EXECUTE: {@code
     * /*!…}, {@code /*NNNNN…} (five digits) or MariaDB's {@code /*M!…}. Calcite skips all of these
     * as comments; the engine does not.
     */
    private static boolean isExecutableConditionalComment(String sql, int pos) {
        int j = pos + 2;
        if (j >= sql.length()) {
            return false;
        }
        if (sql.charAt(j) == '+') {
            return false; // optimizer hint: a comment everywhere
        }
        if (sql.startsWith("M!", j)) {
            return true;
        }
        return sql.charAt(j) == '!' || Character.isDigit(sql.charAt(j));
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
