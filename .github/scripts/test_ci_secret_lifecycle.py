#!/usr/bin/env python3
"""Gate: no pull_request job may run Maven or npm lifecycle code over PR
content while a package credential is readable (saiku#2459).

Third instance of the same class: a `pull_request` job writes
`GH_PACKAGES_TOKEN` (directly, or handed to a reusable workflow as
`BUILD_TOKEN`) into Maven settings and then runs `mvn verify` / `package` /
`install` / `deploy` — or an npm lifecycle — against the PR's own tree. A
same-repository PR author, or any dependency executed in that build, can read
the credential from the job. Prior instances:

- saiku#1993 — release.yml's `jar` job held a `packages: write` GITHUB_TOKEN
  during `mvn verify` and the frontend npm lifecycle. Fixed by 0d40d696b
  (publishing split into a dedicated `deploy-packages` job).
- saiku#2305 — model-diff.yml built PR-controlled Maven code with the
  credential present. Fixed by building the validator from the trusted base
  commit and removing `~/.m2/settings.xml` before returning to PR content.

What the gate checks, per job of every workflow that triggers on
`pull_request`, `pull_request_target` or `workflow_run` (fork PRs never
receive the repo secret, so the exposure is specific to same-repository PRs
and trusted automation branches; `pull_request_target` and `workflow_run`
are scanned too because a job under either trigger that checks out the PR
head is the WORSE version of this exact class — review of PR #2494):

1. A `run` job whose steps make the credential readable (an env var mapping
   `secrets.GH_PACKAGES_TOKEN`, an inline `${{ secrets.GH_PACKAGES_TOKEN }}`
   interpolation in the `run:` script itself, or `secrets.GITHUB_TOKEN` /
   `github.token` in a job holding `packages: write` or `write-all` — the
   saiku#1993 shape; job permissions fall back to workflow permissions) and
   then execute a Maven/npm lifecycle goal while the credential is still in
   Maven settings or `.npmrc` and the checkout is PR content. An
   `actions/checkout` whose `ref` is not the
   trusted base SHA also returns the job to PR content (review of PR #2494).
   The repaired model-diff pattern passes: the checkout moves to the trusted
   base SHA (`git checkout ... $BASE_SHA`) before the credential is written,
   `mvn dependency:build-classpath` is not a lifecycle goal, and the settings
   file is removed before the checkout returns to PR content.
2. A reusable-workflow caller that passes a secret mapped from
   `secrets.GH_PACKAGES_TOKEN` alongside a Maven/npm build command — the
   `run-specs` / `spec-first` shape. The called workflow is external, so the
   caller's own inputs are the observable surface.

The jobs still doing this are on an explicit ALLOWED list, each entry linking
its remediation issue (#2492). The list may only shrink: the moment a job
stops exposing the credential its entry must be deleted (a stale entry fails
`test_allow_list_has_no_stale_entries`).

Remediation design (accepted on #2459): stage the GitHub Packages artifacts
in a credentialed step that never runs lifecycle code on PR content, then
build PR content with no package credential (#2492). Do not skip PR builds
or suppress failing tests to make this gate green.

Run (the `scripts` job of ci.yml runs exactly this):
    python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOWS_DIR = Path(__file__).resolve().parent.parent / "workflows"

# The repository secret the gate watches (the read-scoped Packages PAT), and
# the ephemeral token when the job holds `packages: write` (saiku#1993).
CREDENTIAL_SECRETS = ("GH_PACKAGES_TOKEN", "GITHUB_TOKEN")
SECRET_REF_RE = re.compile(r"secrets\.([A-Z_]+)")
GITHUB_TOKEN_REF_RE = re.compile(r"\bgithub\.token\b")
PACKAGES_WRITE_RE = re.compile(r"^\s*packages:\s*write\s*(#.*)?$", re.M)

# Lifecycle goals only: `mvn help:evaluate` and `dependency:build-classpath`
# are dependency plumbing the repaired patterns rely on and are not flags.
MAVEN_LIFECYCLE_RE = re.compile(
    r"\bmvn\b[^|#\n]*\b(verify|package|install|deploy|test)\b"
)
NPM_LIFECYCLE_RE = re.compile(
    r"\bnpm\s+(?:--?[^\s]+\s+)*(ci|install)\b"
    r"|\bnpm\s+run\s+(?:[^\s]+\s+)*(build|test|package)\b"
    r"|\b(?:yarn|pnpm)\s+(?:--?[^\s]+\s+)*(install|build|publish)\b"
)
GIT_CHECKOUT_RE = re.compile(r"\bgit\s+checkout\b")
CREDENTIAL_FILE_WRITE_RE = re.compile(
    r"(?:^|\s)>+\s*(?:\"([^\"]+)\"|'([^']+)'|([^\s;|]+))"
)
MAVEN_SETTINGS_ARG_RE = re.compile(
    r"(?:^|\s)(?:-s|--settings)(?:\s+|=)(?:\"([^\"]+)\"|'([^']+)'|([^\s;|]+))"
)

# Workflows may parse the trigger key `on:` as a boolean True.
ON_KEYS = ("on", True)


def workflow_files() -> list[Path]:
    return sorted(WORKFLOWS_DIR.glob("*.yml")) + sorted(WORKFLOWS_DIR.glob("*.yaml"))


def triggers(doc: dict) -> list[str]:
    for key in ON_KEYS:
        if key in doc:
            value = doc[key]
            if isinstance(value, dict):
                return [str(k) for k in value]
            if isinstance(value, list):
                return [str(k) for k in value]
            if isinstance(value, str):
                return [value]
    return []


def is_pull_request_workflow(doc: dict) -> bool:
    # pull_request_target and workflow_run jobs build PR-supplied content too
    # (a pull_request_target job commonly checks out the PR head), and a job
    # under either trigger can hold the package credential — the worse version
    # of the same class (review of PR #2494).
    return bool({"pull_request", "pull_request_target", "workflow_run"} & set(triggers(doc)))


def secret_vars(values) -> set[str]:
    """Watched credentials referenced by an environment value or script."""
    found = set()
    for value in values:
        if not isinstance(value, str):
            continue
        for name in SECRET_REF_RE.findall(value):
            if name in CREDENTIAL_SECRETS:
                found.add(name)
        if GITHUB_TOKEN_REF_RE.search(value):
            found.add("GITHUB_TOKEN")
    return found


def matched_path(match: re.Match[str]) -> str:
    """Return a shell path from either a quoted or unquoted regex group."""
    return next(group for group in match.groups() if group is not None)


def string_values(node) -> list[str]:
    out = []
    if isinstance(node, str):
        out.append(node)
    elif isinstance(node, dict):
        for value in node.values():
            out.extend(string_values(value))
    elif isinstance(node, list):
        for item in node:
            out.extend(string_values(item))
    return out


def caller_finding(job: dict) -> str | None:
    """Reusable-workflow caller passing the credential with a build command."""
    secrets_passed = string_values(job.get("secrets") or {})
    if not any(
        f"secrets.{name}" in value
        for name in CREDENTIAL_SECRETS
        for value in secrets_passed
    ):
        return None
    inputs = string_values(job.get("with") or {})
    if any(MAVEN_LIFECYCLE_RE.search(v) or NPM_LIFECYCLE_RE.search(v) for v in inputs):
        return (
            "reusable-workflow caller passes GH_PACKAGES_TOKEN alongside a "
            "Maven/npm build command"
        )
    return None


def watched_secrets(values, packages_write: bool) -> set[str]:
    """Env-var names carrying a credential this gate watches: the Packages
    PAT always, and the ephemeral GITHUB_TOKEN only when the job holds
    `packages: write` (the saiku#1993 shape; elsewhere GITHUB_TOKEN is the
    read-scoped default and is not a package credential)."""
    return {
        name
        for name in secret_vars(values)
        if name == "GH_PACKAGES_TOKEN" or (name == "GITHUB_TOKEN" and packages_write)
    }


def run_job_findings(job_id: str, job: dict, doc: dict) -> list[str]:
    """Simulate a `run` job's steps in order and flag lifecycle code over PR
    content while the credential is on disk."""
    findings: list[str] = []
    job_env = job.get("env") or {}
    permissions = job.get("permissions")
    if permissions is None:
        # The job inherits the workflow-level permissions block (review of PR
        # #2494): a top-level `permissions: {packages: write}` grants the same
        # write-scoped GITHUB_TOKEN the job-level form does.
        permissions = doc.get("permissions")
    if isinstance(permissions, dict):
        packages_write = permissions.get("packages") == "write"
    else:
        permission_text = str(permissions or "").strip()
        packages_write = permission_text == "write-all" or bool(
            PACKAGES_WRITE_RE.search(permission_text)
        )

    credential_files: set[str] = set()
    on_trusted_base = False

    steps = job.get("steps") or []
    for index, step in enumerate(steps, start=1):
        if not isinstance(step, dict):
            continue
        # An actions/checkout whose ref is not the trusted base returns the job
        # to PR content — review of PR #2494: `uses:` steps carry no `run`
        # script, so the old skip hid this path entirely.
        uses = step.get("uses")
        if isinstance(uses, str) and "actions/checkout" in uses:
            refs = string_values(step.get("with") or {})
            if not any("BASE_SHA" in v for v in refs):
                on_trusted_base = False
            continue
        step_env = {**job_env, **(step.get("env") or {})}
        script = step.get("run")
        if not isinstance(script, str):
            continue
        # The credential may also be interpolated inline in the `run:` script
        # itself (review of PR #2494: `echo "…<password>${{ secrets.
        # GH_PACKAGES_TOKEN }}</password>…" > ~/.m2/settings.xml` carries no env
        # mapping at all) — treat a secret reference in the script as the step
        # having the credential.
        step_secrets = watched_secrets(string_values(step_env) + [script], packages_write)
        for line in script.splitlines():
            # The credential in the step's own environment (the npm shape: an
            # auth token beside `npm ci`), or persisted to disk by an earlier
            # settings.xml write (the Maven shape).
            if step_secrets and not on_trusted_base:
                goal = "Maven" if MAVEN_LIFECYCLE_RE.search(line) else None
                if goal is None and NPM_LIFECYCLE_RE.search(line):
                    goal = "npm"
                if goal:
                    findings.append(
                        f"job {job_id!r} step #{index} ({step.get('name') or 'run'}): "
                        f"{goal} lifecycle code runs over PR content while a package "
                        "credential is in the step environment"
                    )
            if step_secrets:
                credential_files.update(
                    matched_path(match)
                    for match in CREDENTIAL_FILE_WRITE_RE.finditer(line)
                    if matched_path(match) != "/dev/null"
                )
            if GIT_CHECKOUT_RE.search(line):
                if "BASE_SHA" in line:
                    on_trusted_base = True
                else:
                    on_trusted_base = False
            if re.search(r"\brm\b", line):
                credential_files = {
                    path for path in credential_files if path not in line
                }
            settings_paths = {
                matched_path(match) for match in MAVEN_SETTINGS_ARG_RE.finditer(line)
            }
            maven_credential_on_disk = any(
                path.endswith("settings.xml") or path in settings_paths
                for path in credential_files
            )
            npm_credential_on_disk = any(
                path.endswith(".npmrc") for path in credential_files
            )
            if not on_trusted_base:
                goal = (
                    "Maven"
                    if maven_credential_on_disk and MAVEN_LIFECYCLE_RE.search(line)
                    else None
                )
                if goal is None and npm_credential_on_disk and NPM_LIFECYCLE_RE.search(line):
                    goal = "npm"
                if goal:
                    findings.append(
                        f"job {job_id!r} step #{index} ({step.get('name') or 'run'}): "
                        f"{goal} lifecycle code runs over PR content while a package "
                        "credential is on disk"
                    )
    return findings


def findings_in_workflow(doc: dict, filename: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    jobs = doc.get("jobs") or {}
    for job_id, job in jobs.items():
        if not isinstance(job, dict):
            continue
        if "uses" in job:
            reason = caller_finding(job)
            if reason:
                out[f"{filename}:{job_id}"] = [reason]
            continue
        found = run_job_findings(str(job_id), job, doc)
        if found:
            out[f"{filename}:{job_id}"] = found
    return out


def repository_findings() -> dict[str, list[str]]:
    """Findings across every workflow that triggers on pull_request."""
    all_findings: dict[str, list[str]] = {}
    for path in workflow_files():
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(doc, dict) or not is_pull_request_workflow(doc):
            continue
        all_findings.update(findings_in_workflow(doc, path.name))
    return all_findings


# Each entry links the remediation issue that will remove it (#2492, accepted
# design on #2459): stage the GitHub Packages artifacts in a credentialed step
# that never runs lifecycle code on PR content, then build PR content with no
# package credential. The list may only shrink.
ALLOWED: dict[str, str] = {
    "ci.yml:build": "remediation issue #2492",
    "ci.yml:dist": "remediation issue #2492",
    "ci.yml:run-specs": "remediation issue #2492",
    "ci.yml:spec-first": "remediation issue #2492",
    "docker.yml:build": "remediation issue #2492",
    "release-prep.yml:verify": "remediation issue #2492",
}


class RepositoryGateTest(unittest.TestCase):
    """The gate as CI runs it, over the repository's own workflows."""

    def test_workflows_exist(self):
        self.assertTrue(workflow_files(), "no workflows found; the gate would pass vacuously")

    def test_gate_runs_in_the_existing_scripts_job(self):
        """AC: the gate needs no new workflow step — ci.yml's `scripts` job
        runs unittest discover over test_*.py, which picks this file up."""
        ci = yaml.safe_load((WORKFLOWS_DIR / "ci.yml").read_text(encoding="utf-8"))
        scripts_job = (ci.get("jobs") or {}).get("scripts") or {}
        steps = scripts_job.get("steps") or []
        discover = [
            step.get("run", "") for step in steps
            if isinstance(step, dict) and "unittest discover" in str(step.get("run", ""))
        ]
        self.assertTrue(discover, "ci.yml scripts job no longer runs the python unittests")
        self.assertIn(
            "test_*.py",
            discover[0],
            "the discover pattern no longer matches test_*.py; this gate would not run",
        )

    def test_pull_request_workflows_parse(self):
        for path in workflow_files():
            doc = yaml.safe_load(path.read_text(encoding="utf-8"))
            if isinstance(doc, dict) and is_pull_request_workflow(doc):
                self.assertIn("jobs", doc, f"{path.name} has no jobs")

    def test_scalar_pull_request_trigger_is_scanned(self):
        doc = yaml.safe_load("""
on: pull_request
jobs:
  b:
    runs-on: ubuntu-latest
    steps:
      - env: {T: "${{ secrets.GH_PACKAGES_TOKEN }}"}
        run: mvn verify
""")
        self.assertTrue(is_pull_request_workflow(doc))
        self.assertEqual(sorted(findings_in_workflow(doc, "scalar.yml")), ["scalar.yml:b"])

    def test_every_finding_is_allow_listed(self):
        unexpected = {
            key: reasons for key, reasons in repository_findings().items() if key not in ALLOWED
        }
        self.assertEqual(
            unexpected,
            {},
            "pull_request jobs run Maven/npm lifecycle code over PR content while a "
            "package credential is readable — fix the job (see #2492) or, only for an "
            "existing same-class job, allow-list it with a remediation issue",
        )

    def test_allow_list_has_no_stale_entries(self):
        """The allow-list may only shrink: an entry whose job no longer
        exposes the credential must be deleted, not left behind."""
        findings = repository_findings()
        stale = sorted(set(ALLOWED) - set(findings))
        self.assertEqual(
            stale,
            [],
            "allow-list entries no longer matching any finding — remove them "
            "(the allow-list may only shrink)",
        )

    def test_allow_list_entries_link_a_remediation_issue(self):
        bad = [key for key, note in ALLOWED.items() if not re.search(r"#\d+", note)]
        self.assertEqual(bad, [])


