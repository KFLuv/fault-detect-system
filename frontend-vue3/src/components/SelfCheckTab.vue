<template>
    <section>
        <!-- 说明 + 触发 -->
        <div class="card">
            <h2>🧪 系统自检（观测层可信度）</h2>
            <p class="hint">
                排障系统的全部价值建立在「看得准」之上：一旦观测层把 404 错判成超时，后面推理再精致也只是
                一本正经地给出错误结论。本页用两套<strong>互不依赖</strong>的实现，对内置测试服务各探测一轮再比对 ——
                真值走 <code>HttpURLConnection</code>，被测走检测系统实际使用的 <code>HttpClient</code>。
                两者若结论一致，说明观测层可信；不一致的地方会逐条列出，不做掩盖。
            </p>
            <ul class="rules">
                <li>✅ <strong>用例自包含</strong>：内置测试服务只绑 127.0.0.1 随机端口，随自检结束即关闭，换任何机器都能跑</li>
                <li>✅ <strong>只读安全</strong>：全部为 GET 请求，不对任何目标产生写操作</li>
                <li>✅ <strong>可复现</strong>：随时可重跑，结果包含每条的独立真值与实测值，可自行核对</li>
            </ul>
            <div class="btn-row">
                <button class="btn btn-primary" :disabled="running" @click="runCheck">
                    {{ running ? '⏳ 正在自检…' : '▶ 开始自检' }}
                </button>
                <span v-if="error" class="err-tip">❌ {{ error }}</span>
            </div>
        </div>

        <!-- 汇总 -->
        <div v-if="result" class="card">
            <h2>📊 自检结果</h2>
            <div class="summary">
                <div class="sum-box">
                    <div class="label">一致率</div>
                    <div class="value" :class="rateClass">{{ result.consistency }}%</div>
                </div>
                <div class="sum-box">
                    <div class="label">通过 / 总数</div>
                    <div class="value small">{{ result.passed }} / {{ result.total }}</div>
                </div>
                <div class="sum-box">
                    <div class="label">耗时</div>
                    <div class="value small">{{ result.elapsed_ms }} ms</div>
                </div>
                <div class="sum-box">
                    <div class="label">检测时间</div>
                    <div class="value small">{{ result.timestamp }}</div>
                </div>
            </div>

            <div class="src-row">
                <span class="src-tag">真值来源：{{ result.truth_source }}</span>
                <span class="src-tag">被测对象：{{ result.subject_source }}</span>
            </div>

            <div class="verdict-banner" :class="rateClass">
                <template v-if="result.total && result.passed === result.total">
                    ✅ 全部用例一致，观测层可信。
                </template>
                <template v-else-if="result.total">
                    ⚠️ 有 {{ result.total - result.passed }} 条不一致，请对照下方明细核查。
                </template>
                <template v-else>
                    ⚠️ 未能取得任何用例结果，请查看下方说明。
                </template>
            </div>
        </div>

        <!-- 明细 -->
        <div v-if="result && result.observations && result.observations.length" class="card">
            <h2>🔍 逐条明细（{{ result.observations.length }} 条）</h2>
            <div class="obs-list">
                <div v-for="o in result.observations" :key="o.id" class="obs-item" :class="o.match ? 'pass' : 'fail'">
                    <div class="obs-head">
                        <span class="obs-icon">{{ o.match ? '✅' : '❌' }}</span>
                        <span class="obs-id">{{ o.id }}</span>
                        <span class="obs-name">{{ o.name }}</span>
                        <span class="obs-verdict">{{ o.match ? '一致' : '不一致' }}</span>
                    </div>
                    <div class="obs-url">{{ o.url }}</div>
                    <div class="obs-cmp">
                        <span class="cmp-box">
                            <span class="cmp-label">独立真值</span>
                            <span class="cmp-val">{{ o.truth }}</span>
                            <span class="cmp-ms">{{ o.truth_ms }} ms</span>
                        </span>
                        <span class="cmp-arrow">vs</span>
                        <span class="cmp-box">
                            <span class="cmp-label">系统实测</span>
                            <span class="cmp-val">{{ o.actual }}</span>
                            <span class="cmp-ms">{{ o.actual_ms }} ms</span>
                        </span>
                        <span v-if="o.body_ok !== null && o.body_ok !== undefined" class="cmp-box">
                            <span class="cmp-label">响应体中文</span>
                            <span class="cmp-val" :class="o.body_ok ? 'ok' : 'bad'">{{ o.body_ok ? '完整未乱码' : '乱码/缺失' }}</span>
                        </span>
                    </div>
                    <div v-if="o.body_note" class="obs-note">{{ o.body_note }}</div>
                    <div v-if="o.note" class="obs-note">{{ o.note }}</div>
                </div>
            </div>
        </div>

        <!-- 说明 -->
        <div v-if="result && result.notes && result.notes.length" class="card">
            <h2>📌 说明与已知边界</h2>
            <ul class="notes">
                <li v-for="(n, i) in result.notes" :key="i">{{ n }}</li>
            </ul>
        </div>
    </section>
