package com.faultdetect;

import com.faultdetect.DetectEngine.DetectionResult;
import com.faultdetect.DetectEngine.ParsedUrl;
import com.faultdetect.DetectEngine.ProbeResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【P1】差分隔离层
 *
 * 思路：不再靠「状态码 → 查表」，而是对同一 URL 自动跑一组「只改变一个变量」的对照实验，
 * 用对照组之间的差异反推故障所在的层次（网络 / 网关 / 鉴权 / 业务 / 数据）。
 *
 * 硬性安全约束：
 *   1) 只允许对幂等只读方法（GET / HEAD）自动重放；POST / PUT / PATCH / DELETE 一律拒绝（见 assertSafeMethod）。
 *   2) 所有对照串行执行，避免对目标系统造成瞬时压力。
 *   3) 基线直接复用第 1 步已获得的响应，不重复请求。
 *   4) 可通过 /api/detect 的 enable_contrast=false 整体关闭。
 */
@Component
public class ContrastProbe {

    /** 允许自动重放的幂等方法白名单（变更此集合即变更安全边界，勿随意扩大） */
    private static final Set<String> SAFE_METHODS = new LinkedHashSet<>(List.of("GET", "HEAD"));
    /** 稳定性重放总次数（含基线共 3 次，用于判断偶发 / 必现） */
    private static final int REPLAY_TIMES = 3;

    private final DetectEngine engine;

    public ContrastProbe(DetectEngine engine) {
        this.engine = engine;
    }

    // ================= 结果容器 =================

    /** 单个对照实验 */
    public static class Experiment {
        public String id;              // CT_01
        public String title;           // 鉴权隔离
        public String change;          // 相对基线改变了什么
        public String method = "GET";  // 实际发出的 HTTP 方法（仅 GET / HEAD）
        public String url;             // 实际请求的 URL
        public String result;          // ok（已取得响应）/ fail（未取得响应）/ skip（该对照无意义）
        public String observed;        // 观测结果：状态码 / 错误原因
        public Integer statusCode;
        public Integer responseTimeMs;
    }

    /** 由对照差异推导出的结论 */
    public static class Conclusion {
        public String id;           // DC_01
        public String title;        // 鉴权层隔离
        public String derived;      // 推导出的结论（自然语言）
        public double confidence;   // 该推导的置信度（0-1）
        public String basis;        // 推导所依据的对照关系
    }

    /** 差分隔离实验总结果 */
    public static class ContrastResult {
        public boolean enabled = true;
        public String baseline;                          // 基线观测
        public List<Experiment> experiments = new ArrayList<>();
        public List<Conclusion> conclusions = new ArrayList<>();
        public List<String> notes = new ArrayList<>();   // 跳过原因 / 安全说明
    }

    // ================= 主流程 =================