class ModelDiffPatternTest(unittest.TestCase):
    """saiku#2305: the repaired pattern must keep passing, the pre-fix shape
    must fail."""

    def test_the_repaired_model_diff_workflow_has_no_finding(self):
        doc = yaml.safe_load((WORKFLOWS_DIR / "model-diff.yml").read_text(encoding="utf-8"))
        self.assertTrue(is_pull_request_workflow(doc))
        self.assertEqual(findings_in_workflow(doc, "model-diff.yml"), {})

    def test_pre_fix_model_diff_shape_is_flagged(self):
        doc = yaml.safe_load("""
on:
  pull_request:
    paths: ['**/*.xml']
jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@1111111111111111111111111111111111111111
        with:
          fetch-depth: 0
      - name: Set up JDK 22
        uses: actions/setup-java@2222222222222222222222222222222222222222
      - name: Configure Maven settings.xml
        env:
          GH_PACKAGES_USER: ${{ github.actor }}
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: |
          mkdir -p ~/.m2
          cat > ~/.m2/settings.xml <<EOF
          <server><id>github-olap4j</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
          EOF
      - name: Build saiku-service
        run: mvn -B -ntp -q -pl saiku-core/saiku-service -am -DskipTests install
""")
        found = findings_in_workflow(doc, "model-diff.yml")
        self.assertEqual(sorted(found), ["model-diff.yml:diff"])
        self.assertIn("Maven lifecycle", found["model-diff.yml:diff"][0])

    def test_classpath_resolution_on_trusted_base_is_not_a_lifecycle_goal(self):
        script = (
            "mvn -B -ntp -q -pl saiku-core/saiku-service dependency:build-classpath \\\n"
            "  -Dmdep.outputFile=\"$PWD/target/classpath.txt\"\n"
        )
        self.assertIsNone(MAVEN_LIFECYCLE_RE.search(script))


