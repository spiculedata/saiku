/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import mondrian.olap.MondrianServer;
import mondrian.olap.MondrianServer.MondrianVersion;
import org.junit.Test;

/**
 * {@link MondrianServer#forId(String)} returns a process-singleton default
 * server, so the stats endpoints always have a server to talk to even in
 * unit-test JVMs. Verify the resource surfaces a sensible body that wraps
 * the default server's monitor data.
 */
public class StatisticsResourceTest {

    private final StatisticsResource resource = new StatisticsResource();

    @Test
    public void getMondrianStats_returnsNonNullBodyAgainstDefaultServer() {
        MondrianStats stats = resource.getMondrianStats();
        assertNotNull(stats);
    }

    @Test
    public void getMondrianServer_returnsServerInfoFromDefaultServer() {
        assertNotNull(resource.getMondrianServer());
    }

    @Test
    public void getMondrianServerVersion_returnsRunningMondrianVersion() {
        MondrianVersion v = resource.getMondrianServerVersion();
        assertNotNull(v);
        assertNotNull("MondrianVersion.getVersionString must be non-null", v.getVersionString());
    }
}
