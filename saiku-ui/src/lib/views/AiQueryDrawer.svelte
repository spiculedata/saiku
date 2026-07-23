<script lang="ts">
  /*
   * AI Query drawer — right-side slide-in panel that translates natural-
   * language questions into MDX via the backend NL ask bridge (PR1 of
   * #1093). Each successful turn lets the user click "Edit in canvas"
   * to load the generated MDX into the active workspace tab; the canvas
   * re-runs the query against the workspace path so all existing
   * grid / chart / drill features just work.
   *
   * Chat thread (Phase 1 + 2 — multi-turn with follow-up context):
   * prior turns are sent back as `history` so follow-ups like "now
   * break it down by region" resolve against the earlier question.
   *
   * Backend: POST /saiku/api/ai/ask (saiku-core/saiku-web/.../AiQueryResource.java
   * → AiAskService). 503 = provider not configured; surfaced as a
   * banner with the reason from the response body.
   */

  import { aiInsight } from "$lib/stores/aiInsight.svelte";
  import { emailComposer } from "$lib/stores/emailComposer.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { askAi, askAiStream, AiAskTransportError, type AiInsight, type AiViewChange, type AskResponse, type NlAskMessageDto } from "$lib/api/aiAsk";
  import { buildCellsetDigest } from "$lib/api/cellsetDigest";
  import { aiRequestToQueryModel, queryModelToAiRequest, type AiQueryRequestShape } from "$lib/api/aiQueryToModel";
  import { classifyChainEnvelope } from "$lib/api/chainStep";
  import { renderTinyMarkdown } from "$lib/api/tinyMarkdown";
  import { query } from "$lib/stores/query.svelte";
  import { X, Send, Sparkles, ChevronDown, ChevronRight, Copy, Trash2 } from "lucide-svelte";

  /**
   * Payload passed to the host's "edit in canvas" handler. Prefer the
   * interactive queryModel when the server returned one (every non-degraded
   * path since the AskResponse.queryModel surfacing); fall back to MDX only
   * when conversion was skipped (validation error / degraded provider).
   */
  export interface EditInCanvasPayload {
    mdx: string;
    /** ThinQueryModel — interactive chip-editable structured query. */
    queryModel?: unknown;
  }

  interface Props {
    open: boolean;
    onClose: () => void;
    /** Workspace runs the query in the canvas behind the drawer. Pass both
     *  the queryModel (preferred — interactive) and the mdx (fallback). */
    onEditInCanvas: (payload: EditInCanvasPayload) => void;
    /** Apply an AI-emitted view change (mode + chartType) to the workspace. */
    onApplyViewChange: (vc: AiViewChange) => void;
  }

  let { open, onClose, onEditInCanvas, onApplyViewChange }: Props = $props();

  /** One displayed turn in the chat thread. */
  interface ChatTurn {
    id: string;
    /** kind drives the renderer:
     *  - user: the question bubble
     *  - assistant: a QUERY result (summary + collapsible MDX + edit-in-canvas)
     *  - insight: AI's markdown analysis of the current cellset
     *  - viewChange: AI's view-mode + chart-type choice (applied automatically; this is the receipt)
     *  - error: degradation reason / refusal */
    role: "user" | "assistant" | "insight" | "viewChange" | "error";
    /** User: the question. Assistant/error: short summary. Insight: headline. ViewChange: rationale. */
    text: string;
    /** Assistant only: the generated MDX for collapsible display + edit. */
    mdx?: string;
    /** Assistant only: the converted ThinQueryModel for interactive
     *  hydration of the workbench (chip-editable). When present, "edit
     *  in canvas" prefers this; mdx is the fallback. */
    queryModel?: unknown;
    /** Insight only: full markdown body the LLM emitted. Rendered as text in the bubble (we don't
     *  pull in a markdown lib for the drawer; the LLM is instructed to keep formatting tight). */
    insightMarkdown?: string;
    /** ViewChange only: the applied target — drawer shows mode + chartType as a small receipt. */
    viewChange?: AiViewChange;
    /** Assistant only: which model the backend used. */
    model?: string;
    /** Assistant only: VALIDATION_ERROR field + candidate suggestions. */
    field?: string;
    available?: string[];
    /** Per-turn collapse flag for the MDX <details>. */
    mdxExpanded?: boolean;
  }

  let turns = $state<ChatTurn[]>([]);
  let prompt = $state<string>("");
  let inflight = $state<boolean>(false);

  /**
   * Resizable drawer width. Persisted to localStorage so the user's preferred
   * width survives reloads. Bounded between MIN/MAX so a misclick can't drag
   * the drawer too narrow to type in or so wide it eats the canvas. Default
   * 380px matches the previous fixed value.
   */
  const DRAWER_MIN_PX = 320;
  const DRAWER_MAX_PX = 1100;
  const DRAWER_DEFAULT_PX = 380;
  const DRAWER_WIDTH_LS_KEY = "saiku_ai_drawer_width";
  let drawerWidth = $state<number>(loadDrawerWidth());
  let resizing = $state<boolean>(false);

  function loadDrawerWidth(): number {
    if (typeof localStorage === "undefined") return DRAWER_DEFAULT_PX;
    const raw = localStorage.getItem(DRAWER_WIDTH_LS_KEY);
    const n = raw ? parseInt(raw, 10) : NaN;
    if (!Number.isFinite(n)) return DRAWER_DEFAULT_PX;
    return Math.max(DRAWER_MIN_PX, Math.min(DRAWER_MAX_PX, n));
  }

  function saveDrawerWidth(): void {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(DRAWER_WIDTH_LS_KEY, String(drawerWidth));
  }

  /**
   * Mouse-driven resize. Mousedown on the handle starts tracking; mousemove
   * updates width based on viewport-X (drawer is right-anchored so width =
   * window.innerWidth - clientX). Mouseup persists. Cursor + user-select
   * cues live on the body during the drag so the user gets unambiguous
   * feedback even if the cursor leaves the handle.
   */
  function startResize(e: MouseEvent): void {
    if (e.button !== 0) return;
    e.preventDefault();
    resizing = true;
    document.body.style.cursor = "ew-resize";
    document.body.style.userSelect = "none";
    const onMove = (ev: MouseEvent) => {
      const w = Math.max(DRAWER_MIN_PX, Math.min(DRAWER_MAX_PX, window.innerWidth - ev.clientX));
      drawerWidth = w;
    };
    const onUp = () => {
      resizing = false;
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      saveDrawerWidth();
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }
  /** Mode picker. Auto = LLM routes via question shape; the others force a single tool. */
  type ForceTool = "auto" | "query" | "insight" | "view_change";
  let forceTool = $state<ForceTool>("auto");
  /** Opt-in "Build & report" toggle — OFF by default. When true, send routes to submitChained()
   *  (askAiStream against the chain endpoint) instead of submit()'s single-turn askAi. The
   *  single-turn path below is untouched by this flag; it only changes which function the send
   *  button/keyboard shortcut calls. */
  let buildAndReport = $state(false);
  /** Elapsed-time indicator for in-flight requests so 5-10s Anthropic round-trips don't read as
   *  "frozen". Ticks every 250ms; only visible while inflight. */
  let inflightStartedAt = $state<number | null>(null);
  let inflightElapsedMs = $state<number>(0);
  $effect(() => {
    if (!inflight) {
      inflightStartedAt = null;
      inflightElapsedMs = 0;
      return;
    }
    inflightStartedAt = Date.now();
    inflightElapsedMs = 0;
    const id = setInterval(() => {
      if (inflightStartedAt != null) {
        inflightElapsedMs = Date.now() - inflightStartedAt;
      }
    }, 250);
    return () => clearInterval(id);
  });
  /** Set when the backend returns 503 — banner persists until cleared / new ask. */
  let notConfiguredBanner = $state<string | null>(null);
  let scrollEl = $state<HTMLElement | null>(null);
  let inputEl = $state<HTMLTextAreaElement | null>(null);

  // Autoscroll on new turn.
  $effect(() => {
    if (turns.length && scrollEl) {
      // Defer until DOM paints — scrollTop on the freshly-added node lands
      // at the bottom even if the new turn pushes past the previous height.
      queueMicrotask(() => {
        if (scrollEl) scrollEl.scrollTop = scrollEl.scrollHeight;
      });
    }
  });

  // Focus the textarea when the drawer opens.
  $effect(() => {
    if (open && inputEl) {
      queueMicrotask(() => inputEl?.focus());
    }
  });

  // Esc closes.
  $effect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  });

  /** Convert the live chat thread into the wire `history` shape the backend
   *  expects. Skips error turns (they aren't part of the conversation). */
  function historyForRequest(): NlAskMessageDto[] {
    const out: NlAskMessageDto[] = [];
    for (const t of turns) {
      if (t.role === "user") {
        out.push({ role: "user", content: t.text });
      } else if (t.role === "assistant") {
        // Send the model's emitted summary back as `assistant` context — the
        // model is forced to emit tool_use only, so this is purely a hint
        // about what was returned last turn.
        out.push({ role: "assistant", content: t.text });
      } else if (t.role === "insight") {
        // Insight responses also belong in history so follow-ups like "go deeper on the regional
        // split" land in context. Use the markdown body, not just the headline.
        out.push({ role: "assistant", content: t.insightMarkdown ?? t.text });
      } else if (t.role === "viewChange") {
        // ViewChange receipts get folded in too so "now switch to a stacked bar" can reference
        // "previous chart was a line chart on revenue".
        const vc = t.viewChange;
        const summary = vc
          ? `Changed view to ${vc.viewMode}${vc.chartType ? ` (${vc.chartType})` : ""}.`
          : t.text;
        out.push({ role: "assistant", content: summary });
      }
    }
    return out;
  }

  function nextId(): string {
    return `t${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  }

  async function submit(): Promise<void> {
    const question = prompt.trim();
    if (!question || inflight) return;
    const cube = selection.cube;
    if (!cube) {
      // No cube — emit a local error turn rather than going to the backend.
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.noCube"),
        },
      ];
      return;
    }

    const history = historyForRequest();
    const userTurn: ChatTurn = { id: nextId(), role: "user", text: question };
    turns = [...turns, userTurn];
    prompt = "";
    inflight = true;
    notConfiguredBanner = null;

    let resp: AskResponse;
    try {
      // Include a markdown digest of the current cellset so the model can route to insight
      // ("spot trends") or view-change ("switch to chart") tools. Server-side AiPolicyGuard will
      // strip it when AI policy is "schema-only"; client just sends what it has on screen.
      const cellsetDigest = buildCellsetDigest(query.result) || undefined;
      // Snapshot the user's current query as an AiQueryRequest so the LLM can EXTEND rather than
      // wipe-and-rewrite on follow-ups like "add therapeutic class to columns". Null when there's
      // no live queryModel (fresh tab, MDX-mode query, etc).
      const currentQuery = query.current?.queryModel
        ? queryModelToAiRequest(query.current.queryModel, {
            connectionName: cube.connection,
            catalog: cube.catalog,
            schema: cube.schema,
            cubeName: cube.name,
          }) ?? undefined
        : undefined;
      resp = await askAi({
        question,
        cube: {
          connectionName: cube.connection,
          catalog: cube.catalog,
          schema: cube.schema,
          cubeName: cube.name,
        },
        history,
        cellsetDigest,
        forceTool: forceTool === "auto" ? undefined : forceTool,
        currentQuery,
      });
    } catch (e) {
      const message = e instanceof AiAskTransportError ? e.message : (e as Error).message;
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.transportError").replace("{message}", message),
        },
      ];
      inflight = false;
      return;
    }

    if (resp.degraded) {
      const reason = resp.reason ?? i18n.t("workspace.aiQuery.unknownError");
      if (reason.toLowerCase().includes("not configured")) {
        notConfiguredBanner = reason;
      }
      // Server-side scope guardrail — the model called refuse_off_topic
      // because the question isn't about the cube. Render as a softer
      // "scope" turn rather than a generic error so the user can rephrase.
      const isOffTopic = reason.startsWith("OFF_TOPIC: ");
      const text = isOffTopic
        ? i18n.t("workspace.aiQuery.offTopic").replace("{reason}", reason.slice("OFF_TOPIC: ".length))
        : reason;
      turns = [
        ...turns,
        {
          id: nextId(),
          role: isOffTopic ? "assistant" : "error",
          text,
        },
      ];
      inflight = false;
      return;
    }

    // Intent: insight — model produced markdown analysis of the user's current cellset.
    // Render in the thread; no canvas mutation.
    if (resp.insight) {
      // Lift the markdown into the shared store so surfaces outside the
      // drawer (the "email me this" modal) can read the latest insight
      // without owning the chat thread. Plain event-handler write — not
      // inside an $effect — so no read/write reentrancy risk here.
      aiInsight.set(resp.insight.markdown);
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "insight",
          text: resp.insight.headline ?? "",
          insightMarkdown: resp.insight.markdown,
          model: resp.model,
        },
      ];
      inflight = false;
      return;
    }

    // Intent: email draft — model picked the email intent. Hand the summary off to the
    // "email me this" modal via the shared aiInsight store (same seed path the modal already
    // reads from) and signal the toolbar to open it. The AI never sends — the human reviews
    // and clicks Send in the modal.
    if (resp.emailDraft) {
      aiInsight.set(resp.emailDraft.summary);
      emailComposer.requestOpen();
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "assistant",
          text: i18n.t("workspace.aiQuery.emailDraftOpening", "Opening a draft email for you to review…"),
          model: resp.model,
        },
      ];
      inflight = false;
      return;
    }

    // Intent: view-change — model picked viewMode + chartType. Apply via host, log a receipt turn.
    if (resp.viewChange) {
      onApplyViewChange(resp.viewChange);
      const target = resp.viewChange.viewMode === "chart"
        ? `chart (${resp.viewChange.chartType ?? "?"})`
        : "grid";
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "viewChange",
          text: resp.viewChange.reason ?? `Switched view to ${target}.`,
          viewChange: resp.viewChange,
          model: resp.model,
        },
      ];
      inflight = false;
      return;
    }

    const ai = resp.response;
    const mdx = resp.generatedMdx ?? ai?.metadata?.generatedMdx ?? "";
    // The server's AiSchemaConverter goes AiQueryRequest → MDX directly and
    // never populates queryModel, so resp.queryModel is always null on the
    // wire. Build the queryModel client-side from resp.request (the AI's
    // typed AiQueryRequest) instead — same intent, surfaced as interactive
    // chips the user can drag/drop/edit. Falls back to whatever the server
    // sent (probably null) only if the converter rejected the request.
    const queryModel =
      resp.queryModel ?? (resp.request ? aiRequestToQueryModel(resp.request as AiQueryRequestShape) : null);
    if (ai && ai.status === "VALIDATION_ERROR") {
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "assistant",
          text: i18n.t("workspace.aiQuery.validationError").replace("{error}", ai.error ?? ""),
          mdx,
          queryModel,
          model: resp.model,
          field: ai.field,
          available: ai.available,
          mdxExpanded: false,
        },
      ];
      inflight = false;
      return;
    }

    const rowCount = ai?.totalRows ?? ai?.data?.length ?? ai?.matrix?.length ?? 0;
    const summary = i18n
      .t("workspace.aiQuery.resultSummary")
      .replace("{rows}", String(rowCount));
    turns = [
      ...turns,
      {
        id: nextId(),
        role: "assistant",
        text: summary,
        mdx,
        queryModel,
        model: resp.model,
        mdxExpanded: false,
      },
    ];
    inflight = false;

    // Auto-render in the canvas behind the drawer so the user sees data
    // immediately. Prefer the queryModel payload — the workspace hydrates
    // it into the chip builder so the canvas behind the drawer is fully
    // interactive (drag/drop levels, add measures, change axis filters)
    // without ever round-tripping to MDX. Falls back to MDX paste when
    // the server didn't return a queryModel (degraded / validation error).
    if (mdx || queryModel) {
      onEditInCanvas({ mdx, queryModel });
    }
  }

  /**
   * Opt-in chained path — "Build & report" toggle. Streams a multi-step ask (query builds → runs
   * → report renders) via askAiStream and dispatches each step into the chat thread + workspace,
   * instead of the single askAi round-trip submit() makes. Mirrors submit()'s opening exactly
   * (guards, user turn, cellsetDigest/currentQuery, inflight) so the request payload and the local
   * thread bookkeeping stay identical between the two paths; only how the response is consumed
   * differs.
   */
  async function submitChained(): Promise<void> {
    const question = prompt.trim();
    if (!question || inflight) return;
    const cube = selection.cube;
    if (!cube) {
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.noCube"),
        },
      ];
      return;
    }

    const history = historyForRequest();
    const userTurn: ChatTurn = { id: nextId(), role: "user", text: question };
    turns = [...turns, userTurn];
    prompt = "";
    inflight = true;
    notConfiguredBanner = null;

    const cellsetDigest = buildCellsetDigest(query.result) || undefined;
    const currentQuery = query.current?.queryModel
      ? queryModelToAiRequest(query.current.queryModel, {
          connectionName: cube.connection,
          catalog: cube.catalog,
          schema: cube.schema,
          cubeName: cube.name,
        }) ?? undefined
      : undefined;

    const req = {
      question,
      cube: {
        connectionName: cube.connection,
        catalog: cube.catalog,
        schema: cube.schema,
        cubeName: cube.name,
      },
      history,
      cellsetDigest,
      forceTool: forceTool === "auto" ? undefined : forceTool,
      currentQuery,
    };

    // Model tag surfaced by the "via {model}" caption; captured from the `model` SSE event and
    // stamped onto whichever turns we push after it arrives.
    let model: string | undefined;
    // Id of the in-progress insight turn we grow with streamed chunks, or null before the report
    // step starts (or if this chain never reaches one, e.g. it hits the step limit first).
    let reportTurnId: string | null = null;

    // Hydrate the workspace + push a "built the query" turn for a built-query step/terminal
    // envelope — the same construction submit()'s QUERY path uses (aiRequestToQueryModel +
    // onEditInCanvas), just reused for both the intermediate `step` event and a `final` envelope
    // that turns out to be a query (the chain hit its step limit before a report followed).
    const hydrateBuiltQuery = (env: AskResponse): void => {
      const queryModel = env.queryModel ?? aiRequestToQueryModel(env.request as AiQueryRequestShape);
      const mdx = env.generatedMdx ?? "";
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "assistant",
          text: i18n.t("workspace.aiQuery.builtQuery", "Built the query — running it…"),
          mdx,
          queryModel,
          model,
          mdxExpanded: false,
        },
      ];
      if (mdx || queryModel) onEditInCanvas({ mdx, queryModel });
    };

    try {
      await askAiStream(req, {
        onModel: (m) => {
          model = m;
        },
        onIntent: (kind) => {
          if (kind === "INSIGHT") {
            // Open an empty insight turn now so the bubble appears before the first chunk lands;
            // onChunk below grows it in place.
            const id = nextId();
            reportTurnId = id;
            turns = [...turns, { id, role: "insight", text: "", insightMarkdown: "", model }];
          }
          // QUERY intent: nothing to render yet — the `step` envelope (onStep) hydrates the canvas
          // once the built query itself arrives.
        },
        onStep: (env) => {
          if (classifyChainEnvelope(env) === "query") {
            hydrateBuiltQuery(env);
          }
        },
        onChunk: (delta) => {
          if (reportTurnId == null) return;
          // Mutate by index, mirroring toggleMdx()'s idiom — Svelte 5's $state proxy surfaces
          // array-element field writes reactively without rebuilding the array.
          const idx = turns.findIndex((t) => t.id === reportTurnId);
          if (idx >= 0) turns[idx].insightMarkdown = (turns[idx].insightMarkdown ?? "") + delta;
        },
        onFinal: (env) => {
          const kind = classifyChainEnvelope(env);
          if (kind === "degraded") {
            turns = [
              ...turns,
              {
                id: nextId(),
                role: "error",
                text: env.reason ?? i18n.t("workspace.aiQuery.unknownError"),
                model,
              },
            ];
          } else if (kind === "report" && env.insight) {
            aiInsight.set(env.insight.markdown);
            if (reportTurnId != null) {
              const idx = turns.findIndex((t) => t.id === reportTurnId);
              if (idx >= 0) {
                turns[idx].insightMarkdown = env.insight.markdown;
                turns[idx].text = env.insight.headline ?? "";
                turns[idx].model = model;
              }
            } else {
              // No INSIGHT intent event preceded this — degrade gracefully by pushing the
              // completed turn directly rather than assuming onIntent always fires first.
              turns = [
                ...turns,
                {
                  id: nextId(),
                  role: "insight",
                  text: env.insight.headline ?? "",
                  insightMarkdown: env.insight.markdown,
                  model,
                },
              ];
            }
          } else if (kind === "emailDraft" && env.emailDraft) {
            aiInsight.set(env.emailDraft.summary);
            emailComposer.requestOpen();
            turns = [
              ...turns,
              {
                id: nextId(),
                role: "assistant",
                text: i18n.t("workspace.aiQuery.emailDraftOpening", "Opening a draft email for you to review…"),
                model,
              },
            ];
          } else if (kind === "viewChange" && env.viewChange) {
            onApplyViewChange(env.viewChange);
            const target =
              env.viewChange.viewMode === "chart" ? `chart (${env.viewChange.chartType ?? "?"})` : "grid";
            turns = [
              ...turns,
              {
                id: nextId(),
                role: "viewChange",
                text: env.viewChange.reason ?? `Switched view to ${target}.`,
                viewChange: env.viewChange,
                model,
              },
            ];
          } else if (kind === "query") {
            // Step-limit terminal: the chain ran out of steps right after building a query, with
            // no report to follow. Hydrate it exactly like an intermediate step.
            hydrateBuiltQuery(env);
          } else {
            turns = [
              ...turns,
              { id: nextId(), role: "error", text: i18n.t("workspace.aiQuery.unknownError"), model },
            ];
          }
        },
        onNote: (reason) => {
          turns = [...turns, { id: nextId(), role: "assistant", text: reason, model }];
        },
        // `error` is an early signal that always precedes a terminal degraded `final` for the
        // same failure (by design — see streamChainAsSse) — onFinal's degraded branch renders
        // the one error turn from env.reason. Pushing here too would double the red bubble.
        onError: () => {},
      });
    } catch (e) {
      const message = e instanceof AiAskTransportError ? e.message : (e as Error).message;
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.transportError").replace("{message}", message),
          model,
        },
      ];
    } finally {
      inflight = false;
    }
  }

  function clearConversation(): void {
    turns = [];
    notConfiguredBanner = null;
  }

  function copyMdx(mdx: string): void {
    if (typeof navigator !== "undefined" && navigator.clipboard) {
      void navigator.clipboard.writeText(mdx);
    }
  }

  function toggleMdx(turn: ChatTurn): void {
    // Mutate by index to keep reactivity — Svelte 5's $state proxies
    // surface array-element field changes directly.
    const idx = turns.findIndex((t) => t.id === turn.id);
    if (idx >= 0) turns[idx].mdxExpanded = !turns[idx].mdxExpanded;
  }

  function handleKeydown(e: KeyboardEvent): void {
    // Cmd/Ctrl + Enter submits; plain Enter inserts newline.
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      void (buildAndReport ? submitChained() : submit());
    }
  }

  /** Per-turn "Edit in canvas" replay. Prefers the turn's queryModel so the
   *  user lands in the interactive chip builder, not on opaque MDX. */
  function editInCanvas(turn: ChatTurn): void {
    const mdx = turn.mdx ?? "";
    if (!mdx && !turn.queryModel) return;
    onEditInCanvas({ mdx, queryModel: turn.queryModel });
  }
</script>

{#if open}
  <button
    type="button"
    class="fixed inset-0 bg-transparent border-0 p-0 z-[90] cursor-default"
    aria-label={i18n.t("workspace.aiQuery.close")}
    onclick={onClose}
  ></button>
{/if}

<aside
  class="ai-drawer"
  class:ai-drawer--open={open}
  class:ai-drawer--resizing={resizing}
  aria-labelledby="ai-drawer-title"
  inert={!open}
  style="width: {drawerWidth}px;"
>
  <!-- Resize handle on the left edge — drag horizontally to widen / narrow.
       Width persists to localStorage. Tooltip + role for discoverability;
       keyboard users still get the default DRAWER_DEFAULT_PX width. -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <div
    class="ai-drawer__resize"
    role="separator"
    aria-orientation="vertical"
    aria-label="Resize AI Ask drawer"
    title="Drag to resize"
    onmousedown={startResize}
  ></div>
  <header class="ai-drawer__header">
    <Sparkles size={18} aria-hidden="true" />
    <h2 id="ai-drawer-title" class="ai-drawer__title">{i18n.t("workspace.aiQuery.title")}</h2>
    <div class="flex-1"></div>
    {#if turns.length > 0}
      <button
        type="button"
        class="ai-drawer__icon-btn"
        title={i18n.t("workspace.aiQuery.clear")}
        aria-label={i18n.t("workspace.aiQuery.clear")}
        onclick={clearConversation}
      >
        <Trash2 size={16} />
      </button>
    {/if}
    <button
      type="button"
      class="ai-drawer__icon-btn"
      title={i18n.t("workspace.aiQuery.close")}
      aria-label={i18n.t("workspace.aiQuery.close")}
      onclick={onClose}
    >
      <X size={18} />
    </button>
  </header>

  {#if notConfiguredBanner}
    <div class="ai-drawer__banner" role="alert">
      <strong>{i18n.t("workspace.aiQuery.notConfiguredHeading")}</strong>
      <p>{notConfiguredBanner}</p>
    </div>
  {/if}

  <div class="flex-1 overflow-y-auto p-3.5 flex flex-col gap-2.5" bind:this={scrollEl}>
    {#if turns.length === 0}
      <div class="ai-drawer__empty">
        <p>{i18n.t("workspace.aiQuery.empty")}</p>
        {#if selection.cube}
          <p class="ai-drawer__empty-cube">
            {i18n.t("workspace.aiQuery.activeCube").replace("{cube}", selection.cube.caption ?? selection.cube.name)}
          </p>
          <div class="ai-drawer__examples">
            <div class="ai-drawer__examples-label">{i18n.t("workspace.aiQuery.tryAsking")}</div>
            {#each [i18n.t("workspace.aiQuery.example1"), i18n.t("workspace.aiQuery.example2"), i18n.t("workspace.aiQuery.example3")] as example}
              <button
                type="button"
                class="ai-drawer__example"
                onclick={() => {
                  prompt = example;
                  inputEl?.focus();
                }}
              >{example}</button>
            {/each}
          </div>
        {:else}
          <p class="ai-drawer__empty-cube ai-drawer__empty-cube--warn">
            {i18n.t("workspace.aiQuery.noCube")}
          </p>
        {/if}
      </div>
    {/if}

    {#each turns as turn (turn.id)}
      {#if turn.role === "user"}
        <div class="ai-drawer__turn justify-end">
          <div class="ai-drawer__bubble ai-drawer__bubble--user">{turn.text}</div>
        </div>
      {:else if turn.role === "assistant"}
        <div class="ai-drawer__turn ai-drawer__turn--assistant">
          <div class="ai-drawer__bubble bg-bg-muted border border-border">
            <div class="font-medium">{turn.text}</div>
            {#if turn.available && turn.available.length}
              <div class="ai-drawer__candidates">
                <div class="ai-drawer__candidates-label">
                  {#if turn.field}
                    {i18n.t("workspace.aiQuery.didYouMean").replace("{field}", turn.field)}
                  {:else}
                    {i18n.t("workspace.aiQuery.didYouMeanGeneric")}
                  {/if}
                </div>
                <div class="flex flex-wrap gap-1">
                  {#each turn.available as cand}
                    <button
                      type="button"
                      class="ai-drawer__chip"
                      onclick={() => {
                        prompt = prompt.trim().length === 0 ? cand : `${prompt} ${cand}`;
                        inputEl?.focus();
                      }}
                    >{cand}</button>
                  {/each}
                </div>
              </div>
            {/if}
            {#if turn.mdx}
              <div class="ai-drawer__mdx">
                <button
                  type="button"
                  class="ai-drawer__mdx-toggle"
                  onclick={() => toggleMdx(turn)}
                  aria-expanded={turn.mdxExpanded ? "true" : "false"}
                >
                  {#if turn.mdxExpanded}
                    <ChevronDown size={14} aria-hidden="true" />
                  {:else}
                    <ChevronRight size={14} aria-hidden="true" />
                  {/if}
                  <span>{i18n.t("workspace.aiQuery.mdx")}</span>
                </button>
                {#if turn.mdxExpanded}
                  <pre class="ai-drawer__mdx-pre"><code>{turn.mdx}</code></pre>
                  <div class="flex gap-1.5 mt-1.5">
                    <button
                      type="button"
                      class="ai-drawer__small-btn"
                      onclick={() => copyMdx(turn.mdx ?? "")}
                      title={i18n.t("workspace.aiQuery.copyMdx")}
                    >
                      <Copy size={12} aria-hidden="true" />
                      <span>{i18n.t("workspace.aiQuery.copy")}</span>
                    </button>
                    <button
                      type="button"
                      class="ai-drawer__small-btn ai-drawer__small-btn--primary"
                      onclick={() => editInCanvas(turn)}
                    >{i18n.t("workspace.aiQuery.editInCanvas")}</button>
                  </div>
                {/if}
              </div>
            {/if}
            {#if turn.model}
              <div class="ai-drawer__model">{i18n.t("workspace.aiQuery.viaModel").replace("{model}", turn.model)}</div>
            {/if}
          </div>
        </div>
      {:else if turn.role === "insight"}
        <div class="ai-drawer__turn ai-drawer__turn--assistant">
          <div class="ai-drawer__bubble bg-bg-muted border border-border">
            <div class="ai-drawer__insight-badge">
              <Sparkles size={12} aria-hidden="true" />
              <span>{i18n.t("workspace.aiQuery.insightLabel")}</span>
            </div>
            {#if turn.text}
              <div class="font-medium mb-1">{turn.text}</div>
            {/if}
            <!-- eslint-disable-next-line svelte/no-at-html-tags — renderer escapes untrusted text before emitting any tags (tinyMarkdown.ts) -->
            <div class="ai-drawer__insight-body">{@html renderTinyMarkdown(turn.insightMarkdown ?? "")}</div>
            {#if turn.model}
              <div class="ai-drawer__model">{i18n.t("workspace.aiQuery.viaModel").replace("{model}", turn.model)}</div>
            {/if}
          </div>
        </div>
      {:else if turn.role === "viewChange"}
        <div class="ai-drawer__turn ai-drawer__turn--assistant">
          <div class="ai-drawer__bubble bg-bg-muted border border-border">
            <div class="ai-drawer__insight-badge">
              <Sparkles size={12} aria-hidden="true" />
              <span>{i18n.t("workspace.aiQuery.viewChangeLabel")}</span>
            </div>
            <div class="font-medium">{turn.text}</div>
            {#if turn.viewChange}
              <div class="text-fg-subtle text-xs mt-1">
                {turn.viewChange.viewMode}{turn.viewChange.chartType ? ` · ${turn.viewChange.chartType}` : ""}
              </div>
            {/if}
            {#if turn.model}
              <div class="ai-drawer__model">{i18n.t("workspace.aiQuery.viaModel").replace("{model}", turn.model)}</div>
            {/if}
          </div>
        </div>
      {:else}
        <div class="ai-drawer__turn justify-start">
          <div class="ai-drawer__bubble ai-drawer__bubble--error">{turn.text}</div>
        </div>
      {/if}
    {/each}

    {#if inflight}
      <div class="ai-drawer__turn ai-drawer__turn--assistant">
        <div class="ai-drawer__bubble bg-bg-muted border border-border inline-flex items-center gap-2">
          <span class="ai-drawer__dot"></span>
          <span class="ai-drawer__dot"></span>
          <span class="ai-drawer__dot"></span>
          <!-- Elapsed-time hint. LLM round-trips can take 5-15s with the full 4-tool prompt +
               cellset digest; without this the dots look frozen. -->
          {#if inflightElapsedMs > 1500}
            <span class="ai-drawer__elapsed">{(inflightElapsedMs / 1000).toFixed(1)}s</span>
          {/if}
        </div>
      </div>
    {/if}
  </div>

  <footer class="border-t border-border py-2.5 px-3.5 flex flex-col gap-2 bg-bg shrink-0">
    <!-- Mode picker — Auto leaves the LLM to route; the explicit options narrow the tool list to
         one intent (plus refusal). Disabled while a request is in flight so the user can't change
         intent mid-roundtrip. -->
    <div class="ai-drawer__mode-bar" role="radiogroup" aria-label={i18n.t("workspace.aiQuery.modeLabel")}>
      {#each [{id:"auto",label:i18n.t("workspace.aiQuery.modeAuto")},{id:"query",label:i18n.t("workspace.aiQuery.modeQuery")},{id:"insight",label:i18n.t("workspace.aiQuery.modeInsight")},{id:"view_change",label:i18n.t("workspace.aiQuery.modeView")}] as opt (opt.id)}
        <button
          type="button"
          role="radio"
          aria-checked={forceTool === opt.id}
          class={"ai-drawer__mode-btn " + (forceTool === opt.id ? "ai-drawer__mode-btn--active" : "")}
          disabled={inflight}
          onclick={() => (forceTool = opt.id as ForceTool)}
          title={i18n.t(`workspace.aiQuery.modeTip.${opt.id}`)}
        >{opt.label}</button>
      {/each}
    </div>
    <!-- Opt-in chained ("Build & report") toggle — default OFF. When on, send routes to
         submitChained() (streamed multi-step ask) instead of submit()'s single-turn askAi. -->
    <label class="ai-drawer__chain-toggle" title={i18n.t("workspace.aiQuery.buildAndReportHint")}>
      <input type="checkbox" bind:checked={buildAndReport} disabled={inflight} />
      <span>{i18n.t("workspace.aiQuery.buildAndReport")}</span>
    </label>
    <div class="flex gap-2 items-end">
    <textarea
      bind:this={inputEl}
      class="ai-drawer__input"
      placeholder={i18n.t("workspace.aiQuery.placeholder")}
      bind:value={prompt}
      rows="3"
      disabled={inflight}
      onkeydown={handleKeydown}
    ></textarea>
    <button
      type="button"
      class="ai-drawer__submit"
      disabled={inflight || prompt.trim().length === 0}
      onclick={() => void (buildAndReport ? submitChained() : submit())}
      title={i18n.t("workspace.aiQuery.send")}
      aria-label={i18n.t("workspace.aiQuery.send")}
    >
      <Send size={16} aria-hidden="true" />
      <span>{i18n.t("workspace.aiQuery.send")}</span>
    </button>
    </div>
  </footer>
</aside>

<style>
.ai-drawer {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    /* width is set inline on the element from the resizable state. max-width
       caps at the viewport so a misclick dragging past the left edge can't
       eat the canvas entirely. */
    max-width: 100vw;
    background: var(--bg);
    border-left: 1px solid var(--border);
    box-shadow: -8px 0 24px rgba(0, 0, 0, 0.08);
    display: flex;
    flex-direction: column;
    transform: translateX(100%);
    transition: transform 0.18s ease-out;
    z-index: 100;
    color: var(--fg);
  }
  /* Suspend the slide-in transition while resizing so the width change
     follows the cursor 1:1 instead of easing per-frame. */
  .ai-drawer--resizing {
    transition: none !important;
  }
  .ai-drawer--open {
    transform: translateX(0);
  }
  /* Resize handle — 6px-wide strip on the left edge of the drawer. Hover
     highlight + ew-resize cursor signal the affordance. Sits above all
     drawer content via z-index so clicks always hit it before the body. */
  .ai-drawer__resize {
    position: absolute;
    top: 0;
    left: -3px;
    width: 6px;
    height: 100%;
    cursor: ew-resize;
    background: transparent;
    transition: background 120ms ease;
    z-index: 110;
  }
  .ai-drawer__resize:hover,
  .ai-drawer--resizing .ai-drawer__resize {
    background: color-mix(in srgb, var(--accent) 40%, transparent);
  }
  .ai-drawer__header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 14px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    /* Pin header + footer against any squeeze when `messages` swells —
       previously a tall conversation could nudge the footer below the
       viewport on short windows. */
    flex-shrink: 0;
  }
  .ai-drawer__title {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 600;
  }
  .ai-drawer__icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: 1px solid transparent;
    color: var(--fg-muted);
    border-radius: 6px;
    padding: 5px;
    cursor: pointer;
  }
  .ai-drawer__icon-btn:hover {
    background: var(--bg);
    border-color: var(--border);
    color: var(--fg);
  }
  .ai-drawer__banner {
    margin: 12px 14px 0;
    padding: 10px 12px;
    background: rgba(245, 158, 11, 0.12);
    border: 1px solid rgba(245, 158, 11, 0.35);
    border-radius: 6px;
    font-size: 0.85rem;
  }
  .ai-drawer__banner strong {
    display: block;
    margin-bottom: 4px;
  }
  .ai-drawer__banner p {
    margin: 0;
    color: var(--fg-muted);
  }
  .ai-drawer__empty {
    color: var(--fg-muted);
    font-size: 0.9rem;
    line-height: 1.5;
  }
  .ai-drawer__empty p {
    margin: 0 0 8px;
  }
  .ai-drawer__empty-cube {
    font-size: 0.8rem;
    color: var(--fg-muted);
  }
  .ai-drawer__empty-cube--warn {
    color: #b45309;
  }
  .ai-drawer__examples {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .ai-drawer__examples-label {
    font-size: 0.78rem;
    color: var(--fg-muted);
    margin-bottom: 2px;
  }
  .ai-drawer__example {
    text-align: left;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 10px;
    font-size: 0.85rem;
    color: var(--fg);
    cursor: pointer;
    font-family: inherit;
  }
  .ai-drawer__example:hover {
    border-color: var(--accent);
    background: var(--bg-muted);
  }
  .ai-drawer__turn {
    display: flex;
  }
  .ai-drawer__turn--assistant,
  .ai-drawer__bubble {
    padding: 9px 12px;
    border-radius: 10px;
    max-width: 90%;
    font-size: 0.9rem;
    line-height: 1.4;
    white-space: pre-wrap;
    /* `overflow-wrap: anywhere` (vs the older `word-wrap: break-word`)
       breaks within long unbroken strings like MDX identifiers / URLs
       too, so a user bubble or candidate chip can never poke past the
       drawer's right edge. */
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .ai-drawer__bubble--user {
    background: var(--accent);
    color: white;
  }
  .ai-drawer__bubble--error {
    background: rgba(239, 68, 68, 0.1);
    border: 1px solid rgba(239, 68, 68, 0.35);
    color: #991b1b;
  }
  .ai-drawer__candidates {
    margin-top: 8px;
  }
  .ai-drawer__candidates-label {
    font-size: 0.8rem;
    color: var(--fg-muted);
    margin-bottom: 4px;
  }
  .ai-drawer__chip {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 3px 9px;
    font-size: 0.78rem;
    color: var(--fg);
    cursor: pointer;
  }
  .ai-drawer__chip:hover {
    background: var(--bg-muted);
    border-color: var(--accent);
  }
  .ai-drawer__mdx {
    margin-top: 10px;
    border-top: 1px solid var(--border);
    padding-top: 8px;
  }
  .ai-drawer__mdx-toggle {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: transparent;
    border: 0;
    color: var(--fg-muted);
    font-size: 0.78rem;
    cursor: pointer;
    padding: 2px 4px;
  }
  .ai-drawer__mdx-toggle:hover {
    color: var(--fg);
  }
  .ai-drawer__mdx-pre {
    margin: 6px 0;
    padding: 8px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    font-size: 0.75rem;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .ai-drawer__small-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--fg);
    border-radius: 6px;
    padding: 4px 9px;
    font-size: 0.78rem;
    cursor: pointer;
  }
  .ai-drawer__small-btn:hover {
    background: var(--bg-muted);
  }
  .ai-drawer__small-btn--primary {
    background: var(--accent);
    border-color: var(--accent);
    color: white;
  }
  .ai-drawer__small-btn--primary:hover {
    filter: brightness(0.95);
    background: var(--accent);
  }
  .ai-drawer__model {
    margin-top: 6px;
    font-size: 0.72rem;
    color: var(--fg-muted);
    font-style: italic;
  }
  /* Insight / view-change badges sit above the bubble title so the user can
     scan at a glance which tool the AI picked (vs a normal query result). */
  .ai-drawer__insight-badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 0.68rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--accent);
    margin-bottom: 4px;
    font-weight: 600;
  }
  /* Insight body — markdown rendered as preformatted text so paragraphs,
     bullets, and the model's spacing survive. white-space:pre-wrap respects
     LLM newlines without us pulling in a markdown lib for the drawer. */
  /* Insight body — rendered HTML from renderTinyMarkdown(). We style the
     tags we actually emit (h3-h6, p, ul, li, strong, em, code, a) and let
     everything else inherit from the bubble. */
  .ai-drawer__insight-body {
    font-size: 0.85rem;
    line-height: 1.5;
    color: var(--fg);
  }
  .ai-drawer__insight-body :global(h3),
  .ai-drawer__insight-body :global(h4),
  .ai-drawer__insight-body :global(h5),
  .ai-drawer__insight-body :global(h6) {
    font-weight: 600;
    margin: 12px 0 4px;
    color: var(--fg);
    line-height: 1.3;
  }
  .ai-drawer__insight-body :global(h3) { font-size: 0.95rem; }
  .ai-drawer__insight-body :global(h4) { font-size: 0.9rem; }
  .ai-drawer__insight-body :global(h5),
  .ai-drawer__insight-body :global(h6) { font-size: 0.85rem; color: var(--fg-muted); }
  .ai-drawer__insight-body :global(h3:first-child),
  .ai-drawer__insight-body :global(h4:first-child) { margin-top: 0; }
  .ai-drawer__insight-body :global(p) {
    margin: 0 0 8px;
  }
  .ai-drawer__insight-body :global(p:last-child) { margin-bottom: 0; }
  .ai-drawer__insight-body :global(ul) {
    margin: 0 0 8px;
    padding-left: 18px;
  }
  .ai-drawer__insight-body :global(li) {
    margin: 2px 0;
  }
  .ai-drawer__insight-body :global(strong) {
    font-weight: 600;
    color: var(--fg);
  }
  .ai-drawer__insight-body :global(code) {
    font-family: ui-monospace, SFMono-Regular, monospace;
    font-size: 0.8rem;
    background: var(--bg-subtle);
    padding: 1px 4px;
    border-radius: 3px;
  }
  .ai-drawer__insight-body :global(a) {
    color: var(--accent);
    text-decoration: underline;
  }
  /* Mode picker — segmented control above the textarea. Pill row, active gets
     the accent ring + bolder weight so it reads as a radio without losing the
     tight footer height. */
  .ai-drawer__mode-bar {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }
  .ai-drawer__mode-btn {
    flex: 1 1 auto;
    min-width: 0;
    background: transparent;
    color: var(--fg-muted);
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 3px 10px;
    font-size: 0.72rem;
    cursor: pointer;
    transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
  }
  .ai-drawer__mode-btn:hover:not(:disabled) {
    background: var(--bg-subtle);
    color: var(--fg);
  }
  .ai-drawer__mode-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .ai-drawer__mode-btn--active {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    color: var(--accent);
    border-color: var(--accent);
    font-weight: 600;
  }
  /* "Build & report" opt-in toggle — sits between the mode bar and the input row. Plain
     checkbox + label rather than a bespoke switch component; matches the mode bar's font size
     and muted-until-active color so it reads as a secondary control. */
  .ai-drawer__chain-toggle {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    width: fit-content;
    font-size: 0.78rem;
    color: var(--fg-muted);
    cursor: pointer;
  }
  .ai-drawer__chain-toggle input {
    accent-color: var(--accent);
    cursor: pointer;
  }
  .ai-drawer__chain-toggle:has(input:checked) {
    color: var(--fg);
    font-weight: 600;
  }
  .ai-drawer__chain-toggle:has(input:disabled) {
    opacity: 0.5;
    cursor: not-allowed;
  }
  /* Elapsed-time label inside the in-flight bubble — appears after 1.5s so
     short successful turns don't flash it. */
  .ai-drawer__elapsed {
    font-size: 0.72rem;
    color: var(--fg-muted);
    margin-left: 6px;
  }
  .ai-drawer__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--fg-muted);
    animation: ai-drawer-pulse 1.2s ease-in-out infinite;
  }
  .ai-drawer__dot:nth-child(2) {
    animation-delay: 0.15s;
  }
  .ai-drawer__dot:nth-child(3) {
    animation-delay: 0.3s;
  }
  @keyframes ai-drawer-pulse {
    0%,
    100% {
      opacity: 0.25;
      transform: scale(0.9);
    }
    50% {
      opacity: 1;
      transform: scale(1.05);
    }
  }
  .ai-drawer__input {
    flex: 1;
    resize: vertical;
    /* The empty state previously gave the impression there was no input
       at all — bumped min-height so the textarea has obvious presence at
       the footer even when collapsed. */
    min-height: 72px;
    max-height: 200px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--fg);
    font-family: inherit;
    font-size: 0.9rem;
  }
  .ai-drawer__input:focus {
    outline: 2px solid var(--accent);
    outline-offset: -1px;
    border-color: var(--accent);
  }
  .ai-drawer__submit {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--accent);
    border: 1px solid var(--accent);
    color: white;
    border-radius: 6px;
    padding: 0 14px;
    height: 38px;
    font-size: 0.85rem;
    cursor: pointer;
    white-space: nowrap;
  }
  .ai-drawer__submit:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
</style>
