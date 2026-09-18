import os
import re
import logging
import time
from pathlib import Path

import pandas as pd
import psycopg2
from dotenv import load_dotenv
from playwright.sync_api import sync_playwright, Browser, Page, TimeoutError as PlaywrightTimeoutError

# Carrega .env de forma robusta: diretório atual + raiz do projeto
BASE_DIR = Path(__file__).resolve().parent.parent.parent
ENV_PATH = BASE_DIR / "ingestion" / ".env"
load_dotenv(dotenv_path=ENV_PATH, override=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


# ============================================================
# 1. Playwright-based scraper module
# ============================================================

class PlaywrightScraper:
    def __init__(self, headless: bool = True):
        self.headless = headless
        self._playwright = None
        self._browser: Browser = None

    def __enter__(self):
        logger.info("Starting Playwright (Chromium)...")
        self._playwright = sync_playwright().start()
        self._browser = self._playwright.chromium.launch(headless=self.headless)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._browser:
            self._browser.close()
        if self._playwright:
            self._playwright.stop()
        logger.info("Playwright stopped.")

    def fetch_page(self, url: str, wait_selector: str = "body") -> Page:
        page = self._browser.new_page()
        page.set_default_timeout(3000)
        page.set_default_navigation_timeout(3000)
        logger.info(f"Navegando para: {url} (timeout: 3s)")
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=3000)
        except Exception as e:
            logger.warning(f"Timeout/erro ao navegar para {url}: {e}. Tentando continuar...")
            return page
        try:
            page.wait_for_selector(wait_selector, timeout=3000)
            logger.info(f"Página carregada. Seletor encontrado: '{wait_selector}'")
        except Exception:
            logger.warning(f"Seletor '{wait_selector}' não encontrado em {url} dentro do timeout. Continuando com conteúdo parcial.")
        return page

    def extract_jobs_from_page(self, page: Page) -> list[dict]:
        jobs: list[dict] = []
        source_url = page.url

        cards = []
        try:
            cards = page.locator(
                "[data-testid='job-card'], .job-card, [class*='card'], article, li"
            ).all()
            logger.info(f"Elementos candidatos encontrados: {len(cards)}")
        except Exception as e:
            logger.error(f"Falha ao localizar cards na página: {e}")

        if not cards:
            logger.warning("Nenhum card encontrado na página.")
            return jobs

        # Limite de amostragem: apenas os 5 primeiros elementos
        sample_cards = cards[:5]
        logger.info(f"Processando amostra de {len(sample_cards)} vagas (top 5 de {len(cards)})...")

        for idx, card in enumerate(sample_cards, start=1):
            logger.info(f"Processando vaga {idx} de {len(sample_cards)}...")
            start_time = time.time()
            title = "Não informado"
            company = "Não informado"
            description = ""
            link = ""

            try:
                # Extração com timeout de 1 segundo por campo
                title = self._extract_text_with_timeout(card, "h1, h2, h3, [class*='title'], a, strong, b", 1000)
                company = self._extract_text_with_timeout(card, "[class*='company'], [class*='empresa'], [class*='name']", 1000)
                description = self._extract_text_with_timeout(card, "[class*='description'], [class*='descricao'], p, span", 1000)
                link = self._extract_link_with_timeout(card, 1000)
                link = self._normalize_link(link, source_url)
            except Exception as e:
                logger.warning(f"Vaga {idx}: falha na extração ({e})")

            elapsed = time.time() - start_time
            logger.info(f"Vaga {idx}: título='{title}' | empresa='{company}' | tempo={elapsed:.2f}s")

            if title == "Não informado" and company == "Não informado":
                logger.info(f"Vaga {idx}: ignorada (sem dados válidos)")
                continue

            jobs.append({
                "title": title,
                "company": company,
                "description": description,
                "source": source_url.split("/")[2],
                "source_url": source_url,
                "link": link,
            })
            logger.info(f"Vaga {idx}: adicionada com sucesso")

        return jobs

    def _extract_text_with_timeout(self, card, selector: str, timeout_ms: int) -> str:
        try:
            locator = card.locator(selector).first
            # timeout no Playwright é em ms
            return (locator.text_content(timeout=timeout_ms) or "").strip()
        except PlaywrightTimeoutError:
            return "Não informado"
        except Exception:
            return "Não informado"

    def _extract_link_with_timeout(self, card, timeout_ms: int) -> str:
        try:
            return card.locator("a").first.get_attribute("href", timeout=timeout_ms) or ""
        except Exception:
            return ""

    @staticmethod
    def _normalize_link(link: str, base_url: str) -> str:
        if not link:
            return ""
        if link.startswith("http"):
            return link
        if link.startswith("/"):
            root = "/".join(base_url.split("/")[:3])
            return root + link
        return link


# ============================================================
# 2. Primary hygiene/normalization with Pandas
# ============================================================

