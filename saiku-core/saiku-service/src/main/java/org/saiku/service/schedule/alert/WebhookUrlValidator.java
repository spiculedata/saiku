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
package org.saiku.service.schedule.alert;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF gate for a threshold-alert webhook URL (saiku#1098). The URL is <b>admin-authored</b> (it comes
 * off the alert's job payload, which only an admin can create), never a request-time host from an
 * untrusted source — so this is defence-in-depth, not the only line. It still fails closed:
 *
 * <ul>
 *   <li><b>https only</b> — plaintext http is rejected (alert payloads describe business data; don't
 *       leak them over the wire).</li>
 *   <li><b>No internal / loopback / link-local / private / wildcard host</b> — a literal IP or a
 *       hostname that resolves into any of those ranges is rejected, closing the classic
 *       "webhook → 169.254.169.254 metadata endpoint / 127.0.0.1 / 10.x internal service" SSRF.</li>
 *   <li><b>No credentials in the authority</b> — keep the surface boring.</li>
 * </ul>
 *
 * <p>Validation happens at alert-create / parse time and again immediately before the POST. The
 * literal / syntactic checks never touch the network; the address-range check resolves the host once
 * (via an injectable {@link HostResolver} seam so tests stay hermetic). A host that does not resolve is
 * rejected — a webhook that can't be reached is not a usable target.
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {}

    /** Seam so tests can validate resolving-range logic without live DNS. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final HostResolver DNS = InetAddress::getAllByName;

    /**
     * Validate {@code url} for use as an alert webhook target, resolving the host against live DNS.
     * Returns the parsed {@link URI} on success; throws {@link IllegalArgumentException} with a short
     * reason on any failure.
     */
    public static URI validate(String url) {
        return validate(url, DNS);
    }

    /** As {@link #validate(String)} but with a pluggable resolver (visible for tests). */
    static URI validate(String url, HostResolver resolver) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("webhook url is required");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("webhook url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.toLowerCase(Locale.ROOT).equals("https")) {
            throw new IllegalArgumentException("webhook url must use https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("webhook url must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("webhook url must have a host");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        // Reject obvious internal names / literals outright without needing DNS (fast fail + covers
        // unresolvable ones, and closes an IP literal even when the resolver is a no-op in tests).
        if (isBlockedHostName(lowerHost)) {
            throw new IllegalArgumentException("webhook url host is not allowed (internal / loopback)");
        }
        if (isIpLiteral(lowerHost)) {
            // A literal address never needs DNS — check the bytes directly.
            InetAddress literal;
            try {
                literal = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("webhook url host is not a valid address");
            }
            if (isBlockedAddress(literal)) {
                throw new IllegalArgumentException("webhook url resolves to a non-routable / internal address");
            }
            return uri;
        }
        // Resolve and reject any address in a loopback / link-local / site-local / private / wildcard range.
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("webhook url host does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("webhook url host does not resolve");
        }
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                throw new IllegalArgumentException("webhook url resolves to a non-routable / internal address");
            }
        }
        return uri;
    }

    /** True when {@code url} passes {@link #validate(String)} without throwing. */
    public static boolean isValid(String url) {
        return isValid(url, DNS);
    }

    /** As {@link #isValid(String)} but with a pluggable resolver (visible for tests). */
    static boolean isValid(String url, HostResolver resolver) {
        try {
            validate(url, resolver);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlockedHostName(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        // Common cloud metadata endpoints by literal / name.
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal")) {
            return true;
        }
        // A bare hostname with no dot is almost always an internal short name (but an IP literal may be dotless in v6).
        return !host.contains(".") && !isIpLiteral(host);
    }

    private static boolean isIpLiteral(String host) {
        // Cheap heuristic: v4 dotted-quad or a bracketed/colon'd v6 form.
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":");
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] a = addr.getAddress();
        // IPv4 extras site-local doesn't cover: 100.64/10 (CGNAT) and 0.0.0.0/8 ("this host").
        if (a.length == 4) {
            int b0 = a[0] & 0xff;
            int b1 = a[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true; // 100.64.0.0/10 carrier-grade NAT
            }
            if (b0 == 0) {
                return true; // 0.0.0.0/8 "this host"
            }
        }
        // IPv6 unique-local fc00::/7 (ULA is not flagged by isSiteLocalAddress).
        if (a.length == 16) {
            int b0 = a[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) {
                return true; // fc00::/7
            }
        }
        return false;
    }
}
