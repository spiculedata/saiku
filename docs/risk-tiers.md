# Change risk tiers

Every PR is classified into one of three risk tiers from the paths it changes. The tier tells the author and the reviewer how much care the change needs; it does not block anything.

The classifier is [`.github/workflows/tier-classifier.yml`](../.github/workflows/tier-classifier.yml). It labels the PR `risk/high`, `risk/medium` or `risk/low` and removes a stale tier label when the PR is updated. The rules below are the human-readable form of that workflow's `TIER_RULES`; if you change one, change the other in the same PR.

## How a PR gets its tier

Each changed file is matched against the rules in order (first match wins); a file that matches nothing is **medium**. The PR takes the **highest** tier of any file it touches. One high-tier file makes the whole PR high.

The workflow only reads the list of changed file names through the GitHub API. It does not check out or run PR code, and it uses `pull-requests: write` only.

## Tiers

### High

Security, identity, secrets, data egress to AI, the shared design system, and the build and release machinery. A mistake here can leak data, widen access, break a published package, or ship a bad release.

| Area | Paths |
|---|---|
| Web security (auth, CSRF, rate limit, embed JWT, share, audit, demo auth) | `saiku-core/saiku-web/src/main/java/org/saiku/web/security/**`, `.../web/demo/**`, `.../web/servlet/SecurityHeadersFilter*`, `.../web/rest/SaikuJaxrsSecurityContextFilter*` |
| JDBC URL validation | `.../web/rest/objects/JdbcUrlValidator*` |
| Service-side security and encryption | `saiku-core/saiku-service/src/main/java/org/saiku/security/**`, `.../org/saiku/datasources/connection/encrypt/**` |
| AI egress, privacy and row-level security | `.../service/olap/ai/` files named `AiPolicy*`, `AiDataKind*`, `KAnonymity*`, `ThinQueryFilterMerge*` |
| Mondrian role handling | `saiku-core/saiku-olap-util/**/SaikuMondrianHelper*` |
| Spring Security and servlet wiring | `saiku-webapp/src/main/webapp/WEB-INF/applicationContext-spring-security*`, `WEB-INF/web.xml` |
| Shared design system (also consumed by Saiku Cloud from npm) | `saiku-ui/design-system/**`, `saiku-ui/src/lib/design-system/**`, `saiku-ui/src/lib/components/ui/**` |
| CI and release | `.github/workflows/**`, `.github/dependabot.yml`, `.github/test-floors.json` |
| Build, packaging, dependencies | every `pom.xml`, `saiku-bom/**`, `docker/**`, `dist/**` |
| Security policy and agent permissions | `SECURITY.md`, `docs/security/**`, `.claude/settings.json` |

Expectations for a high PR:

- A second human reviewer, and the security checks in [`docs/review-rubric.md`](./review-rubric.md) and [`docs/security/SECURITY-AI.md`](./security/SECURITY-AI.md) applied explicitly.
- A test that fails if the change is reverted. Fail-closed behaviour (unknown role, unconfigured AI policy, unappliable RLS filter) is tested, not assumed.
- The PR body states the blast radius. For a design-system change that includes the Saiku Cloud impact and whether `saiku-ui/design-system/package.json` needs a version bump (see `AGENTS.md`).
- Agents do not merge it.

### Medium

Ordinary product code that ships. This is the default for any file no other rule matches.

Typical paths: `saiku-core/saiku-service/**` and `saiku-core/saiku-web/**` outside the high-tier files above, `saiku-core/saiku-semantic/**`, `saiku-core/saiku-sql/**`, `saiku-launcher/**`, `saiku-ui/src/**` outside the design system, seed assets under `saiku-launcher/src/main/resources/seed/**`, `scripts/**`, and agent guidance (`AGENTS.md`, `CLAUDE.md`, `.claude/**` other than `settings.json`, `.github/prompts/**`, `.github/copilot-instructions.md`).

Expectations: one reviewer, tests for behaviour changes, `mvn verify` green (or `npm run check`, `npm test` and `npm run lint` for UI changes).

### Low

Changes with no runtime effect on their own: tests, stories, e2e, and documentation.

Paths: `**/src/test/**`, `*.test.ts|js|svelte|mjs`, `*.spec.*`, `*.stories.*`, `saiku-ui/e2e/**`, `saiku-ui/bombadil/**`, `docs/**` and any other `*.md`. The exceptions that stay high are `SECURITY.md` and `docs/security/**`.

Expectations: normal review. Docs should still match the tree, so check any path, command or class name you cite.

## Notes and limits

- The tier is a heuristic from file paths, not an analysis of the diff. A one-line change to a medium file can still be dangerous. A reviewer should move a PR up a tier by hand if it is warranted (add `risk/high` and remove the other `risk/*` label; the classifier will re-apply its own on the next push, so say so in the PR).
- Tier labels are created by the workflow on first use.
- Related: area labels (`area/*`, `docs`, `ci`) come from `.github/labeler.yml` when that workflow is present on the branch; they describe *where* a change is, tiers describe *how careful to be*.
- Planned changes to the tier mapping go through a normal PR touching both this file and the workflow.
