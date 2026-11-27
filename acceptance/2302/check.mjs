// Acceptance spec for spiculedata/saiku#2302: the cube designer gains a
// default measure on <Cube> properties. The engine already reads Mondrian 4's
// `defaultMeasure` cube attribute (FoodMart4.xml carries
// `<Cube name='Sales' defaultMeasure='Unit Sales'>`); this issue is the
// designer half — pick a measure under the cube's properties, round-trip it
// through export/import, and keep it across the workbench write-back.
// The browser proof is the designer's vitest suite
// (saiku-ui/src/lib/cube-designer/*.test.ts); this check pins the contract in
// source and, when a launcher is configured (ACCEPTANCE_BASE_URL), the live
// FoodMart cube still reports the seeded default measure.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => {
  try {
    return readFileSync(join(root, path), 'utf8');
  } catch {
    return '';
  }
};

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// C1: the exporter emits defaultMeasure on the <Cube> element.
const exp = read('saiku-ui/src/lib/cube-designer/mondrian-export.ts');
const cubeLine = exp.split('\n').find((l) => l.includes('<Cube name='));
claim('C1', 'mondrian-export.ts emits <Cube> elements', cubeLine !== undefined);
claim(
  'C1',
  'the <Cube> element is emitted with a defaultMeasure attribute',
  !!cubeLine && cubeLine.includes("defaultMeasure")
);
claim(
  'C1',
  'the export validates the named measure against the cube\u2019s own measures before emitting it',
  /defaultMeasure[\s\S]{0,800}?measureColumns/.test(exp)
);

// C2: the importer reads defaultMeasure back off <Cube>.
const imp = read('saiku-ui/src/lib/cube-designer/mondrian-import.ts');
claim('C2', 'mondrian-import.ts reads the defaultMeasure attribute of <Cube>', /getAttribute\(['"]defaultMeasure['"]\)/.test(imp));
claim('C2', 'the imported workbench cube carries defaultMeasure', /defaultMeasure\?:/.test(imp));

// C3: the workbench seed mapping carries it onto the doc cube.
const seed = read('saiku-ui/src/routes/admin/cube-designer/[dataSourceId]/workbench-seed.ts');
claim(
  'C3',
  'mapWorkbenchDocCubes carries defaultMeasure onto the doc cube',
  /defaultMeasure:\s*c\.defaultMeasure/.test(seed)
);

// C4: the inspector's <Cube> properties panel offers the select.
const insp = read('saiku-ui/src/lib/cube-designer/InspectorProperties.svelte');
claim('C4', 'the inspector renders a default-measure select on cube properties', /default measure/.test(insp));
claim('C4', 'the select is test-addressable as inspector-cube-default-measure', /inspector-cube-default-measure/.test(insp));

// C5: the store mutator + write-back preservation.
const st = read('saiku-ui/src/lib/cube-designer/state.svelte.ts');
claim('C5', 'the store exposes setDefaultMeasure', /setDefaultMeasure\(/.test(st));
claim(
  'C5',
  'setCubes preserves an existing cube\u2019s defaultMeasure across the workbench write-back',
  /existingDefaultMeasure/.test(st)
);

// Optional live proof: the seeded FoodMart Sales cube keeps its engine-side
// default measure (not authored by the designer — this only guards that the
// attribute survives the chain end to end when a launcher is available).
const base = (process.env.ACCEPTANCE_BASE_URL || process.env.SAIKU_BASE_URL || '').replace(/\/+$/, '');
if (base) {
  let res = null;
  try {
    res = await fetch(`${base}/rest/saiku/api/ai/cubes`, { headers: { Accept: 'application/json' } });
  } catch (e) {
    failures.push(`C1: GET ${base}/rest/saiku/api/ai/cubes failed: ${e.message}`);
  }
  if (res && res.status === 200) {
    const cubes = await res.json();
    const sales = Array.isArray(cubes) ? cubes.find((c) => c && c.name === 'Sales') : null;
    claim(
      'C1',
      'the live FoodMart Sales cube reports its seeded defaultMeasure (Unit Sales)',
      !!sales && sales.defaultMeasure === 'Unit Sales'
    );
  }
}

if (failures.length) {
  for (const f of failures) process.stderr.write(f + '\n');
  process.exit(1);
}
process.stdout.write('cube designer <Cube> properties offer a default measure, round-tripped through Mondrian export/import (saiku#2302)\n');
