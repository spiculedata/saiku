/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.saiku.service.schedule.alert.WebhookUrlValidator;

/**
 * SSRF gate for the SMTP host the admin mail wizard accepts (saiku#1918 17b, CWE-918).
 *
 * <p><b>The hole this closes.</b> {@code MailConfigResource#save} only CRLF-stripped the host, and
 * {@code sendTest} then dialled it. SMTP has no URL, so the webhooks' "must be https" check has no
 * analogue here — but the probe is exactly as good. JavaMail opens a TCP connection to
 * {@code host:port} and the caller learns the outcome from the response: a fast
 * connection-refused, a slow timeout, or a protocol banner read back. Run from a tenant-admin that
 * is enough to blind-map the engine's own network: which host:port pairs are listening (the
 * control-plane database, a neighbour tenant's service), which are firewalled, and what protocol
 * answers. A whole class of internal reconnaissance that the webhook gate already refuses, reachable
 * through the mail wizard.
 *
 * <p><b>What this enforces.</b> Mirrors {@link WebhookUrlValidator} rather than inventing a second
 * rule set:
 *
 * <ul>
 *   <li><b>Hostname or IP literal only</b> — no scheme, no path, no port suffix, no userinfo, no
 *       whitespace or control characters. A bare SMTP host is a bare hostname; anything with
 *       structure in it is a mistake or an attempt.</li>
 *   <li><b>No obfuscated numeric IPv4</b> — {@code 2130706433}, {@code 0x7f000001},
 *       {@code 0177.0.0.1} and {@code 127.1} are all rejected before any resolution, because JDK
 *       resolvers parse those forms inconsistently (saiku#1846).</li>
 *   <li><b>No internal host names</b> — {@code localhost}, {@code *.localhost}, {@code *.local},
 *       {@code metadata.google.internal}, and any dotless short name.</li>
 *   <li><b>Resolve-and-range check</b> — the host is resolved once (through an injectable
 *       {@link HostResolver} so tests stay hermetic) and every returned address is checked against
 *       the same loopback / link-local / site-local / any-local / multicast / CGNAT / IPv6-ULA rules
 *       the webhook gate uses. An unresolvable host is refused: a relay that can't be reached is
 *       not a usable configuration.</li>
 *   <li><b>Port allowlist</b> — only genuine SMTP ports. A probe needs an odd port; mail does not.
 *       Defaults to 25, 465, 587, 2525 and is extensible per-deployment.</li>
 * </ul>
 *
 * <p><b>Escape hatch.</b> {@code saiku.mail.smtp.allowedHosts} (comma-separated) adds specific
 * hosts to an allowlist that bypasses the resolve-and-range check but NOT the syntactic and port
 * checks, and {@code saiku.mail.smtp.allowPrivateRange=true} lifts the range check entirely. Both
 * exist because an on-prem deployment's relay is routinely an RFC-1918 address on a dotless internal
 * name, and refusing to configure mail there would be a regression, not a hardening. Both are
 * opt-in configuration, never defaults. The recommended shape stays what the issue says it should
 * be: an ops-managed relay supplied by environment, where {@code MailConfigResolver#managedByOps}
 * already makes the in-app wizard read-only.
 */
public final class SmtpHostValidator {

    private SmtpHostValidator() {}

    /** Seam so tests can exercise the resolve-and-range logic without live DNS. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final HostResolver DNS = InetAddress::getAllByName;

    /** Ports an SMTP relay can plausibly listen on. Anything else is a probe, not mail. */
    private static final Set<Integer> DEFAULT_PORTS = Set.of(25, 465, 587, 2525);

    /** Outcome of a successful validation: the exact host and port that were cleared. */
    public record ValidatedHost(String host, int port) {}

    /**
     * Validate an SMTP host/port pair against live DNS.
     *
     * @throws IllegalArgumentException with a short, non-leaking reason on any failure
     */
    public static ValidatedHost validate(String host, int port) {
        return validate(host, port, DNS);
    }

