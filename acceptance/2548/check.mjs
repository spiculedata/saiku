import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const { classify, filesAreSafe } = await import(
  new URL(`file://${join(root, '.github/scripts/dependabot-automerge.mjs')}`)
);

// C3 — a real npm patch bump is still eligible, and the pair is proven by the diff
// (package.json range plus the same bump inside package-lock.json).
const npmPatch = [
  { filename: 'saiku-ui/package.json', patch: '-    "foo": "^1.2.3",\n+    "foo": "^1.2.4",' },
  { filename: 'saiku-ui/package-lock.json', patch: '-        "version": "1.2.3"\n+        "version": "1.2.4"' },
];
assert.equal(
  classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['dependencies', 'npm'], files: npmPatch }).eligible,
  true,
  'a genuine single-package npm patch bump stays eligible'
);

// C1 — GitHub Actions updates and any change under .github/workflows/ are never auto-merged
// (#2548): actions execute inside jobs that hold packages:/contents: write and repository
// tokens, and only `ci` stands between such a PR and development.
const usesFile = [{ filename: '.github/workflows/ci.yml', patch: '-      - uses: a/b@4.1.0\n+      - uses: a/b@4.1.1' }];
assert.equal(
  filesAreSafe(usesFile),
  false,
  'a change under .github/workflows/ is never safe for auto-merge'
);
assert.equal(
  classify({ title: 'Bump a/b from 4.1.0 to 4.1.1', labels: ['github-actions'], files: usesFile }).eligible,
  false,
  'a github-actions patch bump is not eligible (#2548)'
);
assert.equal(
  classify({ title: 'Bump a/b from abc1234 to def5678', labels: ['github-actions'], files: usesFile }).eligible,
  false,
  'a github-actions digest bump is not eligible'
);
assert.equal(
  classify({ title: 'Bump a/b from 4.1.0 to 4.1.1', labels: ['dependencies', 'maven'], files: usesFile }).eligible,
  false,
  'even a maven-labelled PR touching .github/workflows/ is not eligible'
);

// C3 — the title is a cross-check, never the source: a title claiming a patch cannot carry a
// major bump past the diff check.
const majorDiff = [{ filename: 'saiku-ui/package-lock.json', patch: '-        "version": "1.2.3"\n+        "version": "2.0.0"' }];
assert.equal(
  classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['dependencies', 'npm'], files: majorDiff }).eligible,
  false,
  'a title claiming a patch cannot mask a major bump: the diff decides'
);

// C2 — every ecosystem in .github/dependabot.yml waits out a cooldown of at least 3 days.
const dependabot = readFileSync(join(root, '.github', 'dependabot.yml'), 'utf8');
const blocks = dependabot.split(/^\s*-\s+package-ecosystem:/m).slice(1);
assert.equal(blocks.length, 4, 'dependabot.yml covers four ecosystems (maven, github-actions, docker, npm)');
for (const block of blocks) {
  const ecosystem = block.split('\n')[0].trim();
  const match = /cooldown:\s*\n\s+default-days:\s*(\d+)/.exec(block);
  assert.ok(match, `${ecosystem} sets a cooldown default-days`);
  assert.ok(Number(match[1]) >= 3, `${ecosystem} cooldown is at least 3 days (got ${match[1]})`);
}

// C4 — the Dependabot section of docs/ci-overview.md documents both changes.
const docs = readFileSync(join(root, 'docs', 'ci-overview.md'), 'utf8');
const section = docs.slice(docs.indexOf('## Dependabot'));
assert.match(section, /cooldown/i, 'docs/ci-overview.md documents the cooldown');
assert.match(section, /never auto-merged|never auto-merge/i, 'docs/ci-overview.md documents the Actions auto-merge exclusion');

process.stdout.write('Dependabot: Actions and workflow bumps never auto-merge, every ecosystem waits out a >=3-day cooldown, the diff decides eligibility, docs say so\n');
