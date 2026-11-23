// Acceptance spec for spiculedata/saiku#2432 (follow-ups to #2345). Behaviour is proven by unit tests
// (AccountStatusFilterTest, AccountStatusPolicyTest, JdbcUserDAOEnabledTest, UserServiceAccountStatusTest,
// SessionServiceAccountStatusTest); this command spec pins the structure so it cannot regress unnoticed.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const problems = [];
const expect = (ok, msg) => {
  if (!ok) problems.push(msg);
};

const filter = read('saiku-core/saiku-web/src/main/java/org/saiku/web/security/account/AccountStatusFilter.java');
const policy = read('saiku-core/saiku-web/src/main/java/org/saiku/web/security/account/AccountStatusPolicy.java');
const audit = read('saiku-core/saiku-web/src/main/java/org/saiku/web/security/audit/AuditLogger.java');
const dao = read('saiku-core/saiku-service/src/main/java/org/saiku/database/JdbcUserDAO.java');
const svc = read('saiku-core/saiku-service/src/main/java/org/saiku/service/user/UserService.java');
const daoTest = read('saiku-core/saiku-service/src/test/java/org/saiku/database/JdbcUserDAOEnabledTest.java');
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const queries = [
  'saiku-webapp/src/main/resources/database-queries.properties',
  'saiku-webapp/src/main/webapp/WEB-INF/database-queries.properties',
].map(read);
const docs = read('docs/operator-hardening.md');

expect(/AuditLogger\.accountRefused\(/.test(filter) && /AuditLogger\.accountDemoted\(/.test(filter),
  'C1: AccountStatusFilter must audit refusals and demotions');
expect(/account_refused/.test(audit) && /account_demoted/.test(audit), 'C1: AuditLogger lacks the account_refused/account_demoted events');

const insertRole = dao.slice(dao.indexOf('public void insertRole('), dao.indexOf('public void deleteUser(SaikuUser'));
expect(/TransactionTemplate|@Transactional|setAutoCommit\(false\)/.test(insertRole),
  'C2: JdbcUserDAO.insertRole must delete and insert inside one transaction');

expect(queries.every((q) => /^getUserByUsername=/m.test(q)), 'C3: getUserByUsername keyed query missing from a database-queries.properties');
expect(/findByUsername\(/.test(dao), 'C3: JdbcUserDAO.findByUsername missing');
const status = svc.slice(svc.indexOf('public AccountStatus accountStatus('));
expect(!/getUsers\(\)/.test(status.slice(0, status.indexOf('private ') > 0 ? status.indexOf('private ') : 2500)),
  'C3: accountStatus must use the keyed lookup, not getUsers()');

expect(/nanoTime/.test(policy) && !/currentTimeMillis/.test(policy), 'C4: policy TTL must use System.nanoTime()');
expect(!/assumeTrue/.test(daoTest), 'C4: JdbcUserDAOEnabledTest must not skip via assumeTrue');
expect(!/<bean id="authorisationPredicate"/.test(beans), 'C4: dead authorisationPredicate bean still defined');

const section = docs.slice(docs.indexOf('## Disabling and demoting users'));
for (const [re, what] of [
  [/re-enab/i, 'how to re-enable a user'],
  [/ROLE_SQL_EXEC|only the admin role/i, 'that only the admin role is reconciled'],
  [/rename/i, 'the rename caveat'],
  [/upgrad/i, 'the upgrade note and recovery step'],
]) {
  expect(re.test(section), 'C5: docs/operator-hardening.md must cover ' + what);
}

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
