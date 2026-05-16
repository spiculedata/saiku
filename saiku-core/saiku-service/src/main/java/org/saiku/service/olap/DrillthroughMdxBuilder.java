/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build a {@code DRILLTHROUGH ...} MDX string from a base SELECT + the caller's row caps.
 * Extracted from {@link ThinQueryService#drillthrough} so the cap-emission policy is unit-testable
 * and so a single seam can route Mondrian back to {@code MAXROWS} when the caller asked for
 * {@code FIRST_ROWSET}.
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
     * Compose the drillthrough MDX. Precedence:
     * <ol>
     *   <li>{@code firstRowset > 0} on a non-Mondrian backend → {@code DRILLTHROUGH FIRST_ROWSET N ...}</li>
     *   <li>{@code firstRowset > 0} on Mondrian → fall back to {@code DRILLTHROUGH MAXROWS N ...}
     *       (using the smaller of {@code firstRowset} and {@code maxrows} when both supplied).</li>
     *   <li>{@code maxrows > 0} → {@code DRILLTHROUGH MAXROWS N ...}</li>
     *   <li>Otherwise → bare {@code DRILLTHROUGH ...}</li>
     * </ol>
     * Optional non-blank {@code returns} is appended as {@code "\r\n RETURN " + returns}.
     */
    public static String build(
            String baseSelect, int maxrows, Integer firstRowset, String returns, boolean isMondrian) {
        String mdx;
        if (firstRowset != null && firstRowset > 0) {
            if (isMondrian) {
                int cap = (maxrows > 0) ? Math.min(maxrows, firstRowset) : firstRowset;
                log.debug(
                        "Mondrian backend doesn't support FIRST_ROWSET; falling back to MAXROWS {} for drillthrough.",
                        cap);
                mdx = "DRILLTHROUGH MAXROWS " + cap + " " + baseSelect;
            } else {
                mdx = "DRILLTHROUGH FIRST_ROWSET " + firstRowset + " " + baseSelect;
            }
        } else if (maxrows > 0) {
            mdx = "DRILLTHROUGH MAXROWS " + maxrows + " " + baseSelect;
        } else {
            mdx = "DRILLTHROUGH " + baseSelect;
        }
        if (StringUtils.isNotBlank(returns)) {
            mdx += "\r\n RETURN " + returns;
        }
        return mdx;
    }
}
