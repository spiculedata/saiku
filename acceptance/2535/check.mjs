// #2535 — REST request-body size limits + repository/history/comment object caps.
//
// Without ACCEPTANCE_BASE_URL (CI gives command specs no launcher only when none is
// configured) it checks the source of the tree it runs in:
//   S1: web.xml declares the servlet-level RestBodySizeFilter mapped to /rest/* and
//       BEFORE the Spring Security filterChainProxy, so the pre-auth login body is
//       capped before authentication runs;
//   S2: the filter reads the three configurable caps (default REST body, session
//       route, upload routes) and answers 413;
//   S3: the repository save path refuses oversized objects — 413 in
//       BasicRepositoryResource2.saveResource and a backstop failure string in
//       RepositoryDatasourceManager.saveFile;
//   S4: DashboardHistoryService caps an archived entry and CommentService caps a
//       comment body and the live comment count;
//   S5: docs/operator-hardening.md documents every key.
// With ACCEPTANCE_BASE_URL set it also checks a live launcher:
//   C1: authenticated POST /rest/saiku/api/repository/resource with a 30 MB content
//       field answers 413 (and the object is not stored);
//   C2: anonymous POST /rest/saiku/session with a 5 MB body answers 413;
//   C3: a small login answers 200 and a small repo save/read/delete round-trips;
//   C4: a 1x1 PNG upload to /rest/saiku/api/dashboards/image/upload is accepted.
//
// Exit non-zero on the first broken clause, with the reason on stderr.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const base = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/+$/, '');
const user = process.env.ACCEPTANCE_USER;
const password = process.env.ACCEPTANCE_PASSWORD;

const KEYS = [
  'saiku.http.maxBodyBytes',
  'saiku.http.sessionMaxBodyBytes',
  'saiku.http.uploadMaxBodyBytes',
  'saiku.repo.maxObjectBytes',
  'saiku.history.maxEntryBytes',
  'saiku.comments.maxBodyBytes',
  'saiku.comments.maxCount'
];

