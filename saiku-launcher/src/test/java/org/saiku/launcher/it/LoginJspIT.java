/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher.it;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpResponse;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * saiku#1158 — the legacy {@code login.jsp} (stale demo creds + an unescaped
 * {@code AuthenticationException.getMessage()} XSS sink) has been removed; the
 * SPA owns login via {@code /ui/}. Lock the deletion so a future change that
 * re-adds the JSP is an explicit, visible decision.
 */
public class LoginJspIT {

    private static SaikuItHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = SaikuItHarness.shared();
    }

    @Test
    public void loginJspIsNotServed() throws Exception {
        HttpResponse<String> resp = harness.getAuth("/login.jsp");
        int status = resp.statusCode();
        // The file is removed from the WAR, and the shipped security config also
        // denies direct *.jsp access — so a request is either 404 (gone) or 403
        // (denied). Both are safe; the outcome that must NEVER happen is a 200
        // rendering the legacy login page (stale demo creds + the unescaped
        // AuthenticationException sink). Asserting "not 200" is the real
        // regression guard: it fires if anyone re-adds the JSP AND opens up
        // *.jsp access.
        assertTrue("login.jsp must not be served — got " + status, status == 404 || status == 403);
        String body = resp.body() == null ? "" : resp.body();
        assertFalse("stale marissa/koala creds must not be served", body.contains("koala"));
        assertFalse("stale paul/emu creds must not be served", body.contains("emu"));
    }
}
