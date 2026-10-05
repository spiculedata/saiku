/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.saiku.datasources.connection.encrypt.CryptoUtil;
import org.saiku.service.snapshot.SnapshotReference.SnapshotPanel;

/**
 * Signs and verifies the snapshot reference token (saiku#1810) — the SSRF guard, in one class.
 *
 * <p>Token layout: {@code base64url(payload) + "." + base64url(HMAC-SHA256(hmacKey, payload))}, where
 * {@code payload} is the deterministic line encoding below. The HMAC key is derived from the SAME
 * per-install key that encrypts stored datasource passwords ({@link
 * CryptoUtil#installKeyMaterial()}), domain-separated with a fixed label so it is never the raw
 * encryption key. <b>No new key mechanism is invented</b> — the same pattern as {@code
 * UnsubscribeTokens} (saiku#1811).
 *
 * <p><b>Why this is not an SSRF primitive.</b> The payload has no field capable of carrying a fetch
 * target:
 *
 * <ul>
 *   <li>{@code p} is a <b>relative repository path</b> which {@link
 *       SnapshotReference#validate()} forces to end {@code .saikudash} with no scheme, authority,
 *       backslash, leading slash or {@code ..} segment. A URL cannot be expressed.
 *   <li>{@code o} is the owner, signed — so the token cannot be replayed as another user, and the
 *       renderer separately requires the caller's authenticated name to match.
 *   <li>{@code e} is an expiry, checked at verify time.
 * </ul>
 *
 * <p>A verified reference is resolved <b>locally</b> from the repository. The renderer issues no
 * outbound HTTP request, so there is nothing to redirect off-origin.
 *
 * <p><b>Determinism.</b> {@link #payloadFor} writes fields in a fixed order with sorted filter
 * dimensions, so the same reference always serialises to the same bytes — required for the signature
 * to be reproducible. {@link #sign} therefore re-encodes from the reference rather than signing
 * caller-supplied bytes: a caller cannot smuggle unsigned fields alongside a valid signature.
 *
 * <p>Verification is constant-time ({@link MessageDigest#isEqual}) over raw MAC bytes, and every
 * failure mode throws the same terse {@link SnapshotReferenceException} reasons.
 */
public final class SnapshotReferenceSigner {

    private static final String HMAC_ALGO = "HmacSHA256";
    /** Domain-separation label: the snapshot HMAC key is distinct from the AES install key. */
    private static final byte[] LABEL = "saiku:snapshot-ref:v1".getBytes(StandardCharsets.US_ASCII);

    /** Payload version prefix — a future format change bumps this rather than guessing. */
    private static final String VERSION = "v1";

    /** Hard cap on decoded payload size, so a huge token cannot be a cheap way to burn memory. */
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    private final byte[] hmacKey;

    /** Production ctor: derives the HMAC key from the per-install key. */
    public SnapshotReferenceSigner() {
        this(CryptoUtil.installKeyMaterial());
    }

    /** Test/explicit ctor; the supplied material is domain-separated before use. */
    public SnapshotReferenceSigner(byte[] installKeyMaterial) {
        this.hmacKey = deriveHmacKey(installKeyMaterial);
    }

    /**
     * Validate {@code reference} and return its signed token.
     *
     * @throws SnapshotReferenceException when the reference is not a valid self-origin reference
     */
    public String sign(SnapshotReference reference) {
        if (reference == null) {
            throw new SnapshotReferenceException("snapshot reference is required");
        }
        reference.validate();
        byte[] payload = payloadFor(reference).getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
    }

    /**
     * Verify {@code token} and return the reference it carries.
     *
     * <p>Fails closed on every path: null/blank, wrong shape, oversized payload, malformed base64, a
     * bad or mismatched MAC, an unparseable payload, an invalid/off-origin reference, or an expired
     * one. Never returns a partially-trusted reference.
     *
     * @param nowEpochMillis clock used for the expiry check (injected so tests are deterministic)
     * @throws SnapshotReferenceException on any failure
     */
    public SnapshotReference verify(String token, long nowEpochMillis) {
        if (token == null || token.isBlank() || token.length() > 64 * 1024) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        byte[] payload;
        byte[] suppliedMac;
        try {
            payload = Base64.getUrlDecoder().decode(token.substring(0, dot));
            suppliedMac = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES || suppliedMac.length == 0) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        // Constant-time over raw MAC bytes: no length/charset leak, and a truncated or padded
        // signature simply fails to match.
        if (!MessageDigest.isEqual(hmac(payload), suppliedMac)) {
            throw new SnapshotReferenceException("snapshot reference token signature is not valid");
        }
        SnapshotReference reference = parse(new String(payload, StandardCharsets.UTF_8));
        reference.validate();
        if (reference.isExpired(nowEpochMillis)) {
            throw new SnapshotReferenceException("snapshot reference token has expired");
        }
        return reference;
    }

    /** As {@link #verify(String, long)} using the wall clock. */
    public SnapshotReference verify(String token) {
        return verify(token, System.currentTimeMillis());
    }

    // ---- payload codec ----

