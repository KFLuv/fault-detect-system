<template>
    <section>
        <div class="card">
            <h2>📖 手动排障教学指南</h2>
            <p class="hint">
                本系统能帮你<strong>自动完成</strong>全套排查。但作为实施实习生，你必须掌握<strong>没有系统时</strong>怎么一步步手动排障。
                本模块与系统自动检测的每一步<strong>一一对应</strong>——先看懂自动检测报告，再对照本章节学会手动操作。
                建议按章节顺序学习，第 1 次花 30 分钟通读，之后遇到问题按「第 6 章速查」定位。
            </p>
        </div>

        <div v-for="(ch, i) in chapters" :key="i" class="card teach-card"
             :class="{ open: openCh === i }" @click="toggleCh(i)">
            <h2>{{ ch.icon }} {{ ch.title }}</h2>
            <div class="teach-intro">{{ ch.intro }}</div>

            <div class="teach-body">
                <div v-for="(sec, j) in ch.sections" :key="j" class="teach-sec">
                    <div class="teach-sec-head">{{ sec.head }}</div>
                    <div v-if="sec.cmd" class="teach-cmd">{{ sec.cmd }}</div>
                    <ul v-if="sec.lines" class="teach-list">
                        <li v-for="(ln, k) in sec.lines" :key="k" v-html="ln"></li>
                    </ul>
                </div>

                <div v-if="ch.tips && ch.tips.length" class="teach-tips">
                    <div class="teach-tips-head">💡 实习要点</div>
                    <ul>
                        <li v-for="(t, k) in ch.tips" :key="k">{{ t }}</li>
                    </ul>
                </div>
            </div>
        </div>
    </section>
</template>

<script setup>
import { ref } from 'vue'

const openCh = ref(null)

function toggleCh(i) {
    openCh.value = openCh.value === i ? null : i
}