    /**
     * 执行差分实验矩阵。
     *
     * @param dr        已完成第 1 步探测的检测结果（dr.probe 作为基线）
     * @param headers   用户传入的自定义请求头（作为「原样」基线配置）
     * @param timeoutSec 单次请求超时（秒）
     */
    public ContrastResult run(DetectionResult dr, Map<String, String> headers, int timeoutSec) {
        ContrastResult cr = new ContrastResult();
        if (dr == null || dr.parsed == null || dr.probe == null) {
            cr.enabled = false;
            cr.notes.add("缺少基线探测结果，跳过差分实验");
            return cr;
        }

        ParsedUrl base = dr.parsed;
        ProbeResult baseline = dr.probe;
        cr.baseline = "GET " + base.url + " → " + describe(baseline);

        // 基线本身就没拿到响应（超时 / 连接失败）时，各对照必然同样失败，
        // 再打 6 次只会拖慢检测并给目标添无谓流量，故直接跳过（该场景已由第 5 步「前后端隔离验证」覆盖）。
        if (baseline.statusCode == null) {
            cr.notes.add("基线请求未取得 HTTP 响应（" + describe(baseline) + "），跳过差分实验：各对照必然同样不可达");
            dr.contrast = cr;
            return cr;
        }

        Map<String, String> h = headers == null ? Collections.emptyMap() : headers;
        boolean hasAuth = hasHeader(h, "authorization");
        boolean hasCustomHeaders = !h.isEmpty();
        List<Experiment> exps = new ArrayList<>();

        // CT_01 鉴权隔离：只去掉 Authorization，其余请求头与 URL 完全不变
        if (hasAuth) {
            exps.add(runOne("CT_01", "鉴权隔离", "去掉 Authorization 头（其余不变）",
                    base.url, "GET", withoutHeader(h, "authorization"), timeoutSec));
        } else {
            exps.add(skip("CT_01", "鉴权隔离", "去掉 Authorization 头（其余不变）",
                    "未提供 Authorization 头，该对照无参照意义"));
        }

        // CT_02 自定义头隔离：去掉全部自定义头（等价 Postman 裸请求）
        if (hasCustomHeaders) {
            exps.add(runOne("CT_02", "自定义头隔离", "去掉全部自定义请求头（裸请求）",
                    base.url, "GET", Collections.emptyMap(), timeoutSec));
        } else {
            exps.add(skip("CT_02", "自定义头隔离", "去掉全部自定义请求头（裸请求）",
                    "未提供自定义请求头，该对照无参照意义"));
        }

        // CT_03 方法语义隔离：GET ↔ HEAD（两者均为幂等只读方法）
        exps.add(runOne("CT_03", "方法语义隔离", "换用 HEAD 方法（保留全部请求头）",
                base.url, "HEAD", h, timeoutSec));

        // CT_04 协议层隔离：http ↔ https
        String swappedUrl = swapSchemeUrl(base);
        if (swappedUrl != null) {
            exps.add(runOne("CT_04", "协议层隔离", "改走另一协议（保留全部请求头）",
                    swappedUrl, "GET", h, timeoutSec));
        } else {
            exps.add(skip("CT_04", "协议层隔离", "改走另一协议",
                    "URL 协议不是 http/https，该对照无意义"));
        }

        // CT_05 缓存 / 参数敏感隔离：追加随机无关参数
        exps.add(runOne("CT_05", "缓存与参数敏感隔离", "追加随机无关参数（保留全部请求头）",
                appendRandomParam(base.url), "GET", h, timeoutSec));

        // CT_06 稳定性重放：原样再打 2 次（连同基线共 3 次），判断偶发 / 必现
        for (int i = 2; i <= REPLAY_TIMES; i++) {
            exps.add(runOne("CT_06", "稳定性重放", "原样重放第 " + i + " 次（判断偶发 / 必现）",
                    base.url, "GET", h, timeoutSec));
        }

        cr.experiments = exps;
        cr.conclusions = derive(dr, exps);
        if (!hasAuth && !hasCustomHeaders) {
            cr.notes.add("未提供任何自定义请求头，CT_01 / CT_02 已跳过");
        }
        cr.notes.add("差分实验全部使用幂等只读方法（GET / HEAD），未向目标发送任何写请求");

        dr.contrast = cr;
        // 追加检测步骤，使「检测流程」区块自动展示差分结果
        dr.steps.add(DetectEngine.step(6, "差分隔离实验（自动完成）",
                "对同一 URL 跑对照矩阵：每次只改变一个变量，用差异反推故障层次",
                "info", summarize(cr)));
        return cr;
    }

    // ================= 对照执行 =================

    private Experiment runOne(String id, String title, String change, String url, String method,
                              Map<String, String> headers, int timeoutSec) {
        assertSafeMethod(method);
        Experiment e = new Experiment();
        e.id = id;
        e.title = title;
        e.change = change;
        e.url = url;
        e.method = method;
        ProbeResult p = engine.httpProbe(url, timeoutSec, headers, method);
        e.statusCode = p.statusCode;
        e.responseTimeMs = p.responseTimeMs;
        e.observed = describe(p);
        e.result = p.statusCode != null ? "ok" : "fail";
        return e;
    }

    private static Experiment skip(String id, String title, String change, String reason) {
        Experiment e = new Experiment();
        e.id = id;
        e.title = title;
        e.change = change;
        e.result = "skip";
        e.observed = "已跳过：" + reason;
        return e;
    }

    /**
     * 硬性安全约束：只允许自动重放幂等方法。
     * 若将来有人把 POST / PUT / PATCH / DELETE 接进差分矩阵，这里会直接抛错阻断，避免误触发写操作。
     */
    private static void assertSafeMethod(String method) {
        if (method == null || !SAFE_METHODS.contains(method.toUpperCase())) {
            throw new IllegalStateException("差分实验拒绝自动重放非幂等 / 非只读方法：" + method);
        }
    }

    // ================= 差分推导 =================

