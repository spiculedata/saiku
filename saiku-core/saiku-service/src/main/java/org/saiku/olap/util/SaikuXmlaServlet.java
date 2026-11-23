/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.olap.util;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import mondrian.xmla.XmlaException;
import mondrian.xmla.XmlaHandler;
import mondrian.xmla.XmlaHandler.ConnectionFactory;
import mondrian.xmla.XmlaHandler.Request;
import mondrian.xmla.XmlaHandler.XmlaExtra;
import mondrian.xmla.XmlaRequest;
import mondrian.xmla.XmlaServlet;
import mondrian.xmla.XmlaUtil;
import mondrian.xmla.impl.Olap4jXmlaServlet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.impl.Olap4jUtil;
import org.olap4j.metadata.Database;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.util.exception.SaikuAccessDeniedException;
import org.saiku.service.util.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Created by bugg on 30/03/15.
 */
public class SaikuXmlaServlet extends Olap4jXmlaServlet {

    private static final Logger log = LoggerFactory.getLogger(SaikuXmlaServlet.class);
    private static IConnectionManager connections;

    public SaikuXmlaServlet() {
        super();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        ServletContext context = getServletContext();

        WebApplicationContext applicationContext = WebApplicationContextUtils.getWebApplicationContext(context);
        connections = (IConnectionManager) applicationContext.getBean("connectionManager");
    }

    // SOAP envelope namespace, mirrored from mondrian.xmla.XmlaConstants (not re-exported
    // as a public constant we can reference here).
    private static final String NS_SOAP_ENV_1_1 = "http://schemas.xmlsoap.org/soap/envelope/";

    /** saiku#1973 — fault code for an authenticated-but-unauthorised datasource request. */
    private static final String ACCESS_DENIED_CODE = "00USMD01";

    /**
     * saiku#1973 (CWE-863) — render an access-denied as a CLEAN {@code Client} fault.
     *
     * <p>A {@link SaikuAccessDeniedException} (saiku#1968 fail-closed denial — the caller's roles
     * resolve to no Mondrian role on the datasource they named, and they are not an admin) is the
     * client's problem, not a server fault. Unfixed, it reached {@code
     * DefaultXmlaServlet.handleFault} as a raw {@code RuntimeException}, which the fork renders as a
     * {@code Server}-class fault whose {@code desc} is {@code getMessage()} — i.e. the denial text,
     * datasource name and all, was echoed back inside {@code SOAP-ENV:Fault/detail/XA:error/desc},
     * and a {@code Server} fault tells an XMLA client (and every client-side retry/transient-error
     * policy built on it) that the failure is worth retrying against a healthy server.
     *
     * <p>This override re-labels the denial as a {@code Client}-class fault with a fixed,
     * information-free description before delegating to the parent renderer, so the whole SOAP
     * envelope (faultcode, faultstring, detail, code) is produced by the unchanged upstream code
     * path. The original denial message is logged server-side and never leaves the JVM.
     *
     * <p>Only denials are rewritten; every other fault — including other {@code RuntimeException}s —
     * renders exactly as before.
     */
    @Override
    protected void handleFault(
            HttpServletResponse response, byte[][] requestSoapParts, XmlaServlet.Phase phase, Throwable exception) {
        SaikuAccessDeniedException denied = findAccessDenied(exception);
        if (denied == null) {
            super.handleFault(response, requestSoapParts, phase, exception);
            return;
        }
        log.warn("XMLA access denied on datasource [detail={}]", denied.getMessage());
        // The CAUSE is a sanitised copy on purpose: {@code XmlaException.getDetail()} — which is
        // what the renderer writes into XA:error/desc — is the ROOT CAUSE's message, so handing it
        // the original denial would put the datasource name straight back on the wire. (A null
        // cause is not an option either: getDetail() then walks a null chain and the whole fault
        // render dies into the upstream catch-all.)
        super.handleFault(
                response,
                requestSoapParts,
                phase,
                new XmlaException(
                        "Client",
                        ACCESS_DENIED_CODE,
                        "Access denied",
                        new SaikuAccessDeniedException("Access denied")));
    }

