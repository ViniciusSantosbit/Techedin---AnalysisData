"""Verify the real-data dashboard using the collected snapshot, without touching the DB."""
import functools
import http.server
import json
import threading
from pathlib import Path
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
snapshot = json.loads((root / 'frontend/data/jobs.json').read_text(encoding='utf-8'))
assert snapshot['dataKind'] == 'real' and snapshot['jobs']
assert all(job['is_demo'] is False for job in snapshot['jobs'])
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root / 'frontend'))
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
mode = {'api': False, 'snapshot': True}
errors = []

def api_response(route):
    route.fulfill(status=200 if mode['api'] else 503, content_type='application/json',
                  body=json.dumps(snapshot) if mode['api'] else '{}')

def file_response(route):
    if mode['snapshot']:
        route.continue_()
    else:
        route.fulfill(status=503, content_type='application/json', body='{}')

try:
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={'width': 1440, 'height': 1050}, locale='pt-BR')
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.route('http://localhost:8080/api/**', api_response)
        page.route('**/data/jobs.json', file_response)
        page.goto(f'http://127.0.0.1:{server.server_port}', wait_until='networkidle')
        page.locator('#data-status').filter(has_text='Dados reais · Coleta dos portais').wait_for()
        assert page.locator('.site-header').evaluate("el => getComputedStyle(el).display") == 'flex'
        assert page.locator('.stats-grid').evaluate("el => getComputedStyle(el).display") == 'grid'
        assert page.locator('.page-heading h1').evaluate("el => getComputedStyle(el).fontSize") == '56px'
        assert int(page.locator('#stat-jobs').inner_text()) == len(snapshot['jobs'])
        assert page.evaluate("Chart.getChart(document.getElementById('trendsChart')).data.datasets.length") == 1
        assert page.locator('#demo-file').count() == 0
        assert page.locator('.job-title').count() > 0
        assert page.locator('.job-title').first.get_attribute('href').startswith('https://')
        options = page.locator('#technology-select option').all_text_contents()
        if len(options) > 1:
            page.locator('#technology-select').select_option(options[1])
            assert page.evaluate("Chart.getChart(document.getElementById('trendsChart')).data.datasets[0].label") == options[1]
        page.locator('.ranking-row').first.click()
        assert not page.locator('#clear-filter').is_hidden()
        page.locator('#clear-filter').click()
        page.locator('#job-search').fill('IMPOSSIVEL-NENHUMA-VAGA-XYZ')
        assert page.locator('.job-card').count() == 0
        page.locator('#clear-filter').click()
        mode['snapshot'] = False
        page.locator('#refresh-btn').click()
        page.locator('#data-status').filter(has_text='Última coleta disponível').wait_for()
        assert int(page.locator('#stat-jobs').inner_text()) == len(snapshot['jobs'])
        mode['api'] = True
        page.locator('#refresh-btn').click()
        page.locator('#data-status').filter(has_text='Dados reais · Banco conectado').wait_for()
        mode['api'] = False
        mode['snapshot'] = True
        page.locator('#refresh-btn').click()
        page.locator('#data-status').filter(has_text='Dados reais · Coleta dos portais').wait_for()
        page.evaluate('window.scrollTo(0, 0)')
        page.wait_for_timeout(1000)
        page.screenshot(path=str(root / 'frontend-preview.png'), full_page=True)
        page.screenshot(path=str(root / 'frontend-preview-top.png'), full_page=False)
        page.set_viewport_size({'width': 390, 'height': 844})
        page.wait_for_timeout(600)
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Overflow horizontal'
        page.screenshot(path=str(root / 'frontend-mobile-preview.png'), full_page=True)
        assert not errors, errors
        browser.close()
    print('Dashboard validado: coleta real, filtros, links, preservação em falhas, API, desktop e celular.')
finally:
    server.shutdown()
    server.server_close()
