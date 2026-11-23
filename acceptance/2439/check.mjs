// The acceptance spec for spiculedata/saiku#2439, run as a command spec.
//
// The demo pages (embed-demo, aqvira-demo, saiku-ui/static/showcase, saiku-showcase) sign in with a
// form-encoded POST to `/rest/saiku/session/` (trailing slash). An api spec can only send a JSON body and the
// login consumes application/x-www-form-urlencoded, so this check makes that exact request itself.
//
// Without ACCEPTANCE_BASE_URL (CI gives command specs no launcher) it checks the source of the tree it runs in:
//   S1: SaikuCsrfRequestMatcher's LOGIN_PATH exempts /rest/saiku/session and /rest/saiku/session/ and
//       nothing beneath them;
//   S2: applicationContext-saiku.xml permits exactly /rest/saiku/session/ and never widens it
//       (/rest/saiku/session/**, /rest/saiku/session/*, /rest/saiku/session*);
//   S3: the matcher, URL-rule and launcher IT tests pin the trailing-slash login.
// With ACCEPTANCE_BASE_URL set it also checks a live launcher:
//   C1: the trailing-slash login answers 200, sets a session cookie, and that cookie alone authenticates a
//       later call as the same user.
//   C2: nothing BENEATH /rest/saiku/session/ is reopened (#2368 closed /session/clear): an anonymous POST with
//       credentials is refused and never 200, and an anonymous GET is not permitted.
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
  console.error(`#2439: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

// ---- S1: the CSRF login exemption ---------------------------------------------------------------------
const matcherSrc = read('saiku-core/saiku-web/src/main/java/org/saiku/web/security/csrf/SaikuCsrfRequestMatcher.java');
const loginPath = /LOGIN_PATH\s*=\s*Pattern\.compile\("((?:[^"\\]|\\.)*)"\)/.exec(matcherSrc);
if (!loginPath) fail('S1: LOGIN_PATH not found in SaikuCsrfRequestMatcher');
const loginRe = new RegExp(loginPath[1].replace(/\\\\/g, '\\'));
for (const p of ['/rest/saiku/session', '/rest/saiku/session/']) {
  if (!loginRe.test(p)) fail(`S1: LOGIN_PATH ${loginPath[1]} does not exempt ${p} (the demo pages log in at /rest/saiku/session/)`);
}
for (const p of ['/rest/saiku/session/clear', '/rest/saiku/session/anything', '/rest/saiku/session//', '/rest/saiku/sessionx']) {
  if (loginRe.test(p)) fail(`S1: LOGIN_PATH ${loginPath[1]} also exempts ${p}; only the login itself may be exempt`);
}

// ---- S2: the URL rules --------------------------------------------------------------------------------
const xml = read('saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml').replace(/<!--[\s\S]*?-->/g, '');
const rules = [...xml.matchAll(/<security:intercept-url\b[^>]*>/g)].map((m) => m[0]);
const patternOf = (r) => (/\spattern="([^"]*)"/.exec(r) || [])[1];
const exact = rules.filter((r) => patternOf(r) === '/rest/saiku/session/');
if (exact.length !== 1 || !/\saccess="permitAll"/.test(exact[0]) || /\smethod=/.test(exact[0])) {
  fail('S2: applicationContext-saiku.xml needs exactly one <intercept-url pattern="/rest/saiku/session/" access="permitAll"/>');
}
const widened = rules.map(patternOf).filter((p) => p && p.startsWith('/rest/saiku/session') && /\*/.test(p));
if (widened.length) fail(`S2: a wildcard session rule reopens paths beneath the login: ${widened.join(', ')}`);

// ---- S3: the tests that pin it ------------------------------------------------------------------------
const pins = [
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/security/csrf/SaikuCsrfRequestMatcherTest.java', 'assertFalse(matcher.matches(req("POST", "/rest/saiku/session/")))'],
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/security/SpringUrlRulesGuestExclusionTest.java', 'assertTrue(granted(mainChain, anon, "/rest/saiku/session/"))'],
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/security/SpringUrlRulesGuestExclusionTest.java', 'assertFalse(granted(mainChain, anon, "/rest/saiku/session/clear"))'],
  ['saiku-launcher/src/test/java/org/saiku/launcher/it/CsrfIT.java', 'URI.create(baseUrl + "/rest/saiku/session/")']
];
for (const [file, needle] of pins) {
  if (!read(file).includes(needle)) fail(`S3: ${file} does not pin the trailing-slash login (${needle})`);
}

if (!base) {
  console.log('#2439: static checks pass (S1-S3); ACCEPTANCE_BASE_URL is unset, so the live C1/C2 checks did not run');
  process.exit(0);
}
if (!user || !password) fail('ACCEPTANCE_USER / ACCEPTANCE_PASSWORD are not set');

const form = (u, p) => new URLSearchParams({ username: u, password: p }).toString();

async function send(path, init = {}) {
  return fetch(base + path, { redirect: 'manual', ...init });
}

/** The last `name=value` pair the response set for a cookie (login rotates the session id). */
function cookieFrom(response, name) {
  const all = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  const pairs = all.map((c) => c.split(';')[0]).filter((c) => c.startsWith(`${name}=`));
  return pairs.length ? pairs[pairs.length - 1] : null;
}

// ---- C1: the exact login the demo pages make ------------------------------------------------------------
const login = await send('/rest/saiku/session/', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form(user, password)
});
const loginBody = await login.text();
if (login.status !== 200) {
  fail(`C1: POST /rest/saiku/session/ (the demo pages' login) answered ${login.status}, expected 200: ${loginBody.slice(0, 200)}`);
}
const session = cookieFrom(login, 'JSESSIONID');
if (!session) fail('C1: the trailing-slash login answered 200 but set no JSESSIONID cookie');

const who = await send('/rest/saiku/session', { headers: { Accept: 'application/json', Cookie: session } });
const whoBody = await who.text();
if (who.status !== 200) fail(`C1: GET /rest/saiku/session with the login cookie answered ${who.status}, expected 200`);
let whoJson;
try {
  whoJson = JSON.parse(whoBody);
} catch {
  fail(`C1: GET /rest/saiku/session did not answer JSON: ${whoBody.slice(0, 200)}`);
}
if (whoJson.username !== user) {
  fail(`C1: the login cookie does not authenticate as ${user}; session reports ${JSON.stringify(whoJson.username)}`);
}

// ---- C2: nothing beneath /rest/saiku/session/ is reopened -----------------------------------------------
for (const sub of ['/rest/saiku/session/clear', '/rest/saiku/session/anything']) {
  const post = await send(sub, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form(user, 'not-the-password')
  });
  await post.arrayBuffer();
  if (![403, 404, 405].includes(post.status)) {
    fail(`C2: anonymous POST ${sub} answered ${post.status}, expected it refused (403, 404 or 405)`);
  }
}
const get = await send('/rest/saiku/session/anything', { headers: { Accept: 'application/json' } });
await get.arrayBuffer();
if (![401, 403].includes(get.status)) {
  fail(`C2: anonymous GET /rest/saiku/session/anything answered ${get.status}, expected 401 or 403 (not permitAll)`);
}

console.log('#2439: static checks pass; trailing-slash login signs in; nothing beneath /rest/saiku/session/ is reopened');
