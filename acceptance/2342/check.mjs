// Acceptance spec for spiculedata/saiku#2342, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2342`.
//
// The behavioural half is RequestPathsTest / SaikuCsrfRequestMatcherTest / DemoGateFilterTest
// (JUnit, run by `mvn verify`). This file guards the structural invariant that makes them
// matter: no security filter may go back to deciding on the raw getRequestURI().

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const web = join(root, 'saiku-core', 'saiku-web', 'src');
const main = join(web, 'main/java/org/saiku/web');
const test = join(web, 'test/java/org/saiku/web');

const failures = [];
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : null);

// C1: the seven filters must not read the raw URI.
const filters = [
  'security/csrf/SaikuCsrfRequestMatcher.java',
  'servlet/DemoGateFilter.java',
  'security/embed/EmbedAuthFilter.java',
  'security/share/ShareTokenAuthFilter.java',
  'security/oauth/OAuthBearerAuthenticationFilter.java',
  'security/scim/ScimAuthFilter.java',
  'servlet/SpaFallbackFilter.java',
];
for (const f of filters) {
  const src = read(join(main, f));
  if (src === null) {
    failures.push(`C1: ${f} is missing`);
    continue;
  }
  if (/getRequestURI\(\)/.test(src)) failures.push(`C1: ${f} still reads the raw getRequestURI()`);
  if (!/RequestPaths\.pathWithinApplication\(/.test(src)) failures.push(`C1: ${f} does not use RequestPaths.pathWithinApplication`);
}

// C2: the helper exists and implements each normalisation step.
const helper = read(join(main, 'security/RequestPaths.java'));
if (helper === null) {
  failures.push('C2: security/RequestPaths.java is missing');
} else {
  for (const [name, re] of [
    ['percent-decoding', /URLDecoder|percentDecode|decode\(/],
    ['path-parameter stripping', /indexOf\(';'\)|';'/],
    ['dot-segment handling', /"\.\."/],
    ['fail-closed null return', /return null;/],
  ]) {
    if (!re.test(helper)) failures.push(`C2: RequestPaths has no ${name}`);
  }
}

// C3: the regression tests ship.
for (const [file, needle] of [
  ['security/RequestPathsTest.java', 'pathWithinApplication'],
  ['security/csrf/SaikuCsrfRequestMatcherTest.java', '%72est'],
  ['servlet/DemoGateFilterTest.java', '%72est'],
]) {
  const src = read(join(test, file));
  if (src === null || !src.includes(needle)) failures.push(`C3: ${file} lacks a ${needle} test`);
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('acceptance/2342: ok');