function fail(message) {
  console.error(`#2535: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

// ---- S1: the web.xml wiring --------------------------------------------------------------------------
const webXml = read('saiku-webapp/src/main/webapp/WEB-INF/web.xml');
const filterDecl = webXml.indexOf('<filter-class>org.saiku.web.servlet.RestBodySizeFilter</filter-class>');
if (filterDecl < 0) fail('S1: web.xml does not declare org.saiku.web.servlet.RestBodySizeFilter');
const mapping = webXml.indexOf('/rest/*', filterDecl);
if (mapping < 0) fail('S1: the RestBodySizeFilter is not mapped to /rest/*');
const securityProxy = webXml.indexOf('org.springframework.security.filterChainProxy');
if (securityProxy < 0) fail('S1: web.xml has no filterChainProxy declaration (unexpected)');
if (filterDecl > securityProxy) {
  fail('S1: the RestBodySizeFilter must be declared BEFORE the security filterChainProxy so the pre-auth login body is capped first');
}

// ---- S2: the filter and its configurable caps --------------------------------------------------------
const filterSrc = read('saiku-core/saiku-web/src/main/java/org/saiku/web/servlet/RestBodySizeFilter.java');
for (const key of ['saiku.http.maxBodyBytes', 'saiku.http.sessionMaxBodyBytes', 'saiku.http.uploadMaxBodyBytes']) {
  if (!filterSrc.includes(key)) fail(`S2: RestBodySizeFilter does not read ${key}`);
}
if (!filterSrc.includes('413')) fail('S2: RestBodySizeFilter does not answer 413');

// ---- S3: the repository object cap -------------------------------------------------------------------
const repoSrc = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/BasicRepositoryResource2.java');
if (!repoSrc.includes('saiku.repo.maxObjectBytes') || !repoSrc.includes('REQUEST_ENTITY_TOO_LARGE')) {
  fail('S3: BasicRepositoryResource2.saveResource does not enforce saiku.repo.maxObjectBytes with a 413');
}
const managerSrc = read('saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/RepositoryDatasourceManager.java');
if (!managerSrc.includes('saiku.repo.maxObjectBytes')) {
  fail('S3: RepositoryDatasourceManager.saveFile has no saiku.repo.maxObjectBytes backstop');
}

// ---- S4: history entry cap + comment body/count caps -------------------------------------------------
const historySrc = read('saiku-core/saiku-service/src/main/java/org/saiku/service/history/DashboardHistoryService.java');
if (!historySrc.includes('saiku.history.maxEntryBytes')) {
  fail('S4: DashboardHistoryService does not enforce saiku.history.maxEntryBytes');
}
const commentSrc = read('saiku-core/saiku-service/src/main/java/org/saiku/service/comments/CommentService.java');
for (const key of ['saiku.comments.maxBodyBytes', 'saiku.comments.maxCount']) {
  if (!commentSrc.includes(key)) fail(`S4: CommentService does not enforce ${key}`);
}

// ---- S5: the operator documentation ------------------------------------------------------------------
const doc = read('docs/operator-hardening.md');
for (const key of KEYS) {
  if (!doc.includes(key)) fail(`S5: docs/operator-hardening.md does not document ${key}`);
}

if (!base) {
  console.log('#2535: static checks pass (S1-S5); ACCEPTANCE_BASE_URL is unset, so the live C1-C4 checks did not run');
  process.exit(0);
}
if (!user || !password) fail('ACCEPTANCE_USER / ACCEPTANCE_PASSWORD are not set');

const form = (u, p) => new URLSearchParams({ username: u, password: p }).toString();

async function send(path, init = {}) {
  return fetch(base + path, { redirect: 'manual', ...init });
}

/** All cookies the response set, `name=value` pairs (last one wins on rotation). */
function cookiesFrom(response) {
  const all = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  return all.map((c) => c.split(';')[0]);
}

function cookieFrom(response, name) {
  const pairs = cookiesFrom(response).filter((c) => c.startsWith(`${name}=`));
  return pairs.length ? pairs[pairs.length - 1] : null;
}

// ---- C3 (first half): a normal small login still works ----------------------------------------------
const login = await send('/rest/saiku/session', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: form(user, password)
});
await login.arrayBuffer();
if (login.status !== 200) {
  fail(`C3: normal POST /rest/saiku/session answered ${login.status}, expected 200 — the caps must not block small bodies`);
}
let cookies = cookiesFrom(login);
// One authenticated GET so the CSRF cookie materialises for the later POSTs.
const who = await send('/rest/saiku/session', { headers: { Accept: 'application/json', Cookie: cookies.join('; ') } });
await who.arrayBuffer();
cookies = [...new Set([...cookies, ...cookiesFrom(who)])];
const sessionCookie = cookieFrom({ headers: { getSetCookie: () => cookies } }, 'JSESSIONID');
const csrfCookie = cookieFrom({ headers: { getSetCookie: () => cookies } }, 'XSRF-TOKEN');
if (!sessionCookie) fail('C3: login answered 200 but set no JSESSIONID cookie');
const authHeaders = {
  Cookie: [...cookies].join('; '),
  ...(csrfCookie
    ? { 'X-XSRF-TOKEN': decodeURIComponent(csrfCookie.substring(csrfCookie.indexOf('=') + 1)) }
    : {})
};

// ---- C1: a 30 MB repository save answers 413 --------------------------------------------------------
const bigContent = 'x'.repeat(30 * 1024 * 1024);
const bigForm = new URLSearchParams({ file: `homes/${user}/2535-spec-big.txt`, content: bigContent });
const big = await send('/rest/saiku/api/repository/resource', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...authHeaders },
  body: bigForm.toString()
});
const bigBody = await big.text();
if (big.status !== 413) {
  fail(`C1: POST /rest/saiku/api/repository/resource with a 30 MB content field answered ${big.status}, expected 413: ${bigBody.slice(0, 200)}`);
}
// The oversized object must not be stored — a read of the same path must not return it.
const probe = await send(`/rest/saiku/api/repository/resource?file=${encodeURIComponent(`homes/${user}/2535-spec-big.txt`)}`, {
  headers: { Cookie: authHeaders.Cookie }
});
const probeBody = await probe.text();
if (probe.status === 200 && probeBody.includes('xxxxxxx')) {
  fail('C1: the 30 MB object was stored in the repository despite the 413');
}

// ---- C2: the anonymous pre-auth login refuses a 5 MB body -------------------------------------------
const junkForm = new URLSearchParams({ username: 'x', password: '' }) + '&junk=' + 'a'.repeat(5 * 1024 * 1024);
const junk = await send('/rest/saiku/session', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: junkForm
});
await junk.arrayBuffer();
if (junk.status !== 413) {
  fail(`C2: anonymous POST /rest/saiku/session with a 5 MB body answered ${junk.status}, expected 413`);
}

// ---- C3 (second half): a small save/read/delete round-trip ------------------------------------------
const smallForm = new URLSearchParams({ file: `homes/${user}/2535-spec.txt`, content: 'hello #2535' });
const small = await send('/rest/saiku/api/repository/resource', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...authHeaders },
  body: smallForm.toString()
});
await small.arrayBuffer();
if (small.status !== 200) {
  fail(`C3: small repository save answered ${small.status}, expected 200`);
}
const readBack = await send(`/rest/saiku/api/repository/resource?file=${encodeURIComponent(`homes/${user}/2535-spec.txt`)}`, {
  headers: { Cookie: authHeaders.Cookie }
});
const readBackBody = await readBack.text();
if (readBack.status !== 200 || !readBackBody.includes('hello #2535')) {
  fail(`C3: the small saved object did not read back (status ${readBack.status})`);
}
const del = await send(`/rest/saiku/api/repository/resource?file=${encodeURIComponent(`homes/${user}/2535-spec.txt`)}`, {
  method: 'DELETE',
  headers: { ...authHeaders }
});
await del.arrayBuffer();
if (del.status !== 200) fail(`C3: delete of the spec object answered ${del.status}, expected 200`);

// ---- C4: a legitimate image upload is still accepted ------------------------------------------------
// 1x1 PNG (67 bytes) — under the documented 2 MB image limit, so the upload route cap must let it through.
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);
const fd = new FormData();
fd.append('file', new Blob([png], { type: 'image/png' }), 'spec-2535.png');
fd.append('tileId', 'spec2535');
const upload = await send('/rest/saiku/api/dashboards/image/upload', {
  method: 'POST',
  headers: { ...authHeaders },
  body: fd
});
const uploadBody = await upload.text();
if (upload.status === 413) {
  fail('C4: a legitimate 1x1 PNG upload answered 413 — the image upload route must keep its documented headroom');
}
if (upload.status !== 200) {
  fail(`C4: image upload answered ${upload.status}, expected 200: ${uploadBody.slice(0, 200)}`);
}
// Clean up the stored image asset (best-effort; the resource writes it as homes/<user>/spec2535.png).
await send(`/rest/saiku/api/repository/resource?file=${encodeURIComponent(`homes/${user}/spec2535.png`)}`, {
  method: 'DELETE',
  headers: { ...authHeaders }
}).then((r) => r.arrayBuffer());

console.log('#2535: 30 MB repository save → 413, 5 MB anonymous login body → 413, small requests still work, image upload accepted');
