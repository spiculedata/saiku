// Acceptance spec for spiculedata/saiku#2418. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const strip = (t) => t.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');
const claude = strip(readFileSync(join(root, '.github', 'workflows', 'claude.yml'), 'utf8'));
const doc = (f) => readFileSync(join(root, 'docs', f), 'utf8');

// C1
assert.ok(!/^\s+prompt:/m.test(claude), 'C1: claude.yml must not set `prompt:` (it selects automation mode)');

// C2
const args = /claude_args:[^\n]*\n?((?:\s{12,}.*\n?)*)/.exec(claude);
assert.ok(args, 'claude_args present');
assert.match(args[0], /--append-system-prompt/, 'C2: framing must go through --append-system-prompt');
const sys = /--append-system-prompt\s+"([^"]*)"/.exec(args[0]);
assert.ok(sys, 'C2: --append-system-prompt must have a double-quoted value');
assert.match(sys[1], /UNTRUSTED/, 'C2: framing must mark issue/PR text as untrusted data');
assert.ok(!sys[1].includes('${{'), 'C2: framing must not interpolate expressions');

// C3
const allowed = /--allowed-tools\s+"([^"]*)"/.exec(args[0]);
assert.ok(allowed, 'C3: --allowed-tools present');
const tools = allowed[1].split(',').map((s) => s.trim());
for (const bad of ['Edit', 'Write', 'Bash(npm run check)', 'Bash(mvn spotless:apply)', 'Bash(git log:*)', 'Bash(git diff:*)']) {
  assert.ok(!tools.includes(bad), `C3: --allowed-tools must not contain ${bad}`);
}
assert.ok(!tools.some((t) => /^Bash\(git push/.test(t)), 'C3: git push must not be allowed');
assert.match(args[0], /--disallowed-tools[^\n]*Bash\(git push/, 'C3: git push stays disallowed');
assert.match(args[0], /(Edit|Write)\(\.github\/\*\*\)/, 'C3: edits under .github/ stay disallowed');

// C4
assert.match(claude, /head\.repo\.full_name/, 'C4: fork guard retained');
assert.ok(claude.indexOf('head.repo.full_name') < claude.indexOf('anthropics/claude-code-action@'), 'C4: guard before agent');
const issues = /^ {2}issues:\s*\n\s+types:\s*\[([^\]]*)\]/m.exec(claude);
assert.ok(issues && !/assigned/.test(issues[1]), 'C4: issues trigger must not include assigned');
assert.match(doc('review-rubric.md'), /fork/i, 'C4: review-rubric.md must mention the fork skip');
assert.match(doc('ci-overview.md'), /interactive|tag mode/i, 'C4: ci-overview.md must describe tag mode');
assert.match(doc('ci-overview.md'), /push wrapper|pushes? (still )?go/i, 'C4: ci-overview.md must describe push behaviour');
console.log('claude.yml stays in tag mode with the #2334 protections intact');
