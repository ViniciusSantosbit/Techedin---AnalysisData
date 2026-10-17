import { registration as pwaRegistration, pushSupport, publicKeyBytes } from './pwa.mjs?v=20261016-11';
import { resolveApiBase } from './api-config.mjs?v=20261014-01';
const apiBase = resolveApiBase(window.location, window.TECHEDIN_API_BASE);
const $ = id => document.getElementById(id);
let account = null, registering = false, busy = false;
let notificationsGeneration = 0, notificationConfig = null, nativeSubscription = null, notificationSupported = false, notificationsRegistered = false;
let recommendationsGeneration = 0;
let chatResumeRevision = null;
let chatHistory = [], chatGeneration = 0, chatPending = false;
let openResumeAfterLogin = false;
let resume = null, resumeLoaded = false, resumeOwner = null;

function message(text, error = false) {
    $('account-message').textContent = text;
    $('account-message').classList.toggle('error', error);
}
async function request(path, method = 'GET', body) {
    const headers = {};
    if (method !== 'GET') {
        const csrf = await request('/api/auth/csrf');
        headers[csrf.headerName] = csrf.token;
        if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
    }
    let response;
    try {
        response = await fetch(apiBase + path, { method, headers, credentials: 'include', cache: 'no-store',
            signal: AbortSignal.timeout((path.endsWith('/evaluation') || path.endsWith('/career-chat')) ? 55000 : body instanceof FormData ? 30000 : 12000),
            body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body) });
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
    if (resumeOwner !== (account?.id || null)) {
        resumeOwner = account?.id || null; resume = null; resumeLoaded = false; renderResume();
        notificationsGeneration++; notificationConfig = null; nativeSubscription = null; notificationsRegistered = false;
        resetChat(); $('career-chat').hidden = true; $('career-chat-open').setAttribute('aria-expanded', 'false');
    }
    $('account-auth').hidden = !!account;
    $('account-profile').hidden = !account;
    $('account-open').textContent = account ? `Bem-vindo, ${account.displayName}` : 'Minha conta';
    $('resume-open').hidden = !account;
    $('career-chat-open').hidden = !account;
    $('recommendations-section').hidden = !account;
    if (account) { loadRecommendations(); loadNotifications(); }
    else { recommendationsGeneration++; $('recommendations-list').replaceChildren(); $('recommendations-profile').textContent = ''; $('recommendations-status').textContent = ''; }
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
    const buttons = [...document.querySelectorAll('#account-dialog button, #resume-dialog button, #resume-open, #account-open')].filter(button => !['account-close', 'resume-close'].includes(button.id));
    buttons.forEach(button => button.disabled = true);
    message('Aguarde…');
    try { await work(); }
    catch (error) { message(error.message, true); }
    finally { busy = false; buttons.forEach(button => button.disabled = false); syncResumeControls(); syncNotificationButtons(); }
}
$('account-open').addEventListener('click', () => {
    openResumeAfterLogin = false;
    $('account-dialog').showModal();
    action(async () => {
        const session = await request('/api/auth/session');
        account = session.authenticated ? session.account : null;
        message('');
        render();

    });
});
$('account-close').addEventListener('click', () => $('account-dialog').close());
$('account-dialog').addEventListener('close', () => {
    $('account-password').value = ''; $('account-new-password').value = ''; $('account-delete-form').reset();
});
$('account-login-tab').addEventListener('click', () => mode(false));
$('account-register-tab').addEventListener('click', () => mode(true));
$('account-auth-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
        const body = { email: $('account-email').value.trim(), password: $('account-password').value };
        if (registering) body.displayName = $('account-name').value.trim();
        const result = await request('/api/auth/' + (registering ? 'register' : 'login'), 'POST', body);
        if (result.verificationRequired) {
            $('account-password').value = '';
            mode(false); message(result.message); return;
        }
        account = result;
        $('account-auth-form').reset();
        render(); message('Você está conectado. Suas preferências ficam vinculadas à sua conta.');
        if (openResumeAfterLogin) {
            openResumeAfterLogin = false;
            $('account-dialog').close();
            $('resume-dialog').showModal();
            await loadResume();
        }
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
    try { await stopNotifications(); } catch { /* Browser unsubscribe is attempted even when server cleanup fails. */ }
    await request('/api/auth/logout', 'POST');
    account = null; $('account-preferences-form').reset(); $('account-profile-email').value = '';
    mode(false); render(); message('Você saiu da sua conta.');
}));

