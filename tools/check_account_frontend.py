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
state = {'account': None, 'resume': None, 'offline': False, 'push': False}
resume_text = 'Pessoa de Exemplo - Desenvolvimento Java e Python. Experiência em sistemas, testes e bancos SQL.'
errors = []
requests = []
screenshots = Path(tempfile.mkdtemp(prefix='techedin-account-'))


def api(route):
    request = route.request
    path = urlsplit(request.url).path
    headers = {'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Credentials': 'true',
               'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
               'Access-Control-Allow-Headers': 'Content-Type,X-CSRF-TOKEN'}
    status, payload = 200, {}
    if request.method == 'OPTIONS':
        route.fulfill(status=204, headers=headers)
        return
    if path == '/api/dashboard':
        payload = {'dataKind': 'real', 'jobs': [], 'sources': [{'name': 'Remotive', 'status': 'cached', 'jobCount': 0}, *[{'name': f'Fonte {i}', 'status': 'stored', 'jobCount': 0} for i in range(4)]], 'collectedAt': None}
    elif state['offline']:
        status, payload = 503, {'message': 'Conta temporariamente indisponível.'}
    elif path == '/api/auth/options':
        payload = {'emailConfirmationRequired': True, 'localMailPreview': False, 'emailDeliveryEnabled': True}
    elif path == '/api/auth/csrf':
        payload = {'headerName': 'X-CSRF-TOKEN', 'token': 'fixture-token'}
    elif path == '/api/auth/session':
        payload = {'authenticated': state['account'] is not None, 'account': state['account']}
    elif path == '/api/account/notifications' and request.method == 'GET':
        payload = {'available': True, 'publicKey': 'B' + 'A' * 86}
    elif path == '/api/account/recommendations':
        payload = {'state': 'ready' if state['resume'] else 'no_resume', 'skills': ['Java', 'Python'], 'sampleSize': 1,
            'matches': [{'job': {'url': 'https://jobs.example/1', 'title': 'Backend Java', 'company': 'Exemplo', 'source': 'Teste', 'seniority': 'Júnior'},
                'matchedSkills': ['Java'], 'otherSkills': ['Docker'], 'reasons': ['Java aparece nos dois textos.']}] if state['resume'] else []}
    elif path == '/api/account/resume' and request.method == 'GET':
        payload = {'resume': state['resume']}
    elif path == '/api/account/resume/evaluation' and request.method == 'GET':
        payload = {'configured': True}
    elif path == '/api/account' and request.method == 'GET':
        status = 200 if state['account'] else 401
        payload = state['account'] or {'message': 'Entre na sua conta.'}
    else:
        assert request.headers.get('x-csrf-token') == 'fixture-token'
        if path == '/api/account/notifications/status':
            payload = {'subscribed': state['push']}
        elif path == '/api/account/notifications':
            assert request.post_data_json['endpoint'] == 'https://fcm.googleapis.com/fcm/send/fixture'
            state['push'] = request.method == 'POST'
            status = 204 if request.method == 'DELETE' else 200
        elif path == '/api/account/career-chat':
            assert 'includeResume' not in request.post_data_json
            payload = {'resumeRevision': state['resume']['revision'] if state['resume'] else None, 'text': '<script>Exemplo seguro</script> Descreva resultados nos projetos.'}
        elif path == '/api/account/resume/evaluation':
            assert request.post_data_json['revision'] == state['resume']['revision']
            payload = {'revision': state['resume']['revision'], 'score': 7.5,
                'criteria': [{'name': name, 'score': 1.5, 'feedback': 'Exemplo de melhoria.'} for name in ['Clareza', 'Organização', 'Experiências e projetos', 'Habilidades', 'Formação']],
                'suggestions': '<script>Não executar HTML</script>'}
        elif path == '/api/account/resume':
            if request.method == 'POST':
                assert request.headers['content-type'].startswith('multipart/form-data; boundary=')
                assert b'curriculo.pdf' in request.post_data_buffer
                state['resume'] = {'revision': '00000000-0000-0000-0000-000000000001', 'fileName': 'curriculo.pdf',
                    'fileType': 'application/pdf', 'fileSize': 512, 'text': resume_text,
                    'uploadedAt': '2026-10-16T12:00:00Z', 'updatedAt': '2026-10-16T12:00:00Z'}
            elif request.method == 'PUT':
                assert request.post_data_json['revision'] == state['resume']['revision']
                state['resume']['text'] = request.post_data_json['text']
                state['resume']['revision'] = '00000000-0000-0000-0000-000000000002'
            elif request.method == 'DELETE':
                assert request.post_data_json['revision'] == state['resume']['revision']
                state['resume'], status = None, 204
            payload = {'resume': state['resume']}
        elif path == '/api/auth/register':
            body = request.post_data_json
            state['account'] = {'id': 'fixture', 'email': body['email'], 'displayName': body['displayName'],
                'preferences': {'targetRole': '', 'location': '', 'workModel': 'any', 'seniority': 'any'}}
            status, payload = 201, state['account']
            if state.get('verification_required'):
                state['account'] = None
                payload = {'verificationRequired': True, 'message': 'Confirme seu e-mail para entrar.'}
        elif path in ['/api/auth/password/request', '/api/auth/verification/request']:
            payload = {'message': 'Se houver uma conta elegível, enviaremos um link.'}
        elif path in ['/api/auth/password/reset', '/api/auth/verification/confirm']:
            assert request.post_data_json['token'] == 'A' * 43
            payload = {'message': 'Senha alterada.' if path.endswith('/reset') else 'E-mail confirmado.'}
        elif path == '/api/account' and request.method == 'DELETE':
            assert request.post_data_json['confirmation'] == 'EXCLUIR'
            state['account'], state['resume'], state['push'], status = None, None, False, 204
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
        browser_context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='pt-BR')
        browser_context.add_init_script("""
            window.__permissionRequests = 0;
            const notice = class { static permission = 'default'; static async requestPermission() { window.__permissionRequests++; this.permission = 'granted'; return 'granted'; } };
            Object.defineProperty(window, 'Notification', {value: notice});
            Object.defineProperty(window, 'PushManager', {value: class {}});
            let active = null;
            const registration = {pushManager: {
                getSubscription: async () => active,
                subscribe: async options => { if (options.applicationServerKey.length !== 65) throw Error('Invalid key'); active = {endpoint:'https://fcm.googleapis.com/fcm/send/fixture',unsubscribe:async () => { active = null; return true; }}; return active; }
            }};
            Object.defineProperty(navigator, 'serviceWorker', {value: {register:async () => registration}});
        """)
        page = browser_context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.route('http://localhost:8080/api/**', api)
        page.route('**/data/jobs.json', lambda route: route.fulfill(content_type='application/json',
            body=json.dumps({'dataKind': 'real', 'jobs': [], 'sources': []})))
        page.route('https://cdn.jsdelivr.net/**', lambda route: route.abort())
        page.goto(origin, wait_until='domcontentloaded')
        expect(page.locator('#sources-list')).to_contain_text('API com atraso de 24h')
        expect(page.locator('#sources-list')).to_contain_text('Em cache')
        expect(page.locator('#sources-list .source-row')).to_have_count(3)
        page.locator('#sources-toggle').click()
        expect(page.locator('#sources-list .source-row')).to_have_count(5)
        expect(page.locator('#sources-toggle')).to_have_attribute('aria-expanded', 'true')
        page.locator('#sources-toggle').click()
        expect(page.locator('#sources-list .source-row')).to_have_count(3)
        expect(page.locator('#sources-toggle')).to_have_attribute('aria-expanded', 'false')
        expect(page.locator('#recommendations-section')).to_be_hidden()
        expect(page.locator('#career-chat-open')).to_be_hidden()
        expect(page.locator('#resume-open')).to_be_hidden()
        page.locator('#account-open').click()
        expect(page.locator('#account-register-tab')).to_be_enabled()
        page.locator('#account-register-tab').click()
        page.locator('#account-name').fill('Pessoa de Exemplo')
        page.locator('#account-email').fill('exemplo@example.com')
        page.locator('#account-password').fill('Senha somente para teste!')
        page.locator('#account-submit').click()
        expect(page.locator('#account-open')).to_have_text('Bem-vindo, Pessoa de Exemplo')
        expect(page.locator('#resume-open')).to_be_visible()
        expect(page.locator('#account-profile')).to_be_visible()
        page.locator('#account-close').click()
        page.locator('#career-chat-open').click()
        page.locator('#career-chat-input').fill('Como melhorar os projetos do currículo?')
        page.locator('#career-chat-send').click()
        expect(page.locator('#career-chat-messages')).to_contain_text('Descreva resultados')
        expect(page.locator('#career-chat-messages script')).to_have_count(0)
        page.locator('#career-chat-clear').click()
        expect(page.locator('#career-chat-messages')).to_be_empty()
        page.locator('#career-chat-close').click()
        page.locator('#account-open').click()
        expect(page.locator('#account-password')).to_have_value('')
        form = page.locator('#account-preferences-form')
        form.locator('[name=targetRole]').fill('Desenvolvimento Python')
        form.locator('[name=location]').fill('São Paulo, SP')
        form.locator('[name=workModel]').select_option('remote')
        form.locator('[name=seniority]').select_option('junior')
        form.locator('button[type=submit]').click()
        expect(page.locator('#account-message')).to_have_text('Preferências salvas.')
        expect(page.locator('#account-dialog #resume-file')).to_have_count(0)
        page.locator('#account-close').click()
        page.locator('#resume-open').click()
        expect(page.locator('#resume-dialog')).to_be_visible()
        expect(page.locator('#resume-file')).to_be_enabled()
        page.locator('#resume-file').set_input_files({'name': 'curriculo.pdf', 'mimeType': 'application/pdf',
            'buffer': b'%PDF-1.4 browser fixture; parser tests use real documents'})
        page.locator('#resume-upload').click()
        expect(page.locator('#resume-text')).to_have_value(resume_text)
        page.locator('#resume-close').click()
        page.locator('#account-open').click()
        expect(page.locator('#notifications-enable')).to_be_enabled()
        assert page.evaluate('window.__permissionRequests') == 0
        page.locator('#notifications-enable').click()
        expect(page.locator('#notifications-status')).to_contain_text('Alertas ativados')
        assert state['push'] is True
        page.locator('#notifications-disable').click()
        expect(page.locator('#notifications-status')).to_have_text('Alertas desativados neste dispositivo.')
        assert state['push'] is False
        page.locator('#account-close').click()
        page.locator('#resume-open').click()
        expect(page.locator('#resume-dialog')).to_be_visible()
        expect(page.locator('#recommendations-list')).to_contain_text('Backend Java')
        page.locator('#resume-evaluate').click()
        expect(page.locator('#resume-evaluation-result')).to_contain_text('7,5 / 10')
        expect(page.locator('#resume-evaluation-result script')).to_have_count(0)
        page.locator('#resume-text').fill(resume_text + '\nInglês e comunicação com equipes.')
        page.locator('#resume-review-form button[type=submit]').click()
        expect(page.locator('#resume-status')).to_have_text('Texto do currículo atualizado.')
        page.locator('#resume-heading').scroll_into_view_if_needed()
        page.screenshot(path=str(screenshots / 'desktop.png'))
        page.locator('#resume-close').click()
        page.reload(wait_until='domcontentloaded')
        page.locator('#account-open').click()
        expect(form.locator('[name=targetRole]')).to_have_value('Desenvolvimento Python')
        page.locator('#account-close').click()
        page.locator('#resume-open').click()
        expect(page.locator('#resume-text')).to_have_value(resume_text + '\nInglês e comunicação com equipes.')
        page.set_viewport_size({'width': 390, 'height': 844})
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert page.locator('#resume-dialog').bounding_box()['width'] <= 390
        page.locator('#resume-heading').scroll_into_view_if_needed()
        page.screenshot(path=str(screenshots / 'mobile.png'))
        page.locator('#resume-delete').click()
        page.locator('#resume-delete-cancel').click()
        expect(page.locator('#resume-details')).to_be_visible()
        page.locator('#resume-delete').click()
        page.locator('#resume-delete-yes').click()
        expect(page.locator('#resume-details')).to_be_hidden()
        expect(page.locator('#resume-text')).to_have_value('')
        page.locator('#resume-close').click()
        page.locator('#career-chat-open').click()
        assert page.locator('#career-chat').bounding_box()['width'] <= 390
        page.screenshot(path=str(screenshots / 'mobile-chat.png'))
        page.locator('#career-chat-input').press('Escape')
        expect(page.locator('#career-chat')).to_be_hidden()
        page.locator('#account-open').click()
        page.locator('#account-logout').click()
        expect(page.locator('#account-auth')).to_be_visible()
        expect(page.locator('#recommendations-section')).to_be_hidden()
        expect(page.locator('#career-chat-open')).to_be_hidden()
        expect(page.locator('#resume-open')).to_be_hidden()
        expect(page.locator('#account-open')).to_have_text('Minha conta')
        page.locator('#account-email').fill('exemplo@example.com')
        page.locator('#account-password').fill('Senha incorreta')
        page.locator('#account-submit').click()
        expect(page.locator('#account-message')).to_have_text('E-mail ou senha inválidos.')
        assert page.evaluate('localStorage.length') == 0
        page.locator('#account-recovery').click()
        expect(page.locator('#account-message')).to_contain_text('Se houver uma conta')
        page.locator('#account-resend').click()
        expect(page.locator('#account-message')).to_contain_text('Se houver uma conta')
        state['verification_required'] = True
        page.locator('#account-register-tab').click()
        page.locator('#account-name').fill('Conta com confirmação')
        page.locator('#account-password').fill('Senha sintética longa 123!')
        page.locator('#account-submit').click()
        expect(page.locator('#account-message')).to_have_text('Confirme seu e-mail para entrar.')
        expect(page.locator('#resume-open')).to_be_hidden()
        expect(page.locator('#account-password')).to_have_value('')
        for purpose in ['verify-email', 'reset-password']:
            page.goto(origin + '/#' + purpose + '=' + 'A' * 43, wait_until='domcontentloaded')
            expect(page.locator('#account-link-actions')).to_be_visible()
            assert '#' not in page.url
            if purpose == 'reset-password': page.locator('#account-new-password').fill('Outra senha longa 123!')
            page.locator('#account-link-submit').click()
            expect(page.locator('#account-message')).to_have_text('Senha alterada.' if purpose == 'reset-password' else 'E-mail confirmado.')
            expect(page.locator('#account-link-actions')).to_be_hidden()
        state['verification_required'] = False
        page.locator('#account-register-tab').click()
        page.locator('#account-name').fill('Conta para excluir')
        page.locator('#account-email').fill('excluir@example.invalid')
        page.locator('#account-password').fill('Senha sintética longa 123!')
        page.locator('#account-submit').click()
        expect(page.locator('#account-profile')).to_be_visible()
        page.locator('#account-profile details summary').click()
        delete_form = page.locator('#account-delete-form')
        delete_form.locator('[name=password]').fill('Senha sintética longa 123!')
        delete_form.locator('[name=confirmation]').fill('EXCLUIR')
        delete_form.locator('button').click()
        expect(page.locator('#account-message')).to_contain_text('foram excluídos')
        expect(page.locator('#resume-open')).to_be_hidden()
        page.locator('#account-close').click()
        state['offline'] = True
        page.locator('#account-open').click()
        expect(page.locator('#account-message')).to_have_text('Conta temporariamente indisponível.')
        assert not errors, errors
        assert not any(method == 'GET' and status == 401 for method, path, status in requests), requests
        browser.close()
    print('Interface validada: conta, preferências, upload, revisão, reabertura, remoção, logout e celular.')
    print(f'Pré-visualizações: {screenshots}')
finally:
    server.shutdown()
    server.server_close()
