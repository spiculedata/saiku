/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.sqlworkbench;

/**
 * Thrown by {@link SqlWorkbenchService} for a request the caller can act on — an unknown
 * datasource, a rejected write statement, or a failed query. {@link #code} is a stable,
 * machine-readable reason the REST layer maps to an HTTP status; {@link #getMessage()} is safe to
 * show a {@code ROLE_SQL_EXEC} user (it never carries a stack trace or Java class name).
 */
public class SqlWorkbenchException extends RuntimeException {

    public enum Code {
        UNKNOWN_DATASOURCE,
        READ_ONLY_VIOLATION,
        QUERY_FAILED
    }

    private final Code code;

    public SqlWorkbenchException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
