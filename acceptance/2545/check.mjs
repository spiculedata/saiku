// The acceptance spec for spiculedata/saiku#2545, run as a command spec.
//
// #2545: the app shell builds a scoped stylesheet from the app document's `id`
// with a template string — `[data-saiku-app="${id}"]` — and the server stores
// the id without validation. An id that closes the selector injects arbitrary
// CSS rules into a `<style>` element that never passes through the custom-CSS
// sanitiser.
//
// Without ACCEPTANCE_BASE_URL (CI gives command specs no launcher) it checks
// the source of the tree it runs in:
//   S1: AppResource validates `id` against ^[A-Za-z0-9_-]{1,64}$ (empty = the
//       preview sentinel) on save and refuses with the typed 400 VALIDATION_ERROR;
//   S2: the UI loader (normaliseApp) refuses a document whose id is outside the
//       same set, so it is never rendered;
//   S3: rootSelectorFor escapes its input — no raw `${...}` interpolation of the
//       scope id inside the attribute selector — and the tests ship: a vitest
//       covers the issue's payload and asserts no rule outside the app scope,
//       and the Java test pins the typed 400;
//   S4: a generated id (letters, digits, _ -, ≤64) is still accepted by both.
// With ACCEPTANCE_BASE_URL set it also checks a live launcher:
//   C1: the issue's POST (a doc whose id closes the selector) answers 400
//       VALIDATION_ERROR and the saved file is not written;
//   C3: the same POST with a generated id answers 200 and loads back unchanged.
//
// Exit non-zero on the first broken clause, with the reason on stderr.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const base = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/+$/, '');
const user = process.env.ACCEPTANCE_USER;
const password = process.env.ACCEPTANCE_PASSWORD;

