import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = readFileSync(
  join(root, 'saiku-core/saiku-service/src/main/java/org/saiku/olap/discover/OlapMetaExplorer.java'),
  'utf8'
);

const start = src.indexOf('hasAnnotation(l, MondrianDictionary.SQLMemberLookup)');
const branch = src.slice(start, src.indexOf('if (search || searchLimit > 0)', start));
const failures = [];
if (!/restrictToRoleAndLimit\(/.test(branch)) failures.push('C1/C2: the SQLMemberLookup branch must filter by role access and clamp the limit');

if (failures.length) {
  failures.forEach((f) => process.stderr.write(`${f}\n`));
  process.exit(1);
}
process.stdout.write('SQLMemberLookup hits are role-filtered and limited\n');
