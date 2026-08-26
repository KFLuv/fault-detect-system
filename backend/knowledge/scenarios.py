# -*- coding: utf-8 -*-
"""
92 场景知识库
数据来源：《功能排障标准 SOP（整合版）》92 场景手册
         《故障排查练习系统》11 个真实场景
         + 按状态码规则补齐的通用场景模板
"""

# 问题归属分类标签
CATEGORY_LABELS = {
    "frontend": "前端问题",
    "backend": "后端问题",
    "database": "数据库问题",
    "service": "服务问题",
    "network": "网络问题",
    "permission": "权限问题",
    "performance": "性能问题",
    "config": "配置问题",
    "auth": "认证问题",
    "business": "业务问题",
}

CATEGORY_COLORS = {
    "frontend": "#ffd32a",
    "backend": "#0984e3",
    "database": "#1dd1a1",
    "service": "#d63031",
    "network": "#fdcb6e",
    "permission": "#e84393",
    "performance": "#636e72",
    "config": "#8e44ad",
    "auth": "#e17055",
    "business": "#00b894",
}


def _sc(id_no, name, http_codes, patterns, symptoms, root_cause, conclusion,
        evidence, solution, probability=0.9, priority="中"):
    return {
        "id": "SCN_%03d" % id_no,
        "name": name,
        "http_codes": http_codes,
        "response_patterns": patterns,
        "ui_symptoms": symptoms,
        "root_cause": root_cause,
        "conclusion": conclusion,
        "evidence": evidence,
        "solution": solution,
        "probability": probability,
        "priority": priority,
    }


