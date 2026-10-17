import io
import json
import tempfile
import unittest
from unittest.mock import patch, Mock
from datetime import date
from test_scraper import scraper

REMOTIVE = 'https://remotive.com/api/remote-jobs?category=software-dev&limit=100'
ASHBY = 'https://api.ashbyhq.com/posting-api/job-board/nubank'

class PublicSourcesTest(unittest.TestCase):
    def read(self, url, jobs):
        response = io.BytesIO(json.dumps({'jobs': jobs}).encode()); response.status = 200
        with patch.object(scraper, 'urlopen', return_value=response):
            return scraper.parse_remotive_jobs(url) if url == REMOTIVE else scraper.parse_nubank_jobs(url)
    def test_remotive_validates_links_deduplicates_and_preserves_source(self):
        job = {'title': 'Senior Python Engineer', 'company_name': 'Acme', 'url': 'https://remotive.com/remote-jobs/software-dev/python-1',
            'description': '<p>Python and SQL</p>', 'publication_date': '2026-10-12T12:00:00'}
        jobs = self.read(REMOTIVE, [job, job, {**job, 'url': 'https://evil.example/remote-jobs/1'}, {**job, 'title': ''}])
        self.assertEqual(len(jobs),1); self.assertEqual(jobs[0]['source'],'Remotive')
        self.assertEqual(jobs[0]['published_date'],date(2026,10,12)); self.assertIn('Python',jobs[0]['technologies'])
    def test_nubank_ignores_unlisted_and_wrong_boards_and_uses_published_not_updated(self):
        job = {'title': 'Software Engineer Java', 'isListed': True, 'jobUrl': 'https://jobs.ashbyhq.com/nubank/abc-123',
            'descriptionPlain': 'Java, SQL e Docker', 'publishedAt': '2026-10-12T12:00:00Z', 'updatedAt': '2026-10-15T12:00:00Z'}
        jobs = self.read(ASHBY,[job, {**job, 'isListed': False}, {**job, 'jobUrl': 'https://jobs.ashbyhq.com/other/abc'}])
        self.assertEqual(len(jobs),1);self.assertEqual(jobs[0]['company'],'Nubank')
        self.assertEqual(jobs[0]['published_date'],date(2026,10,12))
    def test_rejects_unknown_urls_and_invalid_api_shape(self):
        with self.assertRaises(ValueError): scraper.parse_nubank_jobs('http://localhost/private')
        response=io.BytesIO(b'{"jobs":null}');response.status=200
        with patch.object(scraper,'urlopen',return_value=response),self.assertRaises(ValueError): scraper.parse_remotive_jobs(REMOTIVE)
    def test_cache_respects_frequency_keeps_dates_and_preserves_stale_data(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(scraper.os.environ,{'SCRAPER_CACHE_DIR':directory}):
            job={**scraper.demo_jobs()[0],'is_demo':False}
            clock = scraper.time.time()
            loader=Mock(return_value=[job])
            with patch.object(scraper.time,'time',return_value=clock):
                first,status=scraper.cached_source('Remotive',REMOTIVE,21600,loader,10)
                second,cached=scraper.cached_source('Remotive',REMOTIVE,21600,loader,10)
            self.assertEqual(loader.call_count,1);self.assertEqual(cached['status'],'cached')
            self.assertEqual(first[0]['published_date'],second[0]['published_date'])
            self.assertEqual(cached['delayHours'],24)
            loader.side_effect=RuntimeError('HTTP 503')
            with patch.object(scraper.time,'time',return_value=clock+21601):
                jobs,status=scraper.cached_source('Remotive',REMOTIVE,21600,loader,10)
                scraper.cached_source('Remotive',REMOTIVE,21600,loader,10)
            self.assertEqual(status['status'],'stale');self.assertEqual(len(jobs),1);self.assertEqual(loader.call_count,2)
            with patch.object(scraper.time,'time',return_value=clock+9*86400):
                jobs,status=scraper.cached_source('Remotive',REMOTIVE,21600,loader,10)
            self.assertEqual(jobs,[]);self.assertEqual(status['status'],'unavailable')
    def test_api_failure_does_not_cancel_other_sources_and_no_duplicate_url(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(scraper.os.environ,{'SCRAPER_CACHE_DIR':directory}), \
                patch.object(scraper,'scraper_urls',return_value=[]),patch.object(scraper,'rss_urls',return_value=[]):
            job={**scraper.demo_jobs()[0],'is_demo':False}
            with patch.object(scraper,'api_sources',return_value=[('bad','https://bad.example',900,Mock(side_effect=ValueError('format'))),
                    ('good','https://good.example',900,Mock(return_value=[job,job]))]):
                jobs,sources=scraper.collect_jobs()
            self.assertEqual(len(jobs),1);self.assertEqual(sources[0]['status'],'unavailable')
    def test_does_not_query_same_source_when_another_collection_is_running(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(scraper.os.environ,{'SCRAPER_CACHE_DIR':directory}):
            with scraper.collection_lock(),self.assertRaises(RuntimeError):
                with scraper.collection_lock(): pass
