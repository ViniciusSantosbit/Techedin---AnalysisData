import importlib.util
import io
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('rss_scraper', Path(__file__).resolve().parents[1] / 'scrapers/job_scraper.py')
scraper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scraper)
FEED = 'https://weworkremotely.com/categories/remote-programming-jobs.rss'


class RssTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        cache = patch.dict(scraper.os.environ, {'SCRAPER_CACHE_DIR': directory.name})
        cache.start(); self.addCleanup(cache.stop)
        sources = patch.object(scraper, 'api_sources', return_value=[])
        sources.start(); self.addCleanup(sources.stop)

    def read(self, xml):
        response = io.BytesIO(xml.encode())
        response.status = 200
        with patch.object(scraper, 'urlopen', return_value=response):
            return scraper.parse_rss_jobs(FEED, 5)

    def test_normalizes_preserving_attribution_and_skips_bad_links(self):
        item = '<item><title>Acme: Python Developer Senior</title><link>https://weworkremotely.com/remote-jobs/acme-dev</link><description>&lt;p&gt;Python and PostgreSQL&lt;/p&gt;</description><pubDate>Mon, 12 Oct 2026 12:00:00 +0000</pubDate></item>'
        jobs = self.read('<rss><channel>' + item + item +
            '<item><title>Missing link</title><description>Python</description></item>' +
            '<item><title>External</title><link>https://untrusted.example/remote-jobs/dev</link><description>Python</description></item>' +
            '<item><title>No description</title><link>https://weworkremotely.com/remote-jobs/no-details</link></item>' +
            '</channel></rss>')
        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0]['company'], 'Acme')
        self.assertEqual(jobs[0]['title'], 'Python Developer Senior')
        self.assertEqual(jobs[0]['source'], 'We Work Remotely')
        self.assertEqual(jobs[0]['description'], 'Python and PostgreSQL')
        self.assertEqual(jobs[0]['technologies'], ['PostgreSQL', 'Python'])
        self.assertFalse(jobs[0]['is_demo'])

    def test_rejects_unknown_feed_and_entity_declarations(self):
        with self.assertRaises(ValueError):
            scraper.parse_rss_jobs('http://untrusted.example/jobs.rss')
        with self.assertRaises(ValueError):
            self.read('<!DOCTYPE rss [<!ENTITY job "Python">]><rss/>')

    def test_rss_remains_available_if_browser_cannot_start(self):
        job = {'source': 'We Work Remotely', 'source_url': 'https://weworkremotely.com/remote-jobs/real'}
        with patch.object(scraper, 'parse_rss_jobs', return_value=[job]), \
                patch.object(scraper, 'rss_urls', return_value=[FEED]), \
                patch.object(scraper, 'scraper_urls', return_value=['https://portal.gupy.io']), \
                patch.object(scraper.PlaywrightScraper, '__enter__', side_effect=RuntimeError('browser unavailable')):
            jobs, sources = scraper.collect_jobs()
        self.assertEqual(jobs, [job])
        self.assertEqual([source['status'] for source in sources], ['ok', 'unavailable'])
