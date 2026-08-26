<template>
    <header class="app-header">
        <div class="header-inner">
            <h1>🔍 故障检测系统</h1>
            <p class="subtitle">输入 URL 一键诊断 · 32 状态码 × 92 场景 · 证据链闭环</p>
            <div class="stats-bar">
                <span class="stat-chip">📚 场景库：{{ stats.total_scenarios ?? '—' }} 个</span>
                <span class="stat-chip">🔢 状态码：{{ stats.total_status_codes ?? '—' }} 个</span>
                <span class="stat-chip">➕ 自定义场景：{{ stats.custom_scenarios ?? '—' }} 个</span>
                <span class="stat-chip">🕘 历史记录：{{ stats.history_count ?? '—' }} 条</span>
                <span class="stats-actions">
                    <button class="icon-btn" @click="toggleTheme" :title="theme === 'dark' ? '切换到白天模式' : '切换到夜晚模式'">
                        {{ theme === 'dark' ? '☀️ 白天模式' : '🌙 夜晚模式' }}
                    </button>
                    <button class="icon-btn" @click="refreshPage" title="刷新页面数据">🔄 刷新页面</button>
                </span>
            </div>
        </div>
    </header>

    <nav class="tab-nav">
        <button class="tab-btn" :class="{ active: tab === 'detect' }" @click="tab = 'detect'">🛠️ 故障检测</button>
        <button class="tab-btn" :class="{ active: tab === 'scenarios' }" @click="tab = 'scenarios'">
            📚 场景库 <span class="badge">{{ stats.total_scenarios ?? '' }}</span>
        </button>
        <button class="tab-btn" :class="{ active: tab === 'status-codes' }" @click="tab = 'status-codes'">
            🔢 状态码 <span class="badge">{{ stats.total_status_codes ?? '' }}</span>
        </button>
        <button class="tab-btn" :class="{ active: tab === 'history' }" @click="tab = 'history'">🕘 历史记录</button>
        <button class="tab-btn" :class="{ active: tab === 'add' }" @click="tab = 'add'">➕ 新增场景</button>
    </nav>

    <main class="container">
        <DetectTab v-show="tab === 'detect'" @changed="reloadStats" />
        <ScenariosTab v-show="tab === 'scenarios'" />
        <StatusCodesTab v-show="tab === 'status-codes'" />
        <HistoryTab v-show="tab === 'history'" :active="tab === 'history'" @changed="reloadStats" />
        <AddScenarioTab v-show="tab === 'add'" @saved="reloadStats" />
    </main>

    <footer class="app-footer">
        故障检测系统 v2.0 · Vue3 前端 · 基于《功能排障标准 SOP（92 场景整合版）》 · 适用驻场实施
    </footer>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import DetectTab from './components/DetectTab.vue'
import ScenariosTab from './components/ScenariosTab.vue'
import StatusCodesTab from './components/StatusCodesTab.vue'
import HistoryTab from './components/HistoryTab.vue'
import AddScenarioTab from './components/AddScenarioTab.vue'
import { getStats } from './api'

const tab = ref('detect')
const stats = ref({})

// ---------- 日夜模式切换（持久化到 localStorage） ----------
const theme = ref(localStorage.getItem('fds-theme') || 'dark')
watch(theme, (v) => {
    document.body.dataset.theme = v
    localStorage.setItem('fds-theme', v)
}, { immediate: true })

function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

// ---------- 刷新页面 ----------
function refreshPage() {
    window.location.reload()
}

async function reloadStats() {
    try {
        stats.value = await getStats()
    } catch (e) {
        console.error('加载统计失败', e)
    }
}

onMounted(reloadStats)
</script>
