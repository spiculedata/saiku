/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.lineage;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.user.UserService;

/** Unit test for {@link LineageResource}'s request handling, see saiku#1120. */
public class LineageResourceTest {

    private LineageResource resource;

    @Before
    public void setUp() {
        resource = new LineageResource();
        resource.setLineageService(new MeasureLineageService() {
            @Override
            public List<LineageDependent> findDependents(String uniqueName, String username, List<String> roles) {
                return List.of(new LineageDependent("saved-query", "q1", "/queries/q1.saiku", 1000L));
            }
        });
        resource.setUserService(new StubUserService());
    }

    @Test
    public void missingMeasureParamReturns400() {
        Response r = resource.find(null);
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("VALIDATION_ERROR", body.get("status"));
    }

    @Test
    public void blankMeasureParamReturns400() {
        Response r = resource.find("   ");
        assertEquals(400, r.getStatus());
    }

    @Test
    public void validMeasureReturnsDependents() {
        Response r = resource.find("[Measures].[Store Sales]");
        assertEquals(200, r.getStatus());
        @SuppressWarnings("unchecked")
        List<LineageDependent> body = (List<LineageDependent>) r.getEntity();
        assertEquals(1, body.size());
        assertEquals("saved-query", body.get(0).kind);
    }

    private static final class StubUserService extends UserService {
        @Override
        public String getActiveUsername() {
            return "admin";
        }

        @Override
        public String[] getCurrentUserRoles() {
            return new String[] {"ROLE_ADMIN"};
        }
    }
}
