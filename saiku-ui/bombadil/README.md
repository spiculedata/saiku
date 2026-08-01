# Bombadil UI fuzzing

[Bombadil](https://github.com/antithesishq/bombadil) is a property-based UI
fuzzer from Antithesis: it drives a headless browser, autonomously explores the
Saiku UI (clicking, typing, navigating), and checks the correctness properties
in [`spec.ts`](./spec.ts) on every state — surfacing crashes, console errors,
failed requests, and broken states a scripted e2e wouldn't reach.

It's **test infrastructure**, not a product feature — run on demand, locally.
Ported from saiku-cloud's `dashboard/bombadil/` and adapted for saiku (Spring
Security session auth, local launcher target).

## Prerequisites

- The dev-dependency is installed (`@antithesishq/bombadil`, pinned in
  `package.json`). `npm install` restores it.
- **A running Saiku** to fuzz. The default target is a local launcher:

  ```bash
  # from the repo root — build once, then serve:
  mvn -pl saiku-launcher,saiku-webapp clean
  mvn -pl saiku-launcher -am -Dmaven.test.skip=true package
  java -jar saiku-launcher/target/saiku-<version>.jar serve --port 8080 --home ./saiku-home
  # UI at http://localhost:8080/ui/  ·  login admin / admin
  ```

- **An authenticated session.** The fuzzer's headless browser can't fill the
  login form and keep a session, so it starts with a `JSESSIONID` cookie
  injected. `run.sh` mints one automatically by logging in to the target with
  `admin`/`admin` — nothing to do for the default local setup.

## Run it

```bash
npm run fuzz                 # 2-min run vs http://localhost:8080/ui/, auto-login admin/admin
FUZZ_TIME=60m npm run fuzz   # fuzz for an hour
```

Point it elsewhere / use other creds / supply a cookie directly:

```bash
FUZZ_TARGET=http://localhost:8080/ui/ SAIKU_USER=admin SAIKU_PASS=admin FUZZ_TIME=60m npm run fuzz
# or bypass auto-mint with a JSESSIONID value (from a logged-in browser's cookies):
SAIKU_SESSION='<jsessionid value>' npm run fuzz
# or a full Cookie header verbatim:
FUZZ_COOKIE='JSESSIONID=…; XSRF-TOKEN=…' npm run fuzz
```

Only fuzz a **local / disposable** Saiku — every action issues real requests
(each query a real Mondrian/engine call). Never point `FUZZ_TARGET` at a shared
or production instance.

## Read the results

Output lands in `bombadil/out/` (git-ignored): a `trace.jsonl`, screenshots, and
a report of any property violations. Explore a run interactively:

```bash
node_modules/.bin/bombadil inspect bombadil/out/trace.jsonl   # opens a local UI
```

When Bombadil finds a violation it records the exact action sequence. Replay it
deterministically to confirm a fix:

```bash
node_modules/.bin/bombadil browser test http://localhost:8080/ui/ \
  bombadil/spec.ts --reproduce bombadil/out/trace.jsonl
```

## The properties

`spec.ts` re-exports Bombadil's **browser defaults** — no uncaught JS exceptions,
no unhandled promise rejections, no console errors, no failed HTTP requests —
plus a curated action set. Saiku has no top-level `+error.svelte` crash screen,
so **5xx server bugs are caught by the built-in `noHttpErrorCodes`** property.

Triage note: `noHttpErrorCodes` fires on *any* ≥ 400 response, so a long run will
also flag legitimate 4xx (a 404 from a fuzzed URL, a 403 on a CSRF-guarded POST).
Those are noise — the **5xx** violations are the real bugs. Add more invariants as
you learn the UI's contracts, but keep them conservative (a property that fires on
legitimate states trains you to ignore the report). See the
[specification language docs](https://antithesishq.github.io/bombadil/browser/3-specification-language.html).

## Actions (why not `defaultActions`)

The default action set clicks everything, including **Sign out** (which ends the
injected session, stranding the run on `/login`) and the login submit. `spec.ts`
composes its own set: every default action except raw `clicks` (replaced by an
auth-avoiding `safeClicks`) and `navigation` (which could jump to `/login`).
Sign-out and login submit carry `data-testid="app-signout"` / `"login-submit"`
so `safeClicks` can exclude them.

## Not wired into CI

On-demand only. A gated nightly workflow could follow once the property set has
settled.
