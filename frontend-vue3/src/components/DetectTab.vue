<template>
    <section>
        <!-- ============ 输入区 ============ -->
        <div class="card input-card">
            <h2>📝 输入故障信息</h2>
            <div class="form-row">
                <div class="form-group grow">
                    <label>故障 URL（必填）</label>
                    <input type="text" v-model="form.url" placeholder="例如：http://192.168.1.100:8081/api/users">
                </div>
            </div>
            <div class="form-row">
                <div class="form-group grow">
                    <label>故障现象 / 症状（可选，帮助精准匹配）</label>
                    <input type="text" v-model="form.symptom" placeholder="例如：页面显示空表格 / 提示未登录 / 请求超时">
                </div>
                <div class="form-group">
                    <label>检测选项</label>
                    <label class="checkbox"><input type="checkbox" v-model="form.serviceCheck"> 服务存活检测（TCP）</label>
                    <label class="checkbox"><input type="checkbox" v-model="form.contrastCheck"> 🔬 差分隔离实验（对照推导）</label>
                    <p class="hint" style="margin:4px 0 0;font-size:12px;">
                        开启后会对同一 URL 追加<b>最多 7 次只读</b>对照请求（<span class="mono">GET</span> / <span class="mono">HEAD</span>），
                        每次只改变一个变量，用对照组之间的差异反推故障层次。<b>不会发送任何写请求</b>。
                    </p>
                    <label class="checkbox"><input type="checkbox" v-model="form.llmCheck"> 🤖 LLM 增强分析（可选层，默认关闭）</label>
                    <p class="hint" style="margin:4px 0 0;font-size:12px;">
                        开启后把本次<b>观测事实 + 差分结论 + 通用假设</b>发给后端配置的大模型，请它补充根因判断、验证动作与反证条件。
                        需后端已配置 <span class="mono">faultdetect.llm.*</span>；<b>内网不可达或调用失败会自动降级</b>，不影响已有结论。
                    </p>
                </div>
                <div class="form-group">
                    <label>超时时间</label>
                    <select v-model="form.timeout">
                        <option value="10">10 秒</option>
                        <option value="5">5 秒</option>
                        <option value="15">15 秒</option>
                        <option value="30">30 秒</option>
                    </select>
                </div>
            </div>
            <!-- 认证 / 登录凭证（可选） -->
            <div class="form-group" style="margin-bottom: 12px;">
                <a class="hdr-toggle" @click="form.headersOpen = !form.headersOpen">
                    {{ form.headersOpen ? '▲ 收起认证设置' : '▼ 🔑 接口需要登录/Token？点这里填凭证（可选）' }}
                </a>
                <div v-if="form.headersOpen" class="hdr-box">
                    <div class="hdr-field">
                        <label>Cookie 值</label>
                        <input v-model="form.cookieVal" placeholder="从 F12 请求头复制粘贴，如 JSESSIONID=xxxxxx（直接粘，不用加引号）" />
                    </div>
                    <div class="hdr-field">
                        <label>Authorization 前缀（scheme）</label>
                        <select v-model="form.authScheme">
                            <option value="">不加前缀（原样发送）</option>
                            <option value="Bearer">Bearer</option>
                            <option value="Basic">Basic</option>
                            <option value="Token">Token</option>
                            <option value="JWT">JWT</option>
                            <option value="ApiKey">ApiKey</option>
                            <option value="__custom">自定义…</option>
                        </select>
                        <input v-if="form.authScheme === '__custom'" v-model="form.authSchemeCustom" placeholder="自定义前缀，如 Xxx" />
                    </div>
                    <div class="hdr-field">
                        <label>Token 本体（不要带前缀）</label>
                        <input v-model="form.tokenVal" placeholder="从 F12 复制 Authorization 的值，只填 token 本体，前缀在上面选" />
                    </div>
                    <p v-if="authPreview" class="hint mono-preview">将发送：<span class="mono">{{ authPreview }}</span></p>
                    <p class="hint">
                        <b>怎么分辨前缀：</b>F12 → Network → 点该请求 → Request Headers，看 <span class="mono">Authorization</span> 的<b>值的第一个单词</b>，
                        那就是前缀（<span class="mono">Bearer</span> / <span class="mono">Basic</span> / <span class="mono">Token</span> …），照着选即可。<br>
                        <b>如果压根没有 <span class="mono">Authorization</span> 这个头</b>，两种可能：该接口用裸 token（选“不加前缀”），或它根本不用这个头（改用上面的 Cookie 值）。
                    </p>
                </div>
            </div>
            <div class="btn-row">
                <button class="btn btn-primary" @click="detect" :disabled="loading">🚀 开始检测</button>
                <button class="btn btn-ghost" @click="loadDemo">📚 加载示例</button>
                <button class="btn btn-ghost" @click="clear">🔄 清空</button>
            </div>
        </div>

        <!-- ============ 加载中 ============ -->
        <div v-if="loading" class="loading">
            <div class="spinner"></div>
            <p>正在执行检测流程（含差分对照与假设推理{{ form.llmCheck ? '，并等待 LLM 增强分析' : '' }}）...</p>
        </div>

        <!-- ============ 检测结果 ============ -->
        <div v-if="report && !loading" class="result-section">
            <div class="status-banner" v-html="bannerHtml"></div>

            <div class="card">
                <h2>⏱️ 检测流程（{{ stepRangeText }}）</h2>
                <div v-html="timelineHtml"></div>
            </div>

            <div class="card">
                <h2>📸 证据链</h2>
                <div v-html="evidenceHtml"></div>
            </div>

            <!-- ============ 差分隔离实验（P1） ============ -->
            <div v-if="hasContrast" class="card contrast-card">
                <h2>🔬 差分隔离实验（对照推导，不靠状态码查表）</h2>
                <p class="hint">
                    对同一 URL 每次只改变一个变量并重放，用<b>对照组之间的差异</b>反推故障所在层次。
                    全部使用幂等只读方法（<span class="mono">GET</span> / <span class="mono">HEAD</span>），不会向目标发送任何写请求。
                </p>
                <div v-html="contrastHtml"></div>
            </div>

            <!-- ============ 通用假设推理（P2） ============ -->
            <div v-if="hasHypotheses" class="card hypo-card">
                <h2>🧠 通用假设推理（多假设排序，不靠状态码查表）</h2>
                <p class="hint">
                    把本次观测到的事实（状态码 / 响应关键信息 / 异常类名 / 差分结果）喂给<b>通用技术规则库</b>，
                    按置信度排出多个候选假设。每条假设都给出<b>支持证据</b>、<b>反证条件</b>与<b>可执行验证动作</b>——
                    即使知识库里没有对应场景（例如 417），也能得到可行动的结论。
                </p>
                <div v-html="hypothesesHtml"></div>
            </div>

            <!-- ============ 可选 LLM 增强层（P3） ============ -->
            <div v-if="hasLlm" class="card llm-card">
                <h2>🤖 LLM 增强分析（可选层，默认关闭）</h2>
                <p class="hint">
                    <b>补充层</b>，不替代上面的差分推导与通用规则结论。只有当通用规则、差分推导、知识库三条路径
                    都没能给出任何假设时，LLM 的结论才会升格为主结论；其余情况仅并列展示，
                    <b>采信前请先按「可执行验证动作」复核</b>。内网不可达或调用失败会自动降级，
                    此时检测结果与未开启该层完全一致。
                </p>
                <div v-html="llmHtml"></div>
            </div>

            <div class="card conclusion-card">
                <h2>🎯 诊断结论</h2>
                <div v-html="conclusionHtml"></div>
                <div v-if="alternativesHtml" class="alt-list" v-html="alternativesHtml"></div>
            </div>

            <div class="card">
                <h2>💡 解决建议</h2>
                <div v-html="solutionsHtml"></div>
            </div>

            <div class="card">
                <h2>📝 汇报模板（3 段式）</h2>
                <div class="report-template" ref="reportEl">{{ reportText }}</div>
                <div class="btn-row">
                    <button class="btn btn-primary" @click="copyReport">📋 复制汇报文本</button>
                </div>
            </div>

            <!-- ============ 动态教学：对应本次故障 ============ -->
            <div class="card teach-dyn-card">
                <h2>📖 本次故障 · 手动排查教学</h2>
                <p class="hint">
                    系统已自动帮你完成排查，下面按本次故障（状态码 + 归属）展示<b>手动版</b>应该怎么做——
                    作为实习生无需执行，重点是<b>看懂流程、学会方法</b>。
                </p>

                <div v-if="teachCard" class="teach-dyn">
                    <div class="teach-dyn-head">{{ teachCard.title }}</div>
                    <ol class="teach-dyn-steps">
                        <li v-for="(s, i) in teachCard.steps" :key="'s' + i" v-html="s"></li>
                    </ol>
                </div>

                <div v-if="catCard" class="teach-dyn">
                    <div class="teach-dyn-head">{{ catCard.title }}</div>
                    <ol class="teach-dyn-steps">
                        <li v-for="(s, i) in catCard.steps" :key="'c' + i" v-html="s"></li>
                    </ol>
                </div>

                <div class="teach-toggle" @click="teachOpen = !teachOpen">
                    {{ teachOpen ? '▲ 收起完整 7 步手动排障总纲' : '▼ 展开完整 7 步手动排障总纲（系统学习用）' }}
                </div>
                <div v-if="teachOpen" class="teach-full">
                    <TeachingTab />
                </div>
            </div>
        </div>
    </section>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { api, esc, catColor, catLabel } from '../api'
