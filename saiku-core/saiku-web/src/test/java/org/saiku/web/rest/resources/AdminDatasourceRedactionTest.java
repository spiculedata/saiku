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
package org.saiku.web.rest.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.DataSourceMapper;

/** The admin datasource failure responses must not echo a warehouse password. */
public class AdminDatasourceRedactionTest {
    private static final String SECRET = "correct horse battery staple";
    private static final String FAILURE = "Error while creating SQL connection: Jdbc=jdbc:postgresql://host/x; "
            + "JdbcUser=bob; JdbcPassword=" + SECRET + "; Catalog=foodmart";

    private AdminResource resource;

    @Before
    public void setUp() {
        resource = new AdminResource();
        resource.setUserService(new UserService() {
            @Override
            public boolean isAdmin() {
                return true;
            }

            @Override
            public String[] getCurrentUserRoles() {
                return new String[] {"ROLE_ADMIN"};
            }
        });
        resource.setDatasourceService(new DatasourceService() {
            @Override
            public void addDatasource(SaikuDatasource datasource, boolean overwrite, String[] roles) {
                throw new SaikuServiceException(FAILURE);
            }
        });
        resource.setOlapDiscoverService(new OlapDiscoverService() {
            @Override
            public void refreshConnection(String name) {
                throw new SaikuServiceException(FAILURE);
            }
        });
    }

    @Test
    public void createFailureRedactsResponse() {
        assertRedacted(resource.createDatasource(mapper()));
    }

    @Test
    public void updateFailureRedactsResponse() {
        assertRedacted(resource.updateDatasource(mapper(), "foodmart"));
    }

    @Test
    public void refreshFailureRedactsResponse() {
        assertRedacted(resource.refreshDatasource("foodmart"));
    }

    private static DataSourceMapper mapper() {
        return new DataSourceMapper() {
            @Override
            public SaikuDatasource toSaikuDataSource() {
                return null;
            }
        };
    }

    private static void assertRedacted(Response response) {
        assertEquals(500, response.getStatus());
        String body = String.valueOf(response.getEntity());
        assertFalse(body, body.contains(SECRET));
        assertTrue(body, body.contains("JdbcUser=bob; JdbcPassword=***; Catalog=foodmart"));
        assertTrue(body, body.contains("Error while creating SQL connection"));
    }
}
