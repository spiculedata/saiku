package org.saiku.service.util.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * Class to log the load of converting queries to PDF's.
 */
public class PdfPerformanceLogger{
    private static final Logger log = LoggerFactory.getLogger(PdfPerformanceLogger.class);

    long start;
    long queryToHtmlStart;
    long queryToHtmlEnd;
    long renderStart;
    long renderEnd;

    public PdfPerformanceLogger() {
        this.start = (new Date()).getTime();
    }

    public void queryToHtmlStart(){
        queryToHtmlStart = getCurrentTime();
    }

    public void setQueryToHtmlStop(){
        queryToHtmlEnd = getCurrentTime();
    }

    public void renderStart(){
        renderStart = getCurrentTime();
    }

    public void renderStop(){
        renderEnd = getCurrentTime();
    }

    public void logResults(){
        log.debug("PDF Output - JSConverter: " + (queryToHtmlEnd - queryToHtmlStart) + "ms PDF Render: " + (renderEnd - renderStart) + "ms");
    }

    public long getCurrentTime(){
        return (new Date()).getTime();
    }
}