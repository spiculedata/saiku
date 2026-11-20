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

const drillthroughStart = service.indexOf('public ResultSet drillthrough(ThinQuery query)');
const drillthroughEnd = service.indexOf('public DrillThroughResult drillthroughWithCaptions(', drillthroughStart);
// Keep the assertion scoped to this method without tying it to comment length. The
// fail-closed guard added for #2420 pushed the cap call past the old 1200-character
// window even though the raw path remained bounded.
const rawPath = drillthroughStart >= 0 && drillthroughEnd > drillthroughStart
  ? service.slice(drillthroughStart, drillthroughEnd)
  : '';
claim('C3', 'the raw drillthrough path does not apply the MDX MAXROWS cap', /DrillthroughMdxBuilder\.capRawDrillthrough\(/.test(rawPath));
// Mondrian's olap4j statement throws UnsupportedOperationException from setMaxRows, which
// turned every raw DRILLTHROUGH into a 500; the MDX cap is the bound on this path.
claim('C3', 'the raw drillthrough statement calls setMaxRows, which Mondrian rejects', !/\.setMaxRows\(/.test(rawPath));

claim('C4', 'the commented-drillthrough unit test does not ship', capTest.includes('leadingBlockComment'));
claim('C4', 'the RestUtil default-limit unit test does not ship', convertTest.includes('capsRowsAtGuardrail'));

if (failures.length > 0) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}

process.stdout.write('commented raw DRILLTHROUGH is capped, ResultSet conversion is bounded, the MDX MAXROWS cap bounds the raw path\n');