import { pickStatusTeach, pickCategoryTeach } from '../teaching-data'
import TeachingTab from './TeachingTab.vue'

const emit = defineEmits(['changed'])
const form = reactive({ url: '', symptom: '', serviceCheck: true, contrastCheck: true, llmCheck: false, timeout: '10', cookieVal: '', tokenVal: '', authScheme: '', authSchemeCustom: '', headersOpen: false })
const loading = ref(false)
const report = ref(null)
const reportEl = ref(null)

// ---------- Authorization 前缀拼接 ----------
// 按所选前缀拼出 Authorization 的值；若粘贴的内容已是「同名前缀 + 值」，则不重复拼接
function buildAuthValue() {
    const raw = form.tokenVal.trim()
    if (!raw) return ''
    const scheme = (form.authScheme === '__custom' ? form.authSchemeCustom : form.authScheme).trim()
    if (!scheme) return raw
    return raw.toLowerCase().startsWith((scheme + ' ').toLowerCase()) ? raw : scheme + ' ' + raw
}

// 实时预览实际发出去的 Authorization，切换前缀立刻可见
const authPreview = computed(() => {
    const v = buildAuthValue()
    if (!v) return ''
    const shown = v.length > 60 ? v.slice(0, 60) + '…' : v
    return 'Authorization: ' + shown + (v.length > 60 ? '（共 ' + v.length + ' 字符）' : '')
})

