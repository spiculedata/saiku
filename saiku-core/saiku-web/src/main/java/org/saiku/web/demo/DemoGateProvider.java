/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

/**
 * Pluggable backend for the demo email-validation gate (saiku#1029).
 *
 * <p>The gate proves a visitor controls an email address before letting them
 * reach the public demo's login form — light lead-capture + bot/spam filter,
 * <em>not</em> a real auth system. {@link WorkOsMagicAuthProvider} is the
 * shipped implementation; a self-hosted SMTP + signed-JWT provider can drop in
 * later without touching the filter, cookie, resource, or UI (everything above
 * this interface is provider-agnostic).
 */
public interface DemoGateProvider {

    /**
     * Send a one-time validation code to {@code email}. Implementations own the
     * delivery (email send, deliverability, code generation, expiry).
     *
     * @throws DemoGateException on a hard failure (network, provider 5xx, bad
     *     configuration). A successful return means the provider accepted the
     *     send request — the code is on its way.
     */
    void sendCode(String email) throws DemoGateException;

    /**
     * Verify the {@code code} the visitor entered for {@code email}.
     *
     * @return {@code true} when the code is valid (email is validated);
     *     {@code false} when the code is wrong or expired.
     * @throws DemoGateException only on a hard failure — a wrong code is a
     *     {@code false} return, not an exception.
     */
    boolean verifyCode(String email, String code) throws DemoGateException;

    /** Short, stable provider identifier surfaced on the info endpoint (e.g. {@code "workos"}). */
    String name();
}
