import { resolveApiBase } from './api-config.mjs?v=20261014-01';
const apiBase = resolveApiBase(window.location, window.TECHEDIN_API_BASE);
const $ = id => document.getElementById(id);
let account = null, registering = false, busy = false;

function message(text, error = false) {
    $('account-message').textContent = text;
    $('account-message').classList.toggle('error', error);
}
async function request(path, method = 'GET', body) {
    const headers = {};
    if (method !== 'GET') {
        const csrf = await request('/api/auth/csrf');
        headers[csrf.headerName] = csrf.token;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
    }
    let response;
    try {
        response = await fetch(apiBase + path, { method, headers, credentials: 'include', cache: 'no-store',
            signal: AbortSignal.timeout(12000), body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
        throw new Error('Não foi possível conectar à sua conta. Tente novamente em alguns instantes.');
    }
    const result = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        const error = new Error(result?.message || 'Não foi possível concluir a solicitação. Tente novamente.');
        error.status = response.status;
        throw error;
    }
    return result;
}
function render() {
    $('account-auth').hidden = !!account;
    $('account-profile').hidden = !account;
    $('account-open').textContent = account ? 'Meu perfil' : 'Minha conta';
    $('account-heading').textContent = account ? 'Seu perfil profissional' : 'Sua próxima oportunidade';
    if (!account) return;
    const form = $('account-preferences-form');
    form.elements.displayName.value = account.displayName;
    $('account-profile-email').value = account.email;
    for (const key of ['targetRole', 'location', 'workModel', 'seniority']) {
        form.elements[key].value = account.preferences[key];
    }
}
function mode(value) {
    registering = value;
    $('account-name-label').hidden = !value;
    $('account-name').disabled = !value;
    $('account-name').required = value;
    $('account-password-hint').hidden = !value;
    $('account-password').minLength = value ? 12 : 1;
    $('account-password').autocomplete = value ? 'new-password' : 'current-password';
    $('account-submit').textContent = value ? 'Criar conta' : 'Entrar';
    $('account-login-tab').setAttribute('aria-pressed', String(!value));
    $('account-register-tab').setAttribute('aria-pressed', String(value));
    message('');
}
async function action(work) {
    if (busy) return;
    busy = true;
    const buttons = [...$('account-dialog').querySelectorAll('button')].filter(button => button.id !== 'account-close');
    buttons.forEach(button => button.disabled = true);
    message('Aguarde…');
    try { await work(); }
    catch (error) { message(error.message, true); }
    finally { busy = false; buttons.forEach(button => button.disabled = false); }
}
$('account-open').addEventListener('click', () => {
    $('account-dialog').showModal();
    action(async () => {
        const session = await request('/api/auth/session');
        account = session.authenticated ? session.account : null;
        message('');
        render();
    });
});
$('account-close').addEventListener('click', () => $('account-dialog').close());
$('account-dialog').addEventListener('close', () => { $('account-password').value = ''; });
$('account-login-tab').addEventListener('click', () => mode(false));
$('account-register-tab').addEventListener('click', () => mode(true));
$('account-auth-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
        const body = { email: $('account-email').value.trim(), password: $('account-password').value };
        if (registering) body.displayName = $('account-name').value.trim();
        account = await request('/api/auth/' + (registering ? 'register' : 'login'), 'POST', body);
        $('account-auth-form').reset();
        render(); message('Você está conectado. Suas preferências ficam vinculadas à sua conta.');
    });
});
$('account-preferences-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
        try {
            account = await request('/api/account/preferences', 'PUT', Object.fromEntries(new FormData(event.target)));
        } catch (error) {
            if (error.status === 401) { account = null; render(); }
            throw error;
        }
        render(); message('Preferências salvas.');
    });
});
$('account-logout').addEventListener('click', () => action(async () => {
    await request('/api/auth/logout', 'POST');
    account = null; $('account-preferences-form').reset(); $('account-profile-email').value = '';
    mode(false); render(); message('Você saiu da sua conta.');
}));