class ReleaseTokenPatternTest(unittest.TestCase):
    """saiku#1993: a `packages: write` GITHUB_TOKEN on disk during the build
    lifecycle is the same class, whether the token is the Packages PAT or the
    ephemeral write-scoped GITHUB_TOKEN."""

    def test_write_scoped_github_token_during_mvn_verify_is_flagged(self):
        doc = yaml.safe_load("""
on:
  push:
    tags: ['v*']
jobs:
  jar:
    runs-on: ubuntu-latest
    permissions:
      packages: write
    steps:
      - uses: actions/checkout@1111111111111111111111111111111111111111
      - name: Configure Maven settings.xml
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          cat > ~/.m2/settings.xml <<EOF
          <server><id>github-saiku-self</id><username>${GITHUB_ACTOR}</username><password>${GITHUB_TOKEN}</password></server>
          EOF
      - name: mvn verify
        run: mvn -B -ntp verify
""")
        # The release workflow never triggers on pull_request, so the
        # repository gate does not scan it; the shape itself is the fixture.
        found = findings_in_workflow(doc, "release.yml")
        self.assertEqual(sorted(found), ["release.yml:jar"])
        self.assertIn("Maven lifecycle", found["release.yml:jar"][0])

    def test_dedicated_deploy_job_without_pr_trigger_is_out_of_gate_scope(self):
        """The fix landed by 0d40d696b: publishing moved to its own job on a
        trusted ref. release.yml never triggers on pull_request, so the
        repository gate does not scan it — the deploy itself is the point of
        that job, on code that is not PR-controlled."""
        doc = yaml.safe_load("""
on:
  push:
    tags: ['v*']
jobs:
  deploy-packages:
    permissions:
      packages: write
    steps:
      - name: Configure Maven settings.xml
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          cat > ~/.m2/settings.xml <<EOF
          <server><id>github-saiku-self</id><username>${GITHUB_ACTOR}</username><password>${GITHUB_TOKEN}</password></server>
          EOF
      - name: mvn deploy
        run: mvn -B -ntp deploy
""")
        self.assertFalse(is_pull_request_workflow(doc))
        self.assertNotIn("release.yml", repository_findings())

    def test_release_workflows_are_not_pr_scanned(self):
        for name in ("release.yml", "design-system.yml"):
            path = WORKFLOWS_DIR / name
            if path.exists():
                doc = yaml.safe_load(path.read_text(encoding="utf-8"))
                self.assertFalse(
                    is_pull_request_workflow(doc),
                    f"{name} now triggers on pull_request — the gate scans it and its "
                    "jobs must not build PR content with the credential",
                )


