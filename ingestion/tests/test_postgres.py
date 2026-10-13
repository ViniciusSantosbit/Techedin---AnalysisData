"""Real PostgreSQL checks in an isolated temporary schema; opt in with TEST_DB_DSN."""
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
import psycopg2
from psycopg2 import sql
from test_scraper import scraper


@unittest.skipUnless(os.getenv('TEST_DB_DSN'), 'TEST_DB_DSN não configurado')
class PostgresTests(unittest.TestCase):
    def setUp(self):
        self.dsn = os.environ['TEST_DB_DSN']
        self.schema = 'test_' + uuid.uuid4().hex
        conn = psycopg2.connect(self.dsn)
        try:
            with conn, conn.cursor() as cur:
                cur.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(self.schema)))
                cur.execute(sql.SQL('SET search_path TO {}').format(sql.Identifier(self.schema)))
                for migration in sorted((scraper.BASE_DIR / 'database/migrations').glob('V*.sql')):
                    cur.execute(migration.read_text(encoding='utf-8'))
        finally:
            conn.close()
        self.repo = scraper.PostgresJobRepository()

    def connect(self):
        return psycopg2.connect(self.dsn, options=f'-csearch_path={self.schema}')

    def tearDown(self):
        conn = psycopg2.connect(self.dsn)
        try:
            with conn, conn.cursor() as cur:
                cur.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(self.schema)))
        finally:
            conn.close()

    def scalar(self, query):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute(query)
                return cur.fetchone()[0]
        finally:
            conn.close()

    def test_reingestion_preserves_identity_and_replaces_facts(self):
        job = {**scraper.demo_jobs()[0], 'is_demo': False, 'technologies': ['Node.js', 'C#']}
        second = {**job, 'source_url': job['source_url'] + '?id=2'}
        with patch.object(self.repo, '_connect', side_effect=self.connect):
            self.assertEqual(self.repo.upsert_jobs_and_facts([job, second]), 4)
            job['technologies'] = ['Python']
            self.assertEqual(self.repo.upsert_jobs_and_facts([job]), 1)
            self.assertEqual(self.repo.upsert_jobs_and_facts([job]), 1)
        self.assertEqual(self.scalar('SELECT count(*) FROM dim_job'), 2)
        self.assertEqual(self.scalar('SELECT sum(mention_count) FROM fact_job_technology'), 3)
        self.assertEqual(self.scalar("SELECT count(*) FROM fact_job_technology f JOIN dim_technology t USING (technology_id) WHERE t.name='Python'"), 1)
        self.assertEqual(self.scalar("SELECT count(*) FROM dim_job WHERE is_verified AND NOT is_demo"), 2)

    def test_entire_batch_rolls_back_on_unknown_catalog_entry(self):
        job = {**scraper.demo_jobs()[0], 'is_demo': False}
        bad = {**job, 'source_url': job['source_url'] + '/bad', 'technologies': ['Nonexistent']}
        with patch.object(self.repo, '_connect', side_effect=self.connect):
            with self.assertRaises(ValueError):
                self.repo.upsert_jobs_and_facts([job, bad])
        self.assertEqual(self.scalar('SELECT count(*) FROM dim_job'), 0)
