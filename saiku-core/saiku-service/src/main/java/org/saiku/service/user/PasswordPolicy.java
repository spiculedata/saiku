/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.user;

/**
 * Validates new/updated user passwords against the Saiku password policy.
 *
 * <p>Defaults:
 * <ul>
 *   <li>minimum length 10 (tunable via {@code saiku.password.min-length})</li>
 *   <li>at least 3 of 4 character classes — lowercase, uppercase, digit, symbol
 *       (tunable via {@code saiku.password.require-classes}; max 4)</li>
 *   <li>must not equal the username (case-insensitive)</li>
 * </ul>
 *
 * <p>The policy is applied only when a password is <em>being set</em>
 * (new user, explicit update). Existing hashed entries are never re-validated
 * at boot — operators would otherwise be locked out of legacy accounts.
 */
public final class PasswordPolicy {

    private final int minLength;
    private final int requiredClasses;

    public PasswordPolicy() {
        this(
                Integer.parseInt(System.getProperty("saiku.password.min-length", "10")),
                Math.min(4, Integer.parseInt(System.getProperty("saiku.password.require-classes", "3"))));
    }

    public PasswordPolicy(int minLength, int requiredClasses) {
        this.minLength = minLength;
        this.requiredClasses = Math.min(4, Math.max(1, requiredClasses));
    }

    public int getMinLength() { return minLength; }
    public int getRequiredClasses() { return requiredClasses; }

    /**
     * @throws IllegalArgumentException describing the first failing rule.
     */
    public void validate(String username, String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        if (password.length() < minLength) {
            throw new IllegalArgumentException(
                    "Password must be at least " + minLength + " characters");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("Password must not equal the username");
        }
        int classes = countCharClasses(password);
        if (classes < requiredClasses) {
            throw new IllegalArgumentException(
                    "Password must contain at least " + requiredClasses
                            + " of: lowercase, uppercase, digit, symbol");
        }
    }

    private static int countCharClasses(String s) {
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else symbol = true;
        }
        int n = 0;
        if (lower) n++;
        if (upper) n++;
        if (digit) n++;
        if (symbol) n++;
        return n;
    }
}
