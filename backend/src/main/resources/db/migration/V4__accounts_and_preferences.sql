-- Personal data is independent of the analytical star schema.
CREATE TABLE app_user (
    user_id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user_preferences (
    user_id UUID PRIMARY KEY REFERENCES app_user(user_id) ON DELETE CASCADE,
    target_role VARCHAR(120) NOT NULL DEFAULT '',
    location VARCHAR(120) NOT NULL DEFAULT '',
    work_model VARCHAR(20) NOT NULL DEFAULT 'any',
    seniority VARCHAR(20) NOT NULL DEFAULT 'any',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_preferences_work_model CHECK (work_model IN ('any', 'remote', 'hybrid', 'onsite')),
    CONSTRAINT chk_preferences_seniority CHECK (seniority IN ('any', 'internship', 'junior', 'mid', 'senior', 'specialist'))
);