// ---------- 检测 ----------
function detect() {
    const url = form.url.trim()
    if (!url) { alert('请输入故障 URL'); return }
    // 登录凭证（Cookie / Token）：Authorization 按上面所选前缀拼接，不再自动补 Bearer
    const cookie = form.cookieVal.trim()
    let headers = null
    if (cookie || form.tokenVal.trim()) {
        headers = {}
        if (cookie) headers['Cookie'] = cookie
        const authValue = buildAuthValue()
        if (authValue) headers['Authorization'] = authValue
    }
    loading.value = true
    api('/api/detect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            url: url,
            symptom: form.symptom.trim(),
            enable_service_check: form.serviceCheck,
            enable_contrast: form.contrastCheck,
            // 【P3】可选 LLM 增强层：默认 false，不勾选时后端不会发起任何外部请求
            enable_llm: form.llmCheck,
            timeout: parseInt(form.timeout, 10),
            headers: headers,
        }),
    }).then((data) => {
        report.value = data
        emit('changed')
    }).catch((e) => {
        alert('检测失败：' + e.message)
    }).finally(() => {
        loading.value = false
    })
}

// ---------- 渲染（与原版 innerHTML 逻辑一致，内容已 esc 防 XSS）----------
const bannerHtml = computed(() => {
    const c = report.value.conclusion || {}
    const color = catColor(c.root_cause)
    const statusText = report.value.status_text || report.value.status_code || '无响应'
    return (
        '<div class="code" style="color:' + color + ';border:2px solid ' + color + '">' +
            esc(report.value.status_code || '—') +
        '</div>' +
        '<div class="verdict">' +
            '<h3>' + esc(statusText) + '</h3>' +
            '<p>报告编号：' + esc(report.value.report_id) + ' · ' + esc(report.value.timestamp) + '</p>' +
            '<span class="verdict-tag" style="background:' + color + '33;color:' + color + '">' +
                '🎯 ' + esc(c.root_cause_label || '未确定') +
            '</span>' +
        '</div>'
    )
})

