// The acceptance spec for spiculedata/saiku#2532, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2532`.
//
// #2532 is a service-internal security fix: the breach (a datasource the OLAP path denies over
// MDX is readable over the SQL workbench's raw JDBC connection with the stored credentials) has
// no observable REST difference on the FoodMart demo datasources, which carry no datasource
// security. The contract is therefore asserted in the source of SqlWorkbenchService (the same
// pattern acceptance/2394 established); the behavioural half is SqlWorkbenchServiceTest (JUnit,
// run by `mvn verify`), which this spec requires to ship. Exit non-zero on the first broken
// clause, with the reason on stderr.

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

// C1 — the workbench re-derives the connection path's datasource security decision and refuses
// one2one and passthrough BEFORE a raw JDBC connection is opened.
claim('C1', () => {
  // The decision method exists and consults the shared policy — the single definition of how a
  // caller's authorities resolve on a datasource (saiku#779).
  assert.match(
    service,
    /isWorkbenchAccessible/,
    'SqlWorkbenchService carries its own reproduction of the connection-path security decision',
  );
  assert.match(
    service,
    /isWorkbenchAccessible[\s\S]{0,2000}?MondrianRolePolicy\.modeOf\(/,
    'the decision reads the datasource security mode through MondrianRolePolicy.modeOf, the same policy the connection path applies',
  );
  // one2one and passthrough are refused: they are not among the allowed modes.
  assert.match(
    service,
    /isWorkbenchAccessible[\s\S]{0,3000}?Mode\.LOOKUP/,
    'the decision allows a security-enabled datasource only through a mode whose decision the workbench can reproduce (lookup) — one2one and passthrough are not served',
  );
  assert.doesNotMatch(
    service,
    /isWorkbenchAccessible[\s\S]{0,3000}?Mode\.ONE2ONE[\s\S]{0,200}return true/,
    'one2one is never served by the workbench — its decision needs the schema role list, which only the OLAP connection can read',
  );
  assert.doesNotMatch(
    service,
    /isWorkbenchAccessible[\s\S]{0,3000}?Mode\.PASSTHROUGH[\s\S]{0,200}return true/,
    'passthrough is never served by the workbench — the caller would connect as the service account',
  );
  // The decision gates BOTH surfaces: the picker and the query path.
  assert.match(
    service,
    /listDatasources[\s\S]{0,800}?isWorkbenchAccessible\(/,
    'the datasource picker applies the same security decision — a datasource the workbench cannot serve is not offered',
  );
  assert.match(
    service,
    /isEntitledTo[\s\S]{0,900}?isWorkbenchAccessible\(/,
    'the query path applies the same security decision — visibility over OLAP is not sufficient to open a raw JDBC connection',
  );
});

// C2 — an unrecognised security.type is refused, fail closed (same rule as saiku#1972).
claim('C2', () => {
  assert.match(
    service,
    /isWorkbenchAccessible[\s\S]{0,3000}?Mode\.LOOKUP\s*\)\s*\{\s*return false/,
    'every security-enabled mode other than lookup (including an unrecognised type) is refused, fail closed',
  );
  assert.match(
    tests,
    /execute_refusesSecurityEnabledDatasourceWithUnrecognisedSecurityType/,
    'the unrecognised-security.type refusal regression exists',
  );
});

// C3 — lookup is served only when the caller's own authorities map to a Mondrian role, re-checked
// by the workbench through the shared policy.
claim('C3', () => {
  assert.match(
    service,
    /isWorkbenchAccessible[\s\S]{0,4000}?MondrianRolePolicy\.resolveMondrianRoles\(/,
    'the lookup decision resolves the caller\u2019s authorities through MondrianRolePolicy.resolveMondrianRoles — the same resolution the connection path applies',
  );
  assert.match(
    service,
    /isWorkbenchAccessible[\s\S]{0,4000}?MondrianRolePolicy\.mappingOf\(/,
    'the lookup decision consults the datasource\u2019s parsed security.mapping',
  );
  assert.match(
    tests,
    /execute_servesLookupOnlyWhenTheCallerRolesMapToAMondrianRole/,
    'the lookup-with-mapping / lookup-without-mapping regression exists',
  );
});

// C4 — the admin exemption mirrors the connection path and fails closed; the behavioural
// regressions ship.
claim('C4', () => {
  assert.match(
    tests,
    /execute_refusesOne2OneAndPassthroughSecurityEnabledDatasources/,
    'the one2one/passthrough refusal regression exists',
  );
  const refusal = tests.match(/execute_refusesOne2OneAndPassthroughSecurityEnabledDatasources[\s\S]{0,3000}/);
  assert.ok(refusal, 'the one2one/passthrough refusal test body was found');
  assert.match(
    refusal[0],
    /openedFor\.isEmpty\(\)/,
    'the refused datasource was never opened — the decision fires before the raw JDBC connection',
  );
  assert.match(
    refusal[0],
    /SqlWorkbenchException\.Code\.UNKNOWN_DATASOURCE/,
    'a security-gated datasource answers the same as an unknown one — never a distinguishable "exists but denied"',
  );
  assert.match(
    tests,
    /execute_adminExemptionMirrorsTheConnectionPath_andFailsClosedWithoutAdminRoles/,
    'the admin exemption / fail-closed regression exists',
  );
  assert.match(
    tests,
    /listDatasources_omitsSecurityEnabledDatasourcesTheWorkbenchCannotServe/,
    'the picker-filter regression exists',
  );
  assert.match(
    service,
    /isAdmin[\s\S]{0,1200}?getAdminRoles\(\)/,
    'the admin determination reads the configured admin roles from UserService, mirroring the visibility filter',
  );
  assert.match(
    service,
    /isAdmin[\s\S]{0,1400}?\{\s*return false/,
    'unavailable admin roles fail closed — the caller is treated as non-admin, never defaulted to full',
  );
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write(
  'acceptance/2532: the SQL workbench re-derives the OLAP connection path\u2019s datasource security decision before serving a query\n',
);
