"""Coleta real e demonstração explícita, sem mistura de dados."""
import argparse
import hashlib
import time
import tempfile
from contextlib import contextmanager
from html import unescape
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
import json
import logging
import os
import re
import unicodedata
import xml.etree.ElementTree as ET
from datetime import date, timedelta, datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlsplit, urlunsplit, parse_qs, parse_qsl, urlencode, quote
from urllib.request import Request, urlopen

import psycopg2
from psycopg2.extras import execute_values
from dotenv import load_dotenv
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

BASE_DIR = Path(__file__).resolve().parents[2]
load_dotenv(BASE_DIR / 'ingestion' / '.env', override=False)
logger = logging.getLogger(__name__)
CATALOG = json.loads((BASE_DIR / 'ingestion' / 'technologies.json').read_text(encoding='utf-8'))


def extract_technologies(text):
    found = set()
    for item in CATALOG:
        for alias in item['aliases']:
            if alias == 'r':
                # A currency prefix (R$) or a letter in prose is not the R language.
                pattern = r'(?:\b(?:linguagem|language|programa[cç][aã]o em)\s+R\b|\bRStudio\b|\bR\s*[,/]\s*(?:Python|SQL)\b)'
            elif alias == 'go':
                pattern = r'\b(?:linguagem|language)\s+go\b|(?-i:\bGo\b)(?!\s+(?:to|ahead|back)\b)'
            elif alias == 'rest':
                pattern = r'(?-i:\bREST\b)|\brestful\b|\bapi\s+rest\b'
            else:
                pattern = r'(?<![\w+#])' + re.escape(alias) + r'(?![\w+#])'
            if re.search(pattern, text, re.I):
                found.add(item['name'])
                break
    return sorted(found)


def parse_seniority(title):
    text = ''.join(c for c in unicodedata.normalize('NFKD', title.lower()) if not unicodedata.combining(c))
    for level, pattern in [('Estágio', r'\b(estagio|estagiari[oa]|intern)\b'),
                           ('Júnior', r'\b(junior|jr)\b'), ('Pleno', r'\b(pleno|mid[ -]?level)\b'),
                           ('Sênior', r'\b(senior|sr)\b'),
                           ('Especialista', r'\b(especialista|specialist|staff|principal)\b')]:
        if re.search(pattern, text):
            return level
    return 'Não informado'


def parse_published_date(text, today=None):
    today = today or date.today()
    match = re.search(r'\b(\d{4}-\d{2}-\d{2})(?!\d)', text)
    if match:
        value = match[1]
    else:
        match = re.search(r'\b(\d{2})/(\d{2})/(\d{4})\b', text)
        if match:
            value = '-'.join([match[3], match[2], match[1]])
        else:
            written = re.search(r'\b(\d{1,2}) de ([a-zç]+) de (\d{4})\b', text, re.I)
            months = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho',
                      'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']
            if written and written[2].lower() in months:
                try:
                    parsed = date(int(written[3]), months.index(written[2].lower())+1, int(written[1]))
                    return parsed if date(2000,1,1) <= parsed <= today else None
                except ValueError:
                    return None
            relative = re.search(r'h[aá]\s+(\d+)\s+dias?\b', text, re.I)
            if relative:
                parsed = today - timedelta(days=int(relative[1]))
                return parsed if parsed.year >= 2000 else None
            if re.search(r'\bhoje\b', text, re.I):
                return today
            if re.search(r'\bontem\b', text, re.I):
                return today - timedelta(days=1)
            return None
    try:
        parsed = date.fromisoformat(value)
        return parsed if date(2000, 1, 1) <= parsed <= today else None
    except ValueError:
        return None


def canonical_url(base, href):
    url = urlsplit(urljoin(base, href))
    if url.scheme not in ('http', 'https') or not url.hostname:
        return None
    query = urlencode(sorted((key, value) for key, value in parse_qsl(url.query, keep_blank_values=True)
                             if not key.lower().startswith('utm_') and key.lower() not in
                             ('gclid', 'fbclid', 'jobboardsource')))
    return urlunsplit((url.scheme, url.netloc.lower(), url.path, query, ''))


def is_job_url(url):
    path = urlsplit(url).path
    return bool(re.fullmatch(r'/jobs?/[^/]+/?', path) or
                re.fullmatch(r'/vagas?/(?:[^/]+/)?\d+/?', path))


