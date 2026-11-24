// The acceptance spec for spiculedata/saiku#2539, run as a command spec.
//
// The AI schema cache was keyed by the CALLER's spelling of
// connection/catalog/schema/cube while the cube lookup itself is
// case-insensitive with wildcard catalog/schema, so every case variant of one
// cube built and stored its own ~40 KB schema in an UNBOUNDED ConcurrentHashMap
// — a heap-growth vector for any logged-in user (also via MCP describe_cube).
// The fix resolves the cube first and keys the cache on the resolved cube's
// canonical identifiers, and replaces both caches (schema + PII suggestions)
// with bounded Caffeine caches (maximum size + expiry).
//
// Without ACCEPTANCE_BASE_URL (CI gives command specs no launcher) it checks
// the source of the tree it runs in:
//   S1 (C2): schemaCache and piiSuggestionsCache are Caffeine caches built with
//            maximumSize and an expireAfter policy — no raw ConcurrentHashMaps.
//   S2 (C1): getSchema resolves the cube (findCube) BEFORE touching the cache
//            and keys it on the resolved cube's canonical identifiers; the
//            PII suggestions path resolves too.
//   S3 (C1/C2): the unit tests pin it — a cold-build-counting case-variant
//            test, a wildcard test, and a fill-past-the-bound test ship.
//   S4 (C3): the saiku#2326 visibility pins are untouched.
// With ACCEPTANCE_BASE_URL set it also checks a live launcher:
//   C1: two describe requests differing only in letter case both answer 200
//       and carry the SAME canonical cubeId.
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
  console.error(`#2539: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

const SERVICE = 'saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/OlapAiCubeMetadataService.java';

/** Body of the first method whose declaration matches `re` (brace-matched). */
function methodBody(src, re) {
  const m = re.exec(src);
  if (!m) return null;
  const open = src.indexOf('{', m.index);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(m.index, i + 1);
    }
  }
  return null;
}

// ---- S1 (C2): both caches are bounded Caffeine caches ----------------------------------------------
const svcSrc = read(SERVICE);
const rawMaps = [...svcSrc.matchAll(/new ConcurrentHashMap<>\(\)/g)].length;
if (rawMaps > 0) {
  fail(`S1: the AI schema caches are still unbounded ConcurrentHashMaps (${rawMaps} found) — saiku#2539 requires bounded caches`);
}
const caffeineBuilders = [...svcSrc.matchAll(/Caffeine\.newBuilder\(\)/g)].length;
if (caffeineBuilders < 2) {
  fail(`S1: expected Caffeine caches for BOTH schemaCache and piiSuggestionsCache (found ${caffeineBuilders} Caffeine.newBuilder() builders)`);
}
if (!svcSrc.includes('com.github.benmanes.caffeine.cache.Cache')) {
  fail('S1: the cache fields must be caffeine Cache declarations');
}
const maximumSizePins = [...svcSrc.matchAll(/\.maximumSize\(/g)].length;
if (maximumSizePins < 2) {
  fail(`S1: both caches need a maximum size (found ${maximumSizePins} .maximumSize( calls)`);
}
const expiryPins = [...svcSrc.matchAll(/\.expireAfter(Write|Access)\(/g)].length;
if (expiryPins < 2) {
  fail(`S1: both caches need an expiry (found ${expiryPins} .expireAfter* calls)`);
}

// ---- S2 (C1): the key is derived from the RESOLVED cube ---------------------------------------------
const getSchema = methodBody(svcSrc, /public AiSchema getSchema\(AiCubeRef ref\)/);
if (!getSchema) fail('S2: getSchema(AiCubeRef) not found');
if (!/SaikuCube cube = findCube\(ref\)/.test(getSchema)) {
  fail('S2: getSchema must resolve the cube through findCube(ref) BEFORE consulting the cache (the saiku#2326 fail-closed rule on every path)');
}
const cacheRead = getSchema.indexOf('schemaCache.getIfPresent');
const resolved = getSchema.indexOf('SaikuCube cube = findCube(ref)');
if (cacheRead < 0 || resolved < 0 || cacheRead < resolved) {
  fail('S2: the cache lookup must come AFTER the caller-scoped findCube resolution');
}
if (/cacheKey\(ref\)/.test(getSchema) || !/cacheKey\(cube\)/.test(getSchema)) {
  fail('S2: the cache key must be derived from the RESOLVED cube (cacheKey(cube)), not the caller\'s spelling (cacheKey(ref))');
}
if (!methodBody(svcSrc, /private String cacheKey\(SaikuCube cube\)/)) {
  fail('S2: cacheKey must take the resolved SaikuCube and use its canonical identifiers');
}
const getPii = methodBody(svcSrc, /public List<PiiScanner\.Match> getPiiSuggestions\(AiCubeRef ref\)/);
if (!getPii || !/findCube\(ref\)/.test(getPii) || !/cacheKey\(cube\)/.test(getPii)) {
  fail('S2: getPiiSuggestions must resolve the cube too and key on the resolved identifiers');
}

// ---- S3 (C1/C2): the tests pin the behaviour --------------------------------------------------------
const CACHE_TEST = 'saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/OlapAiCubeMetadataServiceCacheKeyTest.java';
const cacheTest = read(CACHE_TEST);
for (const pin of [
  'caseVariantsShareOneColdBuild',
  'wildcardCatalogAndSchemaShareOneColdBuild',
  'cachesAreBounded_fillPastMaximumSizeRebuildsOldest',
]) {
  if (!cacheTest.includes(pin)) fail(`S3: ${CACHE_TEST} does not pin ${pin}`);
}

// ---- S4 (C3): the saiku#2326 visibility pins stay ----------------------------------------------------
const visTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/OlapAiCubeMetadataServiceVisibilityTest.java');
for (const pin of [
  'getSchema_cacheHitForDifferentRoleSet_rebuildsUnderCallerRole',
  'getSchema_cacheHit_whenCubeNotVisibleToCaller_throwsAiValidationException',
  'getSchema_sameRoleSet_sharesEntry',
]) {
  if (!visTest.includes(pin)) fail(`S4: the saiku#2326 pin ${pin} disappeared from OlapAiCubeMetadataServiceVisibilityTest`);
}

if (!base) {
  console.log('#2539: static checks pass (S1-S4); ACCEPTANCE_BASE_URL is unset, so the live C1 check did not run');
  process.exit(0);
}
if (!user || !password) fail('ACCEPTANCE_USER / ACCEPTANCE_PASSWORD are not set');

// ---- C1 (live): case variants describe the SAME canonical cube ---------------------------------------
const login = await fetch(base + '/rest/saiku/session/', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ username: user, password }).toString(),
  redirect: 'manual',
});
if (!login.ok) fail(`C1: login failed with ${login.status}`);
const cookie = (typeof login.headers.getSetCookie === 'function' ? login.headers.getSetCookie() : [])
  .map((c) => c.split(';')[0]).find((c) => c.startsWith('JSESSIONID='));
if (!cookie) fail('C1: login set no session cookie');

const cubeId = (conn, cat, schema, cube) => `${encodeURIComponent(conn)}/${encodeURIComponent(cat)}/${encodeURIComponent(schema)}/${encodeURIComponent(cube)}`;
const paths = [
  `/ai/schema/${cubeId('foodmart', 'FoodMart', 'FoodMart', 'Sales')}`,
  `/ai/schema/${cubeId('foodMART', 'FoodMart', 'FoodMart', 'sAlEs')}`,
];
const bodies = [];
for (const p of paths) {
  const res = await fetch(base + p, { headers: { Cookie: cookie } });
  if (res.status !== 200) fail(`C1: GET ${p} answered ${res.status}`);
  const body = await res.json();
  if (!body || typeof body.cubeId !== 'string') fail(`C1: GET ${p} returned no canonical cubeId`);
  bodies.push(body);
}
if (bodies[0].cubeId !== bodies[1].cubeId) {
  fail(`C1: case variants of one cube describe different cubeIds: ${bodies[0].cubeId} vs ${bodies[1].cubeId}`);
}

console.log('#2539: live checks pass — case variants of one cube share the canonical cubeId');