def normalize_jobs_df(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df

    df = df.copy()
    for col in ("title", "company", "description"):
        if col in df.columns:
            df[col] = df[col].apply(_clean_text)
            df[col] = df[col].str.replace(r"\s{2,}", " ", regex=True).str.strip()

    df = df.dropna(subset=["title", "company"])
    df = df.drop_duplicates(subset=["title", "company", "source_url"], keep="first")
    df = df.reset_index(drop=True)

    if "description" in df.columns:
        df["description"] = df["description"].replace("", None)
    return df


def _clean_text(text: str) -> str:
    if not isinstance(text, str):
        return ""
    text = re.sub(r"[\r\n\t]+", " ", text)
    text = re.sub(r"\s{2,}", " ", text)
    text = re.sub(r"[^\S ]+", "", text)
    return text.strip()


# ============================================================
# 3. PostgreSQL insertion preserving raw text for RNF01
# ============================================================

class PostgresJobRepository:
    # Host correto do Supabase como fallback obrigatório
    SUPABASE_HOST = "db.hvuprzgcxkirpgftnohx.supabase.co"

    def __init__(self):
        self.dsn = self._build_dsn()

    @staticmethod
    def _build_dsn() -> str:
        host = os.getenv("DB_HOST", "").strip()
        if not host:
            host = PostgresJobRepository.SUPABASE_HOST
            logger.warning(f"DB_HOST vazio no .env. Usando fallback Supabase: {host}")
        port = os.getenv("DB_PORT", "5432")
        dbname = os.getenv("DB_NAME", "techedin")
        user = os.getenv("DB_USER", "postgres")
        password = os.getenv("DB_PASSWORD", "postgres")
        sslmode = os.getenv("DB_SSLMODE", "require")
        return f"host={host} port={port} dbname={dbname} user={user} password={password} sslmode={sslmode}"

    def _connect(self):
        # Extrai host e user do DSN para log claro
        host = self._extract_param(self.dsn, "host")
        user = self._extract_param(self.dsn, "user")
        logger.info(f"Conectando ao PostgreSQL... host={host} user={user}")
        return psycopg2.connect(self.dsn)

    @staticmethod
    def _extract_param(dsn: str, param: str) -> str:
        import re
        match = re.search(rf"{param}=([^\s]+)", dsn)
        return match.group(1) if match else "desconhecido"

    def upsert_jobs(self, jobs: list[dict]) -> int:
        if not jobs:
            logger.info("Nenhuma vaga para inserir.")
            return 0

        df = pd.DataFrame(jobs)
        df = normalize_jobs_df(df)

        if df.empty:
            logger.warning("DataFrame vazio após normalização. Nenhuma vaga inserida.")
            return 0

        rows = []
        for record in df.to_dict(orient="records"):
            rows.append((
                record.get("title"),
                record.get("company"),
                record.get("description"),
                record.get("source"),
            ))

        insert_sql = """
            INSERT INTO dim_job (title, company, description, source)
            VALUES %s
            ON CONFLICT ON CONSTRAINT uq_dim_job_source_title_company
            DO UPDATE SET
                title       = EXCLUDED.title,
                company     = EXCLUDED.company,
                description = EXCLUDED.description;
        """

        inserted = 0
        conn = None
        try:
            from psycopg2.extras import execute_values
            logger.info("Conectando ao PostgreSQL (Supabase)...")
            conn = self._connect()
            cur = conn.cursor()
            logger.info("Inserindo no Supabase...")
            execute_values(cur, insert_sql, rows, fetch=False, page_size=100)
            conn.commit()
            inserted = len(rows)
            logger.info(f"Upsert concluído: {inserted} vaga(s) em dim_job.")
            cur.close()
        except psycopg2.IntegrityError as e:
            if conn:
                conn.rollback()
            logger.error(f"IntegrityError durante upsert: {e}")
        except psycopg2.Error as e:
            if conn:
                conn.rollback()
            logger.error(f"DatabaseError durante upsert: {e}")
        except Exception as e:
            logger.error(f"Erro inesperado durante upsert: {e}")
        finally:
            if conn:
                conn.close()
                logger.info("Conexão com banco fechada.")
        return inserted


# ============================================================
# 4. Orchestrator
# ============================================================

def get_scraper_urls() -> list[str]:
    raw = os.getenv("SCRAPER_URLS", "")
    urls = [url.strip() for url in raw.split(",") if url.strip()]
    logger.info(f"URLs de scraping carregadas: {urls}")
    return urls


def run_scraper():
    urls = get_scraper_urls()
    if not urls:
        logger.warning("Nenhuma URL configurada em SCRAPER_URLS. Encerrando.")
        return

    all_jobs: list[dict] = []
    repo = PostgresJobRepository()

    for index, url in enumerate(urls, start=1):
        logger.info(f"[{index}/{len(urls)}] Processando URL: {url}")
        try:
            jobs = scrape_single_url(url)
            all_jobs.extend(jobs)
        except Exception as e:
            logger.error(f"[{index}/{len(urls)}] Falha ao processar {url}: {e}. Continuando para próxima URL.")
            continue

    if not all_jobs:
        logger.warning("Nenhuma vaga foi extraída de nenhuma URL. Encerrando.")
        return

    logger.info(f"Total de vagas coletadas: {len(all_jobs)}")
    logger.info("Iniciando limpeza com Pandas...")
    repo.upsert_jobs(all_jobs)
    logger.info("Execução finalizada.")


def scrape_single_url(url: str) -> list[dict]:
    jobs: list[dict] = []
    scraper = None
    try:
        logger.info(f"Iniciando extração para: {url}")
        scraper = PlaywrightScraper(headless=True)
        scraper.__enter__()
        page = scraper.fetch_page(url, wait_selector="body")
        jobs = scraper.extract_jobs_from_page(page)
        logger.info(f"Extração concluída para {url}. Vagas capturadas: {len(jobs)}")
        logger.info("Passando dados para o Pandas...")
    except Exception as e:
        logger.error(f"Erro crítico ao processar {url}: {e}")
    finally:
        if scraper:
            try:
                scraper.__exit__(None, None, None)
                logger.info("Navegador fechado.")
            except Exception:
                pass
    return jobs


if __name__ == "__main__":
    run_scraper()
