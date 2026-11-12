// Acceptance spec for the small, safe parts of spiculedata/saiku#2348:
//   (b) user-controlled export names reach Content-Disposition through ContentDispositions
//   (c) the dead db.tcpServer=-tcpAllowOthers context-param is removed
//   (e) the WAR session cookie is HttpOnly (web.xml cookie-config)
// Unit behaviour is ContentDispositionsTest; this fails on base and passes on the fix.

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
const res = 'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources';
const helper = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/util/ContentDispositions.java');
const webXml = read('saiku-webapp/src/main/webapp/WEB-INF/web.xml');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

claim('C1', 'ContentDispositions emits the quoted and RFC 5987 forms', /filename\*=UTF-8''/.test(helper) && /filename=\\"/.test(helper));
for (const [file, count] of [
  ['ExporterResource.java', 1],
  ['Query2Resource.java', 3],
  ['AdminResource.java', 1],
]) {
  const uses = (read(`${res}/${file}`).match(/ContentDispositions\.attachment\(/g) || []).length;
  claim('C1', `${file} builds ${count} header(s) with ContentDispositions`, uses >= count);
}
claim('C2', 'db.tcpServer / tcpAllowOthers removed from web.xml', !/tcpAllowOthers|db\.tcpServer/.test(webXml));
claim('C3', 'web.xml declares an HttpOnly session cookie', /<cookie-config>[\s\S]*<http-only>\s*true\s*<\/http-only>[\s\S]*<\/cookie-config>/.test(webXml));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('export filenames are sanitised, dead tcpServer config removed, WAR cookie HttpOnly\n');
