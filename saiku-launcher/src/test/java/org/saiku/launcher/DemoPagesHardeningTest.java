/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * saiku#1908 — guards the three public demo pages that ship in the WAR
 * ({@code /embed-demo/}, {@code /aqvira-demo/}, {@code /ui/showcase/}):
 *
 * <ul>
 *   <li>the default admin/admin login is gated on the server reporting demo mode
 *       ({@code /rest/saiku/info/capabilities} → {@code demoMode:true}), never fired on
 *       plain page load;</li>
 *   <li>live member captions are HTML-escaped before they reach {@code innerHTML};</li>
 *   <li>each page carries a Content-Security-Policy whose {@code script-src} hash
 *       matches its single inline script — so an injected event-handler attribute
 *       cannot execute even if a caption slips past escaping.</li>
 * </ul>
 *
 * <p>If you edit a page's inline script, the CSP hash goes stale and the browser
 * blocks the whole script. The assertion message prints the hash to paste in.
 */
public class DemoPagesHardeningTest {

    private static final Path REPO = Paths.get("").toAbsolutePath().getParent();

    private static final String[] PAGES = {
        "saiku-webapp/src/main/webapp/embed-demo/index.html",
        "saiku-webapp/src/main/webapp/aqvira-demo/index.html",
        "saiku-ui/static/showcase/index.html",
    };

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern CSP_META =
            Pattern.compile("<meta http-equiv=\"Content-Security-Policy\" content=\"([^\"]*)\">");

    private static String read(String rel) throws IOException {
        return Files.readString(REPO.resolve(rel), StandardCharsets.UTF_8);
    }

    private static String sha256(String s) throws NoSuchAlgorithmException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'";
    }

    @Test
    public void everyPageHasCspPinningItsInlineScript() throws Exception {
        for (String page : PAGES) {
            String html = read(page);
            List<String> scripts = new ArrayList<>();
            Matcher m = INLINE_SCRIPT.matcher(html);
            while (m.find()) {
                scripts.add(m.group(1));
            }
            assertEquals(page + ": expected exactly one inline <script>", 1, scripts.size());

            Matcher csp = CSP_META.matcher(html);
            assertTrue(page + ": missing Content-Security-Policy <meta>", csp.find());
            String policy = csp.group(1);
            String hash = sha256(scripts.get(0));
            assertTrue(
                    page + ": CSP script-src hash is stale — inline script now hashes to " + hash,
                    policy.contains("script-src") && policy.contains(hash));
            assertFalse(
                    page + ": script-src must not allow 'unsafe-inline'",
                    scriptSrc(policy).contains("unsafe-inline"));
            assertTrue(page + ": object-src 'none' expected", policy.contains("object-src 'none'"));
            assertTrue(page + ": base-uri 'none' expected", policy.contains("base-uri 'none'"));
        }
    }

    @Test
    public void defaultCredentialLoginIsGatedOnDemoMode() throws Exception {
        for (String page : PAGES) {
            String html = read(page);
            int login = html.indexOf("/rest/saiku/session/");
            assertTrue(page + ": expected the demo session bootstrap", login >= 0);
            assertEquals(page + ": exactly one session POST expected", login, html.lastIndexOf("/rest/saiku/session/"));
            int probe = html.indexOf("/rest/saiku/info/capabilities");
            int gate = html.indexOf("demoMode !== true") >= 0
                    ? html.indexOf("demoMode !== true")
                    : html.indexOf("demoMode!==true");
            assertTrue(page + ": login must be preceded by a capabilities probe", probe >= 0 && probe < login);
            assertTrue(page + ": login must be gated on demoMode === true", gate > probe && gate < login);
        }
    }

    @Test
    public void liveCaptionsAreEscapedBeforeInnerHtml() throws Exception {
        // The raw, unescaped interpolations the issue called out. Each must be gone.
        String[][] forbidden = {
            {PAGES[0], "${d.name}"},
            {PAGES[1], "${t.name}"},
            {PAGES[1], "${d.name}"},
            {PAGES[1], "${p.name}"},
        };
        for (String[] f : forbidden) {
            assertFalse(
                    f[0] + ": unescaped " + f[1] + " in an HTML template",
                    read(f[0]).contains(f[1]));
        }
    }

    private static String scriptSrc(String policy) {
        for (String directive : policy.split(";")) {
            if (directive.trim().startsWith("script-src")) {
                return directive;
            }
        }
        return "";
    }
}
