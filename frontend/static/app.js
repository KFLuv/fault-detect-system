/* ============================================================
   故障检测系统 - 前端逻辑
   对接接口：
     GET  /api/status-codes / /api/scenarios / /api/history / /api/stats
     POST /api/detect / /api/add-scenario
   ============================================================ */

// ---------- 全局状态 ----------
let CATEGORY_COLORS = {};
let CATEGORY_LABELS = {};
let STATUS_CODES = [];
let ALL_SCENARIOS = [];
let LAST_REPORT = null;

// ---------- 通用工具 ----------
const $ = (id) => document.getElementById(id);

async function api(url, options) {
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

function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
}

function catColor(key) {
    return CATEGORY_COLORS[key] || "#8b96ad";
}

// ---------- 初始化 ----------
async function init() {
    bindTabs();
    await loadMeta();
    await loadScenarioFilters();
    await loadStatusCodes();
    await renderScenarios();
    await loadHistory();
    bindCategorySelect();
    setInterval(loadHistory, 30000); // 自动刷新历史
}

function bindTabs() {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => {
            document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
            document.querySelectorAll(".tab-content").forEach((c) => c.classList.remove("active"));
            btn.classList.add("active");
            $("tab-" + btn.dataset.tab).classList.add("active");
        });
    });
}

async function loadMeta() {
    try {
        const stats = await api("/api/stats");
        $("scenarioCount").textContent = stats.total_scenarios;
        $("codeCount").textContent = stats.total_status_codes;
        $("statsBar").innerHTML =
            '<span class="stat-chip">📚 场景库：' + stats.total_scenarios + " 个</span>" +
            '<span class="stat-chip">🔢 状态码：' + stats.total_status_codes + " 个</span>" +
            '<span class="stat-chip">➕ 自定义场景：' + stats.custom_scenarios + " 个</span>" +
            '<span class="stat-chip">🕘 历史记录：' + stats.history_count + " 条</span>";
    } catch (e) {
        console.error("加载统计失败", e);
    }
}

async function loadMetaScenarios() {
    const data = await api("/api/scenarios");
    CATEGORY_LABELS = data.category_labels || {};
    CATEGORY_COLORS = data.category_colors || {};
    ALL_SCENARIOS = data.scenarios || [];
    return data;
}

async function loadScenarioFilters() {
    try {
        const data = await loadMetaScenarios();
        const codeSel = $("scenarioCodeFilter");
        // 状态码下拉（来自状态码库）
        const codes = await api("/api/status-codes");
        codeSel.innerHTML = '<option value="">全部状态码</option>' +
            codes.status_codes.map((c) =>
                '<option value="' + esc(c.code) + '">' + esc(c.code) + " " + esc(c.name) + "</option>"
            ).join("");
        // 归属下拉
        const catSel = $("scenarioCategoryFilter");
        catSel.innerHTML = '<option value="">全部归属</option>' +
            Object.entries(CATEGORY_LABELS).map(([k, v]) =>
                '<option value="' + esc(k) + '">' + esc(v) + "</option>"
            ).join("");
    } catch (e) {
        console.error("加载筛选失败", e);
    }
}

function bindCategorySelect() {
    const sel = $("addRootCause");
    sel.innerHTML = Object.entries(CATEGORY_LABELS)
        .map(([k, v]) => '<option value="' + esc(k) + '">' + esc(v) + "</option>")
        .join("");
}

