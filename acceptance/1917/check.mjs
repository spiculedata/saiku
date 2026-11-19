import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const failures = [];
const kit = read('saiku-ui/kit.config.js');

// C1
if (!/csp:\s*\{[\s\S]*?mode:\s*'hash'/.test(kit)) failures.push("C1: kit.config.js must enable csp mode 'hash'");
const script = kit.match(/'script-src':\s*\[([^\]]*)\]/)?.[1] ?? '';
if (!/'self'/.test(script)) failures.push("C1: script-src must include 'self'");
if (/unsafe-inline|unsafe-eval|https?:|\*/.test(script)) failures.push('C1: script-src must not allow unsafe-inline, unsafe-eval or remote hosts');

// C2
const need = {
  'worker-src': 'blob:',
  'style-src': 'unsafe-inline',
  'img-src': 'data:',
  'connect-src': 'self',
  'object-src': 'none',
  'base-uri': 'self'
};
for (const [dir, tok] of Object.entries(need)) {
  const m = kit.match(new RegExp(`'${dir}':\\s*\\[([^\\]]*)\\]`));
  if (!m || !m[1].includes(tok)) failures.push(`C2: ${dir} must include ${tok}`);
}

// C3
if (/<[a-z][^>]*\son[a-z]+\s*=/i.test(read('saiku-ui/src/app.html'))) failures.push('C3: app.html has an inline event handler');

// C4
if (!/saiku\.security\.frameAncestors/.test(read('saiku-core/saiku-web/src/main/java/org/saiku/web/servlet/SecurityHeadersFilter.java')))
  failures.push('C4: frame-ancestors is no longer configurable');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('SPA CSP is enforced via SvelteKit hash mode; frame-ancestors still configurable\n');
