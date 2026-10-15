"""Check local CORS and anonymous CSRF-cookie round trips with API:8080 and frontend:5500 running.
No accounts are created and no database writes are performed.
"""
from pathlib import Path
from playwright.sync_api import sync_playwright

config = (Path(__file__).resolve().parents[1] / 'frontend/js/api-config.mjs').read_text(encoding='utf-8')
with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    try:
        for host in ('127.0.0.1', 'localhost'):
            context = browser.new_context()
            page = context.new_page()
            origin = f'http://{host}:5500'
            page.route(origin + '/__techedin_api_config__.mjs', lambda route: route.fulfill(
                content_type='application/javascript', body=config))
            # Navigate over real HTTP so Chromium classifies the document as loopback.
            page.goto(origin + '/', wait_until='domcontentloaded')
            result = page.evaluate('''async () => {
                const { resolveApiBase } = await import('/__techedin_api_config__.mjs');
                const base = resolveApiBase(location);
                const dashboard = await fetch(base + '/api/dashboard');
                const sessionResponse = await fetch(base + '/api/auth/session', { credentials: 'include' });
                const session = await sessionResponse.json();
                const tokenResponse = await fetch(base + '/api/auth/csrf', { credentials: 'include' });
                const csrf = await tokenResponse.json();
                // Without a session cookie the valid token would fail CSRF (403).
                // With the cookie, CSRF passes and authentication correctly rejects the anonymous user (401).
                const privateResponse = await fetch(base + '/api/account/preferences', {
                    method: 'PUT', credentials: 'include',
                    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
                    body: JSON.stringify({ displayName: 'Unused', targetRole: '', location: '', workModel: 'any', seniority: 'any' })
                });
                return { base, dashboard: dashboard.status, csrf: tokenResponse.status, anonymousWrite: privateResponse.status,
                    session: sessionResponse.status, anonymousSession: session.authenticated === false && session.account === null };
            }''')
            assert result == {'base': f'http://{host}:8080', 'dashboard': 200, 'csrf': 200, 'anonymousWrite': 401,
                'session': 200, 'anonymousSession': True}, result
            print(f'{origin}: API, CORS, preflight e cookie CSRF verificados; escrita anônima bloqueada.')
            context.close()
    finally:
        browser.close()
