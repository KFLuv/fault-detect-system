# 🔍 故障检测系统（Java 版）

> 智能故障检测与诊断系统 —— 面向现场实施/交付/运维的一键排障工具。
> 输入故障 URL 自动执行 7 步检测流程，锁定问题根因，输出可直接汇报的 3 段式诊断报告。

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🛠️ **一键诊断** | 输入 URL + 症状描述，自动完成 7 步 SOP 检测流程 |
| 📚 **完整知识库** | 内置 **92 个故障场景** × **32 个 HTTP 状态码** |
| 📸 **证据链闭环** | 每步检测生成实时证据 + 知识库证据，可直接截图汇报 |
| 📝 **3 段式报告** | 现象 → 排查过程 → 结论，一键复制汇报文本 |
| ➕ **自定义扩展** | 随时新增场景，立即生效并持久化保存 |
| 🐳 **容器化部署** | Docker 一条命令启动，数据持久化到宿主机 |
| 🔗 **接口一致** | 与原 Python 版 8 个 API 完全对齐（逐项对比验证） |

## 🧱 技术栈

- **后端**：Java 11 + Spring Boot 2.7.18（Tomcat 9）
- **前端**：原生 HTML/CSS/JavaScript（由后端静态资源托管，无需 Node）
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

### 方式二：本地 jar（需 JDK 11）

```bash
java -jar java-backend/target/fault-detect-system.jar
# 首次需先打包：
# mvn -f java-backend/pom.xml clean package -DskipTests
```

## 📖 使用说明

详细操作指南见 [使用指南-Java版.md](使用指南-Java版.md)，涵盖：

- 快速开始与启动自检
- 5 个功能页签的操作方法
- 检测结果逐块解读
- 新增自定义场景完整示例
- 13 条常见问题 FAQ
- 系统维护（备份/恢复/重新部署）

## 🌐 访问方式

| 场景 | 地址 |
|------|------|
| 本机 | http://localhost:8000 |
| 局域网（手机/其他电脑） | http://<本机IP>:8000 |
| 健康检查 | http://localhost:8000/api/health |

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
│       └── resources/                  # 配置 + 知识库 JSON + 前端页面
├── frontend/static/         # 前端源文件（index.html / style.css / app.js）
├── backend/                 # 原 Python 版（保留，功能相同）
├── data/                    # 运行数据（不入库：SQLite 历史 + 自定义场景）
├── docker-compose-java.yml  # Docker 编排
├── start-java.bat           # Windows 一键启动脚本
└── 使用指南-Java版.md        # 详细使用文档
```

## 🔍 检测流程（7 步 SOP）

```
0️⃣ URL 解析 → 服务存活检测（TCP）
1️⃣ HTTP 请求探测 → 捕获状态码/耗时/响应体
2️⃣ 状态码分析 → 对照 SOP 速查表
3️⃣ 响应数据分析 → 提取特征关键词
4️⃣ 证据收集 → 固定 3 样证据
5️⃣ 分支判断 → 500查日志 / 200空数组查数据库 / 其他Postman隔离验证
```

## 🛠️ 开发构建

```bash
# 重新打包 jar
mvn -f java-backend/pom.xml clean package -DskipTests

# 重新构建 Docker 镜像并启动
docker compose -f docker-compose-java.yml up -d --build
```

> 修改前端后需 **frontend/static/** 与 **java-backend/src/main/resources/static/** 两处同步，再重新打包。

## 📄 许可证

内部项目，仅供驻场实施团队使用。
