// The acceptance spec for spiculedata/saiku#2547, run as a command spec.
//
// #2547: when a Mondrian connection fails, the exception message carries the full
// connect string, and the connection managers log it at ERROR. For a
// `security.type=passthrough` datasource the connect string carries the signed-in
// user's login name and plaintext password as `JdbcUser=`/`JdbcPassword=`; for
// other datasources it carries the stored warehouse password. Anyone who can read
// `saiku.log` (log shippers, `GET /rest/saiku/admin/log/{logname}`) sees them.
//
// Without ACCEPTANCE_BASE_URL (CI gives command specs no launcher) it checks the
// source of the tree it runs in:
//   S1: a ConnectStringRedactor exists in saiku-service and masks JdbcPassword=/
//       password= values (quoted or bare) and URL user-info passwords with ***,
//       leaving every other character of the text intact;
//   S2: it can redact a whole exception chain (redact(Throwable)), so the logged
//       cause chain no longer carries the password;
//   S3: the three connection-failure log sites (SecurityAwareConnectionManager
//       connect + refresh, AbstractConnectionManager refreshAllConnections) log
//       the redacted exception;
//   S4: the admin datasource create/update/refresh endpoints return redacted
//       error text;
//   S5: the tests ship: a saiku-service unit test for the redactor, and a
//       saiku-web test that captures the log4j2 appender output of a failed
//       connect() and asserts the password value is absent while the datasource
//       name and root cause survive.
// Command specs get no launcher, so there is no live check.
//
// Exit non-zero on the first broken clause, with the reason on stderr.

import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

const RECONNECTOR =
  'saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/ConnectStringRedactor.java';
const RECONNECTOR_TEST =
  'saiku-core/saiku-service/src/test/java/org/saiku/service/datasource/ConnectStringRedactorTest.java';
const MANAGER = 'saiku-core/saiku-web/src/main/java/org/saiku/web/core/SecurityAwareConnectionManager.java';
const ABSTRACT = 'saiku-core/saiku-service/src/main/java/org/saiku/datasources/connection/AbstractConnectionManager.java';
const ADMIN = 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AdminResource.java';

function fail(message) {
  console.error(`#2547: ${message}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch {
    fail(`cannot read ${rel}`);
  }
}

// ---- S1: the redactor exists and masks the credential shapes --------------------------
const redactor = read(RECONNECTOR);
// masks a JdbcPassword= value (case-insensitive)
if (!/jdbcpassword/i.test(redactor)) {
  fail('S1: ConnectStringRedactor has no JdbcPassword= pattern');
}
// masks a password= query/value key (URL or property form)
if (!/password\s*=|passwd|pwd/i.test(redactor)) {
  fail('S1: ConnectStringRedactor has no password= pattern');
}
// masks URL user-info (scheme://user:password@host)
if (!/user-info|userinfo|:[^@"/\s]+@/i.test(redactor)) {
  fail('S1: ConnectStringRedactor has no URL user-info pattern');
}
// the mask literal
if (!redactor.includes('"***"') && !/\bMASK\b/.test(redactor)) {
  fail('S1: ConnectStringRedactor does not define the *** mask');
}

// ---- S2: it can redact a whole exception chain -----------------------------------------
if (!/Throwable\s+redact\s*\(\s*Throwable/.test(redactor)) {
  fail('S2: ConnectStringRedactor has no redact(Throwable) for exception chains');
}

// ---- S3: the connection-failure log sites log the redacted exception -------------------
const manager = read(MANAGER);
if (!/ConnectStringRedactor\.redact\(\s*e\s*\)/.test(manager)) {
  fail('S3: SecurityAwareConnectionManager does not redact the logged connection failure');
}
const abstractManager = read(ABSTRACT);
if (!/ConnectStringRedactor\.redact\(\s*ex\s*\)/.test(abstractManager)) {
  fail('S3: AbstractConnectionManager does not redact the logged refresh failure');
}

// ---- S4: admin datasource endpoints return redacted error text -------------------------
const admin = read(ADMIN);
if (!/ConnectStringRedactor\.redact\(\s*\w+\.getLocalizedMessage\(\)\s*\)/.test(admin)) {
  fail('S4: AdminResource datasource error responses are not redacted');
}

// ---- S5: the tests ship ----------------------------------------------------------------
read(RECONNECTOR_TEST); // saiku-service redactor unit test
const webTest = readdirSync(join(root, 'saiku-core/saiku-web/src/test/java/org/saiku/web/core'), {
  withFileTypes: true,
});
const redactionTest = webTest
  .filter((e) => e.isFile() && e.name.startsWith('SecurityAwareConnectionManager') && e.name.endsWith('.java'))
  .map((e) => readFileSync(join(root, 'saiku-core/saiku-web/src/test/java/org/saiku/web/core', e.name), 'utf8'))
  .find((src) => /AbstractAppender/.test(src) && /JdbcPassword=/.test(src));
if (!redactionTest) {
  fail('S5: no SecurityAwareConnectionManager test captures appender output of a JdbcPassword= failure');
}
for (const needle of ['must never appear', 'datasource name', 'root cause']) {
  if (!redactionTest.toLowerCase().includes(needle.toLowerCase())) {
    fail(`S5: the redaction test does not assert the issue's clause (${needle})`);
  }
}

console.log('#2547: all clauses satisfied');
