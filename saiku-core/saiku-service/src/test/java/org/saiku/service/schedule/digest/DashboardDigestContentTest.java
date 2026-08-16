/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.digest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.saiku.service.schedule.digest.DashboardDigestContent.MeasureLine;

/** HTML content builder: measure/value rows + deep link, with data-derived strings HTML-escaped. */
public class DashboardDigestContentTest {

    private static final String LINK = "https://analytics.example.com/ui/dashboards/shared/exec.saikudash";

    @Test
    public void bodyRendersMeasureRowsAndDeepLink() {
        String html = DashboardDigestContent.htmlBody(
                "Executive Overview",
                List.of(new MeasureLine("Total Units", "1,234"), new MeasureLine("Store Sales", "56,789.5")),
                LINK);

        // Each measure row is present (label + value).
        assertTrue(html.contains("Total Units"));
        assertTrue(html.contains("1,234"));
        assertTrue(html.contains("Store Sales"));
        assertTrue(html.contains("56,789.5"));
        // The prominent deep link to the live dashboard.
        assertTrue(html.contains("href=\"" + LINK + "\""));
        assertTrue(html.contains("Open the live dashboard"));
        // The dashboard title heads the email.
        assertTrue(html.contains("Executive Overview"));
    }

    @Test
    public void dataDerivedStringsAreHtmlEscaped_scriptNeutralised() {
        String html = DashboardDigestContent.htmlBody(
                "<script>alert('title')</script>",
                List.of(new MeasureLine("<script>alert('x')</script>", "<b>42</b>")),
                LINK);

        // No live <script> tag survives — every angle bracket from the data is escaped.
        assertFalse("raw <script> must not appear", html.contains("<script>"));
        assertFalse("raw </script> must not appear", html.contains("</script>"));
        assertFalse("raw <b> from a value must not appear", html.contains("<b>42</b>"));
        // The escaped forms are present instead.
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;b&gt;42&lt;/b&gt;"));
    }

    @Test
    public void missingLink_emitsNoAnchor_andExplains() {
        String html = DashboardDigestContent.htmlBody("Ops", List.of(new MeasureLine("Sales", "10")), null);
        assertFalse("no anchor when the deep link is unavailable", html.contains("<a href="));
        assertTrue(html.contains("link is unavailable"));
    }

    @Test
    public void subjectIncludesTitleWhenPresent() {
        assertTrue(DashboardDigestContent.subject("Q4 Revenue").contains("Q4 Revenue"));
    }

    @Test
    public void subjectFallsBackWhenTitleBlank() {
        assertTrue(DashboardDigestContent.subject(null).toLowerCase().contains("digest"));
        assertTrue(DashboardDigestContent.subject("  ").toLowerCase().contains("digest"));
    }
}
