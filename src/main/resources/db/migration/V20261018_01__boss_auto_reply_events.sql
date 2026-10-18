-- Additive, idempotent SQLite migration. Rollback: revert application code and retain
-- these independent tables for audit; no existing table/data needs removal.
CREATE TABLE IF NOT EXISTS boss_auto_reply_event (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    session_key TEXT NOT NULL,
    session_title TEXT NOT NULL,
    message_id TEXT,
    hr_message TEXT,
    reply TEXT,
    status TEXT NOT NULL,
    reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_boss_reply_event_time_status
    ON boss_auto_reply_event(occurred_at, status);

-- Only messages captured from the unread filter may enter this retry queue.
CREATE TABLE IF NOT EXISTS boss_auto_reply_pending (
    session_key TEXT PRIMARY KEY,
    session_title TEXT NOT NULL,
    hr_message TEXT NOT NULL,
    message_id TEXT NOT NULL,
    context TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    updated_at TEXT NOT NULL
);
