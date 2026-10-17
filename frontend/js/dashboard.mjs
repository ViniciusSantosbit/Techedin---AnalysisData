export function validateSnapshot(payload) {
    if (payload?.dataKind !== 'real' || !Array.isArray(payload.jobs)) {
        throw new Error('O arquivo não contém uma coleta real reconhecida.');
    }
    const unique = new Map();
    for (const job of payload.jobs) {
        if (job.is_demo !== false || !job.title || !Array.isArray(job.technologies)
            || job.technologies.some(name => typeof name !== 'string')) {
            throw new Error('A coleta contém registros inválidos ou de demonstração.');
        }
        const url = new URL(job.source_url);
        if (!['http:', 'https:'].includes(url.protocol) || url.hostname.endsWith('.invalid')) {
            throw new Error('A vaga não possui uma origem pública válida.');
        }
        const fallback = job.first_seen || payload.collectedAt?.slice(0, 10);
        const day = job.published_date || fallback;
        if (!/^\d{4}-\d{2}-\d{2}$/.test(day || '') || Number.isNaN(Date.parse(day))) {
            throw new Error('A coleta contém uma data inválida.');
        }
        unique.set(url.href, { ...job, day, technologies: [...new Set(job.technologies)] });
    }
    return { ...payload, sources: Array.isArray(payload.sources) ? payload.sources : [], jobs: [...unique.values()] };
}

export function summarize(jobs) {
    const counts = new Map();
    const seniorities = new Map();
    for (const job of jobs) {
        for (const name of new Set(job.technologies)) counts.set(name, (counts.get(name) || 0) + 1);
        const level = job.seniority || 'Não informado';
        seniorities.set(level, (seniorities.get(level) || 0) + 1);
    }
    return {
        total: jobs.length,
        companies: new Set(jobs.map(job => job.company).filter(name => name && name !== 'Não informado')).size,
        ranking: [...counts].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name)),
        seniorities: [...seniorities].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count)
    };
}

export function technologySeries(jobs, technology) {
    const days = [...new Set(jobs.map(job => job.day))].sort();
    const counts = new Map();
    for (const job of jobs) {
        if (job.technologies.includes(technology)) counts.set(job.day, (counts.get(job.day) || 0) + 1);
    }
    return { labels: days, values: days.map(day => counts.get(day) || 0) };
}

export function readableDate(day, options = {}) {
    return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', ...options })
        .format(new Date(day + 'T12:00:00'));
}

export function searchJobs(jobs, term, technology) {
    const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const search = normalize(term.trim());
    return jobs.filter(job => (!technology || job.technologies.includes(technology))
        && normalize([job.title, job.company, ...job.technologies].join(' ')).includes(search));
}

export function describeSource(source) {
    const labels = { ok: 'Coletada', cached: 'Em cache', stale: 'Anterior', unavailable: 'Indisponível', empty: 'Sem resultados', stored: 'No banco' };
    let description = `${source.jobCount} vagas desta fonte na amostra`;
    if (source.status === 'stale') description = 'Fonte indisponível; última coleta válida preservada';
    else if (source.status === 'empty') description = 'A consulta não retornou vagas com os critérios atuais';
    else if (source.status === 'unavailable') description = source.message?.includes('403') ? 'Portal respondeu HTTP 403 nesta coleta' : 'Fonte indisponível nesta consulta';
    if (source.name === 'Remotive') description += ' · API com atraso de 24h';
    return { label: labels[source.status] || 'Não verificada', description,
        warning: ['unavailable', 'stale'].includes(source.status) };
}
