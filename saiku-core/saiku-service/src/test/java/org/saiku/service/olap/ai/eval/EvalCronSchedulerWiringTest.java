/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.support.CronExpression;

/**
 * saiku#1477 — the launcher-boot verification the issue asks for: prove the {@code evalScheduler}
 * bean <em>schedules</em> when {@code saiku.ai.eval.schedule.cron} is set, stays <em>off by
 * default</em> when it is not, and never breaks the Spring context in either case.
 *
 * <p>The definition under test is a copy of the one added to {@code saiku-beans.xml}, loaded through
 * a real Spring context, so the {@code factory-method} / {@code init-method} / {@code
 * destroy-method} wiring is exercised — the parts a pure unit test of {@link EvalCronScheduler}
 * can't see, and the parts that would take the whole app down at boot if they were wrong. The
 * collaborators are real objects (an in-memory store, a trivial ask service) rather than mocks, so
 * a signature drift in {@code forCron} fails here.
 */
public class EvalCronSchedulerWiringTest {

    private static final String PROP = "saiku.ai.eval.schedule.cron";

    /**
     * Copy of the evalScheduler definition in {@code saiku-beans.xml}, with the collaborators
     * replaced by beans this test can construct without a datasource. The property-placeholder line
     * is copied verbatim too — it is what makes {@code -Dsaiku.ai.eval.schedule.cron=...} visible.
     */
    private static final String BEANS_XML =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:context="http://www.springframework.org/schema/context"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans
                   http://www.springframework.org/schema/beans/spring-beans.xsd
                   http://www.springframework.org/schema/context
                   http://www.springframework.org/schema/context/spring-context.xsd">

              <context:property-placeholder
                      ignore-resource-not-found="true"
                      ignore-unresolvable="true"
                      system-properties-mode="OVERRIDE"/>

              <bean id="testAskService" class="org.saiku.service.olap.ai.eval.EvalCronSchedulerWiringTest$TestAskService"/>
              <bean id="testMetadataService" class="org.saiku.service.olap.ai.eval.EvalCronSchedulerWiringTest$TestMetadataService"/>
              <bean id="testThinQueryService" class="org.saiku.service.olap.ThinQueryService"/>
              <bean id="testEvalStore" class="org.saiku.service.olap.ai.eval.EvalResultStore">
                <constructor-arg value="jdbc:h2:mem:evalWiring;DB_CLOSE_DELAY=-1"/>
              </bean>

