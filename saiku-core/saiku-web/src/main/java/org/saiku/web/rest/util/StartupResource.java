package org.saiku.web.rest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup hook. Previously used to silence Jersey 1.x logging; Jersey has been
 * removed in favour of Spring MVC so this is now a no-op kept for backwards
 * compatibility with the existing Spring bean wiring in saiku-beans.xml.
 */
public class StartupResource {

    private static final Logger log = LoggerFactory.getLogger(StartupResource.class);

    public void init() {
        log.debug("StartupResource.init() invoked (no-op after Jersey removal).");
    }
}
