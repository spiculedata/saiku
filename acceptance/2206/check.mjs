import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const lock = JSON.parse(readFileSync(join(root, 'saiku-ui/package-lock.json'), 'utf8'));
const minimum = {
  'brace-expansion': [5, 0, 12],
  'source-map-js': [1, 2, 2],
  'postcss-selector-parser': [7, 1, 6],
};

for (const [name, floor] of Object.entries(minimum)) {
  const version = lock.packages[`node_modules/${name}`]?.version;
  const parts = version?.split('.').map(Number);
  const meetsFloor = parts && (parts[0] > floor[0]
    || (parts[0] === floor[0] && parts[1] > floor[1])
    || (parts[0] === floor[0] && parts[1] === floor[1] && parts[2] >= floor[2]));
  if (!meetsFloor) {
    process.stderr.write(`C1: ${name} is ${version ?? 'missing'}; expected at least ${floor.join('.')}\n`);
    process.exitCode = 1;
  }
}

if (!process.exitCode) process.stdout.write('UI parser dependencies resolve to patched versions\n');