def build_scenarios():
    S = []
    n = 0

    # ============ pending（无响应）3 个 ============
    S.append(_sc(1, "后端服务未启动", ["pending"],
        ["no response", "timeout", "超时"], ["转圈", "无响应", "打不开"],
        "service", "后端服务未启动（TCP 连接失败，请求未到达后端）",
        [
            "第 0 步：Test-NetConnection {host} -Port {port} → TcpTestSucceeded: False",
            "第 1 步：F12 Network → 请求 pending → 30 秒后超时（504）",
            "进程检查：ps -ef | grep java → 无 SpringBoot 进程",
        ],
        ["启动后端服务（java -jar app.jar / docker restart <容器>）",
         "确认服务启动参数 --server.address=0.0.0.0（避免只监听 IPv6）",
         "确认端口是否被占用：netstat -tlnp | grep {port}"],
        probability=1.0, priority="高"))

    S.append(_sc(2, "网络不通", ["pending"],
        ["no response", "network unreachable", "connect"], ["超时", "无响应", "页面打不开"],
        "network", "网络不通（IP/端口无法连通，请求未发出）",
        [
            "第 0 步：Test-NetConnection {host} -Port {port} → 失败",
            "ping {host} → 不通 或 丢包严重",
            "确认客户端与服务端在同一网段/可路由",
        ],
        ["检查客户端网络（能否 ping 通服务器）",
         "检查 VPN/内网连接是否正常",
         "联系网络管理员确认路由/防火墙放行"],
        probability=0.95, priority="高"))

    S.append(_sc(3, "防火墙拦截", ["pending"],
        ["no response", "blocked", "拒绝"], ["超时", "无响应", "特定 IP 无法访问"],
        "network", "防火墙/安全组拦截（端口未放行）",
        [
            "第 0 步：telnet {host} {port} → 连接失败",
            "防火墙状态：systemctl status firewalld → 开启",
            "防火墙规则：firewall-cmd --list-all → 未见 {port} 端口",
        ],
        ["开放端口：firewall-cmd --permanent --add-port={port}/tcp && firewall-cmd --reload",
         "云服务器检查安全组入站规则是否放行 {port}"],
        probability=0.95, priority="高"))

    # ============ 200 10 个 ============
    S.append(_sc(4, "数据库无数据", ["200"],
        ["data", "empty", "\[\]", "空", "records", "rows"], ["空表格", "空列表", "无数据", "查询无结果"],
        "database", "数据库无数据（后端接口正常，但查询结果为空）",
        [
            "第 1 步：F12 Network → {url} → Status 200",
            "响应体：{\"code\":200,\"message\":\"success\",\"data\":[]}",
            "第 6 步：数据库 → SELECT * FROM 表名 LIMIT 10 → 0 rows",
        ],
        ["检查数据库中是否有数据（mysql -u root -p → SELECT * FROM users LIMIT 10）",
         "检查数据导入流程或重置测试数据",
         "检查查询条件是否过于严格（误加过滤条件）"],
        probability=1.0, priority="高"))

    S.append(_sc(5, "前端渲染问题", ["200"],
        ["data", "数据"], ["页面白屏", "数据不显示", "表格空白但有接口数据"],
        "frontend", "前端渲染问题（接口有数据，但页面不展示）",
        [
            "第 1 步：F12 Network → {url} → Status 200，且 data 有值",
            "Postman 复现 → 返回正常数据",
            "F12 Console → 前端 JS 报错（渲染中断）",
        ],
        ["查看 F12 Console 报错（Uncaught TypeError / ReferenceError）",
         "检查前端循环渲染逻辑与数据字段名是否匹配",
         "检查前端是否用错响应字段（如 data.list 写成 data.rows）"],
        probability=0.95, priority="高"))

    S.append(_sc(6, "CORS 跨域问题", ["200"],
        ["cors", "cross-origin", "Access-Control"], ["接口 200 但页面报跨域错误", "控制台报 CORS"],
        "backend", "CORS 跨域配置问题（接口正常但浏览器拦截）",
        [
            "F12 Console → Access to XMLHttpRequest at {url} ... blocked by CORS policy",
            "Postman 直接调用 → 正常返回（Postman 不受跨域限制）",
            "响应头缺少 Access-Control-Allow-Origin",
        ],
        ["后端添加 CORS 配置（允许前端来源）",
         "Nginx 层配置 add_header Access-Control-Allow-Origin *",
         "前端代理转发（开发环境 vite proxy / devServer proxy）"],
        probability=0.9, priority="高"))

    S.append(_sc(7, "响应 JSON 解析失败", ["200"],
        ["parse", "json", "unexpected"], ["页面报 JSON 解析错误", "数据区域空白"],
        "backend", "后端返回非标准 JSON（前端解析失败）",
        [
            "F12 Network → {url} → Response 不是合法 JSON（如带 HTML 或 BOM）",
            "响应体开头是 <!DOCTYPE html> 或包含异常字符",
        ],
        ["后端接口返回格式与约定不符，联系后端修复",
         "检查是否有网关/防火墙篡改响应体",
         "前端做兼容处理（容错解析）"],
        probability=0.85, priority="中"))

    S.append(_sc(8, "数据缓存旧数据", ["200"],
        ["cache", "304", "etag", "last-modified"], ["数据不更新", "改了但页面没变", "刷新还是旧数据"],
        "config", "浏览器/网关缓存导致页面显示旧数据",
        [
            "F12 Network → {url} → 命中缓存（from disk cache）",
            "强制刷新（Ctrl+F5）后数据正常",
            "响应头 Cache-Control / Expires 配置了强缓存",
        ],
        ["后端响应头禁止强缓存（Cache-Control: no-cache）",
         "接口 URL 加版本号/时间戳参数",
         "前端请求加随机参数防缓存"],
        probability=0.85, priority="中"))

    S.append(_sc(9, "接口响应慢但成功", ["200"],
        ["slow", "time"], ["页面转圈很久才出来", "接口 200 但耗时长"],
        "performance", "接口响应慢（200 但耗时过长，体验卡顿）",
        [
            "F12 Network → {url} → 耗时 {time}ms（>2s）",
            "后端日志查看该接口处理耗时",
            "数据库 show processlist 检查是否有慢查询",
        ],
        ["优化 SQL / 加索引",
         "接口结果加缓存（Redis）",
         "大数据量接口改分页/异步"],
        probability=0.8, priority="中"))

    S.append(_sc(10, "字段名不匹配", ["200"],
        ["data", "字段", "undefined"], ["undefined", "表格列显示 undefined", "页面取值空白"],
        "frontend", "前后端字段名不一致（前端取不到数据）",
        [
            "F12 Network → {url} → Response 字段为 createdAt",
            "前端代码读取 createTime → undefined",
            "页面表格列空白",
        ],
        ["统一前后端字段命名（对照后端 DTO 字段）",
         "前端按实际返回字段取值",
         "后端按前端约定调整返回字段（加 @JsonProperty）"],
        probability=0.85, priority="中"))

    S.append(_sc(11, "数据权限过滤", ["200"],
        ["data", "permission", "auth"], ["数据比预期少", "某些用户看不到全部数据"],
        "permission", "数据权限过滤导致部分数据不可见（200 正常但被过滤）",
        [
            "F12 Network → {url} → Status 200，data 数据量少于数据库实际数据",
            "数据库全量查询 → 数据存在",
            "当前用户角色存在数据权限范围限制",
        ],
        ["检查用户角色的数据权限范围（部门/人员维度）",
         "确认是否需要调整数据权限配置",
         "如需全部数据，联系管理员调整角色权限"],
        probability=0.8, priority="中"))

    S.append(_sc(12, "下载文件损坏", ["200"],
        ["download", "file", "content-disposition"], ["下载的压缩包/文件打不开", "文件损坏"],
        "backend", "下载文件损坏（200 但文件内容异常）",
        [
            "F12 Network → {url} → Status 200，Content-Type 为 application/octet-stream",
            "文件大小与源文件不一致",
            "下载后解压提示文件损坏",
        ],
        ["后端下载接口正确设置 Content-Disposition 与 Content-Type",
         "检查文件流是否正确关闭（Buffer flush/close）",
         "前端下载使用 blob 并携带正确文件名"],
        probability=0.8, priority="中"))

    S.append(_sc(13, "数据为空但表不存在", ["200"],
        ["data", "\[\]", "empty", "table", "doesn't exist"], ["查询报接口 200 但实际异常", "日志提示表不存在"],
        "database", "数据库表不存在（接口 200 但后端日志报表缺失）",
        [
            "响应体：{\"code\":200,\"message\":\"success\",\"data\":[]}",
            "后端日志：Table 'xxx' doesn't exist",
            "数据库 show tables → 未见对应表",
        ],
        ["执行建表 SQL 或迁移脚本（migration）",
         "检查是否缺少初始化 SQL",
         "确认数据库连接指向正确的库（选错库常见）"],
        probability=0.85, priority="高"))

    # ============ 201 3 个 ============
    S.append(_sc(14, "成功但无提示", ["201"],
        ["success", "created", "成功"], ["操作成功但页面无任何提示", "不知道是否成功"],
        "frontend", "后端返回 201 成功，但前端未处理成功响应（体验问题）",
        [
            "F12 Network → {url} → Status 201",
            "响应体：{\"code\":201,\"message\":\"发布成功\"}",
            "页面无任何成功/失败提示",
        ],
        ["前端添加成功提示（Toast/Swal 提示）",
         "成功后自动刷新列表或跳转页面",
         "前端代码处理 201 成功分支"],
        probability=1.0, priority="高"))

    S.append(_sc(15, "创建成功但列表不刷新", ["201"],
        ["created", "success", "成功"], ["新增成功但列表看不到新数据", "需要手动刷新才能看到"],
        "frontend", "创建成功但前端未刷新列表",
        [
            "F12 Network → 创建接口 → 201 成功",
            "列表查询接口 → 数据库已有新数据",
            "前端未在创建成功后重新查询列表",
        ],
        ["前端在 201 后重新调用列表接口刷新",
         "创建成功后跳转到列表页/详情页",
         "使用事件总线或状态管理统一刷新"],
        probability=0.9, priority="中"))

    S.append(_sc(16, "创建成功但跳转错误", ["201"],
        ["created", "redirect", "跳转"], ["创建成功但跳到错误页面", "跳转后 404"],
        "frontend", "创建成功但前端跳转路由错误",
        [
            "F12 Network → 创建接口 → 201 成功",
            "页面跳转到不存在的路由 → 404/白屏",
            "前端代码跳转路径写错（如 /edit 写成 /update）",
        ],
        ["修正前端跳转路由",
         "确认路由表中有对应页面配置",
         "详情页参数传递错误（漏传 id）"],
        probability=0.85, priority="中"))

    # ============ 202 2 个 ============
    S.append(_sc(17, "异步任务无状态展示", ["202"],
        ["accepted", "async", "task", "job"], ["提交后一直转圈", "不知道任务进行到哪一步"],
        "frontend", "异步任务提交成功但前端无进度展示",
        [
            "F12 Network → {url} → Status 202 Accepted",
            "响应体：{\"code\":202,\"message\":\"任务已提交\",\"taskId\":\"xxx\"}",
            "前端未轮询任务状态接口",
        ],
        ["前端轮询任务状态接口（GET /api/tasks/{id}）",
         "增加任务进度条/状态展示",
         "任务完成时给用户明确反馈"],
        probability=0.85, priority="中"))

    S.append(_sc(18, "任务失败无反馈", ["202"],
        ["failed", "error", "task"], ["任务提交后最后失败但页面无提示", "后台任务静默失败"],
        "backend", "异步任务执行失败但未通知用户",
        [
            "F12 Network → {url} → 202 提交成功",
            "任务状态接口 → failed",
            "后端日志：任务执行异常堆栈",
        ],
        ["后端任务失败时记录日志并通知前端",
         "增加任务失败重试机制",
         "前端展示失败原因并支持重新提交"],
        probability=0.8, priority="中"))

    # ============ 204 2 个 ============
    S.append(_sc(19, "成功但前端误判失败", ["204"],
        ["204", "no content"], ["操作成功但页面提示失败", "接口 204 前端走失败分支"],
        "frontend", "后端返回 204（无内容）但前端误判为失败",
        [
            "F12 Network → {url} → Status 204 No Content",
            "后端正常处理（删除/更新成功）",
            "前端代码只判断 data/body，未处理 204 成功分支",
        ],
        ["前端将 204 视为成功（res.status === 204）",
         "统一 axios 拦截器处理 204",
         "后端返回 200 代替 204（约定化）"],
        probability=0.9, priority="中"))

    S.append(_sc(20, "成功无内容无提示", ["204"],
        ["no content", "204"], ["操作成功但用户完全无感知", "无任何提示"],
        "frontend", "后端 204 成功且前端无任何反馈",
        [
            "F12 Network → {url} → Status 204",
            "页面无任何提示（成功/失败都没有）",
            "用户无法确认操作是否生效",
        ],
        ["前端加成功提示（toast）",
         "204 场景需要刷新列表给用户确认",
         "增加操作日志供用户查看"],
        probability=0.85, priority="低"))

    # ============ 300 1 个 ============
    S.append(_sc(21, "多选项未处理", ["300"],
        ["multiple", "300"], ["请求返回多个结果但页面报错", "接口 300 未处理"],
        "config", "服务器返回多选项（300）但前后端未处理",
        [
            "F12 Network → {url} → Status 300 Multiple Choices",
            "响应体包含多个可选地址",
            "前端未处理 300 分支",
        ],
        ["确认服务端为何返回多选项（多为配置）",
         "前端处理 300 分支（展示选择）",
         "后端明确单一响应（返回 200/302）"],
        probability=0.8, priority="低"))

    # ============ 301 2 个 ============
    S.append(_sc(22, "URL 永久变更未更新", ["301"],
        ["301", "moved", "redirect"], ["接口/页面被重定向", "旧地址自动跳转新地址"],
        "frontend", "资源永久迁移，前端仍在调用旧 URL",
        [
            "F12 Network → {url} → Status 301 Moved Permanently",
            "Location 头指向新地址",
            "前端代码/收藏夹仍是旧地址",
        ],
        ["更新前端 API 地址为新 URL",
         "更新书签/快捷方式",
         "后端保留旧地址 301 跳转（过渡期）"],
        probability=0.95, priority="中"))

    S.append(_sc(23, "旧链接未加跳转", ["301"],
        ["301", "moved"], ["老用户访问旧链接打不开", "旧地址未跳转到新地址"],
        "config", "资源已迁移但旧地址未配置重定向",
        [
            "F12 Network → 旧地址 → Status 404（未配置跳转）",
            "对比新地址 → 正常 200",
            "服务器/网关未配置旧地址 301 规则",
        ],
        ["在 Nginx/网关配置旧地址 301 到新地址",
         "或后端 Controller 保留旧路由做重定向",
         "通知用户更新访问地址"],
        probability=0.85, priority="中"))

    # ============ 302 3 个 ============
    S.append(_sc(24, "登录重定向死循环", ["302"],
        ["302", "redirect", "loop"], ["登录后一直跳转刷不出页面", "浏览器提示重定向过多"],
        "backend", "登录后重定向死循环（302 循环跳转）",
        [
            "F12 Network → 多个 302 连续跳转",
            "浏览器提示 ERR_TOO_MANY_REDIRECTS",
            "登录成功但每次请求又被重定向到登录页",
        ],
        ["检查登录成功后 Cookie/Token 是否正确种入",
         "检查权限拦截器是否对登录接口也拦截",
         "确认前端路由守卫与后端重定向逻辑不冲突"],
        probability=0.95, priority="高"))

    S.append(_sc(25, "重定向丢失 Token", ["302"],
        ["302", "redirect", "token"], ["跳转后提示未登录", "重定向后 Token 丢失"],
        "backend", "重定向后丢失登录凭证（Token/Cookie 未带）",
        [
            "F12 Network → 302 跳转后的请求 → 401",
            "跳转请求未携带 Authorization / Cookie",
            "跨域跳转导致 Cookie 丢失（SameSite 策略）",
        ],
        ["确认重定向目标是否同一域名（跨域需配置 CORS + Cookie）",
         "检查 SameSite=None; Secure 配置",
         "前端请求拦截器统一携带 Token"],
        probability=0.85, priority="高"))

    S.append(_sc(26, "重定向到错误页面", ["302"],
        ["302", "redirect", "location"], ["跳转到了不存在的页面", "跳转后 404/白屏"],
        "frontend", "重定向目标地址错误",
        [
            "F12 Network → {url} → 302 → Location 指向错误地址",
            "目标地址访问 → 404",
            "前端/后端重定向 URL 配置错误",
        ],
        ["修正重定向目标 URL",
         "检查 Nginx rewrite / proxy 配置",
         "后端 redirect 地址与前端路由保持一致"],
        probability=0.9, priority="中"))

    # ============ 303 1 个 ============
    S.append(_sc(27, "提交后未跳转结果页", ["303"],
        ["303", "see other"], ["表单提交后页面无变化", "提交后应跳转结果页未跳转"],
        "frontend", "POST 提交后 303 重定向但前端未跟随",
        [
            "F12 Network → 提交接口 → Status 303 See Other",
            "Location 指向结果页",
            "前端未跟随重定向/未处理 303",
        ],
        ["前端 fetch 默认跟随重定向，检查是否关闭（redirect: 'manual'）",
         "若手动处理重定向，跳转 Location 指向的页面",
         "后端确认 303 目标地址正确"],
        probability=0.85, priority="低"))

    # ============ 304 2 个 ============
    S.append(_sc(28, "缓存导致数据不更新", ["304"],
        ["304", "not modified", "cache"], ["数据不更新", "接口一直 304", "改了数据页面不变"],
        "config", "浏览器缓存（304）导致数据不更新",
        [
            "F12 Network → {url} → Status 304 Not Modified",
            "强缓存命中（from disk cache）",
            "强制刷新后数据正常",
        ],
        ["响应头去除强缓存（Cache-Control: no-cache / max-age=0）",
         "GET 请求带版本参数防缓存",
         "Nginx 关闭敏感接口缓存"],
        probability=0.9, priority="中"))

    S.append(_sc(29, "ETag 校验导致响应异常", ["304"],
        ["304", "etag", "not modified"], ["接口返回 304 导致前端拿不到数据", "条件请求异常"],
        "config", "ETag/Last-Modified 校验导致返回 304 而非 200",
        [
            "F12 Network → {url} → 带 If-None-Match → 返回 304",
            "前端期望每次拿到最新数据",
            "缓存校验逻辑正常但业务上不需要",
        ],
        ["动态接口关闭 ETag 校验或让响应体变化",
         "前端加时间戳参数绕过缓存",
         "后端正确设置 Cache-Control"],
        probability=0.8, priority="低"))

    # ============ 307 1 个 ============
    S.append(_sc(30, "网关路由配置错误", ["307"],
        ["307", "temporary redirect"], ["接口被重定向到错误服务", "307 跳转异常"],
        "config", "网关/路由 307 重定向配置错误",
        [
            "F12 Network → {url} → Status 307 Temporary Redirect",
            "Location 指向错误的服务/端口",
            "网关路由规则配置问题",
        ],
        ["检查 Nginx/网关路由配置（proxy_pass 目标）",
         "确认多环境（测试/生产）配置是否串了",
         "负载均衡权重/节点配置检查"],
        probability=0.85, priority="中"))

    # ============ 308 1 个 ============
    S.append(_sc(31, "永久重定向规则错误", ["308"],
        ["308", "permanent redirect"], ["功能被错误重定向到新地址", "旧功能不可用"],
        "config", "308 永久重定向规则配置错误",
        [
            "F12 Network → {url} → Status 308",
            "Location 指向的地址无对应功能",
            "网关重定向规则写错",
        ],
        ["修正重定向规则目标地址",
         "确认新旧地址功能一致性",
         "浏览器可能缓存 308（需清理或换浏览器验证）"],
        probability=0.85, priority="中"))

    # ============ 400 4 个 ============
    S.append(_sc(32, "参数缺失", ["400"],
        ["required", "missing", "参数", "不能为空", "必填"], ["提交提示参数缺失", "必填项没填就提交"],
        "frontend", "请求参数缺失（前端未校验必填项）",
        [
            "F12 Network → {url} → Status 400",
            "响应体：{\"code\":400,\"message\":\"xxx 不能为空\"}",
            "Payload 中缺少必填字段",
        ],
        ["前端表单增加必填校验（提交前拦截）",
         "检查提交参数是否正确组装",
         "后端错误提示返回具体字段名"],
        probability=0.95, priority="高"))

    S.append(_sc(33, "参数格式错误", ["400"],
        ["format", "格式", "invalid", "不正确"], ["提示邮箱/手机号格式错误", "格式校验不过"],
        "frontend", "参数格式错误（前端未做格式校验）",
        [
            "F12 Network → {url} → Status 400",
            "响应体：{\"code\":400,\"message\":\"邮箱格式不正确\"}",
            "Payload：email=\"invalid-email\"（缺少 @ 和域名）",
        ],
        ["前端增加正则校验（邮箱/手机号/身份证等）",
         "输入框加格式化提示",
         "后端返回更详细的校验错误"],
        probability=0.95, priority="高"))

    S.append(_sc(34, "参数类型错误", ["400"],
        ["type", "类型", "cannot", "convert"], ["提交报参数类型错误", "数字字段传了字符串"],
        "frontend", "参数类型错误（前后端类型不匹配）",
        [
            "F12 Network → {url} → Status 400",
            "Payload 中 id 传了字符串 \"123\"，后端期望数字",
            "响应体：Failed to convert value of type 'String' to required type 'Long'",
        ],
        ["前端按后端类型传参（数字字段传 number）",
         "检查 JSON.stringify 后类型是否变化",
         "后端参数转换失败给明确提示"],
        probability=0.9, priority="中"))

    S.append(_sc(35, "参数超长", ["400"],
        ["too long", "length", "超长", "长度"], ["提交长文本报参数错误", "字段长度超限"],
        "config", "参数超过后端长度限制",
        [
            "F12 Network → {url} → Status 400",
            "响应体：字段长度超限（如 max 255）",
            "数据库字段 varchar(255) 限制",
        ],
        ["前端限制输入长度",
         "后端放宽字段长度或分页/截断处理",
         "数据库字段类型与 DTO 校验一致"],
        probability=0.85, priority="中"))

    # ============ 401 4 个 ============
    S.append(_sc(36, "未登录/Token 失效", ["401"],
        ["未登录", "token", "unauthorized", "401", "请先登录"], ["提示未登录", "Token 失效", "操作被拒绝"],
        "auth", "前端未登录/Token 失效（认证问题）",
        [
            "第 1 步：F12 Network → {url} → Status 401",
            "响应体：{\"code\":401,\"message\":\"未登录或 Token 失效，请先登录\"}",
            "请求 Headers 缺少 Authorization: Bearer <token>",
        ],
        ["实现登录功能，保存 Token 到 localStorage",
         "请求拦截器统一携带 Authorization 头",
         "Token 过期后跳转登录页重新登录"],
        probability=1.0, priority="高"))

    S.append(_sc(37, "Token 过期", ["401"],
        ["token", "expired", "过期", "401"], ["用了一段时间后提示登录", "Token 过期失效"],
        "auth", "Token 已过期（登录状态丢失）",
        [
            "F12 Network → {url} → Status 401",
            "响应体：{\"code\":401,\"message\":\"Token 已过期\"}",
            "Token 创建时间距今超过过期时间",
        ],
        ["引导用户重新登录",
         "前端实现 Token 刷新机制（refresh token）",
         "检查后端 Token 过期时间配置是否过短"],
        probability=0.95, priority="高"))

    S.append(_sc(38, "Token 格式错误", ["401"],
        ["token", "invalid", "格式"], ["接口一直 401 但已登录", "Token 携带方式不对"],
        "auth", "Token 格式错误（携带方式/前缀不对）",
        [
            "F12 Network → {url} → Status 401",
            "请求头 Authorization 缺少 Bearer 前缀",
            "或 Token 值本身格式错误",
        ],
        ["前端拼接 Authorization: Bearer ${token}",
         "检查登录接口是否返回完整 Token",
         "检查是否有 Token 被截断（换行/空格）"],
        probability=0.9, priority="高"))

    S.append(_sc(39, "登录接口调用失败", ["401"],
        ["login", "auth", "failed"], ["登录就报错", "输入正确账号密码也登不上"],
        "auth", "登录接口认证失败（账号密码/接口问题）",
        [
            "F12 Network → 登录接口 → Status 401 / Auth failed",
            "Postman 调登录接口 → 也失败",
            "后端日志：登录认证失败记录",
        ],
        ["确认账号密码是否正确/是否被锁定",
         "检查认证服务（如统一认证/SSO）是否正常",
         "后端日志查看具体失败原因"],
        probability=0.9, priority="高"))

    # ============ 402 1 个 ============
    S.append(_sc(40, "付费功能未开通", ["402"],
        ["402", "payment", "开通", "授权"], ["提示需要开通功能", "提示授权过期"],
        "business", "业务功能未开通/授权过期（402）",
        [
            "F12 Network → {url} → Status 402 Payment Required",
            "响应体：该功能需要开通/授权",
            "License/授权配置检查",
        ],
        ["联系厂商开通对应功能模块",
         "检查 License/授权文件是否过期",
         "确认功能授权与当前环境匹配"],
        probability=0.9, priority="中"))

    # ============ 403 4 个 ============
    S.append(_sc(41, "用户权限不足", ["403"],
        ["403", "权限不足", "forbidden", "无权限"], ["提示权限不足", "普通用户访问管理员功能"],
        "permission", "用户权限不足（RBAC 配置问题）",
        [
            "第 1 步：F12 Network → {url} → Status 403",
            "响应体：{\"code\":403,\"message\":\"权限不足\"}",
            "当前用户角色：USER，访问的功能需要 ADMIN",
        ],
        ["联系管理员为用户分配对应权限",
         "检查 RBAC 角色-权限映射配置",
         "确认用户角色是否与功能匹配"],
        probability=1.0, priority="高"))

    S.append(_sc(42, "RBAC 配置错误", ["403"],
        ["403", "rbac", "role", "权限"], ["有权限的角色也访问不了", "权限配置混乱"],
        "permission", "RBAC 权限配置错误（角色权限映射问题）",
        [
            "F12 Network → {url} → Status 403",
            "数据库角色-权限表数据异常",
            "新加的角色未绑定菜单/接口权限",
        ],
        ["检查角色权限绑定数据（角色-菜单-接口）",
         "重新绑定角色权限并刷新缓存",
         "确认权限缓存是否需要清理（Redis）"],
        probability=0.9, priority="高"))

    S.append(_sc(43, "IP 被限制", ["403"],
        ["403", "ip", "denied", "blacklist"], ["特定 IP 无法访问", "换 IP 就能访问"],
        "network", "IP 被加入黑名单/白名单限制",
        [
            "F12 Network → {url} → Status 403",
            "服务器/防火墙有 IP 白名单规则",
            "当前出口 IP 不在白名单",
        ],
        ["将当前 IP 加入访问白名单",
         "检查防火墙/安全组 IP 限制规则",
         "确认是否需要通过 VPN 访问"],
        probability=0.9, priority="中"))

    S.append(_sc(44, "菜单隐藏但接口可调", ["403"],
        ["403", "menu", "button", "隐藏"], ["菜单没显示但接口有权限", "按钮不显示但仍能调用"],
        "config", "前端菜单/按钮权限与后端接口权限不一致",
        [
            "F12 Network → {url} → Status 403",
            "前端菜单已隐藏，但用户仍可调用该接口",
            "后端接口权限未配置",
        ],
        ["后端接口配置权限注解（@PreAuthorize 等）",
         "统一前后端权限配置（菜单-接口映射）",
         "权限变更后同步刷新缓存"],
        probability=0.85, priority="中"))

    # ============ 404 4 个 ============
    S.append(_sc(45, "前端路径错误", ["404"],
        ["404", "接口不存在", "not found", "路径"], ["提示接口不存在", "点击按钮报 404"],
        "frontend", "前端 URL 写错（后端有接口但路径不对）",
        [
            "第 1 步：F12 Network → {url} → Status 404",
            "请求路径：/api/config，实际应为 /api/system/config",
            "响应体：{\"code\":404,\"message\":\"接口不存在\"}",
        ],
        ["修正前端 API 路径（对照后端 Controller @RequestMapping）",
         "用 Postman 验证正确路径返回 200",
         "检查接口前缀（context-path）配置"],
        probability=1.0, priority="高"))

    S.append(_sc(46, "后端接口未开发", ["404"],
        ["404", "not found"], ["页面调用接口报 404", "Postman 所有路径都 404"],
        "backend", "后端接口尚未开发（所有路径均 404）",
        [
            "第 1 步：F12 Network → {url} → Status 404",
            "Postman 尝试所有可能路径 → 均 404",
            "后端 Controller 中无对应接口",
        ],
        ["确认该接口是否在开发计划中（联系后端）",
         "如果已开发，检查是否部署到当前环境",
         "前端对未开发功能做隐藏/降级处理"],
        probability=0.95, priority="高"))

    S.append(_sc(47, "接口被删除/改名", ["404"],
        ["404", "removed", "改名"], ["之前能用现在 404", "接口被下线"],
        "backend", "接口被删除或改名（未通知前端）",
        [
            "F12 Network → {url} → Status 404",
            "版本升级后接口路径变更",
            "后端代码中该接口已移除/改名",
        ],
        ["联系后端确认接口变更记录",
         "前端同步更新为新接口",
         "后端对旧接口做兼容转发（过渡期）"],
        probability=0.9, priority="中"))

    S.append(_sc(48, "静态资源 404", ["404"],
        ["404", ".js", ".css", ".png", "静态"], ["页面样式丢失/图标不显示", "控制台报资源 404"],
        "config", "静态资源路径错误（js/css/图片 404）",
        [
            "F12 Network → 静态资源 → Status 404",
            "资源路径带错前缀或版本号错误",
            "前端构建后资源路径配置（base/publicPath）错误",
        ],
        ["检查前端构建配置 publicPath/base",
         "Nginx location 静态资源路径配置",
         "确认资源文件是否被打包部署"],
        probability=0.9, priority="中"))

    # ============ 405 3 个 ============
    S.append(_sc(49, "请求方法用错", ["405"],
        ["405", "method not allowed", "请求方法"], ["接口报 405", "GET 写成 POST 或用反"],
        "backend", "请求方法错误（前端传错 GET/POST/PUT/DELETE）",
        [
            "F12 Network → {url} → Status 405",
            "后端接口是 POST，前端用了 GET",
            "响应体：Request method 'GET' not supported",
        ],
        ["前端修正请求方法（对照后端 @PostMapping/@GetMapping）",
         "检查 axios/fetch 方法配置",
         "后端对不支持的请求方法返回明确提示"],
        probability=0.95, priority="高"))

    S.append(_sc(50, "方法未实现", ["405"],
        ["405", "not implemented", "不支持"], ["接口 405 且确认方法没错", "后端未实现该方法"],
        "backend", "后端未实现该请求方法",
        [
            "F12 Network → {url} → Status 405",
            "方法正确但后端 Controller 未定义该方法映射",
            "Swagger/接口文档中无该方法",
        ],
        ["后端补充对应方法映射（如 PUT 未实现）",
         "前端改用后端已实现的方法",
         "确认接口文档与实现一致"],
        probability=0.9, priority="中"))

    S.append(_sc(51, "跨域预检请求 405", ["405"],
        ["405", "options", "preflight", "cors"], ["跨域接口报 405", "OPTIONS 预检失败"],
        "config", "跨域预检（OPTIONS）请求被拒绝 405",
        [
            "F12 Network → OPTIONS {url} → Status 405",
            "后端未处理 OPTIONS 预检请求",
            "CORS 配置缺失导致预检失败",
        ],
        ["后端配置 CORS 允许 OPTIONS 预检",
         "Nginx 层面处理 OPTIONS 请求",
         "网关统一添加 CORS 响应头"],
        probability=0.9, priority="高"))

    # ============ 406 2 个 ============
    S.append(_sc(52, "Accept 头不匹配", ["406"],
        ["406", "not acceptable", "accept"], ["接口报 406", "Accept 头要求格式后端不支持"],
        "config", "Accept 请求头与后端响应格式不匹配",
        [
            "F12 Network → {url} → Status 406",
            "请求头 Accept: application/xml，后端只返回 JSON",
            "响应体：Not Acceptable",
        ],
        ["前端 Accept 头改为 application/json",
         "后端配置内容协商支持 JSON/XML",
         "检查是否有全局响应格式转换器冲突"],
        probability=0.9, priority="中"))

    S.append(_sc(53, "响应格式不受支持", ["406"],
        ["406", "format", "格式"], ["页面要求某种格式后端不支持", "406 与响应格式相关"],
        "backend", "响应格式不受支持（内容协商失败）",
        [
            "F12 Network → {url} → Status 406",
            "请求头与响应头格式不一致",
            "后端无对应 MessageConverter",
        ],
        ["后端添加对应格式的转换器（如 XML 转 JSON）",
         "前端明确请求格式",
         "统一约定接口返回 JSON"],
        probability=0.8, priority="低"))

    # ============ 408 2 个 ============
    S.append(_sc(54, "网络延迟高", ["408"],
        ["408", "timeout", "慢"], ["接口请求超时", "网络慢导致 408"],
        "network", "网络延迟过高导致请求超时（408）",
        [
            "F12 Network → {url} → Status 408",
            "响应时间接近超时阈值",
            "ping 服务器延迟高/丢包",
        ],
        ["优化网络链路（VPN/专线）",
         "前端增大超时时间",
         "接口数据压缩传输（gzip）"],
        probability=0.85, priority="中"))

    S.append(_sc(55, "后端处理超时", ["408"],
        ["408", "timeout", "处理慢"], ["接口 408 但网络正常", "后端处理太慢"],
        "backend", "后端处理超时（客户端超时设置过短或后端慢）",
        [
            "F12 Network → {url} → Status 408",
            "网络正常但接口处理超过前端超时时间",
            "后端日志该接口耗时过长",
        ],
        ["前端增大请求超时时间",
         "后端优化接口处理（SQL/逻辑）",
         "后端超时配置与前端对齐"],
        probability=0.85, priority="中"))

    # ============ 409 3 个 ============
    S.append(_sc(56, "唯一约束冲突", ["409"],
        ["409", "conflict", "duplicate", "重复", "已存在"], ["新增提示已存在", "唯一字段重复"],
        "database", "数据库唯一约束冲突（重复数据）",
        [
            "F12 Network → {url} → Status 409",
            "响应体：Duplicate entry 'xxx' for key 'uk_xxx'",
            "数据库已存在相同记录",
        ],
        ["清理重复数据",
         "前端提交前做重复校验",
         "后端捕获唯一约束异常并友好提示"],
        probability=0.95, priority="高"))

    S.append(_sc(57, "版本冲突", ["409"],
        ["409", "version", "乐观锁"], ["多人编辑同一数据报冲突", "乐观锁版本不一致"],
        "backend", "并发版本冲突（乐观锁失败）",
        [
            "F12 Network → {url} → Status 409",
            "响应体：数据已被其他人修改，请刷新重试",
            "数据库 version 字段不一致",
        ],
        ["前端刷新数据后重试",
         "提示用户数据已被他人修改",
         "后端调整乐观锁冲突策略（合并/覆盖）"],
        probability=0.9, priority="中"))

    S.append(_sc(58, "重复提交", ["409"],
        ["409", "duplicate", "重复"], ["连续点击按钮报冲突", "重复提交被拒绝"],
        "frontend", "用户重复提交导致冲突",
        [
            "F12 Network → {url} → Status 409（第二次提交）",
            "同一请求被提交多次",
            "后端幂等校验拒绝重复提交",
        ],
        ["前端按钮防重复点击（提交后禁用）",
         "前端生成请求唯一 ID（幂等键）",
         "后端幂等接口配置调整"],
        probability=0.9, priority="中"))

    # ============ 410 2 个 ============
    S.append(_sc(59, "资源已下线未清理", ["410"],
        ["410", "gone", "已删除"], ["提示资源不存在/已删除", "旧功能还在调用已删除资源"],
        "backend", "资源已永久删除，前端仍在调用",
        [
            "F12 Network → {url} → Status 410 Gone",
            "后端确认该资源已被删除",
            "前端功能入口未下线",
        ],
        ["前端下线对应功能入口",
         "前端跳转友好提示（资源已删除）",
         "后端确认资源删除是否符合业务预期"],
        probability=0.9, priority="中"))

    S.append(_sc(60, "旧版本接口未清理", ["410"],
        ["410", "gone", "旧版本"], ["旧版本接口报 410", "升级后旧接口不可用"],
        "backend", "旧版本接口已下线（410）",
        [
            "F12 Network → 旧接口 → Status 410",
            "系统升级后旧接口被移除",
            "前端仍引用旧接口",
        ],
        ["前端升级到新接口",
         "旧接口做兼容转发（过渡期）",
         "清理浏览器缓存/重新部署前端"],
        probability=0.85, priority="中"))

    # ============ 413 3 个 ============
    S.append(_sc(61, "文件超过配置限制", ["413"],
        ["413", "payload too large", "文件过大", "超过"], ["上传报文件过大", "超过大小限制"],
        "config", "文件超过服务器配置的最大大小限制",
        [
            "第 1 步：F12 Network → {url} → Status 413",
            "响应体：{\"code\":413,\"message\":\"文件过大，超过 10MB 限制\"}",
            "application.yml：spring.servlet.multipart.max-file-size=10MB",
        ],
        ["压缩文件后再上传",
         "修改后端配置 max-file-size（需评估服务器磁盘）",
         "实现分片上传/断点续传"],
        probability=1.0, priority="高"))

    S.append(_sc(62, "请求体过大", ["413"],
        ["413", "payload too large", "body", "请求体"], ["提交大数据报 413", "JSON 请求体过大"],
        "config", "请求体超过后端/Nginx 限制（413）",
        [
            "F12 Network → {url} → Status 413",
            "请求体大小超过配置限制",
            "Nginx client_max_body_size 限制",
        ],
        ["检查 Nginx client_max_body_size 配置",
         "检查后端请求体大小限制配置",
         "大数据提交改为分页/分批接口"],
        probability=0.9, priority="中"))

    S.append(_sc(63, "Nginx 上传限制", ["413"],
        ["413", "nginx", "client_max_body_size"], ["Nginx 返回 413", "上传大文件失败"],
        "config", "Nginx 上传大小限制导致 413",
        [
            "F12 Network → {url} → Status 413",
            "Nginx 日志：client intended to send too large body",
            "nginx.conf 未配置 client_max_body_size 或值过小",
        ],
        ["nginx.conf 增加 client_max_body_size 50m;",
         "reload Nginx（nginx -s reload）",
         "确认上传走的是否是 Nginx 代理端口"],
        probability=0.95, priority="高"))

    # ============ 415 2 个 ============
    S.append(_sc(64, "Content-Type 错误", ["415"],
        ["415", "unsupported media type", "content-type"], ["提交报 415", "Content-Type 设置错误"],
        "frontend", "请求 Content-Type 与后端不匹配（415）",
        [
            "F12 Network → {url} → Status 415",
            "请求头 Content-Type: text/plain，后端期望 application/json",
            "响应体：Content type 'text/plain' not supported",
        ],
        ["前端设置正确 Content-Type（application/json）",
         "检查 axios 是否序列化了对象",
         "后端接口消费类型与前端一致"],
        probability=0.95, priority="高"))

    S.append(_sc(65, "上传格式不支持", ["415"],
        ["415", "format", "类型", "不支持"], ["上传特定格式文件被拒", "文件类型校验不过"],
        "config", "上传文件类型不被后端支持",
        [
            "F12 Network → {url} → Status 415",
            "响应体：不支持的文件类型",
            "后端仅允许指定扩展名",
        ],
        ["转换为后端支持的文件格式",
         "后端放宽文件类型限制（需评估安全）",
         "前端选择文件前做类型校验提示"],
        probability=0.9, priority="中"))

    # ============ 422 3 个 ============
    S.append(_sc(66, "字段校验失败", ["422"],
        ["422", "validation", "校验", "valid"], ["提交报字段校验失败", "422 参数错误"],
        "backend", "参数校验失败（422 字段级错误）",
        [
            "F12 Network → {url} → Status 422",
            "响应体：校验错误详情（字段+错误原因）",
            "后端 @Valid 校验未通过",
        ],
        ["前端按错误详情修正参数",
         "前端增加相同规则的校验",
         "后端校验错误信息友好化"],
        probability=0.95, priority="高"))

    S.append(_sc(67, "JSON 格式错误", ["422"],
        ["422", "json", "parse", "格式"], ["提交报 JSON 解析失败", "请求体不是合法 JSON"],
        "frontend", "请求体 JSON 格式错误（前后端序列化问题）",
        [
            "F12 Network → {url} → Status 422",
            "响应体：JSON parse error",
            "请求体含多余逗号/单引号/转义错误",
        ],
        ["前端使用 JSON.stringify 正确序列化",
         "检查手写 JSON 的语法",
         "后端返回具体解析错误位置"],
        probability=0.9, priority="中"))

    S.append(_sc(68, "参数绑定失败", ["422"],
        ["422", "binding", "bind", "绑定"], ["提交报参数绑定失败", "DTO 绑定不上"],
        "backend", "参数绑定失败（请求体与 DTO 不匹配）",
        [
            "F12 Network → {url} → Status 422",
            "响应体：Failed to bind request body",
            "请求字段与后端 DTO 字段不匹配",
        ],
        ["对照后端 DTO 调整请求字段名",
         "检查字段类型是否匹配（日期/枚举等）",
         "后端 DTO 增加必要字段"],
        probability=0.9, priority="中"))

    # ============ 429 3 个 ============
    S.append(_sc(69, "限流触发", ["429"],
        ["429", "too many requests", "频繁", "限流"], ["操作频繁提示稍后再试", "429 限流"],
        "backend", "请求频率过高触发限流（429）",
        [
            "F12 Network → {url} → Status 429",
            "响应体：{\"code\":429,\"message\":\"操作过于频繁，请稍后再试\"}",
            "Redis 限流计数器已满",
        ],
        ["等待限流窗口过后重试",
         "前端节流/防抖（按钮不可重复提交）",
         "后端调整限流阈值或加白名单"],
        probability=0.95, priority="高"))

    S.append(_sc(70, "并发过高", ["429"],
        ["429", "too many", "并发", "繁忙"], ["系统繁忙请重试", "并发超限"],
        "performance", "并发请求过高导致限流/拒绝",
        [
            "F12 Network → {url} → Status 429",
            "多用户同时操作触发限流",
            "后端限流/连接池配置不足",
        ],
        ["错峰操作（避开高峰期）",
         "后端增大限流阈值/连接池",
         "系统扩容或加缓存降低压力"],
        probability=0.85, priority="中"))

    S.append(_sc(71, "IP 被封禁", ["429"],
        ["429", "blocked", "封"], ["突然所有请求 429", "IP 被限流封禁"],
        "backend", "IP 被限流策略封禁（429）",
        [
            "F12 Network → {url} → Status 429（全部请求）",
            "换网络/换 IP 正常",
            "限流策略按 IP 封禁",
        ],
        ["等待封禁时间到期",
         "联系管理员解除封禁",
         "确认是否有脚本/爬虫误触发限流"],
        probability=0.85, priority="中"))

    # ============ 500 9 个 ============
    S.append(_sc(72, "后端 SQL 异常", ["500"],
        ["500", "sql", "sqlexception", "cannot be null", "sql语法"], ["操作失败提示服务器内部错误", "日志报 SQL 异常"],
        "backend", "后端 SQL 异常（SQL 语句/字段问题）",
        [
            "第 1 步：F12 Network → {url} → Status 500",
            "后端日志：java.sql.SQLException: Column 'email' cannot be null",
            "堆栈指向 UserMapper.java:58",
        ],
        ["修复 SQL 语句，添加字段处理",
         "检查表结构与实体字段是否匹配",
         "联系后端开发修复（附日志堆栈）"],
        probability=0.95, priority="高"))

    S.append(_sc(73, "空指针异常", ["500"],
        ["500", "nullpointer", "null", "空指针"], ["操作报 500 服务器错误", "日志空指针异常"],
        "backend", "后端空指针异常（NPE）",
        [
            "后端日志：java.lang.NullPointerException",
            "堆栈：at com.xxx.UserService.getUser(UserService.java:42)",
            "对象未判空直接调用方法",
        ],
        ["联系后端修复（对象判空处理）",
         "前端排查是否传了空值参数",
         "补充参数校验避免空值进入业务层"],
        probability=0.9, priority="高"))

    S.append(_sc(74, "数据库连接拒绝", ["500"],
        ["500", "connection refused", "connection", "无法连接"], ["接口 500 报连接拒绝", "数据库连接失败"],
        "database", "数据库连接失败（服务在但数据库不可用）",
        [
            "后端日志：Connection refused: localhost:3306",
            "数据库服务未启动或连接配置错误",
            "数据库连接池耗尽",
        ],
        ["检查数据库服务是否启动",
         "检查数据库连接配置（地址/端口/密码）",
         "重启数据库或增大连接池"],
        probability=0.95, priority="高"))

    S.append(_sc(75, "内存溢出", ["500"],
        ["500", "outofmemory", "oom", "内存"], ["接口 500 且服务卡顿", "日志 OOM"],
        "performance", "后端内存溢出（OOM）",
        [
            "后端日志：java.lang.OutOfMemoryError: Java heap space",
            "服务频繁重启/卡顿",
            "free -h → 内存不足",
        ],
        ["重启服务释放内存（临时）",
         "优化代码（大对象/缓存/分页）",
         "增大 JVM 内存参数（-Xmx）"],
        probability=0.9, priority="高"))

    S.append(_sc(76, "代码逻辑错误", ["500"],
        ["500", "exception", "error", "异常"], ["特定操作必现 500", "日志业务异常"],
        "backend", "后端代码逻辑错误（业务异常未处理）",
        [
            "F12 Network → {url} → Status 500",
            "后端日志：业务异常堆栈",
            "固定操作路径必现",
        ],
        ["联系后端修复代码逻辑",
         "前端规避触发路径（临时）",
         "后端增加全局异常处理（友好提示）"],
        probability=0.9, priority="高"))

    S.append(_sc(77, "Redis 连接失败", ["500"],
        ["500", "redis", "connection", "缓存"], ["接口 500 且与缓存有关", "日志 Redis 连接失败"],
        "service", "Redis 连接失败导致接口 500",
        [
            "后端日志：redis.clients.jedis.exceptions.JedisConnectionException",
            "redis-cli ping → 无响应",
            "Redis 服务未启动或密码错误",
        ],
        ["检查 Redis 服务状态（redis-cli ping）",
         "检查 Redis 配置（地址/端口/密码）",
         "重启 Redis 服务"],
        probability=0.9, priority="高"))

    S.append(_sc(78, "配置错误导致异常", ["500"],
        ["500", "config", "configuration", "配置"], ["部署后接口 500", "配置项缺失"],
        "config", "后端配置错误导致 500",
        [
            "F12 Network → {url} → Status 500",
            "后端日志：配置项缺失/格式错误",
            "新环境部署后出现",
        ],
        ["对照正确环境的配置文件对比",
         "检查环境变量/配置文件是否完整",
         "确认配置中心数据是否同步"],
        probability=0.85, priority="高"))

    S.append(_sc(79, "文件读写异常", ["500"],
        ["500", "file", "ioexception", "读写", "磁盘"], ["上传/下载报 500", "磁盘空间不足"],
        "config", "文件读写异常（磁盘满/权限/路径）",
        [
            "后端日志：java.io.IOException: No space left on device",
            "df -h → 磁盘使用率 95%",
            "上传目录无写权限",
        ],
        ["清理磁盘（删日志/临时文件）",
         "检查上传目录权限（chmod）",
         "检查上传路径配置是否存在"],
        probability=0.9, priority="高"))

    S.append(_sc(80, "参数为 null 未校验", ["500"],
        ["500", "null", "参数"], ["空参数提交导致 500", "日志提示参数为 null"],
        "backend", "后端对空参数未做校验导致异常",
        [
            "F12 Network → {url} → Status 500",
            "请求参数为空但后端直接使用",
            "后端日志：argument is null",
        ],
        ["后端补充参数校验（@NotNull/@Valid）",
         "前端提交前校验必填",
         "后端对空值做兜底处理"],
        probability=0.85, priority="中"))

    # ============ 501 1 个 ============
    S.append(_sc(81, "HTTP 方法不支持", ["501"],
        ["501", "not implemented", "不支持"], ["接口 501 方法不支持", "后端未实现该功能"],
        "backend", "后端未实现该请求方法（501）",
        [
            "F12 Network → {url} → Status 501",
            "后端未实现对应方法/功能",
            "接口文档中无该功能",
        ],
        ["后端实现对应方法",
         "前端改用已实现接口",
         "确认功能是否在需求范围内"],
        probability=0.9, priority="中"))

    # ============ 502 3 个 ============
    S.append(_sc(82, "后端服务崩溃", ["502"],
        ["502", "bad gateway", "网关"], ["提示 502 Bad Gateway", "页面打不开"],
        "service", "后端服务崩溃/未启动（502）",
        [
            "第 1 步：F12 Network → {url} → Status 502",
            "响应内容：Bad Gateway",
            "进程检查：ps -ef | grep java → 无进程",
        ],
        ["重启后端服务（java -jar / docker restart）",
         "检查服务崩溃原因（OOM/异常退出）",
         "Nginx reload 后验证"],
        probability=1.0, priority="高"))

    S.append(_sc(83, "Nginx 配置错误", ["502"],
        ["502", "bad gateway", "proxy_pass", "upstream"], ["502 且后端服务正常", "Nginx 代理配置错"],
        "config", "Nginx 代理配置错误导致 502",
        [
            "F12 Network → {url} → Status 502",
            "后端服务正常（直连端口可访问）",
            "Nginx 日志：upstream 配置错误/无法连接",
        ],
        ["检查 nginx.conf proxy_pass 地址/端口",
         "nginx -t 检查配置语法",
         "确认 upstream 节点权重/健康状态"],
        probability=0.95, priority="高"))

    S.append(_sc(84, "后端端口错误", ["502"],
        ["502", "bad gateway", "connect refused", "端口"], ["502 且日志报连接拒绝", "Nginx 连不上后端端口"],
        "config", "Nginx 转发端口与后端实际端口不一致",
        [
            "Nginx 日志：connect() failed (111: Connection refused)",
            "后端实际监听端口与 proxy_pass 配置端口不同",
            "netstat -tlnp 查看后端实际端口",
        ],
        ["修改 proxy_pass 端口为后端实际端口",
         "或修改后端启动端口对齐 Nginx 配置",
         "修改后 nginx -s reload"],
        probability=0.95, priority="高"))

    # ============ 503 2 个 ============
    S.append(_sc(85, "服务维护中", ["503"],
        ["503", "service unavailable", "维护"], ["提示服务维护中", "503 服务不可用"],
        "service", "服务维护/暂时不可用（503）",
        [
            "F12 Network → {url} → Status 503",
            "响应内容：Service Unavailable",
            "服务维护开关/维护页面已开启",
        ],
        ["确认服务维护计划与时间",
         "维护结束后恢复正常",
         "确认是否有维护开关被误开启"],
        probability=0.95, priority="高"))

    S.append(_sc(86, "服务过载", ["503"],
        ["503", "overload", "过载", "busy"], ["系统繁忙 503", "服务过载不可用"],
        "performance", "服务过载导致 503",
        [
            "F12 Network → {url} → Status 503",
            "服务器 CPU/内存负载高",
            "top → load average 过高",
        ],
        ["错峰访问，缓解压力",
         "重启服务或扩容",
         "优化慢查询/加缓存降低负载"],
        probability=0.9, priority="高"))

    # ============ 504 5 个 ============
    S.append(_sc(87, "慢 SQL 超时", ["504"],
        ["504", "timeout", "慢", "slow"], ["导出报表超时", "查询大数据量 504"],
        "performance", "后端慢 SQL 导致网关超时（504）",
        [
            "第 1 步：F12 Network → {url} → 先 pending 后 504",
            "数据库 show processlist → 慢 SQL",
            "Slow query log: Query took 28 秒",
        ],
        ["优化 SQL（加索引/减少 JOIN）",
         "分页查询避免一次加载大量数据",
         "大数据量导出改异步任务"],
        probability=1.0, priority="高"))

    S.append(_sc(88, "网关超时配置短", ["504"],
        ["504", "timeout", "proxy_read_timeout"], ["接口 504 但后端其实处理完了", "网关超时太短"],
        "config", "Nginx 网关超时配置过短导致 504",
        [
            "F12 Network → {url} → Status 504",
            "Nginx 日志：upstream timed out",
            "后端日志显示处理成功但耗时 > proxy_read_timeout",
        ],
        ["调大 proxy_read_timeout / proxy_connect_timeout",
         "长耗时接口改异步处理",
         "确认后端处理时间是否合理"],
        probability=0.95, priority="高"))

    S.append(_sc(89, "第三方接口超时", ["504"],
        ["504", "third party", "外部", "feign", "httpclient"], ["接口 504 且依赖外部系统", "调用第三方超时"],
        "performance", "第三方接口调用超时导致 504",
        [
            "F12 Network → {url} → Status 504",
            "后端日志：调用外部接口超时",
            "第三方服务响应慢/不可用",
        ],
        ["确认第三方系统状态",
         "后端增加第三方调用超时与重试机制",
         "第三方接口做降级/缓存"],
        probability=0.9, priority="中"))

    S.append(_sc(90, "服务卡死无响应", ["504"],
        ["504", "卡死", "hang", "死锁"], ["所有接口都 504", "服务卡死无响应"],
        "service", "后端服务卡死/死锁导致超时",
        [
            "F12 Network → 所有接口 → 504/pending",
            "线程 dump 显示死锁/线程池耗尽",
            "jstack 查看线程状态",
        ],
        ["重启服务（临时恢复）",
         "排查死锁/线程池配置",
         "增加健康检查自动重启机制"],
        probability=0.9, priority="高"))

    S.append(_sc(91, "连接池耗尽", ["504"],
        ["504", "connection pool", "连接池", "waiting"], ["接口 504 且日志提示连接池耗尽", "等待数据库连接超时"],
        "performance", "数据库/HTTP 连接池耗尽导致超时",
        [
            "后端日志：Connection pool exhausted / waiting for connection",
            "连接池大小配置过小",
            "存在连接未释放（泄漏）",
        ],
        ["增大连接池大小",
         "排查连接泄漏（未关闭的连接）",
         "重启服务释放连接"],
        probability=0.9, priority="中"))

    # ============ 505 1 个 ============
    S.append(_sc(92, "HTTP 版本不支持", ["505"],
        ["505", "http version", "version not supported"], ["提示 HTTP 版本不支持", "老浏览器访问失败"],
        "network", "HTTP 版本不支持（客户端过旧或协议不兼容）",
        [
            "F12 Network → {url} → Status 505",
            "服务器仅支持 HTTP/1.1，客户端使用 HTTP/2 或过旧",
            "浏览器版本过旧",
        ],
        ["升级浏览器到新版",
         "检查 Nginx/服务器 HTTP 协议配置",
         "确认客户端与服务端协议版本兼容"],
        probability=0.9, priority="低"))

    # 校验数量
    assert len(S) == 92, "场景数量必须为 92，当前为 %d" % len(S)
    return S


SCENARIOS = build_scenarios()
