"""Inspect the assets and computed layout delivered by the user's local server."""
from pathlib import Path
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1050}, locale='pt-BR')
    assets = []
    page.on('response', lambda response: assets.append((response.url, response.status))
            if '/css/styles.css' in response.url else None)
    page.goto('http://localhost:8081/?v=css-fix-02', wait_until='networkidle')
    page.wait_for_function("document.getElementById('stat-jobs').textContent !== '—'")
    styles = page.evaluate('''() => ({
        header: getComputedStyle(document.querySelector('.site-header')).display,
        grid: getComputedStyle(document.querySelector('.stats-grid')).display,
        heading: getComputedStyle(document.querySelector('h1')).fontSize,
        background: getComputedStyle(document.body).backgroundColor,
        headingFont: getComputedStyle(document.querySelector('h1')).fontFamily,
        navbar: getComputedStyle(document.querySelector('.site-header')).backgroundColor,
        editorialLoaded: [...document.styleSheets].some(sheet => sheet.href?.includes('/css/editorial.css')),
        rules: [...document.styleSheets].find(sheet => sheet.href?.includes('/css/styles.css')).cssRules.length
    })''')
    print('CSS servido:', assets)
    print('Estilos aplicados:', styles)
    assert styles['header'] == 'flex' and styles['grid'] == 'grid' and styles['heading'] == '64px'
    assert styles['editorialLoaded'] and 'Space Grotesk' in styles['headingFont']
    assert styles['navbar'] == 'rgb(27, 26, 31)'
    assert styles['rules'] > 60
    page.wait_for_timeout(800)
    page.screenshot(path=str(root / 'frontend-preview-top.png'))
    browser.close()