const timelineHtml = computed(() => {
    const steps = report.value.steps || []
    if (!steps.length) return '<div class="empty-tip">暂无检测步骤</div>'
    return '<div class="timeline">' + steps.map((s) => {
        const icon = { pass: '✓', fail: '✗', info: 'i', skip: '⏭', ok: '✓' }[s.result] || '•'
        return '<div class="timeline-item ' + esc(s.result) + '">' +
            '<div class="timeline-dot">' + icon + '</div>' +
            '<div class="timeline-title">' + esc(s.title) + '</div>' +
            '<div class="timeline-action">' + esc(s.action) + '</div>' +
            '<div class="timeline-detail">' + esc(s.detail) + '</div>' +
        '</div>'
    }).join('') + '</div>'
})

// 检测流程的步骤范围随实际返回动态变化（开启差分实验时会出现第 6 步）
const stepRangeText = computed(() => {
    const steps = (report.value && report.value.steps) || []
    const max = steps.length ? Math.max(...steps.map((s) => Number(s.step) || 0)) : 5
    return '第 0 步 → 第 ' + max + ' 步'
})

const evidenceHtml = computed(() => {
    const list = report.value.evidence_chain || []
    if (!list.length) return '<div class="empty-tip">暂无证据</div>'
    return list.map((e, i) =>
        '<div class="evidence-item ' + (e.type === 'template' ? 'template' : '') + '">' +
            '<div class="evidence-title">' + (i + 1) + '. ' + esc(e.title) + '</div>' +
            '<div class="evidence-content">' + esc(e.content) + '</div>' +
        '</div>'
    ).join('')
})

// ---------- 差分隔离实验（P1）----------
const hasContrast = computed(() => {
    const ct = (report.value && report.value.contrast) || {}
    return !!(ct.experiments && ct.experiments.length)
})

const contrastHtml = computed(() => {
    const ct = report.value.contrast || {}
    const exps = ct.experiments || []
    const rows = exps.map((e) => {
        const cls = e.result === 'ok' ? 'ct-ok' : (e.result === 'skip' ? 'ct-skip' : 'ct-fail')
        return '<tr class="' + cls + '">' +
            '<td class="mono">' + esc(e.id) + '</td>' +
            '<td>' + esc(e.title) + '</td>' +
            '<td>' + esc(e.change) + '</td>' +
            '<td class="mono">' + esc(e.method) + '</td>' +
            '<td class="mono">' + esc(e.observed) + '</td>' +
        '</tr>'
    }).join('')
    const concls = (ct.conclusions || []).map((c) =>
        '<div class="dc-item">' +
            '<div class="dc-head">' +
                '<span class="dc-id mono">' + esc(c.id) + '</span>' +
                '<span class="dc-title">' + esc(c.title) + '</span>' +
                '<span class="dc-conf">置信度 ' + Math.round((c.confidence || 0) * 100) + '%</span>' +
            '</div>' +
            '<div class="dc-text">' + esc(c.derived) + '</div>' +
            '<div class="dc-basis">推导依据：' + esc(c.basis) + '</div>' +
        '</div>'
    ).join('')
    const notes = (ct.notes || []).map((n) => '<li>' + esc(n) + '</li>').join('')
    return (
        '<p class="ct-base"><b>基线</b>：<span class="mono">' + esc(ct.baseline || '—') + '</span></p>' +
        '<div class="ct-table-wrap"><table class="ct-table">' +
            '<thead><tr><th>编号</th><th>对照实验</th><th>改变了什么</th><th>方法</th><th>观测结果</th></tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
        '</table></div>' +
        '<div class="ct-concl-title">差分推导结论</div>' +
        (concls || '<div class="empty-tip">本次对照未产生可推导结论</div>') +
        (notes ? '<ul class="ct-notes">' + notes + '</ul>' : '')
    )
})