    /** As {@link #validate(String, int)} but with a pluggable resolver (visible for tests). */
    public static ValidatedHost validate(String host, int port, HostResolver resolver) {
        String h = normalize(host);
        int p = validatePort(port);
        if (isExplicitlyAllowed(h)) {
            return new ValidatedHost(h, p);
        }
        if (WebhookUrlValidator.isObfuscatedNumericIpv4(h)) {
            throw new IllegalArgumentException("smtp host is a non-canonical numeric address encoding");
        }
        if (allowPrivateRange()) {
            // The operator has said this deployment's mail is internal. Waive the "is this an
            // internal destination" judgement (both the name heuristic and the address range) but
            // NOT the syntax, the obfuscated-numeric or the port rules — those are about the shape
            // of the configuration, not about where the relay lives, and keeping them means this
            // is a scoped "mail is internal here" switch rather than a blank cheque.
            return new ValidatedHost(h, p);
        }
        if (WebhookUrlValidator.isBlockedHostName(h)) {
            throw new IllegalArgumentException("smtp host is not an allowed host (internal / loopback name)");
        }
        if (WebhookUrlValidator.isIpLiteral(h)) {
            InetAddress literal;
            try {
                literal = InetAddress.getByName(h);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("smtp host is not a valid address");
            }
            if (WebhookUrlValidator.isBlockedAddress(literal)) {
                throw new IllegalArgumentException("smtp host resolves to a non-routable / internal address");
            }
            return new ValidatedHost(h, p);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver == null ? DNS.resolve(h) : resolver.resolve(h);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("smtp host does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("smtp host does not resolve");
        }
        for (InetAddress addr : addresses) {
            if (WebhookUrlValidator.isBlockedAddress(addr)) {
                throw new IllegalArgumentException("smtp host resolves to a non-routable / internal address");
            }
        }
        return new ValidatedHost(h, p);
    }

    /** True when {@code host}/{@code port} passes {@link #validate(String, int)} without throwing. */
    public static boolean isValid(String host, int port) {
        try {
            validate(host, port, DNS);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Trim, strip IPv6 brackets and lowercase the host, rejecting anything that isn't a bare
     * hostname or IP literal. A bare SMTP host never carries a scheme, a path, a port or
     * credentials — each of those is either a configuration mistake or an attempt to smuggle a
     * different target past the checks below.
     */
    private static String normalize(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("smtp host is required");
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        // JavaMail accepts bracketed IPv6 literals; normalise them away so the range check sees
        // the bare address. Anything else with a bracket, colon-in-a-weird-place, slash, at-sign
        // or whitespace is not a bare host.
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.isEmpty()) {
            throw new IllegalArgumentException("smtp host is required");
        }
        if (h.indexOf('/') >= 0) {
            throw new IllegalArgumentException("smtp host must be a bare hostname or IP address (no path)");
        }
        if (h.indexOf('@') >= 0) {
            throw new IllegalArgumentException("smtp host must not contain credentials");
        }
        // A colon in a non-bracketed host is either a port suffix or a malformed IPv6 literal.
        // Both are refused: the port is a separate, separately-validated field.
        if (h.indexOf(':') >= 0) {
            throw new IllegalArgumentException("smtp host must not carry a port or scheme");
        }
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("smtp host contains an invalid character");
            }
        }
        if (h.startsWith(".") || h.endsWith(".") || h.contains("..")) {
            throw new IllegalArgumentException("smtp host is not a valid hostname");
        }
        return h;
    }

    private static int validatePort(int port) {
        if (!allowedPorts().contains(port)) {
            throw new IllegalArgumentException(
                    "smtp port " + port + " is not allowed. Use one of " + new java.util.TreeSet<>(allowedPorts())
                            + ", or set saiku.mail.smtp.allowedPorts to extend the list.");
        }
        return port;
    }

    /**
     * The configured port allowlist: the defaults plus anything in the
     * {@code saiku.mail.smtp.allowedPorts} system property (comma-separated). Read per call so a
     * test can change it without a rebuild.
     */
    public static Set<Integer> allowedPorts() {
        Set<Integer> ports = new LinkedHashSet<>(DEFAULT_PORTS);
        String extra = System.getProperty("saiku.mail.smtp.allowedPorts", "");
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                try {
                    ports.add(Integer.parseInt(p));
                } catch (NumberFormatException ignored) {
                    // A malformed entry is ignored rather than fatal: the allowlist can only be
                    // widened by a valid integer, and a typo must not brick mail configuration.
                }
            }
        }
        return ports;
    }

    /** True when {@code host} is listed in {@code saiku.mail.smtp.allowedHosts}. */
    public static boolean isExplicitlyAllowed(String host) {
        if (host == null || host.isBlank()) return false;
        String list = System.getProperty("saiku.mail.smtp.allowedHosts", "");
        if (list == null || list.isBlank()) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        for (String part : list.split(",")) {
            String allowed = part.trim().toLowerCase(Locale.ROOT);
            if (allowed.isEmpty()) continue;
            if (allowed.equals(h)) return true;
            // A leading-dot entry is a suffix match, so ".corp.example.com" covers every relay in
            // the estate without listing each host.
            if (allowed.startsWith(".") && h.endsWith(allowed)) return true;
        }
        return false;
    }

    private static boolean allowPrivateRange() {
        return Boolean.parseBoolean(System.getProperty("saiku.mail.smtp.allowPrivateRange", "false"));
    }

    /** The default port allowlist, for docs and error messages. */
    public static Set<Integer> defaultPorts() {
        return Set.copyOf(Arrays.asList(25, 465, 587, 2525));
    }
}
