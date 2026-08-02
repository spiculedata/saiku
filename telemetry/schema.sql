-- Saiku telemetry — D1 (serverless SQLite) schema.
-- One row per install. No IP, no raw id (only sha256(id)), no user data.

CREATE TABLE IF NOT EXISTS installs (
  id_hash    TEXT PRIMARY KEY,   -- sha256(random install uuid)
  version    TEXT NOT NULL,
  edition    TEXT,
  os         TEXT,
  arch       TEXT,
  java       TEXT,
  first_seen INTEGER NOT NULL,   -- unix seconds, first heartbeat
  last_seen  INTEGER NOT NULL    -- unix seconds, most recent heartbeat
);

-- the count query filters on last_seen (active in the last 30 days)
CREATE INDEX IF NOT EXISTS idx_installs_last_seen ON installs (last_seen);

-- Saiku#1636 — DEMO-ONLY engagement events. Only the online demo (SAIKU_DEMO)
-- posts these; day-to-day self-hosted installs never do. No IP, no user id, no
-- query data — just a coarse "what did an anonymous demo visitor look at?".
-- `session_hash` is sha256 of a random per-browser id (anonymous, uncorrelatable).
CREATE TABLE IF NOT EXISTS demo_event (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  ts           INTEGER NOT NULL,   -- unix seconds, server receive time
  session_hash TEXT NOT NULL,      -- sha256(random per-browser session id)
  type         TEXT NOT NULL,      -- coarse category, e.g. 'app', 'cube-designer', 'ai'
  name         TEXT NOT NULL,      -- coarse action, e.g. 'open', 'tile-add', 'ask'
  detail       TEXT,               -- optional coarse qualifier (e.g. tile type)
  version      TEXT                -- demo build version, for cohorting
);

-- engagement reporting filters on ts (recent) and groups by type/name
CREATE INDEX IF NOT EXISTS idx_demo_event_ts ON demo_event (ts);
