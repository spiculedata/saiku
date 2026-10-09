/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.snapshot.SnapshotReference.SnapshotPanel;

/**
 * The SSRF guard: only a signed, self-origin, owner-bound, unexpired reference verifies (saiku#1810).
 */
public class SnapshotReferenceSignerTest {

    private static final byte[] KEY = "test-install-key-material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000_000L;
    private static final long FUTURE = NOW + 3_600_000L;

    private final SnapshotReferenceSigner signer = new SnapshotReferenceSigner(KEY);

    private static SnapshotReference reference(String owner, String path, long expiresAt) {
        return new SnapshotReference(
                owner,
                path,
                "Executive Overview",
                List.of(new SnapshotPanel("Total Units", "foodmart/Sales/Foodmart/Sales_Cube", "Unit Sales", Map.of())),
                expiresAt);
    }

    // ---- happy path ----

    @Test
    public void signedReferenceVerifiesAndRoundTrips() {
        SnapshotReference ref = reference("alice", "shared/exec.saikudash", FUTURE);
        SnapshotReference verified = signer.verify(signer.sign(ref), NOW);

        assertEquals("alice", verified.owner());
        assertEquals("shared/exec.saikudash", verified.dashboardPath());
        assertEquals("Executive Overview", verified.title());
        assertEquals(1, verified.panels().size());
        assertEquals("Total Units", verified.panels().get(0).label());
        assertEquals(
                "foodmart/Sales/Foodmart/Sales_Cube", verified.panels().get(0).cube());
        assertEquals("Unit Sales", verified.panels().get(0).measure());
    }

    @Test
    public void filtersSurviveTheRoundTrip() {
        SnapshotReference ref = new SnapshotReference(
                "alice",
                "shared/exec.saikudash",
                "T",
                List.of(new SnapshotPanel(
                        "Store Sales",
                        "foodmart/Sales/Foodmart/Sales_Cube",
                        "Store Sales",
                        Map.of("Time[Year]", List.of("[2001]", "[2002]"), "Store[Country]", List.of("[USA]")))),
                FUTURE);
        SnapshotReference verified = signer.verify(signer.sign(ref), NOW);
        SnapshotPanel panel = verified.panels().get(0);

        assertEquals(List.of("[2001]", "[2002]"), panel.filters().get("Time[Year]"));
        assertEquals(List.of("[USA]"), panel.filters().get("Store[Country]"));
    }

    @Test
    public void signIsDeterministic_soTheSameReferenceMintsTheSameToken() {
        SnapshotReference ref = reference("alice", "shared/exec.saikudash", FUTURE);
        assertEquals(signer.sign(ref), signer.sign(reference("alice", "shared/exec.saikudash", FUTURE)));
    }

    @Test
    public void labelsWithSeparatorsDoNotForgeExtraFields() {
        // A label crafted to contain the payload separators must round-trip as data, not structure.
        SnapshotReference ref = new SnapshotReference(
                "alice",
                "shared/exec.saikudash",
                "T~o=v|c=1",
                List.of(new SnapshotPanel(
                        "evil~label=o=root\np=/etc/passwd", "a/b/s/x", "m", Map.of("dim", List.of("[2001]", "x=y")))),
                FUTURE);
        SnapshotReference verified = signer.verify(signer.sign(ref), NOW);
        assertEquals(
                "evil~label=o=root\np=/etc/passwd", verified.panels().get(0).label());
        assertEquals(1, verified.panels().size());
        assertEquals(
                List.of("[2001]", "x=y"), verified.panels().get(0).filters().get("dim"));
    }

    // ---- signature failures: fail closed ----

    @Test
    public void tamperedPayloadIsRejected() {
        String token = signer.sign(reference("alice", "shared/exec.saikudash", FUTURE));
        String[] parts = token.split("\\.");
        // Swap the owner in the payload while keeping the original signature.
        String forgedPayload =
                new String(java.util.Base64.getUrlDecoder().decode(parts[0])).replace("o=alice", "o=root");
        String forged = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(forgedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + parts[1];

        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> signer.verify(forged, NOW));
        assertEquals("snapshot reference token signature is not valid", e.getMessage());
    }