    /**
     * 由「基线 + 各对照」的差异推导结论。
     * 每条规则只使用对照之间的相对差异，不依赖任何预置状态码知识库。
     */
    private List<Conclusion> derive(DetectionResult dr, List<Experiment> exps) {
        List<Conclusion> out = new ArrayList<>();
        Integer bc = dr.probe.statusCode;
        if (bc == null) {
            return out;
        }
        Experiment auth = find(exps, "CT_01");
        Experiment bare = find(exps, "CT_02");
        Experiment head = find(exps, "CT_03");
        Experiment proto = find(exps, "CT_04");
        Experiment rand = find(exps, "CT_05");
        List<Experiment> replays = findAll(exps, "CT_06");

        // DC_01 鉴权层隔离（本方案最关键的一条：直接回答「问题在不在鉴权层」）
        if (auth != null && auth.statusCode != null) {
            if (isAuthReject(auth.statusCode) && !isAuthReject(bc)) {
                out.add(conclusion("DC_01", "鉴权层隔离",
                        "鉴权层已通过：不带凭证时被判为鉴权失败（" + auth.statusCode + "），携带凭证后未被判为鉴权失败（"
                                + bc + "）→ 凭证有效、身份已识别，故障不在鉴权层，位于鉴权之后的业务处理层",
                        0.90, "CT_01 去掉 Authorization → " + auth.statusCode + "；基线 → " + bc));
            } else if (isAuthReject(bc)) {
                out.add(conclusion("DC_01", "鉴权层隔离",
                        "故障位于鉴权层：基线本身即被判为鉴权失败（" + bc + "）→ 凭证无效 / 缺失 / 过期，"
                                + "应先解决鉴权，再进行业务层排查",
                        0.90, "基线 → " + bc + "（鉴权失败）"));
            } else if (auth.statusCode.equals(bc)) {
                out.add(conclusion("DC_01", "鉴权层隔离",
                        "去掉凭证与携带凭证返回完全相同的状态码（" + bc + "）→ 该接口不依赖此凭证，或该凭证被服务端忽略，"
                                + "所有「凭证相关」的假设可排除",
                        0.80, "CT_01 → " + auth.statusCode + "；基线 → " + bc));
            }
        }

        // DC_02 后端异常直证：响应关键信息里出现具体异常类名 → 故障点可直接锚定到后端代码 / 数据
        String exc = dr.keyInfo == null ? null : dr.keyInfo.get("异常类型");
        if (exc != null && !exc.isEmpty()) {
            out.add(conclusion("DC_02", "后端异常直证",
                    "后端已抛出具体异常（" + exc + "）→ 故障点在后端业务代码或其访问的数据层，前端与网关可排除",
                    0.85, "响应关键信息中的异常类型：" + exc));
        }

        // DC_03 稳定性：3 次原样请求结果是否一致
        if (!replays.isEmpty()) {
            List<Integer> codes = new ArrayList<>();
            codes.add(bc);
            boolean allSame = true;
            for (Experiment r : replays) {
                if (r.statusCode == null || !r.statusCode.equals(bc)) {
                    allSame = false;
                }
                codes.add(r.statusCode);
            }
            if (allSame) {
                out.add(conclusion("DC_03", "稳定性判定",
                        "问题必现：连续 " + (replays.size() + 1) + " 次完全相同的请求返回一致结果（" + bc
                                + "）→ 可稳定复现，可排除偶发性因素（多实例版本不一致、缓存击穿、并发时序、负载波动）",
                        0.85, "CT_06 重放结果：" + codes));
            } else {
                out.add(conclusion("DC_03", "稳定性判定",
                        "问题偶发：同一请求多次返回不一致（" + codes + "）→ 优先怀疑负载均衡后多实例版本不一致、"
                                + "缓存命中差异、并发或时序问题，而非固定的业务逻辑错误",
                        0.75, "CT_06 重放结果：" + codes));
            }
        }

        // DC_04 请求头影响面
        if (bare != null && bare.statusCode != null) {
            if (!bare.statusCode.equals(bc)) {
                out.add(conclusion("DC_04", "请求头影响因素",
                        "结果对请求头敏感：裸请求 " + bare.statusCode + " 与基线 " + bc + " 不一致 → 差异由自定义请求头造成，"
                                + "需逐个请求头做减法定位（优先排查 Content-Type / Accept / 租户或版本类头）",
                        0.75, "CT_02 裸请求 → " + bare.statusCode + "；基线 → " + bc));
            } else {
                out.add(conclusion("DC_04", "请求头影响因素",
                        "排除请求头因素：裸请求与基线返回一致（" + bc + "）→ 自定义请求头不是本次故障的原因",
                        0.70, "CT_02 裸请求 → " + bare.statusCode + "；基线 → " + bc));
            }
        }

        // DC_05 缓存与 URL 参数敏感性
        if (rand != null && rand.statusCode != null) {
            if (!rand.statusCode.equals(bc)) {
                out.add(conclusion("DC_05", "缓存与参数敏感性",
                        "结果对 URL 参数敏感：追加随机无关参数后变为 " + rand.statusCode + "（基线 " + bc
                                + "）→ 优先怀疑 CDN / 网关 / 浏览器缓存命中，或后端按完整 URL 做了差异化处理",
                        0.70, "CT_05 追加随机参数 → " + rand.statusCode + "；基线 → " + bc));
            } else {
                out.add(conclusion("DC_05", "缓存与参数敏感性",
                        "排除缓存与参数相关假设：追加随机无关参数后结果不变（" + bc + "）→ 该响应不是由 URL 级缓存造成的",
                        0.65, "CT_05 追加随机参数 → " + rand.statusCode + "；基线 → " + bc));
            }
        }

        // DC_06 协议层差异
        if (proto != null && proto.statusCode != null && !proto.statusCode.equals(bc)) {
            out.add(conclusion("DC_06", "协议层差异",
                    "两种协议结果不一致：" + proto.observed + "（基线 " + bc
                            + "）→ 差异来自协议处理链路，需检查 SSL 终止、网关端口映射与协议强制跳转配置",
                    0.70, "CT_04 换协议 → " + proto.statusCode + "；基线 → " + bc));
        }

        // DC_07 方法语义差异
        if (head != null && head.statusCode != null && !head.statusCode.equals(bc)) {
            out.add(conclusion("DC_07", "方法语义差异",
                    "HEAD 与 GET 结果不一致：" + protoObserved(head) + "（基线 " + bc
                            + "）→ 网关或后端按 HTTP 方法做了差异化处理（路由 / 过滤 / 拦截规则）",
                    0.65, "CT_03 HEAD → " + head.statusCode + "；基线 → " + bc));
        }

        return out;
    }

