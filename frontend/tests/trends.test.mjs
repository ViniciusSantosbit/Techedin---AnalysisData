import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareTrends, demoTrends } from '../js/trends.mjs';

test('alinha séries por data e preenche lacunas sem duplicar rótulos', () => {
    const data = prepareTrends([
        { date: '2026-10-12', technologyName: 'Java', mentionCount: 3 },
        { date: '2026-10-11', technologyName: 'Python', mentionCount: 2 },
        { date: '2026-10-11', technologyName: 'Java', mentionCount: 1 }
    ]);
    assert.deepEqual(data.labels, ['2026-10-11', '2026-10-12']);
    assert.deepEqual(data.datasets.find(d => d.label === 'Java').data, [1, 3]);
    assert.deepEqual(data.datasets.find(d => d.label === 'Python').data, [2, 0]);
});
test('não aceita vagas reais como demonstração', () => {
    assert.throws(() => demoTrends([{ is_demo: false }]));
    assert.deepEqual(demoTrends([{ is_demo: true, published_date: '2026-10-12', technologies: ['C#'] }]),
        [{ date: '2026-10-12', technologyName: 'C#', mentionCount: 1 }]);
});
