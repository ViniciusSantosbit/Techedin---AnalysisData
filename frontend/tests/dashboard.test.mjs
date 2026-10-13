import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSnapshot, summarize, technologySeries, searchJobs } from '../js/dashboard.mjs';

const sample = {
    dataKind: 'real', collectedAt: '2026-10-12T20:00:00Z', sources: [],
    jobs: [
        { title: 'Dev Sênior', company: 'Empresa A', is_demo: false, source_url: 'https://example.com/jobs/1',
          published_date: '2026-10-10', seniority: 'Sênior', technologies: ['Java', 'Java', 'SQL'] },
        { title: 'Dev Python', company: 'Empresa B', is_demo: false, source_url: 'https://example.com/jobs/2',
          published_date: null, seniority: 'Não informado', technologies: ['Python', 'SQL'] }
    ]
};
test('rejeita demonstração e links inválidos', () => {
    assert.throws(() => validateSnapshot({ ...sample, dataKind: 'demo' }));
    assert.throws(() => validateSnapshot({ ...sample, jobs: [{ ...sample.jobs[0], is_demo: true }] }));
    assert.throws(() => validateSnapshot({ ...sample, jobs: [{ ...sample.jobs[0], source_url: 'javascript:alert(1)' }] }));
});
test('conta vagas únicas por tecnologia, usa coleta quando publicação não existe', () => {
    const data = validateSnapshot({ ...sample, jobs: [...sample.jobs, sample.jobs[0]] });
    const result = summarize(data.jobs);
    assert.equal(result.total, 2);
    assert.equal(result.companies, 2);
    assert.equal(result.ranking[0].name, 'SQL');
    assert.equal(result.ranking[0].count, 2);
    assert.equal(data.jobs[1].day, '2026-10-12');
    assert.deepEqual(technologySeries(data.jobs, 'Java'), { labels: ['2026-10-10', '2026-10-12'], values: [1, 0] });
    assert.equal(searchJobs(data.jobs, 'senior', 'SQL').length, 1);
});
