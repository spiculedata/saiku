import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = readFileSync(
  join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/objects/DataSourceMapper.java'),
  'utf8'
);

function body(signature) {
  const start = src.indexOf(signature);
  return start < 0 ? '' : src.slice(start, src.indexOf('}', start));
}

const failures = [];
if (!/redactPassword\(/.test(body('public String getAdvanced()'))) failures.push('C1: getAdvanced() must redact the password line');
if (!/redactPassword\(/.test(body('public String getCsv()'))) failures.push('C1: getCsv() must redact the password line');

if (failures.length) {
  failures.forEach((f) => process.stderr.write(`${f}\n`));
  process.exit(1);
}
process.stdout.write('advanced/csv blobs are redacted on serialisation\n');
