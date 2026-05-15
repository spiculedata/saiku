package org.saiku.web.rest;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class SaikuJerseyApplication extends ResourceConfig {

    public SaikuJerseyApplication(@Context ServletContext servletContext) {
        register(MultiPartFeature.class);
        // saiku#791: translate Jackson deserialisation failures into the
        // typed AiQueryResponse 400 envelope instead of the raw text/plain
        // Jackson message that leaked class names and source location.
        register(org.saiku.web.rest.exception.JacksonValidationExceptionMapper.class);

        ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
        for (String name : ctx.getBeanNamesForAnnotation(Path.class)) {
            if (name.startsWith("scopedTarget.")) {
                continue;
            }
            register(ctx.getBean(name));
        }
    }
}
