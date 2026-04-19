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
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import org.saiku.service.PlatformUtilsService;
import org.saiku.service.util.dto.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Info Resource to get platform information.
 */
@Component
@RestController
@RequestMapping("/saiku/info")
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
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<Plugin>")
    public ResponseEntity<List<Plugin>> getAvailablePlugins() {
        return ResponseEntity.ok(platformService.getAvailablePlugins());
    }
}