    /**
     * Walk the cause chain for a {@link SaikuAccessDeniedException}. By exception TYPE, never by
     * message text. The walk is needed because the denial can be rewrapped on its way out of the
     * connection factory before the fault is rendered.
     */
    private static SaikuAccessDeniedException findAccessDenied(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SaikuAccessDeniedException denied) {
                return denied;
            }
        }
        return null;
    }

    /**
     * XXE fix (saiku#1905, CWE-611). The fork's {@code DefaultXmlaServlet.unmarshallSoapMessage}
     * parses the raw client SOAP body with an un-hardened {@code DocumentBuilderFactory} (DOCTYPE
     * and external entities allowed), which lets an authenticated XMLA client read any file the JVM
     * can (e.g. {@code conf/secret.key}, {@code users.properties}), perform SSRF, or DoS via entity
     * expansion.
     *
     * <p>This override reproduces the parent's contract EXACTLY — same request-input handling, same
     * SOAP-envelope validation, same {@link XmlaException} fault codes, and the same population of
     * {@code requestSoapParts} ({@code [0]} = Header or {@code null}, {@code [1]} = Body) — but swaps
     * the parser for {@link SecureXml#secureDocumentBuilder()}, which disallows DOCTYPE declarations,
     * disables external general/parameter entities and enables secure processing. Behaviour is
     * identical for a well-formed (no-DOCTYPE) request; only DOCTYPE / external-entity inputs are now
     * rejected (as a client-side SAX parse error), so no legitimate XMLA client is affected.
     *
     * <p>This is the sole request-body DOM parse in the servlet chain — {@code XmlaUtil}'s parse
     * helpers are not reached with client-controlled input in the request flow — so hardening here
     * closes the XXE surface without any fork change.
     */
    @Override
    protected void unmarshallSoapMessage(HttpServletRequest request, Element[] requestSoapParts) throws XmlaException {
        try {
            InputStream inputStream;
            try {
                inputStream = request.getInputStream();
            } catch (IllegalStateException ex) {
                throw new XmlaException("Server", "00USMA01", "Request input method invoked at illegal time", ex);
            } catch (IOException ex) {
                throw new XmlaException("Server", "00USMA02", "Request input Exception occurred", ex);
            }

            DocumentBuilder domBuilder;
            try {
                domBuilder = SecureXml.secureDocumentBuilder();
            } catch (ParserConfigurationException ex) {
                throw new XmlaException(
                        "Server",
                        "00USMB01",
                        "DocumentBuilder cannot be created which satisfies the configuration requested",
                        ex);
            }

            Document soapDoc;
            try {
                soapDoc = domBuilder.parse(new InputSource(inputStream));
            } catch (IOException ex) {
                throw new XmlaException("Server", "00USMC01", "DOM parse IO errors occur", ex);
            } catch (SAXException ex) {
                // A DOCTYPE / external-entity payload trips disallow-doctype-decl and lands here,
                // exactly like any other malformed SOAP request would.
                throw new XmlaException("Client", "00USMC02", "DOM parse errors occur", ex);
            }

            Element envElem = soapDoc.getDocumentElement();

            if (log.isDebugEnabled()) {
                logXmlaRequest(envElem);
            }

            if ("Envelope".equals(envElem.getLocalName())) {
                if (!NS_SOAP_ENV_1_1.equals(envElem.getNamespaceURI())) {
                    throw new XmlaException(
                            "Client",
                            "00USMC02",
                            "DOM parse errors occur",
                            new SAXException("Invalid SOAP message: Envelope element not in SOAP namespace"));
                }
            } else {
                throw new XmlaException(
                        "Client",
                        "00USMC02",
                        "DOM parse errors occur",
                        new SAXException("Invalid SOAP message: Top element not Envelope"));
            }

            Element[] childs = XmlaUtil.filterChildElements(envElem, NS_SOAP_ENV_1_1, "Header");
            if (childs.length > 1) {
                throw new XmlaException(
                        "Client",
                        "00USMC02",
                        "DOM parse errors occur",
                        new SAXException("Invalid SOAP message: More than one Header elements"));
            }
            requestSoapParts[0] = childs.length == 1 ? childs[0] : null;

            childs = XmlaUtil.filterChildElements(envElem, NS_SOAP_ENV_1_1, "Body");
            if (childs.length != 1) {
                throw new XmlaException(
                        "Client",
                        "00USMC02",
                        "DOM parse errors occur",
                        new SAXException("Invalid SOAP message: Does not have one Body element"));
            }
            requestSoapParts[1] = childs[0];
        } catch (XmlaException xex) {
            throw xex;
        } catch (Exception ex) {
            throw new XmlaException("Server", "00USMU01", "Unknown error unmarshalling soap message", ex);
        }
    }

    @Override
    protected ConnectionFactory createConnectionFactory(final ServletConfig servletConfig) throws ServletException {
        return new ConnectionFactory() {
            private final XmlaHandler.XmlaExtra extra = new SaikuXmlaExtraImpl();

            public OlapConnection getConnection(String s, String s1, String s2, Properties properties)
                    throws SQLException {
                try {
                    // connections.refreshAllConnections();
                    if (s != null) {
                        for (Map.Entry<String, OlapConnection> entry :
                                connections.getAllOlapConnections().entrySet()) {
                            if (entry.getKey().toLowerCase().equals(s.toLowerCase())) {
                                return borrow(entry.getValue());
                            }
                        }
                        return borrow(connections.getOlapConnection(s));
                    } else {
                        for (Map.Entry<String, OlapConnection> entry :
                                connections.getAllOlapConnections().entrySet()) {
                            return borrow(entry.getValue());
                        }
                    }

                } catch (SaikuOlapException e) {
                    log.error("XMLA discover failed", e);
                }
                return null;
            }

            /**
             * saiku#1969: hand the XMLA handler a BORROWED view of the cached connection.
             *
             * <p>The connection comes from {@link IConnectionManager}, which caches and shares it
             * with every other caller resolving to the same cache key — and the XMLA fork closes the
             * connection it is given on the way out of every query, success or failure. Wrapping it
             * in {@link NonClosingOlapConnection} keeps that {@code close()} a no-op, so a bad MDX
             * over {@code /xmla} can no longer take the shared connection down for the REST path and
             * for other XMLA callers. Identity is also broken here, which is the point: the handler
             * must never hold the object the cache owns.
             */
            private OlapConnection borrow(OlapConnection cached) {
                return cached == null ? null : new NonClosingOlapConnection(cached);
            }

            public Map<String, Object> getPreConfiguredDiscoverDatasourcesResponse() {

                return null; // getDataSources();
            }

            public Request startRequest(XmlaRequest xmlaRequest, OlapConnection olapConnection) {
                return null;
            }

            public void endRequest(Request request) {}

            public XmlaExtra getExtra() {
                return extra;
            }
        };
    }

    private Map<String, Object> getDataSources() {
        List<Map<String, Object>> lret = new ArrayList<>();
        try {
            Map<String, OlapConnection> conns = connections.getAllOlapConnections();
            for (Map.Entry<String, OlapConnection> c : conns.entrySet()) {
                Database olapDb = c.getValue().getOlapDatabase();
                final String modes = createCsv(olapDb.getAuthenticationModes());
                final String providerTypes = createCsv(olapDb.getProviderTypes());
                List<Map<String, Object>> l = Collections.singletonList(Olap4jUtil.mapOf(
                        "DataSourceName", (Object) c.getKey(),
                        "DataSourceDescription", olapDb.getDescription(),
                        "URL", olapDb.getURL(),
                        "DataSourceInfo", olapDb.getDataSourceInfo(),
                        "ProviderName", olapDb.getProviderName(),
                        "ProviderType", providerTypes,
                        "AuthenticationMode", modes));
                return l.get(0);
                // lret.addAll(l);
            }

        } catch (Exception e) {

        }
        return null;
        // return lret;
    }

    private static class SaikuXmlaExtraImpl extends XmlaHandler.XmlaExtraImpl {

        @Override
        public List<Map<String, Object>> getDataSources(OlapConnection connection) throws OlapException {
            List<Map<String, Object>> lret = new ArrayList<>();
            try {
                Map<String, OlapConnection> conns = connections.getAllOlapConnections();
                for (Map.Entry<String, OlapConnection> c : conns.entrySet()) {
                    Database olapDb = c.getValue().getOlapDatabase();
                    final String modes = createCsv(olapDb.getAuthenticationModes());
                    final String providerTypes = createCsv(olapDb.getProviderTypes());
                    List<Map<String, Object>> l = Collections.singletonList(Olap4jUtil.mapOf(
                            "DataSourceName", (Object) c.getKey(),
                            "DataSourceDescription", olapDb.getDescription(),
                            "URL", olapDb.getURL(),
                            "DataSourceInfo", olapDb.getDataSourceInfo(),
                            "ProviderName", olapDb.getProviderName(),
                            "ProviderType", providerTypes,
                            "AuthenticationMode", modes));
                    lret.addAll(l);
                }

                return lret;
            } catch (SaikuOlapException e) {
                log.error("XMLA datasource enumeration failed", e);
            }

            return null;
        }
    }

    private static String createCsv(Iterable<? extends Object> iterable) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object o : iterable) {
            if (!first) {
                sb.append(',');
            }
            sb.append(o);
            first = false;
        }
        return sb.toString();
    }
}