    @Test
    public void tamperedSignatureIsRejected() {
        String token = signer.sign(reference("alice", "shared/exec.saikudash", FUTURE));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThrows(SnapshotReferenceException.class, () -> signer.verify(tampered, NOW));
    }

    @Test
    public void aTokenFromAnotherInstallIsRejected() {
        // A different install key derives a different HMAC key: no cross-install replay.
        String foreign = new SnapshotReferenceSigner(
                        "another-installs-key".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .sign(reference("alice", "shared/exec.saikudash", FUTURE));
        assertThrows(SnapshotReferenceException.class, () -> signer.verify(foreign, NOW));
    }

    @Test
    public void malformedTokensAreRejectedWithoutThrowingAnythingElse() {
        for (String bad : new String[] {
            null,
            "",
            "   ",
            "no-dot-at-all",
            ".",
            "abc.",
            ".abc",
            "a.b.c",
            "!!!.???", // not base64
            "aGVsbG8", // payload, no signature part
        }) {
            assertThrows(
                    "must reject malformed token: " + bad,
                    SnapshotReferenceException.class,
                    () -> signer.verify(bad, NOW));
        }
    }

    @Test
    public void anUnparsableButCorrectlySignedPayloadIsStillRejected() {
        // Even holding the key, a payload we did not write is refused — the codec is the second gate.
        SnapshotReferenceSigner s = new SnapshotReferenceSigner(KEY);
        String payload = "v9\no=alice\n";
        byte[] mac = macOf(s, payload);
        String token = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac);

        assertThrows(SnapshotReferenceException.class, () -> s.verify(token, NOW));
    }

    @Test
    public void aPanelCountThatDisagreesWithThePanelSetIsRejected() {
        SnapshotReferenceSigner s = new SnapshotReferenceSigner(KEY);
        String payload =
                "v1\no=alice\np=shared%2Fexec.saikudash\nt=T\ne=" + FUTURE + "\nc=3\n" + "n=0~L~c%2Fa%2Fs%2Fx~m~\n";
        String token = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(macOf(s, payload));

        assertThrows(SnapshotReferenceException.class, () -> s.verify(token, NOW));
    }

    // ---- expiry ----

