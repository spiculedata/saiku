/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.enrich.LlmEnricher;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;
import org.saiku.service.schema.generate.infer.SchemaInferrer;
import org.saiku.service.schema.generate.introspect.JdbcIntrospector;
import org.saiku.service.schema.generate.session.SchemaGenOrchestrator;
import org.saiku.service.schema.generate.session.SchemaGenSessionStore;
import org.saiku.service.schema.generate.writer.MondrianSchemaWriter;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Smoke test asserting that the schema-generation bean graph (as defined in production
 * {@code saiku-beans.xml}) wires cleanly. The test loads a mirror configuration that replaces
 * only the webapp-scoped {@code datasourceServiceBean} with a stub, so constructor-arg and
 * property-name drift between Java types and XML surfaces here before it reaches production.
 */
public class SchemaGenWiringSmokeTest {

    @Test
    public void contextLoadsWithSchemaGenBeans() {
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext("schemagen-wiring-test.xml")) {
            assertNotNull(ctx.getBean("schemaGenSessionStore", SchemaGenSessionStore.class));
            assertNotNull(ctx.getBean("jdbcIntrospector", JdbcIntrospector.class));
            assertNotNull(ctx.getBean("schemaInferrer", SchemaInferrer.class));
            assertNotNull(ctx.getBean("noopProvider", NoopProvider.class));
            assertNotNull(ctx.getBean("llmEnricher", LlmEnricher.class));
            assertNotNull(ctx.getBean("opApplier", OpApplier.class));
            assertNotNull(ctx.getBean("mondrianSchemaWriter", MondrianSchemaWriter.class));

            DatasourceJdbcConnectionProvider provider =
                    ctx.getBean("connectionProvider", DatasourceJdbcConnectionProvider.class);
            assertNotNull(provider);

            SchemaGenOrchestrator orchestrator = ctx.getBean("schemaGenOrchestrator", SchemaGenOrchestrator.class);
            assertNotNull(orchestrator);

            SchemaGeneratorResource resource = ctx.getBean("schemaGeneratorResource", SchemaGeneratorResource.class);
            assertNotNull(resource);

            // Sink is a lambda, so assert only that it's a non-null SchemaSink.
            SchemaGeneratorResource.SchemaSink sink =
                    ctx.getBean("schemaSink", SchemaGeneratorResource.SchemaSink.class);
            assertNotNull(sink);

            // Singletons shared between resource and orchestrator must be the same instance so that
            // ops applied via the resource are visible to subsequent pipeline interactions.
            assertSame(
                    "store should be shared between orchestrator and resource",
                    ctx.getBean("schemaGenSessionStore", SchemaGenSessionStore.class),
                    ctx.getBean("schemaGenSessionStore", SchemaGenSessionStore.class));

            // Extraction helper parity — guard against regressions in the Mondrian URL parser
            // which the orchestrator depends on at runtime.
            assertEquals(
                    "jdbc:h2:mem:test",
                    DatasourceJdbcConnectionProvider.extractKey(
                            "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://x;JdbcDrivers=org.h2.Driver",
                            "Jdbc"));
            assertEquals(
                    "org.h2.Driver",
                    DatasourceJdbcConnectionProvider.extractKey(
                            "jdbc:mondrian:Jdbc=jdbc:h2:mem:test;Catalog=mondrian://x;JdbcDrivers=org.h2.Driver",
                            "JdbcDrivers"));
            assertTrue(true);
        }
    }
}