class NpmLifecycleTest(unittest.TestCase):
    def test_npm_ci_with_credential_over_pr_content_is_flagged(self):
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  npm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@1111111111111111111111111111111111111111
      - name: Install
        env:
          NODE_AUTH_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: npm ci
      - name: Build
        run: npm run build
""")
        found = findings_in_workflow(doc, "x.yml")
        self.assertEqual(sorted(found), ["x.yml:npm"])

    def test_npm_lifecycle_without_credential_passes(self):
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  npm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@1111111111111111111111111111111111111111
      - name: Build
        run: npm run build
""")
        self.assertEqual(findings_in_workflow(doc, "x.yml"), {})

    def test_npmrc_credential_persists_into_later_step(self):
        doc = yaml.safe_load("""
on: pull_request
jobs:
  npm:
    steps:
      - env: {TOKEN: "${{ secrets.GH_PACKAGES_TOKEN }}"}
        run: echo "//npm.pkg.github.com/:_authToken=${TOKEN}" > .npmrc
      - run: npm ci
""")
        self.assertEqual(sorted(findings_in_workflow(doc, "npmrc.yml")), ["npmrc.yml:npm"])


class TrustedBasePatternTest(unittest.TestCase):
    def test_maven_over_trusted_base_with_credential_passes(self):
        """The repaired model-diff shape, as a minimal fixture."""
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@1111111111111111111111111111111111111111
        with:
          fetch-depth: 0
          persist-credentials: false
      - name: Select trusted base source
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
        run: |
          git rev-parse HEAD > "$RUNNER_TEMP/head"
          git checkout --detach "$BASE_SHA"
      - name: Configure Maven settings.xml
        env:
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: |
          cat > ~/.m2/settings.xml <<EOF
          <server><id>github-olap4j</id><username>${GH_PACKAGES_USER}</username><password>${GH_PACKAGES_TOKEN}</password></server>
          EOF
      - name: Build
        run: mvn -B -ntp -q -pl saiku-core/saiku-service -am -DskipTests install
      - name: Resolve classpath and return to PR content
        run: |
          mvn -B -ntp -q -pl saiku-core/saiku-service dependency:build-classpath -Dmdep.outputFile=cp.txt
          rm -f ~/.m2/settings.xml
          git checkout --detach "$(cat "$RUNNER_TEMP/head")"