// ---------- 通用假设推理（P2）----------
const hasHypotheses = computed(() => {
    const list = (report.value && report.value.hypotheses) || []
    return list.length > 0
})

const hypothesesHtml = computed(() => {
    const list = (report.value && report.value.hypotheses) || []
    if (!list.length) return ''
    return list.map((h, i) => {
        const p = Math.round((h.confidence || 0) * 100)
        const src = { rule: '通用规则', contrast: '差分推导', knowledge: '场景库', llm: 'LLM 增强' }[h.source] || h.source || ''
        const ev = (h.supported_by || []).map((s) => '<li>' + esc(s) + '</li>').join('')
        const rf = (h.refute_if || []).map((s) => '<li>' + esc(s) + '</li>').join('')
        const va = (h.verify_actions || []).map((s) => '<li>' + esc(s) + '</li>').join('')
        return '<div class="hypo-item' + (i === 0 ? ' top' : '') + '">' +
            '<div class="hypo-head">' +
                '<span class="hypo-rank">#' + (i + 1) + '</span>' +
                '<span class="hypo-cat" style="background:' + catColor(h.category) + '">' + esc(h.category_label) + '</span>' +
                '<span class="hypo-title">' + esc(h.title) + '</span>' +
                '<span class="hypo-src">' + esc(src) + '</span>' +
                '<span class="hypo-conf">' + p + '%</span>' +
            '</div>' +
            '<div class="hypo-bar"><i style="width:' + p + '%"></i></div>' +
            '<div class="hypo-desc">' + esc(h.description) + '</div>' +
            (ev ? '<div class="hypo-sec"><b>支持证据</b><ul>' + ev + '</ul></div>' : '') +
            (rf ? '<div class="hypo-sec refute"><b>反证条件</b><ul>' + rf + '</ul></div>' : '') +
            (va ? '<div class="hypo-sec verify"><b>可执行验证动作</b><ul>' + va + '</ul></div>' : '') +
        '</div>'
    }).join('')
})

// ---------- 可选 LLM 增强层（P3）----------
// 只在「本次确实请求了 LLM」时才显示卡片（后端字段 requested 由 enable_llm 决定），
// 未启用 / 未配置 / 已降级都如实呈现，避免让使用者误以为结论有 LLM 背书。
const hasLlm = computed(() => {
    const l = (report.value && report.value.llm) || null
    return !!(l && l.requested)
})

