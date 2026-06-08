/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.demo;

/**
 * Hard failure inside a {@link DemoGateProvider} — network error, provider 5xx,
 * misconfiguration. A wrong/expired code is NOT this; it's a {@code false}
 * return from {@link DemoGateProvider#verifyCode}.
 */
public class DemoGateException extends Exception {

    public DemoGateException(String message) {
        super(message);
    }

    public DemoGateException(String message, Throwable cause) {
        super(message, cause);
    }
}
