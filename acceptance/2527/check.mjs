// The acceptance spec for spiculedata/saiku#2527, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2527` or the
// shared runner in CI. Talks to a running launcher (loopback in CI):
//
//   POST /rest/saiku/{user}/export/saiku/chart (form-encoded, type=png)
//
// The chart export is exercised over HTTP because the contract is what a
// logged-in user gets back, not an internal helper: C1 wants a typed 4xx for an
// SVG whose intrinsic dimensions exceed the pixel budget, C2 and C3 want the
// returned PNG's raster size (read from its IHDR chunk) to stay within the
// budget, and C4 wants an ordinary export unchanged. On the base branch C1
// returns 200 with a 3000 px wide PNG and C2 returns a 3200 px tall PNG, so the
// spec is red on base and green on head. Spring rotates the CSRF token at
// login, so the POSTs use the fresh token from an authenticated GET afterward.

const BASE_URL = process.env.ACCEPTANCE_BASE_URL || 'http://127.0.0.1:18080';
const USER = process.env.ACCEPTANCE_USER || 'admin';
const PASSWORD = process.env.ACCEPTANCE_PASSWORD || 'admin';
const MAX = 2000; // the documented default pixel budget per side

/** The four exports the criteria ask about. */
const CHARTS = {
  C1: {
    name: 'oversized intrinsic dimensions, no size',
    svg:
      '<svg xmlns="http://www.w3.org/2000/svg" width="3000" height="10">' +
      '<rect width="1" height="1" fill="red"/></svg>',
    size: undefined,
    expect: { status: [400, 413, 422] },
  },
  C2: {
    name: 'extreme aspect ratio with size',
    svg:
      '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="100">' +
      '<rect width="1" height="1" fill="red"/></svg>',
    size: 32,
    expect: { status: 200 },
    maxSides: true,
  },
  C3: {
    name: 'percentage dimensions with size',
    svg:
      '<svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 40 40">' +
      '<rect width="1" height="1" fill="red"/></svg>',
    size: 512,
    expect: { status: 200 },
    maxSides: true,
  },
  C4: {
    name: 'ordinary chart, unchanged',
    svg:
      '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">' +
      '<rect width="1" height="1" fill="red"/></svg>',
    size: undefined,
    expect: { status: 200 },
    exact: [800, 600],
  },
};

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

function sessionCookieJar(resp) {
  const jar = {};
  for (const cookie of resp.headers.getSetCookie()) {
    const [pair] = cookie.split(';');
    const idx = pair.indexOf('=');
    if (idx > 0) jar[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
  }
  return jar;
}

function cookieHeader(cookies) {
  return Object.entries(cookies)
    .map(([k, v]) => `${k}=${v}`)
    .join('; ');
}

async function login() {
  const body = new URLSearchParams({ username: USER, password: PASSWORD });
  const resp = await fetch(`${BASE_URL}/rest/saiku/session`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' },
    body: body.toString(),
    redirect: 'manual',
  });
  if (resp.status !== 200) {
    throw new Error(`login as ${USER} failed with ${resp.status} — check ACCEPTANCE_USER/ACCEPTANCE_PASSWORD`);
  }
  const loginCookies = sessionCookieJar(resp);
  if (!loginCookies.JSESSIONID) throw new Error(`login as ${USER} returned no JSESSIONID cookie`);

  // The login response's XSRF-TOKEN belongs to the pre-login session. A GET
  // with only the new JSESSIONID materializes the token for the authenticated
  // one; echoing the stale token on that GET would suppress a new cookie.
  const probe = await fetch(`${BASE_URL}/rest/saiku/session`, {
    headers: { accept: 'application/json', cookie: cookieHeader({ JSESSIONID: loginCookies.JSESSIONID }) },
    redirect: 'manual',
  });
  if (probe.status !== 200) throw new Error(`session check after login as ${USER} returned ${probe.status}`);
  const session = await probe.json();
  if (session.username !== USER) {
    throw new Error(`session check after login as ${USER} returned ${JSON.stringify(session.username)}`);
  }
  const freshCookies = sessionCookieJar(probe);
  if (!freshCookies['XSRF-TOKEN']) throw new Error(`session check after login as ${USER} returned no XSRF-TOKEN`);
  return { JSESSIONID: loginCookies.JSESSIONID, ...freshCookies };
}

async function exportChart(cookies, svg, size) {
  const form = new URLSearchParams({ type: 'png', svg });
  if (size !== undefined) form.set('size', String(size));
  const headers = {
    'content-type': 'application/x-www-form-urlencoded',
    accept: 'image/png',
    cookie: cookieHeader(cookies),
  };
  const xsrf = cookies['XSRF-TOKEN'];
  if (xsrf) headers['x-xsrf-token'] = xsrf;
  return fetch(`${BASE_URL}/rest/saiku/${encodeURIComponent(USER)}/export/saiku/chart`, {
    method: 'POST',
    headers,
    body: form.toString(),
    redirect: 'manual',
  });
}

/** PNG IHDR: signature (8) + length (4) + "IHDR" (4) -> width at 16, height at 20. */
function pngDimensions(bytes) {
  if (bytes.length < 24) return null;
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  for (let i = 0; i < sig.length; i++) if (bytes[i] !== sig[i]) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes[12] !== 0x49 || bytes[13] !== 0x48 || bytes[14] !== 0x44 || bytes[15] !== 0x52) return null;
  return [view.getUint32(16), view.getUint32(20)];
}

const cookies = await login();

for (const [id, chart] of Object.entries(CHARTS)) {
  const resp = await exportChart(cookies, chart.svg, chart.size);
  claim(id, `${chart.name}: expected ${JSON.stringify(chart.expect.status)}, got ${resp.status}`, Array.isArray(
    chart.expect.status
  )
    ? chart.expect.status.includes(resp.status)
    : resp.status === chart.expect.status);

  if (chart.expect.status === 200 || (Array.isArray(chart.expect.status) && chart.expect.status.includes(200))) {
    const bytes = new Uint8Array(await resp.arrayBuffer());
    const dims = pngDimensions(bytes);
    claim(id, `${chart.name}: body is not a readable PNG`, dims !== null);
    if (dims) {
      const [w, h] = dims;
      claim(id, `${chart.name}: PNG is ${w}x${h}, wider than the ${MAX} px budget`, w <= MAX);
      claim(id, `${chart.name}: PNG is ${w}x${h}, taller than the ${MAX} px budget`, h <= MAX);
      if (chart.exact) {
        claim(id, `${chart.name}: PNG is ${w}x${h}, expected exactly ${chart.exact[0]}x${chart.exact[1]}`,
          w === chart.exact[0] && h === chart.exact[1]);
      }
    }
  }
}

if (failures.length > 0) {
  console.error(failures.map((f) => `FAIL ${f}`).join('\n'));
  process.exit(1);
}
console.log('acceptance/2527: chart export raster bounds hold');