const chapters = [
    // ============ 第 0 章 ============
    {
        icon: '🛠️', title: '第 0 章 · 准备工作：认识你的工具箱',
        intro: '手动排障之前，先准备齐下面这些工具，并知道它们各自能干什么。',
        sections: [
            {
                head: '① 浏览器开发者工具（最重要）',
                cmd: 'Chrome / Edge 里按 F12（或右键 → 检查）',
                lines: [
                    '<b>Network（网络）面板</b>：看到页面发出的每一个请求。排障 90% 时间在这里。<br>打开方式：F12 → 点"Network"标签。',
                    '<b>Console（控制台）</b>：显示 JS 报错，前端问题最先在这里露馅。',
                    '<b>Sources（源代码）</b>：看前端代码、打断点调试。',
                ],
            },
            {
                head: '② 命令窗口',
                cmd: 'Win + R → 输入 cmd 回车（或 PowerShell）',
                lines: [
                    '用于：ping 测试网络、Test-NetConnection 测端口、curl 发请求、tail 看日志、mysql 查库。',
                ],
            },
            {
                head: '③ Postman（可选但强烈建议装）',
                lines: [
                    '发"裸请求"的利器——绕开浏览器，直接看后端真实响应。',
                    '用于：复现接口、看真实状态码和响应体、区分前后端问题。',
                ],
            },
            {
                head: '④ 截图工具',
                cmd: 'Win + Shift + S（区域截图）',
                lines: [
                    '证据要留痕：请求 URL、状态码、响应体、报错信息，全部截图保存。',
                ],
            },
        ],
        tips: [
            '工具不在多，F12 + 命令窗口 + Postman 三样足够解决 95% 问题。',
            '养成习惯：排障第一步永远是 F12 开 Network，不是瞎猜。',
        ],
    },

    // ============ 第 1 章 ============
    {
        icon: '🎯', title: '第 1 章 · 拿到故障 URL（F12 操作详解）',
        intro: '系统自动检测时你只需要"粘贴 URL"。手动排障时，URL 要自己从浏览器里拿——这是第一步，也是最重要的一步。',
        sections: [
            {
                head: '① 打开 Network 面板',
                lines: [
                    'F12 → 点 <b>Network</b> 标签 → 左上角红色圆点（Record）要处于开启状态（红色=记录中）。',
                ],
            },
            {
                head: '② 触发故障操作',
                lines: [
                    '回到页面，<b>重新执行一次报错的操作</b>（刷新页面 / 点按钮 / 提交表单）。',
                    'Network 列表会实时出现新的请求。',
                ],
            },
            {
                head: '③ 找出故障请求',
                lines: [
                    '按 <b>红色</b> 或 <b>Status 列非 200</b> 的请求优先排查（红色=请求失败）。',
                    '按 <b>Name / 类型</b> 过滤：通常接口请求是 <b>Fetch/XHR</b> 类型，HTML/图片/JS 一般不是故障源。',
                    '确认请求方法与路径：GET /api/users、POST /api/login 等。',
                ],
            },
            {
                head: '④ 复制 URL',
                cmd: '在请求上右键 → Copy → Copy link address（或 Copy as cURL）',
                lines: [
                    '这就是"故障 URL"——系统检测、Postman 复现都用它。',
                    '顺便记下：<b>请求方法</b>（GET/POST）、<b>请求头</b>（Headers 标签）、<b>请求体</b>（Payload 标签），Postman 复现要用。',
                ],
            },
        ],
        tips: [
            '故障不一定只有一条请求，多条失败时先修"最先失败"的那条（上游）。',
            'F12 面板要开着再触发操作，否则抓不到请求（操作前先点 F12）。',
        ],
    },

    // ============ 第 2 章 ============
    {
        icon: '🧪', title: '第 2 章 · 手动 7 步排查（对应系统自动检测）',
        intro: '系统自动检测就是这 7 步的"自动化版本"。下面教你每一步手动怎么做、看什么、怎么判断。',
        sections: [
            {
                head: '第 0 步 · 服务存活检测：后端服务到底通不通',
                cmd: 'Test-NetConnection 主机IP -Port 端口',
                lines: [
                    '示例：<span class="mono">Test-NetConnection 192.168.1.100 -Port 8081</span>',
                    '看 <b>TcpTestSucceeded</b>：True=端口通（服务在听）；False=端口不通（服务没起 / 网络不通 / 防火墙拦截）。',
                    '<b>判断</b>：不通 → 先排查服务进程和端口，别急着查代码。',
                ],
            },
            {
                head: '第 1 步 · HTTP 请求探测：后端到底回了什么',
                cmd: '方式A：浏览器直接访问 URL    方式B：Postman 发请求（推荐）',
                lines: [
                    '用 Postman 粘贴 URL → 选对方法（GET/POST）→ 填好 Headers/Body → Send。',
                    '看三样东西：<b>状态码</b>、<b>响应体</b>、<b>响应时间</b>。',
                    '状态码不是 2xx → 按第 2 步对照速查表；是 2xx → 看响应体数据是否正常。',
                ],
            },
            {
                head: '第 2 步 · 状态码分析：查速查表',
                cmd: '对照本系统「🔢 状态码」页签（32 个全量速查）',
                lines: [
                    '200 正常 → 重点看返回的数据；401 未授权 → 查 Token/登录态；404 接口不存在 → 后端没这个接口；500 服务器内部错误 → 必须查后端日志；504 网关超时 → 慢 SQL / 网关配置。',
                ],
            },
            {
                head: '第 3 步 · 响应数据分析：看响应体里的"蛛丝马迹"',
                lines: [
                    '是 <b>JSON</b> 还是 HTML？JSON 里 <b>data</b> 是否空数组？<b>code / message</b> 字段写了什么？',
                    '用 <b>Ctrl+F</b> 在响应体里搜特征词：exception / timeout / forbidden / 权限 / 未登录 / SQL 等。',
                    '<b>判断</b>：空数组 → 数据库问题；报具体业务错误 → 业务逻辑问题；HTML 错误页 → 网关/服务器问题。',
                ],
            },
            {
                head: '第 4 步 · 证据收集：固定 3 样',
                cmd: '截图：请求 URL / 状态码 / 响应体',
                lines: [
                    '手动排障也要留证据：请求 URL、请求方法、状态码、响应体、报错时间。',
                    '证据要能<b>复现</b>——研发拿到 URL 一测就能看到同样的问题，沟通效率翻倍。',
                ],
            },
            {
                head: '第 5 步 · 日志 / 数据库分析（按状态码分支）',
                cmd: '500 → tail -f logs/app.log | grep -E "Exception|Error"',
                lines: [
                    '<b>500 错误</b>：去后端服务器看日志，找到 Exception 堆栈，定位第一个 <b>Caused by</b>，记下类名和行号。',
                    '<b>200 + 空数据</b>：连数据库验证表和数据：<span class="mono">mysql -u root -p</span> → <span class="mono">SELECT * FROM 表名 LIMIT 10</span>',
                    '<b>其他状态码</b>：先做前后端隔离验证（见第 3 章），再决定查哪边。',
                ],
            },
        ],
        tips: [
            '第 0 步和第 1 步系统是并行做的（更快），手动时也要同时开两个窗口对比。',
            '手动排障口诀：<b>先通后正</b>——先确认网络/服务通不通（第 0 步），再分析响应对不对（第 1-3 步）。',
        ],
    },

    // ============ 第 3 章 ============
    {
        icon: '🔀', title: '第 3 章 · 前后端隔离判断（Postman 教学）',
        intro: '页面报错时，最关键的判断是"问题在前端还是后端"。Postman 隔离法 30 秒出结论（系统已自动完成这一步，手动排障时需要自己做）。',
        sections: [
            {
                head: '① 用 Postman 复现同一个请求',
                lines: [
                    '从 F12 复制 URL → 粘贴进 Postman → 补上请求方法、Headers（含 Token）、Body。',
                    '点击 Send，等结果。',
                ],
            },
            {
                head: '② 对比两条结果，对号入座',
                lines: [
                    '<b>Postman 正常 + 页面异常</b> = 100% <b>前端问题</b>（页面 JS 渲染 / 参数拼错 / 请求没发出）。',
                    '<b>Postman 也报错</b> = <b>后端 / 网络问题</b>（后端代码 / 数据库 / 服务挂了 / 网络）。',
                ],
            },
            {
                head: '③ 常见的 4 种隔离结论',
                lines: [
                    'Postman 404，页面也 404 → 后端确实没有这个接口，让后端加接口。',
                    'Postman 200 有数据，页面空表格 → 前端没正确渲染，查前端 JS（Console 报错）。',
                    'Postman 401/403，页面也 401/403 → 登录态 / Token 问题，重新登录或检查权限。',
                    'Postman 一直转圈/超时 → 后端慢或没起来，查服务状态和日志。',
                ],
            },
        ],
        tips: [
            'Postman 里记得勾选必要的 Headers，尤其是 <b>Authorization（Token）</b>，漏了会得到假的 401。',
            '没有 Postman 也能隔离：浏览器 <b>无痕窗口</b>直接访问 URL（绕开缓存和部分前端环境）也是一个粗略的替代方案。',
        ],
    },

    // ============ 第 4 章 ============
    {
        icon: '📝', title: '第 4 章 · 报障汇报怎么写（3 段式）',
        intro: '实习生最容易栽的坑：问题查到了，但说不清楚。按下面 3 段式汇报，研发/领导一看就懂。',
        sections: [
            {
                head: '汇报模板',
                cmd: '【现象】描述 + 【排查过程】动作与证据 + 【结论】定位结果',
                lines: [
                    '<b>【现象】</b>：一句话说清"什么功能、什么操作、看到什么结果"。示例：登录页点击登录后一直转圈，无任何提示。',
                    '<b>【排查过程】</b>：列出你做了什么、看到什么。示例：F12 看到 /api/login 请求 504 超时；Test-NetConnection 端口通；Postman 复现同样超时。',
                    '<b>【结论】</b>：给出你的判断和下一步建议。示例：后端 /api/login 响应超时，疑似慢 SQL，建议研发查数据库连接与日志。',
                ],
            },
            {
                head: '好汇报 vs 差汇报',
                lines: [
                    '差：<span class="dim">"系统登不上了，快看看"</span> —— 没有 URL、没有证据，研发无从下手。',
                    '好：<span class="dim">"POST /api/login 返回 504（超时 10s），端口 8081 通，Postman 复现同样超时，已截图，怀疑后端慢 SQL，建议查日志"</span> —— 信息完整，研发直接开工。',
                ],
            },
        ],
        tips: [
            '汇报 = <b>现象 + 证据 + 结论</b>，缺一不可。证据能截图就截图。',
            '系统自动生成的"汇报模板"就是 3 段式，可以抄作业学习。',
        ],
    },

    // ============ 第 5 章 ============
    {
        icon: '📋', title: '第 5 章 · 命令速查表（Windows）',
        intro: '手动排障最常用的命令，全部背下来（不用记参数，知道怎么查就行）。',
        sections: [
            {
                head: '网络与服务',
                cmd: 'Test-NetConnection 主机 -Port 端口',
                lines: [
                    'ping 主机IP —— 测主机通不通（网络层）',
                    'Test-NetConnection 192.168.1.100 -Port 8081 —— 测端口通不通（服务层）',
                    'netstat -ano | findstr 8081 —— 看本机端口被哪个进程占用',
                ],
            },
            {
                head: 'HTTP 请求',
                cmd: 'curl.exe http://192.168.1.100:8081/api/users',
                lines: [
                    'curl.exe -X POST http://主机/api/login -H "Content-Type: application/json" -d "{\"user\":\"admin\"}"',
                    'curl.exe -k https://主机/api —— -k 忽略 SSL 证书错误（内网自签证书常用）',
                ],
            },
            {
                head: '看日志与数据库',
                cmd: 'tail -f logs/app.log | grep -E "Exception|Error"',
                lines: [
                    'tail -f 日志文件 —— 实时滚动看日志尾部（Windows 里可用 Get-Content 日志文件 -Wait）',
                    'Get-Content app.log -Tail 200 —— 看最后 200 行',
                    'mysql -u root -p —— 进入数据库后：SHOW DATABASES; USE 库名; SHOW TABLES; SELECT * FROM 表名 LIMIT 10;',
                ],
            },
        ],
        tips: [
            '命令记不住没关系，重点记住"这三类操作"：<b>测端口、发请求、看日志查库</b>。',
            'Windows 的 PowerShell 里 curl 其实是 Invoke-WebRequest 的别名，用 <b>curl.exe</b> 才是真正的 curl。',
        ],
    },

    // ============ 第 6 章 ============
    {
        icon: '❓', title: '第 6 章 · 常见现象 → 原因 → 动作（速查）',
        intro: '现场最常见的 8 类问题，背下来，大部分场景直接对号入座。',
        sections: [
            {
                head: '现象速查表',
                lines: [
                    '<b>页面空表格 / 无数据</b> → 后端返回 200 但 data 为空 → 查数据库（表/数据）。',
                    '<b>一直转圈 / 请求超时</b> → 端口不通或后端慢 → 先测端口，再看后端日志。',
                    '<b>提示未登录 / 自动跳登录</b> → 401/Token 失效 → 重新登录 / 检查接口是否漏传 Token。',
                    '<b>提示无权限</b> → 403 → 查账号角色权限配置（RBAC）。',
                    '<b>接口 404</b> → 后端没有该接口或路径不对 → Postman 验证，让后端补接口或改前端路径。',
                    '<b>提交失败 / 参数错误</b> → 400/422 → F12 看 Payload，前端参数名/格式与后端要求不一致。',
                    '<b>500 服务器错误</b> → 后端异常 → 必查后端日志堆栈。',
                    '<b>文件上传失败 / 过大</b> → 413 → 查上传大小配置（前端 + Nginx + 后端三处）。',
                ],
            },
        ],
        tips: [
            '遇到没见过的现象：先跑一遍系统自动检测拿结论，再对照本速查表理解为什么。',
            '把每一次排障当成学习案例：解决后记录"现象 → 根因 → 动作"，三个月你就是老手。',
        ],
    },
]
</script>

