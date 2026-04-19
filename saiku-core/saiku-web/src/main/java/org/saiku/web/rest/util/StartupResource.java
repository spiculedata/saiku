package org.saiku.web.rest.util;

import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StartupResource {

    private static final Logger log = LoggerFactory.getLogger(StartupResource.class);

    public void init() {
        try {
            java.util.logging.Logger.getLogger("org.glassfish.jersey.servlet.WebComponent")
                    .setLevel(Level.SEVERE);
        } catch (Exception e) {
            log.error("Failed to adjust Jersey WebComponent log level", e);
        }
    }
}
