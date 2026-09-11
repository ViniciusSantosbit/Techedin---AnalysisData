-- ============================================================
-- V1__initial_star_schema.sql
-- Techedin - Star Schema Inicial (PostgreSQL)
-- ============================================================

-- ============================================================
-- Tabela Fato
-- ============================================================
CREATE TABLE IF NOT EXISTS fact_job_technology (
    job_id         BIGINT      NOT NULL,
    technology_id  BIGINT      NOT NULL,
    date_id        BIGINT      NOT NULL,
    seniority_id   BIGINT      NOT NULL,
    mention_count  INTEGER     NOT NULL DEFAULT 1,

    CONSTRAINT pk_fact_job_technology PRIMARY KEY (job_id, technology_id, date_id, seniority_id),
    CONSTRAINT fk_fact_job_technology_dim_job        FOREIGN KEY (job_id)        REFERENCES dim_job (job_id)        ON DELETE CASCADE,
    CONSTRAINT fk_fact_job_technology_dim_technology FOREIGN KEY (technology_id) REFERENCES dim_technology (technology_id) ON DELETE CASCADE,
    CONSTRAINT fk_fact_job_technology_dim_date       FOREIGN KEY (date_id)        REFERENCES dim_date (date_id)       ON DELETE CASCADE,
    CONSTRAINT fk_fact_job_technology_dim_seniority  FOREIGN KEY (seniority_id)   REFERENCES dim_seniority (seniority_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_fact_job_technology_date       ON fact_job_technology (date_id);
CREATE INDEX IF NOT EXISTS idx_fact_job_technology_technology ON fact_job_technology (technology_id);
CREATE INDEX IF NOT EXISTS idx_fact_job_technology_seniority  ON fact_job_technology (seniority_id);

-- ============================================================
-- Dimensões
-- ============================================================

-- dim_job
CREATE TABLE IF NOT EXISTS dim_job (
    job_id      BIGSERIAL   PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    company     VARCHAR(255) NOT NULL,
    description TEXT,
    source      VARCHAR(100) NOT NULL,

    CONSTRAINT uq_dim_job_source_title_company UNIQUE (source, title, company)
);

CREATE INDEX IF NOT EXISTS idx_dim_job_source   ON dim_job (source);
CREATE INDEX IF NOT EXISTS idx_dim_job_company  ON dim_job (company);

-- dim_technology
CREATE TABLE IF NOT EXISTS dim_technology (
    technology_id BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    category      VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_dim_technology_category ON dim_technology (category);

-- dim_date
CREATE TABLE IF NOT EXISTS dim_date (
    date_id BIGSERIAL PRIMARY KEY,
    date    DATE    NOT NULL UNIQUE,
    day     INTEGER NOT NULL CHECK (day BETWEEN 1 AND 31),
    month   INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    year    INTEGER NOT NULL CHECK (year >= 2000)
);

CREATE INDEX IF NOT EXISTS idx_dim_date_year_month ON dim_date (year, month);

-- dim_seniority
CREATE TABLE IF NOT EXISTS dim_seniority (
    seniority_id BIGSERIAL PRIMARY KEY,
    level        VARCHAR(100) NOT NULL UNIQUE
);