    /**
     * The deterministic text encoding of {@code reference}. Exposed for tests: the exact bytes that
     * get signed are asserted, so a reordering bug in this method cannot silently weaken the MAC's
     * coverage of a field.
     *
     * <p>Shape (one {@code key=value} per line, values {@code application/x-www-form-urlencoded} so no
     * separator can be smuggled through a value):
     *
     * <pre>{@code
     * v1
     * o=alice
     * p=shared%2Fexec.saikudash
     * t=Executive+Overview
     * e=1893456000000
     * c=2
     * n=1~Total+Units~conn%2Fcat%2Fschema%2Fsales~Unit+Sales~Time%5BYear%5D%3D%5B2001%5D
     * }</pre>
     */
    String payloadFor(SnapshotReference reference) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(VERSION).append('\n');
        sb.append("o=").append(enc(reference.owner())).append('\n');
        sb.append("p=").append(enc(reference.dashboardPath())).append('\n');
        sb.append("t=")
                .append(enc(reference.title() == null ? "" : reference.title()))
                .append('\n');
        sb.append("e=").append(reference.expiresAtEpochMillis()).append('\n');
        List<SnapshotPanel> panels = reference.panels();
        sb.append("c=").append(panels.size()).append('\n');
        int i = 0;
        for (SnapshotPanel panel : panels) {
            sb.append("n=")
                    .append(i++)
                    .append('~')
                    .append(enc(panel.label()))
                    .append('~')
                    .append(enc(panel.cube()))
                    .append('~')
                    .append(enc(panel.measure()))
                    .append('~')
                    .append(enc(encodeFilters(panel.filters())))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Filters as {@code dimKey=member|member;dim2=member}, dimensions sorted for determinism. */
    private static String encodeFilters(Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>(filters.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(key).append('=').append(String.join("|", filters.get(key)));
        }
        return sb.toString();
    }

    private SnapshotReference parse(String payload) {
        if (!payload.startsWith(VERSION + "\n")) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        String owner = null;
        String path = null;
        String title = null;
        long expires = Long.MAX_VALUE;
        List<SnapshotPanel> panels = new ArrayList<>();
        int declaredCount = -1;
        String[] lines = payload.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0) {
                // The version line is a bare token, not a key=value pair.
                if (line.equals(VERSION)) {
                    continue;
                }
                throw new SnapshotReferenceException("snapshot reference token is not valid");
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new SnapshotReferenceException("snapshot reference token is not valid");
            }
            String key = line.substring(0, eq);
            String rawValue = line.substring(eq + 1);
            switch (key) {
                case "o" -> owner = dec(rawValue);
                case "p" -> path = dec(rawValue);
                case "t" -> {
                    String t = dec(rawValue);
                    title = (t == null || t.isEmpty()) ? null : t;
                }
                case "e" -> {
                    try {
                        expires = Long.parseLong(rawValue);
                    } catch (NumberFormatException e) {
                        throw new SnapshotReferenceException("snapshot reference token is not valid");
                    }
                }
                case "c" -> {
                    try {
                        declaredCount = Integer.parseInt(rawValue);
                    } catch (NumberFormatException e) {
                        throw new SnapshotReferenceException("snapshot reference token is not valid");
                    }
                }
                case "n" -> panels.add(parsePanel(rawValue));
                default -> throw new SnapshotReferenceException("snapshot reference token is not valid");
            }
        }
        if (declaredCount < 0 || declaredCount != panels.size()) {
            // A count that disagrees with the panel set means the payload was hand-edited after
            // signing — impossible for a genuine token, so refuse rather than render a subset.
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        return new SnapshotReference(owner, path, title, panels, expires);
    }

    private SnapshotPanel parsePanel(String rawValue) {
        // The '~' separator is one character that enc() percent-encodes, so a value can never
        // contain one: split(-1) is unambiguous without a full parser.
        String[] parts = rawValue.split("~", -1);
        if (parts.length != 5) {
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
        String label = dec(parts[1]);
        String cube = dec(parts[2]);
        String measure = dec(parts[3]);
        return new SnapshotPanel(label, cube, measure, decodeFilters(dec(parts[4])));
    }

    private static Map<String, List<String>> decodeFilters(String encoded) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String dim : encoded.split(";", -1)) {
            int eq = dim.indexOf('=');
            if (eq <= 0 || eq == dim.length() - 1) {
                throw new SnapshotReferenceException("snapshot reference token is not valid");
            }
            String key = dim.substring(0, eq);
            List<String> members = new ArrayList<>();
            for (String m : dim.substring(eq + 1).split("\\|", -1)) {
                if (m.isEmpty()) {
                    throw new SnapshotReferenceException("snapshot reference token is not valid");
                }
                members.add(m);
            }
            out.put(key, members);
        }
        return out;
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A stray '%' that is not a valid escape — the payload is not something we wrote.
            throw new SnapshotReferenceException("snapshot reference token is not valid");
        }
    }

    // ---- internals ----

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static byte[] deriveHmacKey(byte[] installKeyMaterial) {
        if (installKeyMaterial == null || installKeyMaterial.length == 0) {
            throw new IllegalStateException("install key material unavailable for snapshot reference HMAC");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(installKeyMaterial, HMAC_ALGO));
            return mac.doFinal(LABEL);
        } catch (Exception e) {
            throw new IllegalStateException("Snapshot reference HMAC key derivation failed", e);
        }
    }
}