// ---------- 故障检测 ----------
async function detectFault() {
    const url = $("inputUrl").value.trim();
    if (!url) { alert("请输入故障 URL"); return; }
    const body = {
        url: url,
        symptom: $("inputSymptom").value.trim(),
        enable_service_check: $("optService").checked,
        timeout: parseInt($("optTimeout").value, 10),
    };
    $("loading").classList.remove("hidden");
    $("resultSection").classList.add("hidden");
    try {
        const data = await api("/api/detect", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        LAST_REPORT = data;
        renderResult(data);
        await loadMeta();
        await loadHistory();
    } catch (e) {
        alert("检测失败：" + e.message);
    } finally {
        $("loading").classList.add("hidden");
    }
}

function renderResult(data) {
    $("resultSection").classList.remove("hidden");
    renderStatusBanner(data);
    renderTimeline(data.steps || []);
    renderEvidence(data.evidence_chain || []);
    renderConclusion(data.conclusion || {});
    renderSolutions(data.conclusion || {});
    renderReportTemplate(data.report || {});
    $("resultSection").scrollIntoView({ behavior: "smooth" });
}

function renderStatusBanner(data) {
    const c = data.conclusion || {};
    const color = catColor(c.root_cause);
    const statusText = data.status_text || data.status_code || "无响应";
    $("statusBanner").innerHTML =
        '<div class="code" style="color:' + color + ';border:2px solid ' + color + '">' +
            esc(data.status_code || "—") +
        "</div>" +
        '<div class="verdict">' +
            "<h3>" + esc(statusText) + "</h3>" +
            "<p>报告编号：" + esc(data.report_id) + " · " + esc(data.timestamp) + "</p>" +
            '<span class="verdict-tag" style="background:' + color + "33;color:" + color + '">' +
                "🎯 " + esc(c.root_cause_label || "未确定") +
            "</span>" +
        "</div>";
}

function renderTimeline(steps) {
    const box = $("timelineSteps");
    if (!steps.length) { box.innerHTML = '<div class="empty-tip">暂无检测步骤</div>'; return; }
    box.innerHTML = '<div class="timeline">' + steps.map((s, i) => {
        const icon = { pass: "✓", fail: "✗", info: "i", skip: "⏭", ok: "✓" }[s.result] || "•";
        return '<div class="timeline-item ' + esc(s.result) + '">' +
            '<div class="timeline-dot">' + icon + "</div>" +
            '<div class="timeline-title">' + esc(s.title) + "</div>" +
            '<div class="timeline-action">' + esc(s.action) + "</div>" +
            '<div class="timeline-detail">' + esc(s.detail) + "</div>" +
        "</div>";
    }).join("") + "</div>";
}

function renderEvidence(list) {
    const box = $("evidenceGrid");
    if (!list.length) { box.innerHTML = '<div class="empty-tip">暂无证据</div>'; return; }
    box.innerHTML = list.map((e, i) =>
        '<div class="evidence-item ' + (e.type === "template" ? "template" : "") + '">' +
            '<div class="evidence-title">' + (i + 1) + ". " + esc(e.title) + "</div>" +
            '<div class="evidence-content">' + esc(e.content) + "</div>" +
        "</div>"
    ).join("");
}

function renderConclusion(c) {
    const color = catColor(c.root_cause);
    const conf = Math.round((c.confidence || 0) * 100);
    $("conclusionContent").innerHTML =
        '<div class="conclusion-main">' +
            '<div class="conclusion-box"><div class="label">问题归属</div>' +
                '<div class="value" style="color:' + color + '">' + esc(c.root_cause_label || "未确定") + "</div></div>" +
            '<div class="conclusion-box"><div class="label">匹配场景</div>' +
                '<div class="value">' + esc(c.scenario_id || "—") + " · " + esc(c.scenario_name || "未匹配") + "</div></div>" +
            '<div class="conclusion-box"><div class="label">置信度</div>' +
                '<div class="value confidence" style="color:' + color + '">' + conf + "%</div>" +
                '<div class="confidence-bar"><div class="fill" style="width:' + conf + '%"></div></div></div>' +
        "</div>" +
        '<div class="conclusion-text"><div class="label" style="font-size:12px;color:var(--text-dim)">诊断结论</div>' +
        "<p style='font-size:14px;margin-top:4px'>" + esc(c.conclusion_text) + "</p></div>";
    // 备选场景
    const matches = c.matches || [];
    const box = $("matchAlternatives");
    if (matches.length > 1) {
        box.innerHTML = '<div class="alt-list">' + matches.slice(1).map((m) =>
            '<div class="alt-item"><span>🔁 备选：' + esc(m.id) + " " + esc(m.name) + "</span>" +
            '<span class="alt-score">归属：' + esc(m.root_cause_label) + " · 置信度 " +
            Math.round(m.confidence * 100) + "%</span></div>"
        ).join("") + "</div>";
    } else {
        box.innerHTML = "";
    }
}

function renderSolutions(c) {
    const sols = c.solution || [];
    $("solutionContent").innerHTML = sols.length
        ? '<ul class="solution-list">' + sols.map((s) => "<li>" + esc(s) + "</li>").join("") + "</ul>"
        : '<div class="empty-tip">暂无解决建议，请联系研发确认处理方案</div>';
}

function renderReportTemplate(r) {
    const text =
        "【现象】" + (r.phenomenon || "—") + "\n\n" +
        "【排查过程】\n" + (r.checked || "—") + "\n\n" +
        "【结论】" + (r.conclusion || "—");
    $("reportTemplate").textContent = text;
}

async function copyReport() {
    const el = $("reportTemplate");
    if (!el.textContent) { alert("暂无汇报内容"); return; }
    try {
        await navigator.clipboard.writeText(el.textContent);
        alert("汇报文本已复制到剪贴板");
    } catch (e) {
        // 降级方案：选中文本
        const range = document.createRange();
        range.selectNodeContents(el);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand("copy");
        alert("已复制（请 Ctrl+C 粘贴）");
    }
}

function loadDemo() {
    $("inputUrl").value = "http://192.168.1.100:8081/api/users";
    $("inputSymptom").value = "页面显示空表格，没有任何数据";
    $("optService").checked = true;
}

function clearDetect() {
    $("inputUrl").value = "";
    $("inputSymptom").value = "";
    $("resultSection").classList.add("hidden");
    LAST_REPORT = null;
}

// ---------- 场景库 ----------
function renderScenarios() {
    const kw = ($("scenarioSearch").value || "").trim().toLowerCase();
    const code = $("scenarioCodeFilter").value;
    const cat = $("scenarioCategoryFilter").value;
    let list = ALL_SCENARIOS.filter((s) => {
        if (code && !s.http_codes.includes(code)) return false;
        if (cat && s.root_cause !== cat) return false;
        if (kw) {
            const hay = (s.name + " " + (s.conclusion || "") + " " + (s.id || "")).toLowerCase();
            if (!hay.includes(kw)) return false;
        }
        return true;
    });
    $("scenarioTotal").textContent = "共 " + list.length + " / " + ALL_SCENARIOS.length + " 个场景";
    const box = $("scenarioList");
    if (!list.length) { box.innerHTML = '<div class="empty-tip">没有匹配的场景</div>'; return; }
    box.innerHTML = list.map((s) => {
        const color = catColor(s.root_cause);
        const codes = (s.http_codes || []).map((c) =>
            '<span class="code-tag">' + esc(c) + "</span>"
        ).join("");
        const solutions = (s.solution || []).map((x) => "<li>" + esc(x) + "</li>").join("");
        return '<div class="scenario-card" onclick="this.classList.toggle(\'open\')">' +
            '<div class="sc-head">' +
                '<span class="scenario-id">' + esc(s.id) + "</span>" +
                '<span class="scenario-name">' + esc(s.name) + "</span>" +
                '<span class="sc-tag" style="background:' + color + "22;color:" + color + '">' +
                    esc(CATEGORY_LABELS[s.root_cause] || s.root_cause) + "</span>" +
            "</div>" +
            '<div class="sc-codes">' + codes + "</div>" +
            '<div class="sc-desc">' + esc(s.conclusion || "") + "</div>" +
            '<div class="sc-detail">' +
                '<div class="d-label">🧩 响应特征关键词</div><div>' +
                    ((s.response_patterns || []).map(esc).join("、") || "—") + "</div>" +
                '<div class="d-label">🖥️ 界面症状关键词</div><div>' +
                    ((s.ui_symptoms || []).map(esc).join("、") || "—") + "</div>" +
                '<div class="d-label">💡 解决建议</div><ul>' + solutions + "</ul>" +
            "</div>" +
        "</div>";
    }).join("");
}

// ---------- 状态码 ----------
async function loadStatusCodes() {
    try {
        const data = await api("/api/status-codes");
        STATUS_CODES = data.status_codes || [];
        const box = $("statusCodeList");
        box.innerHTML = STATUS_CODES.map((c) => {
            const cats = (c.problem_category || []).map((x) =>
                '<span style="color:var(--text-dim)">' + esc(x) + "</span>"
            ).join(" · ");
            return '<div class="code-card">' +
                '<div class="code-num">' + esc(c.code) + "</div>" +
                '<div class="code-name">' + esc(c.name) + "</div>" +
                '<div class="code-cat">' + (cats || "—") + "</div>" +
                '<div class="code-desc">' + esc(c.description || "") + "</div>" +
                '<div class="code-memory">📌 ' + esc(c.memory || "") + "</div>" +
            "</div>";
        }).join("");
    } catch (e) {
        console.error("加载状态码失败", e);
    }
}

// ---------- 历史记录 ----------
async function loadHistory() {
    try {
        const data = await api("/api/history");
        const list = data.history || [];
        const box = $("historyList");
        if (!list.length) {
            box.innerHTML = '<div class="empty-tip">暂无检测历史</div>';
            return;
        }
        box.innerHTML = list.map((h) => {
            const color = catColor(guessCategory(h.root_cause));
            return '<div class="history-item">' +
                '<div class="h-status" style="color:' + color + '">' + esc(h.status_code || "—") + "</div>" +
                '<div class="h-info">' +
                    '<div class="h-url">' + esc(h.url) + "</div>" +
                    '<div class="h-meta">' + esc(h.ts) + " · " + esc(h.root_cause || "未分类") +
                    " · " + esc(h.scenario_name || "未匹配") +
                    " · 置信度 " + Math.round((h.confidence || 0) * 100) + "%</div>" +
                "</div>" +
            "</div>";
        }).join("");
    } catch (e) {
        console.error("加载历史失败", e);
    }
}

function guessCategory(label) {
    // 从中文归属标签反查 key
    for (const [k, v] of Object.entries(CATEGORY_LABELS)) {
        if (v === label) return k;
    }
    return "";
}

// ---------- 新增场景 ----------
async function addScenario() {
    const name = $("addName").value.trim();
    const codes = $("addCodes").value.trim();
    if (!name) { alert("请填写场景名称"); return; }
    if (!codes) { alert("请填写状态码"); return; }
    const body = {
        name: name,
        http_codes: codes.split(/[,，\s]+/).filter(Boolean),
        response_patterns: $("addPatterns").value.split(/[,，]+/).map((s) => s.trim()).filter(Boolean),
        ui_symptoms: $("addSymptoms").value.split(/[,，]+/).map((s) => s.trim()).filter(Boolean),
        root_cause: $("addRootCause").value,
        conclusion: $("addConclusion").value.trim(),
        solution: $("addSolution").value.split("\n").map((s) => s.trim()).filter(Boolean),
        priority: "中",
    };
    try {
        const data = await api("/api/add-scenario", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        alert("场景已保存！当前共 " + data.total + " 个场景。");
        $("addName").value = ""; $("addCodes").value = ""; $("addPatterns").value = "";
        $("addSymptoms").value = ""; $("addConclusion").value = ""; $("addSolution").value = "";
        await loadMeta();
        await loadScenarioFilters();
        await renderScenarios();
    } catch (e) {
        alert("保存失败：" + e.message);
    }
}

// ---------- 启动 ----------
document.addEventListener("DOMContentLoaded", init);
