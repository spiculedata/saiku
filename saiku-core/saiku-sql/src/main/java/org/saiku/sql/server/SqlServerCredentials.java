/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The single username/password pair a {@code sql-serve} endpoint accepts. Both the Avatica
 * (HTTP basic) and Postgres-wire (SCRAM-SHA-256) frontends check clients against it. A server
 * constructed with {@code null} credentials runs in trust mode — acceptable only on loopback.
 */
public final class SqlServerCredentials {

    /** Conservative on purpose: the name ends up in a Jetty login properties file. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final String username;
    private final String password;

    public SqlServerCredentials(String username, String password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "SQL endpoint username must be 1-64 characters of [A-Za-z0-9._@-]: '" + username + "'");
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("SQL endpoint password must not be empty");
        }
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /** Default bind address for every SQL endpoint: loopback only. */
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";

    /**
     * True when {@code bindHost} resolves to a loopback address, i.e. the endpoint is reachable
     * only from this machine. Wildcard addresses ({@code 0.0.0.0}, {@code ::}) are not loopback.
     */
    public static boolean isLoopback(String bindHost) throws UnknownHostException {
        return InetAddress.getByName(bindHost).isLoopbackAddress();
    }
}
