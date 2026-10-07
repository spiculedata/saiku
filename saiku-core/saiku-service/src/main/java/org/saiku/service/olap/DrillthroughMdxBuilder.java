/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import org.apache.commons.lang3.StringUtils;
import org.saiku.olap.util.QueryGuardrails;
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
     * Clamp a client-supplied, already-assembled {@code DRILLTHROUGH} MDX string (saiku#1914).
     *
     * <p>Used by the paths that execute raw MDX from the request body rather than composing
     * the statement server-side. Whatever the client wrote — nothing, {@code MAXROWS 5},
     * {@code MAXROWS 999999999}, {@code FIRST_ROWSET 4} — the emitted statement always carries a
     * row cap no larger than {@link QueryGuardrails#maxRows()}. An unrecognised or
     * malformed leading clause falls back to prepending {@code MAXROWS <ceiling>} rather than
     * passing the statement through uncapped.
     *
     * <p>Only the leading {@code DRILLTHROUGH [MAXROWS|FIRST_ROWSET] <n>} prefix is inspected;
     * the rest of the statement is passed through byte-for-byte so a
     * {@code RETURN}/{@code SET}/{@code WHERE} tail can't be mangled.
     *
     * @param mdx the client's statement; if it doesn't start with {@code DRILLTHROUGH}
     *     (case-insensitive, after trimming) it is returned unchanged — this method must not
     *     invent a drillthrough from something that isn't one
     */
    public static String capRawDrillthrough(String mdx, int requestedMaxRows) {
        if (mdx == null) {
            return null;
        }
        final String trimmed = mdx.trim();
        if (!trimmed.regionMatches(true, 0, "DRILLTHROUGH", 0, "DRILLTHROUGH".length())) {
            return mdx;
        }
        final int cap = QueryGuardrails.clampMaxRows(requestedMaxRows);
        final String rest = trimmed.substring("DRILLTHROUGH".length()).stripLeading();

        // DRILLTHROUGH MAXROWS 500 SELECT ...
        String[] tokens = rest.split("\\s+", 3);
        if (tokens.length >= 2
                && ("MAXROWS".equalsIgnoreCase(tokens[0]) || "FIRST_ROWSET".equalsIgnoreCase(tokens[0]))) {
            Integer clientCap = parsePositive(tokens[1]);
            if (clientCap != null) {
                int effective = Math.min(clientCap, cap);
                String tail = tokens.length == 3 ? " " + tokens[2] : "";
                if (effective != clientCap) {
                    log.warn(
                            "Client-supplied {} {} exceeds server ceiling {}; clamping drillthrough",
                            tokens[0],
                            clientCap,
                            cap);
                }
                return "DRILLTHROUGH MAXROWS " + effective + tail;
            }
            log.warn(
                    "Unparseable {} bound '{}' in client DRILLTHROUGH; applying server ceiling {}",
                    tokens[0],
                    tokens[1],
                    cap);
        }
        return "DRILLTHROUGH MAXROWS " + cap + " " + rest;
    }

    private static Integer parsePositive(String token) {
        try {
            int v = Integer.parseInt(token.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
