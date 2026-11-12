// Acceptance spec for spiculedata/saiku#2336 (the parts that live in git). Exits non-zero with the
// broken clause on stderr. The repository-level Actions settings are documented in the PR.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const gh = join(root, '.github');
const read = (p) => readFileSync(p, 'utf8');
const yml = (dir) => readdirSync(join(gh, dir), { recursive: true }).filter((f) => /\.ya?ml$/.test(f)).map((f) => join(gh, dir, f));

// C1
const action = read(join(gh, 'actions', 'preview-host-access', 'action.yml'));
const version = /^\s+version:\s*"?([^\s"#]+)/m.exec(action)?.[1];
assert.match(version ?? '', /^\d+\.\d+\.\d+$/, `C1: tailscale version must be x.y.z, got ${version}`);
assert.match(/^\s+sha256sum:\s*"?([^\s"#]+)/m.exec(action)?.[1] ?? '', /^[0-9a-f]{64}$/, 'C1: sha256sum must be 64 hex');

// C2
const bad = [];
for (const file of [...yml('workflows'), ...yml('actions')]) {
  read(file).split('\n').forEach((line, i) => {
    const m = /^\s*(?:-\s*)?uses:\s*(\S+)/.exec(line);
    if (!m || m[1].startsWith('./') || m[1].startsWith('docker://')) return;
    const ref = m[1].split('@')[1] ?? '';
    if (!/^[0-9a-f]{40}$/.test(ref)) bad.push(`${file.slice(root.length + 1)}:${i + 1} ${m[1]}`);
  });
}
assert.deepEqual(bad, [], 'C2: every uses: ref must be a 40-hex SHA');

// C3
const missing = yml('workflows').filter((f) => !/^permissions:/m.test(read(f))).map((f) => f.slice(root.length + 1));
assert.deepEqual(missing, [], 'C3: workflows without a top-level permissions: block');
console.log('actions pins and default permissions hold');
