ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE app_account_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    purpose VARCHAR(16) NOT NULL CHECK(purpose IN ('verification','reset')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_account_token_user ON app_account_token(user_id,purpose);
-- PostgreSQL security:
ALTER TABLE app_account_token ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_account_token FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN REVOKE ALL ON app_account_token FROM anon; END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN REVOKE ALL ON app_account_token FROM authenticated; END IF;
END $$;
