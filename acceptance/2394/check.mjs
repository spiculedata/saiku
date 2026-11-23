// The acceptance spec for spiculedata/saiku#2394, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2394`.
//
// #2394 is a service-internal authorization fix: the collision (a visible `sales_payroll`
// authorizing the role-restricted `payroll`) has no observable REST difference on the FoodMart
// demo datasources, so the contract is asserted in the source of SqlWorkbenchService; the
// behavioural half is SqlWorkbenchServiceTest (JUnit, run by `mvn verify`), which this spec
// requires to ship. Exit non-zero on the first broken clause, with the reason on stderr.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const servicePath = join(
  here,
  '..',
  '..',
  'saiku-core',
  'saiku-service',
  'src',
  'main',
  'java',
  'org',
  'saiku',
  'service',
  'sqlworkbench',
  'SqlWorkbenchService.java',
);
const testPath = join(
  here,
  '..',
  '..',
  'saiku-core',
  'saiku-service',
  'src',
  'test',
  'java',
  'org',
  'saiku',
  'service',
  'sqlworkbench',
  'SqlWorkbenchServiceTest.java',
);

const service = readFileSync(servicePath, 'utf8');
const tests = readFileSync(testPath, 'utf8');

const failures = [];
const claim = (id, fn) => {
  try {
    fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 — authorization resolves once and checks the resolved record; prefix tolerance is gone.
claim('C1', () => {
  // The old check matched every visible datasource name with sameConnection — the bypass.
  assert.doesNotMatch(
    service,
    /DatasourceNameDecoration\.sameConnection\s*\(/,
    'SqlWorkbenchService no longer calls DatasourceNameDecoration.sameConnection to authorize a requested datasource',
  );
  // The requested id is resolved once, the same resolution the connection provider applies.
  assert.match(
    service,
    /isEntitledTo\([^)]*\)\s*\{[\s\S]*?datasourceService\.getDatasourceByIdOrName\(\s*dataSourceId\s*\)/,
    'isEntitledTo resolves the requested id through DatasourceService.getDatasourceByIdOrName before authorizing',
  );
  // A null/unresolvable datasource is refused, not authorized.
  assert.match(
    service,
    /getDatasourceByIdOrName\(\s*dataSourceId\s*\)\s*;?\s*[\s\S]{0,200}if\s*\(\s*requested\s*==\s*null\s*\)\s*\{\s*return false/,
    'an unresolvable requested datasource is refused',
  );
  // Distinct records are never matched by prefix tolerance: equal immutable id property or exact name.
  assert.match(
    service,
    /isSameDatasource/,
    'the resolved record is compared to the visible map through a strict same-record check',
  );
  assert.match(
    service,
    /isSameDatasource[\s\S]{0,900}?getProperties\(\)\s*==\s*null\s*\?\s*null\s*:\s*[\s\S]{0,200}getProperty\(\s*"id"\s*\)/,
    'the same-record check compares the immutable id property',
  );
  assert.match(
    service,
    /isSameDatasource[\s\S]{0,1400}?getName\(\)\s*!=\s*null\s*&&\s*[\s\S]{0,100}getName\(\)\.equals\(\s*b\.getName\(\)\s*\)/,
    'the same-record check falls back to the exact connection name, never a tolerated prefix',
  );
});

// C2 — aliasing moved to resolution: UUID and decorated-name requests still execute.
claim('C2', () => {
  assert.match(
    service,
    /getDatasourceByIdOrName/,
    'resolution keeps the by-id-or-name lookup that supports UUID and aliased spellings',
  );
  assert.match(
    service,
    /saiku#1869/,
    'the workspace-decoration alias is applied at resolution, per saiku#1869, not at authorization',
  );
});

// C3 — the collision regression and the alias/UUID no-over-correction tests ship.
claim('C3', () => {
  assert.match(
    tests,
    /execute_rejectsRestrictedSuffixWhenAPrefixedNameIsVisible/,
    'the collision regression test (visible sales_payroll, restricted payroll) exists',
  );
  const collision = tests.match(/execute_rejectsRestrictedSuffixWhenAPrefixedNameIsVisible[\s\S]{0,2500}/);
  assert.ok(collision, 'the collision test body was found');
  assert.match(
    collision[0],
    /SqlWorkbenchException\.Code\.UNKNOWN_DATASOURCE/,
    'the colliding request is rejected as an unknown datasource',
  );
  assert.match(
    collision[0],
    /openedFor\.isEmpty\(\)/,
    'the restricted datasource was never opened — rejection happens before the JDBC connection',
  );
  assert.match(
    tests,
    /execute_acceptsDatasourceResolvedByUuidWhenVisible/,
    'a UUID request for an authorized datasource still executes (no over-correction)',
  );
  assert.match(
    tests,
    /execute_acceptsDecoratedNameAliasForAuthorizedDatasource/,
    'exact and decorated-name requests for an authorized datasource still execute',
  );
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2394: the SQL workbench authorizes the resolved datasource, not a suffix-matching name\n');
