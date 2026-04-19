package org.saiku.service.util.export;

import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to log the load of converting queries to PDF's.
 */
public class PdfPerformanceLogger {
    private static final Logger log = LoggerFactory.getLogger(PdfPerformanceLogger.class);

    private final long start;
    private long queryToHtmlStart;
    private long queryToHtmlEnd;
    private long renderStart;
    private long renderEnd;

    public PdfPerformanceLogger() {
        this.start = (new Date()).getTime();
    }

    public void queryToHtmlStart() {
        queryToHtmlStart = getCurrentTime();
    }

    public void setQueryToHtmlStop() {
        queryToHtmlEnd = getCurrentTime();
    }

    public void renderStart() {
        renderStart = getCurrentTime();
    }

    public void renderStop() {
        renderEnd = getCurrentTime();
    }

    public void logResults() {
        log.debug("PDF Output - JSConverter: " + (queryToHtmlEnd - queryToHtmlStart) + "ms PDF Render: "
                + (renderEnd - renderStart) + "ms");
    }

    private long getCurrentTime() {
        return (new Date()).getTime();
    }
}