""")
        self.assertEqual(findings_in_workflow(doc, "trusted.yml"), {})

    def test_lifecycle_after_returning_to_pr_content_with_credential_is_flagged(self):
        """The rm must come before the checkout back to PR content — the
        reversed order re-exposes the credential to PR content."""
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - name: Select trusted base source
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
        run: git checkout --detach "$BASE_SHA"
      - name: Configure Maven settings.xml
        env:
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: |
          cat > ~/.m2/settings.xml <<EOF
          <server><id>github-olap4j</id><password>${GH_PACKAGES_TOKEN}</password></server>
          EOF
      - name: Return to PR content, then keep building
        run: |
          git checkout --detach "$(cat head.txt)"
          mvn -B -ntp verify
          rm -f ~/.m2/settings.xml
""")
        found = findings_in_workflow(doc, "trusted.yml")
        self.assertEqual(sorted(found), ["trusted.yml:diff"])

    def test_inline_secret_in_run_script_is_flagged(self):
        """The most common way to write the bug: the settings file is built by
        interpolating `${{ secrets.GH_PACKAGES_TOKEN }}` directly in the
        `run:` script, with NO env mapping at all (review of PR #2494). The
        script reference must count as the step having the credential."""
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  inline:
    steps:
      - name: Write settings and build
        run: |
          echo "<settings>...<password>${{ secrets.GH_PACKAGES_TOKEN }}</password>...</settings>" > ~/.m2/settings.xml
          mvn -B verify
