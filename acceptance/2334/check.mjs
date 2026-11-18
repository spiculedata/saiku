// Acceptance spec for spiculedata/saiku#2334. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (f) =>
  readFileSync(join(root, '.github', 'workflows', f), 'utf8')
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');
const claude = read('claude.yml');
const review = read('claude-code-review.yml');

// C1
const issues = /^ {2}issues:\s*\n\s+types:\s*\[([^\]]*)\]/m.exec(claude);
assert.ok(issues, 'claude.yml has an issues trigger');
assert.ok(!/assigned/.test(issues[1]), 'C1: issues trigger must not include `assigned`');

// C2
assert.match(claude, /head\.repo\.full_name/, 'C2: claude.yml must look up the PR head repository');
assert.match(claude, /fork/i, 'C2: claude.yml must refuse fork PRs');
const guardAt = claude.indexOf('head.repo.full_name');
const agentAt = claude.indexOf('anthropics/claude-code-action@');
assert.ok(guardAt >= 0 && guardAt < agentAt, 'C2: the fork guard runs before the agent step');
assert.match(
  review,
  /head\.repo\.full_name\s*==\s*github\.repository/,
  'C2: claude-code-review.yml must only run for same-repository PRs',
);

// C3
assert.match(claude, /^\s+prompt:/m, 'C3: claude.yml needs a fixed prompt');
assert.match(claude, /UNTRUSTED/, 'C3: the prompt must mark issue/PR text as untrusted data');
const args = /claude_args:[^\n]*\n?((?:\s{12,}.*\n?)*)/.exec(claude);
assert.ok(args && /--allowed-tools/.test(args[0]), 'C3: claude_args must set --allowed-tools');
assert.ok(!/Bash\(git push/.test(/--allowed-tools[^\n]*/.exec(args[0])[0]), 'C3: git push must not be allowed');
assert.match(args[0], /--disallowed-tools[^\n]*Bash\(git push/, 'C3: git push is explicitly disallowed');
assert.match(args[0], /(Edit|Write)\(\.github\/\*\*\)/, 'C3: edits under .github/ are disallowed');
console.log('the @claude workflows are fork-guarded, assignment-proof and tool-restricted');
