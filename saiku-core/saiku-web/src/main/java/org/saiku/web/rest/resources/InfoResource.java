/*
 *   Copyright 2012 OSBI Ltd
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

import com.qmino.miredot.annotations.ReturnType;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.util.List;
import org.saiku.service.PlatformUtilsService;
import org.saiku.service.util.dto.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Info Resource to get platform information.
 */
@Path("/saiku/info")
@XmlAccessorType(XmlAccessType.NONE)
public class InfoResource {

    private static final Logger log = LoggerFactory.getLogger(InfoResource.class);

    private PlatformUtilsService platformService;

    // @Autowired
    public void setPlatformUtilsService(PlatformUtilsService ps) {
        this.platformService = ps;
    }

    /**
     * Get a list of available plugins.
     * @summary Get plugins
     * @return A response containing a list of plugins.
     */
    @GET
    @Produces({"application/json"})
    @ReturnType("java.util.List<Plugin>")
    public Response getAvailablePlugins() {

        GenericEntity<List<Plugin>> entity = new GenericEntity<List<Plugin>>(platformService.getAvailablePlugins()) {};
        return Response.ok(entity).build();
    }

    /**
     * Cheap HEAD for monitors / health checks.
     *
     * <p>saiku#866: without this, Jersey's auto-HEAD handler runs the
     * full GET (including the plugin directory walk in
     * {@link org.saiku.service.PlatformUtilsService#getAvailablePlugins()}),
     * then tries to strip the body. On the launcher the plugin dir is
     * often unset and {@code File.list} returns null, leaving the
     * response writer stalled on a Content-Length that never matches.
     * HEAD callers see a 30-second hang followed by a connection reset.
     */
    @HEAD
    public Response head() {
        return Response.ok().build();
    }
}
