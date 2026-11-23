import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
const svc = 'saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/';

const guard = read(svc + 'SchemaFileAccessGuard.java');
const guardTest = read(svc.replace('/main/', '/test/') + 'SchemaFileAccessGuardTest.java');
const confinementTest = read(
  svc.replace('/main/', '/test/') + 'FileEngineConfinementTest.java'
);

// C1: the canonicaliser resolves the nearest existing ancestor with toRealPath() and appends the
// remainder — the lexical fallback is no longer the answer for a not-yet-existing H2 candidate
// under a symlinked home.
assert.ok(
  /canonicalise/.test(guard),
  'C1: SchemaFileAccessGuard canonicalises candidates and roots'
);
assert.ok(
  /getFileName\(\)|getParent\(\)|nearest existing ancestor/i.test(guard),
  'C1: canonicalise walks up to the nearest existing ancestor and appends the remainder'
);
assert.ok(
  !/catch \(IOException e\) \{\s*try \{\s*return p\.toAbsolutePath\(\)\.normalize\(\);\s*\} catch \(RuntimeException/.test(
    guard
  ),
  'C1: a missing file no longer falls straight back to the lexical path'
);

// C2: an existing symlink's target is still followed (escape stays rejected) and a path whose
// ancestors are all missing still normalises lexically so ../ traversal keeps being refused.
assert.ok(
  /refusesASymlinkWhoseTargetEscapesTheRoot/.test(guardTest),
  'C2: the symlink-escape regression test is kept'
);
assert.ok(
  /refusesATraversalEvenWhenTheTargetDoesNotExist/.test(guardTest),
  'C2: the lexical ../ regression test is kept'
);

// C3: the regression tests the issue asks for — a symlinked saiku.home opens the FoodMart H2
// database, and a guard-level test covers a not-yet-existing file under a symlinked root.
assert.ok(
  /symlink/i.test(confinementTest) && /foodmart/i.test(confinementTest),
  'C3: FileEngineConfinementTest has a symlinked-saiku.home FoodMart H2 test'
);
assert.ok(
  /symlink/i.test(guardTest),
  'C3: SchemaFileAccessGuardTest covers a missing file under a symlinked root'
);
console.log('ok');
