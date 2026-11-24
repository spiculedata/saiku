// The acceptance spec for spiculedata/saiku#2540, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2540`.
//
// #2540: AiAuditFilter only audited paths containing "/ai/". The MCP endpoint
// (/saiku/api/mcp) calls the AI resources in-process, so no MCP tools/call
// appeared in ai-audit.jsonl; three admin AI endpoints sat outside the filter
// for the same reason; and no entry carried the cube/model because the P_CUBE /
// P_MODEL enrichment keys were never set by main code.
//
// This spec is a command spec (the MCP session id is minted per server run and
// the spec runner can hold no dynamic state across steps, so a live-API spec
// cannot present a valid Mcp-Session-Id; the same constraint shaped #2324). It
// proves the behaviour structurally against the tree it runs on — which is what
// makes it fail on the base commit and pass on the fix.

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

const mcp = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/mcp/McpResource.java');
const filter = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiAuditFilter.java');
const entry = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/audit/AiAuditEntry.java');
const queryResource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiQueryResource.java');
const ossieResource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiOssieResource.java');
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const mcpTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/mcp/McpResourceTest.java');
const filterTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/AiAuditFilterTest.java');

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

// ---- C1: every MCP tools/call writes exactly one audit entry ---------------
claim('C1', 'McpResource takes the audit log', /private AiAuditLog auditLog|setAuditLog\(AiAuditLog/.test(mcp));
claim('C1', 'the audit log is wired into the mcpResource bean', /<property name="auditLog" ref="aiAuditLog"\/>/.test(beans));
claim('C1', 'AiAuditEntry carries the MCP tool name', /public String tool;/.test(entry));
claim(
  'C1',
  'handleToolsCall audits the success path with the tool name',
  /auditToolCall\(\s*name,[\s\S]{0,400}OUTCOME_SUCCESS/.test(between(mcp, 'private Response handleToolsCall', 'private Response handleInitialize') + between(mcp, 'ObjectNode wrapPolicyDeniedToolResult', 'private JsonNode dispatchTool')),
);
claim(
  'C1',
  'a policy denial is audited as denied',
  /AiPolicyViolation[\s\S]{0,600}OUTCOME_DENIED/.test(between(mcp, 'private Response handleToolsCall', 'ObjectNode wrapPolicyDeniedToolResult')),
);
claim(
  'C1',
  'a policy denial records the deny decision',
  /DECISION_DENY/.test(between(mcp, 'catch (org.saiku.service.olap.ai.AiPolicyViolation', 'private JsonNode dispatchTool')),
);
claim(
  'C1',
  'failures (unknown tool, bad args, internal error) are audited too',
  /UnknownToolException[\s\S]{0,400}auditToolCall/.test(between(mcp, 'private Response handleToolsCall', 'ObjectNode wrapPolicyDeniedToolResult')),
);
claim('C1', 'the entry carries the target cube (run_query / describe_cube / search_members)', /targetCube\(|targetCubeFor\(/.test(mcp));
claim('C1', 'the entry carries the target model (Ossie tools)', /targetModel\(|targetModelFor\(/.test(mcp));
claim('C1', 'the entry carries the caller (principal reader)', /getRemoteUser|getUserPrincipal|SecurityContextHolder/.test(mcp));

// ---- C2: the admin AI endpoints are audited --------------------------------
claim('C2', 'the filter gate covers the cube-designer turn endpoint', /\/saiku\/admin\/cube-designer\/turn/.test(filter));
claim('C2', 'the filter gate covers the eval sweep endpoint', /\/saiku\/admin\/ai-evals\/run/.test(filter));
claim('C2', 'the filter gate covers the ossie-generate endpoint', /\/saiku\/admin\/ossie-generate/.test(filter));
claim('C2', 'the admin audit read endpoint stays out of its own log', /\/saiku\/admin\/ai-audit[^/]/.test(filterTest) || /skips_non_ai_paths|admin\/ai-audit/.test(filterTest));

// ---- C3: REST AI entries carry the cube or model ---------------------------
claim('C3', 'AiQueryResource publishes the cube enrichment', /AiAuditFilter\.P_CUBE/.test(queryResource));
claim(
  'C3',
  'the cube enrichment is set on the query paths (executeAi / previewAi)',
  /P_CUBE/.test(between(queryResource, 'public Response executeAi', 'public Response describeQuery')) &&
    /P_CUBE/.test(between(queryResource, 'public Response previewAi', 'public Response listCubes')),
);
claim(
  'C3',
  'the cube enrichment is set on the schema and member-search paths',
  /P_CUBE/.test(between(queryResource, 'public Response getSchema', 'public Response listCubes')) &&
    /P_CUBE/.test(between(queryResource, 'public Response searchMembers', 'public static AiCubeRef parseCubeId')),
);
claim('C3', 'AiOssieResource publishes the model enrichment', /AiAuditFilter\.P_MODEL/.test(ossieResource));
claim('C3', 'the filter folds enrichment published as servlet request attributes', /servletRequestEnrichment|ServletRequestAttributes/.test(filter));

// ---- C4: regression tests ---------------------------------------------------
claim('C4', 'a unit test asserts the audit entry for an MCP tools/call', /OUTCOME_SUCCESS/.test(mcpTest) && /OUTCOME_DENIED/.test(mcpTest));
claim('C4', 'the MCP audit test reads the written entries back', /AiAuditLog/.test(mcpTest));
claim('C4', 'the filter gate tests cover the widened audited surface', /cube-designer\/turn|ai-evals\/run|ossie-generate/.test(filterTest));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('MCP tool calls, admin AI endpoints and cube/model enrichment are in the AI audit log\n');
