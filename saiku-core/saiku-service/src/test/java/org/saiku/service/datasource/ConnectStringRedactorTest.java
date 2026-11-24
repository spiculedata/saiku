/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.saiku.service.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/**
 * saiku#2547: {@link ConnectStringRedactor} masks the credential values a failed connection can
 * leak into a log line or an admin error response, and redacts a whole exception chain without
 * losing the types, traces or structure — so the log still names the datasource and the root
 * cause, just without the password.
 */
public class ConnectStringRedactorTest {

    private static final String SECRET = "the-users-login-password";

    /** The failure shape the issue shows: a Mondrian exception over a passthrough connect string. */
    private static final String CONNECT_FAILURE = "Mondrian Error:Internal error: Error while creating SQL connection: "
            + "Jdbc=jdbc:postgresql://warehouse.example/x; JdbcUser=bob; JdbcPassword=" + SECRET + ";";

    @Test
    public void masksBareJdbcPassword() {
        String out = ConnectStringRedactor.redact(CONNECT_FAILURE);

        assertFalse(out, out.contains(SECRET));
        assertTrue(out, out.contains("JdbcPassword=***;"));
        // non-secret parts survive so the log still names the datasource and root cause
        assertTrue(out, out.contains("Jdbc=jdbc:postgresql://warehouse.example/x"));
        assertTrue(out, out.contains("JdbcUser=bob"));
        assertTrue(out, out.contains("Mondrian Error:Internal error"));
    }

    @Test
    public void masksSingleQuotedJdbcPassword() {
        // quoteMondrianValue quotes values containing terminators, doubling inner quotes
        String out = ConnectStringRedactor.redact("JdbcPassword='p''a;s,s';JdbcUser=bob;");

        assertFalse(out, out.contains("p'"));
        assertTrue(out, out.contains("JdbcPassword=***;"));
        assertTrue(out, out.contains("JdbcUser=bob"));
    }

    @Test
    public void masksCaseVariantsOfPasswordKeys() {
        String out = ConnectStringRedactor.redact("PASSWORD=hunter2 Pwd=hunter2 passwd=hunter2&x=1");

        assertFalse(out, out.contains("hunter2"));
        assertTrue(out, out.contains("PASSWORD=*** Pwd=*** passwd=***&x=1"));
    }

    @Test
    public void leavesNonSecretKeysAlone() {
        // PasswordFile=/ PasswordRequired are not credentials; "password=" must be the whole key
        String text = "PasswordFile=/etc/shadow; PasswordRequired=true; JdbcUser=bob; Catalog=foodmart";
        assertEquals(text, ConnectStringRedactor.redact(text));
    }

    @Test
    public void masksUrlUserInfoPassword() {
        String out = ConnectStringRedactor.redact("jdbc:postgresql://bob:" + SECRET + "@warehouse.example:5432/db");

        assertFalse(out, out.contains(SECRET));
        assertTrue(out, out.contains("jdbc:postgresql://bob:***@warehouse.example:5432/db"));
    }

    @Test
    public void leavesBareUserInfoUserAlone() {
        // a user-info with no password is not the secret the issue is about
        assertEquals(
                "jdbc:postgresql://bob@warehouse.example/db",
                ConnectStringRedactor.redact("jdbc:postgresql://bob@warehouse.example/db"));
    }

    @Test
    public void nullAndEmptyAndCleanTextPassThrough() {
        assertNull(ConnectStringRedactor.redact((String) null));
        assertEquals("", ConnectStringRedactor.redact(""));
        assertEquals("no secrets here", ConnectStringRedactor.redact("no secrets here"));
    }

    @Test
    public void redactsWholeExceptionChain() {
        IllegalStateException top = new IllegalStateException(
                "Error connecting: foodmart",
                new RuntimeException(CONNECT_FAILURE, new java.sql.SQLException(
                        "jdbc:postgresql://bob:" + SECRET + "@warehouse.example/x")));

        Throwable out = ConnectStringRedactor.redact(top);

        // the original is untouched
        assertTrue(top.getCause().getMessage(), top.getCause().getMessage().contains(SECRET));
        // no level of the chain carries the secret
        assertFalse(out.getMessage(), String.valueOf(out.getMessage()).contains(SECRET));
        assertFalse(out.getCause().getMessage(), out.getCause().getMessage().contains(SECRET));
        assertFalse(
                out.getCause().getCause().getMessage(), out.getCause().getCause().getMessage().contains(SECRET));
        // the log still names the datasource and the root cause
        assertTrue(out.getMessage(), out.getMessage().contains("Error connecting: foodmart"));
        assertTrue(
                out.getCause().getCause().getMessage(),
                out.getCause().getCause().getMessage().startsWith("jdbc:postgresql://bob:***@"));

        // types survive (same class at each level)
        assertEquals(IllegalStateException.class, out.getClass());
        assertEquals(RuntimeException.class, out.getCause().getClass());
        assertEquals(java.sql.SQLException.class, out.getCause().getCause().getClass());
    }

    @Test
    public void redactedCopyKeepsStackTrace() {
        IllegalStateException top = new IllegalStateException("wrapper", new RuntimeException(CONNECT_FAILURE));

        Throwable out = ConnectStringRedactor.redact(top);

        assertEquals(top.getStackTrace().length, out.getStackTrace().length);
        assertEquals(top.getStackTrace()[0], out.getStackTrace()[0]);
        assertEquals(top.getCause().getStackTrace().length, out.getCause().getStackTrace().length);
    }

    @Test
    public void cleanExceptionIsReturnedAsIs() {
        IllegalStateException clean = new IllegalStateException("nothing secret");

        assertSame(clean, ConnectStringRedactor.redact(clean));
    }

    @Test
    public void redactsSuppressedExceptions() {
        RuntimeException top = new RuntimeException("top");
        top.addSuppressed(new RuntimeException(CONNECT_FAILURE));

        Throwable out = ConnectStringRedactor.redact(top);

        assertFalse(out.getSuppressed()[0].getMessage().contains(SECRET));
        assertTrue(out.getSuppressed()[0].getMessage().contains("JdbcPassword=***"));
    }

    @Test
    public void exceptionWithoutCopyableConstructorFallsBackToTypedWrapper() {
        // a private-class exception whose only constructor is no-arg — reflection cannot hand it
        // a redacted message, so the fallback wrapper must carry the type name instead
        RuntimeException weird = new WeirdException();

        Throwable out = ConnectStringRedactor.redact(weird);

        assertFalse(out.getMessage(), out.getMessage().contains(SECRET));
        // the failing type is preserved in the fallback wrapper's message
        assertTrue(out.getMessage(), out.getMessage().contains("WeirdException"));
        assertTrue(out.getMessage(), out.getMessage().contains("JdbcPassword=***"));
        assertEquals(weird.getStackTrace().length, out.getStackTrace().length);
    }

    @Test
    public void handlesNullMessagesAndSelfCause() {
        RuntimeException self = new RuntimeException();
        self.initCause(self);

        Throwable out = ConnectStringRedactor.redact(new IOException((String) null, self));
        assertNull(out.getMessage());
        assertEquals(IOException.class, out.getClass());
    }

    /** An exception type with no {@code String} or {@code (String, Throwable)} constructor. */
    private static final class WeirdException extends RuntimeException {
        private WeirdException() {
            super("JdbcPassword=" + SECRET + ";");
        }
    }
}
