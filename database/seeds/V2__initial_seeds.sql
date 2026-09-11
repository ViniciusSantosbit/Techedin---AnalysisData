-- ============================================================
-- V2__initial_seeds.sql
-- Techedin - Dados iniciais para massa de teste
-- ============================================================

-- ============================================================
-- 1. dim_seniority
-- ============================================================
INSERT INTO dim_seniority (level) VALUES
    ('Estágio'),
    ('Júnior'),
    ('Pleno'),
    ('Sênior'),
    ('Especialista');

-- ============================================================
-- 2. dim_technology
-- ============================================================
INSERT INTO dim_technology (name, category) VALUES
    ('Python', 'Linguagem'),
    ('Java', 'Linguagem'),
    ('JavaScript', 'Linguagem'),
    ('HTML', 'Web'),
    ('CSS', 'Web'),
    ('Spring Boot', 'Framework'),
    ('Node.js', 'Framework'),
    ('PostgreSQL', 'Banco de Dados'),
    ('SQL', 'Banco de Dados'),
    ('AWS', 'Infraestrutura'),
    ('Docker', 'Infraestrutura'),
    ('Git', 'Infraestrutura');

-- ============================================================
-- 3. dim_date (últimos 5 dias consecutivos do mês atual)
-- ============================================================
INSERT INTO dim_date (date, day, month, year) VALUES
    ('2026-09-07',  7,  9, 2026),
    ('2026-09-08',  8,  9, 2026),
    ('2026-09-09',  9,  9, 2026),
    ('2026-09-10', 10,  9, 2026),
    ('2026-09-11', 11,  9, 2026);
