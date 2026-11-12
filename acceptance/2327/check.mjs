// The acceptance spec for spiculedata/saiku#2327, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2327`.
//
// The commented-DRILLTHROUGH bypass has no observable REST difference on the FoodMart fact
// table (86k rows is below the 100k ceiling), so the contract is asserted in the source; the
// behavioural half is DrillthroughMdxBuilderCapRawTest and RestUtilConvertTest (JUnit, run by
// `mvn verify`), which this spec requires to ship.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');

const builder = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/DrillthroughMdxBuilder.java');
const service = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java');
const restUtil = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/util/RestUtil.java');
const capTest = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/DrillthroughMdxBuilderCapRawTest.java');
const convertTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/util/RestUtilConvertTest.java');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

const capRaw = builder.slice(builder.indexOf('public static String capRawDrillthrough'));
claim('C1', 'capRawDrillthrough does not skip leading MDX comments', /skipLeadingTrivia/.test(capRaw));
claim('C1', 'the comment skipper does not handle "--" comments', builder.includes('"--"'));
claim('C1', 'the comment skipper does not handle "//" comments', builder.includes('"//"'));
claim('C1', 'the comment skipper does not handle "/*" comments', builder.includes('"/*"'));

const convertResultSet = restUtil.slice(
  restUtil.indexOf('public static QueryResult convert(ResultSet rs)'),
  restUtil.indexOf('private static QueryResult convert(ResultSet rs, int limit)'),
);
claim('C2', 'convert(ResultSet) still passes no row limit', /QueryGuardrails\.maxRows\(\)/.test(convertResultSet));

const drillthrough = service.slice(service.indexOf('public ResultSet drillthrough(ThinQuery query)'));
claim('C3', 'the raw drillthrough statement is not bounded with setMaxRows', /setMaxRows\(\s*QueryGuardrails\.maxRows\(\)\s*\)/.test(drillthrough.slice(0, 1200)));

claim('C4', 'the commented-drillthrough unit test does not ship', capTest.includes('leadingBlockComment'));
claim('C4', 'the RestUtil default-limit unit test does not ship', convertTest.includes('capsRowsAtGuardrail'));

if (failures.length > 0) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}

process.stdout.write('commented raw DRILLTHROUGH is capped, ResultSet conversion is bounded, statement carries setMaxRows\n');
