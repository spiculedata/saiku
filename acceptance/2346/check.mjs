import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const doc = readFileSync(join(root, 'docs/embed/quickstart.md'), 'utf8');
const flat = doc.replace(/\s+/g, ' ');
const failures = [];
const need = (id, re, msg) => { if (!re.test(flat)) failures.push(`${id}: ${msg}`); };

need('C1', /does not ship (any )?CORS/i, 'say Saiku does not ship CORS configuration');
need('C1', /preflight/i, 'mention the CORS preflight');
need('C2', /Access-Control-Allow-Origin/, 'show Access-Control-Allow-Origin with an explicit origin');
need('C2', /\/rest\/saiku\/api\/embed\/query\//, 'scope CORS to the embed guest prefixes');
need('C2', /never[^.]*Access-Control-Allow-Credentials/i, 'say never to enable Access-Control-Allow-Credentials');
need('C3', /(reflect|\$http_origin)[^.]*Origin|Origin[^.]*reflect/i, 'warn about reflecting the Origin header');
need('C3', /\/rest\/\*\*/, 'name /rest/** as the path not to open');
if (/no cookie\/CORS-credential complications/i.test(flat)) failures.push('C4: old "no CORS complications" claim still present');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('quickstart documents the safe CORS pattern and the warning\n');
