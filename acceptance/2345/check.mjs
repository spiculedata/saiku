// Acceptance spec for spiculedata/saiku#2345. The behavioural proofs are unit tests
// (AccountStatusPolicyTest, AccountStatusFilterTest, UserServiceAccountStatusTest, JdbcUserDAOEnabledTest);
// this command spec pins the wiring so the enforcement cannot be dropped from the chains unnoticed.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const problems = [];
const expect = (ok, msg) => {
  if (!ok) problems.push(msg);
};

const sec = read('saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml');
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const dao = read('saiku-core/saiku-service/src/main/java/org/saiku/database/JdbcUserDAO.java');

const filterRefs = sec.match(/<security:custom-filter ref="accountStatusFilter"[^>]*>/g) ?? [];
expect(filterRefs.length >= 2, 'C1/C2: accountStatusFilter must be on both the main chain and /xmla/** (found ' + filterRefs.length + ')');
expect(/<bean id="accountStatusFilter"/.test(beans), 'C1: accountStatusFilter bean is not defined');
expect(/AccountStatusPolicy/.test(beans) && /name="authorisationPredicate" ref="accountStatusPolicy"/.test(beans),
  'C1: form login must authorise through AccountStatusPolicy');
expect(!/Boolean\.valueOf\(true\)/.test(dao.slice(dao.indexOf('public SaikuUser updateUser('), dao.indexOf('public void updateRoles('))),
  'C3: JdbcUserDAO.updateUser still hard-codes enabled = TRUE');

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
