/*
 * Reactive client store for the Saiku schema-generator workflow.
 *
 * Wraps a `SchemaGenClient` (see $lib/api/schemaGen) with Svelte 5 runes:
 *   - `$state` fields hold session, stage, draft, suggestions, pendingOps.
 *   - `start()` kicks off a session, primes initial draft/suggestions, and
 *     polls `status` on an interval. Polling re-fetches draft + suggestions
 *     only when the stage changes, and stops as soon as a terminal stage
 *     (`READY`, `SAVED`, `FAILED`) is reported.
 *   - `applyOp()` is optimistic: the op is removed from `suggestions.ops` and
 *     pushed onto `pendingOps` synchronously, then reconciled against the
 *     server-returned draft. On failure the op is rolled back into
 *     `suggestions.ops` and `error` is set.
 *   - `rejectOp()` is UI-local (no server call); the backend op-log only
 *     records accepted ops.
 *   - `stop()` cancels polling and resets all reactive state.
 *
 * The store is exposed as a plain object of getters so callers (Svelte
 * components) can read `store.draft` etc. and always observe the latest
 * reactive values.
 */

import type {
  DraftView,
  SchemaGenClient,
  Stage,
  SuggestionOp,
  SuggestionView,
} from "$lib/api/schemaGen";

const TERMINAL: readonly Stage[] = ["READY", "SAVED", "FAILED"] as const;

function isTerminal(stage: Stage): boolean {
  return TERMINAL.includes(stage);
}

export interface SchemaGenStore {
  readonly sessionId: string | null;
  readonly stage: Stage;
  readonly draft: DraftView | null;
  readonly suggestions: SuggestionView | null;
  readonly failureMessage: string | null;
  readonly pendingOps: SuggestionOp[];
  readonly error: string | null;
  /** Paths newly introduced upstream since the baseline sidecar. `0` on first-run. */
  readonly deltaNewCount: number;
  /** Paths present in baseline but absent from the fresh introspection. `0` on first-run. */
  readonly deltaRemovedCount: number;

  start(dataSourceId: string): Promise<void>;
  applyOp(op: SuggestionOp): Promise<void>;
  rejectOp(op: SuggestionOp): void;
  save(schemaName?: string): Promise<void>;
  stop(): void;
}

export function createSchemaGenStore(
  client: SchemaGenClient,
  pollMs = 500,
): SchemaGenStore {
  // Reactive state. `$state` on class fields makes direct mutation reactive;
  // we expose getters below so consumers always read the latest value.
  class State {
    sessionId = $state<string | null>(null);
    stage = $state<Stage>("PENDING");
    draft = $state<DraftView | null>(null);
    suggestions = $state<SuggestionView | null>(null);
    failureMessage = $state<string | null>(null);
    pendingOps = $state<SuggestionOp[]>([]);
    error = $state<string | null>(null);
    deltaNewCount = $state<number>(0);
    deltaRemovedCount = $state<number>(0);
  }
  const s = new State();

  let timer: ReturnType<typeof setInterval> | null = null;

  function clearTimer(): void {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
  }

  async function refreshViews(sessionId: string): Promise<void> {
    try {
      const [d, sug] = await Promise.all([
        client.draft(sessionId),
        client.suggestions(sessionId),
      ]);
      s.draft = d;
      s.suggestions = sug;
    } catch (err) {
      s.error = errorMessage(err);
    }
  }

  async function poll(): Promise<void> {
    const sessionId = s.sessionId;
    if (sessionId === null) return;
    try {
      const status = await client.status(sessionId);
      const previous = s.stage;
      s.stage = status.stage;
      s.failureMessage = status.failureMessage ?? null;
      s.deltaNewCount = status.deltaNewCount ?? 0;
      s.deltaRemovedCount = status.deltaRemovedCount ?? 0;
      if (status.stage !== previous) {
        await refreshViews(sessionId);
      }
      if (isTerminal(status.stage)) {
        clearTimer();
      }
    } catch (err) {
      s.error = errorMessage(err);
    }
  }

  async function start(dataSourceId: string): Promise<void> {
    clearTimer();
    s.error = null;
    s.pendingOps = [];
    try {
      const resp = await client.start(dataSourceId);
      s.sessionId = resp.sessionId;
      s.stage = resp.stage;
    } catch (err) {
      s.error = errorMessage(err);
      return;
    }
    // Prime status + views immediately so the UI has data on first paint.
    const sessionId = s.sessionId!;
    try {
      const status = await client.status(sessionId);
      s.stage = status.stage;
      s.failureMessage = status.failureMessage ?? null;
      s.deltaNewCount = status.deltaNewCount ?? 0;
      s.deltaRemovedCount = status.deltaRemovedCount ?? 0;
    } catch (err) {
      s.error = errorMessage(err);
    }
    await refreshViews(sessionId);
    if (!isTerminal(s.stage)) {
      timer = setInterval(() => {
        void poll();
      }, pollMs);
    }
  }

  async function applyOp(op: SuggestionOp): Promise<void> {
    const sessionId = s.sessionId;
    if (sessionId === null) {
      s.error = "no active schema-generator session";
      return;
    }
    // Optimistic update: splice the op out of suggestions, onto pendingOps.
    const prior = s.suggestions;
    if (prior !== null) {
      s.suggestions = {
        ...prior,
        ops: prior.ops.filter((o) => o !== op),
      };
    }
    s.pendingOps = [...s.pendingOps, op];
    try {
      const next = await client.applyOp(sessionId, op);
      s.draft = next;
      s.pendingOps = s.pendingOps.filter((o) => o !== op);
    } catch (err) {
      // Roll back: put the op back into suggestions (at its original spot if
      // we still have the prior view), drop from pendingOps, record error.
      if (prior !== null) {
        s.suggestions = prior;
      }
      s.pendingOps = s.pendingOps.filter((o) => o !== op);
      s.error = errorMessage(err);
    }
  }

  function rejectOp(op: SuggestionOp): void {
    const prior = s.suggestions;
    if (prior === null) return;
    s.suggestions = {
      ...prior,
      ops: prior.ops.filter((o) => o !== op),
    };
  }

  async function save(schemaName?: string): Promise<void> {
    const sessionId = s.sessionId;
    if (sessionId === null) {
      s.error = "no active schema-generator session";
      return;
    }
    try {
      await client.save(sessionId, schemaName);
      s.stage = "SAVED";
      clearTimer();
    } catch (err) {
      s.error = errorMessage(err);
    }
  }

  function stop(): void {
    clearTimer();
    s.sessionId = null;
    s.stage = "PENDING";
    s.draft = null;
    s.suggestions = null;
    s.failureMessage = null;
    s.pendingOps = [];
    s.error = null;
    s.deltaNewCount = 0;
    s.deltaRemovedCount = 0;
  }

  return {
    get sessionId() {
      return s.sessionId;
    },
    get stage() {
      return s.stage;
    },
    get draft() {
      return s.draft;
    },
    get suggestions() {
      return s.suggestions;
    },
    get failureMessage() {
      return s.failureMessage;
    },
    get pendingOps() {
      return s.pendingOps;
    },
    get error() {
      return s.error;
    },
    get deltaNewCount() {
      return s.deltaNewCount;
    },
    get deltaRemovedCount() {
      return s.deltaRemovedCount;
    },
    start,
    applyOp,
    rejectOp,
    save,
    stop,
  };
}
