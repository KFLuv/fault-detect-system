<template>
    <section>
        <div class="card">
            <h2>🔢 状态码知识库（{{ store.statusCodes.length }} 个）</h2>
            <div v-if="!store.statusCodes.length" class="empty-tip">加载中...</div>
            <div v-else class="code-grid">
                <div v-for="c in store.statusCodes" :key="c.code" class="code-card">
                    <div class="code-num">{{ c.code }}</div>
                    <div class="code-name">{{ c.name }}</div>
                    <div class="code-cat">
                        <template v-if="c.problem_category && c.problem_category.length">
                            <span v-for="(x, i) in c.problem_category" :key="i" style="color:var(--text-dim)">{{ i > 0 ? ' · ' : '' }}{{ x }}</span>
                        </template>
                        <template v-else>—</template>
                    </div>
                    <div class="code-desc">{{ c.description || '' }}</div>
                    <div class="code-memory">📌 {{ c.memory || '' }}</div>
                </div>
            </div>
        </div>
    </section>
</template>

<script setup>
import { onMounted } from 'vue'
import { store, loadStatusCodes } from '../api'

onMounted(async () => {
    try {
        await loadStatusCodes()
    } catch (e) {
        console.error('加载状态码失败', e)
    }
})
</script>
