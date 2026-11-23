/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Minimal LookML reader — turns {@code view: orders { dimension: id { … } }} into the
 * {@link LookmlBlock} tree the converter walks.
 *
 * <p>Deliberately not a YAML front-end. LookML is brace-structured with its own quoting and
 * its own {{ liquid }} templating; the subset that carries modelling meaning (blocks,
 * {@code key: value} parameters, quoted strings, comments, bare expressions such as
 * {@code join_on: ${a.id} = ${b.id}}) is small enough to read directly, and reading it
 * ourselves means unparseable templating degrades to a warning instead of aborting the import.
 *
 * <p>Not thread-safe by design — instances are per-parse.
 */
final class LookmlParser {

    private final String src;
    private int pos;

    private LookmlParser(String src) {
        this.src = src;
    }

    /**
     * Parse one LookML file into its top-level blocks. An empty file yields an empty list —
     * callers decide whether that is a diagnostic or an error.
     */
    static List<LookmlBlock> parse(String text) {
        LookmlParser p = new LookmlParser(text == null ? "" : text);
        return p.parseDocument();
    }

    private List<LookmlBlock> parseDocument() {
        List<LookmlBlock> roots = new ArrayList<>();
        Deque<LookmlBlock> stack = new ArrayDeque<>();

        while (pos < src.length()) {
            char c = src.charAt(pos);

            if (c == '#') {
                skipToLineEnd();
                continue;
            }
            if (c == '\n' || c == '\r') {
                pos++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            if (c == '}') {
                if (!stack.isEmpty()) stack.pop();
                pos++;
                continue;
            }
            if (c == '{') {
                // A bare '{' with no key — happens in the `dimension_groups: { … }` shape
                // where the value was empty. Attach an anonymous block to the current scope.
                pos++;
                LookmlBlock anon = new LookmlBlock(null, null);
                attach(roots, stack, anon);
                stack.push(anon);
                continue;
            }

            String token = readToken();
            if (token == null || token.isEmpty()) {
                pos++; // defensive: never spin on an unparseable byte
                continue;
            }
            skipInlineSpace();

            char next = pos < src.length() ? src.charAt(pos) : '\0';
            if (next == ':') {
                pos++;
                String value = readValue();
                LookmlBlock node = new LookmlBlock(token, value);
                attach(roots, stack, node);
                // `view: orders {` — a key with a value AND a block body. Push the node so
                // its children land on it.
                skipInlineSpace();
                if (pos < src.length() && src.charAt(pos) == '{') {
                    pos++;
                    stack.push(node);
                }
            } else if (next == '{') {
                pos++;
                LookmlBlock node = new LookmlBlock(token, null);
                attach(roots, stack, node);
                stack.push(node);
            } else {
                // Bare positional value: `join_on: 1 = 1` arrives as key 'join_on' + value
                // '1 = 1', so a bare value here is a block name written without a colon.
                attach(roots, stack, new LookmlBlock(null, token));
            }
        }
        return roots;
    }

    private static void attach(List<LookmlBlock> roots, Deque<LookmlBlock> stack, LookmlBlock node) {
        if (stack.isEmpty()) roots.add(node);
        else stack.peek().add(node);
    }

    /** Read a bare token: up to the first structural character, honouring quotes. */
    private String readToken() {
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') return readQuoted();
        if (c == '{' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') return readLiquid();
        int start = pos;
        while (pos < src.length()) {
            char d = src.charAt(pos);
            if (d == '$' && isInterpolationAt(pos)) {
                skipInterpolation();
                continue;
            }
            if (d == ':' || d == '{' || d == '}' || d == '\n' || d == '\r' || d == '#') break;
            pos++;
        }
        return src.substring(start, pos).trim();
    }

    /**
     * Read the value after a {@code key:}. Returns null when there is none (a block, an empty
     * value, or end of line).
     */
    private String readValue() {
        skipInlineSpace();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        if (c == '\n' || c == '\r' || c == '#' || c == '}' || c == ':') return null;
        if (c == '{') {
            if (pos + 1 < src.length() && src.charAt(pos + 1) == '{') return readLiquid();
            return null; // block body follows, not a value
        }
        if (c == '"' || c == '\'') return readQuoted();

        int start = pos;
        while (pos < src.length()) {
            char d = src.charAt(pos);
            if (d == '\n' || d == '\r') break;
            if (d == '#') break; // comment to end of line, but not inside a quoted string
            // `${TABLE}` / `{{ … }}` are value text, not block structure: without this the
            // braces in every Looker column reference would be read as nesting and the rest
            // of the line would land in the wrong node.
            if (d == '$' && isInterpolationAt(pos)) {
                skipInterpolation();
                continue;
            }
            if (d == '{') {
                if (pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                    readLiquid();
                    continue;
                }
                break;
            }
            if (d == '}') break;
            if (d == '"' || d == '\'') {
                skipQuotedFrom(pos);
                continue;
            }
            // LookML's own statement terminator, and a second `key:` on the same line
            // (`dimension: id { primary_key: yes sql: ${TABLE}.id }` is legal hand-edited
            // LookML, and pasted/condensed files are full of it).
            if (d == ';' || startsNextKey(pos)) break;
            pos++;
        }
        String raw = src.substring(start, pos);
        return stripTrailingSemicolons(raw.trim());
    }

    /**
     * True when a bare {@code identifier:} begins at {@code at} — the boundary between two
     * parameters sharing a line. A PostgreSQL cast ({@code amount::int}) is excluded by
     * requiring a single colon, and a value that merely starts mid-identifier isn't a
     * boundary either.
     */
    private boolean startsNextKey(int at) {
        if (at > 0) {
            char prev = src.charAt(at - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_') return false;
        }
        int j = at;
        while (j < src.length()) {
            char c = src.charAt(j);
            if (Character.isLetterOrDigit(c) || c == '_') {
                j++;
            } else {
                break;
            }
        }
        if (j == at || j >= src.length()) return false;
        if (src.charAt(j) != ':') return false;
        return j + 1 >= src.length() || src.charAt(j + 1) != ':';
    }

    /** Skip a quoted run starting at {@code at}, leaving {@code pos} just past the closing quote. */
    private void skipQuotedFrom(int at) {
        char quote = src.charAt(at);
        int j = at + 1;
        while (j < src.length()) {
            char c = src.charAt(j);
            if (c == '\\' && j + 1 < src.length()) {
                j += 2;
                continue;
            }
            if (c == quote) {
                j++;
                break;
            }
            if (c == '\n') break; // unterminated — don't run past the line
            j++;
        }
        pos = j;
    }

    private boolean isInterpolationAt(int at) {
        return at + 1 < src.length() && src.charAt(at + 1) == '{';
    }

    /** Consume a {@code ${ … }} reference, braces included. */
    private void skipInterpolation() {
        pos += 2; // past ${
        while (pos < src.length() && src.charAt(pos) != '}') {
            if (src.charAt(pos) == '\n') return; // unterminated — don't run away
            pos++;
        }
        if (pos < src.length()) pos++; // past }
    }

    private String readQuoted() {
        char quote = src.charAt(pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char d = src.charAt(pos);
            if (d == '\\' && pos + 1 < src.length()) {
                sb.append(src.charAt(pos + 1));
                pos += 2;
                continue;
            }
            if (d == quote) {
                pos++;
                break;
            }
            sb.append(d);
            pos++;
        }
        return sb.toString();
    }

    /** Consume a {@code {{ … }}} liquid segment, returning it verbatim (the caller degrades it). */
    private String readLiquid() {
        int end = src.indexOf("}}", pos);
        if (end < 0) {
            String rest = src.substring(pos);
            pos = src.length();
            return rest;
        }
        String liquid = src.substring(pos, end + 2);
        pos = end + 2;
        return liquid;
    }

    private void skipInlineSpace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t') {
                pos++;
            } else if (c == '#') {
                skipToLineEnd();
            } else {
                return;
            }
        }
    }

    private void skipToLineEnd() {
        while (pos < src.length() && src.charAt(pos) != '\n') pos++;
    }

    /**
     * LookML terminates statements with {@code ;;}. The parser has no statement concept, so the
     * marker is stripped here rather than leaking into every value it produces.
     */
    private static String stripTrailingSemicolons(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ';' || value.charAt(end - 1) == ' ')) end--;
        return value.substring(0, end);
    }
}
