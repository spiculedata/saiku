# Copilot instructions

Full project guidance lives in [`AGENTS.md`](../AGENTS.md) at the repo root. Read it before making changes. The rules below are the ones most often broken, repeated here because Copilot code review reads only this file.

## Repo rules

- **Agent policy:** the *Working agreement* section of `AGENTS.md` (deliverables, hard stops, the `issue-approved` / `human-queue` label model) applies to Copilot too. Skills live in `.agents/skills/`; load only the one that matches the task.

- **Branching:** Gitflow. Work on `feature/<name>`, `fix/<name>` or `chore/<name>` off `development`, and open PRs against `development`, never `main`.
- **Commit messages:** `#<issue> - <description>`.
- **Java formatting:** Palantir Java Format via Spotless. Run `mvn spotless:apply` before committing; `mvn verify` fails on unformatted code.
- **Spring wiring:** beans go in the `applicationContext-*.xml` / `saiku-beans.xml` files, not `@Configuration` classes.
- **REST:** Jersey 3.1 JAX-RS resources (`jakarta.ws.rs.*`) under `org.saiku.web.rest.resources`, not Spring `@RestController`.
- **Compiler levels:** the root pom pins old source levels on purpose; modules that need Java 21/22 override locally. Don't "fix" the root pom.
- **Tests:** CI runs `mvn -B -ntp -DskipITs=false verify`. `.github/test-floors.json` and `.coverage-thresholds.json` are hard floors. Add tests; never delete them to get green.
- **Acceptance specs, first:** a PR that closes issue N ships `acceptance/N/spec.json`, committed BEFORE the implementation. `acceptance / spec fails on base` fails a spec that already passes on the base build; only a human may excuse it with the `spec-already-green` label. See `AGENTS.md` and `docs/acceptance-specs.md`.
- **UI (`saiku-ui/`):** Tailwind v4 with design-system tokens only. ESLint bans raw tone classes (`bg-emerald-*`, `text-red-*`, …) outside the design system. Import design-system pieces from the barrels (`$lib/design-system`, `$lib/components/ui`), never deep paths.
- **Svelte 5:** never call a helper that writes `$state` synchronously inside an `$effect` that also reads it. Defer with `queueMicrotask` or `untrack`.
- **FoodMart schema:** edit `saiku-launcher/src/main/resources/seed/FoodMart4.xml`, not the runtime copy under `saiku-home/`.

## When reviewing

Flag PRs that target `main`, lower a test or coverage floor without explanation, add raw Tailwind tone classes in `saiku-ui/src`, or move REST endpoints to Spring MVC.
