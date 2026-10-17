-- One resume per account. Only extracted text is retained; the source file is discarded.
CREATE TABLE app_user_resume (
    user_id UUID PRIMARY KEY REFERENCES app_user(user_id) ON DELETE CASCADE,
    revision UUID NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    file_type VARCHAR(100) NOT NULL,
    file_size INTEGER NOT NULL CHECK (file_size BETWEEN 1 AND 5242880),
    extracted_text TEXT NOT NULL CHECK (CHAR_LENGTH(extracted_text) BETWEEN 40 AND 100000),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- PostgreSQL security: accessed through the authenticated Java API, never the public Data API.
ALTER TABLE app_user_resume ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_user_resume FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON app_user_resume FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON app_user_resume FROM authenticated;
    END IF;
END
$$;
