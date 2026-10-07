/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

import java.util.Locale;
import java.util.Set;

/**
 * Lexical read-only guard for the SQL workbench (saiku#1107 phase 1). Rejects anything that
 * isn't a single read-only statement before it ever reaches a JDBC driver.
 *
 * <p>This is an allowlist on the statement's leading keyword, not a SQL parser — it is one layer
 * of a multi-layer defense. {@link SqlWorkbenchService} additionally opens the connection with
 * {@code setReadOnly(true)} and runs the statement through {@code executeQuery()} (which most JDBC
 * drivers already refuse for a non-{@code SELECT}), and the datasource itself can only be reached
 * by a user holding {@code ROLE_SQL_EXEC}/{@code ROLE_ADMIN}. Per-datasource read/write toggling is
 * phase 3 of the issue and out of scope here — every datasource is read-only for now.
 */
public final class ReadOnlySqlGuard {

    /** Leading keywords that keep a statement read-only across the mainstream SQL dialects. */
    private static final Set<String> ALLOWED_LEADING_KEYWORDS =
            Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC");

    private ReadOnlySqlGuard() {}

    /**
     * @throws IllegalArgumentException when {@code sql} is blank, carries more than one
     *     statement, or does not start with a read-only keyword. The message is safe to return to
     *     the caller — it never echoes the offending SQL.
     */
    public static void checkReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL statement is empty");
        }
        String singleStatement = singleTopLevelStatement(sql);
        String firstWord = firstWord(singleStatement).toUpperCase(Locale.ROOT);
        if (!ALLOWED_LEADING_KEYWORDS.contains(firstWord)) {
            throw new IllegalArgumentException(
                    "Only read-only statements (SELECT / WITH / SHOW / EXPLAIN / DESCRIBE) are allowed here");
        }
    }

    /**
     * Walks the SQL once, skipping {@code --} / {@code /* *&#47;} comments and quoted text (' and
     * "), and returns the trimmed text of the first top-level statement. Throws when a second,
     * non-blank top-level statement follows a {@code ;} — stacked statements are how a read-only
     * guard on statement #1 gets bypassed by statement #2.
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
