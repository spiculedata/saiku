/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.datasources.connection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.olap4j.OlapConnection;
import org.olap4j.OlapWrapper;
import org.olap4j.metadata.NamedList;

/**
 * A catch-all {@link Driver} for the saiku#1902 chokepoint tests. It accepts <em>every</em>
 * {@code jdbc:} URL and counts how often {@link DriverManager} asked it to connect, so a test can
 * prove that a rejected URL never reached any driver at all (count stays 0) and that a permitted
 * one did (count 1). Registered drivers are consulted in registration order, so the real H2 /
 * HSQLDB drivers still win for their own URLs — this one only sees what nobody else claimed.
 *
 * <p>{@link #connect} hands back a JDK-proxy {@link Connection} that also implements
 * {@link OlapWrapper} and unwraps to an empty {@link OlapConnection}, which is exactly enough for
 * {@code SaikuOlapConnection.connect()} to report success without a real OLAP backend.
 */
public final class RecordingJdbcDriver implements Driver {

    public final AtomicInteger connectCalls = new AtomicInteger();

    public volatile String lastUrl;

    @Override
    public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) {
            return null;
        }
        connectCalls.incrementAndGet();
        lastUrl = url;
        return olapWrapperConnection();
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    private static Connection olapWrapperConnection() {
        ClassLoader loader = RecordingJdbcDriver.class.getClassLoader();
        Object olap = Proxy.newProxyInstance(loader, new Class<?>[] {OlapConnection.class}, new Stub("olap"));
        return (Connection) Proxy.newProxyInstance(
                loader, new Class<?>[] {Connection.class, OlapWrapper.class}, new Stub("jdbc", olap));
    }

    /** Minimal behaviour: unwrap to the olap proxy, report open, empty catalog list, defaults elsewhere. */
    private static final class Stub implements InvocationHandler {

        private final String name;
        private final Object unwrapped;

        Stub(String name) {
            this(name, null);
        }

        Stub(String name, Object unwrapped) {
            this.name = name;
            this.unwrapped = unwrapped;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "unwrap":
                    return unwrapped != null ? unwrapped : proxy;
                case "isWrapperFor":
                    return true;
                case "isClosed":
                    return false;
                case "getOlapCatalogs":
                    return Proxy.newProxyInstance(
                            RecordingJdbcDriver.class.getClassLoader(),
                            new Class<?>[] {NamedList.class},
                            new EmptyList());
                case "toString":
                    return "recording-" + name + "-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private static final class EmptyList implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "size":
                    return 0;
                case "isEmpty":
                    return true;
                case "iterator":
                    return Collections.emptyIterator();
                case "toString":
                    return "[]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
