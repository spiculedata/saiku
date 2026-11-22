import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const failures = [];

// C1
const e2e = 'saiku-ui/e2e/csp-launcher.live.spec.ts';
if (!existsSync(join(root, e2e))) failures.push(`C1: ${e2e} is missing`);
else {
  const src = read(e2e);
  if (!/securitypolicyviolation/.test(src) || !/disposition/.test(src) || !/enforce/.test(src))
    failures.push('C1: the live spec must listen for securitypolicyviolation and filter on disposition enforce');
  if (!/\/ui\//.test(src) || !/deep|dashboards|workbench/.test(src)) failures.push('C1: the live spec must cover /ui/ and a deep link');
}

// C2
const kit = read('saiku-ui/kit.config.js');
const form = kit.match(/'form-action':\s*\[([^\]]*)\]/)?.[1] ?? '';
if (!/'self'/.test(form)) failures.push("C2: kit.config.js csp must set 'form-action': ['self']");

// C3 / C4
const filter = read('saiku-core/saiku-web/src/main/java/org/saiku/web/servlet/SecurityHeadersFilter.java');
if (/static final String DEFAULT_CSP\b/.test(filter)) failures.push('C3: SecurityHeadersFilter still ships a default report-only policy (DEFAULT_CSP)');
if (!/NON_SPA_CSP/.test(filter) || !/NON_SPA_CSP\s*=[\s\S]*?script-src 'self'/.test(filter))
  failures.push("C4: SecurityHeadersFilter must enforce a script-src 'self' policy (NON_SPA_CSP) outside the SPA shell");

// C5
const changelog = read('CHANGELOG.md');
if (!/saiku#2430/.test(changelog) || !/http:/.test(changelog.split('saiku#2430')[1] ?? ''))
  failures.push('C5: CHANGELOG.md needs a saiku#2430 note covering http: tiles / CDN fonts');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('CSP follow-ups (#2430) are in place\n');
