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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schedule.digest.ChannelDigestContent.MeasureLine;

/** Slack Block Kit + Teams MessageCard payload shape for a WEBHOOK_DIGEST message (saiku#1099). */
public class ChannelDigestContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<MeasureLine> lines() {
        return List.of(new MeasureLine("Total Units", "1,234"), new MeasureLine("Store Sales", "56,789.5"));
    }

    @Test
    public void slackPayloadIsValidJsonWithHeaderSectionAndButton() throws Exception {
        var payload = ChannelDigestContent.slackPayload(
                "Executive Overview", lines(), "https://analytics.example.com/ui/dashboards/shared/exec.saikudash");
        JsonNode tree = MAPPER.valueToTree(payload);

        assertEquals("Executive Overview", tree.get("text").asText());
        JsonNode blocks = tree.get("blocks");
        assertTrue(blocks.isArray());

        JsonNode header = blocks.get(0);
        assertEquals("header", header.get("type").asText());
        assertEquals("Executive Overview", header.get("text").get("text").asText());

        JsonNode section = blocks.get(1);
        assertEquals("section", section.get("type").asText());
        String summary = section.get("text").get("text").asText();
        assertTrue(summary.contains("Total Units"));
        assertTrue(summary.contains("1,234"));
        assertTrue(summary.contains("Store Sales"));

        JsonNode actions = blocks.get(2);
        assertEquals("actions", actions.get("type").asText());
        JsonNode button = actions.get("elements").get(0);
        assertEquals("button", button.get("type").asText());
        assertEquals(
                "https://analytics.example.com/ui/dashboards/shared/exec.saikudash",
                button.get("url").asText());
    }

    @Test
    public void slackPayloadOmitsButtonAndNotesUnavailableLinkWhenUrlMissing() throws Exception {
        var payload = ChannelDigestContent.slackPayload("Executive Overview", lines(), null);
        JsonNode tree = MAPPER.valueToTree(payload);
        JsonNode blocks = tree.get("blocks");

        for (JsonNode block : blocks) {
            assertFalse(
                    "no actions block when the link is unavailable",
                    "actions".equals(block.get("type").asText()));
        }
        JsonNode last = blocks.get(blocks.size() - 1);
        assertEquals("context", last.get("type").asText());
        assertTrue(last.get("elements").get(0).get("text").asText().contains("unavailable"));
    }

    @Test
    public void slackPayloadDefaultsHeadingWhenTitleMissing() throws Exception {
        var payload = ChannelDigestContent.slackPayload(null, lines(), null);
        JsonNode tree = MAPPER.valueToTree(payload);
        assertEquals("Dashboard digest", tree.get("text").asText());
    }

    @Test
    public void slackMrkdwnEscapesAngleBracketsAndAmpersand() {
        var payload =
                ChannelDigestContent.slackPayload("Title", List.of(new MeasureLine("<script>&\"", "5 > 3")), null);
        JsonNode tree = MAPPER.valueToTree(payload);
        String summary = tree.get("blocks").get(1).get("text").get("text").asText();
        assertFalse(summary.contains("<script>"));
        assertTrue(summary.contains("&lt;script&gt;"));
        assertTrue(summary.contains("&amp;"));
    }

    @Test
    public void teamsPayloadIsValidMessageCardWithAction() throws Exception {
        var payload = ChannelDigestContent.teamsPayload(
                "Executive Overview", lines(), "https://analytics.example.com/ui/dashboards/shared/exec.saikudash");
        JsonNode tree = MAPPER.valueToTree(payload);

        assertEquals("MessageCard", tree.get("@type").asText());
        assertEquals("http://schema.org/extensions", tree.get("@context").asText());
        assertEquals("Executive Overview", tree.get("title").asText());
        String text = tree.get("text").asText();
        assertTrue(text.contains("Total Units"));
        assertTrue(text.contains("1,234"));

        JsonNode action = tree.get("potentialAction").get(0);
        assertEquals("OpenUri", action.get("@type").asText());
        assertEquals(
                "https://analytics.example.com/ui/dashboards/shared/exec.saikudash",
                action.get("targets").get(0).get("uri").asText());
    }

    @Test
    public void teamsPayloadOmitsActionWhenUrlMissing() throws Exception {
        var payload = ChannelDigestContent.teamsPayload("Executive Overview", lines(), null);
        JsonNode tree = MAPPER.valueToTree(payload);
        assertFalse(tree.has("potentialAction"));
        assertTrue(tree.get("text").asText().contains("unavailable"));
    }
}