def scraper_urls():
    raw = os.getenv('SCRAPER_URLS', 'https://portal.gupy.io/job-search/term=desenvolvedor,https://www.catho.com.br/vagas/desenvolvedor/')
    urls = []
    for url in filter(None, (part.strip() for part in raw.split(','))):
        parsed = urlsplit(url)
        if parsed.hostname in ('www.gupy.io', 'gupy.io') and parsed.path.rstrip('/') == '/empregos':
            url = 'https://portal.gupy.io/job-search/term=desenvolvedor'
            parsed = urlsplit(url)
        if parsed.hostname in ('www.catho.com.br', 'catho.com.br') and not parsed.path.strip('/'):
            url = 'https://www.catho.com.br/vagas/desenvolvedor/'
            parsed = urlsplit(url)
        term = parse_qs(parsed.query).get('term', [])
        if parsed.hostname == 'portal.gupy.io' and parsed.path.rstrip('/') == '/job-search' and term:
            # Gupy currently uses path-based search filters, not the old query parameter.
            url = f'https://portal.gupy.io/job-search/term={quote(term[0])}'
        urls.append(url)
    return urls


def rss_urls():
    raw = os.getenv('SCRAPER_RSS_URLS',
        'https://weworkremotely.com/categories/remote-programming-jobs.rss')
    return [part.strip() for part in raw.split(',') if part.strip()]


class FeedText(HTMLParser):
    """Extract readable text from RSS descriptions that contain HTML."""
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, data):
        if data.strip():
            self.parts.append(data.strip())

    def text(self, value):
        self.parts.clear()
        self.reset()
        self.feed(value or '')
        self.close()
        return re.sub(r'\s+', ' ', ' '.join(self.parts)).strip()


def parse_rss_jobs(feed_url, max_jobs=30):
    """Read a public RSS feed into the same job shape used by the dashboard."""
    feed = urlsplit(feed_url)
    if feed.scheme != 'https' or feed.hostname not in ('weworkremotely.com', 'www.weworkremotely.com') or not feed.path.endswith('.rss'):
        raise ValueError('A integração RSS aceita apenas feeds HTTPS do We Work Remotely.')
    request = Request(feed_url, headers={'User-Agent': 'TechedinJobCollector/1.0'})
    with urlopen(request, timeout=20) as response:
        if response.status >= 400:
            raise RuntimeError(f'HTTP {response.status} em {feed_url}')
        payload = response.read(5 * 1024 * 1024 + 1)
    if len(payload) > 5 * 1024 * 1024:
        raise RuntimeError(f'Feed RSS excede o limite de 5 MB: {feed_url}')

    if b'<!DOCTYPE' in payload.upper() or b'<!ENTITY' in payload.upper():
        raise ValueError('Declarações de entidades não são permitidas no feed RSS.')
    root = ET.fromstring(payload)
    entries = root.findall('.//item')
    if not entries:
        # Support Atom feeds as well as RSS without changing the normalized job format.
        entries = root.findall('.//{http://www.w3.org/2005/Atom}entry')

    jobs, seen = [], set()
    text_parser = FeedText()
    for entry in entries:
        if len(jobs) >= max_jobs:
            break
        title = (entry.findtext('title') or
                 entry.findtext('{http://www.w3.org/2005/Atom}title') or '').strip()
        link = (entry.findtext('link') or '').strip()
        if not link:
            atom_link = entry.find('{http://www.w3.org/2005/Atom}link')
            link = atom_link.get('href', '').strip() if atom_link is not None else ''
        if not link:
            continue
        link = canonical_url(feed_url, link)
        if not title or not link or link in seen:
            continue
        parsed_link = urlsplit(link)
        if (parsed_link.scheme != 'https' or parsed_link.hostname not in ('weworkremotely.com', 'www.weworkremotely.com')
                or not parsed_link.path.startswith('/remote-jobs/')):
            logger.warning('Link RSS fora do domínio esperado, ignorado: %s', link)
            continue

        raw_description = (entry.findtext('description') or
                           entry.findtext('{http://www.w3.org/2005/Atom}summary') or
                           entry.findtext('{http://www.w3.org/2005/Atom}content') or '')
        description = text_parser.text(raw_description)
        if not description:
            logger.warning('Anúncio RSS sem descrição, ignorado: %s', link)
            continue

        company, job_title = 'Não informado', title
        if ': ' in title:
            company, job_title = (part.strip() for part in title.split(': ', 1))
            if not company or not job_title:
                company, job_title = 'Não informado', title

        published = None
        for date_field in ('pubDate', '{http://www.w3.org/2005/Atom}published',
                           '{http://www.w3.org/2005/Atom}updated'):
            published_text = entry.findtext(date_field)
            if published_text:
                try:
                    parsed_datetime = parsedate_to_datetime(published_text)
                    published = parsed_datetime.astimezone(timezone.utc).date() if parsed_datetime.tzinfo else parsed_datetime.date()
                except (TypeError, ValueError, OverflowError):
                    published = parse_published_date(published_text)
                if published and date(2000, 1, 1) <= published <= date.today():
                    break
                published = None

        seen.add(link)
        jobs.append({'title': job_title[:255], 'company': company[:255],
                     'description': description, 'source': 'We Work Remotely', 'source_url': link,
                     'published_date': published, 'seniority': parse_seniority(job_title),
                     'technologies': extract_technologies(job_title + '\n' + description),
                     'is_demo': False})
    return jobs


