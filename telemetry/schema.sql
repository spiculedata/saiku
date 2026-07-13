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
