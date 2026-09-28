# 🔍 故障检测系统

> 智能故障检测与诊断系统 —— 面向现场实施/交付/运维的一键排障工具。
> 输入故障 URL 自动执行 7 步检测流程 + 差分隔离实验，锁定问题根因，输出可直接汇报的 3 段式诊断报告，
> 并针对每次故障动态教学手动排障方法；自带**可信度自检**，可随时验证"系统查得准不准"。

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🛠️ **一键诊断** | 输入 URL + 症状描述，自动完成 7 步 SOP 检测流程 |
| ⚡ **快速检测** | HTTP/TCP 并行探测，不可达场景数秒出结果 |
| 📚 **完整知识库** | 内置 **132 个故障场景** × **59 个 HTTP/业务状态码**，覆盖企业内网常见码 |
| 🧪 **系统自检** | 一键校验"观测层"一致率：独立真值 vs 系统实测逐条比对，可自行复现，不必信任口头承诺 |
| 🔬 **差分隔离实验** | 在主流程外追加只读对照请求（去认证头 / 换 HEAD / 换协议 / 追加随机参数 / 原样重放），用差异反推根因 |
| 📸 **证据链闭环** | 每步检测生成实时证据 + 知识库证据，可直接截图汇报 |
| 📝 **3 段式报告** | 现象 → 排查过程 → 结论，一键复制汇报文本 |
| 🎓 **动态教学** | 检测后自动展示"本次故障 · 手动排障教学"，按状态码 + 归属动态对应，另附 7 步总纲 |
| 🌓 **日夜模式** | 一键切换亮色/深色主题，选择持久化保存 |
| 🔄 **刷新页面** | 顶部一键刷新页面数据 |
| ➕ **自定义扩展** | 随时新增场景，立即生效并持久化保存 |
| 🖥️ **单文件 EXE** | 打包为**一个 exe**（内嵌 JRE），双击即用，目标机无需安装 Java |
| 📦 **绿色版免安装** | jar + 内嵌 JRE + 启动器，整目录拷走即用（另一种分发形态） |
| 🤖 **可选 LLM 增强** | 默认关闭；配置后可对结论做自然语言增强，调用失败自动降级，不影响规则结论 |
| 🐳 **容器化部署** | Docker 一条命令启动，数据持久化到宿主机 |
| 🔗 **接口一致** | 与原 Python 版 API 对齐（Python 版保留在 `backend/`） |

## 🧱 技术栈

- **后端**：Java 11 + Spring Boot 2.7.18（Tomcat 9）
- **前端**：Vue 3 + Vite 5（生产构建产物由后端静态资源托管，静态资源禁用缓存）
- **数据**：SQLite（检测历史）+ JSON（知识库 / 自定义场景）
- **运行环境自检**：JDK 内置 `java.net.http.HttpClient`（被测）与 `java.net.HttpURLConnection`（独立真值）双实现比对
- **部署**：单文件 EXE（内嵌 jlink 精简 JRE）/ 绿色版目录 / Docker（多阶段构建）

## 🚀 快速开始

### 方式一：单文件 EXE（推荐给现场，零依赖）

只需分发**一个文件**，双击即用：

```
故障检测系统.exe          # 约 64 MB，内含 Spring Boot jar + 精简 JRE
```

- 双击后自动展开运行环境 → 拉起服务 → 打开浏览器（首次约 7 秒，之后约 5 秒）
- **目标机器无需安装 Java**，无需附带任何文件夹
- 运行环境与数据落在固定位置，升级 / 换位置都不丢历史：