def first_text(container, selectors):
    for selector in selectors:
        locator = container.locator(selector).first
        if locator.count():
            text = (locator.inner_text(timeout=1500) or '').strip()
            if text:
                return re.sub(r'\s+', ' ', text)
    return ''


def api_sources():
    sources = []
    if os.getenv('SCRAPER_REMOTIVE_ENABLED', 'true').lower() == 'true':
        sources.append(('Remotive', 'https://remotive.com/api/remote-jobs?category=software-dev&limit=100', 21600, parse_remotive_jobs))
    # Only verified employer boards are enabled; arbitrary caller URLs are never used.
    if os.getenv('SCRAPER_NUBANK_ENABLED', 'true').lower() == 'true':
        sources.append(('Nubank · Ashby', 'https://api.ashbyhq.com/posting-api/job-board/nubank', 1800, parse_nubank_jobs))
    return sources


def read_public_jobs(url):
    request = Request(url, headers={'User-Agent': 'TechedinJobCollector/1.0', 'Accept': 'application/json'})
    with urlopen(request, timeout=20) as response:
        if response.status != 200:
            raise RuntimeError(f'HTTP {response.status}')
        payload = response.read(8 * 1024 * 1024 + 1)
    if len(payload) > 8 * 1024 * 1024:
        raise ValueError('Resposta da fonte excedeu 8 MB.')
    data = json.loads(payload)
    if not isinstance(data, dict) or not isinstance(data.get('jobs'), list):
        raise ValueError('Formato da API de vagas mudou.')
    if len(data['jobs']) > 10000:
        raise ValueError('Quantidade de anúncios excedeu o limite da fonte.')
    return data['jobs']


def normalize_public_job(raw, company, source, url, description, published):
    title = raw.get('title')
    if not isinstance(title, str) or not title.strip() or not isinstance(description, str) or not description.strip():
        return None
    description = FeedText().text(unescape(description))[:100000]
    technologies = extract_technologies(title + '\n' + description)
    if not description or not technologies:
        return None
    return {'title': title.strip()[:255], 'company': company.strip()[:255], 'description': description,
            'source': source, 'source_url': url, 'published_date': parse_published_date(published if isinstance(published, str) else ''),
            'seniority': parse_seniority(title), 'technologies': technologies, 'is_demo': False}


def parse_remotive_jobs(url, max_jobs=100):
    expected = 'https://remotive.com/api/remote-jobs?category=software-dev&limit=100'
    if url != expected:
        raise ValueError('URL não autorizada para Remotive.')
    jobs, seen = [], set()
    for raw in read_public_jobs(url):
        if not isinstance(raw, dict):
            continue
        link = raw.get('url')
        if not isinstance(link, str):
            continue
        link = canonical_url(url, link)
        parsed = urlsplit(link or '')
        if parsed.scheme != 'https' or parsed.hostname != 'remotive.com' or parsed.username or not parsed.path.startswith('/remote-jobs/'):
            continue
        company = raw.get('company_name')
        if not isinstance(company, str) or not company.strip():
            continue
        job = normalize_public_job(raw, company, 'Remotive', link, raw.get('description'), raw.get('publication_date'))
        if job and link not in seen:
            seen.add(link); jobs.append(job)
    jobs.sort(key=lambda job: job['published_date'] or date.min, reverse=True)
    return jobs[:max_jobs]


