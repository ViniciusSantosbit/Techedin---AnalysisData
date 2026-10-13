FROM python:3.12-slim
WORKDIR /app/ingestion
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && playwright install --with-deps chromium
COPY technologies.json .
COPY scrapers scrapers
RUN useradd --create-home techedin && mkdir -p /home/techedin/.cache && cp -r /root/.cache/ms-playwright /home/techedin/.cache/ && chown -R techedin:techedin /home/techedin/.cache
ENV PLAYWRIGHT_BROWSERS_PATH=/home/techedin/.cache/ms-playwright
USER techedin
ENTRYPOINT ["python", "scrapers/job_scraper.py"]
