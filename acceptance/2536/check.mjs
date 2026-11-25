// Acceptance spec for spiculedata/saiku#2536. Text-based (no YAML dependency); exits non-zero with
// the broken clause on stderr.
//
// The full end-to-end repro of the #2536 scenario needs two accounts (a minter and a collaborator
// with WRITE on a shared object) plus a role-secured datasource; the acceptance runner drives a
// single session user against a seeded FoodMart home, so the scenario is pinned here by the
// repository tests that reproduce it (C1) plus the binding mechanism those tests exercise (C2/C3)
// and the documentation of the rule (C4).
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (...p) => readFileSync(join(root, ...p), 'utf8');

// C2: the three record classes carry the binding.
const recordFields = [
  ['saiku-core/saiku-web/src/main/java/org/saiku/web/share/ShareToken.java', 'ShareToken'],
  ['saiku-core/saiku-web/src/main/java/org/saiku/web/embed/EmbedToken.java', 'EmbedToken'],
  ['saiku-core/saiku-web/src/main/java/org/saiku/web/embed/EmbedPublicGrant.java', 'EmbedPublicGrant']
];
for (const [path, name] of recordFields) {
  const src = read(...path.split('/'));
  assert.match(src, /contentSha256/, `C2: ${name} carries a mint-time content hash`);
  assert.match(src, /contentChangedByOther/, `C2: ${name} carries a changed-by-other poison flag`);
}

// C2: a shared hashing/matching helper exists and the guest read paths enforce it.
const binding = read('saiku-core', 'saiku-web', 'src', 'main', 'java', 'org', 'saiku', 'web', 'share', 'ContentBinding.java');
assert.match(binding, /SHA-256|Sha256|sha256/, 'C2: binding helper hashes with SHA-256');
assert.match(binding, /matches\(/, 'C2: binding helper compares pinned hash to content');
assert.match(binding, /409|CONFLICT/, 'C2: binding helper produces the 409 conflict response');

const readers = [
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/share/ShareViewResource.java',
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/share/NotebookShareViewResource.java',
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/embed/EmbedViewResource.java'
];
for (const p of readers) {
  const src = read(...p.split('/'));
  assert.match(src, /ContentBinding|contentBinding/, `C2: ${p.split('/').pop()} enforces the content binding`);
  assert.match(src, /contentChangedByOther/, `C2: ${p.split('/').pop()} refuses poisoned records`);
  assert.match(src, /contentSha256/, `C2: ${p.split('/').pop()} consults the pinned hash`);
}

// C2: mints bind the hash at mint time.
for (const p of [
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/share/ShareTokenResource.java',
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/embed/EmbedTokenResource.java'
]) {
  const src = read(...p.split('/'));
  assert.match(src, /ContentBinding|contentSha256/, `C2: ${p.split('/').pop()} binds the minted content`);
}

// C3: every repository file save funnels through DatasourceService.saveFile, which notifies a
// listener; the listener re-arms the minter's records and poisons everyone else's.
const ds = read('saiku-core', 'saiku-service', 'src', 'main', 'java', 'org', 'saiku', 'service', 'datasource', 'DatasourceService.java');
assert.match(ds, /RepositoryFileListener/, 'C3: DatasourceService notifies a RepositoryFileListener');
assert.match(ds, /notifyFileSaved|onFileSaved/, 'C3: saveFile notifies the listener');

const listener = read('saiku-core', 'saiku-web', 'src', 'main', 'java', 'org', 'saiku', 'web', 'share', 'SharedContentBindingListener.java');
assert.match(listener, /onContentSaved/, 'C3: listener calls the stores\' save hook');
assert.match(
  listener,
  /ShareTokenStore|shareTokenStore[\s\S]*EmbedTokenStore[\s\S]*EmbedPublicRegistry/,
  'C3: listener covers share tokens, embed tokens and public grants');

for (const p of [
  'saiku-core/saiku-web/src/main/java/org/saiku/web/share/ShareTokenStore.java',
  'saiku-core/saiku-web/src/main/java/org/saiku/web/embed/EmbedTokenStore.java',
  'saiku-core/saiku-web/src/main/java/org/saiku/web/embed/EmbedPublicRegistry.java'
]) {
  const src = read(...p.split('/'));
  assert.match(src, /onContentSaved/, `C3: ${p.split('/').pop()} implements the save hook`);
  assert.match(src, /sameUser/, `C3: ${p.split('/').pop()} distinguishes the minter by account identity`);
}

// C3: the wiring attaches the listener and injects the stores into the read resources.
const beans = read('saiku-webapp', 'src', 'main', 'webapp', 'WEB-INF', 'saiku-beans.xml');
assert.match(beans, /SharedContentBindingListener/, 'C3: the listener is wired as a bean');
assert.match(beans, /repositoryFileListeners/, 'C3: DatasourceService gets the listener injected');

// C1: the scenario tests exist and assert the refusal + the serving path.
const scenarioTests = [
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/share/ShareViewResourceContentBindingTest.java', /409|CONFLICT/],
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/share/NotebookShareViewResourceContentBindingTest.java', /409|CONFLICT/],
  ['saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/embed/EmbedViewResourceContentBindingTest.java', /409|CONFLICT/]
];
for (const [p, expect] of scenarioTests) {
  assert.ok(existsSync(join(root, ...p.split('/'))), `C1: ${p} exists`);
  const src = read(...p.split('/'));
  assert.match(src, expect, `C1: ${p.split('/').pop()} asserts the refusal`);
  assert.match(src, /contentChangedByOther|changedByOther|tamper/, `C1: ${p.split('/').pop()} covers the non-minter write`);
}

// C4: the rule is documented.
let docsCovered = 0;
for (const p of [
  'docs/embed/quickstart.md',
  'docs/dashboards-user-guide.md',
  'saiku-ui/src/embed/README.md'
]) {
  const src = read(...p.split('/'));
  if (/content hash|content binding|re-mint|rearms?|re-arms?|no longer matches|changed since/i.test(src)) docsCovered++;
}
assert.ok(docsCovered >= 2, 'C4: the binding rule is documented in the sharing/embed docs');

console.log('#2536 acceptance spec: all clauses pass');