def parse_nubank_jobs(url, max_jobs=100):
    if url != 'https://api.ashbyhq.com/posting-api/job-board/nubank':
        raise ValueError('URL não autorizada para Nubank.')
    jobs, seen = [], set()
    for raw in read_public_jobs(url):
        if not isinstance(raw, dict) or raw.get('isListed') is not True:
            continue
        link = raw.get('jobUrl')
        if not isinstance(link, str):
            continue
        link = canonical_url(url, link)
        parsed = urlsplit(link or '')
        if (parsed.scheme != 'https' or parsed.hostname != 'jobs.ashbyhq.com' or parsed.username
                or not re.fullmatch(r'/nubank/[a-zA-Z0-9-]+/?', parsed.path)):
            continue
        description = raw.get('descriptionPlain') or raw.get('descriptionHtml')
        job = normalize_public_job(raw, 'Nubank', 'Nubank · Ashby', link, description, raw.get('publishedAt'))
        if job and link not in seen:
            seen.add(link); jobs.append(job)
    jobs.sort(key=lambda job: job['published_date'] or date.min, reverse=True)
    return jobs[:max_jobs]


def cache_directory():
    return Path(os.getenv('SCRAPER_CACHE_DIR', str(BASE_DIR / '.cache/ingestion'))).resolve()


@contextmanager
def collection_lock():
    directory = cache_directory(); directory.mkdir(parents=True, exist_ok=True)
    with (directory / 'collector.lock').open('a+b') as handle:
        handle.seek(0); handle.write(b'0'); handle.flush(); handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError('Já existe uma coleta em execução nesta instalação.') from error
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def cached_source(name, url, minimum_seconds, loader, max_jobs):
    directory = cache_directory(); directory.mkdir(parents=True, exist_ok=True)
    path = directory / (hashlib.sha256(url.encode()).hexdigest() + '.json')
    now = time.time(); record = {}
    try:
        if path.stat().st_size <= 16 * 1024 * 1024:
            record = json.loads(path.read_text(encoding='utf-8'))
        if (record.get('url') != url or record.get('version') != 1
                or not isinstance(record.get('nextAttempt', 0), (int, float))
                or not isinstance(record.get('fetchedAt', 0), (int, float))):
            record = {}
    except (OSError, ValueError, AttributeError):
        record = {}
    cached = record.get('jobs', [])
    valid = isinstance(cached, list) and all(isinstance(job, dict) and job.get('is_demo') is False for job in cached)
    if not valid: record = {}; cached = []
    state = 'cached'
    error_message = None
    if now >= record.get('nextAttempt', 0):
        logger.info('Consultando fonte: %s', name)
        try:
            cached = loader(url, 100)
            record = {'version': 1, 'url': url, 'fetchedAt': now, 'jobs': cached}
            state = 'ok'
        except Exception as error:
            error_message = f'{type(error).__name__}: {str(error)[:240]}'
            logger.warning('Fonte %s indisponível: %s', name, error_message)
            record.update(version=1, url=url, jobs=cached, error=error_message)
        record['nextAttempt'] = now + minimum_seconds
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=directory, delete=False) as temporary:
            json.dump(record, temporary, ensure_ascii=False, default=str)
            temporary_path = Path(temporary.name)
        temporary_path.replace(path)
    if record.get('error'):
        state = 'stale' if cached and now - record.get('fetchedAt', 0) < 7 * 86400 else 'unavailable'
        error_message = record['error']
        if state == 'unavailable': cached = []
    jobs = []
    for item in cached[:max_jobs]:
        job = dict(item)
        if isinstance(job.get('published_date'), str): job['published_date'] = parse_published_date(job['published_date'])
        jobs.append(job)
    return jobs, {'url': url, 'name': name, 'status': state if jobs else ('unavailable' if error_message else 'empty'),
                  'jobCount': len(jobs), 'checkedAt': datetime.fromtimestamp(record['fetchedAt'], timezone.utc).isoformat() if record.get('fetchedAt') else None,
                  'minimumIntervalMinutes': minimum_seconds // 60, 'message': error_message,
                  'delayHours': 24 if name == 'Remotive' else 0}