              <bean id="evalScheduler" class="org.saiku.service.olap.ai.eval.EvalCronScheduler"
                    factory-method="forCron" init-method="start" destroy-method="stop">
                <constructor-arg value="${saiku.ai.eval.schedule.cron:}"/>
                <constructor-arg value="${saiku.home:./saiku-home}/evals"/>
                <constructor-arg ref="testAskService"/>
                <constructor-arg ref="testMetadataService"/>
                <constructor-arg ref="testThinQueryService"/>
                <constructor-arg ref="testEvalStore"/>
              </bean>
            </beans>
            """;

    /** Boot the context with {@code saiku.ai.eval.schedule.cron} set to {@code cron} (null = unset). */
    private static GenericApplicationContext boot(String cron) {
        if (cron == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, cron);
        }
        try {
            GenericApplicationContext ctx = new GenericApplicationContext();
            XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(ctx);
            reader.loadBeanDefinitions(new ByteArrayResource(BEANS_XML.getBytes(StandardCharsets.UTF_8)));
            ctx.refresh();
            return ctx;
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    public void beanIsDefinedWithTheFactoryAndLifecycleTheBeansFileUses() {
        GenericApplicationContext ctx = boot(null);
        try {
            String[] names = ctx.getBeanNamesForType(EvalCronScheduler.class);
            assertEquals("expected exactly one evalScheduler bean", 1, names.length);
            assertEquals("evalScheduler", names[0]);

            BeanDefinition def = ctx.getBeanFactory().getBeanDefinition("evalScheduler");
            assertEquals("forCron", def.getFactoryMethodName());
            assertEquals("start", def.getInitMethodName());
            assertEquals("stop", def.getDestroyMethodName());
        } finally {
            ctx.close();
        }
    }

    @Test
    public void contextLoadsAndStaysInertWhenTheCronIsUnset() throws Exception {
        GenericApplicationContext ctx = boot(null);
        try {
            EvalCronScheduler scheduler = ctx.getBean(EvalCronScheduler.class);
            assertNotNull(scheduler);
            assertFalse("unset cron must not arm the scheduler", scheduler.isStarted());
            // Give a would-be scheduler time to misbehave before we call it a day.
            Thread.sleep(1_500L);
            assertFalse(scheduler.isStarted());
        } finally {
            ctx.close();
        }
    }

    @Test
    public void contextLoadsWithAGarbageCronWithoutFailing() throws Exception {
        // A typo in saiku-beans.properties must not fail the whole context refresh — that would take
        // the entire app down, not just the eval cron.
        GenericApplicationContext ctx = boot("every tuesday at noon-ish");
        try {
            EvalCronScheduler scheduler = ctx.getBean(EvalCronScheduler.class);
            assertFalse("an invalid cron must leave the scheduler inert", scheduler.isStarted());
            Thread.sleep(1_500L);
            assertFalse(scheduler.isStarted());
        } finally {
            ctx.close();
        }
    }

    @Test
    public void aConfiguredCronArmsTheSchedulerAtBoot() {
        GenericApplicationContext ctx = boot("0 30 3 * * *");
        try {
            EvalCronScheduler scheduler = ctx.getBean(EvalCronScheduler.class);
            assertEquals("0 30 3 * * *", scheduler.cronExpression());
            assertTrue("a configured cron must arm the scheduler at boot", scheduler.isStarted());
        } finally {
            ctx.close();
        }
    }

    @Test
    public void destroyMethodStopsTheSchedulerOnContextClose() {
        GenericApplicationContext ctx = boot("0 30 3 * * *");
        EvalCronScheduler scheduler = ctx.getBean(EvalCronScheduler.class);
        assertTrue(scheduler.isStarted());
        ctx.close();
        assertFalse("context close must stop the cron thread (destroy-method=stop)", scheduler.isStarted());
    }

    /**
     * The lifecycle assertion that matters most for "does it actually schedule": the wired scheduler
     * must run a real sweep through the {@code forCron} factory's own adapter/runner, not just spin
     * a thread. Driven directly via {@code fireOnce} (the exact body the cron thread executes)
     * rather than waiting for 03:30 — with no suites on disk the sweep is a no-op, but it must not
     * throw, which means the adapter and runner were built correctly.
     */
    @Test
    public void theWiredSchedulerRunsItsSweepWithoutThrowing() {
        GenericApplicationContext ctx = boot("0 30 3 * * *");
        try {
            EvalCronScheduler scheduler = ctx.getBean(EvalCronScheduler.class);
            scheduler.fireOnce(CronExpression.parse("0 30 3 * * *"));
            assertTrue("a sweep must re-arm the cron", scheduler.isStarted());
        } finally {
            ctx.close();
        }
    }

    // ---- collaborators the wiring needs, without a datasource ----

    /** An ask service over a provider that refuses everything — the sweep degrades, never throws. */
    public static class TestAskService extends AiAskService {
        public TestAskService() {
            super(new TestMetadataService(), new NlAskProvider() {
                @Override
                public NlAskResponse ask(NlAskRequest request) {
                    return NlAskResponse.degraded("no provider in the wiring test");
                }
            });
        }
    }

    /** Metadata service that knows no cubes — every case degrades rather than explodes. */
    public static class TestMetadataService implements AiCubeMetadataService {
        @Override
        public AiSchema getSchema(AiCubeRef ref) {
            throw new IllegalStateException("no schema in the wiring test");
        }
    }
}
