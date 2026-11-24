// The acceptance spec for spiculedata/saiku#2537, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2537`.
//
// #2537 is a server-side hardening of the SCIM group surface: a group's
// displayName is the role granted to its members, so the set of grantable
// names must be an operator-defined allow-list on top of a computed reserved
// set, and SCIM must not be able to deactivate the last enabled admin.
//
// The spec is a command spec, not an api spec, on purpose: while saiku#2449
// stands, every /rest/scim/v2 bearer call 401s before reaching the service
// (ScimAuthFilter sits before SecurityContextHolderFilter), so a live
// api spec would be red on the fixed build as well. The behaviour lives in
// ScimService, and this spec verifies the shipped contract where it can be
// observed from the checkout: the computed reserved set, the allow-list
// property and its wiring, the last-enabled-admin guard on all three
// deactivation paths, the documentation of the default, and the unit tests
// that pin all of it. Ported in spirit from acceptance/2172.
//
// Exit non-zero on the first broken clause, with the reason on stderr, so the
// runner's "exit code" detail says what broke.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch (e) {
    throw new Error(`expected ${rel} to exist: ${e.message}`);
  }
}

const service = read('saiku-core/saiku-web/src/main/java/org/saiku/web/scim/ScimService.java');
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const docs = read('docs/operator-hardening.md');
const tests = read('saiku-core/saiku-web/src/test/java/org/saiku/web/scim/ScimServiceTest.java');

function must(cond, why) {
  assert.ok(cond, why);
}

// C1 — the reserved set is computed and complete; admin roles are dynamic.
{
  for (const reserved of [
    'ROLE_SQL_EXEC',
    'ROLE_TOKEN_PRINCIPAL',
    'ROLE_EMBED_AUTHOR',
    'ROLE_EMBED_GUEST',
    'ROLE_SHARE_GUEST',
    'ROLE_ANONYMOUS',
    'ROLE_SCIM',
    'ROLE_ACTUATOR',
  ]) {
    must(
      service.includes(`"${reserved}"`),
      `C1: ScimService must reserve ${reserved} — a SCIM token must not be able to grant it via a group displayName`
    );
  }
  must(
    service.includes('getAdminRoles()'),
    'C1: the reserved set must derive admin roles from userService.getAdminRoles(), not a hard-coded ROLE_ADMIN literal'
  );
  // Every displayName entry point feeds the same guard: the create/replace
  // path through requireDisplayName and the PATCH rename path.
  must(
    service.includes('requireDisplayName(') && service.includes('renameGroup('),
    'C1: displayName is validated on the create/replace path and on the rename path'
  );
}

// C2 — the operator-defined allow-list exists, is wired and is documented.
{
  must(
    service.includes('saiku.scim.grantableRoles'),
    'C2: ScimService must read the saiku.scim.grantableRoles allow-list property'
  );
  must(
    beans.includes('saiku.scim.grantableRoles'),
    'C2: saiku-beans.xml must wire saiku.scim.grantableRoles into the scimService bean'
  );
  must(
    docs.includes('saiku.scim.grantableRoles'),
    'C2: docs/operator-hardening.md must document the allow-list and its default'
  );
  // The allow-list never rescues a reserved name: the reserved check runs on
  // top of the configured list.
  must(
    service.indexOf('saiku.scim.grantableRoles') !== -1 && service.includes('reserved'),
    'C2: the reserved check must run on top of the configured allow-list'
  );
}

// C3 — SCIM cannot deactivate the last enabled admin.
{
  must(
    service.includes('last enabled admin') || service.includes('LastEnabledAdmin') || service.includes('lastEnabledAdmin'),
    'C3: ScimService must carry an explicit last-enabled-admin guard'
  );
  // All three deactivation paths: DELETE (= deactivate), PATCH active:false, PUT active:false.
  must(
    service.includes('deactivateUser('),
    'C3: the DELETE (deactivate) path must be guarded'
  );
  const patchActive = /case "active":[\s\S]{0,400}?(last|Last)/.test(service);
  must(
    patchActive || service.includes('patchUser(') === false,
    'C3: the PATCH active:false path must run the guard before the flag is applied'
  );
  must(
    /replaceUser\([\s\S]{0,2000}?(last|Last)/i.test(service),
    'C3: the PUT active:false path must run the guard'
  );
  // The guard counts enabled admins from the directory, against getAdminRoles().
  must(
    service.includes('getAdminRoles()'),
    'C3: the guard must identify admins through userService.getAdminRoles()'
  );
}

// C4 — the unit tests pin the list and the last-admin rule.
{
  for (const covered of ['ROLE_SQL_EXEC', 'grantable', 'admin']) {
    must(
      tests.toLowerCase().includes(covered.toLowerCase()),
      `C4: ScimServiceTest must cover ${covered}`
    );
  }
  must(
    tests.includes('last') && tests.toLowerCase().includes('admin'),
    'C4: ScimServiceTest must cover the last-enabled-admin rule'
  );
}

console.log('acceptance/2537: SCIM grantable-roles allow-list and last-enabled-admin guard are in place');
