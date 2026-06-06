/*
 * AI Ask client — TypeScript bindings to /rest/saiku/api/ai/ask.
 *
 * The Ask endpoint takes a natural-language question + cube ref + optional
 * conversation history, sends it through the configured backend LLM provider
 * (Anthropic / OpenAI), and returns:
 *
 *   - the structured AiQueryRequest the model emitted (for "edit in canvas"),
 *   - the executed AiQueryResponse (for rendering result in the active tab),
 *   - the generated MDX (for display + copy),
 *   - a degraded envelope when the provider isn't configured or upstream
 *     fails — the UI surfaces the reason rather than throwing.
 *
 * Backend: AiAskApi (saiku-core/saiku-service/.../olap/ai/ask/AiAskApi.java).
 */

import type { AiQueryResponse } from "./aiQuery";

const ASK_URL = "/rest/saiku/api/ai/ask";

/** Mirror of {@link org.saiku.service.olap.ai.AiCubeRef}. */
export interface AiCubeRef {
  connectionName: string;
  catalog: string;
  schema: string;
  cubeName: string;
}

/** One conversation turn. Roles are restricted to user / assistant — the
 *  backend controls system prompts. */
export interface NlAskMessageDto {
  role: "user" | "assistant";
  content: string;
}

export interface AskRequest {
  question: string;
  cube: AiCubeRef;
  history?: NlAskMessageDto[];
}

/** The AiQueryRequest the model emitted. Opaque to the client — handed back
 *  to /saiku/api/ai/query verbatim if the user clicks "edit in canvas". */
export type AiQueryRequestShape = Record<string, unknown>;

export interface AskResponse {
  degraded: boolean;
  reason?: string;
  model?: string;
  /** The structured request the model emitted; absent when degraded. */
  request?: AiQueryRequestShape;
  /** The executed query result; absent when degraded before execution. */
  response?: AiQueryResponse;
  /** Convenience mirror of {@code response.metadata.generatedMdx}. */
  generatedMdx?: string;
}

/** Error thrown only on transport / non-JSON failures. The "not configured"
 *  and provider-degraded paths come back as 200 / 503 with an AskResponse body
 *  that has {@code degraded:true} — callers should read {@code .degraded}
 *  first, not assume a throw means failure. */
export class AiAskTransportError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "AiAskTransportError";
  }
}

/** POST /rest/saiku/api/ai/ask. Never throws on 4xx / 5xx whose body parses
 *  as JSON — that JSON is the AskResponse envelope carrying a degraded reason. */
export async function askAi(req: AskRequest): Promise<AskResponse> {
  let res: Response;
  try {
    res = await fetch(ASK_URL, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(req),
    });
  } catch (e) {
    throw new AiAskTransportError(`askAi: transport error (${(e as Error).message})`, 0);
  }

  const text = await res.text();
  if (!text) {
    throw new AiAskTransportError(`askAi -> ${res.status}: empty body`, res.status);
  }
  let parsed: AskResponse;
  try {
    parsed = JSON.parse(text) as AskResponse;
  } catch (e) {
    throw new AiAskTransportError(
      `askAi -> ${res.status}: non-JSON response (${(e as Error).message})`,
      res.status,
    );
  }
  return parsed;
}
