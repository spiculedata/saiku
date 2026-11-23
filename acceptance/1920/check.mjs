import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => readFileSync(join(root, p), 'utf8');
const failures = [];

const resource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiOssieResource.java');
const echo = resource.match(/return error\([^;]*getMessage\(\)[^;]*\);/g) ?? [];
if (echo.length) failures.push(`C1: ${echo.length} error(...) call(s) in AiOssieResource still echo getMessage()`);
if (!/\bserverError\(/.test(resource)) failures.push('C1: AiOssieResource has no serverError(...) helper');
if (!/"ref"/.test(resource)) failures.push('C1: SERVER_ERROR body must carry a "ref"');

const asyncSvc = read('saiku-core/saiku-service/src/main/java/org/saiku/service/ossie/ai/OssieAsyncQueryService.java');
const generic = asyncSvc.split('catch (Exception e)')[1] ?? '';
if (/errorMessage\s*=\s*e\.getMessage\(\)/.test(generic)) failures.push('C2: async generic failure still stores e.getMessage()');
if (!/ref=/.test(asyncSvc)) failures.push('C2: async failure message must carry a ref');

const mcp = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/mcp/McpResource.java');
if (/"Internal error: " \+ e\.getMessage\(\)/.test(mcp)) failures.push('C3: MCP internal error still echoes e.getMessage()');
if (!/Internal error \(ref=/.test(mcp)) failures.push('C3: MCP internal error must carry a ref');

// C4: the typed envelopes stay informative
if (!/badRequest\(e\.getField\(\), e\.getMessage\(\)/.test(resource)) failures.push('C4: VALIDATION_ERROR envelope changed');
if (!/h\.errorMessage = e\.getMessage\(\);[\s\S]{0,40}\} catch \(InterruptedException/.test(asyncSvc))
  failures.push('C4: SaikuAccessDeniedException message must stay as is');

if (failures.length) { failures.forEach((f) => process.stderr.write(f + '\n')); process.exit(1); }
process.stdout.write('exception text is logged with a ref, not echoed to callers\n');
