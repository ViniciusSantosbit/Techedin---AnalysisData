-- Discovery time is for ingestion, never a claim about the publisher's timestamp.
ALTER TABLE dim_job ADD COLUMN discovered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE TABLE app_push_subscription (
    subscription_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    endpoint_hash VARCHAR(64) NOT NULL UNIQUE,
    endpoint TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cursor_job_id BIGINT NOT NULL,
    pending_through BIGINT,
    attempts INTEGER NOT NULL DEFAULT 0,
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_sent_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_push_user ON app_push_subscription(user_id);
-- PostgreSQL security:
ALTER TABLE app_push_subscription ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_push_subscription FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON app_push_subscription FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON app_push_subscription FROM authenticated;
    END IF;
END $$;