</template>

<script setup>
import { ref, computed } from 'vue'
import { getSelfCheck } from '../api'

const running = ref(false)
const error = ref('')
const result = ref(null)

const rateClass = computed(() => {
    const r = result.value
    if (!r || !r.total) return 'warn'
    if (r.passed === r.total) return 'good'
    if (r.consistency >= 80) return 'warn'
    return 'bad'
})

async function runCheck() {
    running.value = true
    error.value = ''
    try {
        result.value = await getSelfCheck()
    } catch (e) {
        error.value = e.message || String(e)
    } finally {
        running.value = false
    }
}
</script>

<style scoped>
.rules {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 13px;
    color: var(--text-dim);
    margin-bottom: 14px;
}

.err-tip { color: #e05c5c; font-size: 13px; align-self: center; }

.summary {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 12px;
    margin-bottom: 14px;
}

.sum-box {
    background: var(--bg-card-hover);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 12px;
}

.sum-box .label { font-size: 12px; color: var(--text-dim); }
.sum-box .value { font-size: 30px; font-weight: 800; margin-top: 4px; }
.sum-box .value.small { font-size: 17px; font-weight: 700; }
.sum-box .value.good { color: var(--success); }
.sum-box .value.warn { color: #e0a23c; }
.sum-box .value.bad { color: #e05c5c; }

.src-row { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.src-tag {
    font-size: 12px;
    color: var(--text-dim);
    border: 1px dashed var(--border);
    border-radius: 6px;
    padding: 4px 10px;
}

.verdict-banner {
    border-radius: 8px;
    padding: 12px 14px;
    font-size: 14px;
    border-left: 4px solid var(--border);
    background: var(--bg-soft, rgba(255, 255, 255, .04));
}
.verdict-banner.good { border-left-color: var(--success); }
.verdict-banner.warn { border-left-color: #e0a23c; }
.verdict-banner.bad { border-left-color: #e05c5c; }

.obs-list { display: flex; flex-direction: column; gap: 10px; }

.obs-item {
    border: 1px solid var(--border);
    border-left: 4px solid var(--border);
    border-radius: 8px;
    padding: 10px 12px;
}
.obs-item.pass { border-left-color: var(--success); }
.obs-item.fail { border-left-color: #e05c5c; }

.obs-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.obs-id { font-family: Consolas, monospace; font-size: 12px; color: var(--text-dim); }
.obs-name { font-weight: 600; font-size: 14px; }
.obs-verdict { margin-left: auto; font-size: 12px; color: var(--text-dim); }
.obs-url {
    font-family: Consolas, monospace;
    font-size: 12px;
    color: var(--text-dim);
    margin-top: 4px;
    word-break: break-all;
}

.obs-cmp { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 8px; }
.cmp-box {
    display: flex;
    align-items: baseline;
    gap: 6px;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
}
.cmp-label { color: var(--text-dim); }
.cmp-val { font-weight: 700; font-family: Consolas, monospace; }
.cmp-val.ok { color: var(--success); }
.cmp-val.bad { color: #e05c5c; }
.cmp-ms { color: var(--text-dim); }
.cmp-arrow { color: var(--text-dim); font-style: italic; }

.obs-note { margin-top: 6px; font-size: 12px; color: #e0a23c; }

.notes { padding-left: 18px; font-size: 13px; color: var(--text-dim); }
.notes li { margin: 5px 0; }
</style>
