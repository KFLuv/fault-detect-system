# 🔍 故障检测系统

> 智能故障检测与诊断系统 —— 面向现场实施/交付/运维的一键排障工具。
> 输入故障 URL 自动执行 7 步检测流程，锁定问题根因，输出可直接汇报的 3 段式诊断报告，并针对每次故障动态教学手动排障方法。

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🛠️ **一键诊断** | 输入 URL + 症状描述，自动完成 7 步 SOP 检测流程 |
| ⚡ **快速检测** | HTTP/TCP 并行探测，不可达场景数秒出结果（较原版耗时大幅下降） |
| 📚 **完整知识库** | 内置 **92 个故障场景** × **32 个 HTTP 状态码** |
| 📸 **证据链闭环** | 每步检测生成实时证据 + 知识库证据，可直接截图汇报 |
| 📝 **3 段式报告** | 现象 → 排查过程 → 结论，一键复制汇报文本 |
| 🎓 **动态教学** | 检测后自动展示"本次故障 · 手动排障教学"，按状态码 + 归属动态对应，另附 7 步总纲供系统学习 |
| 🌓 **日夜模式** | 一键切换亮色/深色主题，选择持久化保存 |
| 🔄 **刷新页面** | 顶部一键刷新页面数据 |
| ➕ **自定义扩展** | 随时新增场景，立即生效并持久化保存 |
| 🐳 **容器化部署** | Docker 一条命令启动，数据持久化到宿主机 |
| 🔗 **接口一致** | 与原 Python 版 8 个 API 完全对齐（逐项对比验证） |

## 🧱 技术栈

- **后端**：Java 11 + Spring Boot 2.7.18（Tomcat 9）
- **前端**：Vue 3 + Vite 5（生产构建产物由后端静态资源托管，静态资源禁用缓存）
- **数据**：SQLite（检测历史）+ JSON（自定义场景）
- **部署**：Docker（多阶段构建，无需本机 Maven/Java）

## 🚀 快速开始

### 方式一：Docker（推荐）

```bash
# 项目根目录执行
docker compose -f docker-compose-java.yml up -d
# 访问 http://localhost:8000
```

- 数据持久化到项目根 `data/` 目录（容器删除不丢失）
- 停止：`docker compose -f docker-compose-java.yml down`
- 重新部署（改代码后）：`docker compose -f docker-compose-java.yml up -d --build`

### 方式二：本地 jar（需 JDK 11）

```bash
java -jar java-backend/target/fault-detect-system.jar
# 首次需先打包：
# mvn -f java-backend/pom.xml clean package -DskipTests
```

### 方式三：双击脚本（Windows）

```
双击 start-java.bat（首次自动用 Maven 打包并打开浏览器）
```

## 📖 使用说明

详细操作指南见 [使用指南-Java版.md](使用指南-Java版.md)，涵盖：

- 快速开始与启动自检
- 5 个功能页签 + 日夜模式/刷新按钮
- 检测结果逐块解读 + 动态教学模块
- 新增自定义场景完整示例
- 13 条常见问题 FAQ
- 系统维护（备份/恢复/重新部署）

## 🌐 访问方式

| 场景 | 地址 |
|------|------|
| 本机 | http://localhost:8000 |
| 局域网（手机/其他电脑） | http://<本机IP>:8000 |
| 健康检查 | http://localhost:8000/api/health |

> 前端静态资源已禁用缓存（`Cache-Control: no-store`），改版后浏览器始终加载最新页面，无需清缓存。

## 🔌 API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 前端页面 |
| GET | `/api/health` | 健康检查 |
| GET | `/api/status-codes` | 32 个状态码知识库 |
| GET | `/api/scenarios?code=500` | 场景列表（支持状态码过滤） |
| POST | `/api/detect` | 执行故障检测 |
| POST | `/api/add-scenario` | 新增场景 |
| GET | `/api/history` | 检测历史 |
| GET | `/api/stats` | 统计信息 |

## 📁 目录结构

```
fault-detect-system/
├── java-backend/            # Java 后端（Spring Boot 2.7）
│   ├── pom.xml              # Maven 配置
│   ├── Dockerfile           # Docker 多阶段构建
│   └── src/main/
│       ├── java/com/cgn/faultdetect/   # 后端源码（8 个类）
│       └── resources/                  # 配置 + 知识库 JSON + 前端构建产物
├── frontend-vue3/           # Vue3 前端工程（Vite 5，源码）
│   └── src/components/      # 检测 / 场景库 / 状态码 / 历史 / 新增 / 教学组件
├── frontend/static/         # 原静态版前端（保留）
├── backend/                 # 原 Python 版（保留，功能相同）
├── data/                    # 运行数据（不入库：SQLite 历史 + 自定义场景）
├── docker-compose-java.yml  # Docker 编排
├── start-java.bat           # Windows 一键启动脚本
└── 使用指南-Java版.md        # 详细使用文档
```

## 🔍 检测流程（7 步 SOP）

```
0️⃣ URL 解析 → 服务存活检测（TCP）     （HTTP/TCP 并行执行，提升速度）
1️⃣ HTTP 请求探测 → 捕获状态码/耗时/响应体
2️⃣ 状态码分析 → 对照 SOP 速查表
3️⃣ 响应数据分析 → 提取特征关键词
4️⃣ 证据收集 → 固定 3 样证据
5️⃣ 分支判断 → 500查日志 / 200空数组查数据库 / 其他自动完成前后端隔离验证（等价 Postman）
```

检测完成后，结果区下方自动展示 **📖 本次故障 · 手动排查教学**（按本次状态码 + 问题归属动态对应），并可按需展开 7 步手动排障总纲。

## 🛠️ 开发构建

```bash
# ① 修改前端（Vue3 工程，需 Node）
cd frontend-vue3
npm install        # 首次
npm run build      # 构建 dist
# ② 集成构建产物到后端（自动复制 dist → java-backend/src/main/resources/static/）
# ③ 重新打包 jar
mvn -f java-backend/pom.xml clean package -DskipTests
# ④ 重新构建 Docker 镜像并启动
docker compose -f docker-compose-java.yml up -d --build
```

> 前端开发模式（热更新）：`cd frontend-vue3 && npm run dev`，访问 http://localhost:5173（自动代理 /api 到 8000）。
> 仅改后端代码（Java）时，只需 ③④ 两步。

## 📄 许可证

内部项目，仅供驻场实施团队使用。
