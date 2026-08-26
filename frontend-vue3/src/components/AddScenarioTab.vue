<template>
    <section>
        <div class="card">
            <h2>➕ 新增故障场景</h2>
            <p class="hint">新增场景会立即生效并持久化，便于后续遇到新问题持续补充。</p>

            <div class="form-group">
                <label>场景名称 *</label>
                <input type="text" v-model="form.name" placeholder="例如：短信服务欠费">
            </div>
            <div class="form-row">
                <div class="form-group grow">
                    <label>状态码（逗号分隔）*</label>
                    <input type="text" v-model="form.codes" placeholder="例如：200, 401">
                </div>
                <div class="form-group">
                    <label>问题归属 *</label>
                    <select v-model="form.root_cause">
                        <option v-for="(v, k) in store.categoryLabels" :key="k" :value="k">{{ v }}</option>
                    </select>
                </div>
            </div>
            <div class="form-group">
                <label>响应特征关键词（逗号分隔，命中则加分）</label>
                <input type="text" v-model="form.patterns" placeholder="例如：欠费, balance, insufficient">
            </div>
            <div class="form-group">
                <label>界面症状关键词（逗号分隔）</label>
                <input type="text" v-model="form.symptoms" placeholder="例如：发送失败, 短信发不出">
            </div>
            <div class="form-group">
                <label>结论</label>
                <input type="text" v-model="form.conclusion" placeholder="例如：短信服务欠费导致发送失败">
            </div>
            <div class="form-group">
                <label>解决建议（每行一条）</label>
                <textarea v-model="form.solution" rows="3" placeholder="例如：&#10;联系运营商充值&#10;检查短信余额"></textarea>
            </div>
            <button class="btn btn-primary" @click="save" :disabled="saving">💾 保存场景</button>
        </div>
    </section>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { api, store, loadScenarios } from '../api'

const emit = defineEmits(['saved'])
const saving = ref(false)

const form = reactive({
    name: '', codes: '', patterns: '', symptoms: '',
    root_cause: '', conclusion: '', solution: '',
})

async function save() {
    const name = form.name.trim()
    const codes = form.codes.trim()
    if (!name) { alert('请填写场景名称'); return }
    if (!codes) { alert('请填写状态码'); return }
    if (!form.root_cause) { alert('请选择问题归属'); return }
    saving.value = true
    try {
        const data = await api('/api/add-scenario', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: name,
                http_codes: codes.split(/[,，\s]+/).filter(Boolean),
                response_patterns: form.patterns.split(/[,，]+/).map((s) => s.trim()).filter(Boolean),
                ui_symptoms: form.symptoms.split(/[,，]+/).map((s) => s.trim()).filter(Boolean),
                root_cause: form.root_cause,
                conclusion: form.conclusion.trim(),
                solution: form.solution.split('\n').map((s) => s.trim()).filter(Boolean),
                priority: '中',
            }),
        })
        alert('场景已保存！当前共 ' + data.total + ' 个场景。')
        form.name = ''; form.codes = ''; form.patterns = ''
        form.symptoms = ''; form.conclusion = ''; form.solution = ''
        await loadScenarios()
        emit('saved')
    } catch (e) {
        alert('保存失败：' + e.message)
    } finally {
        saving.value = false
    }
}

onMounted(async () => {
    try {
        if (!Object.keys(store.categoryLabels).length) await loadScenarios()
        form.root_cause = Object.keys(store.categoryLabels)[0] || ''
    } catch (e) {
        console.error('加载归属失败', e)
    }
})
</script>
