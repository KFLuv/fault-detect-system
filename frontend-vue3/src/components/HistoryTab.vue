<template>
    <section>
        <div class="card">
            <div class="toolbar">
                <h2>🕘 检测历史记录</h2>
                <div class="tb-actions">
                    <button class="btn btn-ghost" @click="load">🔄 刷新</button>
                    <button v-if="list.length" class="btn btn-ghost danger" @click="clearAll">🗑️ 清空历史</button>
                </div>
            </div>
            <div v-if="!list.length" class="empty-tip">暂无检测历史</div>
            <div v-else class="history-list">
                <div v-for="(h, i) in list" :key="i" class="history-item">
                    <div class="h-status" :style="{ color: catColor(guessCategory(h.root_cause)) }">{{ h.status_code || '—' }}</div>
                    <div class="h-info">
                        <div class="h-url">{{ h.url }}</div>
                        <div class="h-meta">
                            {{ h.ts }} · {{ h.root_cause || '未分类' }} · {{ h.scenario_name || '未匹配' }}
                            · 置信度 {{ Math.round((h.confidence || 0) * 100) }}%
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </section>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { api, catColor, guessCategory } from '../api'

const emit = defineEmits(['changed'])
const props = defineProps({
    active: { type: Boolean, default: false }
})
const list = ref([])
let timer = null

// 切到历史页签时立即刷新（解决 v-show 保持挂载、切换后不加载的问题）
watch(() => props.active, (v) => {
    if (v) load()
})

async function load() {
    try {
        const data = await api('/api/history')
        list.value = data.history || []
    } catch (e) {
        console.error('加载历史失败', e)
    }
}

async function clearAll() {
    if (!confirm('确定要清空全部检测历史记录吗？此操作不可恢复。')) return
    try {
        await api('/api/history', { method: 'DELETE' })
        list.value = []
        emit('changed')
    } catch (e) {
        console.error('清空历史失败', e)
        alert('清空历史失败：' + e.message)
    }
}

onMounted(() => {
    load()
    timer = setInterval(load, 30000) // 自动刷新
})

onUnmounted(() => {
    if (timer) clearInterval(timer)
})
</script>
