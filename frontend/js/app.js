const API_BASE = "http://localhost:8080/api";

async function request(path, options = {}) {
    const response = await fetch(`${API_BASE}${path}`, {
        headers: {
            "Accept": "application/json",
            "Content-Type": "application/json",
            ...(options.headers || {}),
        },
        ...options,
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`HTTP ${response.status}: ${text || response.statusText}`);
    }

    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
        return response.json();
    }

    return response.text();
}

function setEmpty(containerId, message = "Nenhum dado encontrado.") {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = `<p class="empty-state">${message}</p>`;
}

function renderTechnologiesList(containerId, technologies) {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (!Array.isArray(technologies) || technologies.length === 0) {
        setEmpty(containerId, "Nenhuma tecnologia monitorada no momento.");
        return;
    }

    container.innerHTML = technologies
        .map(
            (item) => `
            <div class="metric-card">
                <h3>${escapeHtml(item.name)}</h3>
                <p>Categoria: ${escapeHtml(item.category || "Sem categoria")}</p>
            </div>
        `
        )
        .join("");
}

function renderTrends(containerId, trends) {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (!Array.isArray(trends) || trends.length === 0) {
        setEmpty(containerId, "Sem tendências disponíveis.");
        return;
    }

    const grouped = groupBy(trends, (item) => item.technologyName);

    const html = Object.entries(grouped)
        .map(([technology, records]) => {
            const values = records
                .sort((a, b) => (a.year > b.year ? 1 : a.year < b.year ? -1 : a.month - b.month))
                .map(
                    (record) =>
                        `{"x":"${String(record.month).padStart(2, "0")}/${record.year}","y":${record.mentionCount}}`
                )
                .join(",");

            return `
                <div class="metric-card">
                    <h3>${escapeHtml(technology)}</h3>
                    <div class="chart-container" data-chart='{"type":"line","data":{"datasets":[{"label":"${escapeHtml(technology)}","data":[${values}]}]}}'></div>
                </div>
            `;
        })
        .join("");

    container.innerHTML = html;
}

function renderSeniority(containerId, items) {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (!Array.isArray(items) || items.length === 0) {
        setEmpty(containerId, "Sem dados de senioridade.");
        return;
    }

    const grouped = groupBy(items, (item) => item.seniorityLevel);

    container.innerHTML = Object.entries(grouped)
        .map(([level, records]) => {
            const total = records.reduce((sum, record) => sum + Number(record.mentionCount || 0), 0);
            const top = records.slice().sort((a, b) => Number(b.mentionCount || 0) - Number(a.mentionCount || 0))[0];

            return `
                <div class="metric-card">
                    <h3>${escapeHtml(level)}</h3>
                    <p>Total de menções: ${total}</p>
                    <p>Tecnologia mais citada: ${escapeHtml(top.technologyName || "N/D")} (${top.mentionCount})</p>
                </div>
            `;
        })
        .join("");
}

function renderMetrics(containerId, metrics) {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (!Array.isArray(metrics) || metrics.length === 0) {
        setEmpty(containerId, "Sem métricas para essa tecnologia.");
        return;
    }

    container.innerHTML = metrics
        .map(
            (item) => `
            <div class="metric-card">
                <h3>${escapeHtml(item.name)}</h3>
                <p>Total de menções: ${item.totalMentions}</p>
                <p>Senioridade com maior incidência: ${escapeHtml(item.topSeniority || "N/D")} (${item.topSeniorityMentions})</p>
            </div>
        `
        )
        .join("");
}

function groupBy(array, keySelector) {
    return array.reduce((acc, item) => {
        const key = keySelector(item);
        if (!acc[key]) {
            acc[key] = [];
        }
        acc[key].push(item);
        return acc;
    }, {});
}

function escapeHtml(text = "") {
    return String(text)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

async function loadTechnologies() {
    try {
        const technologies = await request("/technologies");
        renderTechnologiesList("technologies-list", technologies);
    } catch (error) {
        console.error(error);
        setEmpty("technologies-list", `Erro ao carregar tecnologias: ${error.message}`);
    }
}

async function loadTrends() {
    try {
        const trends = await request("/technologies/trends");
        renderTrends("trends-container", trends);
    } catch (error) {
        console.error(error);
        setEmpty("trends-container", `Erro ao carregar tendências: ${error.message}`);
    }
}

async function loadTechnologyMetrics(name) {
    try {
        const metrics = await request(`/technologies/${encodeURIComponent(name)}`);
        renderMetrics("technology-metrics", metrics);
    } catch (error) {
        console.error(error);
        if (error.message.includes("404")) {
            setEmpty("technology-metrics", "Tecnologia não encontrada.");
            return;
        }
        setEmpty("technology-metrics", `Erro ao consultar métricas: ${error.message}`);
    }
}

async function loadSeniorityAnalytics() {
    try {
        const analytics = await request("/analytics/seniority");
        renderSeniority("seniority-container", analytics);
    } catch (error) {
        console.error(error);
        setEmpty("seniority-container", `Erro ao carregar análise de senioridade: ${error.message}`);
    }
}

document.addEventListener("DOMContentLoaded", () => {
    loadTechnologies();
    loadTrends();
    loadSeniorityAnalytics();

    const form = document.getElementById("technology-form");
    if (form) {
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const input = document.getElementById("technology-name");
            const name = input.value.trim();
            if (name) {
                loadTechnologyMetrics(name);
            }
        });
    }
});
