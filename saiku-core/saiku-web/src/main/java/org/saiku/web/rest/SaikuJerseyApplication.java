package org.saiku.web.rest;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class SaikuJerseyApplication extends ResourceConfig {

    public SaikuJerseyApplication(@Context ServletContext servletContext) {
        // saiku#806: disable Jersey's auto-WADL OPTIONS response. The auto
        // handler tries to marshal a com.sun.research.ws.wadl.Application
        // document via JAXB, but those classes aren't on the JAXBContext
        // we ship under Jakarta EE 10 — every OPTIONS request 500s out of
        // the auto-WADL marshaller. With this off, Jersey falls back to a
        // plain Allow-header response for OPTIONS, which is what every
        // standard JAX-RS client (and CORS preflight) expects.
        property("jersey.config.server.wadl.disableWadl", true);
        register(MultiPartFeature.class);
        // saiku#780 defence-in-depth: enable @RolesAllowed on JAX-RS resource
        // methods so admin-only mutations (e.g. DataSourceResource.deleteDatasource)
        // are blocked at the framework layer even if the Spring filter chain
        // is misconfigured. Without this dynamic feature Jersey silently
        // ignores @RolesAllowed annotations.
        register(RolesAllowedDynamicFeature.class);
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