| 内容 | 路径 |
|------|------|
| 运行环境（自动解压） | `%LOCALAPPDATA%\FaultDetectSystem\payload-<版本>\` |
| 业务数据（历史记录） | `%LOCALAPPDATA%\FaultDetectSystem\data\` |

- 托盘图标：双击打开界面 / 右键退出系统
- 退出后再次双击：若服务已在运行，直接打开浏览器，不会重复启动

### 方式二：绿色版目录

```
故障检测系统\
├─ 故障检测系统.exe        # 启动器（C# 编译）
├─ app\fault-detect-system.jar
├─ runtime\               # jlink 精简 JRE 11.0.32，约 52 MB
└─ data\                  # 运行数据
```

整目录拷到任意位置双击 exe 即可，同样无需目标机安装 Java。

### 方式三：Docker

```bash
docker compose -f docker-compose-java.yml up -d
# 访问 http://localhost:8000
```

- 数据持久化到项目根 `data/` 目录（容器删除不丢失）
- 停止：`docker compose -f docker-compose-java.yml down`
- 重新部署（改代码后）：`docker compose -f docker-compose-java.yml up -d --build`

### 方式四：本地 jar（需 JDK 11）

```bash
java -jar java-backend/target/fault-detect-system.jar
# 首次需先打包：
# mvn -f java-backend/pom.xml clean package -DskipTests
```

### 方式五：双击脚本（Windows）

```
双击 start-java.bat          # 首次自动用 Maven 打包并打开浏览器
双击 故障检测系统-一键启动.exe # 托盘启动器（源码布局，自动向上查找 jar）
```

## 🧪 系统自检（怎么验证它查得准）

排障系统的价值建立在"看得准"之上：一旦观测层把 404 错判成超时，后面推理再精致也只是错误结论。
因此系统内置**一键自检**（前端页签「🧪 系统自检」，接口 `GET /api/selfcheck`）：

- **真值与被测走两条互不依赖的实现**：真值用 `HttpURLConnection`（JDK 另一套实现），被测用系统实际使用的 `HttpClient`
- **用例自包含**：内置一个只绑 `127.0.0.1` 随机端口的极简测试服务，随自检结束即关闭，换任何机器都能跑
- **只读安全**：全部为 GET 请求，不对任何目标产生写操作

内置 8 条用例及当前结果：

| 用例 | 场景 | 期望 |
|------|------|------|
| SC_01 | 本地 200（UTF-8 中文） | 一致 |
| SC_02 | 本地 404 | 一致 |
| SC_03 | 本地 500（含异常堆栈关键词） | 一致 |
| SC_04 | GBK 响应（`Content-Type` 声明 `charset=GBK`） | 一致 + 中文未乱码 |
| SC_05 | GBK 响应（**响应头不声明 charset**，需回退探测） | 一致 + 中文未乱码 |
| SC_06 | 服务端挂起不响应 | 一致（TIMEOUT） |
| SC_07 | 端口无人监听 | 一致（CONN_REFUSED） |
| SC_08 | 域名无法解析 | 见下方"已知边界" |

当前实测：**8/8，一致率 100%**。

### 🔎 可信度分三层，请按层看待结论

| 层级 | 内容 | 可信度 | 如何验证 |
|------|------|--------|----------|
| 观测层 | 状态码、耗时、响应体、连通性 | **高** | 系统自检 8 条真值用例，可随时复跑 |
| 归因层 | 由现象推断"哪一层出问题" | 中（启发式） | 结合差分隔离实验的对照结果交叉判断 |
| 结论层 | 最终结论与修复建议 | 中（带置信度） | 建议按报告中的排查步骤现场复核 |

### ⚠️ 已知边界（不掩盖）

1. **SC_08 为已声明的粒度差异**：真值为 DNS 解析失败，系统归入"连接被拒绝 / 网络不通"。
   故障层次判断正确，但未细分 DNS 与端口两类原因，自检页会以黄字如实标注。
2. **状态码 200 时归因依赖响应体特征**：若响应正常但业务其实异常（如空数组、业务码藏在 body 里），
   观测层拿不到更多事实，归因层只能基于响应体与症状启发式推测，需人工复核。

## 📖 使用说明

详细操作指南见 [使用指南-Java版.md](使用指南-Java版.md)，涵盖：

- 快速开始与启动自检
- 6 个功能页签 + 日夜模式/刷新按钮
- 检测结果逐块解读 + 动态教学模块
- 新增自定义场景完整示例
- 常见问题 FAQ
- 系统维护（备份/恢复/重新部署）

## 🌐 访问方式

| 场景 | 地址 |
|------|------|
| 本机 | http://localhost:8000 |
| 局域网（手机/其他电脑） | http://<本机IP>:8000 |
| 健康检查 | http://localhost:8000/api/health |
| 系统自检 | http://localhost:8000/api/selfcheck |

> 前端静态资源已禁用缓存（`Cache-Control: no-store`），改版后浏览器始终加载最新页面，无需清缓存。

## 🔌 API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 前端页面 |
| GET | `/api/health` | 健康检查 |
| GET | `/api/selfcheck` | **系统自检**（观测层一致率 + 逐条明细） |
| GET | `/api/status-codes` | 59 个状态码知识库 |
| GET | `/api/scenarios?code=500` | 场景列表（支持状态码过滤） |
| POST | `/api/detect` | 执行故障检测 |
| POST | `/api/add-scenario` | 新增场景 |
| GET | `/api/history` | 检测历史 |
| DELETE | `/api/history` | 清空检测历史 |
| GET | `/api/stats` | 统计信息 |

`POST /api/detect` 主要字段：

| 字段 | 说明 |
|------|------|
| `url` | 目标地址（必填） |
| `symptom` | 症状描述 |
| `headers` | 自定义请求头（Token / Cookie 等） |
| `timeout` | 探测超时（秒） |
| `enable_service_check` | 是否做服务存活检测 |
| `enable_contrast` | 是否启用差分隔离实验（默认开启，只读） |
| `enable_llm` | 是否请求 LLM 增强（默认关闭） |

## 📁 目录结构

```
fault-detect-system/
├── java-backend/            # Java 后端（Spring Boot 2.7）
│   ├── pom.xml              # Maven 配置
│   ├── Dockerfile           # Docker 多阶段构建
│   └── src/main/
│       ├── java/com.faultdetect/   # 后端源码（12 个类）
│       │   ├── ApiController.java        # 接口层
│       │   ├── DetectEngine.java         # 7 步检测主引擎（HTTP/TCP 探测）
│       │   ├── ContrastProbe.java        # 差分隔离实验（只读对照矩阵 + 推导规则）
│       │   ├── MatcherEngine.java        # 场景匹配
│       │   ├── HypothesisEngine.java     # 假设与归因
│       │   ├── SelfCheckService.java     # 系统自检（独立真值比对）
│       │   ├── Knowledge.java            # 知识库加载
│       │   ├── HistoryRepository.java    # SQLite 历史
│       │   └── LlmAdvisor.java           # 可选 LLM 增强
│       └── resources/                    # 配置 + 知识库 JSON + 前端构建产物
├── frontend-vue3/           # Vue3 前端工程（Vite 5，源码）
│   └── src/components/      # 检测 / 场景库 / 状态码 / 历史 / 新增 / 自检 组件
├── tools/                   # 启动器源码与编译产物
│   ├── launcher.cs                    # 绿色版目录启动器
│   └── single-exe-launcher.cs         # 单文件 EXE 启动器（内嵌 payload）
├── frontend/static/         # 原静态版前端（保留）
├── backend/                 # 原 Python 版（保留，功能相同）
├── data/                    # 运行数据（不入库：SQLite 历史 + 自定义场景）
├── docker-compose-java.yml  # Docker 编排
├── start-java.bat           # Windows 一键启动脚本
└── 使用指南-Java版.md        # 详细使用文档
```

## 🔍 检测流程

```
0️⃣ URL 解析 → 服务存活检测（TCP）     （HTTP/TCP 并行执行，提升速度）
1️⃣ HTTP 请求探测 → 捕获状态码/耗时/响应体
2️⃣ 状态码分析 → 对照 SOP 速查表
3️⃣ 响应数据分析 → 提取特征关键词
4️⃣ 证据收集 → 固定 3 样证据
5️⃣ 分支判断 → 500 查日志 / 200 空数组查数据库 / 其他自动完成前后端隔离验证（等价 Postman）
6️⃣ 差分隔离实验 → 只读对照请求（去认证头 / 换 HEAD / 换协议 / 追加随机参数 / 原样重放），用差异反推根因
```

检测完成后，结果区下方自动展示 **📖 本次故障 · 手动排查教学**（按本次状态码 + 问题归属动态对应），
并可按需展开 7 步手动排障总纲。

## 🛠️ 开发构建

### 前端 + 后端

```bash
# ① 修改前端（Vue3 工程，需 Node）
cd frontend-vue3
npm install        # 首次
npm run build      # 构建 dist
# ② 集成构建产物到后端（复制 dist/* → java-backend/src/main/resources/static/）
# ③ 重新打包 jar
mvn -f java-backend/pom.xml clean package -DskipTests
# ④ 重新构建 Docker 镜像并启动
docker compose -f docker-compose-java.yml up -d --build
```

> 前端开发模式（热更新）：`cd frontend-vue3 && npm run dev`，访问 http://localhost:5173（自动代理 /api 到 8000）。
> 仅改后端代码（Java）时，只需 ③④ 两步。

### 打包单文件 EXE

```powershell
# ① 生成精简 JRE（约 52 MB，jdk.crypto.ec 缺失会导致 HTTPS 握手失败，必须带上）
jlink --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs `
      --strip-debug --no-header-files --no-man-pages --compress=2 --output runtime

# ② 组织 payload：app\fault-detect-system.jar + runtime\
#    打成 payload.zip（jar 已是压缩格式，可只存不压，节省打包时间）

# ③ 把 payload 内嵌进启动器，编译出单文件 exe
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe `
  /target:winexe /codepage:65001 /optimize+ `
  /r:System.Windows.Forms.dll /r:System.Drawing.dll `
  /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll `
  "/resource:payload.zip,payload.zip" `
  "/out:故障检测系统.exe" tools\single-exe-launcher.cs
```

> 修改内嵌内容后，需同步提升 `single-exe-launcher.cs` 中的 `PayloadVersion`，
> 目标机才会重新解压运行环境。

## 📄 许可证

本项目为面向现场实施 / 交付 / 运维场景的故障诊断工具，代码已脱敏处理，可自由参考。
