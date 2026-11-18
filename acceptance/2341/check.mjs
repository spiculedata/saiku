// Acceptance spec for spiculedata/saiku#2341. The password cache is in-process state with no HTTP
// surface, and the behavioural proofs live in SessionServicePassthroughSecretTest (unit). This
// command spec pins the contract at the source level so a regression that puts the password back
// into the shared session map, or drops the wipe hook, fails here too.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');

const sessionService = read('saiku-core/saiku-web/src/main/java/org/saiku/web/service/SessionService.java');
const connectionManager = read('saiku-core/saiku-web/src/main/java/org/saiku/web/core/SecurityAwareConnectionManager.java');
const iSessionService = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ISessionService.java');
const webXml = read('saiku-webapp/src/main/webapp/WEB-INF/web.xml');

const fail = (msg) => {
  console.error(msg);
  process.exit(1);
};

try {
  assert.ok(
    !/session\.put\(\s*"password"/.test(sessionService),
    'C1: SessionService still puts the login password into the shared session map',
  );
  assert.ok(
    !/session\.get\(\s*"password"\s*\)/.test(connectionManager),
    'C2: SecurityAwareConnectionManager still reads the password from the session map',
  );
  assert.ok(
    /passthroughCredential\s*\(/.test(iSessionService) && /passthroughCredential\s*\(/.test(connectionManager),
    'C2: pass-through must use ISessionService.passthroughCredential()',
  );
  assert.ok(
    /httpSessionDestroyed\s*\(/.test(sessionService) && /HttpSessionListener|SessionSecretCleanupListener/.test(webXml),
    'C3: no session-end hook wipes the cached credential',
  );
} catch (e) {
  fail(e.message);
}