class PlaywrightScraper:
    def __enter__(self):
        self.playwright = sync_playwright().start()
        try:
            self.browser = self.playwright.chromium.launch(headless=True)
            self.context = self.browser.new_context(locale='pt-BR')
        except Exception:
            self.playwright.stop()
            raise
        return self

    def __exit__(self, *_):
        try:
            self.browser.close()
        finally:
            self.playwright.stop()

    def fetch_page(self, url):
        page = self.context.new_page()
        try:
            response = page.goto(url, wait_until='domcontentloaded', timeout=30000)
            if response and response.status >= 400:
                raise RuntimeError(f'HTTP {response.status} em {url}')
            try:
                page.wait_for_load_state('networkidle', timeout=5000)
            except PlaywrightTimeoutError:
                logger.debug('Usando DOM renderizado; conexões abertas na página.')
            page.wait_for_timeout(1000)
            return page
        except Exception:
            page.close()
            raise

    def extract_jobs_from_page(self, page, max_jobs=30):
        cards = page.locator("article, [data-testid='job-item'], [class*='job-card'], "
                             "[class*='VacancyCard'], [data-testid='job-card'], [data-testid='vaga-card']").all()
        if not cards:
            cards = page.locator("a[href*='/job'], a[href*='/vaga']").all()
        seen, jobs = set(), []
        for card in cards:
            if len(seen) >= max_jobs:
                break
            detail = None
            try:
                href = card.get_attribute('href')
                if not href and card.locator('a[href]').count():
                    href = card.locator('a[href]').first.get_attribute('href')
                if not href and card.locator('xpath=ancestor::a[@href][1]').count():
                    href = card.locator('xpath=ancestor::a[@href][1]').get_attribute('href')
                link = canonical_url(page.url, href or '')
                if not link or not is_job_url(link) or link in seen:
                    continue
                host, provider = urlsplit(link).hostname, urlsplit(page.url).hostname
                if not (host == provider or (host.endswith('.gupy.io') and provider.endswith('.gupy.io'))):
                    continue
                seen.add(link)
                title = first_text(card, ['h1', 'h2', 'h3', "[class*='title']"])
                title = title or re.sub(r'\s+', ' ', card.inner_text() or '').strip()[:255]
                company = first_text(card, ["[class*='company']", "[class*='empresa']", "[data-testid*='company']"])
                if not company and provider == 'portal.gupy.io':
                    company = first_text(card, ['p'])
                card_text = card.inner_text()
                detail = self.fetch_page(link)
                final_url = canonical_url(detail.url, '')
                if not final_url or not is_job_url(final_url):
                    logger.warning('Vaga redirecionou para página de navegação/login: %s', link)
                    continue
                link = final_url
                host = urlsplit(link).hostname
                description = first_text(detail, ["[data-testid='job-description']", "[class*='job-description']", 'main', 'article'])
                title = first_text(detail, ['h1']) or title
                company = first_text(detail, ["[data-testid*='company']", "[class*='company-name']"]) or company
                if not title or not description:
                    logger.warning('Vaga sem título/descrição: %s', link)
                    continue
                time_element = detail.locator('time').first
                published = None
                if time_element.count():
                    published = parse_published_date(time_element.get_attribute('datetime') or time_element.text_content() or '')
                if published is None:
                    label = re.search(r'(?:publicad[ao]|postad[ao])[^\n]{0,80}', card_text + '\n' + description, re.I)
                    if label:
                        published = parse_published_date(label[0])
                jobs.append({'title': title[:255], 'company': (company or 'Não informado')[:255],
                             'description': description, 'source': host, 'source_url': link,
                             'published_date': published, 'seniority': parse_seniority(title),
                             'technologies': extract_technologies(title + '\n' + description), 'is_demo': False})
            except Exception as exc:
                logger.warning('Falha ao ler vaga: %s', exc)
            finally:
                if detail:
                    detail.close()
                page.wait_for_timeout(500)
        return jobs


