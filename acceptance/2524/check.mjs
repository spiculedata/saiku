// The acceptance spec for spiculedata/saiku#2524, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2524`
// (and by acceptance-spec's shared runner in CI).
//
// #2524 is a server-side session-creation bug: every UNAUTHENTICATED request to a
// protected /rest/** URL got a 401 AND a fresh JSESSIONID — Spring's
// HttpSessionRequestCache saved the refused request (URL, query, all headers) in a
// new session, and the launcher persisted it to <saiku-home>/sessions/ for seven
// days. The caller controls the size through its headers, so this is an
// unauthenticated heap/disk-filling vector.
//
// There is no endpoint to answer the bug with, so this is a command spec over the
// live HTTP surface: a refused request must set no JSESSIONID cookie (the CSRF
// token cookie is fine — that one is not a session), while a SUCCESSFUL login must
// still set JSESSIONID so saiku#1859's persisted logged-in sessions are unchanged.
// Exit non-zero on the first broken clause, with the reason on stderr, so the
// runner's "exit code" detail says what broke.

const baseUrl = (process.env.ACCEPTANCE_BASE_URL ?? process.env.SAIKU_BASE_URL ?? '').replace(/\/+$/, '');
if (!baseUrl) {
  console.error('ACCEPTANCE_BASE_URL is not configured — cannot run the #2524 session-creation spec');
  process.exit(2);
}

const user = process.env.ACCEPTANCE_USER ?? '';
const password = process.env.ACCEPTANCE_PASSWORD ?? '';

/** Every cookie the response set whose name is JSESSIONID (XSRF-TOKEN etc. don't count). */
function sessionCookies(res) {
  const setCookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  return setCookies.filter((c) => /^JSESSIONID=/i.test(c.trim()));
}

function fail(criterion, detail) {
  console.error(`#2524 ${criterion} FAIL: ${detail}`);
  process.exit(1);
}

function request(method, path, headers = {}, body) {
  const init = { method, headers, redirect: 'manual' };
  if (body !== undefined) init.body = body;
  return fetch(new URL(path, baseUrl), init);
}

// ── C1: an unauthenticated request to a protected URL creates no session ──────
{
  const res = await request('GET', '/rest/saiku/api/repository');
  if (res.status !== 401) fail('C1', `GET /rest/saiku/api/repository with no credentials must 401, got ${res.status}`);
  const cookies = sessionCookies(res);
  if (cookies.length > 0) {
    fail('C1', `a refused unauthenticated request must not create a session, but the 401 set ${cookies.join(' | ')}`);
  }
}

// Same request padded with a caller-controlled header — the size vector the issue
// reproduces with: the saved request (headers included) used to end up in the session.
{
  const res = await request('GET', '/rest/saiku/api/repository/padded-2524', {
    'X-Pad': 'A'.repeat(6000),
  });
  if (res.status !== 401) fail('C1', `the padded unauthenticated request must still 401, got ${res.status}`);
  const cookies = sessionCookies(res);
  if (cookies.length > 0) {
    fail('C1', `the padded refused request must not create a session, but the 401 set ${cookies.join(' | ')}`);
  }
}

// ── C2: a failed login leaves no 7-day session behind ──────────────────────────
{
  const res = await request(
    'POST',
    '/rest/saiku/session',
    { 'Content-Type': 'application/x-www-form-urlencoded' },
    'username=admin&password=definitely-not-the-password-2524',
  );
  if (res.status !== 401) fail('C2', `a bad-password login must 401, got ${res.status}`);
  const cookies = sessionCookies(res);
  if (cookies.length > 0) {
    fail('C2', `a failed login must not create a session, but the 401 set ${cookies.join(' | ')}`);
  }
}

{
  const basic = Buffer.from('admin:also-not-the-password-2524').toString('base64');
  const res = await request('GET', '/rest/saiku/api/repository', { Authorization: `Basic ${basic}` });
  if (res.status !== 401) fail('C2', `wrong Basic credentials must 401, got ${res.status}`);
  const cookies = sessionCookies(res);
  if (cookies.length > 0) {
    fail('C2', `wrong Basic credentials must not create a session, but the 401 set ${cookies.join(' | ')}`);
  }
}

// ── C3: a successful login still gets a real, persisted session ────────────────
if (!user || !password) {
  console.error('#2524 C3 skipped check: ACCEPTANCE_USER / ACCEPTANCE_PASSWORD not configured');
} else {
  const res = await request(
    'POST',
    '/rest/saiku/session',
    { 'Content-Type': 'application/x-www-form-urlencoded' },
    `username=${encodeURIComponent(user)}&password=${encodeURIComponent(password)}`,
  );
  if (res.status !== 200) fail('C3', `a correct login must still succeed with 200, got ${res.status}`);
  if (sessionCookies(res).length === 0) {
    fail('C3', 'a successful login must still set JSESSIONID (saiku#1859 persisted sessions)');
  }
  const info = await request('GET', '/rest/saiku/session', {
    Cookie: sessionCookies(res)[0].split(';')[0],
  });
  if (info.status !== 200) fail('C3', `the session created by a correct login must be usable, got ${info.status}`);
}

console.log('#2524 OK: refused requests create no session, successful login still persists one');