const llmHtml = computed(() => {
    const l = (report.value && report.value.llm) || {}
    const st = l.status || 'disabled'
    const badge = { ok: '调用成功', degraded: '已自动降级', disabled: '未启用' }[st] || st
    let h = '<div class="llm-head">' +
        '<span class="llm-badge ' + esc(st) + '">' + esc(badge) + '</span>' +
        (l.model ? '<span class="llm-meta">模型：' + esc(l.model) + '</span>' : '') +
        (l.latency_ms ? '<span class="llm-meta">耗时 ' + l.latency_ms + 'ms</span>' : '') +
    '</div>'
    if (l.reason) h += '<div class="llm-reason">' + esc(l.reason) + '</div>'
    if (st === 'ok') {
        const va = (l.verify_actions || []).map((s) => '<li>' + esc(s) + '</li>').join('')
        const rf = (l.refute_if || []).map((s) => '<li>' + esc(s) + '</li>').join('')
        h += '<div class="llm-concl">' +
                '<span class="llm-cat" style="background:' + catColor(l.root_cause) + '">' + esc(l.root_cause_label || '未确定') + '</span>' +
                '<span class="llm-conf">置信度 ' + Math.round((l.confidence || 0) * 100) + '%</span>' +
            '</div>' +
            '<div class="llm-text">' + esc(l.conclusion_text) + '</div>' +
            (l.reasoning ? '<div class="llm-sec"><b>推理链</b><p>' + esc(l.reasoning) + '</p></div>' : '') +
            (va ? '<div class="llm-sec verify"><b>可执行验证动作</b><ul>' + va + '</ul></div>' : '') +
            (rf ? '<div class="llm-sec refute"><b>反证条件</b><ul>' + rf + '</ul></div>' : '')
    } else if (l.raw) {
        h += '<div class="llm-raw">' + esc(l.raw) + '</div>'
    }
    return h
})

// 结论来源四档标注：知识库 / 差分推导 / 通用规则 / LLM 增强
const sourceText = (c) => {
    const names = { knowledge: '知识库快速路径', contrast: '差分推导', rule: '通用规则', llm: 'LLM 增强', none: '未确定' }
    const list = c.reasoning_sources || []
    if (list.length) return list.map((s) => names[s] || s).join(' + ')
    return names[c.reasoning_source] || '未确定'
}

const conclusionHtml = computed(() => {
    const c = report.value.conclusion || {}
    const color = catColor(c.root_cause)
    const conf = Math.round((c.confidence || 0) * 100)
    return (
        '<div class="conclusion-main">' +
            '<div class="conclusion-box"><div class="label">问题归属</div>' +
                '<div class="value" style="color:' + color + '">' + esc(c.root_cause_label || '未确定') + '</div></div>' +
            '<div class="conclusion-box"><div class="label">匹配场景</div>' +
                '<div class="value">' + esc(c.scenario_id || '—') + ' · ' + esc(c.scenario_name || '未匹配') + '</div></div>' +
            '<div class="conclusion-box"><div class="label">结论来源</div>' +
                '<div class="value" style="font-size:13px">' + esc(sourceText(c)) + '</div></div>' +
            '<div class="conclusion-box"><div class="label">置信度</div>' +
                '<div class="value confidence" style="color:' + color + '">' + conf + '%</div>' +
                '<div class="confidence-bar"><div class="fill" style="width:' + conf + '%"></div></div></div>' +
        '</div>' +
        '<div class="conclusion-text"><div class="label" style="font-size:12px;color:var(--text-dim)">诊断结论</div>' +
        "<p style='font-size:14px;margin-top:4px'>" + esc(c.conclusion_text) + '</p></div>'
    )
})

const alternativesHtml = computed(() => {
    const matches = (report.value.conclusion || {}).matches || []
    if (matches.length <= 1) return ''
    return matches.slice(1).map((m) =>
        '<div class="alt-item"><span>🔁 备选：' + esc(m.id) + ' ' + esc(m.name) + '</span>' +
        '<span class="alt-score">归属：' + esc(m.root_cause_label) + ' · 置信度 ' +
        Math.round(m.confidence * 100) + '%</span></div>'
    ).join('')
})

const solutionsHtml = computed(() => {
    const sols = (report.value.conclusion || {}).solution || []
    return sols.length
        ? '<ul class="solution-list">' + sols.map((s) => '<li>' + esc(s) + '</li>').join('') + '</ul>'
        : '<div class="empty-tip">暂无解决建议，请联系研发确认处理方案</div>'
})

