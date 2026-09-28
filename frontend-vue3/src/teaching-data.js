// ============================================================
// 动态教学数据：按「状态码」+「问题归属」索引的手动排障步骤
// 检测结果出来后，前端根据本次故障的 status_code / root_cause
// 动态展示对应的手动排查教学（实习生学习用）。
// ============================================================

export const statusTeach = {
    // ---------- 2xx 成功类 ----------
    '200': {
        title: '200 · 后端正常，但页面异常 → 查"空数据"或前端渲染',
        steps: [
            '<b>① 手动复现</b>：F12 → Network → 右键该请求 → Copy as cURL，在命令行执行（或 Postman 粘贴），确认后端确实返回 200。',
            '<b>② 看响应体 data</b>：<span class="mono">Ctrl+F</span> 搜 "data"，是否空数组 <span class="mono">[]</span>？',
            '<b>③ 空数组 → 查数据库</b>：<span class="mono">mysql -u root -p</span> → <span class="mono">USE 库名</span> → <span class="mono">SELECT * FROM 表名 LIMIT 10</span>，确认表存在且有数据。',
            '<b>④ 有数据但页面空 → 前端问题</b>：F12 → Console 看 JS 报错；Elements 检查表格容器是否被渲染。',
            '<b>⑤ 汇报</b>：【现象】页面空表格 +【排查】cURL 复现 200 返回 data 空/非空 +【结论】数据库无数据 / 前端渲染异常。',
        ],
    },
    '201': {
        title: '201 · 创建成功（通常不是故障）',
        steps: [
            '<b>① 确认业务预期</b>：201 = 资源创建成功（如提交表单后新增成功），一般无需排查。',
            '<b>② 若页面报错</b>：多半是前端没正确处理 201 的跳转/提示，F12 Console 查 JS 逻辑。',
        ],
    },
    '204': {
        title: '204 · 无内容（后端处理成功但无返回体）',
        steps: [
            '<b>① 确认业务预期</b>：204 = 操作成功但无内容返回（如删除成功），前端可能误当失败提示。',
            '<b>② 若页面报"失败"</b>：前端把 204 当异常处理了，查前端状态码判断逻辑。',
        ],
    },

    // ---------- 3xx 重定向类 ----------
    '301': {
        title: '301 · 永久重定向 → 接口地址变更',
        steps: [
            '<b>① 手动查看重定向目标</b>：cURL 加 <span class="mono">-v</span>（<span class="mono">curl.exe -v URL</span>）看 Location 头指向哪。',
            '<b>② 确认是否预期</b>：接口地址变更后，前端代码里的请求地址要同步更新（或改网关/代理转发）。',
            '<b>③ 若一直跳转</b>：看是否 A→B→A 循环重定向（cURL -v 会显示），说明后端路由或前端基础地址配置错。',
        ],
    },
    '302': {
        title: '302 · 临时重定向 → 多半是登录跳转或接口转移',
        steps: [
            '<b>① 看 Location</b>：cURL -v 看跳去哪，常见是跳登录页（/login）→ 说明请求未带 Token 或会话过期。',
            '<b>② 跳登录页</b>：检查请求 Headers 是否带 <span class="mono">Authorization: Bearer xxx</span>；重新登录再试。',
        ],
    },
    '304': {
        title: '304 · 缓存命中（不是故障）',
        steps: [
            '<b>① 认知</b>：304 = 浏览器缓存未过期，返回缓存内容，属于正常机制，不用排查。',
            '<b>② 若怀疑缓存旧数据</b>：F12 → Network → 勾选 Disable cache，再 Ctrl+F5 强刷。',
        ],
    },

    // ---------- 4xx 客户端错误 ----------
    '400': {
        title: '400 · 参数格式错误 → 前端传参不对',
        steps: [
            '<b>① 看响应体</b>：后端会返回具体哪个字段错（message 里通常有提示）。',
            '<b>② F12 对比参数</b>：Network → 该请求 → Payload 标签，对照后端接口文档核对字段名 / 类型 / 必填。',
            '<b>③ 常见坑</b>：字段名拼错、JSON 格式错（引号/逗号）、日期格式不对、类型不匹配（传了字符串该传数字）。',
        ],
    },
    '401': {
        title: '401 · 未授权 → Token / 登录态问题（超高频）',
        steps: [
            '<b>① 重新登录</b>：Token 过期是最常见原因，先重新登录再操作，看是否恢复。',
            '<b>② 检查请求头</b>：F12 → Headers → 看是否有 <span class="mono">Authorization</span>；若前端没带 → 前端登录态存储/拦截器问题。',
            '<b>③ 后端验证</b>：cURL 手动带 Token 请求（<span class="mono">curl.exe -H "Authorization: Bearer xxx" URL</span>），带了对 → 前端漏传；带了还 401 → Token 校验逻辑/密钥问题（后端）。',
            '<b>④ 汇报</b>：说明"浏览器 401 vs cURL 带 Token 结果"，让研发一眼定位前后端。',
        ],
    },
    '403': {
        title: '403 · 无权限 → 账号角色权限问题',
        steps: [
            '<b>① 确认账号角色</b>：当前登录账号是否有所需角色/菜单/按钮权限（RBAC 配置）。',
            '<b>② 换高权限账号验证</b>：用管理员账号复现，管理员可以 → 权限配置问题；管理员也 403 → 后端权限校验代码问题。',
            '<b>③ 检查时间/IP 限制</b>：部分系统有访问时段、IP 白名单限制，也会返回 403。',
        ],
    },
    '404': {
        title: '404 · 接口不存在 → 路径错或后端没实现',
        steps: [
            '<b>① 核对路径</b>：F12 复制完整 URL，与接口文档/后端路由表核对，看是否拼错、漏了 /api 前缀。',
            '<b>② cURL 复现</b>：<span class="mono">curl.exe URL</span> 直接测，404 → 后端确实没这个接口（让后端加）；200 → 前端代理/网关转发问题。',
            '<b>③ 检查反向代理</b>：走 Nginx/网关时，确认 /api 前缀是否被正确转发到后端。',
        ],
    },
    '405': {
        title: '405 · 方法不允许 → 请求方法错（GET/POST 用反）',
        steps: [
            '<b>① 核对方法</b>：F12 看该请求是 GET 还是 POST，对照接口文档要求的方法。',
            '<b>② 前端改方法</b>：前端代码里把请求方法改成后端要求的（axios.get → axios.post 等）。',
        ],
    },
    '408': {
        title: '408 · 请求超时（客户端侧）→ 后端处理慢',
        steps: [
            '<b>① 复现看耗时</b>：F12 → Timing 标签看请求各阶段耗时；后端日志看该接口处理耗时。',
            '<b>② 常见根因</b>：慢 SQL、大文件查询、外部接口调用卡住、死循环。',
            '<b>③ 上报研发</b>：附上耗时数据 + 后端日志，让研发定位慢点（SQL 加索引 / 加缓存 / 异步化）。',
        ],
    },
    '409': {
        title: '409 · 资源冲突 → 重复提交 / 数据已存在',
        steps: [
            '<b>① 看响应体</b>：后端提示具体冲突原因（如"名称已存在""版本号冲突"）。',
            '<b>② 业务判断</b>：通常是用户重复提交、或多人同时编辑同一数据（乐观锁）。引导用户刷新/换名重试即可。',
        ],
    },
    '413': {
        title: '413 · 请求体过大 → 上传文件超限',
        steps: [
            '<b>① 确认文件大小</b>：看上传的文件多大，对比系统限制（前端、Nginx、后端三处都要配）。',
            '<b>② 调配置</b>：Nginx 的 <span class="mono">client_max_body_size</span>、后端的 <span class="mono">spring.servlet.multipart.max-file-size</span>、前端上传组件限制，三处同步调大。',
        ],
    },
    '415': {
        title: '415 · Content-Type 不支持 → 请求头类型错',
        steps: [
            '<b>① 检查 Content-Type</b>：F12 → Headers → 看 Content-Type 是否为后端要求的（常见 <span class="mono">application/json</span> 或 <span class="mono">multipart/form-data</span>）。',
            '<b>② 前端修正</b>：设置正确的请求头类型（axios 传 JSON 对象时会自动带 application/json）。',
        ],
    },
    '422': {
        title: '422 · 参数校验不通过（语义错误）→ 必填/格式问题',
        steps: [
            '<b>① 看响应体</b>：后端会列出每个字段的校验错误（如"url 不能为空""邮箱格式错误"）。',
            '<b>② 前端修正</b>：按提示修正前端传参或表单校验，让用户按正确格式填写。',
        ],
    },
    '429': {
        title: '429 · 请求过于频繁 → 被限流',
        steps: [
            '<b>① 确认触发</b>：短时间内高频请求（循环调用 / 用户狂点）触发限流。',
            '<b>② 处理</b>：降低调用频率、前端做防抖/节流；确认是否需要调大后端限流阈值（联系研发）。',
        ],
    },

    // ---------- 5xx 服务端错误 ----------
    '500': {
        title: '500 · 服务器内部错误 → 必查后端日志',
        steps: [
            '<b>① 复现并抓日志</b>：让研发/运维开日志，复现一次，命令：<span class="mono">tail -f logs/app.log | grep -E "Exception|Error"</span>。',
            '<b>② 定位堆栈</b>：找到 Exception，看第一个 <span class="mono">Caused by</span>（根因），记下类名和行号。',
            '<b>③ 常见根因</b>：空指针、数据库连接失败、SQL 语法错、资源不足、配置文件错。',
            '<b>④ 汇报</b>：报障时附上"复现 URL + 状态码 500 + 日志堆栈截图"，研发可直接定位。',
        ],
    },
    '501': {
        title: '501 · 功能未实现 → 后端还没做这个接口',
        steps: [
            '<b>① 确认接口是否在开发计划内</b>：后端尚未实现该功能，返回 501。',
            '<b>② 处理</b>：与研发确认交付时间；前端先做友好提示，别让用户看到裸报错。',
        ],
    },
    '502': {
        title: '502 · 网关错误 → 后端服务挂了 / 网关配置错',
        steps: [
            '<b>① 确认后端服务状态</b>：<span class="mono">Test-NetConnection 主机 -Port 端口</span> 看后端是否存活；查看后端进程是否崩溃。',
            '<b>② 重启后端</b>：服务挂了就重启，再看日志找崩溃原因（OOM、端口被占、依赖服务没起）。',
            '<b>③ 检查网关配置</b>：Nginx/网关 upstream 指向的后端地址是否写错、后端是否在网关白名单。',
        ],
    },
    '503': {
        title: '503 · 服务不可用 → 服务启动中 / 过载 / 维护中',
        steps: [
            '<b>① 确认服务状态</b>：后端是否正在启动（启动中会短暂 503）、是否处于过载/熔断状态。',
            '<b>② 重启或等待</b>：启动中 → 等启动完成；过载 → 检查连接池/线程池是否耗尽（看日志）。',
            '<b>③ 排查依赖</b>：后端依赖的数据库/中间件挂了，也会让服务降级返回 503。',
        ],
    },
    '504': {
        title: '504 · 网关超时 → 后端处理太久（超高频）',
        steps: [
            '<b>① 确认后端是否真的慢</b>：cURL 直连后端接口（绕过网关）测耗时，<span class="mono">curl.exe -w "%{time_total}s" URL</span>。',
            '<b>② 直连也慢 → 后端问题</b>：查慢 SQL、大查询、外部调用卡住；看后端日志该接口耗时。',
            '<b>③ 直连快但走网关慢 → 网关问题</b>：调大 Nginx <span class="mono">proxy_read_timeout</span>，或检查网关到后端网络。',
            '<b>④ 汇报</b>：附"直连耗时 vs 网关耗时"对比数据，研发一看就知道卡在哪层。',
        ],
    },
    '505': {
        title: '505 · HTTP 版本不支持 → 代理/网关配置问题',
        steps: [
            '<b>① 检查网关配置</b>：确认 Nginx/网关 HTTP 版本与后端支持的版本匹配（老网关配了新后端）。',
            '<b>② 联系运维</b>：调整网关 HTTP 协议配置后重试。',
        ],
    },

    // ---------- 无响应特殊态 ----------
    'REFUSED': {
        title: '连接被拒绝 → 服务没启动 / 端口错 / 防火墙拦截',
        steps: [
            '<b>① 测端口</b>：<span class="mono">Test-NetConnection 主机IP -Port 端口</span> 看 TcpTestSucceeded 是 True 还是 False。',
            '<b>② False → 分层排查</b>：服务进程是否启动（<span class="mono">netstat -ano | findstr 端口</span>）；防火墙是否放行该端口；IP/端口是否写错。',
            '<b>③ True 但页面仍连接被拒</b>：检查是否走错了代理（浏览器代理设置把内网地址也代理了）。',
        ],
    },
    'TIMEOUT': {
        title: '超时（无响应）→ 服务慢 / 网络丢包 / 防火墙丢包',
        steps: [
            '<b>① 测端口</b>：<span class="mono">Test-NetConnection 主机IP -Port 端口</span>，超时无响应 = 网络不通或防火墙丢包（不是拒绝）。',
            '<b>② 测连通性</b>：<span class="mono">ping 主机IP -t</span> 看是否丢包（网络质量问题）。',
            '<b>③ 后端是否过慢</b>：直连后端接口测耗时；后端日志看是否有慢请求堆积。',
            '<b>④ 汇报</b>：附"ping 丢包率 + 端口测试结果"，区分网络问题 / 服务问题。',
        ],
    },
    'NONE': {
        title: '无法访问（URL 无效或目标不可达）→ 先确认 URL 本身',
        steps: [
            '<b>① 核对 URL</b>：F12 复制的是完整 URL 吗？协议（http/https）、IP、端口、路径是否完整。',
            '<b>② 手动访问</b>：浏览器地址栏直接访问该 URL，看能否打开。',
            '<b>③ 换环境验证</b>：换个网络环境（或手机热点）访问，确认是不是本地网络限制。',
        ],
    },
}

