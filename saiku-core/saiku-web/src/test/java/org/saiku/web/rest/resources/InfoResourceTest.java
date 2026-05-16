/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.saiku.service.PlatformUtilsService;
import org.saiku.service.util.dto.Plugin;

public class InfoResourceTest {

    @Test
    public void getAvailablePlugins_returns200WithPluginList() {
        PlatformUtilsService stub = new PlatformUtilsService() {
            @Override
            public ArrayList<Plugin> getAvailablePlugins() {
                ArrayList<Plugin> out = new ArrayList<>();
                out.add(new Plugin("alpha", "", "js/saiku/plugins/alpha/plugin.js"));
                return out;
            }
        };
        InfoResource r = new InfoResource();
        r.setPlatformUtilsService(stub);

        Response resp = r.getAvailablePlugins();
        assertEquals(200, resp.getStatus());
        // Response.ok(GenericEntity) unwraps the generic, so the entity is the
        // raw List. The GenericEntity wrapper is only used to carry the type.
        @SuppressWarnings("unchecked")
        List<Plugin> body = (List<Plugin>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("alpha", body.get(0).getName());
    }

    @Test
    public void getAvailablePlugins_emptyListStillReturns200() {
        PlatformUtilsService stub = new PlatformUtilsService() {
            @Override
            public ArrayList<Plugin> getAvailablePlugins() {
                return new ArrayList<>();
            }
        };
        InfoResource r = new InfoResource();
        r.setPlatformUtilsService(stub);

        Response resp = r.getAvailablePlugins();
        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<Plugin> body = (List<Plugin>) resp.getEntity();
        assertTrue(body.isEmpty());
    }
}
