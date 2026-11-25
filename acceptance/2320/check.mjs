import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
const svc = 'saiku-core/saiku-service/src/main/java/org/saiku/';

const policy = read(svc + 'service/datasource/JdbcUrlPolicy.java');
const confinement = read(svc + 'service/datasource/FileEngineConfinement.java');
const vfh = read(svc + 'service/datasource/SaikuVirtualFileHandler.java');
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

// C2: file-engine database paths are checked in the URL validator.
assert.ok(/FileEngineConfinement\.rejectFileEnginePathOutsideRoots|rejectH2File/.test(policy), 'C2: checkUrl confines file-engine paths');

// C3: opt-out is named in code and documented.
assert.ok(confinement.includes('saiku.jdbc.fileEngineConfinement'), 'C3: opt-out property exists');
assert.ok(docs.includes('saiku.jdbc.fileEngineConfinement'), 'C3: opt-out is documented');

// C4: SQLite / Derby / HSQLDB file paths are confined at the same chokepoint.
assert.ok(confinement.includes('rejectFileEnginePathOutsideRoots'), 'C4: a file-engine path guard exists');
for (const scheme of ['jdbc:sqlite:', 'jdbc:derby:', 'jdbc:hsqldb:']) {
  assert.ok(confinement.includes(scheme), `C4: the guard knows ${scheme} URLs`);
}
assert.ok(policy.includes('rejectFileEnginePathOutsideRoots'), 'C4: checkUrl applies the file-engine path guard');

// C5: http(s) catalogs are refused at the virtual-file handler.
assert.ok(/allowHttpCatalogs/.test(vfh), 'C5: an explicit http-catalog opt-in property exists');
assert.ok(/refuse|reject|den/i.test(vfh) && /https?:/.test(vfh), 'C5: the handler gates http(s) references');

// C6: the Properties overload confines too.
const propsBody = policy.slice(
  policy.indexOf('public static Connection openConnection(String url, Properties'),
  policy.indexOf('static void rejectDeniedInfoProperties')
);
assert.ok(/confineFileEngines|FileEngineConfinement\.confine/.test(propsBody), 'C6: openConnection(String, Properties) applies the confinement');

// C7: user-supplied DuckDB confinement keys are refused.
assert.ok(/enable_external_access/.test(policy), 'C7: the chokepoint knows the DuckDB confinement key names');
assert.ok(/lock_?configuration/.test(policy), 'C7: the chokepoint rejects user-supplied lock_configuration');
assert.ok(/allowed_?(directories|paths|configs)/.test(policy), 'C7: the chokepoint rejects user-supplied allow-list keys');

console.log('ok');
