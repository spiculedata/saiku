// saiku#2538 acceptance spec, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2538`.
//
// #2538 is a cross-user cache leak on pass-through datasources: the cellset
// cache key, the in-flight query coalescer and the Arrow result cache are
// keyed by query + cube version + ROLE set. On a security.type=passthrough
// datasource every user connects to the warehouse with their own credentials,
// so two users with the SAME Saiku roles can legitimately see different rows
// — yet they share one cache key, and the second user receives the first
// user's cellset (and the coalescer even registers the other user's CellSet
// in the caller's context, so a following drillthrough runs on the other
// user's connection). The AI schema cache already solves this for the same
// datasource class with a per-user visibility key (saiku#2326); #2538 reuses
// that rule for the result caches:
//
//   1. a `QueryCacheIdentityContext` seam (mirroring AiVisibilityContext)
//      implemented by `SecurityAwareConnectionManager` — per-identity
//      ("user:<identity>") for pass-through datasources and fail-closed for
//      unresolvable security-enabled ones, "" otherwise;
//   2. `QueryCacheKey.of` takes the identity fragment and mixes it into the
//      canonical JSON (omitted when empty, so non-pass-through keys stay
//      byte-identical to the role-keyed legacy keys — hit rates unchanged);
//   3. `ThinQueryService.cacheKeyFor` feeds the seam and returns null on any
//      resolution failure, so `executeCached` bypasses the cache and the
//      coalescer pass-throughs instead of degrading to a shared key
//      (`currentRolesForCacheKey` no longer swallows exceptions);
//   4. every ThinQueryService construction site that consults the shared
//      caches is wired with the seam.
//
// This command spec fails on the base commit (none of the structures below
// exist) and passes on the fix, which is the property the convention asks of
// a spec; it guards that the fix cannot be reverted or half-applied without
// turning red.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// A file the fix must ship reads as empty (all claims on it fail) rather than
// crashing the spec — a missing file is a failed criterion, not an error.
const read = (path) => {
  try {
    return readFileSync(join(root, path), 'utf8');
  } catch {
    return '';
  }
};

const seam = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/QueryCacheIdentityContext.java');
const cacheKey = read('saiku-core/saiku-service/src/main/java/org/saiku/service/cache/QueryCacheKey.java');
const service = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java');
const manager = read('saiku-core/saiku-web/src/main/java/org/saiku/web/core/SecurityAwareConnectionManager.java');
const reader = read('saiku-core/saiku-service/src/main/java/org/saiku/service/schedule/alert/AiMeasureValueReader.java');
const csvProducer = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/service/schedule/delivery/SavedQueryCsvArtifactProducer.java',
);
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const serviceTest = read(
  'saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ThinQueryServiceCacheKeyTest.java',
);
const keyTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/cache/QueryCacheKeyTest.java');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// ---- C1: pass-through datasources key the result caches per caller -------
claim('C1', 'the QueryCacheIdentityContext seam exists in saiku-service', /interface QueryCacheIdentityContext/.test(seam));
claim('C1', 'QueryCacheIdentityContext declares identityKey(String connectionName)', /identityKey\(String connectionName\)/.test(seam));
claim(
  'C1',
  'QueryCacheKey.of takes the caller identity and mixes it into the canonical view',
  /of\(\s*ThinQuery query, String cubeVersion, Collection<String> roles, String identity\)/.test(cacheKey) &&
    /public String identity;/.test(cacheKey) &&
    /canonicalIdentity/.test(cacheKey),
);
claim(
  'C1',
  'ThinQueryService holds the seam (setIdentityContext) and feeds it into the cache key',
  /setIdentityContext/.test(service) && /callerIdentityForCache/.test(service),
);
claim(
  'C1',
  "cacheKeyFor passes the caller identity into QueryCacheKey.of",
  /QueryCacheKey\.of\(tq, cubeVersion, currentRolesForCacheKey\(\), callerIdentityForCache\(tq\)\)/.test(service),
);
claim(
  'C1',
  'SecurityAwareConnectionManager implements the seam',
  /implements .*QueryCacheIdentityContext/.test(manager),
);
const identityKeyBlock = manager.slice(
  Math.max(manager.indexOf('public String identityKey'), 0),
  Math.max(manager.indexOf('private SaikuDatasource lookupDatasource'), 0),
);
claim(
  'C1',
  "identityKey returns a per-identity key on a passthrough datasource",
  identityKeyBlock.includes('SECURITY_TYPE_PASSTHROUGH_VALUE') &&
    identityKeyBlock.includes('"user:" + callerIdentity()'),
);

// ---- C2: non-pass-through datasources keep the role-keyed behaviour ------
const canonicalView = cacheKey.slice(cacheKey.indexOf('private static final class CanonicalView'));
claim(
  'C2',
  'the identity fragment is omitted from the canonical JSON when empty, so non-pass-through keys stay byte-identical',
  /@JsonInclude\(JsonInclude\.Include\.NON_EMPTY\)[\s\S]{0,120}public String identity;/.test(canonicalView),
);
claim(
  'C2',
  'identityKey collapses to "" for datasources that are not security-enabled or not passthrough',
  identityKeyBlock.includes('return "";'),
);
claim(
  'C2',
  'a unit test pins that the same roles still share a key on a non-pass-through datasource',
  /sameRolesShare|shareKey|sameRole/.test(serviceTest),
);

// ---- C3: unresolvable roles/identity bypass the caches (fail-closed) -----
const cacheKeyFor = service.slice(
  Math.max(service.indexOf('String cacheKeyFor'), 0),
  Math.max(service.indexOf('private String coalesceKey'), 0),
);
claim('C3', 'cacheKeyFor returns null (bypass) on any resolution failure', cacheKeyFor.includes('return null'));
const executeCached = service.slice(
  Math.max(service.indexOf('public CachedQueryResult executeCached'), 0),
  Math.max(service.indexOf('String cacheKeyFor'), 0),
);
claim(
  'C3',
  'executeCached treats a null key as cache-disabled and executes live',
  executeCached.includes('key == null') && executeCached.includes('runAndMaterialise'),
);
const rolesStart = service.indexOf('private java.util.Collection<String> currentRolesForCacheKey()');
const rolesBlock = rolesStart < 0
  ? ''
  : service.slice(rolesStart, Math.max(service.indexOf('/** Live-execute the query'), 0));
claim(
  'C3',
  'currentRolesForCacheKey no longer swallows exceptions into a shared role-less key',
  !rolesBlock.includes('catch') && rolesBlock.includes('getCurrentUserRoles'),
);
claim(
  'C3',
  'a unit test pins the fail-closed bypass (null key) on a throwing role source',
  /throwingUserService.*Bypass|Bypass.*throwing/i.test(serviceTest) || /assertNull/.test(serviceTest),
);

// ---- C4: regression tests ship and every cache consumer is wired ---------
claim(
  'C4',
  'ThinQueryServiceCacheKeyTest covers per-identity keys on pass-through',
  /passthrough|Passthrough/.test(serviceTest) && /identityContext|IdentityContext/.test(serviceTest),
);
claim('C4', 'QueryCacheKeyTest covers the identity fragment', /identity/i.test(keyTest));
claim(
  'C4',
  'the session + eval ThinQueryService beans are wired with the seam',
  (beans.match(/<property name="identityContext" ref="connectionManager"\/>/g) || []).length >= 2,
);
claim(
  'C4',
  'the off-request ThinQueryService factories wire the seam too',
  reader.includes('setIdentityContext') && csvProducer.includes('setIdentityContext'),
);

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('result caches are per user on pass-through datasources and fail closed on unresolvable roles\n');
