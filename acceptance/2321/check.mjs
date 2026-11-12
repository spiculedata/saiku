import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');

const policy = read('saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/JdbcUrlPolicy.java');
const olap = read('saiku-core/saiku-service/src/main/java/org/saiku/datasources/connection/SaikuOlapConnection.java');
const designer = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/cubedesigner/CubeDesignerResource.java');

// C1: the info-properties method must run the H2 token scan.
const start = policy.indexOf('static void rejectDeniedInfoProperties(');
assert.ok(start >= 0, 'rejectDeniedInfoProperties exists');
const body = policy.slice(start, policy.indexOf('\n    }\n', start));
assert.ok(body.includes('rejectDangerousH2Tokens'), 'C1: info properties are scanned for H2 executable tokens');

// C2: no raw credential concatenation; both sites use the shared quoting helper.
for (const [name, src] of [['SaikuOlapConnection', olap], ['CubeDesignerResource', designer]]) {
  assert.ok(!/"Jdbc(User|Password)="\s*\+\s*(?!JdbcUrlPolicy\.quoteMondrianValue)/.test(src), `C2: ${name} must not append credentials unquoted`);
  assert.ok(src.includes('JdbcUrlPolicy.quoteMondrianValue('), `C2: ${name} quotes credentials via the shared helper`);
}
console.log('ok');