class PostgresJobRepository:
    def _connect(self):
        return psycopg2.connect(host=os.getenv('DB_HOST', 'localhost'),
                                port=os.getenv('DB_PORT', '5432'), dbname=os.getenv('DB_NAME', 'techedin'),
                                user=os.getenv('DB_USER', 'postgres'), password=os.getenv('DB_PASSWORD', ''),
                                sslmode=os.getenv('DB_SSLMODE', 'require'), connect_timeout=10)

    def upsert_jobs_and_facts(self, jobs):
        if any(job.get('is_demo') for job in jobs):
            raise ValueError('Dados demo não podem ser gravados no pipeline real.')
        if not jobs:
            return 0
        jobs = list({(job['source'], job['source_url']): job for job in jobs}.values())
        conn = self._connect()
        facts, dates, levels = [], {}, {}
        try:
            with conn, conn.cursor() as cur:
                cur.execute("SET LOCAL lock_timeout = '10s'")
                cur.execute("SET LOCAL statement_timeout = '30s'")
                cur.execute("SELECT pg_advisory_xact_lock(754328901)")
                cur.execute('SELECT technology_id, name FROM dim_technology')
                technology_ids = {name: identifier for identifier, name in cur.fetchall()}
                for job in jobs:
                    missing = set(job['technologies']) - technology_ids.keys()
                    if missing:
                        raise ValueError(f'Catálogo ausente no banco: {sorted(missing)}. Execute as migrations.')
                    cur.execute('''INSERT INTO dim_job
                        (title, company, description, source, source_url, published_date, is_demo, is_verified)
                        VALUES (%s,%s,%s,%s,%s,%s,FALSE,TRUE)
                        ON CONFLICT ON CONSTRAINT uq_dim_job_source_url DO UPDATE SET
                        title=EXCLUDED.title, company=EXCLUDED.company, description=EXCLUDED.description,
                        published_date=COALESCE(EXCLUDED.published_date, dim_job.published_date), is_verified=TRUE
                        RETURNING job_id, COALESCE(published_date, first_seen)''',
                        (job['title'], job['company'], job['description'], job['source'], job['source_url'], job.get('published_date')))
                    job_id, fact_date = cur.fetchone()
                    if fact_date not in dates:
                        cur.execute('''INSERT INTO dim_date (date, day, month, year) VALUES (%s,%s,%s,%s)
                            ON CONFLICT (date) DO UPDATE SET date=EXCLUDED.date RETURNING date_id''',
                            (fact_date, fact_date.day, fact_date.month, fact_date.year))
                        dates[fact_date] = cur.fetchone()[0]
                    level = job.get('seniority', 'Não informado')
                    if level not in levels:
                        cur.execute('''INSERT INTO dim_seniority (level) VALUES (%s)
                            ON CONFLICT (level) DO UPDATE SET level=EXCLUDED.level RETURNING seniority_id''', (level,))
                        levels[level] = cur.fetchone()[0]
                    cur.execute('DELETE FROM fact_job_technology WHERE job_id=%s', (job_id,))
                    facts.extend((job_id, technology_ids[technology], dates[fact_date], levels[level], 1)
                                 for technology in sorted(set(job['technologies'])))
                if facts:
                    execute_values(cur, '''INSERT INTO fact_job_technology
                        (job_id, technology_id, date_id, seniority_id, mention_count) VALUES %s''', facts, page_size=1000)
            return len(facts)
        finally:
            conn.close()


def demo_jobs():
    stacks = ['Python PostgreSQL Docker AWS', 'Java Spring Boot SQL Git', 'C# .NET Azure',
              'JavaScript TypeScript React Node.js', 'C++ Linux Git']
    levels = ['Júnior', 'Pleno', 'Sênior', 'Estágio', 'Especialista']
    return [{'title': f'DEMO Desenvolvedor {levels[i % 5]} {i+1}', 'company': f'Empresa fictícia {i+1}',
             'description': 'DEMONSTRAÇÃO: ' + stacks[i % 5], 'source': 'demo.invalid',
             'source_url': f'https://demo.invalid/jobs/{i+1}', 'published_date': date.today()-timedelta(days=i % 5),
             'seniority': levels[i % 5], 'technologies': extract_technologies(stacks[i % 5]), 'is_demo': True}
            for i in range(10)]


