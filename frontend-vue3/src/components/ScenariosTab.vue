<template>
    <section>
        <div class="card">
            <div class="toolbar">
                <input type="text" v-model="keyword" placeholder="搜索场景名称/结论..." @input="applyFilter">
                <select v-model="codeFilter" @change="applyFilter">
                    <option value="">全部状态码</option>
                    <option v-for="c in statusCodeOptions" :key="c.code" :value="c.code">{{ c.code }} {{ c.name }}</option>
                </select>
                <select v-model="catFilter" @change="applyFilter">
                    <option value="">全部归属</option>
                    <option v-for="(v, k) in store.categoryLabels" :key="k" :value="k">{{ v }}</option>
                </select>
                <span class="toolbar-hint">共 {{ filtered.length }} / {{ store.allScenarios.length }} 个场景</span>
            </div>

            <div v-if="!filtered.length" class="empty-tip">没有匹配的场景</div>
            <div v-else class="scenario-list">
                <div v-for="s in filtered" :key="s.id" class="scenario-card"
                     :class="{ open: openSet.has(s.id) }" @click="toggleOpen(s.id)">
                    <div class="sc-head">
                        <span class="scenario-id">{{ s.id }}</span>
                        <span class="scenario-name">{{ s.name }}</span>
                        <span class="sc-tag" :style="{ background: catColor(s.root_cause) + '22', color: catColor(s.root_cause) }">
                            {{ catLabel(s.root_cause) }}
                        </span>
                    </div>
                    <div class="sc-codes">
                        <span v-for="c in s.http_codes || []" :key="c" class="code-tag">{{ c }}</span>
                    </div>
                    <div class="sc-desc">{{ s.conclusion || '' }}</div>
                    <div class="sc-detail">
                        <div class="d-label">🧩 响应特征关键词</div>
                        <div>{{ (s.response_patterns || []).join('、') || '—' }}</div>
                        <div class="d-label">🖥️ 界面症状关键词</div>
                        <div>{{ (s.ui_symptoms || []).join('、') || '—' }}</div>
                        <div class="d-label">💡 解决建议</div>
                        <ul>
                            <li v-for="x in s.solution || []" :key="x">{{ x }}</li>
                        </ul>
                    </div>
                </div>
            </div>
        </div>
    </section>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { store, loadScenarios, loadStatusCodes, catColor, catLabel } from '../api'

const keyword = ref('')
const codeFilter = ref('')
const catFilter = ref('')
const openSet = reactive(new Set())

const statusCodeOptions = computed(() => store.statusCodes)

const filtered = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    return store.allScenarios.filter((s) => {
        if (codeFilter.value && !s.http_codes.includes(codeFilter.value)) return false
        if (catFilter.value && s.root_cause !== catFilter.value) return false
        if (kw) {
            const hay = (s.name + ' ' + (s.conclusion || '') + ' ' + (s.id || '')).toLowerCase()
            if (!hay.includes(kw)) return false
        }
        return true
    })
})

function applyFilter() { /* computed 自动响应 */ }

function toggleOpen(id) {
    if (openSet.has(id)) openSet.delete(id)
    else openSet.add(id)
}

onMounted(async () => {
    try {
        await loadScenarios()
        await loadStatusCodes()
    } catch (e) {
        console.error('加载场景库失败', e)
    }
})
</script>
