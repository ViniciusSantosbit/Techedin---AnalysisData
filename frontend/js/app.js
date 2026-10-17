import { validateSnapshot, summarize, technologySeries, readableDate, searchJobs, describeSource } from './dashboard.mjs';
import { resolveApiBase } from './api-config.mjs?v=20261014-01';

const apiBase = resolveApiBase(window.location, window.TECHEDIN_API_BASE);
const $ = id => document.getElementById(id);
let sourcesExpanded = false;
let snapshot, summary, chart, selectedTechnology = '', jobFilter = '', loading = false, visibleJobs = 8;
const theme = getComputedStyle(document.documentElement);
const token = name => theme.getPropertyValue(name).trim();
const palette = ['--color-iris-violet', '--color-sky-blue', '--color-iris-violet-glow',
    '--color-smoke-gray', '--color-iron', '--color-charcoal'].map(token);
function translucent(hex, alpha) {
    const channels = [1, 3, 5].map(start => parseInt(hex.slice(start, start + 2), 16));
    return `rgba(${channels.join(', ')}, ${alpha})`;
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function sourceIcon(name) {
    const icon = element('span', 'source-icon', name[0]);
    const brands = [
        ['sicoob', 'sicoob.ico'], ['santander', 'santander.ico'],
        ['nubank', 'nubank.ico'], ['itaú', 'itau-bankline.ico'], ['itau', 'itau-bankline.ico'],
        ['ifood', 'ifood.ico'], ['empregare', 'empregare.ico'],
        ['remotive', 'remotive.ico'], ['we work remotely', 'wwr.ico'],
        ['gupy', 'gupy.png'], ['catho', 'catho.ico']
    ];
    const brand = brands.find(([label]) => name.toLowerCase().includes(label));
    if (brand) {
        const image = document.createElement('img');
        image.alt = '';
        image.width = image.height = 28;
        image.loading = 'lazy';
        image.addEventListener('error', () => { icon.textContent = name[0]; }, { once: true });
        image.src = 'assets/sources/' + brand[1];
        icon.replaceChildren(image);
    }
    icon.setAttribute('aria-hidden', 'true');
    return icon;
}

async function fetchJson(url, timeout) {
    const response = await fetch(url, { signal: AbortSignal.timeout(timeout), cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
}

function setNotice(text, error = false) {
    $('data-notice').hidden = !text;
    $('data-notice').textContent = text;
    $('data-notice').classList.toggle('error', error);
}

function renderRanking() {
    const list = $('ranking-list');
    list.replaceChildren();
    if (!summary.ranking.length) {
        list.append(element('p', 'empty-message', 'Ainda não encontramos tecnologias nas vagas desta amostra.'));
        return;
    }
    const maximum = summary.ranking[0].count;
    summary.ranking.slice(0, 8).forEach((item, index) => {
        const row = element('button', 'ranking-row' + (item.name === selectedTechnology ? ' active' : ''));
        row.type = 'button';
        row.setAttribute('aria-label', `${item.name}: citada em ${item.count} vagas. Explorar tecnologia.`);
        const track = element('span', 'rank-track');
        const fill = element('span', 'rank-fill');
        fill.style.width = `${item.count / maximum * 100}%`;
        track.append(fill);
        row.append(element('span', 'rank-position', String(index + 1).padStart(2, '0')),
            element('span', 'rank-name', item.name), track, element('span', 'rank-value', item.count));
        row.addEventListener('click', () => { selectTechnology(item.name); jobFilter = item.name; visibleJobs = 8; renderJobs(); });
        list.append(row);
    });
}

function renderChart() {
    if (chart) { chart.destroy(); chart = null; }
    const { labels, values } = technologySeries(snapshot.jobs, selectedTechnology);
    $('chart-message').hidden = !!(labels.length && selectedTechnology);
    $('trendsChart').hidden = !labels.length || !selectedTechnology;
    if (!labels.length || !selectedTechnology) {
        $('chart-message').textContent = 'As próximas coletas preencherão a evolução das tecnologias.';
        return;
    }
    $('period-label').textContent = labels.length > 1
        ? `${readableDate(labels[0])} — ${readableDate(labels.at(-1))}` : readableDate(labels[0]);
    if (!window.Chart) {
        $('trendsChart').hidden = true;
        $('chart-message').hidden = false;
        $('chart-message').textContent = 'Não foi possível carregar o gráfico. Verifique sua conexão e atualize a página.';
        return;
    }
    const ctx = $('trendsChart').getContext('2d');
    const violet = token('--color-iris-violet');
    const gradient = ctx.createLinearGradient(0, 0, 0, $('trendsChart').clientHeight);
    gradient.addColorStop(0, translucent(violet, .3));
    gradient.addColorStop(1, translucent(violet, .01));
    chart = new Chart(ctx, {
        type: labels.length === 1 ? 'bar' : 'line',
        data: { labels, datasets: [{ label: selectedTechnology, data: values,
            borderColor: violet, backgroundColor: gradient, fill: true, tension: .28,
            borderWidth: 2.5, pointRadius: labels.length > 25 ? 0 : 3,
            pointBackgroundColor: token('--color-iris-violet-glow'), pointBorderColor: token('--surface-void'), pointBorderWidth: 2,
            pointHoverRadius: 6, borderRadius: 7, maxBarThickness: 55 }] },
        options: { responsive: true, maintainAspectRatio: false,
            animation: { duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 450 },
            interaction: { intersect: false, mode: 'index' },
            layout: { padding: { top: 10, right: 8 } },
            plugins: { legend: { display: false }, tooltip: {
                backgroundColor: token('--surface-surface-lift'), borderColor: token('--color-charcoal'), borderWidth: 1,
                titleColor: token('--color-white'), bodyColor: token('--color-smoke-gray'), padding: 16,
                titleFont: { family: token('--font-inter'), size: 14 },
                bodyFont: { family: token('--font-inter'), size: 14 },
                displayColors: false, cornerRadius: 8,
                callbacks: { title: items => readableDate(items[0].label, { year: 'numeric' }),
                    label: item => `${selectedTechnology} · ${item.raw} vaga${item.raw === 1 ? '' : 's'}` }
            } },
            scales: {
                x: { grid: { display: false }, border: { display: false },
                    ticks: { color: token('--color-ash-gray'), font: { family: token('--font-commit-mono'), size: 12 }, maxTicksLimit: 6,
                        maxRotation: 0, callback: function (value) { return readableDate(this.getLabelForValue(value)); } } },
                y: { beginAtZero: true, suggestedMax: Math.max(3, Math.ceil(Math.max(...values) * 1.2)),
                    border: { display: false }, grid: { color: token('--color-graphite-hairline'), tickLength: 0 },
                    ticks: { color: token('--color-ash-gray'), padding: 12, precision: 0, maxTicksLimit: 5,
                        font: { family: token('--font-commit-mono'), size: 12 } } }
            }
        }
    });
}

function selectTechnology(name) {
    selectedTechnology = name;
    $('technology-select').value = name;
    renderRanking();
    renderChart();
}

function renderSeniorities() {
    $('seniority-bar').replaceChildren();
    $('seniority-list').replaceChildren();
    summary.seniorities.forEach((item, index) => {
        const color = palette[index % palette.length];
        const segment = element('span', 'seniority-segment');
        segment.style.width = `${item.count / summary.total * 100}%`;
        segment.style.backgroundColor = color;
        $('seniority-bar').append(segment);
        const row = element('div', 'seniority-item');
        const dot = element('i');
        dot.style.backgroundColor = color;
        row.append(dot, element('span', '', item.name), element('b', '', `${item.count} · ${Math.round(item.count / summary.total * 100)}%`));
        $('seniority-list').append(row);
    });
}

function renderSources() {
    $('sources-list').replaceChildren();
    snapshot.sources.slice(0, sourcesExpanded ? undefined : 3).forEach(source => {
        const name = source.name.includes('gupy') ? 'Gupy' : source.name.includes('catho') ? 'Catho' : source.name;
        const row = element('div', 'source-row');
        const meta = element('div', 'source-meta');
        const status = describeSource(source);
        meta.append(element('strong', '', name), element('small', '', status.description));
        row.append(sourceIcon(name), meta,
            element('span', `source-state ${status.warning ? 'unavailable' : source.status === 'stored' ? 'stored' : ''}`, status.label));
        $('sources-list').append(row);
    });
    $('sources-toggle').hidden = snapshot.sources.length <= 3;
    $('sources-toggle').setAttribute('aria-expanded', String(sourcesExpanded));
    $('sources-toggle-label').textContent = sourcesExpanded ? 'Mostrar menos' : `Mostrar todas as fontes (${snapshot.sources.length})`;

}

function renderJobs() {
    const jobs = searchJobs(snapshot.jobs, $('job-search').value, jobFilter)
        .sort((a, b) => b.day.localeCompare(a.day) || a.title.localeCompare(b.title));
    $('jobs-count').textContent = `${jobs.length} vaga${jobs.length === 1 ? '' : 's'}${jobFilter ? ' · ' + jobFilter : ''}`;
    $('clear-filter').hidden = !jobFilter && !$('job-search').value;
    $('jobs-list').replaceChildren();
    if (!jobs.length) $('jobs-list').append(element('p', 'empty-message', 'Nenhuma vaga corresponde a esse filtro.'));
    for (const job of jobs.slice(0, visibleJobs)) {
        const row = element('article', 'job-card');
        const main = element('div', 'job-main');
        const link = element('a', 'job-title', job.title);
        link.href = job.source_url; link.target = '_blank'; link.rel = 'noopener noreferrer';
        const tags = element('div', 'job-tags');
        job.technologies.slice(0, 6).forEach(name => tags.append(element('span', 'job-tag', name)));
        if (job.technologies.length > 6) tags.append(element('span', 'job-tag', `+${job.technologies.length - 6}`));
        main.append(element('span', 'job-company', job.company || 'Empresa não informada'), link, tags);
        const aside = element('div', 'job-aside');
        const source = new URL(job.source_url).hostname.includes('gupy') ? 'Gupy' : new URL(job.source_url).hostname.includes('catho') ? 'Catho' : job.source;
        const open = element('a', 'job-open', 'Ver vaga ↗');
        open.href = job.source_url; open.target = '_blank'; open.rel = 'noopener noreferrer';
        aside.append(element('span', 'job-level', job.seniority || 'Não informado'),
            element('span', '', `${job.published_date ? 'Publicada' : 'Coletada'} ${readableDate(job.day)}`),
            element('span', '', source), open);
        row.append(main, aside);
        $('jobs-list').append(row);
    }
    $('more-jobs').hidden = visibleJobs >= jobs.length;
}

function renderSnapshot(payload, origin) {
    snapshot = validateSnapshot(payload);
    summary = summarize(snapshot.jobs);
    $('stat-jobs').textContent = summary.total;
    $('stat-technologies').textContent = summary.ranking.length;
    $('stat-companies').textContent = summary.companies;
    $('stat-top').textContent = summary.ranking[0]?.name || '—';
    $('stat-top-caption').textContent = summary.ranking.length
        ? `Citada em ${summary.ranking[0].count} de ${summary.total} vagas` : 'Ainda não há tecnologias identificadas';
    $('technology-select').replaceChildren();
    summary.ranking.forEach(item => $('technology-select').append(element('option', '', item.name)));
    $('technology-select').disabled = !summary.ranking.length;
    selectedTechnology = summary.ranking.find(item => item.name === selectedTechnology)?.name || summary.ranking[0]?.name || '';
    selectTechnology(selectedTechnology);
    renderSeniorities(); renderSources(); renderJobs();
    $('data-status').textContent = origin === 'api' ? 'Dados reais · Banco conectado' : 'Dados reais · Coleta dos portais';
    $('data-status').classList.remove('offline');
    const time = snapshot.collectedAt && new Date(snapshot.collectedAt);
    $('last-update').textContent = time && !Number.isNaN(time.getTime())
        ? `Ciclo verificado em ${time.toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo', dateStyle: 'short', timeStyle: 'short' })}` : 'Dados armazenados no banco';
    const partial = snapshot.sources.some(source => ['unavailable', 'stale'].includes(source.status));
    const stale = time && (Date.now() - time.getTime() > 86400000);
    setNotice(partial ? 'Coleta parcial: uma das fontes ficou indisponível. Os números abaixo usam somente as vagas realmente coletadas.'
        : stale ? 'Esta é uma coleta anterior. Execute uma nova coleta para atualizar as oportunidades.' : '');
    $('sample-note').textContent = `${summary.total} vagas reais nesta amostra. Os portais podem ter outras oportunidades que não foram incluídas na coleta.`;
    $('footer-provenance').textContent = origin === 'api' ? 'Dados reais · Banco conectado à API' : 'Dados reais · Snapshot da coleta pública';
}

async function loadRealData() {
    if (loading) return;
    loading = true; $('refresh-btn').disabled = true;
    $('data-status').textContent = 'Atualizando dados…';
    try {
        let payload, origin = 'api';
        try {
            payload = await fetchJson(apiBase + '/api/dashboard', 4000);
            const validated = validateSnapshot(payload);
            if (!validated.jobs.length) {
                try {
                    const exported = await fetchJson('data/jobs.json', 8000);
                    if (validateSnapshot(exported).jobs.length) { payload = exported; origin = 'snapshot'; }
                } catch { /* An empty real database can be displayed when no export exists. */ }
            }
        } catch { payload = await fetchJson('data/jobs.json', 8000); origin = 'snapshot'; }
        renderSnapshot(payload, origin);
    } catch (error) {
        if (snapshot) {
            $('data-status').textContent = 'Última coleta disponível';
            setNotice('Não foi possível atualizar agora. Mantivemos os dados reais da última coleta.', true);
        } else {
            $('data-status').textContent = 'Aguardando coleta real';
            $('data-status').classList.add('offline');
            setNotice('Ainda não há uma coleta real disponível. Execute o coletor de vagas e atualize o painel.', true);
            $('ranking-list').replaceChildren(element('p', 'empty-message', 'O ranking aparecerá após a primeira coleta.'));
            $('chart-message').hidden = false;
            $('chart-message').textContent = 'Aguardando dados reais das vagas.';
            $('trendsChart').hidden = true;
            $('jobs-list').replaceChildren(element('p', 'empty-message', 'Nenhuma coleta disponível.'));
        }
    } finally { loading = false; $('refresh-btn').disabled = false; }
}

$('refresh-btn').addEventListener('click', loadRealData);
$('technology-select').addEventListener('change', event => selectTechnology(event.target.value));
$('job-search').addEventListener('input', () => { if (snapshot) { visibleJobs = 8; renderJobs(); } });
$('clear-filter').addEventListener('click', () => { jobFilter = ''; $('job-search').value = ''; visibleJobs = 8; renderJobs(); });
$('more-jobs').addEventListener('click', () => { visibleJobs += 8; renderJobs(); });
loadRealData();

$('sources-toggle').addEventListener('click', () => {
    sourcesExpanded = !sourcesExpanded;
    renderSources();
});
