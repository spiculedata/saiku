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
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.send.MailLinkBuilder;
import org.saiku.service.mail.send.MailSendPolicy;
import org.saiku.service.mail.send.MultiRecipientMailService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.JobRunner;
import org.saiku.service.schedule.JobSchedule;
import org.saiku.service.schedule.JobScheduler;
import org.saiku.service.schedule.JobStatus;
import org.saiku.service.schedule.JobStore;
import org.saiku.service.schedule.ScheduledJobFile;
import org.saiku.service.schedule.alert.MeasureValueReader;

/**
 * A {@code DASHBOARD_DIGEST} job registered on the {@link JobScheduler} routes to the {@link
 * DashboardDigestJobHandler}; a malformed payload is recorded FAILED without killing the ticker
 * (saiku#943).
 */
public class DashboardDigestDispatchTest {

    private JobScheduler scheduler;

    @After
    public void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private static final class CapturingSender implements MailSender {
        int sent = 0;

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(MailMessage m) throws MailException {
            sent++;
        }
    }

    private static final MeasureValueReader READER =
            (AiCubeRef cube, String measure, List<AiFilterSelection> f) -> 1234.0;

    private DashboardDigestJobHandler handler(CapturingSender sender) {
        MailSendPolicy policyOff = new MailSendPolicy(k -> "false", k -> null);
        // multiRecipientMailService is present but the flag is off; with no recipients the handler
        // uses the self-email fallback. selfTo configured so the fallback succeeds.
        MultiRecipientMailService multi = new MultiRecipientMailService(policyOff, null, null, null);
        MailConfig config =
                new MailConfig("smtp.example.com", 587, "u", "p", "from@example.com", true, false, "self@example.com");
        return new DashboardDigestJobHandler(
                READER, multi, sender, config, new MailLinkBuilder("https://a.example.com"));
    }

    private static Map<String, Object> payload() {
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("path", "shared/exec.saikudash");
        dash.put("title", "Executive Overview");
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("cube", "conn/cat/schema/Sales");
        m1.put("measure", "Unit Sales");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dashboard", dash);
        p.put("measures", new ArrayList<>(List.of(m1)));
        return p;
    }

    @Test(timeout = 5000)
    public void digestJobRoutesToHandlerAndDeliversToSelf() {
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, null, JobRunner.DIRECT);
        CapturingSender sender = new CapturingSender();
        scheduler.registerHandler("DASHBOARD_DIGEST", handler(sender));

        ScheduledJobFile job = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "DASHBOARD_DIGEST",
                payload(),
                true);

        ScheduledJobFile after = scheduler.runNow(job.getId());

        assertEquals("routed + delivered to self", 1, sender.sent);
        assertEquals(JobStatus.OK, after.getLastStatus());
    }

    @Test(timeout = 5000)
    public void malformedDigestRecordsFailed_tickerSurvives() {
        JobStore store = new JobStore((String) null);
        scheduler = new JobScheduler(store, null, JobRunner.DIRECT);
        CapturingSender sender = new CapturingSender();
        scheduler.registerHandler("DASHBOARD_DIGEST", handler(sender));

        ScheduledJobFile job = store.create(
                "alice",
                List.of("ROLE_USER"),
                new JobSchedule(JobSchedule.Kind.FIXED_INTERVAL, 60_000L, "UTC"),
                "DASHBOARD_DIGEST",
                new HashMap<>(), // empty → invalid spec (no measures)
                true);

        ScheduledJobFile after = scheduler.runNow(job.getId());

        assertEquals(JobStatus.FAILED, after.getLastStatus());
        assertNotNull(after.getLastError());
        assertEquals("no mail sent for a malformed job", 0, sender.sent);
        // The scheduler is still alive — it recorded the failure rather than throwing.
        assertNotNull(scheduler.handlerFor("DASHBOARD_DIGEST"));
    }
}
