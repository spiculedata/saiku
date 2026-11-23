/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import mondrian.xmla.XmlaServlet;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.util.exception.SaikuAccessDeniedException;

/**
 * saiku#1973 (CWE-863) — a {@link SaikuAccessDeniedException} (the saiku#1968 fail-closed
 * datasource denial) must reach the XMLA client as a CLEAN {@code Client}-class SOAP fault with a
 * generic description.
 *
 * <p>Unfixed, the denial travelled as a bare {@code RuntimeException} into
 * {@code DefaultXmlaServlet.handleFault}, which takes the root-cause message and writes it into
 * {@code SOAP-ENV:Fault/detail/XA:error/desc} — echoing the datasource name back — under a
 * {@code Server} faultcode, which tells every client-side retry/transient-error policy that the
 * request is worth sending again to a healthy server.
 *
 * <p>These tests drive the REAL override (not a re-implementation) through a capturing
 * {@link HttpServletResponse} proxy, so what is asserted is the fault XML a client actually receives.
 */
public class SaikuXmlaServletAccessDeniedFaultTest {

    /** Captures the SOAP fault bytes the inherited upstream renderer writes. */
    private static final class Capturing implements InvocationHandler {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int status = -1;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getOutputStream":
                    return new ServletOutputStream() {
                        @Override
                        public void write(int b) {
                            body.write(b);
                        }

                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener writeListener) {}
                    };
                case "setStatus":
                    status = (Integer) args[0];
                    return null;
                case "getCharacterEncoding":
                    return "UTF-8";
                case "toString":
                    return "CapturingResponse";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    // reset / setContentType / setCharacterEncoding / flushBuffer / …
                    return null;
            }
        }
    }

    private Capturing response;

    @Before
    public void setUp() {
        response = new Capturing();
    }

    private HttpServletResponse servletResponse() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class}, response);
    }

    private String renderFault(Throwable thrown) {
        // handleFault renders the SOAP fault back into the parsed request parts (the caller writes
        // them to the response), so that array is what we assert on.
        byte[][] parts = new byte[2][];
        new SaikuXmlaServlet().handleFault(servletResponse(), parts, XmlaServlet.Phase.PROCESS_BODY, thrown);
        for (byte[] part : parts) {
            if (part != null) {
                return new String(part, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @Test
    public void anAccessDeniedIsRenderedAsAClientClassFault() {
        String fault = renderFault(new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"sales_prod\"."));

        assertTrue("expected a Client faultcode in: " + fault, fault.contains("Client"));
        assertFalse("a denial is not a server fault — clients retry on Server: " + fault, fault.contains("Server"));
    }

    @Test
    public void theFaultCarriesTheSaikuAccessDeniedCode() {
        String fault = renderFault(new SaikuAccessDeniedException("Access denied"));

        assertTrue("expected the access-denied fault code in: " + fault, fault.contains("00USMD01"));
    }

    /** The whole point: the datasource name never crosses the wire. */
    @Test
    public void theFaultDoesNotEchoTheDeniedDatasource() {
        String fault = renderFault(new SaikuAccessDeniedException(
                "Access denied: your account is not granted any role on datasource \"sales_prod\"."));

        assertFalse("datasource name leaked into the SOAP fault: " + fault, fault.contains("sales_prod"));
        assertTrue("a generic description is still present: " + fault, fault.contains("Access denied"));
        assertTrue("a SOAP fault was actually rendered: " + fault, fault.contains("SOAP-ENV:Fault"));
    }

    /** A rewrapped denial (connection factory / proxy) must still be recognised. */
    @Test
    public void aRewrappedAccessDeniedIsStillAClientFault() {
        String fault = renderFault(new RuntimeException(
                "outer",
                new IllegalStateException("middle", new SaikuAccessDeniedException("Access denied: ... sales_prod"))));

        assertTrue("expected a Client fault in: " + fault, fault.contains("Client"));
        assertFalse("datasource name leaked: " + fault, fault.contains("sales_prod"));
    }

    /** Non-denial faults are untouched — an ordinary internal error is still a Server fault. */
    @Test
    public void anOrdinaryFaultStillRendersAsAServerFault() {
        String fault = renderFault(new IllegalStateException("connection pool exhausted"));

        assertTrue("expected an upstream-rendered fault in: " + fault, fault.contains("SOAP-ENV:Fault"));
        assertTrue("an internal fault stays a Server fault: " + fault, fault.contains("Server"));
        assertTrue(
                "the ordinary fault message is still surfaced as before: " + fault,
                fault.contains("connection pool exhausted"));
    }

    /** A self-referencing cause chain must not spin the denial walk. */
    @Test
    public void aSelfReferentialCauseChainTerminates() {
        RuntimeException loop = new IllegalStateException("boom");
        SaikuAccessDeniedException denied = new SaikuAccessDeniedException("Access denied: ... sales_prod");
        RuntimeException wrapper = new RuntimeException("outer", loop);
        loop.initCause(denied);
        denied.initCause(wrapper);

        String fault = renderFault(loop);

        assertFalse("datasource name leaked: " + fault, fault.contains("sales_prod"));
        assertTrue("the loop still produced a fault: " + fault, fault.contains("SOAP-ENV:Fault"));
    }
}
