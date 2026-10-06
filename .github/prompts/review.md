---
description: Review a Saiku PR against docs/review-rubric.md
mode: agent
---

Review pull request ${input:pr:PR number or URL} on spiculedata/saiku using the
rubric in `docs/review-rubric.md`. That rubric is the source of truth for the
axes, the severity labels, and the report shape — read it first and follow it
rather than improvising a checklist.

1. **Set up.** `gh pr view <pr> --json title,body,baseRefName,files` and
   `gh pr diff <pr>`. Confirm `baseRefName` is `development`. Read
   `docs/review-rubric.md` and the *Conventions specific to this repo* section of
   `AGENTS.md`.
2. **Claim check.** List what the PR body promises; list what the diff actually
   contains. A mismatch is a blocking finding on its own.
3. **Walk the axes.** Correctness and security first and in full, then tests, then
   repo conventions, then compatibility, then hygiene. Skip axes the diff cannot
   raise rather than padding the report.
4. **Verify locally where cheap.** Run the module's suite
   (`mvn -pl <module> -am test -Dtest=<Class>`) for a behaviour claim, and
   `cd saiku-ui && npm run check && npm test` for UI paths. Never lower
   `.github/test-floors.json` or `.coverage-thresholds.json` to make something
   pass. Do not push to the contributor's branch.
5. **Report.** Blocking findings first, then non-blocking, then praise. One line
   per finding: `severity — file:line — what breaks — what you'd accept instead`.
   End with a one-line verdict: `approve`, `approve with comments`, or
   `changes requested`.

Do not merge, do not edit the PR body or the diff, and do not review a PR you
authored. The human maintainer has the final call — see `CONTRIBUTING.md` §
*PR review*.