for (const [id, path] of [['account-recovery', 'password'], ['account-resend', 'verification']]) {
    $(id).addEventListener('click', () => action(async () => {
        const input = $('account-email');
        if (!input.reportValidity() || !input.value.trim()) return;
        const result = await request(`/api/auth/${path}/request`, 'POST', { email: input.value.trim() });
        message(result.message);
    }));
}
$('account-delete-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
        await request('/api/account', 'DELETE', Object.fromEntries(new FormData(event.target)));
        try { await nativeSubscription?.unsubscribe(); } catch { /* Account data is already removed. */ }
        event.target.reset(); $('account-preferences-form').reset(); $('account-profile-email').value = '';
        account = null; mode(false); render(); message('Sua conta e os dados vinculados foram excluídos.');
    });
});
let accountLink = null;
function readAccountLink() {
    if (!/^#(?:verify-email|reset-password)=/.test(window.location.hash)) return;
    const linkMatch = window.location.hash.match(/^#(verify-email|reset-password)=([A-Za-z0-9_-]{43})$/);
    accountLink = linkMatch ? { purpose: linkMatch[1], token: linkMatch[2] } : null;
    history.replaceState(null, '', window.location.pathname + window.location.search);
    $('account-dialog').showModal();
    if (accountLink) {
        const reset = accountLink.purpose === 'reset-password';
        $('account-link-actions').hidden = false;
        $('account-new-password-label').hidden = !reset;
        $('account-new-password').required = reset;
        $('account-link-submit').textContent = reset ? 'Salvar nova senha' : 'Confirmar e-mail';
    } else message('Link inválido. Solicite um novo e-mail.', true);
}
readAccountLink();
window.addEventListener('hashchange', readAccountLink);
$('account-link-form').addEventListener('submit', event => {
    event.preventDefault();
    action(async () => {
        if (!accountLink) return;
        const reset = accountLink.purpose === 'reset-password';
        const body = { token: accountLink.token };
        if (reset) body.password = $('account-new-password').value;
        const result = await request(`/api/auth/${reset ? 'password/reset' : 'verification/confirm'}`, 'POST', body);
        accountLink = null; $('account-link-actions').hidden = true; event.target.reset();
        if (reset) { account = null; render(); }
        message(result.message);
    });
});
request('/api/auth/options').then(options => {
    $('account-local-mail').hidden = !options.localMailPreview;
    $('account-recovery').hidden = !options.emailDeliveryEnabled;
    $('account-resend').hidden = !options.emailDeliveryEnabled || !options.emailConfirmationRequired;
}).catch(() => {});

function resumeMessage(text, error = false) {
    $('resume-status').textContent = text;
    $('resume-status').classList.toggle('error', error);
}
function syncResumeControls() {
    $('resume-upload-controls').disabled = busy || !resumeLoaded;
    $('resume-evaluate').disabled = busy || !resume;
    $('resume-review-controls').disabled = busy || !resume;
}
function renderResume() {
    $('resume-evaluation-result').hidden = true;
    $('resume-evaluation-result').replaceChildren();
    $('resume-evaluation-status').textContent = '';
    $('resume-details').hidden = !resume;
    $('resume-delete-confirm').hidden = true;
    $('resume-upload-form').reset();
    $('resume-upload').textContent = resume ? 'Substituir currículo' : 'Anexar currículo';
    $('resume-text').value = resume?.text || '';
    $('resume-file-info').textContent = resume
        ? `${resume.fileName} · ${Math.ceil(resume.fileSize / 1024)} KB · Enviado em ${new Date(resume.uploadedAt).toLocaleDateString('pt-BR')}` : '';
    $('resume-text-count').textContent = resume ? `${resume.text.length.toLocaleString('pt-BR')} / 100.000 caracteres` : '';
    if (!resume) resumeMessage('');
    syncResumeControls();
}
async function loadResume() {
    resumeLoaded = false; syncResumeControls(); resumeMessage('Carregando currículo…');
    try {
        const result = await request('/api/account/resume');
        resume = result.resume; resumeLoaded = true; renderResume();
        resumeMessage(resume ? 'Texto disponível para revisão.' : 'Você ainda não anexou um currículo.');
        if (resume) {
            try {
                const status = await request('/api/account/resume/evaluation');
                if (!status.configured) $('resume-evaluation-status').textContent = 'Configure GEMINI_API_KEY no servidor e reinicie a API para habilitar a nota.';
            } catch { $('resume-evaluation-status').textContent = 'Clique em avaliar para solicitar a nota.'; }
        }
    } catch (error) { resumeMessage(error.message, true); }
}
function resumeAction(work) {
    return action(async () => {
        syncResumeControls();
        try { await work(); }
        catch (error) {
            resumeMessage(error.message, true);
            if (error.status === 401) { account = null; render(); }
            throw error;
        }
    });
}
$('resume-upload-form').addEventListener('submit', event => {
    event.preventDefault();
    const file = $('resume-file').files[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) { resumeMessage('O currículo deve ter no máximo 5 MB.', true); return; }
    if (!/\.(pdf|docx)$/i.test(file.name)) { resumeMessage('Use um arquivo PDF ou DOCX.', true); return; }
    resumeAction(async () => {
        resumeMessage('Lendo o currículo…');
        const body = new FormData(); body.append('file', file);
        if (resume) body.append('revision', resume.revision);
        const result = await request('/api/account/resume', 'POST', body);
        resume = result.resume; renderResume(); resetChat(); loadRecommendations();
        message('Currículo salvo.'); resumeMessage('Confira abaixo se o texto foi extraído corretamente.');
    });
});
$('resume-review-form').addEventListener('submit', event => {
    event.preventDefault();
    if (!resume) return;
    resumeAction(async () => {
        const result = await request('/api/account/resume', 'PUT', { revision: resume.revision, text: $('resume-text').value });
        resume = result.resume; renderResume(); resetChat(); loadRecommendations(); message('Correções salvas.'); resumeMessage('Texto do currículo atualizado.');
    });
});
$('resume-text').addEventListener('input', () => {
    $('resume-text-count').textContent = `${$('resume-text').value.length.toLocaleString('pt-BR')} / 100.000 caracteres`;
});
$('resume-delete').addEventListener('click', () => { $('resume-delete-confirm').hidden = false; });
$('resume-delete-cancel').addEventListener('click', () => { $('resume-delete-confirm').hidden = true; });
$('resume-delete-yes').addEventListener('click', () => {
    if (!resume) return;
    resumeAction(async () => {
        await request('/api/account/resume', 'DELETE', { revision: resume.revision });
        resume = null; renderResume(); resetChat(); loadRecommendations(); message('Currículo removido.'); resumeMessage('O texto do currículo foi excluído do seu perfil.');
    });
});

$('resume-evaluate').addEventListener('click', () => {
    if (!resume) return;
    if ($('resume-text').value !== resume.text) {
        $('resume-evaluation-status').textContent = 'Salve as correções antes de avaliar.';
        return;
    }
    resumeAction(async () => {
        $('resume-evaluation-result').hidden = true;
        $('resume-evaluation-status').textContent = 'Avaliando com Gemini… Isso pode levar alguns segundos.';
        try {
            const result = await request('/api/account/resume/evaluation', 'POST', { revision: resume.revision });
            const container = $('resume-evaluation-result');
            container.replaceChildren();
            const score = document.createElement('h3');
            score.textContent = `Nota do currículo: ${result.score.toLocaleString('pt-BR')} / 10`;
            container.append(score);
            for (const item of result.criteria) {
                const paragraph = document.createElement('p');
                paragraph.textContent = `${item.name}: ${item.score.toLocaleString('pt-BR')} / 2 — ${item.feedback}`;
                container.append(paragraph);
            }
            const suggestions = document.createElement('p');
            suggestions.textContent = result.suggestions;
            container.append(suggestions); container.hidden = false;
            $('resume-evaluation-status').textContent = 'Avaliação concluída. A nota fica disponível nesta tela até recarregar ou alterar o currículo.';
            message('Currículo avaliado.');
        } catch (error) {
            $('resume-evaluation-status').textContent = error.message;
            throw error;
        }
    });
});
$('resume-text').addEventListener('input', () => {
    $('resume-evaluation-result').hidden = true;
    $('resume-evaluation-status').textContent = 'Salve as correções e avalie novamente para atualizar a nota.';
});

$('resume-open').addEventListener('click', () => action(async () => {
    const session = await request('/api/auth/session');
    account = session.authenticated ? session.account : null;
    render();
    if (!account) {
        openResumeAfterLogin = true;
        $('account-dialog').showModal();
        message('Entre na sua conta para acessar seu currículo.');
        return;
    }
    $('resume-dialog').showModal();
    message('');
    await loadResume();
}));
$('resume-close').addEventListener('click', () => $('resume-dialog').close());

// Restore the header state when an existing session survives a page reload.
request('/api/auth/session').then(session => {
    if (busy) return;
    account = session.authenticated ? session.account : null;
    render();
}).catch(() => { /* Account access displays connection errors when opened. */ });

function resetChat() {
    chatGeneration++; chatHistory = []; chatResumeRevision = null; chatPending = false;
    $('career-chat-messages').replaceChildren();
    $('career-chat-status').textContent = '';
    $('career-chat-input').value = '';
    $('career-chat-send').disabled = false;
}
function chatMessage(role, text) {
    const paragraph = document.createElement('p');
    paragraph.className = 'career-chat-message ' + role;
    paragraph.textContent = (role === 'user' ? 'Você: ' : 'Assistente: ') + text;
    $('career-chat-messages').append(paragraph);
    while ($('career-chat-messages').children.length > 16) $('career-chat-messages').firstElementChild.remove();
    paragraph.scrollIntoView({ block: 'nearest' });
}
$('career-chat-open').addEventListener('click', () => {
    const open = $('career-chat').hidden;
    $('career-chat').hidden = !open;
    $('career-chat-open').setAttribute('aria-expanded', String(open));
    if (open) $('career-chat-input').focus();
});
$('career-chat-close').addEventListener('click', () => {
    $('career-chat').hidden = true;
    $('career-chat-open').setAttribute('aria-expanded', 'false');
    $('career-chat-open').focus();
});
$('career-chat').addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); $('career-chat-close').click(); }
});
$('career-chat-clear').addEventListener('click', resetChat);
$('career-chat-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (chatPending || !account) return;
    const text = $('career-chat-input').value.trim();
    if (!text) return;
    const generation = chatGeneration;
    chatPending = true; $('career-chat-send').disabled = true;
    $('career-chat-status').textContent = 'O assistente está respondendo…';
    try {
        const answer = await request('/api/account/career-chat', 'POST', {
            message: text, history: chatHistory.slice(-8), resumeRevision: chatResumeRevision
        });
        if (generation !== chatGeneration) return;
        if (chatResumeRevision !== answer.resumeRevision) { chatHistory = []; $('career-chat-messages').replaceChildren(); }
        chatResumeRevision = answer.resumeRevision;
        chatMessage('user', text); chatMessage('model', answer.text);
        chatHistory.push({ role: 'user', text }, { role: 'model', text: answer.text });
        chatHistory = chatHistory.slice(-8);
        $('career-chat-input').value = '';
        $('career-chat-status').textContent = '';
    } catch (error) {
        if (generation !== chatGeneration) return;
        $('career-chat-status').textContent = error.message;
        if (error.status === 401) { account = null; render(); }
    } finally {
        if (generation === chatGeneration) { chatPending = false; $('career-chat-send').disabled = false; }
    }
});

