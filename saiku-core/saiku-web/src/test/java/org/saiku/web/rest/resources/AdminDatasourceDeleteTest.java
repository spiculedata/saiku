/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

/**
 * saiku#2533: DELETE /rest/saiku/admin/datasources/{id} used to answer 200 regardless of what
 * actually happened — an unknown id, even a descriptor that could not be removed. An admin
 * revoking warehouse credentials was told it worked while the credentials stayed live.
 *
 * <p>The contract now is: 200 only when the datasource really went away, 404 for an unknown id,
 * and a 5xx typed error when the removal cannot be completed. Drives {@link AdminResource}
 * against a stubbed {@link DatasourceService} exactly the way AdminResourceUserPasswordTest
 * drives it against a stubbed UserService.
 */
public class AdminDatasourceDeleteTest {

    private AdminResource resource;
    private StubUserService users;
    private StubDatasourceService datasourceService;

    @Before
    public void setUp() {
        users = new StubUserService();
        datasourceService = new StubDatasourceService();

        resource = new AdminResource();
        resource.setUserService(users);
        resource.setDatasourceService(datasourceService);
    }

    @Test
    public void successfulDelete_answers200_withTheRemainingList() {
        datasourceService.removeResult = Boolean.TRUE;

        Response resp = resource.deleteDatasource("foodmart-id");

        assertEquals(200, resp.getStatus());
        assertFalse(
                "the remaining datasource list must not carry the deleted datasource",
                String.valueOf(resp.getEntity()).contains("deleted-2533"));
    }

    @Test
    public void unknownId_answers404_not200() {
        datasourceService.removeResult = Boolean.FALSE;

        Response resp = resource.deleteDatasource("no-such-id");

        assertEquals("an id that matches nothing must answer 404, never a fake 200", 404, resp.getStatus());
    }

    @Test
    public void failedRemoval_answers5xx_withTheTypedError() {
        datasourceService.removeThrows = new SaikuServiceException("Could not delete the datasource descriptor file");

        Response resp = resource.deleteDatasource("foodmart-id");

        assertEquals("a delete that cannot complete must never answer 200", 500, resp.getStatus());
        assertTrue(String.valueOf(resp.getMediaType()).contains("text/plain"));
        assertTrue(resp.getEntity().toString().contains("Could not delete the datasource descriptor file"));
    }

    @Test
    public void nonAdmin_isForbidden() {
        users.admin = false;

        Response resp = resource.deleteDatasource("foodmart-id");

        assertEquals(403, resp.getStatus());
        assertFalse(
                "a forbidden caller learns nothing about the datasource set",
                String.valueOf(resp.getEntity()).contains("deleted-2533"));
    }

    /* ------------------------------ stubs ------------------------------ */

    private static SaikuDatasource ds(String name, String id) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("id", id);
        props.setProperty("driver", "mondrian.olap4j.MondrianOlap4jDriver");
        props.setProperty("location", "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://x.xml;");
        return new SaikuDatasource(name, SaikuDatasource.Type.OLAP, props);
    }

    /** Answers whatever removeDatasource is told to report (or throws), and serves one survivor. */
    private static final class StubDatasourceService extends DatasourceService {
        Boolean removeResult = Boolean.TRUE;
        SaikuServiceException removeThrows = null;

        @Override
        public boolean removeDatasource(String datasourceId) {
            if (removeThrows != null) {
                throw removeThrows;
            }
            return removeResult;
        }

        @Override
        public Map<String, SaikuDatasource> getDatasources(String[] roles) {
            Map<String, SaikuDatasource> remaining = new LinkedHashMap<>();
            remaining.put("foodmart", ds("foodmart", "foodmart-id"));
            return remaining;
        }
    }

    private static final class StubUserService extends UserService {
        boolean admin = true;

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public String[] getCurrentUserRoles() {
            return admin ? new String[] {"ROLE_ADMIN"} : new String[] {"ROLE_USER"};
        }
    }
}
