import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = readFileSync(
  join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/OlapDiscoverResource.java'),
  'utf8'
);

function method(name) {
  const start = src.indexOf(`public List<SaikuConnection> ${name}(`);
  if (start < 0) return '';
  return src.slice(start, src.indexOf('\n    }\n', start));
}

const failures = [];
const all = method('refreshConnections');
const one = method('refreshConnection');
const guardedBefore = (body, call) => {
  const g = body.indexOf('isAdmin');
  const c = body.indexOf(call);
  return g >= 0 && c >= 0 && g < c;
};
if (!guardedBefore(all, 'refreshAllConnections')) failures.push('C1: refreshConnections must check admin before refreshAllConnections');
if (!guardedBefore(one, 'olapDiscoverService.refreshConnection(')) failures.push('C2: refreshConnection must check admin before refreshing');
if (!/FORBIDDEN|403/.test(one)) failures.push('C2: refreshConnection must refuse non-admins with 403');

if (failures.length) {
  failures.forEach((f) => process.stderr.write(`${f}\n`));
  process.exit(1);
}
process.stdout.write('discover refresh is admin-gated\n');
