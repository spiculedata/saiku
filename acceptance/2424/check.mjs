// Acceptance spec for spiculedata/saiku#2424: pre-auth filter follow-ups to #2342/#2343.
// A non-normalisable path cannot be sent through Jetty (it rejects those spellings first), so
// this pins the source contract; the behaviour is asserted by the unit tests named below.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const web = 'saiku-core/saiku-web/src';
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
const failures = [];
const claim = (id, what, holds) => {
  if (!holds) failures.push(`${id}: ${what}`);
};

const share = read(`${web}/main/java/org/saiku/web/security/share/ShareTokenAuthFilter.java`);
claim('C1', 'ShareTokenAuthFilter guards path == null before startsWith', /path\s*==\s*null\s*\|\|\s*!path\.startsWith\(VIEW_PREFIX\)/.test(share));
claim('C1', 'ShareTokenAuthFilterTest has a non-normalisable case', /nonNormalisable/i.test(read(`${web}/test/java/org/saiku/web/security/share/ShareTokenAuthFilterTest.java`)));

const scim = read(`${web}/main/java/org/saiku/web/security/scim/ScimAuthFilter.java`);
claim('C2', 'ScimAuthFilter does not wave a null path through', /path\s*!=\s*null\s*&&\s*!isScimPath\(path\)/.test(scim));
claim('C2', 'ScimAuthFilterTest has a non-normalisable case', /nonNormalisable/i.test(read(`${web}/test/java/org/saiku/web/security/scim/ScimAuthFilterTest.java`)));

const gate = read(`${web}/main/java/org/saiku/web/servlet/DemoGateFilter.java`);
claim('C3', 'DemoGateFilter no longer returns not-gated for non-/rest/saiku/ paths under /rest/', !/if \(!path\.startsWith\(SAIKU_PREFIX\)\) return false;/.test(gate));
claim('C3', 'DemoGateFilterTest asserts /rest/other/thing is gated', /assertTrue\(DemoGateFilter\.requiresVerification\("\/rest\/other\/thing"\)\)/.test(read(`${web}/test/java/org/saiku/web/servlet/DemoGateFilterTest.java`)));

const paths = read(`${web}/main/java/org/saiku/web/security/RequestPaths.java`);
claim('C4', 'RequestPaths no longer strips the context on the raw URI', !/uri\.startsWith\(ctx\)/.test(paths));
claim('C4', 'RequestPathsTest has a non-root context case', /nonRootContext/i.test(read(`${web}/test/java/org/saiku/web/security/RequestPathsTest.java`)));

claim('C5', 'TokenPrincipals says the marker is mandatory', /mandatory/i.test(read(`${web}/main/java/org/saiku/web/security/TokenPrincipals.java`)));

if (failures.length) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('acceptance/2424: all claims hold');