async function loadRecommendations() {
    if (!account) return;
    const generation = ++recommendationsGeneration;
    $('recommendations-refresh').disabled = true;
    $('recommendations-list').replaceChildren();
    $('recommendations-profile').textContent = '';
    $('recommendations-status').textContent = 'Comparando seu currículo com as vagas coletadas…';
    try {
        const result = await request('/api/account/recommendations');
        if (generation !== recommendationsGeneration) return;
        if (result.state === 'no_resume') {
            $('recommendations-status').textContent = 'Anexe seu currículo em “Meu currículo” para receber sugestões.';
            return;
        }
        $('recommendations-profile').textContent = 'Tecnologias identificadas no texto: ' + (result.skills.join(', ') || 'nenhuma do catálogo atual') + '.';
        $('recommendations-status').textContent = result.matches.length
            ? `${result.matches.length} sugestões na amostra de ${result.sampleSize} vagas. Ordenadas por correspondência de tecnologias, cargo e senioridade.`
            : 'Não encontramos correspondências nesta amostra. Revise seu currículo e as preferências de senioridade ou aguarde novas vagas.';
        for (const match of result.matches) {
            let url;
            try { url = new URL(match.job.url); if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) continue; } catch { continue; }
            const card = document.createElement('article'); card.className = 'recommendation-card';
            const title = document.createElement('h3'); title.textContent = match.job.title;
            const company = document.createElement('p'); company.className = 'panel-note';
            company.textContent = `${match.job.company} · ${match.job.source} · ${match.job.seniority}`;
            const skills = document.createElement('p'); skills.textContent = 'Em comum: ' + match.matchedSkills.join(', ');
            const reasons = document.createElement('p'); reasons.className = 'panel-note'; reasons.textContent = match.reasons.join(' ');
            card.append(title, company, skills, reasons);
            if (match.otherSkills.length) {
                const missing = document.createElement('p'); missing.className = 'panel-note';
                missing.textContent = 'Também citadas na vaga, não identificadas no currículo: ' + match.otherSkills.join(', ') + '. Confira se são exigências ou diferenciais.';
                card.append(missing);
            }
            const link = document.createElement('a'); link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = 'Conferir vaga na origem ↗';
            card.append(link); $('recommendations-list').append(card);
        }
    } catch (error) {
        if (generation !== recommendationsGeneration) return;
        $('recommendations-status').textContent = error.message;
        if (error.status === 401) { account = null; render(); }
    } finally {
        if (generation === recommendationsGeneration) $('recommendations-refresh').disabled = false;
    }
}
$('recommendations-refresh').addEventListener('click', loadRecommendations);

