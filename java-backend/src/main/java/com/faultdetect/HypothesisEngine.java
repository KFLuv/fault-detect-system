package com.faultdetect;

import com.faultdetect.DetectEngine.DetectionResult;
import com.faultdetect.DetectEngine.ProbeResult;
import com.faultdetect.Knowledge.Scenario;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【P2】通用推理层（HypothesisEngine）
 *
 * 与「状态码查表」的本质区别：
 *   - 场景库（scenarios.json）只作为「高置信快速路径」：置信度 ≥ {@link #FAST_PATH_MIN_CONFIDENCE} 时直接采纳；
 *   - 其余情况一律走本类：把本次观测事实（状态码 / 响应关键信息 / 异常类名 / 堆栈线索 / 差分结论）喂给一组
 *     「通用技术规则」，产出**多假设排序结果**。
 *   - 规则描述的是通用技术事实（例如「selectOne 返回多行」「空指针」「唯一约束冲突」），
 *     **不随业务增长**，因此遇到知识库里没有的场景/状态码（如 417）同样能给出结论。
 *
 * 每条假设都包含：假设描述、置信度、支持证据、反证条件、可执行验证动作。
 */
@Component
public class HypothesisEngine {

    /** 知识库命中的场景置信度 ≥ 该阈值时，视为「高置信快速路径」，直接采纳场景库结论 */
    public static final double FAST_PATH_MIN_CONFIDENCE = 0.6;
    /** 输出的最大假设条数（按置信度降序取前 N 条） */
    public static final int MAX_HYPOTHESES = 5;
    /** 响应耗时超过该毫秒数时给出「响应偏慢」假设 */
    private static final int SLOW_MS = 3000;

    // ================= 结果容器 =================

    /** 单条假设 */
    public static class Hypothesis {
        public String id;                 // H_DB_MULTI_ROW
        public String title;              // 数据库单行查询返回了多行
        public String category;           // 归属 key（与 scenarios.json 的 category_labels 一致）
        public String category_label;     // 归属中文名
        public String source;             // rule（通用规则）/ contrast（差分推导）/ knowledge（场景库）
        public String description;        // 假设描述
        public double confidence;         // 置信度 0-1
        public List<String> supported_by = new ArrayList<>();    // 支持证据（引用本次观测）
        public List<String> refute_if = new ArrayList<>();       // 反证条件（什么情况下该假设不成立）
        public List<String> verify_actions = new ArrayList<>();  // 可执行验证动作

        // ---- 链式构造辅助（非 getter，不参与 JSON 序列化） ----
        Hypothesis ev(String... s) {
            supported_by.addAll(Arrays.asList(s));
            return this;
        }

        Hypothesis refute(String... s) {
            refute_if.addAll(Arrays.asList(s));
            return this;
        }

        Hypothesis verify(String... s) {
            verify_actions.addAll(Arrays.asList(s));
            return this;
        }
    }

    /** 推理结果 */
    public static class InferenceResult {
        public List<Hypothesis> hypotheses = new ArrayList<>();
        /** 最高置信假设（无任何假设时为 null） */
        public Hypothesis top;

        public List<Map<String, Object>> toMaps() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Hypothesis h : hypotheses) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", h.id);
                m.put("title", h.title);
                m.put("category", h.category);
                m.put("category_label", h.category_label);
                m.put("source", h.source);
                m.put("description", h.description);
                m.put("confidence", h.confidence);
                m.put("supported_by", h.supported_by);
                m.put("refute_if", h.refute_if);
                m.put("verify_actions", h.verify_actions);
                out.add(m);
            }
            return out;
        }
    }

    // ================= 观测事实 =================

    /** 本次检测观测到的事实集合（规则的唯一输入） */
    static class Facts {
        Integer statusCode;
        String normalized = "";
        String statusText = "";
        String body = "";
        String bodyLower = "";
        String contentType = "";
        Integer responseTimeMs;
        String errorText = "";
        Map<String, String> keyInfo = Collections.emptyMap();
        String keyInfoText = "";
        ContrastProbe.ContrastResult contrast;

        /** 关键词命中判定：在响应关键信息 / 响应体 / 错误信息 / 状态行中任意一处出现即命中 */
        boolean has(String kw) {
            if (kw == null || kw.isEmpty()) {
                return false;
            }
            return keyInfoText.contains(kw) || bodyLower.contains(kw)
                    || errorText.contains(kw) || statusText.contains(kw)
                    || normalized.contains(kw);
        }

        /** 任一关键词命中 */
        boolean hasAny(String... kws) {
            for (String k : kws) {
                if (has(k)) {
                    return true;
                }
            }
            return false;
        }

        boolean code(int c) {
            return statusCode != null && statusCode == c;
        }

        /** 取响应关键信息中键名包含 keyPart 的值（如 "业务信息" → message 原文） */
        String keyInfoValue(String keyPart) {
            for (Map.Entry<String, String> e : keyInfo.entrySet()) {
                if (e.getKey() != null && e.getKey().contains(keyPart)) {
                    return e.getValue();
                }
            }
            return null;
        }

        String keyInfoSummary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : keyInfo.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.length() == 0 ? "（无）" : sb.toString();
        }

        String statusDesc() {
            if (statusCode != null) {
                return String.valueOf(statusCode);
            }
            return errorText.isEmpty() ? "无响应" : "无响应（" + errorText + "）";
        }
    }

    private final Knowledge knowledge;

    public HypothesisEngine(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    private static Facts factsOf(DetectionResult dr) {
        Facts f = new Facts();
        ProbeResult p = dr.probe;
        if (p != null) {
            f.statusCode = p.statusCode;
            f.statusText = p.statusText == null ? "" : p.statusText.toLowerCase();
            f.body = p.body == null ? "" : p.body;
            f.bodyLower = f.body.toLowerCase();
            f.responseTimeMs = p.responseTimeMs;
            f.errorText = p.error == null ? "" : p.error.toLowerCase();
            f.contentType = headerOf(p, "content-type");
        }
        f.normalized = dr.normalizedStatus == null ? "" : dr.normalizedStatus.toLowerCase();
        f.keyInfo = dr.keyInfo == null ? Collections.emptyMap() : dr.keyInfo;
        StringBuilder kb = new StringBuilder();
        for (Map.Entry<String, String> e : f.keyInfo.entrySet()) {
            kb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        f.keyInfoText = kb.toString().toLowerCase();
        f.contrast = dr.contrast;
        return f;
    }

    private static String headerOf(ProbeResult p, String name) {
        if (p == null || p.headers == null) {
            return "";
        }
        for (Map.Entry<String, List<String>> e : p.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() || v.get(0) == null ? "" : v.get(0).toLowerCase();
            }
        }
        return "";
    }

    // ================= 对外入口 =================

    /**
     * 生成多假设排序结果。
     *
     * @param dr             检测结果（提供观测事实）
     * @param best           知识库命中的最佳场景（可为 null）
     * @param bestConfidence 知识库命中的置信度（best 为 null 时为 0）
     */
    public InferenceResult infer(DetectionResult dr, Scenario best, double bestConfidence) {
        InferenceResult res = new InferenceResult();
        Facts f = factsOf(dr);

        // 1) 通用规则（不依赖知识库）
        for (Inferrer r : inferrers()) {
            Hypothesis h = r.infer(f);
            if (h != null) {
                if (h.category_label == null) {
                    h.category_label = knowledge.categoryLabels.getOrDefault(h.category, "无法确定");
                }
                res.hypotheses.add(h);
            }
        }

        // 2) 差分隔离实验的推导结论 → 假设（source=contrast）
        if (dr.contrast != null && dr.contrast.conclusions != null) {
            for (ContrastProbe.Conclusion c : dr.contrast.conclusions) {
                Hypothesis h = new Hypothesis();
                h.id = "H_" + c.id;
                h.title = c.title;
                h.category = categoryOfContrast(c);
                h.source = "contrast";
                h.description = c.derived;
                h.confidence = c.confidence;
                h.ev("差分推导依据：" + c.basis);
                h.refute("对照结果在不同时间 / 网络 / 实例下无法复现 → 该推导依赖特定环境，不能作为稳定结论");
                h.verify("按推导依据重放该对照，确认差异可稳定复现：" + c.basis);
                h.category_label = knowledge.categoryLabels.getOrDefault(h.category, "无法确定");
                res.hypotheses.add(h);
            }
        }

        // 3) 知识库命中 → 作为高置信快速路径（或作为一条候选假设）
        if (best != null) {
            Hypothesis h = new Hypothesis();
            h.id = "H_KB";
            h.title = best.name;
            h.category = best.root_cause;
            h.source = "knowledge";
            h.description = best.conclusion;
            h.confidence = bestConfidence;
            h.ev("场景库匹配：" + best.id + " " + best.name + "（置信度 " + pct(bestConfidence) + "）");
            if (best.evidence != null) {
                for (String ev : best.evidence) {
                    h.supported_by.add("知识库证据：" + ev);
                }
            }
            h.refute("状态码 / 响应特征与场景预期不符，或按该场景的解决方案处理后现象无变化 → 该场景匹配不成立");
            if (best.solution != null) {
                h.verify_actions.addAll(best.solution);
            }
            h.category_label = knowledge.categoryLabels.getOrDefault(best.root_cause, "无法确定");
            res.hypotheses.add(h);
        }

        // 4) 按置信度降序排序并截断
        res.hypotheses.sort(Comparator.comparingDouble((Hypothesis h) -> -h.confidence));
        if (res.hypotheses.size() > MAX_HYPOTHESES) {
            res.hypotheses = new ArrayList<>(res.hypotheses.subList(0, MAX_HYPOTHESES));
        }
        res.top = res.hypotheses.isEmpty() ? null : res.hypotheses.get(0);
        return res;
    }

    /**
     * 若差分实验已确认「鉴权层通过」，返回一句可直接并入结论的补充说明；否则返回空串。
     * 该说明是 P1 验收项「必须给出鉴权层已排除这类由差分推导出的结论」的兜底保证。
     */
    public static String authExclusionNote(ContrastProbe.ContrastResult cr) {
        if (cr == null || cr.conclusions == null || cr.experiments == null) {
            return "";
        }
        boolean authPassed = false;
        for (ContrastProbe.Conclusion c : cr.conclusions) {
            if ("DC_01".equals(c.id) && c.derived != null && c.derived.contains("鉴权层已通过")) {
                authPassed = true;
                break;
            }
        }
        if (!authPassed) {
            return "";
        }
        Integer authCode = null;
        Integer baseCode = null;
        for (ContrastProbe.Experiment e : cr.experiments) {
            if ("CT_01".equals(e.id)) {
                authCode = e.statusCode;
            }
        }
        if (cr.baseline != null) {
            int idx = cr.baseline.lastIndexOf("→ ");
            if (idx > 0) {
                String tail = cr.baseline.substring(idx + 2).trim();
                int sp = tail.indexOf('（');
                String num = (sp > 0 ? tail.substring(0, sp) : tail).trim();
                if (num.matches("\\d{3}")) {
                    baseCode = Integer.valueOf(num);
                }
            }
        }
        return "差分实验已排除鉴权层：不带凭证 " + (authCode == null ? "-" : authCode)
                + "、带凭证 " + (baseCode == null ? "-" : baseCode)
                + " → 凭证有效、身份已识别，故障位于鉴权之后的业务层";
    }

    /** 把差分结论归类到场景库的归属 key 上（保证前端配色/教学卡可用） */
    private static String categoryOfContrast(ContrastProbe.Conclusion c) {
        if (c.id == null) {
            return "backend";
        }
        switch (c.id) {
            case "DC_01":   // 鉴权层隔离
                return "auth";
            case "DC_06":   // 协议层差异
                return "config";
            case "DC_07":   // 方法语义差异
                return "frontend";
            default:        // 稳定性 / 请求头 / 缓存 / 异常直证等
                return "backend";
        }
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    // ================= 通用规则库 =================

    private interface Cond {
        boolean test(Facts f);
    }

    private interface Build {
        Hypothesis build(Facts f);
    }

    private interface Inferrer {
        Hypothesis infer(Facts f);
    }

    private static Inferrer rule(Cond cond, Build build) {
        return f -> cond.test(f) ? build.build(f) : null;
    }

    private static Hypothesis hyp(String id, String title, String category,
                                  String description, double confidence, String source) {
        Hypothesis h = new Hypothesis();
        h.id = id;
        h.title = title;
        h.category = category;
        h.description = description;
        h.confidence = confidence;
        h.source = source;
        return h;
    }

    /**
     * 通用技术规则库（共 28 条，覆盖 数据库 / 后端 / 前端 / 网络 / 服务 / 配置 / 性能 / 认证 / 权限）。
     * 规则只描述通用技术事实，新增业务场景无需改这里。
     */
    private List<Inferrer> inferrers() {
        List<Inferrer> rs = new ArrayList<>();

        // ---------- 数据库 / 数据层 ----------
        rs.add(rule(f -> f.hasAny("expected one result", "toomanyresultsexception")
                        || (f.has("but found:") && f.has("selectone")),
                f -> hyp("H_DB_MULTI_ROW", "数据库单行查询返回了多行", "database",
                        "后端以「只允许返回 1 行」的方式查询（selectOne / findOne / getOne），但实际查到多行 → "
                                + "数据重复，或查询条件不足以唯一定位一行。故障点在数据访问层，不在鉴权 / 网络 / 网关层。",
                        0.92, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("直接在数据库执行同一条查询只返回 1 行 → 问题不在这条 SQL，而在上层调用逻辑（循环调用、条件拼接错误）",
                                "该接口本身设计为返回列表 → 是调用方误用了单行查询 API")
                        .verify("直接在数据库执行该查询（去掉 limit）确认实际返回行数",
                                "按业务唯一键分组计数，找出重复数据：SELECT 唯一键, COUNT(*) FROM 表 GROUP BY 唯一键 HAVING COUNT(*) > 1",
                                "确认业务唯一键上是否缺少唯一索引（数据重复能长期存在的常见原因）")));

        rs.add(rule(f -> f.hasAny("duplicate entry", "unique constraint", "duplicate key",
                        "uniquekey", "dataintegrityviolationexception", "integrity constraint violation",
                        "unique index"),
                f -> hyp("H_DB_UNIQUE_CONFLICT", "唯一约束冲突（重复写入）", "database",
                        "写入或更新时命中了数据库唯一索引约束 → 同一业务键被重复提交，或并发下两个请求同时写入。",
                        0.90, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("同一请求重放一次即成功 → 属并发竞态而非数据本身重复",
                                "数据库里该唯一键实际只有一条记录 → 则是索引/字段映射配错")
                        .verify("查询报错中提到的唯一键取值在表中的记录数与创建时间",
                                "确认该业务操作是否有幂等控制（前端重复点击 / 消息重投 / 定时任务重复执行）",
                                "打开后端 SQL 日志，确认是否存在同一事务内的重复 INSERT")));

        rs.add(rule(f -> f.hasAny("sqlsyntaxerrorexception", "badsqlgrammar",
                        "you have an error in your sql syntax", "invalid sql"),
                f -> hyp("H_DB_SQL_SYNTAX", "SQL 语法错误", "database",
                        "后端拼接/映射出的 SQL 语句本身语法不合法 → 多为动态拼接条件、排序字段拼接或 XML/注解 SQL 写错。",
                        0.90, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("同一 SQL 手工执行正常 → 则是参数绑定或引号转义问题")
                        .verify("从后端日志取出完整 SQL（含参数）并手工执行验证",
                                "确认是否使用 ${} 直接拼接了用户可控参数（同时也是 SQL 注入风险点）")));

        rs.add(rule(f -> f.hasAny("unknown column", "unknown table", "no such table",
                        "table or view not found", "relation \"", "table '"),
                f -> hyp("H_DB_SCHEMA_MISSING", "表或字段不存在（库结构与代码不一致）", "database",
                        "SQL 引用的表/字段在目标库里不存在 → 常见于未执行 DDL、执行了错误的库、环境间库结构不同步、"
                                + "或代码升级后忘同步表结构。",
                        0.90, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("该表/字段在目标库里确实存在 → 则是连接到了错误的库（数据源配错/多数据源路由错误）")
                        .verify("在报错指向的库里执行 DESC 表名 / SHOW COLUMNS 确认字段是否存在",
                                "核对当前应用实际连接的库名（数据源配置与多环境 profile）",
                                "对比测试环境与生产环境的表结构差异")));

        rs.add(rule(f -> f.hasAny("deadlock", "lock wait timeout", "lockwaittimeoutexception", "database is locked"),
                f -> hyp("H_DB_DEADLOCK", "数据库锁等待 / 死锁", "database",
                        "事务之间互相等待锁，或单事务持锁时间过长导致锁等待超时 → 高并发下的更新顺序不一致、"
                                + "或大事务未及时提交。",
                        0.88, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("低并发下同样复现 → 则是某个事务长期不提交（如手动事务未 commit）")
                        .verify("查看数据库的死锁日志 / innodb status，确认涉及的表与 SQL",
                                "核对并发请求对同一批记录的更新顺序是否一致（统一加锁顺序可消除大部分死锁）",
                                "检查是否存在大事务（一次事务内更新大量行）")));

        rs.add(rule(f -> f.hasAny("connection is not available", "communications link failure",
                        "cannot get a connection", "too many connections", "hikaripool", "connection pool"),
                f -> hyp("H_DB_POOL", "数据库连接获取失败 / 连接池耗尽", "database",
                        "后端拿不到数据库连接 → 连接池被占满、连接泄漏（未归还），或数据库侧连接数已达上限。",
                        0.86, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("刚重启后立刻复现 → 则不是泄漏而是池上限配置过小或数据库侧限制")
                        .verify("查看连接池监控指标（活跃连接数 / 等待线程数）确认是否打满",
                                "检查代码中是否存在未关闭的连接 / 流式查询未释放",
                                "核对数据库 max_connections 与连接池 maximum-pool-size 的关系")));

        // ---------- 后端 / 应用层 ----------
        rs.add(rule(f -> f.has("nullpointerexception"),
                f -> hyp("H_BACKEND_NPE", "后端空指针异常", "backend",
                        "后端代码对一个为 null 的对象做了取值 / 调用 → 多为上游返回空数据未判空、"
                                + "或依赖的字段没查到值。",
                        0.90, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("日志中报错行是一次已判空的调用 → 说明异常来自被调用的下游而非本行")
                        .verify("从后端日志取异常的完整堆栈，定位到具体类名与行号",
                                "核对报错行所依赖的数据来源（查询结果 / 下游响应 / 配置项）是否为空",
                                "确认是必现还是仅特定入参触发（仅特定入参 → 属数据边界问题）")));

        rs.add(rule(f -> f.hasAny("classnotfoundexception", "noclassdeffounderror", "nosuchmethoderror",
                        "nosuchfielderror", "abstractmethoderror"),
                f -> hyp("H_BACKEND_CLASS", "类 / 方法缺失（依赖版本不一致）", "backend",
                        "运行期找不到某个类或方法 → 常见于打包漏了依赖、多模块依赖版本冲突、"
                                + "或本地/服务器 jar 版本不一致（编译期有、运行期无）。",
                        0.86, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("本地 IDE 运行正常、只有服务器异常 → 指向部署包与本地不一致")
                        .verify("核对报错中的类/方法所在依赖是否存在于部署包（jar 内 BOOT-INF/lib 中查找）",
                                "执行依赖树分析，确认是否存在同一依赖的多个版本",
                                "对比本地与服务器实际加载的 jar 版本")));

        rs.add(rule(f -> f.hasAny("jsonparseexception", "jsonmappingexception", "unexpected character",
                        "jsondecodeerror"),
                f -> hyp("H_BACKEND_JSON_PARSE", "后端解析 JSON 失败", "backend",
                        "后端反序列化某段 JSON 数据时失败 → 数据来源（下游接口 / 缓存 / 数据库大字段 / 配置文件）"
                                + "返回了非法或非预期的 JSON。",
                        0.78, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("该 JSON 手工解析正常 → 则是编码问题（如返回了 GBK 字节流被当 UTF-8 解析）")
                        .verify("从日志取出反序列化的原始字符串，确认其格式与编码",
                                "核对该数据来源是否最近发生过格式变更",
                                "确认是否为「HTML 错误页被当成 JSON 解析」（常见于下游返回 502 页面）")));

        rs.add(rule(f -> f.code(500),
                f -> hyp("H_BACKEND_UNCAUGHT", "后端未捕获异常（500）", "backend",
                        "服务端返回 500 → 后端处理该请求时抛出了异常且未被全局异常处理器兜住。"
                                + "必须结合后端日志堆栈才能定位，不能仅凭状态码判断。",
                        0.62, "rule")
                        .ev("状态码 500；响应关键信息：" + f.keyInfoSummary())
                        .refute("网关侧返回的 500（响应体无后端框架特征）→ 则属网关自身异常而非业务代码")
                        .verify("按请求时间点在后端日志中定位异常堆栈，取第一个业务包名下的 Caused by",
                                "确认该接口最近是否有发版或配置变更（先回滚再排查是最快路径）")));

        rs.add(rule(f -> f.hasAny("outofmemoryerror", "java heap space", "gc overhead limit", "metaspace"),
                f -> hyp("H_SERVICE_OOM", "服务内存溢出", "service",
                        "服务端出现内存溢出 → 一次性加载过多数据、缓存无上限、或对象长期未释放导致堆被占满。",
                        0.88, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("仅在压测/大批量导出时出现 → 属容量问题而非内存泄漏")
                        .verify("取 heap dump 分析占比最大的对象类型与引用链",
                                "检查是否存在一次性查出全表/全量列表的代码路径",
                                "确认 JVM 堆参数与该服务实际数据量是否匹配")));

        // ---------- 前端 / 客户端侧 ----------
        rs.add(rule(f -> f.hasAny("methodargumentnotvalid", "bindexception", "constraintviolation",
                        "validation failed", "参数校验", "参数错误", "字段校验"),
                f -> hyp("H_PARAM_VALIDATION", "请求参数未通过校验", "frontend",
                        "请求参数不满足后端的校验规则（必填缺失、格式非法、超长、类型不匹配）→ "
                                + "前端提交内容与后端接口约定不一致。",
                        0.86, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("同样的参数在 Swagger / Postman 下通过 → 则是前端序列化或字段名不一致（如驼峰/下划线）")
                        .verify("把 F12 里实际发出的请求体与接口文档逐字段比对",
                                "确认字段命名风格是否一致（如 deviceId 与 device_id）",
                                "确认必填字段是否被前端置为空字符串而非不传")));

        rs.add(rule(f -> f.code(400),
                f -> hyp("H_PARAM_BAD_REQUEST", "请求参数不合法（400）", "frontend",
                        "服务端判定请求本身不合法并拒绝 → 参数缺失、格式错误或超出允许范围；"
                                + "需以响应体中的具体提示字段为准。",
                        0.70, "rule")
                        .ev("状态码 400；响应关键信息：" + f.keyInfoSummary())
                        .refute("响应体明确指出是业务规则拒绝（如「不允许操作」）→ 则属业务校验而非格式错误")
                        .verify("查看响应体中的 message / error 字段确切的字段名与原因",
                                "用 Postman 逐字段删除法定位是哪个参数触发")));

        rs.add(rule(f -> f.code(405) || (f.has("request method") && f.has("not supported")),
                f -> hyp("H_METHOD_405", "请求方法不被支持（405）", "frontend",
                        "后端该路径不支持前端使用的 HTTP 方法 → 前端用了 GET 而后端只提供 POST（或相反）。",
                        0.88, "rule")
                        .ev("状态码 " + f.statusDesc() + "；响应关键信息：" + f.keyInfoSummary())
                        .refute("接口文档写的方法与前端一致 → 则是网关做了方法限制或重写")
                        .verify("核对后端该路径的映射注解（@GetMapping / @PostMapping）",
                                "检查前端请求方法配置（axios method / fetch method）",
                                "用 Postman 按后端声明的方法重发，确认是否恢复正常")));

        rs.add(rule(f -> f.code(415) || f.hasAny("unsupportedmediatypeexception",
                        "httpmediatypenotsupported") || (f.has("content-type") && f.has("not supported")),
                f -> hyp("H_MEDIATYPE_415", "请求体类型不被支持（415）", "frontend",
                        "请求的 Content-Type 与后端期望的格式不匹配 → 常见于后端要 application/json "
                                + "而前端发的是表单格式（或反之）。",
                        0.88, "rule")
                        .ev("状态码 " + f.statusDesc() + "；响应关键信息：" + f.keyInfoSummary())
                        .refute("Content-Type 本身正确 → 则是网关剥离/改写了该请求头")
                        .verify("在 F12 中确认请求头的 Content-Type 实际取值",
                                "确认请求体格式与 Content-Type 一致（JSON 字符串 vs 表单编码）",
                                "用 Postman 换成正确的 Content-Type 重发验证")));

        rs.add(rule(f -> f.code(404) || f.hasAny("no handler found", "no mapping for"),
                f -> hyp("H_ROUTE_404", "接口路径不存在或未部署", "frontend",
                        "后端返回 404 → 该 URL 路径在后端没有对应处理器：可能前端路径写错、"
                                + "或该接口在当前部署版本中还不存在。",
                        0.80, "rule")
                        .ev("状态码 404；响应关键信息：" + f.keyInfoSummary())
                        .refute("Nginx/WAF 自身返回 404（响应体是网关 HTML 页、没有后端框架的 JSON 结构）→ 属转发/前缀配置问题",
                                "绕过网关直接访问后端端口能通 → 属网关 rewrite 或路径前缀配置问题")
                        .verify("绕过网关直连后端端口，用同一路径重试，比较是否仍为 404",
                                "在后端代码中搜索该路径的映射注解，确认是否已实现",
                                "核对前端请求路径前缀（如 /api）与后端 servlet context-path 是否一致")));

        rs.add(rule(f -> f.hasAny("httpmessagenotreadableexception", "json parse error",
                        "cannot deserialize value", "required request body is missing"),
                f -> hyp("H_BODY_UNREADABLE", "请求体无法被后端解析", "frontend",
                        "后端读取请求体时失败 → 请求体不是合法 JSON、字段类型与后端定义不符、或根本没带请求体。",
                        0.82, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("请求体在 Postman 下可正常解析 → 则是前端序列化方式问题（如把对象直接拼进 URL）")
                        .verify("在 F12 中查看实际发送的请求体原文",
                                "核对字段类型是否一致（数字被传成字符串、null 被传成 \"null\"）",
                                "确认请求体是否被前端二次 stringify 导致双重转义")));

        rs.add(rule(f -> f.hasAny("cors", "access-control-allow-origin", "blocked by cors policy"),
                f -> hyp("H_CORS", "跨域配置问题", "config",
                        "浏览器因同源策略拦截了响应 → 后端/网关缺少 CORS 响应头，或预检请求（OPTIONS）被拦。",
                        0.86, "rule")
                        .ev("响应关键信息：" + f.keyInfoSummary())
                        .refute("用 Postman（无同源限制）能得到正常数据 → 则确认属跨域配置而非接口故障")
                        .verify("用 Postman 直连同一接口：能正常返回 → 即确认是跨域配置问题",
                                "检查后端 CORS 配置是否放行该来源、是否放行 OPTIONS 方法",
                                "确认网关/反向代理是否把 CORS 相关请求头剥离或重复添加")));

        // ---------- 网络 / 传输层 ----------
        rs.add(rule(f -> f.hasAny("connection refused", "connectexception"),
                f -> hyp("H_NET_REFUSED", "目标端口拒绝连接", "network",
                        "TCP 连接被明确拒绝 → 目标主机在线但该端口没有进程监听（服务未启动、"
                                + "或启动在别的端口/绑定了其他网卡）。",
                        0.86, "rule")
                        .ev("探测结果：" + f.statusDesc())
                        .refute("本机能连通、只有跨网段不通 → 则是防火墙/安全组策略丢弃导致误报为拒绝")
                        .verify("在目标服务器上确认进程与监听端口（netstat -ano | findstr 端口）",
                                "核对服务配置文件中的端口与实际监听端口是否一致",
                                "确认服务绑定地址是否为 0.0.0.0（绑定 127.0.0.1 时外部无法访问）")));

        rs.add(rule(f -> f.hasAny("unknownhost", "no such host", "name or service not known"),
                f -> hyp("H_NET_DNS", "域名解析失败", "network",
                        "域名无法解析为 IP → DNS 配置错误、hosts 未配置，或域名本身不存在。",
                        0.86, "rule")
                        .ev("探测结果：" + f.statusDesc())
                        .refute("同域名在其他机器上可解析 → 则是本机 DNS / hosts 配置问题")
                        .verify("用 nslookup / ping 该域名确认解析结果",
                                "核对是否需要在 hosts 中配置内网映射",
                                "确认 DNS 服务器地址配置是否正确（内网环境常见）")));

        rs.add(rule(f -> f.hasAny("connect timed out", "httptimeoutexception", "connecttimeoutexception",
                        "read timed out", "socketexception") || f.has("timed out"),
                f -> hyp("H_NET_TIMEOUT", "连接或读取超时", "network",
                        "在超时时间内没有完成连接或读响应 → 网络链路不通（丢包/策略静默丢弃）、"
                                + "或对端处理过慢导致读超时。",
                        0.80, "rule")
                        .ev("探测结果：" + f.statusDesc())
                        .refute("TCP 握手成功但读响应超时 → 则是服务端处理慢，不是网络问题")
                        .verify("用 telnet / Test-NetConnection 确认端口能否建立连接",
                                "连接能建立但响应慢 → 检查后端该请求的处理耗时（慢 SQL / 下游慢）",
                                "确认客户端超时设置是否过小（长耗时接口需单独放大超时）")));

        rs.add(rule(f -> f.hasAny("ssl", "pkix", "certificate", "handshake"),
                f -> hyp("H_SSL_CERT", "TLS/SSL 握手或证书问题", "config",
                        "HTTPS 握手失败或证书校验不通过 → 证书过期、域名与证书不匹配、"
                                + "自签证书未被信任，或该端口根本不提供 HTTPS。",
                        0.86, "rule")
                        .ev("探测结果：" + f.statusDesc())
                        .refute("浏览器可正常访问、只有程序里失败 → 则是客户端的信任库未导入该 CA")
                        .verify("用 openssl s_client 或浏览器查看证书有效期与颁发对象",
                                "确认访问域名与证书 CN/SAN 是否匹配",
                                "确认该端口是否真的提供 HTTPS（HTTP 端口用 https 访问会出现明文连接错误）")));

        // ---------- 网关 / 服务可用性 ----------
        rs.add(rule(f -> f.code(502) || f.hasAny("bad gateway", "upstream"),
                f -> hyp("H_GATEWAY_502", "网关无法连接后端（502）", "service",
                        "网关/Nginx 无法从上游后端获得有效响应 → 后端进程已挂、端口变更、"
                                + "或上游地址配置指向了错误的目标。",
                        0.86, "rule")
                        .ev("状态码 502；响应关键信息：" + f.keyInfoSummary())
                        .refute("后端进程正常且直连可通 → 则是网关 upstream 配置或健康检查摘除导致")
                        .verify("查看网关错误日志中该请求的上游地址与失败原因",
                                "直连后端服务端口验证服务是否存活",
                                "核对网关 upstream 配置中的 IP/端口是否与后端实际监听一致")));

        rs.add(rule(f -> f.code(503) || f.hasAny("service unavailable", "circuit breaker", "hystrix"),
                f -> hyp("H_SERVICE_UNAVAILABLE", "服务不可用（503）", "service",
                        "服务暂时无法处理请求 → 实例被摘除、熔断器打开、线程池打满或正在重启。",
                        0.82, "rule")
                        .ev("状态码 503；响应关键信息：" + f.keyInfoSummary())
                        .refute("所有实例同时 503 → 则更可能是上游依赖（数据库/中间件）不可用触发熔断")
                        .verify("确认该服务实例是否全部在线（注册中心 / 负载均衡列表）",
                                "检查是否发生熔断（熔断器状态与触发时间点）",
                                "核对服务最近是否发生重启或发布")));

        rs.add(rule(f -> f.code(504) || f.hasAny("gateway timeout"),
                f -> hyp("H_TIMEOUT_504", "网关等待后端超时（504）", "performance",
                        "网关在自身超时时间内没等到后端响应 → 后端处理超过网关超时阈值，"
                                + "而不是后端没有响应。",
                        0.78, "rule")
                        .ev("状态码 504；响应关键信息：" + f.keyInfoSummary())
                        .refute("直连后端（不经网关）同样超时 → 则是后端本身处理慢，与网关超时配置无关")
                        .verify("查看后端日志中该请求的实际处理耗时",
                                "检查慢 SQL / 下游调用耗时（最常见原因）",
                                "核对网关 proxy_read_timeout 与该接口正常耗时的关系")));

        rs.add(rule(f -> f.code(429) || f.hasAny("too many requests", "rate limit", "限流"),
                f -> hyp("H_RATE_LIMIT", "请求被限流（429）", "service",
                        "请求频率或并发超过网关/服务的限流阈值而被拒绝 → 属保护机制触发，不是故障。",
                        0.88, "rule")
                        .ev("状态码 429；响应关键信息：" + f.keyInfoSummary())
                        .refute("低频单次请求也被限流 → 则是限流阈值配置过小或计数维度配错")
                        .verify("确认限流规则（阈值 / 维度：IP、用户、接口）",
                                "查看响应头中的限流信息（Retry-After / X-RateLimit-*）",
                                "确认是否有前端轮询或重复提交造成短时高频")));

        // ---------- 通用表现层 ----------
        rs.add(rule(f -> f.responseTimeMs != null && f.responseTimeMs >= SLOW_MS,
                f -> hyp("H_PERF_SLOW", "响应耗时偏高", "performance",
                        "接口在 " + SLOW_MS + "ms 以上才返回 → 慢 SQL、下游接口慢、"
                                + "或一次请求里串行调用了过多依赖。",
                        0.70, "rule")
                        .ev("本次响应耗时：" + f.responseTimeMs + "ms")
                        .refute("仅首次慢、后续明显变快 → 属缓存冷启动或连接池预热，不是持续性能问题")
                        .verify("查看后端日志中该请求各阶段耗时（SQL 耗时 / 下游调用耗时）",
                                "开启慢 SQL 日志确认是否存在全表扫描",
                                "确认返回数据量是否过大（分页缺失）")));

        rs.add(rule(f -> {
                    String b = f.body.trim();
                    return !b.isEmpty() && b.charAt(0) == '<'
                            && !f.contentType.contains("json") && !f.contentType.contains("xml");
                },
                f -> hyp("H_NON_JSON_RESPONSE", "返回的是 HTML 页面而非接口数据", "config",
                        "响应体是 HTML（而非 JSON）→ 请求很可能没有真正到达后端接口，"
                                + "而是被网关错误页、登录跳转页、WAF 拦截页或静态资源兜底处理了。",
                        0.72, "rule")
                        .ev("响应体开头：" + head(f.body, 120) + "；Content-Type：" + (f.contentType.isEmpty() ? "（无）" : f.contentType))
                        .refute("响应体虽是 HTML 但确实是后端模板引擎正常输出的业务页面 → 则该接口本就不是 JSON 接口")
                        .verify("查看 HTML 的 title / 特征字符串，判断是哪一层返回的（nginx 错误页、登录页、WAF 拦截页）",
                                "用 Postman 直连后端端口，确认是否返回 JSON",
                                "确认该请求是否被重定向到了登录页（重定向后跟随会拿到登录页 HTML）")));

        return rs;
    }

    private static String head(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