const reportText = computed(() => {
    const r = report.value.report || {}
    return (
        '【现象】' + (r.phenomenon || '—') + '\n\n' +
        '【排查过程】\n' + (r.checked || '—') + '\n\n' +
        '【结论】' + (r.conclusion || '—')
    )
})

// ---------- 动态教学（对应本次故障）----------
const teachOpen = ref(false)
const teachCard = computed(() => (report.value ? pickStatusTeach(report.value.status_code) : null))
const catCard = computed(() => (report.value ? pickCategoryTeach((report.value.conclusion || {}).root_cause) : null))

// ---------- 复制汇报 ----------
async function copyReport() {
    if (!reportText.value) { alert('暂无汇报内容'); return }
    try {
        await navigator.clipboard.writeText(reportText.value)
        alert('汇报文本已复制到剪贴板')
    } catch (e) {
        const range = document.createRange()
        range.selectNodeContents(reportEl.value)
        const sel = window.getSelection()
        sel.removeAllRanges()
        sel.addRange(range)
        document.execCommand('copy')
        alert('已复制（请 Ctrl+C 粘贴）')
    }
}

// ---------- 示例 / 清空 ----------
function loadDemo() {
    form.url = 'http://192.168.1.100:8081/api/users'
    form.symptom = '页面显示空表格，没有任何数据'
    form.serviceCheck = true
}

function clear() {
    form.url = ''
    form.symptom = ''
    form.cookieVal = ''
    form.tokenVal = ''
    form.authScheme = ''
    form.authSchemeCustom = ''
    form.contrastCheck = true
    form.llmCheck = false
    report.value = null
}
</script>

<style scoped>
/* 动态教学卡 */
.teach-dyn-card .hint { font-size: 13px; color: var(--text-dim); margin-bottom: 12px; }
.teach-dyn {
    background: var(--bg-inset);
    border: 1px solid var(--border);
    border-left: 3px solid var(--primary);
    border-radius: 8px;
    padding: 12px 16px;
    margin-bottom: 12px;
}
.teach-dyn-head { font-size: 14px; font-weight: 700; margin-bottom: 8px; }
.teach-dyn-steps { padding-left: 20px; font-size: 13.5px; }
.teach-dyn-steps li { margin: 6px 0; line-height: 1.9; }
.mono { font-family: Consolas, monospace; background: var(--bg-card); padding: 1px 6px; border-radius: 4px; font-size: 12.5px; }
.teach-toggle {
    margin-top: 6px;
    padding: 8px 12px;
    font-size: 13px;
    color: var(--primary);
    cursor: pointer;
    text-align: center;
    border: 1px dashed var(--border);
    border-radius: 8px;
    transition: background 0.2s;
}
.teach-toggle:hover { background: var(--bg-inset); }
.teach-full { margin-top: 12px; }

