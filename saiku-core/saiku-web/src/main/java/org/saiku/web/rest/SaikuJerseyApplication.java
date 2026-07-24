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
        // saiku#903: ai.policy violations -> typed 403 PERMISSION_DENIED envelope
        // (specific mapper wins over the catch-all Throwable mapper below).
        register(org.saiku.web.rest.exception.AiPolicyViolationMapper.class);
        // saiku#1165 (audit-3): global catch-all mapper. Supersedes the
        // saiku#865 GenericFailureExceptionMapper — JAX-RS allows only one
        // ExceptionMapper<Throwable>, so this single mapper carries the whole
        // contract: it still translates unguarded NPEs / missing-param
        // failures into a 400 JSON envelope (preserving saiku#865 behaviour)
        // AND stops any resource from leaking Mondrian/SQL/path/class
        // internals — unexpected throwables become an information-free 500
        // {status:"ERROR", error:"Internal error", ref:<uuid>} with the full
        // stack logged server-side under the ref. WebApplicationException and
        // AiValidationException pass through with their typed envelopes intact.
        register(org.saiku.web.rest.util.GenericExceptionMapper.class);

        // F2: pre-parse request-size ceiling for POST /saiku/api/email/self. A
        // @PreMatching filter that rejects an oversized body by its Content-Length
        // with 413 BEFORE Jackson materialises it in heap. Dependency-free, so it's
        // registered as a class (like the exception mappers above); it scopes itself
        // to the self-send path internally and leaves every other endpoint untouched.
        register(org.saiku.web.email.EmailRequestSizeFilter.class);

        ApplicationContext ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
        for (String name : ctx.getBeanNamesForAnnotation(Path.class)) {
            if (name.startsWith("scopedTarget.")) {
                continue;
            }
            register(ctx.getBean(name));
        }

        // saiku#906: the AI audit filter writes one record per /ai/* call. It's
        // a JAX-RS provider, not a @Path resource, so the scan above misses it —
        // register it explicitly. Guarded so a context without it still boots.
        if (ctx.containsBean("aiAuditFilter")) {
            register(ctx.getBean("aiAuditFilter"));
        }
    }
}
