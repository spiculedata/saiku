import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = readFileSync(
  join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiQueryResource.java'),
  'utf8'
);

const start = src.indexOf('@Path("/schema/{cubeId:.+}/pii-suggestions")');
if (start < 0) {
  process.stderr.write('C1: pii-suggestions route not found\n');
  process.exit(1);
}
const head = src.slice(start, start + 200);
const body = src.slice(start, src.indexOf('\n    }\n', start));
const failures = [];
if (!/@RolesAllowed\("ROLE_ADMIN"\)/.test(head)) failures.push('C1: getPiiSuggestions must carry @RolesAllowed("ROLE_ADMIN")');
const guard = body.indexOf('currentUserIsAdmin()');
const warm = body.indexOf('cubeMetadataService.getSchema');
if (guard < 0 || !/FORBIDDEN/.test(body)) failures.push('C1: getPiiSuggestions must refuse non-admins with 403');
else if (warm >= 0 && guard > warm) failures.push('C1: the admin check must run before the schema cache is warmed');

if (failures.length) {
  failures.forEach((f) => process.stderr.write(`${f}\n`));
  process.exit(1);
}
process.stdout.write('pii-suggestions is admin-gated before any cache warm-up\n');