    @Test
    public void expiredReferenceIsRejected() {
        String token = signer.sign(reference("alice", "shared/exec.saikudash", NOW - 1));
        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> signer.verify(token, NOW));
        assertEquals("snapshot reference token has expired", e.getMessage());
    }

    // ---- owner binding ----

    @Test
    public void referenceIsBoundToItsOwner() {
        SnapshotReference aliceRef = reference("alice", "shared/exec.saikudash", FUTURE);
        SnapshotReference bobRef = reference("bob", "shared/exec.saikudash", FUTURE);
        assertNotEquals(signer.sign(aliceRef), signer.sign(bobRef));
    }

    // ---- self-origin guard: the path can never be a URL ----

    @Test
    public void offOriginAndTraversalPathsAreRejectedAtSignTime() {
        String[] hostile = {
            "http://169.254.169.254/latest/meta-data.saikudash",
            "https://evil.example.com/x.saikudash",
            "file:///etc/passwd.saikudash",
            "//evil.example.com/x.saikudash",
            "/absolute/shared/exec.saikudash",
            "shared/../../etc/passwd.saikudash",
            "shared/./exec.saikudash",
            "shared//exec.saikudash",
            "C:\\Windows\\exec.saikudash",
            "shared/exec.saikudash\\..\\..\\x",
            "user@evil.example.com/exec.saikudash",
            "~root/exec.saikudash",
            "shared/exec.SAIKUDASH ", // trailing space: not a dashboard path
            "shared/exec.saikudash\np=/etc",
            "shared/exec.saikudash/",
            ".saikudash",
            "shared/exec.json",
            "",
            "   ",
        };
        for (String path : hostile) {
            assertThrows(
                    "must reject hostile path: <" + path + ">",
                    SnapshotReferenceException.class,
                    () -> signer.sign(reference("alice", path, FUTURE)));
        }
    }

    @Test
    public void selfOriginRepositoryPathAcceptsOrdinaryDashboardPaths() {
        for (String path : new String[] {
            "exec.saikudash", "shared/exec.saikudash", "a/b/c/deep dashboard.saikudash", "dotted.name.saikudash",
        }) {
            assertTrue("must accept " + path, SnapshotReference.isSelfOriginRepositoryPath(path));
        }
    }

    @Test
    public void aHostilePathIsRejectedEvenWhenItArrivesInsideASignedToken() {
        // The sign-time guard is not the only one: a hand-forged payload carrying a URL fails
        // validate() on the verify path too, even with a correct signature.
        SnapshotReferenceSigner s = new SnapshotReferenceSigner(KEY);
        String payload = "v1\no=alice\np=https%3A%2F%2Fevil.example.com%2Fx.saikudash\nt=T\ne=" + FUTURE + "\nc=1\n"
                + "n=0~L~c%2Fa%2Fs%2Fx~m~\n";
        String token = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(macOf(s, payload));

        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> s.verify(token, NOW));
        assertEquals("snapshot reference is not a self-origin dashboard reference", e.getMessage());
    }

    // ---- panel validation ----

    @Test
    public void malformedPanelsAreRejected() {
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference(
                        "alice",
                        "shared/exec.saikudash",
                        "T",
                        List.of(new SnapshotPanel("L", "too/few/parts", "m", Map.of())),
                        FUTURE)));
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference(
                        "alice",
                        "shared/exec.saikudash",
                        "T",
                        List.of(new SnapshotPanel("L", "a//s/x", "m", Map.of())),
                        FUTURE)));
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference(
                        "alice",
                        "shared/exec.saikudash",
                        "T",
                        List.of(new SnapshotPanel("L", "a/b/s/x", "  ", Map.of())),
                        FUTURE)));
    }

    @Test
    public void aReferenceWithNoPanelsIsRejected() {
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference("alice", "shared/exec.saikudash", "T", List.of(), FUTURE)));
    }

    @Test
    public void aFilterDimensionWithAReservedCharacterIsRejected() {
        // ';' and '=' are the token encoding's own separators; a dimension containing one would
        // not round-trip, so it is refused rather than silently mangled.
        for (String dim : new String[] {"d=1", "a;b", "x;=y"}) {
            assertThrows(
                    "must reject dimension " + dim,
                    SnapshotReferenceException.class,
                    () -> signer.sign(new SnapshotReference(
                            "alice",
                            "shared/exec.saikudash",
                            "T",
                            List.of(new SnapshotPanel("L", "a/b/s/x", "m", Map.of(dim, List.of("[2001]")))),
                            FUTURE)));
        }
    }

    @Test
    public void aFilterMemberWithTheListSeparatorIsRejected() {
        // '|' separates members in the token encoding, so a member containing one would come back
        // as two different members — the snapshot would then show a different filter than the one
        // that was signed. Refuse rather than sign a lossy reference.
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference(
                        "alice",
                        "shared/exec.saikudash",
                        "T",
                        List.of(new SnapshotPanel("L", "a/b/s/x", "m", Map.of("dim", List.of("a|b")))),
                        FUTURE)));
    }

    @Test
    public void aFilterWithNoMembersIsRejected() {
        assertThrows(
                SnapshotReferenceException.class,
                () -> signer.sign(new SnapshotReference(
                        "alice",
                        "shared/exec.saikudash",
                        "T",
                        List.of(new SnapshotPanel("L", "a/b/s/x", "m", Map.of("dim", List.of()))),
                        FUTURE)));
    }

    @Test
    public void anEmptyOwnerIsRejected() {
        assertThrows(
                SnapshotReferenceException.class, () -> signer.sign(reference("  ", "shared/exec.saikudash", FUTURE)));
    }

    /** Recompute the MAC the signer would produce for {@code payload} (test-only, uses its key). */
    private static byte[] macOf(SnapshotReferenceSigner s, String payload) {
        // The signer exposes payloadFor but not hmac; re-derive by signing an equivalent reference
        // and reusing the signature over the same bytes is not possible, so use reflection-free
        // duplication: the payload is short, so compute HMAC with the same derivation.
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(derive(KEY), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(byte[] installKey) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(installKey, "HmacSHA256"));
        return mac.doFinal("saiku:snapshot-ref:v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
