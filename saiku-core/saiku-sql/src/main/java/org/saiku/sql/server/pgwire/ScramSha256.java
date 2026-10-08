/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server.pgwire;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Server side of SCRAM-SHA-256 (RFC 5802 / RFC 7677) as Postgres uses it for password auth.
 * Channel binding is not offered (the PG-wire endpoint has no TLS), so only the {@code n} and
 * {@code y} GS2 flags are accepted.
 *
 * <p>The salted password is derived once per server; each connection gets a fresh {@link
 * Exchange} carrying its own nonce. The password is never sent over the wire in either
 * direction, which matters because this endpoint speaks plaintext.
 */
final class ScramSha256 {

    static final String MECHANISM = "SCRAM-SHA-256";

    private static final int ITERATIONS = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String salt;
    private final byte[] storedKey;
    private final byte[] serverKey;

    ScramSha256(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        this.salt = Base64.getEncoder().encodeToString(saltBytes);
        try {
            SecretKeyFactory pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] salted = pbkdf2.generateSecret(new PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, 256))
                    .getEncoded();
            this.storedKey = sha256(hmac(salted, "Client Key"));
            this.serverKey = hmac(salted, "Server Key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SCRAM-SHA-256 is unavailable on this JVM", e);
        }
    }

    Exchange newExchange() {
        return new Exchange();
    }

    /** Thrown for any malformed or failed exchange; the caller reports a generic auth failure. */
    static final class ScramException extends Exception {
        private static final long serialVersionUID = 1L;

        ScramException(String message) {
            super(message);
        }
    }

    /** One client's authentication attempt. Not thread-safe; one per connection. */
    final class Exchange {
        private String gs2Header;
        private String clientFirstBare;
        private String serverFirst;
        private String nonce;

        /** Consumes the client-first-message and returns the server-first-message. */
        String serverFirst(String clientFirst) throws ScramException {
            // gs2-header = gs2-cbind-flag "," [authzid] ","
            int first = clientFirst.indexOf(',');
            int second = first < 0 ? -1 : clientFirst.indexOf(',', first + 1);
            if (second < 0) throw new ScramException("malformed client-first-message");
            String cbindFlag = clientFirst.substring(0, first);
            if (!cbindFlag.equals("n") && !cbindFlag.equals("y")) {
                throw new ScramException("channel binding is not supported");
            }
            gs2Header = clientFirst.substring(0, second + 1);
            clientFirstBare = clientFirst.substring(second + 1);
            String clientNonce = attributes(clientFirstBare).get('r');
            if (clientNonce == null || clientNonce.isEmpty()) throw new ScramException("missing client nonce");
            byte[] serverNonce = new byte[18];
            RANDOM.nextBytes(serverNonce);
            nonce = clientNonce + Base64.getEncoder().encodeToString(serverNonce);
            serverFirst = "r=" + nonce + ",s=" + salt + ",i=" + ITERATIONS;
            return serverFirst;
        }

        /**
         * Verifies the client-final-message and returns the server-final-message. Throws when the
         * proof does not match, i.e. the client does not know the password.
         */
        String serverFinal(String clientFinal) throws ScramException {
            if (serverFirst == null) throw new ScramException("client-final-message before client-first");
            int proofAt = clientFinal.lastIndexOf(",p=");
            if (proofAt < 0) throw new ScramException("missing client proof");
            String withoutProof = clientFinal.substring(0, proofAt);
            Map<Character, String> attrs = attributes(withoutProof);
            String expectedBinding = Base64.getEncoder().encodeToString(gs2Header.getBytes(StandardCharsets.UTF_8));
            if (!expectedBinding.equals(attrs.get('c'))) throw new ScramException("channel binding mismatch");
            if (!nonce.equals(attrs.get('r'))) throw new ScramException("nonce mismatch");
            byte[] proof;
            try {
                proof = Base64.getDecoder().decode(clientFinal.substring(proofAt + 3));
            } catch (IllegalArgumentException e) {
                throw new ScramException("malformed client proof");
            }
            String authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
            try {
                byte[] clientSignature = hmac(storedKey, authMessage);
                if (proof.length != clientSignature.length) throw new ScramException("bad client proof");
                byte[] clientKey = new byte[proof.length];
                for (int i = 0; i < proof.length; i++) clientKey[i] = (byte) (proof[i] ^ clientSignature[i]);
                if (!MessageDigest.isEqual(sha256(clientKey), storedKey)) {
                    throw new ScramException("bad client proof");
                }
                return "v=" + Base64.getEncoder().encodeToString(hmac(serverKey, authMessage));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Parses {@code a=value,b=value} into a map keyed by the attribute letter. */
    private static Map<Character, String> attributes(String message) {
        Map<Character, String> out = new HashMap<>();
        for (String part : message.split(",")) {
            if (part.length() >= 2 && part.charAt(1) == '=') out.putIfAbsent(part.charAt(0), part.substring(2));
        }
        return out;
    }

    private static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