function syncNotificationButtons() {
    $('notifications-enable').disabled = busy || !notificationSupported || !notificationConfig?.available || notificationsRegistered;
    $('notifications-disable').disabled = busy || !nativeSubscription;
}
async function loadNotifications() {
    const generation = ++notificationsGeneration;
    notificationConfig = null;
    notificationSupported = !pushSupport();
    if (!notificationSupported) {
        $('notifications-status').textContent = pushSupport(); syncNotificationButtons(); return;
    }
    syncNotificationButtons();
    try {
        const config = await request('/api/account/notifications');
        const registration = await pwaRegistration;
        if (generation !== notificationsGeneration) return;
        const subscription = registration ? await registration.pushManager.getSubscription() : null;
        if (generation !== notificationsGeneration) return;
        notificationConfig = config; nativeSubscription = subscription; notificationsRegistered = false;
        if (nativeSubscription && notificationConfig.available) {
            const state = await request('/api/account/notifications/status', 'POST', {endpoint:nativeSubscription.endpoint});
            if (generation !== notificationsGeneration) return;
            notificationsRegistered = state.subscribed;
        }
        $('notifications-status').textContent = !registration ? 'Não foi possível preparar o app. Atualize a página e tente novamente.'
            : !notificationConfig.available ? 'As notificações ainda não estão habilitadas no servidor.'
            : Notification.permission === 'denied' ? 'Notificações bloqueadas no navegador. Altere a permissão nas configurações do site para ativar.'
            : notificationsRegistered ? 'Alertas ativados neste dispositivo. Ao sair da conta, serão desativados aqui.'
            : 'Ative para receber alertas de novas vagas. Você pode desativar a qualquer momento.';
    } catch (error) {
        if (generation === notificationsGeneration) $('notifications-status').textContent = error.message;
    }
    if (generation === notificationsGeneration) syncNotificationButtons();
}
async function stopNotifications() {
    const registration = await pwaRegistration;
    const subscription = await registration?.pushManager?.getSubscription();
    if (!subscription) return;
    // Stop provider delivery first, including when the account session has expired.
    const endpoint = subscription.endpoint;
    const removed = await subscription.unsubscribe();
    if (!removed) throw new Error('O navegador não confirmou a desativação. Tente novamente nas permissões do site.');
    nativeSubscription = null; notificationsRegistered = false;
    await request('/api/account/notifications', 'DELETE', {endpoint});
}
$('notifications-enable').addEventListener('click', () => action(async () => {
    try {
        // Permission is requested directly from the click gesture, before network waits (required on iOS).
        const permission = await Notification.requestPermission();
        if (permission !== 'granted') { $('notifications-status').textContent = 'A permissão não foi concedida. Nenhum alerta foi ativado.'; return; }
        const registration = await pwaRegistration;
        if (!registration || !notificationConfig?.available) throw new Error('Não foi possível preparar as notificações. Reabra o perfil.');
        let subscription = await registration.pushManager.getSubscription();
        if (subscription && !notificationsRegistered) { await subscription.unsubscribe(); subscription = null; }
        subscription ||= await registration.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:publicKeyBytes(notificationConfig.publicKey)});
        try { await request('/api/account/notifications', 'POST', {endpoint:subscription.endpoint}); }
        catch (error) { await subscription.unsubscribe(); throw error; }
        nativeSubscription = subscription; notificationsRegistered = true;
        $('notifications-status').textContent = 'Alertas ativados neste dispositivo. Só novas descobertas compatíveis serão notificadas.';
        message('Notificações ativadas.');
    } catch (error) { $('notifications-status').textContent = error.message; throw error; }
}));
$('notifications-disable').addEventListener('click', () => action(async () => {
    try { await stopNotifications(); $('notifications-status').textContent = 'Alertas desativados neste dispositivo.'; message('Notificações desativadas.'); }
    catch (error) { $('notifications-status').textContent = error.message; throw error; }
}));
