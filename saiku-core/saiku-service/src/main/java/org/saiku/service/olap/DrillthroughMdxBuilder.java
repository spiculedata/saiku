/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import mondrian.olap4j.SaikuMondrianHelper;
import org.apache.commons.lang3.StringUtils;
import org.olap4j.OlapConnection;
import org.saiku.olap.util.QueryGuardrails;
import org.saiku.service.util.exception.SaikuQueryRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build a {@code DRILLTHROUGH ...} MDX string from a base SELECT + the caller's row caps.
 * Extracted from {@link ThinQueryService#drillthrough} so the cap-emission policy is unit-testable
 * and so a single seam can route Mondrian back to {@code MAXROWS} when the caller asked for
 * {@code FIRST_ROWSET}.
 *
 * <p>saiku#1914: the caller-supplied {@code maxrows} is clamped to the server-side ceiling
 * ({@link QueryGuardrails#clampMaxRows}) BEFORE any of the branches run, and a
 * {@code maxrows <= 0} / omitted cap is replaced by that ceiling rather than emitting a bare
 * {@code DRILLTHROUGH}. A client can therefore neither inflate {@code maxrows} nor drop it
 * to get an unbounded fact-table scan.
 *
 * <p>Mondrian's MDX grammar does not define the {@code FIRST_ROWSET} token. Saiku had been emitting
 * it whenever {@code firstRowset > 0}, and the parser rejected the query with a syntax error —
 * seen live as a 500 with {@code "Error DRILLTHROUGH: ..."}. The builder transparently falls back
 * to {@code MAXROWS} on Mondrian connections; non-Mondrian backends (XMLA, MSAS) keep
 * {@code FIRST_ROWSET} where supported.
 */
public final class DrillthroughMdxBuilder {

    private static final Logger log = LoggerFactory.getLogger(DrillthroughMdxBuilder.class);

    private DrillthroughMdxBuilder() {}

    /**
     * Compose the drillthrough MDX, always carrying a server-clamped row cap. Precedence:
     * <ol>
     *   <li>{@code firstRowset > 0} on a non-Mondrian backend → {@code DRILLTHROUGH FIRST_ROWSET N ...}
     *       with {@code N} clamped to the ceiling</li>
     *   <li>{@code firstRowset > 0} on Mondrian → fall back to {@code DRILLTHROUGH MAXROWS N ...}
     *       (using the smaller of the ceiling and {@code firstRowset}).</li>
     *   <li>Otherwise → {@code DRILLTHROUGH MAXROWS N ...} with the clamped cap.</li>
     * </ol>
     * There is no longer a bare-{@code DRILLTHROUGH} branch (saiku#1914). Optional non-blank
     * {@code returns} is appended as {@code "\r\n RETURN " + returns}.
     */
    public static String build(
            String baseSelect, int maxrows, Integer firstRowset, String returns, boolean isMondrian) {
        // saiku#1914: server-side ceiling, applied before any branch. maxrows <= 0 (client
        // asked for "everything") becomes the ceiling, so the bare-DRILLTHROUGH branch
        // below is only reachable with an explicit, already-clamped cap.
        final int cap = QueryGuardrails.clampMaxRows(maxrows);
        if (maxrows <= 0) {
            log.debug("Drillthrough maxrows={} replaced by server ceiling {}", maxrows, cap);
        } else if (maxrows > cap) {
            log.warn("Drillthrough maxrows={} exceeds server ceiling {}; clamping", maxrows, cap);
        }
        String mdx;
        if (firstRowset != null && firstRowset > 0) {
            if (isMondrian) {
                int mondrianCap = Math.min(cap, firstRowset);
                log.debug(
                        "Mondrian backend doesn't support FIRST_ROWSET; falling back to MAXROWS {} for drillthrough.",
                        mondrianCap);
                mdx = "DRILLTHROUGH MAXROWS " + mondrianCap + " " + baseSelect;
            } else {
                mdx = "DRILLTHROUGH FIRST_ROWSET " + Math.min(firstRowset, cap) + " " + baseSelect;
            }
        } else {
            mdx = "DRILLTHROUGH MAXROWS " + cap + " " + baseSelect;
        }
        if (StringUtils.isNotBlank(returns)) {
            mdx += "\r\n RETURN " + returns;
        }
        return mdx;
    }

    /**
     * Clamp a client-supplied, already-assembled {@code DRILLTHROUGH} MDX string (saiku#1914),
     * then verify the emitted statement with Mondrian's real parser (saiku#2420).
     *
     * <p>This is the overload the raw-drillthrough call sites must use: it composes the capped
     * statement exactly as {@link #capRawDrillthrough(String, int)} does, and then — when
     * {@code con} is a Mondrian connection — re-parses the emitted statement with Mondrian's
     * own parser and requires the parse to yield a {@code DRILLTHROUGH} whose
     * {@code MAXROWS} bound sits in {@code [1, maxRows()]}. Mondrian treats a missing or
     * non-positive bound as UNLIMITED, so "the clamp wrote MAXROWS" is not enough: the number
     * after it must be a positive integer within the ceiling. Any verification failure —
     * including the clamp and Mondrian's lexer disagreeing about comments, which is exactly the
     * saiku#2420 fail-open — becomes a {@link SaikuQueryRejectedException}, i.e. a typed 4xx,
     * never an execution.
     *
     * <p>Non-Mondrian connections skip the parser check (the parse needs a
     * {@code RolapConnection}); the lexical cap above is the bound there, and the raw paths
     * only reach this seam for Mondrian connections in practice (the drillthrough detection
     * gate is Mondrian-only).
     *
     * @param con the connection the statement will execute on (may be null; verification is
     *     then skipped)
     * @param mdx the client's statement
     * @param requestedMaxRows the caller's requested cap, clamped to the server ceiling
     * @return a statement that carries a verified {@code 1 <= MAXROWS <= maxRows()} bound — or,
     *     for a statement that provably is not a drillthrough, the input unchanged
     * @throws SaikuQueryRejectedException when the statement looks like a drillthrough but no
     *     provably-bounded form can be produced for it
     */
    public static String capRawDrillthrough(OlapConnection con, String mdx, int requestedMaxRows) {
        final String capped = capRawDrillthrough(mdx, requestedMaxRows);
        if (capped != null && capped != mdx) {
            verifyCapWithParser(con, capped);
        }
        return capped;
    }

    /**
     * Clamp a client-supplied, already-assembled {@code DRILLTHROUGH} MDX string (saiku#1914,
     * hardened by saiku#2420).
     *
     * <p>Used by the paths that execute raw MDX from the request body rather than composing
     * the statement server-side. Whatever the client wrote — nothing, {@code MAXROWS 5},
     * {@code MAXROWS 999999999}, {@code FIRST_ROWSET 4} — the emitted statement carries a
     * row cap no larger than {@link QueryGuardrails#maxRows()}.
     *
     * <p><strong>Fail closed (saiku#2420).</strong> This method used to return unparseable
     * statements unchanged or paper over them with a second {@code MAXROWS} (a guaranteed
     * Mondrian parse error, i.e. a 500). Both were wrong: returning a drillthrough uncapped is
     * fail-open, and Mondrian treats a missing or non-positive bound as UNLIMITED. Now the
     * only inputs returned unchanged are statements that provably are not drillthroughs under
     * any lexer agreement; everything the clamp cannot turn into a bounded statement raises a
     * {@link SaikuQueryRejectedException}:
     *
     * <ul>
     *   <li>an unterminated block comment anywhere in the leading trivia — where Mondrian's
     *       lexer ends the comment is not something string analysis can establish, and the two
     *       disagreeing is exactly the saiku#2420 fail-open;</li>
     *   <li>MDX comments present but the {@code DRILLTHROUGH} keyword not locatable after
     *       them — the clamp's comment model may disagree with Mondrian's lexer, so the
     *       statement is refused rather than passed through;</li>
     *   <li>a {@code MAXROWS}/{@code FIRST_ROWSET} clause whose bound is not an immediately
     *       adjacent positive integer literal ({@code 0}, negative, garbage, a comment where
     *       the number should be) — Mondrian reads a missing or non-positive bound as
     *       UNLIMITED, and prepending a second {@code MAXROWS} only produces a parse error;</li>
     *   <li>the keyword with no statement following it.</li>
     * </ul>
     *
     * <p>Only the leading {@code DRILLTHROUGH [MAXROWS|FIRST_ROWSET] <n>} prefix is inspected;
     * the rest of the statement is passed through byte-for-byte so a
     * {@code RETURN}/{@code SET}/{@code WHERE} tail can't be mangled.
     *
     * @param mdx the client's statement; if it doesn't start with {@code DRILLTHROUGH}
     *     (case-insensitive, and only when the leading trivia is comment-free) it is returned
     *     unchanged — this method must not invent a drillthrough from something that isn't one
     * @throws SaikuQueryRejectedException when the statement cannot be proven bounded
     */
    public static String capRawDrillthrough(String mdx, int requestedMaxRows) {
        if (mdx == null) {
            return null;
        }
        // saiku#2327: Mondrian's parser skips MDX comments, so "/*x*/DRILLTHROUGH ..." is executed
        // as a drillthrough. Skip them here too, otherwise the statement is treated as "not a
        // drillthrough" and runs uncapped. The comments are dropped from the emitted statement.
        // saiku#2420: the scan reports whether it saw comments or hit an unterminated one, so
        // "keyword not found" can tell "plain SELECT" (safe to pass through) apart from "the
        // clamp's comment model disagrees with Mondrian's lexer" (fail closed).
        final TriviaScan start = scanTrivia(mdx, 0);
        if (start.unterminated) {
            throw reject(mdx, "an unterminated MDX comment makes the statement boundary ambiguous");
        }
        if (!mdx.regionMatches(true, start.index, "DRILLTHROUGH", 0, "DRILLTHROUGH".length())) {
            if (start.sawComment) {
                throw reject(mdx, "comments precede the statement but the DRILLTHROUGH keyword cannot be located");
            }
            return mdx;
        }
        final int cap = QueryGuardrails.clampMaxRows(requestedMaxRows);
        final TriviaScan afterKeyword = scanTrivia(mdx, start.index + "DRILLTHROUGH".length());
        if (afterKeyword.unterminated) {
            throw reject(mdx, "an unterminated MDX comment follows the DRILLTHROUGH keyword");
        }
        if (afterKeyword.index >= mdx.length()) {
            throw reject(mdx, "the statement ends at the DRILLTHROUGH keyword");
        }

        // Word after the keyword: a cap clause ("DRILLTHROUGH MAXROWS 500 SELECT ...") or the
        // start of the statement body itself ("DRILLTHROUGH SELECT ...", capped by prepending).
        final int wordEnd = endOfToken(mdx, afterKeyword.index);
        final String word = mdx.substring(afterKeyword.index, wordEnd);
        final boolean hasCapClause = "MAXROWS".equalsIgnoreCase(word) || "FIRST_ROWSET".equalsIgnoreCase(word);
        if (!hasCapClause) {
            // No cap clause: prepend the server ceiling. Leading trivia is dropped (Mondrian
            // skips it anyway); the body itself is preserved verbatim.
            return "DRILLTHROUGH MAXROWS " + cap + " " + mdx.substring(afterKeyword.index);
        }

        // The bound must be an immediately adjacent unsigned integer literal. Mondrian's
        // grammar reads MAXROWS <unsigned int>, and anything else in that position is either a
        // parse error (500 today) or — for 0/absent — an UNLIMITED scan. Refuse rather than
        // guess; a comment between the clause and the number cannot be told apart from a
        // missing number without Mondrian's lexer, so it is refused too.
        final TriviaScan bound = scanTrivia(mdx, wordEnd);
        if (bound.unterminated) {
            throw reject(mdx, "an unterminated MDX comment precedes the cap clause's number");
        }
        final int digitsEnd = endOfDigits(mdx, bound.index);
        if (digitsEnd == bound.index) {
            throw reject(mdx, "the cap clause carries no numeric bound where one is required");
        }
        if (digitsEnd < mdx.length()
                && !Character.isWhitespace(mdx.charAt(digitsEnd))
                && !mdx.startsWith("/*", digitsEnd)
                && !mdx.startsWith("//", digitsEnd)
                && !mdx.startsWith("--", digitsEnd)) {
            throw reject(mdx, "the cap clause's bound is not a standalone integer literal");
        }
        final int clientCap;
        try {
            clientCap = Integer.parseInt(mdx.substring(bound.index, digitsEnd));
        } catch (NumberFormatException e) {
            throw reject(mdx, "the cap clause's bound overflows the integer range");
        }
        if (clientCap <= 0) {
            // Mondrian: "Maximum number of rows to retrieve, <= 0 if unlimited". A non-positive
            // bound is an unbounded fact-table scan, not a cap.
            throw reject(mdx, "a non-positive cap clause bound means UNLIMITED to Mondrian");
        }
        final int effective = Math.min(clientCap, cap);
        if (effective != clientCap) {
            log.warn("Client-supplied {} {} exceeds server ceiling {}; clamping drillthrough", word, clientCap, cap);
        }
        // FIRST_ROWSET is a rowset bound Mondrian does not parse (see the class doc on build());
        // it is collapsed to MAXROWS here for every backend.
        return "DRILLTHROUGH MAXROWS " + effective + mdx.substring(digitsEnd);
    }

    /**
     * Re-parse {@code capped} with Mondrian's own parser and require a bounded drillthrough
     * (saiku#2420 — suggested fix (b)). This is the definitive check: it uses the same lexer
     * and grammar that will execute the statement, so any disagreement between the clamp's
     * string model and Mondrian's is caught here instead of at execution time. A missing or
     * non-positive {@code MAXROWS} means UNLIMITED to Mondrian, so the parsed bound must lie
     * in {@code [1, maxRows()]}.
     *
     * @throws SaikuQueryRejectedException when the statement does not parse as a drillthrough
     *     with a bound inside the server ceiling
     */
    private static void verifyCapWithParser(OlapConnection con, String capped) {
        if (con == null || !SaikuMondrianHelper.isMondrianConnection(con)) {
            return;
        }
        final Integer parsed = SaikuMondrianHelper.drillThroughMaxRowCount(con, capped);
        if (parsed == null) {
            throw reject(capped, "the capped statement did not re-parse as a bounded DRILLTHROUGH");
        }
        final int ceiling = QueryGuardrails.maxRows();
        if (parsed < 1 || parsed > ceiling) {
            throw reject(capped, "the re-parsed row cap " + parsed + " is outside [1, " + ceiling + "]");
        }
        log.debug("Raw drillthrough cap verified by Mondrian's parser: MAXROWS={}", parsed);
    }

    /** Static rejection message (no statement content on the wire), logged with the statement. */
    private static SaikuQueryRejectedException reject(String mdx, String reason) {
        log.warn("Refusing DRILLTHROUGH without a verifiable row cap ({}): {}", reason, mdx);
        return new SaikuQueryRejectedException(
                "Drillthrough refused: the statement's row cap could not be enforced with certainty (" + reason + ")");
    }

    /** End of the identifier/keyword token starting at {@code from} (stops at trivia). */
    private static int endOfToken(String mdx, int from) {
        int i = from;
        while (i < mdx.length()
                && !Character.isWhitespace(mdx.charAt(i))
                && !mdx.startsWith("/*", i)
                && !mdx.startsWith("//", i)
                && !mdx.startsWith("--", i)) {
            i++;
        }
        return i;
    }

    /** End of the unsigned integer literal starting at {@code from} (possibly empty). */
    private static int endOfDigits(String mdx, int from) {
        int i = from;
        while (i < mdx.length() && mdx.charAt(i) >= '0' && mdx.charAt(i) <= '9') {
            i++;
        }
        return i;
    }

    /**
     * Index of the first character at or after {@code from} that is neither whitespace nor part
     * of an MDX comment (block comments, or {@code //} and {@code --} to end of line), with the
     * detail saiku#2420's fail-closed decision needs.
     *
     * @see #scanTrivia(String, int)
     */
    private static int skipLeadingTrivia(String mdx, int from) {
        return scanTrivia(mdx, from).index;
    }

    /** Result of {@link #scanTrivia(String, int)}. */
    private static final class TriviaScan {

        /** Index of the first character that is neither whitespace nor comment. */
        final int index;
        /** Whether any MDX comment was consumed. */
        final boolean sawComment;
        /** Whether a block comment ran to end-of-input without its {@code *}. */
        final boolean unterminated;

        TriviaScan(int index, boolean sawComment, boolean unterminated) {
            this.index = index;
            this.sawComment = sawComment;
            this.unterminated = unterminated;
        }
    }

    /**
     * Scan past whitespace and MDX comments. An unterminated block comment is reported via
     * {@link TriviaScan#unterminated} rather than silently consuming the rest of the string —
     * whether Mondrian's lexer ends such a comment at end-of-input is not something string
     * analysis can establish, and the two models disagreeing is the saiku#2420 fail-open.
     */
    private static TriviaScan scanTrivia(String mdx, int from) {
        int i = from;
        boolean sawComment = false;
        final int n = mdx.length();
        while (i < n) {
            if (Character.isWhitespace(mdx.charAt(i))) {
                i++;
            } else if (mdx.startsWith("/*", i)) {
                sawComment = true;
                int end = mdx.indexOf("*/", i + 2);
                if (end < 0) {
                    return new TriviaScan(n, true, true);
                }
                i = end + 2;
            } else if (mdx.startsWith("//", i) || mdx.startsWith("--", i)) {
                sawComment = true;
                int end = indexOfLineEnd(mdx, i + 2);
                i = end < 0 ? n : end;
            } else {
                break;
            }
        }
        return new TriviaScan(i, sawComment, false);
    }

    private static int indexOfLineEnd(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }
}