<style scoped>
.teach-card { cursor: pointer; }
.teach-card h2 { margin-bottom: 6px; }
.teach-intro { font-size: 13px; color: var(--text-dim); }
.teach-body { display: none; margin-top: 14px; }
.teach-card.open .teach-body { display: block; }
.teach-sec { margin-bottom: 14px; padding-bottom: 12px; border-bottom: 1px dashed var(--border); }
.teach-sec:last-child { border-bottom: none; }
.teach-sec-head { font-size: 14px; font-weight: 700; margin-bottom: 6px; }
.teach-cmd {
    background: var(--bg-inset);
    border: 1px solid var(--border);
    border-left: 3px solid var(--primary);
    border-radius: 6px;
    padding: 8px 12px;
    font-family: Consolas, "Courier New", monospace;
    font-size: 12.5px;
    color: var(--code-text);
    margin: 4px 0 8px;
    word-break: break-all;
}
.teach-list { padding-left: 18px; font-size: 13.5px; color: var(--text); }
.teach-list li { margin: 5px 0; line-height: 1.8; }
.mono { font-family: Consolas, monospace; background: var(--bg-inset); padding: 1px 6px; border-radius: 4px; font-size: 12.5px; }
.dim { color: var(--text-dim); }
.teach-tips {
    background: rgba(59, 130, 246, 0.08);
    border: 1px dashed rgba(59, 130, 246, 0.35);
    border-radius: 8px;
    padding: 10px 14px;
    margin-top: 8px;
}
.teach-tips-head { font-size: 13px; font-weight: 700; color: var(--primary); margin-bottom: 4px; }
.teach-tips ul { padding-left: 18px; font-size: 13px; color: var(--text-dim); }
.teach-tips li { margin: 4px 0; }
</style>
