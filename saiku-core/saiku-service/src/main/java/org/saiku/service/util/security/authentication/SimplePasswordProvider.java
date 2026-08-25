/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security.authentication;

public class SimplePasswordProvider implements PasswordProvider {
    private final String password;

    public SimplePasswordProvider(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }
}
