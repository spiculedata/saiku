// #2533: check source and regressions in the base/head proof jobs, which do not provide
// a launcher to command specs. With a launcher URL, inspect the parsed admin list.
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
const fail = (message) => {
  console.error('#2533: ' + message);
  process.exit(1);
};

const manager = read('saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/RepositoryDatasourceManager.java');
const connections = read('saiku-core/saiku-web/src/main/java/org/saiku/web/core/SecurityAwareConnectionManager.java');
const managerTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/datasource/RepositoryDatasourceManagerDeleteTest.java');
const connectionTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/core/SecurityAwareConnectionManagerDeleteTest.java');

if (!manager.includes('if (!irm.deleteFile(data.getPath()))')
    || !manager.includes('cache.remove(data.getName())')
    || !manager.includes('cache.remove(decoratedName(data.getName()))')) {
  fail('descriptor deletion must check the real path and evict both datasource cache names');
}
if (!manager.includes('connectionManager.removeConnection(data.getName())')
    || !manager.includes('connectionManager.removeConnection(decoratedName(data.getName()))')
    || !connections.includes('if (datasource == null) {')) {
  fail('connection deletion must evict both names and refuse a missing descriptor');
}
if (!managerTest.includes('delete_seeded_datasource_removes_file_and_cache_entry')
    || !connectionTest.includes('deletionEvictsAndClosesBareAndPerUserConnectionsUnderBothNameSpellings')) {
  fail('seeded datasource and live connection regression tests are missing');
}

const base = (process.env.ACCEPTANCE_BASE_URL || '').replace(/\/+$/, '');
if (!base) {
  console.log('#2533: source and regression checks pass; no launcher URL was supplied');
  process.exit(0);
}
const user = process.env.ACCEPTANCE_USER;
const password = process.env.ACCEPTANCE_PASSWORD;
if (!user || !password) fail('ACCEPTANCE_USER and ACCEPTANCE_PASSWORD are required for the live check');

const headers = {
  Authorization: 'Basic ' + Buffer.from(user + ':' + password).toString('base64'),
};
const endpoint = '/rest/saiku/admin/datasources';
const name = 'del-probe-2533';

async function request(method, path, body) {
  return fetch(base + path, {
    method,
    headers: body ? { ...headers, 'Content-Type': 'application/json' } : headers,
    body: body ? JSON.stringify(body) : undefined,
    redirect: 'manual',
  });
}

async function listContainsProbe(phase, shouldExist) {
  const response = await request('GET', endpoint);
  if (response.status !== 200) fail(phase + ': admin list answered ' + response.status + ', expected 200');
  const list = await response.json();
  if (!Array.isArray(list)) fail(phase + ': admin list is not a JSON array');
  const exists = list.some((entry) => entry?.id === name || entry?.connectionname === name);
  if (exists !== shouldExist) {
    fail(phase + ': datasource ' + name + (exists ? ' is still listed' : ' was never listed'));
  }
}

const created = await request('POST', endpoint, {
  connectionname: name,
  connectiontype: 'MONDRIAN',
  jdbcurl: 'jdbc:h2:mem:delprobe2533;MODE=MySQL',
  schema: 'del-probe-2533.xml',
  driver: 'org.h2.Driver',
  username: 'sa',
  password: '',
  id: name,
});
if (created.status !== 200) fail('create answered ' + created.status + ', expected 200');
await listContainsProbe('after create', true);

const deleted = await request('DELETE', endpoint + '/' + name);
if (deleted.status !== 200) fail('delete answered ' + deleted.status + ', expected 200');
await listContainsProbe('after delete', false);

const refreshed = await request('GET', '/rest/saiku/admin/discover/refresh');
if (refreshed.status !== 200) fail('discover refresh answered ' + refreshed.status + ', expected 200');
await listContainsProbe('after refresh', false);

const secondDelete = await request('DELETE', endpoint + '/' + name);
if (secondDelete.status !== 404) fail('second delete answered ' + secondDelete.status + ', expected 404');
console.log('#2533: datasource absent after delete and refresh, and second delete returns 404');
