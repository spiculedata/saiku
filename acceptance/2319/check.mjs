// The acceptance spec for spiculedata/saiku#2319, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2319`.
//
// #2319: values-search (and the shelf translator) inlined a user string into SQL escaped
// only by quote-doubling. Calcite pushes the literal down to the warehouse and re-emits it
// through the warehouse dialect, which for MySQL/MariaDB does not escape backslashes
// (verified against Calcite 1.42.0: the pushed JdbcFilter keeps the literal, and
// MysqlSqlDialect re-emits `'\'' OR 1=1 -- '`), so `\'` breaks out of the literal.
// This spec fails on the base commit (none of the structures below exist).

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

const resource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiOssieResource.java');
const translator = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/OssieShelfSqlTranslator.java');
const queryService = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/OssieQueryService.java');
const dialect = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/WarehouseSqlDialect.java');
const dialectTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/ossie/WarehouseSqlDialectTest.java');
const translatorTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/ossie/OssieShelfSqlTranslatorTest.java');
const valuesTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/SearchValuesBindTest.java');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};
const between = (src, from, to) => {
  const start = src.indexOf(from);
  if (start < 0) return '';
  const end = src.indexOf(to, start + from.length);
  return src.slice(start, end < 0 ? undefined : end);
};

// ---- C1 ------------------------------------------------------------------
const build = between(resource, 'static String buildValuesSearchSql', '// ----');
claim('C1', 'buildValuesSearchSql binds the pattern (LIKE ?)', /LIKE \?/.test(build));
claim('C1', 'buildValuesSearchSql no longer inlines the user string', !/replace\("'", "''"\)/.test(build));
claim(
  'C1',
  'runValuesSearch executes a PreparedStatement',
  /prepareStatement\(/.test(between(resource, 'runValuesSearch(', 'static String buildValuesSearchSql')),
);

// ---- C2 / C3 -------------------------------------------------------------
claim('C2', 'the translator has a dialect-aware translate overload', /String warehouseJdbcUrl/.test(translator));
claim('C2', 'the translator rejects backslash values on backslash-escaping dialects', /backslash/i.test(between(translator, 'private String literal', '}\n')));
claim('C3', 'WarehouseSqlDialect allow-lists standard-conforming dialects', /postgresql/.test(dialect) && /duckdb/.test(dialect));

// ---- C4 ------------------------------------------------------------------
claim('C4', 'OssieQueryService passes the warehouse URL to the translator', /warehouseJdbcUrl|URL_KEY/.test(queryService));

// ---- C5 ------------------------------------------------------------------
claim('C5', 'dialect classifier tests exist', /mysql/i.test(dialectTest));
claim('C5', 'translator backslash tests exist', /backslash/i.test(translatorTest));
claim('C5', 'bound values-search test runs against Calcite with hostile input', /jdbc:calcite/.test(valuesTest));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('Ossie values-search binds its search term and the translator refuses backslash literals on backslash-escaping warehouses\n');
