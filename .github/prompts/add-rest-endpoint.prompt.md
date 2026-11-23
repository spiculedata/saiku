---
description: Add a JAX-RS REST endpoint to Saiku, wired through Spring XML, with tests
mode: agent
---

Add a REST endpoint: ${input:endpoint:method, path and purpose, e.g. "GET /ai/foo — list foos"}.

- **Resource:** a Jersey 3.1 class in `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/` using `jakarta.ws.rs.*`, never Spring `@RestController`. Add the method to an existing resource if the path belongs to one, e.g. `AiQueryResource` for `/ai/*`.
- **Wiring:** declare the bean in `saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml` with setter `<property>` injection (no `@Autowired`, no `@Configuration`). `SaikuJerseyApplication` registers every Spring bean annotated with `@Path` automatically, so there's no separate Jersey registration step. Request-scoped resources that touch session-scoped beans need `<aop:scoped-proxy/>`.
- **Security:** use `@RolesAllowed` for admin-only endpoints. Validate every input at the boundary. Never put exception messages, class names or SQL/MDX in responses; `GenericExceptionMapper` exists to prevent leaks, so return typed errors instead.
- **AI Query surface (`/ai/*`):** keep the typed contract: `VALIDATION_ERROR` 400s with `field` and `available` candidate lists, and never expose MDX. Update `docs/AI-QUERY-API.md` for contract changes.
- **Tests:** a resource test in `saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/`, plus a launcher IT if the endpoint needs a live cube. Bump the module's floor in `.github/test-floors.json` if you add tests.
- **Finish:** `mvn spotless:apply`, `mvn -pl saiku-core/saiku-web -am verify`, and a PR against `development` per AGENTS.md.
