// The acceptance spec for spiculedata/saiku#2439, run as a command spec against a live launcher.
//
// The demo pages (embed-demo, aqvira-demo, saiku-ui/static/showcase, saiku-showcase) sign in with a
// form-encoded POST to `/rest/saiku/session/` (trailing slash). An api spec can only send a JSON body and the
// login consumes application/x-www-form-urlencoded, so this check makes that exact request itself.
//
// C1: the trailing-slash login answers 200, sets a session cookie, and that cookie alone authenticates a
//     later call as the same user.
// C2: nothing BENEATH /rest/saiku/session/ is reopened (#2368 closed /session/clear): an anonymous POST with
//     credentials is refused and never 200, and an anonymous GET is not permitted.
//
// Exit non-zero on the first broken clause, with the reason on stderr.

const base = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/+$/, '');
const user = process.env.ACCEPTANCE_USER;
const password = process.env.ACCEPTANCE_PASSWORD;

function fail(message) {
  console.error(`#2439: ${message}`);
  process.exit(1);
}

if (!base) fail('ACCEPTANCE_BASE_URL is not set; this spec needs a running launcher');
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

console.log('#2439: trailing-slash login signs in; nothing beneath /rest/saiku/session/ is reopened');
