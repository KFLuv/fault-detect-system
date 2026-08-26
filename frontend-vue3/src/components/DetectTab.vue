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
            <div class="btn-row">
                <button class="btn btn-primary" @click="detect" :disabled="loading">🚀 开始检测</button>
                <button class="btn btn-ghost" @click="loadDemo">📚 加载示例</button>
                <button class="btn btn-ghost" @click="clear">🔄 清空</button>
            </div>
        </div>

        <!-- ============ 加载中 ============ -->
        <div v-if="loading" class="loading">
            <div class="spinner"></div>
            <p>正在执行 7 步检测流程...</p>
        </div>

        <!-- ============ 检测结果 ============ -->
        <div v-if="report && !loading" class="result-section">
            <div class="status-banner" v-html="bannerHtml"></div>

            <div class="card">
                <h2>⏱️ 检测流程（第 0 步 → 第 5 步）</h2>
                <div v-html="timelineHtml"></div>
            </div>

            <div class="card">
                <h2>📸 证据链</h2>
                <div v-html="evidenceHtml"></div>
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
const form = reactive({ url: '', symptom: '', serviceCheck: true, timeout: '10' })
const loading = ref(false)
const report = ref(null)
const reportEl = ref(null)

// ---------- 检测 ----------
function detect() {
    const url = form.url.trim()
    if (!url) { alert('请输入故障 URL'); return }
    loading.value = true
    api('/api/detect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            url: url,
            symptom: form.symptom.trim(),
            enable_service_check: form.serviceCheck,
            timeout: parseInt(form.timeout, 10),
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
</style>