def _collect_jobs(max_jobs=30):
    jobs, sources = [], []
    for name, url, interval, loader in api_sources():
        collected, status = cached_source(name, url, interval, loader, max_jobs)
        jobs.extend(collected); sources.append(status)
    for url in rss_urls():
        collected, status = cached_source('We Work Remotely', url, 1800, parse_rss_jobs, max_jobs)
        jobs.extend(collected); sources.append(status)

    def page_jobs(url, limit):
        with PlaywrightScraper() as scraper:
            page = scraper.fetch_page(url)
            try:
                return scraper.extract_jobs_from_page(page, min(limit, max_jobs))
            finally:
                page.close()
    for url in scraper_urls():
        collected, status = cached_source(urlsplit(url).hostname or 'Portal', url, 900, page_jobs, max_jobs)
        jobs.extend(collected); sources.append(status)
    if not jobs:
        raise RuntimeError('Nenhuma vaga real coletada. Verifique seletores/bloqueios; demo é explícita.')
    jobs = list({j['source_url']: j for j in jobs}.values())
    return jobs, sources


def collect_jobs(max_jobs=30):
    with collection_lock():
        return _collect_jobs(max_jobs)


def export_snapshot(jobs, sources, destination):
    if not jobs or any(job.get('is_demo') for job in jobs):
        raise ValueError('O painel aceita apenas snapshots de vagas reais.')
    snapshot = {'schemaVersion': 1, 'dataKind': 'real', 'collectedAt': datetime.now(timezone.utc).isoformat(),
                'sources': sources, 'jobs': jobs}
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.tmp')
    temporary.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    temporary.replace(destination)
    logger.info('%s vagas reais exportadas para %s', len(jobs), destination)


def run_scraper(max_jobs=30):
    jobs, sources = collect_jobs(max_jobs)
    count = PostgresJobRepository().upsert_jobs_and_facts(jobs)
    logger.info('%s vagas e %s fatos persistidos.', len(jobs), count)
    if any(source['status'] not in ('ok', 'cached', 'empty') for source in sources):
        logger.warning('Coleta parcial: consulte a disponibilidade das fontes nos logs.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--demo-json', type=Path, help='Gera 10 vagas fictícias em JSON, sem sites/banco.')
    parser.add_argument('--export-json', type=Path, help='Coleta vagas REAIS e salva um snapshot, sem conexão com banco.')
    parser.add_argument('--max-jobs', type=int, default=30, help='Limite de vagas por fonte (1 a 100).')
    parser.add_argument('--api-only', action='store_true', help='Consulta somente APIs públicas Remotive/Nubank, sem Chromium ou RSS.')
    parser.add_argument('--watch', action='store_true', help='Mantém o coletor ativo; interrompa com Ctrl+C.')
    parser.add_argument('--interval-seconds', type=int, default=1800, help='Intervalo entre ciclos no modo watch, mínimo 900.')

    args = parser.parse_args()
    if not 900 <= args.interval_seconds <= 86400:
        parser.error('--interval-seconds deve estar entre 900 e 86400')
    if args.watch and args.demo_json:
        parser.error('--watch não aceita dados demo')
    if args.api_only:
        os.environ['SCRAPER_URLS'] = ''
        os.environ['SCRAPER_RSS_URLS'] = ''
    if not 1 <= args.max_jobs <= 100:
        parser.error('--max-jobs deve estar entre 1 e 100')
    if args.demo_json and args.export_json:
        parser.error('--demo-json e --export-json são modos separados')
    if args.demo_json:
        args.demo_json.write_text(json.dumps(demo_jobs(), ensure_ascii=False, indent=2, default=str), encoding='utf-8')
        return
    def cycle():
        if args.export_json:
            jobs, sources = collect_jobs(args.max_jobs)
            export_snapshot(jobs, sources, args.export_json)
        else:
            run_scraper(args.max_jobs)
    if not args.watch:
        cycle()
        return
    logger.info('Coletor contínuo ativo. O processo precisa permanecer em execução; Ctrl+C encerra.')
    try:
        while True:
            try:
                cycle()
            except Exception as error:
                logger.error('Ciclo falhou (%s); dados anteriores preservados. Próxima tentativa no intervalo configurado.', type(error).__name__)
            time.sleep(args.interval_seconds)
    except KeyboardInterrupt:
        logger.info('Coletor encerrado pelo operador.')



if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
    main()
