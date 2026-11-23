---
description: Fix a Saiku bug test-first, from issue to PR against development
mode: agent
---

Fix the bug described in ${input:issue:issue number or description}.

1. **Reproduce first.** Find the smallest place the bug is observable: a unit test in the owning module (`saiku-core/*/src/test`), a launcher IT (`saiku-launcher/src/test/.../it/*IT.java`) for REST or end-to-end behaviour, or a vitest/Playwright test in `saiku-ui` for UI. Write a test that fails for the reason in the issue, and run it to watch it fail:
   `mvn -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
   Match the stubbing style of neighbouring tests in that package.
   If the issue has acceptance criteria, also write `acceptance/<issue>/spec.json` now and commit it FIRST (before any fix), then watch it fail against a build without the fix. `acceptance / spec fails on base` enforces this.
2. **Find the root cause** before changing code. If the failure is timing- or environment-dependent, explain the mechanism in the commit message, not just the symptom.
3. **Fix it minimally** in the module that owns the behaviour, then run the test again to watch it pass. Run the module's full suite as well.
4. **Format and verify:** `mvn spotless:apply`, then `mvn -pl <module> -am verify`. Never lower `.github/test-floors.json` or `.coverage-thresholds.json` to get green.
5. **Ship it:** branch `fix/<issue>-<slug>` off `development`, commit as `#<issue> - fix(<area>): <what changed>`, and open a PR against `development` with `Closes #<issue>`, the root cause, and a test plan.

Follow AGENTS.md throughout.
