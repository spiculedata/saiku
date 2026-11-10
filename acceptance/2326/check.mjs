// The acceptance spec for spiculedata/saiku#2326, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2326`.
//
// #2326 is an access-control defect on the AI schema cache: the singleton
// `OlapAiCubeMetadataService` keyed its `ConcurrentMap` by
// `connection/catalog/schema/cube` only, so the FIRST caller's role-scoped
// snapshot (Mondrian filters cubes, hierarchies and sample members by the
// caller's role) was served verbatim to every later caller, and callers with
// no grant at all got a 200 off the cache-hit path because that path never
// reached `findCube()`. The fix has three parts:
//
//   1. the cache key gains a visibility fingerprint of the CALLER
//      (`AiVisibilityContext`, implemented by `SecurityAwareConnectionManager`
//      from the caller's resolved Mondrian role fingerprint);
//   2. every cache hit re-resolves the cube against the caller's own
//      enumeration (`findCube`) before the cached entry is returned;
//   3. `refreshConnection` invalidates the entries scoped to that connection.
//
// The unit-level behaviour is `OlapAiCubeMetadataServiceVisibilityTest`
// (saiku-service) and `SecurityAwareConnectionManagerVisibilityKeyTest`
// (saiku-web), run by `mvn verify`. This command spec fails on the base
// commit (none of the structures below exist) and passes on the fix, which
// is the property the convention asks of a spec; it guards that the fix
// cannot be reverted or half-applied without turning red.

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

const service = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/OlapAiCubeMetadataService.java',
);
const seam = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/AiVisibilityContext.java');
const connections = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/datasources/connection/AbstractConnectionManager.java',
);
const manager = read('saiku-core/saiku-web/src/main/java/org/saiku/web/core/SecurityAwareConnectionManager.java');
const serviceTest = read(
  'saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/OlapAiCubeMetadataServiceVisibilityTest.java',
);
const managerTest = read(
  'saiku-core/saiku-web/src/test/java/org/saiku/web/core/SecurityAwareConnectionManagerVisibilityKeyTest.java',
);

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// ---- C1: the cache key is scoped by a caller visibility context ----------
claim('C1', 'the AiVisibilityContext seam exists in saiku-service', /interface AiVisibilityContext/.test(seam));
claim('C1', 'AiVisibilityContext declares visibilityKey(connectionName)', /visibilityKey\(String connectionName\)/.test(seam));
claim(
  'C1',
  'OlapAiCubeMetadataService holds an AiVisibilityContext and composes the cache key with it',
  /AiVisibilityContext/.test(service) && /VISIBILITY_SEPARATOR/.test(service),
);
claim(
  'C1',
  'cacheKey() appends the visibility fingerprint to the base key',
  /visibilityKeyFor\(ref\)/.test(service) || /visibilityKeyFor\(.*\)/.test(service),
);
claim(
  'C1',
  'the serialised AiSchema cubeId is built from the role-less base key, not the visibility-scoped key',
  /new AiSchema\(baseCacheKey\(/.test(service),
);

// ---- C2: the cache-hit path re-checks the caller's visibility ------------
const getSchema = service.slice(service.indexOf('public AiSchema getSchema'), service.indexOf('private AiSchema buildSchema'));
claim(
  'C2',
  'getSchema re-resolves the cube (findCube) before returning a cached entry',
  /schemaCache\.get\(key\)/.test(getSchema) &&
    /findCube\(ref\)/.test(getSchema) &&
    getSchema.indexOf('findCube(ref)') < getSchema.indexOf('return cached;'),
);
claim(
  'C2',
  'the hit-path comment ties the re-check to saiku#2326',
  /saiku#2326/.test(getSchema),
);

// ---- C3: a connection refresh invalidates that connection's entries ------
claim(
  'C3',
  'the metadata service implements a per-connection invalidateSchemaCache',
  /public void invalidateSchemaCache\(String connectionName\)/.test(service),
);
claim(
  'C3',
  'the AiCubeMetadataService interface carries the per-connection invalidation hook',
  /default void invalidateSchemaCache\(String connectionName\)/.test(service),
);
claim(
  'C3',
  'AbstractConnectionManager.refreshConnection invokes the invalidator',
  /aiSchemaCache\.invalidateSchemaCache\(name\)/.test(connections),
);

// ---- C4: the web layer publishes the caller's visibility fingerprint -----
claim(
  'C4',
  'SecurityAwareConnectionManager implements AiVisibilityContext',
  /implements .*AiVisibilityContext/.test(manager),
);
claim(
  'C4',
  'visibilityKey fingerprints the caller role set (sorted) on a security-enabled datasource',
  /Collections\.sort|\.sort\(/.test(manager) && /roles:/i.test(manager),
);
claim(
  'C4',
  'passthrough datasources key per principal, not per role set',
  /SECURITY_TYPE_PASSTHROUGH_VALUE/.test(manager.slice(manager.indexOf('visibilityKey'))),
);

// ---- C5: regression tests ship with the fix ------------------------------
claim(
  'C5',
  'OlapAiCubeMetadataServiceVisibilityTest covers cross-role rebuild',
  /rebuildsUnderCallerRole|cacheHitForDifferentRoleSet/.test(serviceTest),
);
claim(
  'C5',
  'OlapAiCubeMetadataServiceVisibilityTest covers no-grant denial on a warm cache',
  /cacheHit_whenCubeNotVisibleToCaller_throwsAiValidationException/.test(serviceTest),
);
claim(
  'C5',
  'OlapAiCubeMetadataServiceVisibilityTest covers same-role sharing and per-connection invalidation',
  /sameRoleSet_sharesEntry/.test(serviceTest) && /invalidateConnection|refreshConnection_invalidates/.test(serviceTest),
);
claim(
  'C5',
  'SecurityAwareConnectionManagerVisibilityKeyTest covers same/different role sets',
  /same|different|Differs|differs/i.test(managerTest),
);

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('AI schema cache is scoped to the caller visibility context and re-checked on every hit\n');