function fail(message) {
  console.error(`#2545: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

// The issue's breakout payload — a `"]{}`-closed id whose tail injects a
// full-page overlay and an https: beacon.
const HOSTILE_ID =
  'x"]{} body::before{content:\'injected\'} html{background-image:url(https://attacker.example/beacon)!important} [x="';

// ---- S1: the server refuses a hostile id on save --------------------------------------
const resource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/apps/AppResource.java');
if (!resource.includes('"^[A-Za-z0-9_-]{1,64}$"')) {
  fail('S1: AppResource does not compile APP_ID_PATTERN ^[A-Za-z0-9_-]{1,64}$');
}
if (!/badRequest\("id"/.test(resource)) {
  fail('S1: AppResource does not answer the typed 400 (VALIDATION_ERROR, field id)');
}

// ---- S2: the UI loader refuses the same id --------------------------------------------
const appsApi = read('saiku-ui/src/lib/api/apps.ts');
if (!/export function isValidAppId\(/.test(appsApi)) {
  fail('S2: apps.ts has no exported isValidAppId');
}
const normaliseSrc = appsApi.slice(appsApi.indexOf('export function normaliseApp'), appsApi.indexOf('export async function listApps'));
if (!/isValidAppId\(/.test(normaliseSrc)) {
  fail('S2: normaliseApp does not validate the id through isValidAppId');
}
if (!/throw new Error\(/.test(normaliseSrc)) {
  fail('S2: normaliseApp does not reject an app with an invalid id — it would still render');
}

// ---- S3: the selector is escaped and the tests pin it ---------------------------------
const appShell = read('saiku-ui/src/lib/views/app/appShell.ts');
if (!/function escapeSelectorValue\(/.test(appShell) && !/export function escapeSelectorValue\(/.test(appShell)) {
  fail('S3: appShell.ts has no escapeSelectorValue');
}
const selectorFn = appShell.slice(appShell.indexOf('export function rootSelectorFor'), appShell.indexOf('export function scopedCustomCss'));
if (!/escapeSelectorValue\(/.test(selectorFn)) {
  fail('S3: rootSelectorFor still interpolates the scope id raw into the attribute selector');
}
if (!/data-saiku-app="\$\{.*\}"/.test(selectorFn)) {
  fail('S3: rootSelectorFor does not build [data-saiku-app="…"] from the escaped value');
}
const shellTest = read('saiku-ui/src/lib/views/app/appShell.test.ts');
if (!shellTest.includes('body::before')) {
  fail('S3: the vitest for the shell helpers does not cover the issue breakout payload');
}
if (!/not\.toContain\(.{0,40}body::before/.test(shellTest)) {
  fail('S3: the shell vitest does not assert the skin stylesheet has no out-of-scope rule');
}
const apiTest = read('saiku-ui/src/lib/api/apps.test.ts');
if (!/INVALID_APP_ID|isValidAppId|normaliseApp\(.{0,400}throw/i.test(apiTest) || !/app-1/.test(apiTest)) {
  fail('S4: apps.test.ts does not pin both the id rejection and the generated-id round-trip');
}
const resourceTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/apps/AppResourceTest.java');
if (!/body::before|\\"]\{\}/.test(resourceTest.replace(/\\"/g, '"'))) {
  fail('S3: AppResourceTest does not cover the issue breakout id');
}
if (!/assertEquals\(400/.test(resourceTest)) {
  fail('S3: AppResourceTest does not pin the 400 answer');
}

if (!base) {
  console.log('#2545: static checks pass (S1-S4); ACCEPTANCE_BASE_URL is unset, so the live C1/C3 checks did not run');
  process.exit(0);
}
if (!user || !password) fail('ACCEPTANCE_USER / ACCEPTANCE_PASSWORD are not set');

// ---- live: sign in the way Saiku signs in, then exercise the endpoint -----------------
async function send(path, init = {}) {
  return fetch(base + path, { redirect: 'manual', ...init });
}

/** The last `name=value` pair the response set for a cookie (login rotates the session id). */
function cookieFrom(response, name) {
  const all = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  const pairs = all.map((c) => c.split(';')[0]).filter((c) => c.startsWith(`${name}=`));
  return pairs.length ? pairs[pairs.length - 1] : null;
}

const login = await send('/rest/saiku/session/', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ username: user, password }).toString()
});
if (login.status !== 200) fail(`C1: the /rest/saiku/session/ login answered ${login.status}`);
const jar = new Map();
for (const name of ['JSESSIONID', 'XSRF-TOKEN']) {
  const value = cookieFrom(login, name);
  if (value) jar.set(name, value);
}
if (!jar.has('JSESSIONID')) fail('C1: the login did not set a JSESSIONID cookie');

/** POST/GET/DELETE an .saikuapp with the session + CSRF cookies the runner itself uses. */
async function api(method, path, body) {
  const headers = { Accept: 'application/json', cookie: [...jar.values()].join('; ') };
  if (method !== 'GET' && method !== 'DELETE') headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && jar.has('XSRF-TOKEN')) headers['x-xsrf-token'] = jar.get('XSRF-TOKEN');
  return send(path, { method, headers, body: body ?? undefined });
}

const homePath = `/rest/saiku/api/apps/homes/${encodeURIComponent(user)}/css.saikuapp`;

// ---- C1: the issue's POST must answer the typed 400 -----------------------------------
const hostile = await api('POST', homePath, JSON.stringify({ id: HOSTILE_ID, name: 'hostile', pages: [] }));
if (hostile.status !== 400) fail(`C1: the hostile-id save answered ${hostile.status}, not the typed 400`);
const hostileBody = await hostile.json().catch(() => ({}));
if (hostileBody.status !== 'VALIDATION_ERROR' || hostileBody.field !== 'id') {
  fail(`C1: the hostile-id save did not answer the typed VALIDATION_ERROR on id (${JSON.stringify(hostileBody)})`);
}
const refused = await api('GET', homePath);
if (refused.status === 200) {
  fail('C1: the hostile app was still saved — the UI would render it');
}

// ---- C3: a generated id saves and loads unchanged -------------------------------------
const fine = await api('POST', homePath, JSON.stringify({ id: 'app-1', name: 'generated id app', pages: [] }));
if (fine.status !== 200) fail(`C3: a generated id was refused (${fine.status})`);
const loaded = await api('GET', homePath);
if (loaded.status !== 200) fail(`C3: the saved app did not load back (${loaded.status})`);
const loadedBody = await loaded.json().catch(() => ({}));
if (loadedBody.id !== 'app-1') fail(`C3: the loaded app's id is not the generated one (${JSON.stringify(loadedBody.id)})`);
await api('DELETE', homePath);

console.log('#2545: all checks pass (S1-S4 static, C1/C3 live)');
