// Acceptance spec for spiculedata/saiku#2343, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2343`.
//
// The behavioural half is SpringUrlRulesGuestExclusionTest (JUnit, run by `mvn verify`): it
// evaluates the real intercept-url expressions against guest and user Authentications. This
// file guards the structural invariant so a new bare isFullyAuthenticated() rule, or a guest
// filter that stops marking its token, turns red.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const web = join(root, 'saiku-core', 'saiku-web', 'src');
const main = join(web, 'main/java/org/saiku/web');
const xmlPath = join(root, 'saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml');
const failures = [];
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : null);

// C1: every filter that mints a guest token marks it.
for (const f of [
  'security/embed/EmbedAuthFilter.java',
  'security/share/ShareTokenAuthFilter.java',
  'security/scim/ScimAuthFilter.java',
]) {
  const src = read(join(main, f));
  if (src === null || !/TOKEN_PRINCIPAL_ROLE/.test(src)) failures.push(`C1: ${f} does not add TOKEN_PRINCIPAL_ROLE`);
}
const marker = read(join(main, 'security/TokenPrincipals.java'));
if (marker === null || !marker.includes('ROLE_TOKEN_PRINCIPAL')) failures.push('C1: security/TokenPrincipals.java is missing');

// C2: strip XML comments, then every access="..." that relies on isFullyAuthenticated() must exclude the marker.
const xml = (read(xmlPath) ?? '').replace(/<!--[\s\S]*?-->/g, '');
for (const m of xml.matchAll(/<security:intercept-url[^>]*access="([^"]*)"/g)) {
  const expr = m[1];
  if (!expr.includes('isFullyAuthenticated()')) continue;
  const bare = expr.replace(/isFullyAuthenticated\(\)\s+and\s+!hasRole\('TOKEN_PRINCIPAL'\)/g, '');
  if (bare.includes('isFullyAuthenticated()')) failures.push(`C2: rule admits any authenticated principal: access="${expr}"`);
}

// C3: the regression test ships.
const t = read(join(web, 'test/java/org/saiku/web/security/SpringUrlRulesGuestExclusionTest.java'));
if (t === null || !t.includes('embedGuest_isDeniedOnFallbackRules')) failures.push('C3: SpringUrlRulesGuestExclusionTest is missing');

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('acceptance/2343: ok');
