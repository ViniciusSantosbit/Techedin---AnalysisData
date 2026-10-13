export function prepareTrends(rows) {
    const dates = [...new Set(rows.map(row => row.date))].sort();
    const totals = new Map();
    for (const row of rows) {
        totals.set(row.technologyName, (totals.get(row.technologyName) || 0) + row.mentionCount);
    }
    const technologies = [...totals.keys()].sort((a, b) => totals.get(b) - totals.get(a) || a.localeCompare(b));
    return {
        labels: dates,
        datasets: technologies.map((name, index) => {
            const byDate = new Map();
            for (const row of rows.filter(row => row.technologyName === name)) {
                byDate.set(row.date, (byDate.get(row.date) || 0) + row.mentionCount);
            }
            return { label: name, data: dates.map(day => byDate.get(day) || 0),
                borderColor: `hsl(${(index * 137.5) % 360} 70% 65%)`, tension: 0.15 };
        })
    };
}

export function demoTrends(jobs) {
    if (!Array.isArray(jobs) || !jobs.length || jobs.some(job => job.is_demo !== true
        || !/^\d{4}-\d{2}-\d{2}$/.test(job.published_date) || !Array.isArray(job.technologies)
        || job.technologies.some(name => typeof name !== 'string'))) {
        throw new Error('O arquivo deve conter vagas de demonstração geradas pelo scraper.');
    }
    return jobs.flatMap(job => job.technologies.map(name => ({
        date: job.published_date, technologyName: name, mentionCount: 1
    })));
}
