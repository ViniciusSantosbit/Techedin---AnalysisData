"""Exercise account UI against an isolated API fixture, without using real credentials or DB."""
import functools
import http.server
import json
import tempfile
import threading
from pathlib import Path
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect

root = Path(__file__).resolve().parents[1]
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0),
    functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root / 'frontend')))
threading.Thread(target=server.serve_forever, daemon=True).start()
origin = f'http://localhost:{server.server_port}'
state = {'account': None, 'offline': False}
errors = []
requests = []
screenshots = Path(tempfile.mkdtemp(prefix='techedin-account-'))


def api(route):
    request = route.request
    path = urlsplit(request.url).path
    headers = {'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Credentials': 'true',
               'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
               'Access-Control-Allow-Headers': 'Content-Type,X-CSRF-TOKEN'}
    status, payload = 200, {}
    if request.method == 'OPTIONS':
        route.fulfill(status=204, headers=headers)
        return
    if path == '/api/dashboard':
        payload = {'dataKind': 'real', 'jobs': [], 'sources': [], 'collectedAt': None}
    elif state['offline']:
        status, payload = 503, {'message': 'Conta temporariamente indisponível.'}
    elif path == '/api/auth/csrf':
        payload = {'headerName': 'X-CSRF-TOKEN', 'token': 'fixture-token'}
    elif path == '/api/auth/session':
        payload = {'authenticated': state['account'] is not None, 'account': state['account']}
    elif path == '/api/account':
        status = 200 if state['account'] else 401
        payload = state['account'] or {'message': 'Entre na sua conta.'}
    else:
        assert request.headers.get('x-csrf-token') == 'fixture-token'
        if path == '/api/auth/register':
            body = request.post_data_json
            state['account'] = {'id': 'fixture', 'email': body['email'], 'displayName': body['displayName'],
                'preferences': {'targetRole': '', 'location': '', 'workModel': 'any', 'seniority': 'any'}}
            status, payload = 201, state['account']
        elif path == '/api/account/preferences':
            body = request.post_data_json
            state['account']['displayName'] = body.pop('displayName')
            state['account']['preferences'] = body
            payload = state['account']
        elif path == '/api/auth/logout':
            state['account'], status = None, 204
        elif path == '/api/auth/login':
            status, payload = 401, {'message': 'E-mail ou senha inválidos.'}
    requests.append((request.method, path, status))
    route.fulfill(status=status, content_type='application/json', headers=headers,
        body='' if status == 204 else json.dumps(payload))


try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={'width': 1440, 'height': 1000}, locale='pt-BR')
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.route('http://localhost:8080/api/**', api)
        page.route('**/data/jobs.json', lambda route: route.fulfill(content_type='application/json',
            body=json.dumps({'dataKind': 'real', 'jobs': [], 'sources': []})))
        page.route('https://cdn.jsdelivr.net/**', lambda route: route.abort())
        page.goto(origin, wait_until='domcontentloaded')
        page.locator('#account-open').click()
        expect(page.locator('#account-register-tab')).to_be_enabled()
        page.locator('#account-register-tab').click()
        page.locator('#account-name').fill('Pessoa de Exemplo')
        page.locator('#account-email').fill('exemplo@example.com')
        page.locator('#account-password').fill('Senha somente para teste!')
        page.locator('#account-submit').click()
        expect(page.locator('#account-profile')).to_be_visible()
        expect(page.locator('#account-password')).to_have_value('')
        form = page.locator('#account-preferences-form')
        form.locator('[name=targetRole]').fill('Desenvolvimento Python')
        form.locator('[name=location]').fill('São Paulo, SP')
        form.locator('[name=workModel]').select_option('remote')
        form.locator('[name=seniority]').select_option('junior')
        form.locator('button[type=submit]').click()
        expect(page.locator('#account-message')).to_have_text('Preferências salvas.')
        page.screenshot(path=str(screenshots / 'desktop.png'))
        page.locator('#account-close').click()
        page.reload(wait_until='domcontentloaded')
        page.locator('#account-open').click()
        expect(form.locator('[name=targetRole]')).to_have_value('Desenvolvimento Python')
        page.set_viewport_size({'width': 390, 'height': 844})
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert page.locator('#account-dialog').bounding_box()['width'] <= 390
        page.screenshot(path=str(screenshots / 'mobile.png'))
        page.locator('#account-logout').click()
        expect(page.locator('#account-auth')).to_be_visible()
        page.locator('#account-email').fill('exemplo@example.com')
        page.locator('#account-password').fill('Senha incorreta')
        page.locator('#account-submit').click()
        expect(page.locator('#account-message')).to_have_text('E-mail ou senha inválidos.')
        assert page.evaluate('localStorage.length') == 0
        page.locator('#account-close').click()
        state['offline'] = True
        page.locator('#account-open').click()
        expect(page.locator('#account-message')).to_have_text('Conta temporariamente indisponível.')
        assert not errors, errors
        assert not any(method == 'GET' and status == 401 for method, path, status in requests), requests
        browser.close()
    print('Interface de conta validada: cadastro, preferências, reabertura, logout, erro e celular.')
    print(f'Pré-visualizações: {screenshots}')
finally:
    server.shutdown()
    server.server_close()