/* 差分隔离实验卡 */
.contrast-card .hint { font-size: 13px; color: var(--text-dim); margin-bottom: 12px; }
.ct-base { font-size: 13.5px; margin-bottom: 12px; }
.ct-table-wrap { overflow-x: auto; margin-bottom: 14px; }
.ct-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.ct-table th,
.ct-table td { border: 1px solid var(--border); padding: 7px 10px; text-align: left; vertical-align: top; }
.ct-table th { background: var(--bg-inset); font-weight: 700; white-space: nowrap; }
.ct-table td:nth-child(3) { line-height: 1.7; }
.ct-table tr.ct-skip td { opacity: 0.55; }
.ct-table tr.ct-fail td:nth-child(5) { color: var(--warn, #d98a2b); }
.ct-concl-title {
    font-size: 13.5px;
    font-weight: 700;
    margin: 14px 0 8px;
    padding-left: 8px;
    border-left: 3px solid var(--primary);
}
.dc-item {
    background: var(--bg-inset);
    border: 1px solid var(--border);
    border-left: 3px solid var(--primary);
    border-radius: 8px;
    padding: 10px 14px;
    margin-bottom: 10px;
}
.dc-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.dc-id { font-size: 12px; color: var(--primary); }
.dc-title { font-size: 13.5px; font-weight: 700; }
.dc-conf { margin-left: auto; font-size: 12px; color: var(--text-dim); white-space: nowrap; }
.dc-text { font-size: 13.5px; line-height: 1.8; }
.dc-basis { font-size: 12px; color: var(--text-dim); margin-top: 6px; }
.ct-notes { margin-top: 10px; padding-left: 20px; font-size: 12px; color: var(--text-dim); line-height: 1.8; }

/* 通用假设推理卡 */
.hypo-card .hint { font-size: 13px; color: var(--text-dim); margin-bottom: 12px; }
.hypo-item {
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 14px;
    margin-bottom: 10px;
    background: var(--bg-inset);
}
.hypo-item.top { border-left: 3px solid var(--primary); }
.hypo-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.hypo-rank { font-size: 12px; color: var(--text-dim); }
.hypo-cat {
    font-size: 11px;
    color: #1a1a1a;
    font-weight: 700;
    padding: 1px 7px;
    border-radius: 10px;
    white-space: nowrap;
}
.hypo-title { font-size: 13.5px; font-weight: 700; }
.hypo-src { font-size: 11px; color: var(--text-dim); border: 1px solid var(--border); padding: 0 6px; border-radius: 8px; white-space: nowrap; }
.hypo-conf { margin-left: auto; font-size: 12.5px; font-weight: 700; color: var(--primary); white-space: nowrap; }
.hypo-bar { height: 4px; background: var(--border); border-radius: 2px; overflow: hidden; margin-bottom: 8px; }
.hypo-bar i { display: block; height: 100%; background: var(--primary); }
.hypo-desc { font-size: 13.5px; line-height: 1.8; }
.hypo-sec { margin-top: 8px; font-size: 12.5px; line-height: 1.8; }
.hypo-sec b { font-size: 12px; }
.hypo-sec ul { margin: 3px 0 0; padding-left: 20px; color: var(--text-dim); }
.hypo-sec.refute b { color: var(--warn, #d98a2b); }
.hypo-sec.verify b { color: var(--primary); }

/* 可选 LLM 增强层卡 */
.llm-card .hint { font-size: 13px; color: var(--text-dim); margin-bottom: 12px; }
.llm-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.llm-badge {
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 10px;
    white-space: nowrap;
    border: 1px solid var(--border);
}
.llm-badge.ok { background: #1f7a4d22; color: #2e9e6b; border-color: #2e9e6b55; }
.llm-badge.degraded { background: #d98a2b22; color: var(--warn, #d98a2b); border-color: #d98a2b55; }
.llm-badge.disabled { color: var(--text-dim); }
.llm-meta { font-size: 11.5px; color: var(--text-dim); }
.llm-reason {
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--text-dim);
    background: var(--bg-inset);
    border-radius: 8px;
    padding: 8px 12px;
    margin-bottom: 10px;
}
.llm-concl { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.llm-cat {
    font-size: 11px;
    color: #1a1a1a;
    font-weight: 700;
    padding: 1px 7px;
    border-radius: 10px;
    white-space: nowrap;
}
.llm-conf { font-size: 12.5px; font-weight: 700; color: var(--primary); }
.llm-text { font-size: 13.5px; line-height: 1.8; }
.llm-sec { margin-top: 8px; font-size: 12.5px; line-height: 1.8; }
.llm-sec b { font-size: 12px; }
.llm-sec p { margin: 3px 0 0; color: var(--text-dim); }
.llm-sec ul { margin: 3px 0 0; padding-left: 20px; color: var(--text-dim); }
.llm-sec.refute b { color: var(--warn, #d98a2b); }
.llm-sec.verify b { color: var(--primary); }
.llm-raw {
    font-family: Consolas, monospace;
    font-size: 12px;
    line-height: 1.7;
    color: var(--text-dim);
    background: var(--bg-inset);
    border-radius: 8px;
    padding: 8px 12px;
    white-space: pre-wrap;
    word-break: break-all;
}
</style>