""")
        found = findings_in_workflow(doc, "inline.yml")
        self.assertEqual(sorted(found), ["inline.yml:inline"])
        self.assertIn("package credential", found["inline.yml:inline"][0])

    def test_actions_checkout_back_to_pr_head_is_flagged(self):
        """A `uses: actions/checkout` with a ref that is not the trusted base
        SHA returns the job to PR content (review of PR #2494): the
        `uses:`-step skip used to leave the job "on trusted base"."""
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  recheck:
    steps:
      - name: Build on trusted base
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: |
          git checkout --detach "$BASE_SHA"
          cat > ~/.m2/settings.xml <<EOF
          <server><password>${GH_PACKAGES_TOKEN}</password></server>
          EOF
          mvn -B -ntp dependency:build-classpath -Dmdep.outputFile=cp.txt
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}
          clean: false
      - name: Build PR content
        run: mvn -B -ntp verify
""")
        found = findings_in_workflow(doc, "recheck.yml")
        self.assertEqual(sorted(found), ["recheck.yml:recheck"])

    def test_actions_checkout_of_the_trusted_base_keeps_the_job_on_trusted_base(self):
        """The mirror: a checkout whose ref IS the base SHA (the repaired
        pattern spelled with the action instead of a git line) stays trusted."""
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  base:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.base.sha }}
      - name: Build trusted base with credential
        env:
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: |
          cat > ~/.m2/settings.xml <<EOF
          <server><password>${GH_PACKAGES_TOKEN}</password></server>
          EOF
          mvn -B -ntp dependency:build-classpath -Dmdep.outputFile=cp.txt