// 兜底教学卡（未匹配到具体状态码时展示）
export const fallbackTeach = {
    title: '通用手动排障流程（任何状态码都适用）',
    steps: [
        '<b>① 拿 URL</b>：F12 → Network → 找到故障请求 → 复制 URL 和请求方法。',
        '<b>② 测服务</b>：<span class="mono">Test-NetConnection 主机IP -Port 端口</span>，确认服务通不通。',
        '<b>③ 裸请求复现</b>：cURL / Postman 发同样请求，看真实状态码和响应体。',
        '<b>④ 查速查表</b>：对照本系统「🔢 状态码」页签定位含义，再按对应章节执行。',
        '<b>⑤ 收证据</b>：截图 URL / 状态码 / 响应体，按「现象 + 排查过程 + 结论」三段式汇报。',
    ],
}

// 按问题归属（root_cause）的排查方向教学
export const categoryTeach = {
    frontend: {
        title: '归属：前端问题 → 排查页面 JS / 渲染 / 请求',
        steps: [
            '<b>① F12 Console</b>：先看有没有 JS 报错（红字），多半直接指向问题代码行。',
            '<b>② Network 对比</b>：看请求是否发出、参数是否正确、响应数据前端有没有正确渲染。',
            '<b>③ 常见前端坑</b>：数据未 setState、v-if 条件写反、接口字段名拼错、空值没做兜底。',
        ],
    },
    backend: {
        title: '归属：后端问题 → 查接口实现 / 日志',
        steps: [
            '<b>① 后端日志</b>：<span class="mono">tail -f logs/app.log</span>，复现故障抓 Exception。',
            '<b>② 接口逻辑</b>：确认接口是否按文档实现、是否漏判边界条件、依赖的其它服务是否正常。',
            '<b>③ 提给研发</b>：附"复现 URL + 请求参数 + 响应 + 日志"，别只甩一句"后端挂了"。',
        ],
    },
    database: {
        title: '归属：数据库问题 → 查表 / 数据 / SQL',
        steps: [
            '<b>① 连库看数据</b>：<span class="mono">mysql -u root -p</span> → <span class="mono">SHOW TABLES;</span> → <span class="mono">SELECT * FROM 表名 LIMIT 10;</span>',
            '<b>② 确认三件事</b>：表存在吗？有数据吗？字段和代码一致吗？',
            '<b>③ 慢 SQL</b>：接口慢 → 看 SQL 是否全表扫描，需要加索引（<span class="mono">EXPLAIN SELECT ...</span>）。',
        ],
    },
    network: {
        title: '归属：网络问题 → 分层测连通性',
        steps: [
            '<b>① ping</b>：<span class="mono">ping 主机IP -t</span>，看是否丢包（网络层）。',
            '<b>② 测端口</b>：<span class="mono">Test-NetConnection 主机IP -Port 端口</span>（传输层）。',
            '<b>③ 检查代理</b>：浏览器/系统代理是否把内网地址也代理出去了（设置里加例外）。',
            '<b>④ 换网络验证</b>：换手机热点访问，能通 → 本机/内网网络问题；不通 → 服务端问题。',
        ],
    },
    auth: {
        title: '归属：登录认证问题 → 查 Token / 会话',
        steps: [
            '<b>① 重新登录</b>：Token 过期最常见，重新登录后操作验证。',
            '<b>② 查 Token 传递</b>：F12 看请求是否带 Authorization 头；跨系统跳转后 Token 是否丢失。',
            '<b>③ 单点登录（SSO）</b>：走 SSO 的排查 Ticket 是否失效、回调地址是否配置正确。',
        ],
    },
    permission: {
        title: '归属：权限问题 → 查角色 / 授权',
        steps: [
            '<b>① 确认角色</b>：当前账号的角色是否有该功能权限（菜单/按钮/数据权限）。',
            '<b>② 换高权限账号验证</b>：管理员能操作 → 权限配置问题；管理员也不行 → 后端授权校验代码问题。',
        ],
    },
    service: {
        title: '归属：服务问题 → 查服务状态 / 依赖 / 配置',
        steps: [
            '<b>① 服务存活</b>：<span class="mono">Test-NetConnection 主机 -Port 端口</span> + 看进程是否在跑。',
            '<b>② 依赖服务</b>：该服务依赖的数据库 / Redis / 其它微服务是否正常启动。',
            '<b>③ 资源情况</b>：内存 / 磁盘 / 连接池是否耗尽（<span class="mono">docker stats</span> 或系统资源监视）。',
        ],
    },
    config: {
        title: '归属：配置问题 → 查环境配置 / 参数',
        steps: [
            '<b>① 环境对比</b>：测试环境正常、生产异常 → 大概率配置差异（数据库地址、密钥、域名、开关）。',
            '<b>② 核对配置项</b>：配置文件里网关地址、数据库连接串、第三方接口地址、超时参数逐一核对。',
            '<b>③ 改后重启</b>：改配置后记得重启服务（部分配置热加载，视系统而定）。',
        ],
    },
    performance: {
        title: '归属：性能问题 → 定位慢在哪一层',
        steps: [
            '<b>① 分步计时</b>：F12 Timing 看前端耗时；后端日志看接口耗时；<span class="mono">EXPLAIN</span> 看 SQL 耗时，逐层定位。',
            '<b>② 常见根因</b>：慢 SQL（加索引）、大列表无分页、循环调用外部接口、缓存未命中。',
            '<b>③ 优化方向</b>：加缓存（Redis）、加索引、分页加载、异步化耗时任务。',
        ],
    },
    business: {
        title: '归属：业务逻辑问题 → 确认业务规则与数据',
        steps: [
            '<b>① 理解业务预期</b>：确认当前结果是否符合业务规则（如状态流转、金额计算、唯一性校验）。',
            '<b>② 检查输入数据</b>：该业务依赖的字典/配置/上游数据是否完整正确。',
            '<b>③ 与业务方确认</b>：规则不明确时先对齐预期，再决定改前端校验还是后端逻辑。',
        ],
    },
}

// 根据状态码取教学卡（含特殊态与兜底）
export function pickStatusTeach(statusCode) {
    const key = String(statusCode || '').toUpperCase()
    if (statusTeach[key]) return statusTeach[key]
    if (key.startsWith('50')) return statusTeach[key] || fallbackTeach
    return fallbackTeach
}

// 根据归属取教学卡（兜底返回 null，前端可不展示）
export function pickCategoryTeach(rootCause) {
    return categoryTeach[rootCause] || null
}
