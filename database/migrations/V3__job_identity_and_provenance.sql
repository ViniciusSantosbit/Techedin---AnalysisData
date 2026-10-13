-- Legacy rows are retained but excluded from analytics until collected again.
ALTER TABLE dim_job DROP CONSTRAINT IF EXISTS uq_dim_job_source_title_company;
ALTER TABLE dim_job ADD COLUMN IF NOT EXISTS source_url TEXT;
ALTER TABLE dim_job ADD COLUMN IF NOT EXISTS published_date DATE;
ALTER TABLE dim_job ADD COLUMN IF NOT EXISTS first_seen DATE NOT NULL DEFAULT CURRENT_DATE;
ALTER TABLE dim_job ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dim_job ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE dim_job ADD CONSTRAINT uq_dim_job_source_url UNIQUE (source, source_url);
