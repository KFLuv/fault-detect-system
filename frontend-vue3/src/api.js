// ============================================================
// API 封装与共享状态（与后端 8 个接口对接）
// ============================================================
import { reactive } from 'vue'

// ---------- 共享状态 ----------
export const store = reactive({
    categoryLabels: {},
    categoryColors: {},
    allScenarios: [],
    statusCodes: []
})

// ---------- 通用工具 ----------
export async function api(url, options) {
    const resp = await fetch(url, options);
    if (!resp.ok) {
        let msg = "请求失败（HTTP " + resp.status + "）";
        try {
            const data = await resp.json();
            msg = data.detail || msg;
        } catch (e) { /* ignore */ }
        throw new Error(msg);
    }
    return resp.json();
}

export function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
}

export function catColor(key) {
    return store.categoryColors[key] || "#8b96ad";
}

export function catLabel(key) {
    return store.categoryLabels[key] || key || "未确定";
}

export function guessCategory(label) {
    for (const [k, v] of Object.entries(store.categoryLabels)) {
        if (v === label) return k;
    }
    return "";
}

// ---------- 数据加载 ----------
export async function loadScenarios() {
    const data = await api("/api/scenarios");
    store.categoryLabels = data.category_labels || {};
    store.categoryColors = data.category_colors || {};
    store.allScenarios = data.scenarios || [];
    return data;
}

export async function loadStatusCodes() {
    const data = await api("/api/status-codes");
    store.statusCodes = data.status_codes || [];
    return data;
}

export async function loadAll() {
    await loadScenarios();
    await loadStatusCodes();
}

export async function getStats() {
    return api("/api/stats");
}
