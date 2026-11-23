/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.snapshot.SnapshotReference.SnapshotPanel;

/** The expiry invariant: a reference that never expires is not a reference (saiku#2162). */
public class SnapshotReferenceExpiryTest {

    private static final byte[] KEY = "test-install-key-material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000_000L;
    private static final long FUTURE = NOW + 3_600_000L;
    private static final SnapshotPanel PANEL =
            new SnapshotPanel("Total Units", "foodmart/Sales/Foodmart/Sales_Cube", "Unit Sales", Map.of());

    private final SnapshotReferenceSigner signer = new SnapshotReferenceSigner(KEY);

    private static SnapshotReference reference(String owner, long expiresAt) {
        return new SnapshotReference(owner, "shared/exec.saikudash", "Executive Overview", List.of(PANEL), expiresAt);
    }

    // ---- a forgotten field must not become a permanent credential ----

    @Test
    public void aBuilderWithoutAnExpiryIsRefusedRatherThanSigningForever() {
        SnapshotReference.Builder builder = new SnapshotReference.Builder()
                .owner("alice")
                .dashboardPath("shared/exec.saikudash")
                .title("Executive Overview")
                .panel(PANEL);

        // The default is "unset", not "never": it is rejected instead of signed.
        assertEquals(0L, builder.build().expiresAtEpochMillis());
        SnapshotReferenceException e = assertThrows(
                SnapshotReferenceException.class, () -> builder.build().validate());
        assertEquals("snapshot reference expiry is required", e.getMessage());
        assertThrows(SnapshotReferenceException.class, () -> signer.sign(builder.build()));
    }

    @Test
    public void theOldNeverExpiresSentinelIsRefused() {
        SnapshotReference never = reference("alice", Long.MAX_VALUE);

        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, never::validate);
        assertEquals("snapshot reference expiry is required", e.getMessage());
        assertThrows(SnapshotReferenceException.class, () -> signer.sign(never));
    }

    @Test
    public void aTokenMintedBeforeTheFixIsRefusedOnVerify() {
        // A token signed by an older build carries e=9223372036854775807 and used to verify for ~292
        // million years: the signature is genuine, so only the expiry guard can stop it.
        byte[] payloadBytes =
                signer.payloadFor(reference("alice", Long.MAX_VALUE)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        String mac = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(macOf(payloadBytes));
        String token = encoded + "." + mac;

        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> signer.verify(token, NOW));
        assertEquals("snapshot reference expiry is required", e.getMessage());
    }

    @Test
    public void anUnsetOrNegativeExpiryIsRefused() {
        assertThrows(SnapshotReferenceException.class, () -> signer.sign(reference("alice", 0L)));
        assertThrows(SnapshotReferenceException.class, () -> signer.sign(reference("alice", -1L)));
    }

    // ---- the contract that must not change: real expiries still work, and still expire ----

    @Test
    public void aRealExpiryStillSignsAndVerifies() {
        SnapshotReference verified = signer.verify(signer.sign(reference("alice", FUTURE)), NOW);

        assertEquals("alice", verified.owner());
        assertEquals(FUTURE, verified.expiresAtEpochMillis());
    }

    @Test
    public void aReferenceThatHasExpiredStillReadsAsExpired() {
        String token = signer.sign(reference("alice", NOW - 1));

        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> signer.verify(token, NOW));
        assertEquals("snapshot reference token has expired", e.getMessage());
    }

    /** Recompute the MAC the signer produces for {@code payload} (test-only, with the same key). */
    private static byte[] macOf(byte[] payloadBytes) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(derive(KEY), "HmacSHA256"));
            return mac.doFinal(payloadBytes);
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