""")
        self.assertEqual(findings_in_workflow(doc, "base.yml"), {})

    def test_workflow_level_packages_write_is_watched(self):
        """A top-level `permissions: {packages: write}` grants the same
        write-scoped GITHUB_TOKEN as the job-level form — the #1993 shape
        spelled at workflow level must be flagged too (review of PR #2494)."""
        doc = yaml.safe_load("""
on:
  pull_request:
permissions:
  packages: write
jobs:
  legacy:
    steps:
      - uses: actions/checkout@v4
      - name: Build with ephemeral token in env
        env:
          BUILD_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: mvn -B -ntp verify
""")
        found = findings_in_workflow(doc, "wlp.yml")
        self.assertEqual(sorted(found), ["wlp.yml:legacy"])

    def test_github_token_expression_is_watched_with_packages_write(self):
        doc = yaml.safe_load("""
on: pull_request
permissions: {packages: write}
jobs:
  build:
    steps:
      - env: {GITHUB_TOKEN: "${{ github.token }}"}
        run: mvn verify
""")
        self.assertEqual(sorted(findings_in_workflow(doc, "token.yml")), ["token.yml:build"])

        doc["permissions"] = {"packages": "read"}
        self.assertEqual(findings_in_workflow(doc, "token.yml"), {})

    def test_write_all_permissions_are_watched_at_both_scopes(self):
        doc = yaml.safe_load("""
on: pull_request
permissions: write-all
jobs:
  build:
    steps:
      - env: {GITHUB_TOKEN: "${{ github.token }}"}
        run: mvn verify
""")
        self.assertEqual(sorted(findings_in_workflow(doc, "all.yml")), ["all.yml:build"])
        doc["permissions"] = {"contents": "read"}
        doc["jobs"]["build"]["permissions"] = "write-all"
        self.assertEqual(sorted(findings_in_workflow(doc, "all.yml")), ["all.yml:build"])

    def test_explicit_maven_settings_file_is_watched(self):
        doc = yaml.safe_load("""
on: pull_request
jobs:
  build:
    steps:
      - env: {TOKEN: "${{ secrets.GH_PACKAGES_TOKEN }}"}
        run: echo "<settings><password>${TOKEN}</password></settings>" > "$RUNNER_TEMP/s.xml"
      - run: mvn -B -s "$RUNNER_TEMP/s.xml" verify
""")
        self.assertEqual(sorted(findings_in_workflow(doc, "settings.yml")), ["settings.yml:build"])
        doc["jobs"]["build"]["steps"].insert(1, {"run": 'rm "$RUNNER_TEMP/s.xml"'})
        self.assertEqual(findings_in_workflow(doc, "settings.yml"), {})

    def test_pull_request_target_trigger_is_scanned(self):
        """A pull_request_target job that checks out the PR head with the
        credential present is the WORSE version of this class — it is in the
        gate's scope now (review of PR #2494)."""
        doc = yaml.safe_load("""
on:
  pull_request_target:
jobs:
  target:
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha }}
      - name: Build PR head with the credential
        env:
          GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
        run: mvn -B -ntp verify
""")
        found = findings_in_workflow(doc, "prt.yml")
        self.assertEqual(sorted(found), ["prt.yml:target"])


class CallerPatternTest(unittest.TestCase):
    def test_caller_passing_build_token_with_maven_command_is_flagged(self):
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  specs:
    uses: org/repo/.github/workflows/run-specs.yml@1111111111111111111111111111111111111111
    with:
      build-command: |
        mvn -B -ntp -pl saiku-launcher -am -Dmaven.test.skip=true package
    secrets:
      BUILD_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
""")
        found = findings_in_workflow(doc, "ci.yml")
        self.assertEqual(sorted(found), ["ci.yml:specs"])
        self.assertIn("reusable-workflow caller", found["ci.yml:specs"][0])

    def test_caller_without_build_command_is_not_flagged(self):
        doc = yaml.safe_load("""
on:
  pull_request:
jobs:
  specs:
    uses: org/repo/.github/workflows/run-specs.yml@1111111111111111111111111111111111111111
    with:
      mode: enforce
    secrets:
      BUILD_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}
""")
        self.assertEqual(findings_in_workflow(doc, "ci.yml"), {})


class CurrentCallersTest(unittest.TestCase):
    """The six allow-listed callers are exactly today's findings; each links
    the remediation issue. When #2492 lands, the list shrinks to zero."""

    def test_the_current_callers_are_the_only_findings_and_are_allow_listed(self):
        findings = repository_findings()
        self.assertEqual(
            sorted(findings),
            sorted(ALLOWED),
            "the flagged set changed — update ALLOWED (only ever shrink it, #2492)",
        )
        for key in (
            "ci.yml:build",
            "ci.yml:dist",
            "ci.yml:run-specs",
            "ci.yml:spec-first",
            "docker.yml:build",
            "release-prep.yml:verify",
        ):
            self.assertIn(key, findings, f"{key} dropped off the findings; shrink ALLOWED")


if __name__ == "__main__":
    unittest.main()
