/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.Filterable;

/**
 * Keeps the extra {@code DEBUG} records a {@link SqlCaptureProbe} provokes out of the appenders
 * that were already there (saiku#2423, the log side of saiku#2328).
 *
 * <p>The probe lowers the Mondrian SQL loggers to {@code DEBUG}, and the loggers are process-wide,
 * so without this every concurrent user's SQL would also be written to the file and console
 * appenders for as long as the probe is open. The fence puts a filter on each appender that
 * existed before the probe, which drops a record from a Mondrian SQL logger when it is more
 * verbose than the level that logger had before the probe raised it. What the configuration
 * already logged is unchanged; only what the probe added is withheld. The probe's own appenders
 * are never fenced, they are the reason the level was raised.
 */
final class SqlLogFence {

    private final Filter filter;
    private final List<Appender> fenced;

    private SqlLogFence(Filter filter, List<Appender> fenced) {
        this.filter = filter;
        this.fenced = fenced;
    }

    /**
     * Fence every appender reachable from {@code config} or from the raised loggers' ancestry.
     *
     * @param levelsBefore the effective level of each Mondrian SQL logger before it was raised
     * @param raised the logger configs the probe raised, whose own and inherited appenders to fence
     */
    static SqlLogFence install(Configuration config, Map<String, Level> levelsBefore, Collection<LoggerConfig> raised) {
        Filter filter = new BelowPreviousLevelFilter(levelsBefore);
        filter.start();
        List<Appender> targets = new ArrayList<>();
        collect(targets, config.getAppenders().values());
        for (LoggerConfig logger : raised) {
            for (LoggerConfig at = logger; at != null; at = at.getParent()) {
                collect(targets, at.getAppenders().values());
            }
        }
        List<Appender> fenced = new ArrayList<>();
        try {
            for (Appender appender : targets) {
                if (appender instanceof Filterable filterable) {
                    filterable.addFilter(filter);
                    fenced.add(appender);
                }
            }
        } catch (RuntimeException | Error t) {
            new SqlLogFence(filter, fenced).remove();
            throw t;
        }
        return new SqlLogFence(filter, fenced);
    }

    /** Take the filter off every appender it was put on; each is attempted on its own. */
    void remove() {
        RuntimeException first = null;
        for (Appender appender : fenced) {
            try {
                ((Filterable) appender).removeFilter(filter);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        fenced.clear();
        if (first != null) {
            throw first;
        }
    }

    private static void collect(List<Appender> into, Collection<Appender> appenders) {
        for (Appender appender : appenders) {
            if (!appender.getName().startsWith(SqlCaptureProbe.APPENDER_PREFIX) && !into.contains(appender)) {
                into.add(appender);
            }
        }
    }

    /** Denies a record from a Mondrian SQL logger that is below the level it had before the probe. */
    private static final class BelowPreviousLevelFilter extends AbstractFilter {
        private final Map<String, Level> levelsBefore;

        BelowPreviousLevelFilter(Map<String, Level> levelsBefore) {
            super(Result.NEUTRAL, Result.NEUTRAL);
            this.levelsBefore = new LinkedHashMap<>(levelsBefore);
        }

        @Override
        public Result filter(LogEvent event) {
            Level before = levelBefore(event.getLoggerName());
            if (before != null && !event.getLevel().isMoreSpecificThan(before)) {
                return Result.DENY;
            }
            return Result.NEUTRAL;
        }

        private Level levelBefore(String loggerName) {
            if (loggerName == null) {
                return null;
            }
            Level best = null;
            int bestLength = -1;
            for (Map.Entry<String, Level> entry : levelsBefore.entrySet()) {
                String name = entry.getKey();
                boolean matches = loggerName.equals(name) || loggerName.startsWith(name + ".");
                if (matches && name.length() > bestLength) {
                    best = entry.getValue();
                    bestLength = name.length();
                }
            }
            return best;
        }
    }
}
