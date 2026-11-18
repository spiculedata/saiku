// Acceptance spec for spiculedata/saiku#2329: "explain this number" built an LLM
// question containing the cell's formatted value and member captions (and a
// facts digest). AiAskService strips the digest under schema-only egress but
// passes the question through verbatim, so the value left the box anyway.
//
// Fix: CellExplainService.narrate asks AiAskService.permitsCellDataEgress() (the
// same gate the ask path uses) and, when cell data may not egress, keeps the
// deterministic narrative without calling the provider. Behaviour is covered by
// CellExplainServiceTest; this command spec fails on base and passes on the fix.

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
const dir = 'saiku-core/saiku-service/src';
const explain = read(`${dir}/main/java/org/saiku/service/olap/ai/explain/CellExplainService.java`);
const ask = read(`${dir}/main/java/org/saiku/service/olap/ai/ask/AiAskService.java`);
const test = read(`${dir}/test/java/org/saiku/service/olap/ai/explain/CellExplainServiceTest.java`);

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

const narrate = explain.slice(explain.indexOf('private void narrate('));
const gate = narrate.search(/permitsCellDataEgress\(\)/);
const call = narrate.search(/askService\.ask\(/);
claim('C1', 'narrate consults the egress gate before calling the provider', gate >= 0 && call >= 0 && gate < call);
claim('C3', 'AiAskService exposes the gate it already uses as permitsCellDataEgress()', /public boolean permitsCellDataEgress\(\)/.test(ask));
claim(
  'C3',
  'the ask path still uses the same method for its digest strip',
  (ask.match(/permitsCellDataEgress\(\)/g) || []).length >= 4,
);
claim('C4', 'schema-only keeps the provider uncalled (test)', /schemaOnlyEgress\w*/.test(test));
claim('C4', 'an unwired egress guard fails closed (test)', /unwiredEgressGuard\w*/.test(test));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('explain narrative honours the LLM egress policy\n');
