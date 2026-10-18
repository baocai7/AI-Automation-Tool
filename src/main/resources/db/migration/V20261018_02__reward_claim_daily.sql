CREATE TABLE IF NOT EXISTS reward_claim_daily (
    platform TEXT NOT NULL,
    claim_date TEXT NOT NULL,
    confirmed_at TEXT NOT NULL,
    PRIMARY KEY (platform, claim_date)
);
