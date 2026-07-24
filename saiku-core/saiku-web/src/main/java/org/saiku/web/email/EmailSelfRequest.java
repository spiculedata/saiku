package org.saiku.web.email;

/** Request body for POST /saiku/api/email/self — the browser-rendered artifacts to email. */
public class EmailSelfRequest {
    private String subject;
    private String summaryHtml;
    private String chartPngBase64;
    private String pdfBase64;

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSummaryHtml() {
        return summaryHtml;
    }

    public void setSummaryHtml(String summaryHtml) {
        this.summaryHtml = summaryHtml;
    }

    public String getChartPngBase64() {
        return chartPngBase64;
    }

    public void setChartPngBase64(String chartPngBase64) {
        this.chartPngBase64 = chartPngBase64;
    }

    public String getPdfBase64() {
        return pdfBase64;
    }

    public void setPdfBase64(String pdfBase64) {
        this.pdfBase64 = pdfBase64;
    }
}
