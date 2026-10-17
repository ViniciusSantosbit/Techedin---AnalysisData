import importlib.util
import json
import unittest
import tempfile
from datetime import date
from pathlib import Path
from unittest.mock import Mock, patch

PATH = Path(__file__).resolve().parents[1] / 'scrapers' / 'job_scraper.py'
spec = importlib.util.spec_from_file_location('job_scraper', PATH)
scraper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scraper)


class ParsingTests(unittest.TestCase):
    def test_punctuation_and_aliases(self):
        actual = scraper.extract_technologies('C# .NET C++ Kubernetes k8s JavaScript Node.js')
        self.assertEqual(actual, ['.NET', 'C#', 'C++', 'JavaScript', 'Kubernetes', 'Node.js'])
        self.assertNotIn('Java', scraper.extract_technologies('JavaScript'))
        self.assertNotIn('Git', scraper.extract_technologies('GitHub'))
        self.assertNotIn('R', scraper.extract_technologies('Salário R$ 10.000'))
        self.assertNotIn('Go', scraper.extract_technologies('Go to the next step'))
        self.assertNotIn('rest', scraper.extract_technologies('the rest of the team'))
        self.assertIn('R', scraper.extract_technologies('Linguagem R e Python'))
        self.assertIn('Go', scraper.extract_technologies('Go e Golang'))

    def test_seniority_does_not_guess(self):
        for title, expected in [('Dev Sênior', 'Sênior'), ('Dev Jr.', 'Júnior'),
                                ('Estagiário', 'Estágio'), ('Dev', 'Não informado')]:
            self.assertEqual(scraper.parse_seniority(title), expected)

    def test_publication_dates(self):
        today = date(2026, 10, 12)
        for text, expected in [('2026-10-10T12:00:00Z', date(2026,10,10)),
                               ('Publicado em 11/10/2026', date(2026,10,11)),
                               ('Publicada em 09 de outubro de 2026', date(2026,10,9)),
                               ('há 2 dias', date(2026,10,10)), ('ontem', date(2026,10,11)),
                               ('31/02/2026', None), ('2030-01-01', None), ('não informado', None)]:
            self.assertEqual(scraper.parse_published_date(text, today), expected)

    def test_job_url_preserves_identity(self):
        self.assertEqual(scraper.canonical_url('https://example.com/search', '/jobs/1?id=7#top'),
                         'https://example.com/jobs/1?id=7')
        self.assertIsNone(scraper.canonical_url('https://example.com', 'javascript:alert(1)'))
        self.assertFalse(scraper.is_job_url('https://portal.gupy.io/job-search/sortBy=publishedDate'))
        self.assertFalse(scraper.is_job_url('https://www.catho.com.br/vagas/desenvolvedor/'))
        self.assertTrue(scraper.is_job_url('https://vetta.gupy.io/job/encoded='))
        self.assertEqual(scraper.canonical_url('https://example.com', '/jobs/1?id=7&utm_source=ad'),
                         'https://example.com/jobs/1?id=7')

    def test_gupy_legacy_search_url_is_normalized(self):
        with patch.dict(scraper.os.environ, {'SCRAPER_URLS': 'https://portal.gupy.io/job-search?term=desenvolvedor'}):
            self.assertEqual(scraper.scraper_urls(), ['https://portal.gupy.io/job-search/term=desenvolvedor'])

    def test_home_pages_are_mapped_to_actual_searches(self):
        with patch.dict(scraper.os.environ, {'SCRAPER_URLS': 'https://www.gupy.io/empregos,https://www.catho.com.br'}):
            self.assertEqual(scraper.scraper_urls(), ['https://portal.gupy.io/job-search/term=desenvolvedor', 'https://www.catho.com.br/vagas/desenvolvedor/'])

    def test_demo_is_rich_and_rejected_before_connection(self):
        jobs = scraper.demo_jobs()
        self.assertEqual(len(jobs), 10)
        self.assertTrue(all(len(j['technologies']) >= 3 and j['is_demo'] for j in jobs))
        with patch.object(scraper.PostgresJobRepository, '_connect') as connect:
            with self.assertRaises(ValueError):
                scraper.PostgresJobRepository().upsert_jobs_and_facts(jobs)
            connect.assert_not_called()

    def test_snapshot_exports_only_real_jobs_and_records_partial_source_status(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'jobs.json'
            jobs = [{**scraper.demo_jobs()[0], 'is_demo': False}]
            sources = [{'name': 'catho', 'status': 'unavailable', 'jobCount': 0, 'message': 'HTTP 403'}]
            scraper.export_snapshot(jobs, sources, destination)
            payload = json.loads(destination.read_text(encoding='utf-8'))
            self.assertEqual(payload['dataKind'], 'real')
            self.assertEqual(payload['sources'][0]['status'], 'unavailable')
            with self.assertRaises(ValueError):
                scraper.export_snapshot(scraper.demo_jobs(), [], destination)
            self.assertEqual(json.loads(destination.read_text(encoding='utf-8')), payload)

    def test_catalog_matches_migration_names(self):
        seed = (PATH.parents[2] / 'database/migrations/V2__initial_seeds.sql').read_text(encoding='utf-8')
        for item in scraper.CATALOG:
            self.assertIn("('" + item['name'].replace("'", "''") + "',", seed)


class TransactionTests(unittest.TestCase):
    def test_unknown_technology_rolls_back_and_propagates(self):
        conn = Mock()
        conn.__enter__ = Mock(return_value=conn)
        conn.__exit__ = Mock(return_value=False)
        cursor = Mock()
        cursor.__enter__ = Mock(return_value=cursor)
        cursor.__exit__ = Mock(return_value=False)
        cursor.fetchall.return_value = []
        conn.cursor.return_value = cursor
        job = {**scraper.demo_jobs()[0], 'is_demo': False}
        with patch.object(scraper.PostgresJobRepository, '_connect', return_value=conn):
            with self.assertRaises(ValueError):
                scraper.PostgresJobRepository().upsert_jobs_and_facts([job])
        self.assertIs(conn.__exit__.call_args.args[0], ValueError)
        conn.close.assert_called_once()

    def test_bulk_facts_reuses_dimensions_and_duplicate_job_identity(self):
        conn = Mock(); cursor = Mock()
        conn.__enter__ = Mock(return_value=conn);conn.__exit__ = Mock(return_value=False)
        cursor.__enter__ = Mock(return_value=cursor);cursor.__exit__ = Mock(return_value=False)
        conn.cursor.return_value = cursor
        cursor.fetchall.return_value = [(7, 'Python')]
        day = date(2026, 10, 12)
        cursor.fetchone.side_effect = [(1, day), (2,), (3,), (4, day)]
        job = {**scraper.demo_jobs()[0], 'is_demo': False, 'technologies': ['Python', 'Python']}
        other = {**job, 'source_url': job['source_url'] + '/other'}
        with patch.object(scraper.PostgresJobRepository, '_connect', return_value=conn), patch.object(scraper, 'execute_values') as bulk:
            count = scraper.PostgresJobRepository().upsert_jobs_and_facts([job, job, other])
        self.assertEqual(count, 2)
        self.assertEqual(bulk.call_count, 1)
        self.assertEqual(bulk.call_args.args[2], [(1,7,2,3,1), (4,7,2,3,1)])
        self.assertEqual(sum('INSERT INTO dim_date' in str(call) for call in cursor.execute.call_args_list), 1)
        conn.close.assert_called_once()


class BrowserTests(unittest.TestCase):
    def test_link_fallback_and_duplicate_url(self):
        try:
            context = scraper.PlaywrightScraper().__enter__()
        except Exception as exc:
            self.skipTest(f'Chromium indisponível: {exc}')
        try:
            page = context.context.new_page()
            context.context.route('https://example.com/**', lambda route: route.fulfill(
                content_type='text/html; charset=utf-8', body='<main><h1>Desenvolvedor Sênior</h1><div class="company-name">Empresa</div>'
                '<time datetime="2026-10-10"></time><p>C# .NET PostgreSQL</p></main>'))
            page.goto('https://example.com/search')
            page.set_content('<a href="/jobs/1">Dev Sênior</a><a href="/jobs/1">Duplicada</a>')
            jobs = context.extract_jobs_from_page(page)
            self.assertEqual(len(jobs), 1)
            self.assertEqual(jobs[0]['source_url'], 'https://example.com/jobs/1')
            self.assertEqual(jobs[0]['seniority'], 'Sênior')
            self.assertIn('C#', jobs[0]['technologies'])
            self.assertEqual(jobs[0]['published_date'], date(2026,10,10))
        finally:
            context.__exit__()


if __name__ == '__main__':
    unittest.main()
