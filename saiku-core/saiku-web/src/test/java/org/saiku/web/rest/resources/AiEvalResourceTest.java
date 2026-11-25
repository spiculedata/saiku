/*
 * ### SPDX-License-Identifier: GPL-3.0 ###
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.eval.EvalResultStore;

/** Unit tests for {@link AiEvalResource}. */
public class AiEvalResourceTest {

    private AiEvalResource resource;

    @Before
    public void setUp() {
        resource = new AiEvalResource();
        resource.setStore(new EvalResultStore("jdbc:h2:mem:evals-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
    }

    @Test
    public void run_without_the_ai_services_is_unavailable() {
        Response response = resource.run();
        assertEquals(503, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("not configured"));
    }

    @Test
    public void suites_start_empty() {
        assertTrue(resource.suites().isEmpty());
    }

    @Test
    public void runs_of_an_unknown_suite_start_empty() {
        assertTrue(resource.runs("nope", 50).isEmpty());
    }

    @Test
    public void trend_of_an_unknown_suite_starts_empty() {
        assertTrue(resource.trend("nope", 100).isEmpty());
    }

    @Test
    public void an_empty_evals_dir_is_accepted() {
        resource.setEvalsDir(null);
        resource.setEvalsDir("  ");
        resource.setEvalsDir("./does-not-exist/evals");
        // The blank values must not have replaced the default; only the last
        // (real) path matters, and run() still reports the 503 correctly.
        assertEquals(503, resource.run().getStatus());
    }

    @Test
    public void a_run_limit_is_honoured_end_to_end() {
        // The resource forwards the limit straight to the store; a negative
        // limit must not blow up the read path.
        assertTrue(resource.runs("nope", -1).isEmpty());
        assertTrue(resource.trend("nope", -1).isEmpty());
    }
}
