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
package org.saiku.service.mail.send;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Builds the absolute public links carried in outbound mail (saiku#1811, PR4): the per-recipient
 * one-click {@code List-Unsubscribe} link and the double-opt-in consent-confirm link.
 *
 * <p>The public base URL is read ops-only from {@code SAIKU_PUBLIC_BASE_URL} /
 * {@code saiku.public.baseUrl} (same env/prop discipline as {@link
 * org.saiku.service.mail.MailConfig}) — never from a request, so a client can never point a link at an
 * attacker host. When unset the builder yields {@code null} links; callers treat a null unsubscribe
 * link as "emit no unsubscribe header" (byte-identical to the pre-PR self-send path) and refuse to send
 * an invite that would carry a null confirm link (fail-closed — an invite with no working link is
 * useless and would look like phishing).
 *
 * <p>All query values are URL-encoded; the base URL is trailing-slash-normalised. The endpoint paths
 * match the wired resources: {@code /rest/saiku/mail/unsubscribe} and
 * {@code /rest/saiku/mail/consent/confirm}.
 */
public final class MailLinkBuilder {

    /** Ops env var for the public base URL (e.g. {@code https://analytics.example.com}). */
    public static final String ENV_KEY = "SAIKU_PUBLIC_BASE_URL";

    /** Ops system property fallback. */
    public static final String PROP_KEY = "saiku.public.baseUrl";

    private static final String UNSUBSCRIBE_PATH = "/rest/saiku/mail/unsubscribe";
    private static final String CONFIRM_PATH = "/rest/saiku/mail/consent/confirm";

    /** The SvelteKit route prefix that opens a saved dashboard by its repository path (saiku#943). */
    private static final String DASHBOARD_PATH_PREFIX = "/ui/dashboards/";

    /** The resolved base URL with any trailing slash removed, or null when unconfigured. */
    private final String baseUrl;

    /** Production ctor: resolve the base URL from env + system properties. */
    public MailLinkBuilder() {
        this(System::getenv, System::getProperty);
    }

    /** Injectable seam for tests. */
    public MailLinkBuilder(Function<String, String> env, Function<String, String> prop) {
        this.baseUrl = normaliseBase(resolve(env, prop));
    }

    /** Explicit-base ctor (tests / callers that already hold a base URL). */
    public MailLinkBuilder(String baseUrl) {
        this.baseUrl = normaliseBase(baseUrl);
    }

    /** True when a public base URL is configured — links can be built. */
    public boolean isConfigured() {
        return baseUrl != null;
    }

    /**
     * The absolute one-click {@code List-Unsubscribe} link for {@code address} carrying its HMAC
     * {@code token}, or {@code null} when the base URL / address / token is missing. The returned value
     * is the bare URL (no angle brackets); {@link #unsubscribeHeader} wraps it for the header.
     */
    public String unsubscribeUrl(String address, String token) {
        if (baseUrl == null || isBlank(address) || isBlank(token)) {
            return null;
        }
        return baseUrl + UNSUBSCRIBE_PATH + "?address=" + enc(address) + "&token=" + enc(token);
    }

    /**
     * The RFC 2369 {@code List-Unsubscribe} header VALUE ({@code "<url>"}) for {@code address}, or
     * {@code null} when no link can be built (then no unsubscribe header is emitted).
     */
    public String unsubscribeHeader(String address, String token) {
        String url = unsubscribeUrl(address, token);
        return url == null ? null : "<" + url + ">";
    }

    /**
     * The absolute consent-confirm link for {@code address} carrying the raw consent {@code token}, or
     * {@code null} when the base URL / address / token is missing.
     */
    public String confirmUrl(String address, String token) {
        if (baseUrl == null || isBlank(address) || isBlank(token)) {
            return null;
        }
        return baseUrl + CONFIRM_PATH + "?t=" + enc(token) + "&e=" + enc(address);
    }

    /**
     * The absolute deep link that opens the saved dashboard at repository path {@code repoPath} in the
     * live UI (saiku#943 — the link-based digest subscription). Returns {@code null} when the public base
     * URL is unconfigured or the path is blank.
     *
     * <p><b>SSRF-safe.</b> The host comes ONLY from the ops-configured base URL — never from a request or
     * job payload — exactly like {@link #unsubscribeUrl} / {@link #confirmUrl}. The repository path is a
     * server-validated JCR path; each of its slash-delimited segments is URL-encoded so a crafted path
     * cannot inject a different host, an authority, a scheme, or query/fragment control characters (a
     * raw {@code ..} segment survives encoding harmlessly — it addresses a sibling repo path, never a URL
     * host). The result is always {@code <opsBaseUrl>/ui/dashboards/<encoded/path>}.
     */
    public String dashboardUrl(String repoPath) {
        if (baseUrl == null || isBlank(repoPath)) {
            return null;
        }
        return baseUrl + DASHBOARD_PATH_PREFIX + encodePath(repoPath);
    }

    // ---- internals ----

    /**
     * URL-encode a repository path segment-by-segment: encode each segment but preserve the {@code /}
     * separators so the multi-segment JCR path stays a path (not a single encoded blob), while any
     * host/scheme/authority/query metacharacter inside a segment is neutralised.
     */
    private static String encodePath(String repoPath) {
        String p = repoPath.replace("\r", "").replace("\n", "").trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        String[] segments = p.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(enc(segments[i]));
        }
        return sb.toString();
    }

    private static String resolve(Function<String, String> env, Function<String, String> prop) {
        String v = env.apply(ENV_KEY);
        if (isBlank(v)) {
            v = prop.apply(PROP_KEY);
        }
        return v;
    }

    /** Trim, CRLF-strip, drop a trailing slash; null when blank. */
    private static String normaliseBase(String base) {
        if (isBlank(base)) {
            return null;
        }
        String s = base.replace("\r", "").replace("\n", "").trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
