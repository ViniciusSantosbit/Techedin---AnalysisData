"""Read-only live selector check. Does not connect to any database."""
import importlib.util
import logging
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('scraper', root / 'ingestion/scrapers/job_scraper.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
logging.basicConfig(level=logging.INFO)
with module.PlaywrightScraper() as scraper:
    for url in module.scraper_urls():
        if not url.strip():
            continue
        page = None
        try:
            page = scraper.fetch_page(url.strip())
            jobs = scraper.extract_jobs_from_page(page, max_jobs=1)
            print({'source': url, 'jobs': len(jobs), 'sample': [
                {'title': j['title'], 'url': j['source_url'], 'technologies': j['technologies'],
                 'description_preview': j['description'][:1200]} for j in jobs]})
        except Exception as exc:
            print({'source': url, 'error': str(exc)})
        finally:
            if page:
                page.close()
