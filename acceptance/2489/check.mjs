// Acceptance spec for spiculedata/saiku#2489: the Cube Designer agent (the
// designer's /turn endpoint, CubeDesignerAiService) was the last AI path that
// only spoke Anthropic. It must now pick its upstream with the same
// saiku.ai.ask.provider setting the NL-ask layer uses - anthropic (default,
// unchanged), openai, ollama (keyless) or noop - and the OpenAI-compatible
// transport must preserve the text + tool_use content-block contract the
// client executes against. Most criteria are behavioural and pinned by the
// unit tests (scripted HttpClient); this check pins the contract's presence
// in source + tests + docs, and is deliberately red on the base branch.

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

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

const service = read('saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/cubedesigner/CubeDesignerAiService.java');
const test = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/cubedesigner/CubeDesignerAiServiceTest.java');
const resource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/cubedesigner/CubeDesignerResource.java');
const info = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/InfoResource.java');
const infoTest = read('saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/InfoResourceTest.java');
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const docs = read('docs/AI-QUERY-API.md');
const scripted = read('saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/cubedesigner/ScriptedHttpClient.java');

// C1: openai/ollama transport with tool_calls → text + tool_use mapping.
claim('C1', 'the designer service selects openai and ollama providers', /PROVIDER_OPENAI/.test(service) && /PROVIDER_OLLAMA/.test(service));
claim('C1', 'it builds an OpenAI-compatible chat completions body', /buildOpenAiRequestBody/.test(service));
claim('C1', 'it sends the designer tools as OpenAI function tools with parameters', /"function"/.test(service) && /parameters/.test(service));
claim('C1', 'it maps tool_calls back to tool_use content blocks', /tool_use/.test(service) && /tool_calls/.test(service));
claim('C1', 'ollama is keyless with a placeholder bearer and localhost default', /OLLAMA_KEYLESS_PLACEHOLDER/.test(service) && /11434/.test(service));
claim('C1', 'a scripted-HttpClient unit test drives the openai turn', /ScriptedHttpClient/.test(test) && /openaiTurn_mapsToolCallsBackToAnthropicBlocks/.test(test));
claim('C1', 'the scripted test asserts the tool_use block shape (id/name/input)', /call_1/.test(test) && /add_table_to_canvas/.test(test) && /public\.sales/.test(test));
claim('C1', 'the bean wiring passes the ask provider setting to the designer service', beans.includes('${saiku.ai.ask.provider:'));

// C2: tool_result → role:"tool" message on the next turn.
claim('C2', 'the service translates tool_result blocks to role:"tool" messages', /tool_call_id/.test(service) && /"tool"/.test(service));
claim('C2', 'the assistant tool_use turn becomes an OpenAI tool_calls message', /arguments/.test(service));
claim('C2', 'a unit test pins the next-turn translation', /toolResult_nextTurn_translatedToOpenAiToolMessage/.test(test));
claim('C2', 'the unit test asserts the tool_call_id round-trips', /tool_call_id/.test(test));

// C3: the anthropic path is unchanged.
claim('C3', 'the legacy anthropic constructor is kept', service.includes('public CubeDesignerAiService(String apiKey, String model, String endpoint, int timeoutSeconds)'));
claim('C3', 'the anthropic transport keeps its headers', /x-api-key/.test(service) && /anthropic-version/.test(service));
claim('C3', 'the anthropic request builder is untouched', /buildRequestBody/.test(service));
claim('C3', 'the pre-existing anthropic tests still run unedited', test.includes('buildRequestBody_carriesPromptTools_canvasState_andMessagesVerbatim') && test.includes('parseContent_returnsTheContentBlockArrayVerbatim'));
claim('C3', 'the anthropic default endpoint and model are kept', service.includes('https://api.anthropic.com/v1/messages') && service.includes('claude-sonnet-4-6'));

// C4: misbehaving upstream → clear errors, never a 500.
claim('C4', 'missing id/name in tool_calls is a clear IOException', /tool call without an id or name/.test(service));
claim('C4', 'bad arguments JSON is a clear IOException', /malformed arguments/.test(service));
claim('C4', 'HTTP errors surface as IOException naming the status', /AI provider returned HTTP/.test(service));
claim('C4', 'timeouts surface as a clear IOException', /timed out/.test(service));
claim('C4', 'unit tests cover each failure mode', /malformedToolCall_missingIdOrName_throwsClearError/.test(test) && /badJsonInArguments_throwsClearError/.test(test) && /httpError_throwsClearIoException/.test(test) && /timeout_throwsClearIoException/.test(test));

// C5: no usable provider → 503 with an "AI upstream not configured" message.
claim('C5', 'noop is never configured', /noop_isNeverConfigured/.test(test));
claim('C5', 'openai without a key is not configured', /openai_notConfiguredWithoutKey/.test(test));
claim('C5', 'the unconfigured turn throws (→ 503) with the not-configured message', /not configured/.test(service));
claim('C5', 'the designer resource answers 503 with the AI upstream not configured body', /AI upstream not configured/.test(resource));

// C6: diagnostics include the cube designer stack.
claim('C6', 'the designer service exposes a diagnostics descriptor', /record Descriptor/.test(service) && /public Descriptor describe\(\)/.test(service));
claim('C6', 'InfoResource reports the cubeDesigner section', /cubeDesigner/.test(info));
claim('C6', 'the diagnostics unit test covers the cube designer stack', /diagnostics_reportsCubeDesigner/.test(infoTest));
claim('C6', 'the diagnostics test wires the designer bean from the spring context', beans.includes('<property name="cubeDesignerAiService" ref="cubeDesignerAiService"/>'));
claim('C6', 'the scripted fake client exists for the transport unit tests', scripted.includes('class ScriptedHttpClient'));

// C7: docs explain running the designer against Ollama.
claim('C7', 'the AI config docs have a Cube Designer agent section', docs.includes('Cube Designer agent'));
claim('C7', 'they state the designer follows saiku.ai.ask.provider', /same provider setting as the ask layer/.test(docs));
claim('C7', 'they cover the keyless ollama path for the designer', /ollama/.test(docs) && /cube-designer\/turn/.test(docs));
claim('C7', 'they note the 503 unconfigured behaviour', /AI upstream not configured/.test(docs));

if (failures.length > 0) {
  console.error(`acceptance/2489: ${failures.length} failure(s):`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('acceptance/2489: all criteria hold.');
