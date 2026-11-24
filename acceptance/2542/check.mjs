/**
 * acceptance/2542 — AI and MCP surface follow-ups (seven small items).
 *
 * Two halves:
 *  1. Live HTTP checks against the launcher (when ACCEPTANCE_BASE_URL is set):
 *     C1 MCP session clamping + principal binding, C2 catalogue role gates,
 *     C3 outbound-registration URL / header-name gates.
 *  2. Static source checks that hold for every run (the CI command runner has no
 *     guaranteed server): C4 provider wiring, C5 system-prompt channel, C6 full
 *     member redaction, C7 response byte cap.
 *
 * Exit 0 = all checks pass.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const specDir = dirname(fileURLToPath(import.meta.url));
const root = join(specDir, '..', '..');

const failures = [];
function fail(msg) {
  failures.push(msg);
}

/* ----------------------------- source checks ----------------------------- */

function src(rel) {
  return readFileSync(join(root, rel), 'utf8');
}

function methodBody(text, startMarker, endMarker) {
  const start = text.indexOf(startMarker);
  if (start < 0) return null;
  const end = text.indexOf(endMarker, start);
  return end < 0 ? text.slice(start) : text.slice(start, end);
}

// C5 — cellsetDigest / currentQuery must not be appended to the system prompt.
const anthropic = src('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/ask/AnthropicNlAskProvider.java');
const openai = src('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/ask/OpenAINlAskProvider.java');
if (/system\.append\("\n\nUser's current cellset/.test(anthropic)) {
  fail('C5: AnthropicNlAskProvider still appends the cellset digest to the system prompt');
}
if (/system\.append\("\n\nUser's CURRENT AiQueryRequest/.test(anthropic)) {
  fail('C5: AnthropicNlAskProvider still appends the current-query snapshot to the system prompt');
}
if (/sys\.append\("\n\nUser's current cellset/.test(openai)) {
  fail('C5: OpenAINlAskProvider still appends the cellset digest to the system prompt');
}
if (/sys\.append\("\n\nUser's CURRENT AiQueryRequest/.test(openai)) {
  fail('C5: OpenAINlAskProvider still appends the current-query snapshot to the system prompt');
}

// C6 — redaction at schema-only clears every member list + named-set expression.
const aiAskService = src('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/ask/AiAskService.java');
const redact = methodBody(aiAskService, 'private AiQueryRequest redact', '\n    /**');
if (redact === null) {
  fail('C6: AiAskService has no currentQuery redaction method');
} else {
  for (const needle of ['getRows()', 'getColumns()', 'setMembers', 'setExpression']) {
    if (!redact.includes(needle)) {
      fail(`C6: currentQuery redaction does not cover ${needle}`);
    }
  }
}

// C4 — the wiring passes the configured provider through, and the Anthropic-only
// services gate on it.
const beans = src('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const tileDescribe = src('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/describe/TileDescribeService.java');
const cubeDesigner = src('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/cubedesigner/CubeDesignerAiService.java');
if (!beans.includes('saiku.ai.ask.provider')) {
  fail('C4: saiku-beans.xml does not pass saiku.ai.ask.provider to the describe/designer beans');
}
if (!/anthropic/i.test(tileDescribe) || !tileDescribe.includes('provider')) {
  fail('C4: TileDescribeService does not gate on the configured provider');
}
if (!/anthropic/i.test(cubeDesigner) || !cubeDesigner.includes('provider')) {
  fail('C4: CubeDesignerAiService does not gate on the configured provider');
}

// C7 — the outbound client reads responses under a byte cap.
const client = src('saiku-core/saiku-service/src/main/java/org/saiku/service/mcp/outbound/McpOutboundClient.java');
if (!client.includes('MAX_RESPONSE_BYTES') || !client.includes('readNBytes')) {
  fail('C7: McpOutboundClient does not bound the response body read');
}

// C3 — the outbound parser reuses the webhook SSRF gate (registered URLs get the
// same https-only / public-address checks).
const parser = src('saiku-core/saiku-service/src/main/java/org/saiku/service/mcp/outbound/McpOutboundServerParser.java');
if (!parser.includes('WebhookUrlValidator')) {
  fail('C3: McpOutboundServerParser does not reuse WebhookUrlValidator for url checks');
}

/* ------------------------------ HTTP checks ------------------------------ */

const BASE = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/$/, '');
if (!BASE) {
  // Static checks only; report and exit.
  if (failures.length) {
    failures.forEach((f) => process.stderr.write(f + '\n'));
    process.exit(1);
  }
  process.stdout.write('acceptance/2542: source checks pass (no base URL; HTTP steps skipped)\n');
  process.exit(0);
}

// Keep the cookie jar minimal: session auth for admin, HTTP basic for bob (a
// pre-seeded demo ROLE_USER) — basic-bearing requests are CSRF-exempt.
function cookieOf(res) {
  const raw = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  return raw.map((c) => c.split(';')[0]).join('; ');
}

async function call(path, { method = 'GET', body, headers = {}, cookie, basic } = {}) {
  const h = { ...headers };
  if (body !== undefined && !h['content-type']) h['content-type'] = 'application/json';
  if (cookie) h.cookie = cookie;
  if (basic) h.authorization = 'Basic ' + Buffer.from(basic).toString('base64');
  const res = await fetch(BASE + path, {
    method,
    headers: h,
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
    redirect: 'manual',
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* not JSON — fine */
  }
  return { res, status: res.status, json, text, setCookie: res.headers.getSetCookie ? res.headers.getSetCookie() : [] };
}

const MCP = '/rest/saiku/api/mcp';
function rpcFrame(id, method, params) {
  return { jsonrpc: '2.0', id, method, params: params ?? {} };
}

// 1. admin session login (form-encoded; the session endpoint is CSRF-exempt)
const login = await call('/rest/saiku/session', {
  method: 'POST',
  headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: 'username=' + encodeURIComponent(process.env.ACCEPTANCE_USER || 'admin')
    + '&password=' + encodeURIComponent(process.env.ACCEPTANCE_PASSWORD || 'admin'),
});
if (login.status !== 200) {
  fail(`login refused with ${login.status} — cannot run HTTP checks`);
  failures.forEach((f) => process.stderr.write(f + '\n'));
  process.exit(1);
}
const adminCookie = cookieOf(login.res);
const xsrf =
  (login.setCookie.find((c) => c.startsWith('XSRF-TOKEN=')) || '').split(';')[0].split('=')[1] ?? '';

function adminHeaders(extra = {}) {
  return { cookie: adminCookie, 'x-xsrf-token': xsrf, ...extra };
}

// C1 — oversized protocolVersion is clamped, never echoed.
const bigVersion = '9'.repeat(300000);
const init = await call(MCP, {
  method: 'POST',
  headers: adminHeaders(),
  body: rpcFrame(1, 'initialize', { protocolVersion: bigVersion }),
});
if (init.status !== 200) {
  fail(`C1: oversized initialize answered ${init.status}, expected 200`);
} else if (init.text.length > 4000) {
  fail('C1: the oversized protocolVersion is echoed back in the response');
} else if (init.json?.result?.protocolVersion !== '2025-03-26') {
  fail(`C1: negotiated protocolVersion is ${init.json?.result?.protocolVersion}, expected the server's supported version`);
}
const sessionId = init.res.headers.get('mcp-session-id');
if (!sessionId) fail('C1: initialize did not mint a Mcp-Session-Id');

// C1 — an unknown session id is a transport-level 404.
const unknown = await call(MCP, {
  method: 'POST',
  headers: adminHeaders({ 'mcp-session-id': 'does-not-exist' }),
  body: rpcFrame(2, 'tools/list'),
});
if (unknown.status !== 404) {
  fail(`C1: tools/list with an unknown session id answered ${unknown.status}, expected 404`);
}

// C1 — a different principal cannot use the session (404, and the session survives).
const bob = 'bob:dylan';
const foreign = await call(MCP, {
  method: 'POST',
  headers: { 'mcp-session-id': sessionId, authorization: 'Basic ' + Buffer.from(bob).toString('base64') },
  body: rpcFrame(3, 'tools/list'),
});
if (foreign.status !== 404) {
  fail(`C1: a different principal's tools/list with the minted session answered ${foreign.status}, expected 404`);
}
if (sessionId) {
  const stillAlive = await call(MCP, {
    method: 'POST',
    headers: adminHeaders({ 'mcp-session-id': sessionId }),
    body: rpcFrame(4, 'tools/list'),
  });
  if (stillAlive.status !== 200) {
    fail(`C1: the foreign probe damaged the session (owner follow-up answered ${stillAlive.status})`);
  }
}

// C2 — non-admin refusals.
const bobAuth = { authorization: 'Basic ' + Buffer.from(bob).toString('base64') };
for (const [path, method, name] of [
  ['/rest/saiku/api/ai/skills/refresh', 'POST', 'skills/refresh'],
  ['/rest/saiku/api/ai/spaces/refresh', 'POST', 'spaces/refresh'],
  ['/rest/saiku/api/ai/certified/refresh', 'POST', 'certified/refresh'],
  ['/rest/saiku/api/ai/skills?errors=true', 'GET', 'skills?errors=true'],
  ['/rest/saiku/api/ai/spaces?errors=true', 'GET', 'spaces?errors=true'],
  ['/rest/saiku/api/ai/certified?errors=true', 'GET', 'certified?errors=true'],
  ['/rest/saiku/api/ai/certified/monthly-store-sales-by-country', 'GET', 'certified detail'],
  ['/rest/saiku/api/ai/skills/weekly-foodmart-rollup', 'GET', 'skill detail'],
]) {
  const r = await call(path, { method, headers: bobAuth });
  if (r.status !== 403) {
    fail(`C2: non-admin ${method} ${name} answered ${r.status}, expected 403`);
  }
}
// The list endpoints themselves stay open for a non-admin.
for (const path of ['/rest/saiku/api/ai/skills', '/rest/saiku/api/ai/spaces', '/rest/saiku/api/ai/certified']) {
  const r = await call(path, { headers: bobAuth });
  if (r.status !== 200) {
    fail(`C2: non-admin GET ${path.replace('/rest/saiku/api', '')} answered ${r.status}, expected 200 (list stays open)`);
  }
}

// C3 — outbound MCP registration refuses unsafe urls and restricted header names.
const SINK = '/rest/saiku/admin/mcp-servers/';
async function tryRegister(id, server) {
  const r = await call(SINK + id, { method: 'PUT', headers: adminHeaders(), body: server });
  // Best-effort cleanup if it was accepted (it must not be).
  if (r.status === 200) await call(SINK + id, { method: 'DELETE', headers: adminHeaders() });
  return r;
}
for (const [id, server, what] of [
  ['hive-ssrf-http', { id: 'hive-ssrf-http', name: 'x', url: 'http://mcp.example.com/mcp' }, 'plain http url'],
  ['hive-ssrf-loopback', { id: 'hive-ssrf-loopback', name: 'x', url: 'https://127.0.0.1:9911/mcp' }, 'loopback url'],
  ['hive-ssrf-meta', { id: 'hive-ssrf-meta', name: 'x', url: 'https://169.254.169.254/latest' }, 'link-local url'],
]) {
  const r = await tryRegister(id, server);
  if (r.status !== 400) {
    fail(`C3: registering an outbound MCP server with a ${what} answered ${r.status}, expected 400`);
  }
}
const hostHeader = await tryRegister('hive-ssrf-host', {
  id: 'hive-ssrf-host',
  name: 'x',
  url: 'https://example.com/mcp',
  authHeaderName: 'Host',
  authHeaderValue: 'evil',
});
if (hostHeader.status !== 400) {
  fail(`C3: registering a restricted header name (Host) answered ${hostHeader.status}, expected 400`);
}

/* -------------------------------- verdict -------------------------------- */

if (failures.length) {
  failures.forEach((f) => process.stderr.write(f + '\n'));
  process.exit(1);
}
process.stdout.write('acceptance/2542: all checks pass\n');