    private static String protoObserved(Experiment e) {
        return e.statusCode == null ? "无响应" : String.valueOf(e.statusCode);
    }

    // ================= 工具 =================

    private static Conclusion conclusion(String id, String title, String derived, double confidence, String basis) {
        Conclusion c = new Conclusion();
        c.id = id;
        c.title = title;
        c.derived = derived;
        c.confidence = confidence;
        c.basis = basis;
        return c;
    }

    private static Experiment find(List<Experiment> exps, String id) {
        List<Experiment> all = findAll(exps, id);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<Experiment> findAll(List<Experiment> exps, String id) {
        List<Experiment> out = new ArrayList<>();
        for (Experiment e : exps) {
            if (id.equals(e.id)) {
                out.add(e);
            }
        }
        return out;
    }

    /** 401 / 403 视为「被鉴权层拒绝」 */
    private static boolean isAuthReject(Integer code) {
        return code != null && (code == 401 || code == 403);
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String k : headers.keySet()) {
            if (name.equalsIgnoreCase(k)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> withoutHeader(Map<String, String> headers, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!name.equalsIgnoreCase(e.getKey())) {
                m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    private static String describe(ProbeResult p) {
        if (p == null) {
            return "无结果";
        }
        if (p.statusCode != null) {
            return p.statusCode + (p.responseTimeMs == null ? "" : "（" + p.responseTimeMs + "ms）");
        }
        return p.error != null && !p.error.isEmpty() ? "请求失败：" + p.error : "无响应";
    }

    /** 追加随机无关参数：用于判别结果是否受 URL 级缓存影响 */
    private static String appendRandomParam(String url) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "_fd_r=" + System.nanoTime();
    }

    /**
     * 生成「另一协议」的 URL：http ↔ https。
     * 原端口若是该协议的默认端口（http:80 / https:443），则切换后交由新协议使用其默认端口；
     * 否则保留显式端口（如 http://host:8000 → https://host:8000）。
     */
    private static String swapSchemeUrl(ParsedUrl base) {
        String other;
        if ("http".equals(base.scheme)) {
            other = "https";
        } else if ("https".equals(base.scheme)) {
            other = "http";
        } else {
            return null;
        }
        boolean defaultPort = ("http".equals(base.scheme) && base.port == 80)
                || ("https".equals(base.scheme) && base.port == 443);
        return defaultPort
                ? other + "://" + base.host + base.path
                : other + "://" + base.host + ":" + base.port + base.path;
    }

    /** 生成写入「检测流程」步骤的摘要文本 */
    private static String summarize(ContrastResult cr) {
        StringBuilder sb = new StringBuilder();
        sb.append("基线：").append(cr.baseline);
        for (Experiment e : cr.experiments) {
            sb.append("\n- ").append(e.title).append("：").append(e.change).append(" → ").append(e.observed);
        }
        for (Conclusion c : cr.conclusions) {
            sb.append("\n【推导 ").append(c.id).append("·").append(c.title).append("】").append(c.derived);
        }
        for (String n : cr.notes) {
            sb.append("\n注：").append(n);
        }
        return sb.toString();
    }
}
