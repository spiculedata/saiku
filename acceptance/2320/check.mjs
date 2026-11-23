import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
const svc = 'saiku-core/saiku-service/src/main/java/org/saiku/';

const policy = read(svc + 'service/datasource/JdbcUrlPolicy.java');
const confinement = read(svc + 'service/datasource/FileEngineConfinement.java');
const olap = read(svc + 'datasources/connection/SaikuOlapConnection.java');
const ossie = read(svc + 'datasources/connection/SaikuOssieConnection.java');
const docs = read('docs/operator-hardening.md');

// C1: DuckDB options are generated and applied on every open path.
for (const opt of ['enable_external_access=false', 'allowed_directories=', 'lock_configuration=true']) {
  assert.ok(confinement.includes(opt), `C1: DuckDB option ${opt} is set`);
}
const openBody = policy.slice(policy.indexOf('public static Connection openConnection(String url, String user'));
assert.ok(/FileEngineConfinement\.confine|confineFileEngines/.test(openBody), 'C1: openConnection applies the confinement');
assert.ok(/confineFileEngines/.test(ossie), 'C1: the Ossie warehouse URL is confined');
assert.ok(olap.includes('JdbcUrlPolicy.openConnection('), 'C1: the OLAP connection goes through openConnection');

// C2: H2 file paths are checked in the URL validator.
assert.ok(/FileEngineConfinement\.rejectH2PathOutsideRoots|rejectH2File/.test(policy), 'C2: checkUrl confines H2 file paths');

// C3: opt-out is named in code and documented.
assert.ok(confinement.includes('saiku.jdbc.fileEngineConfinement'), 'C3: opt-out property exists');
assert.ok(docs.includes('saiku.jdbc.fileEngineConfinement'), 'C3: opt-out is documented');
console.log('ok');
