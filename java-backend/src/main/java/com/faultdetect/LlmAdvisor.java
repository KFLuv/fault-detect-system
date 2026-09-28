package com.faultdetect;

import com.faultdetect.DetectEngine.DetectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【P3】可选 LLM 增强层（LlmAdvisor）
 *
 * 设计原则（与「零外部依赖」约束共存，而不是取代它）：
 *   1) <b>默认关闭</b>：{@code faultdetect.llm.enabled=false} 时本类不发起任何外部请求，
 *      系统的差分隔离 + 通用规则推理链路完全不受影响；
 *   2) <b>按需触发</b>：只有调用方显式要求（{@code /api/detect} 的 {@code enable_llm=true}，
 *      前端为默认不勾选的复选框）才会真正联网；
 *   3) <b>内网自动降级</b>：连接失败 / 超时 / DNS 失败 / 证书错误 / 非 2xx / 返回内容不是合法 JSON
 *      —— 一律记为 {@link #STATUS_DEGRADED} 并把原因原样写进结果，<b>不抛异常、不阻塞、不影响既有结论</b>；
 *   4) <b>只作补充，不抢结论</b>：LLM 结论只在「通用规则 + 差分推导 + 知识库」三条路径都没能给出
 *      任何假设时（真·库外故障）才升格为主结论（{@code reasoning_source=llm}）；
 *      其余情况只作为独立区块并列展示，由人工判断是否采纳。
 *
 * 协议：OpenAI 兼容的 {@code /chat/completions}（messages 结构），便于对接公司内网自建模型网关。
 */
@Component
public class LlmAdvisor {

    /** 未启用 / 未请求 */
    public static final String STATUS_DISABLED = "disabled";
    /** 调用成功且返回内容可解析 */
    public static final String STATUS_OK = "ok";
    /** 调用失败并已自动降级为纯规则结论 */
    public static final String STATUS_DEGRADED = "degraded";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 塞进提示词的响应体截断长度（避免超长 prompt） */
    private static final int PROMPT_BODY_LIMIT = 800;
    /** 降级原因 / 原始响应的截断长度 */
    private static final int REASON_LIMIT = 200;

    private final Knowledge knowledge;

    @Value("${faultdetect.llm.enabled:false}")
    private boolean enabled;
    @Value("${faultdetect.llm.endpoint:}")
    private String endpoint;
    @Value("${faultdetect.llm.api-key:}")
    private String apiKey;
    @Value("${faultdetect.llm.model:}")
    private String model;
    @Value("${faultdetect.llm.timeout-seconds:20}")
    private int timeoutSeconds;
    /** 内网自建模型常见自签证书：置 true 时跳过证书校验（默认 false，保持安全默认值） */
    @Value("${faultdetect.llm.insecure-tls:false}")
    private boolean insecureTls;

    private volatile SSLContext trustAllSsl;

    public LlmAdvisor(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    // ================= 结果容器 =================

    /** LLM 增强分析结果 */
    public static class Advice {
        /** 本次检测是否请求了 LLM（由 MatcherEngine 依据 enable_llm 设置） */
        public boolean requested;
        /** 后端配置是否启用 */
        public boolean enabled;
        /** 是否真的发起了外部请求 */
        public boolean attempted;
        /** disabled / ok / degraded */
        public String status = STATUS_DISABLED;
        /** 状态说明（降级原因由此呈现） */
        public String reason = "";
        public String model = "";
        public long latency_ms;
        public String root_cause;
        public String root_cause_label;
        public String conclusion_text;
        public String reasoning;
        public Double confidence;
        public List<String> verify_actions = new ArrayList<>();
        public List<String> refute_if = new ArrayList<>();
        /** 原始响应（截断），降级时便于排查对接问题 */
        public String raw = "";

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requested", requested);
            m.put("enabled", enabled);
            m.put("attempted", attempted);
            m.put("status", status);
            m.put("reason", reason);
            m.put("model", model);
            m.put("latency_ms", latency_ms);
            m.put("root_cause", root_cause);
            m.put("root_cause_label", root_cause_label);
            m.put("conclusion_text", conclusion_text);
            m.put("reasoning", reasoning);
            m.put("confidence", confidence);
            m.put("verify_actions", verify_actions);
            m.put("refute_if", refute_if);
            m.put("raw", raw);
            return m;
        }
    }

    /** 本次未请求 LLM（前端未勾选）：不产生卡片，也不做任何外部调用 */
    public Advice notRequested() {
        Advice a = new Advice();
        a.requested = false;
        a.enabled = enabled;
        a.status = STATUS_DISABLED;
        a.reason = "本次检测未启用 LLM 增强（前端复选框未勾选）";
        return a;
    }

    public boolean isReady() {
        return enabled && endpoint != null && !endpoint.trim().isEmpty();
    }

    // ================= 主入口 =================

    /**
     * 请求 LLM 对本次检测做增强分析。任何异常都在内部消化并降级，本方法不会抛异常。
     */
    public Advice advise(DetectionResult dr, HypothesisEngine.InferenceResult inference,
                         boolean fastPath, String bestScenarioName, double bestConfidence) {
        Advice a = new Advice();
        a.requested = true;
        a.enabled = enabled;
        if (!enabled) {
            a.status = STATUS_DISABLED;
            a.reason = "后端未启用（faultdetect.llm.enabled=false），已按纯规则链路输出结论";
            return a;
        }
        if (endpoint == null || endpoint.trim().isEmpty()) {
            a.status = STATUS_DISABLED;
            a.reason = "已启用但未配置 faultdetect.llm.endpoint，已按纯规则链路输出结论";
            return a;
        }

        a.attempted = true;
        a.model = model == null ? "" : model;
        long start = System.currentTimeMillis();
        try {
            String payload = buildPayload(dr, inference, fastPath, bestScenarioName, bestConfidence);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.trim()))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = buildClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            a.latency_ms = System.currentTimeMillis() - start;

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                a.status = STATUS_DEGRADED;
                a.reason = "LLM 服务返回 HTTP " + resp.statusCode() + "，已自动降级为纯规则结论";
                a.raw = clip(resp.body(), REASON_LIMIT);
                return a;
            }
            parseInto(a, resp.body());
        } catch (Exception e) {
            // 内网不可达 / 连接超时 / DNS 失败 / 证书错误 / 响应读取失败 等一律降级，绝不影响检测结果
            a.latency_ms = System.currentTimeMillis() - start;
            a.status = STATUS_DEGRADED;
            a.reason = "调用 LLM 失败（" + e.getClass().getSimpleName() + "）：" + clip(String.valueOf(e.getMessage()), REASON_LIMIT)
                    + "　→ 已自动降级为纯规则结论";
        }
        return a;
    }

    private HttpClient buildClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                // 【P0 修复】同 DetectEngine：显式 HTTP/1.1，避免对明文 http:// 目标发起 h2c 升级握手而被吊住
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.min(3, Math.max(1, timeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (insecureTls) {
            b.sslContext(trustAllSslContext());
        }
        return b.build();
    }

    /** 内网自签证书场景：仅在 insecure-tls=true 时启用，默认不参与 */
    private SSLContext trustAllSslContext() {
        if (trustAllSsl == null) {
            synchronized (LlmAdvisor.class) {
                if (trustAllSsl == null) {
                    try {
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, new TrustManager[]{new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(X509Certificate[] c, String a) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }}, new java.security.SecureRandom());
                        trustAllSsl = ctx;
                    } catch (Exception ignored) {
                        // 构造失败则退回默认 SSLContext（调用方会在握手阶段失败并降级）
                    }
                }
            }
        }
        return trustAllSsl;
    }

    // ================= 提示词 =================

    /**
     * 组装 OpenAI 兼容的 chat/completions 请求体。
     * 只把「观测事实 + 差分结论 + 通用假设候选」喂给模型，不喂任何预置答案，避免模型照抄。
     */
    private String buildPayload(DetectionResult dr, HypothesisEngine.InferenceResult inference,
                                boolean fastPath, String bestScenarioName, double bestConfidence) {
        ObjectNode root = MAPPER.createObjectNode();
        if (model != null && !model.trim().isEmpty()) {
            root.put("model", model.trim());
        }
        root.put("temperature", 0.2);
        ArrayNode messages = root.putArray("messages");

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt());

        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt(dr, inference, fastPath, bestScenarioName, bestConfidence));

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("组装 LLM 请求体失败", e);
        }
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深后端 / 运维故障诊断专家，擅长根据 HTTP 响应与对照实验证据定位故障层次。\n");
        sb.append("任务：根据用户给出的【观测事实】，给出最可能的根因判断，并给出可执行的验证动作与反证条件。\n");
        sb.append("约束：\n");
        sb.append("1. 只依据给定事实推理，不要编造未观测到的信息；不确定时降低 confidence。\n");
        sb.append("2. root_cause 必须从以下枚举中选一个：").append(categoryEnum()).append("。\n");
        sb.append("3. 严格只输出一个 JSON 对象，不要输出 markdown 代码块、不要输出任何解释性前后缀。\n");
        sb.append("4. JSON 字段固定为：root_cause(字符串)、conclusion(一句话根因结论)、reasoning(2-4 句推理链)、");
        sb.append("confidence(0~1 之间的小数)、verify_actions(字符串数组，2-4 条可执行验证动作)、");
        sb.append("refute_if(字符串数组，2-4 条能推翻该结论的反证条件)。\n");
        return sb.toString();
    }

    private String categoryEnum() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : knowledge.categoryLabels.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(e.getKey()).append("(").append(e.getValue()).append(")");
        }
        return sb.toString();
    }

    private String userPrompt(DetectionResult dr, HypothesisEngine.InferenceResult inference,
                              boolean fastPath, String bestScenarioName, double bestConfidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("【观测事实】\n");
        sb.append("- 请求方法：GET\n");
        sb.append("- URL：").append(dr.parsed == null ? "" : dr.parsed.url).append("\n");
        if (dr.probe != null) {
            sb.append("- 状态行：").append(dr.probe.statusText).append("\n");
            sb.append("- 响应耗时：").append(dr.probe.responseTimeMs == null ? "-" : dr.probe.responseTimeMs + "ms").append("\n");
            String ct = firstHeader(dr.probe.headers, "Content-Type");
            if (!ct.isEmpty()) {
                sb.append("- Content-Type：").append(ct).append("\n");
            }
            sb.append("- 响应体（截断）：").append(clip(dr.probe.body, PROMPT_BODY_LIMIT)).append("\n");
        }
        if (dr.service != null) {
            sb.append("- 端口连通性：").append(dr.service.detail).append("\n");
        }
        if (dr.keyInfo != null && !dr.keyInfo.isEmpty()) {
            sb.append("\n【响应关键信息】\n");
            for (Map.Entry<String, String> e : dr.keyInfo.entrySet()) {
                sb.append("- ").append(e.getKey()).append("：").append(clip(e.getValue(), 300)).append("\n");
            }
        }
        if (dr.contrast != null && !dr.contrast.conclusions.isEmpty()) {
            sb.append("\n【差分隔离实验结论】（对同一 URL 每次只改一个变量后重放得到的对照结果）\n");
            sb.append("- 基线：").append(dr.contrast.baseline).append("\n");
            for (ContrastProbe.Conclusion c : dr.contrast.conclusions) {
                sb.append("- ").append(c.title).append("：").append(c.derived)
                        .append("（依据：").append(c.basis).append("）\n");
            }
        }
        sb.append("\n【已由通用规则 / 差分推导得到的候选假设】\n");
        if (inference == null || inference.hypotheses.isEmpty()) {
            sb.append("- 无（通用规则库与差分推导均未命中任何已知技术模式，这正是需要你补充判断的情况）\n");
        } else {
            for (HypothesisEngine.Hypothesis h : inference.hypotheses) {
                sb.append("- [").append(h.category_label).append("｜").append(Math.round(h.confidence * 100))
                        .append("%] ").append(h.title).append("：").append(clip(h.description, 200)).append("\n");
            }
        }
        sb.append("\n【知识库快速路径】\n");
        if (fastPath) {
            sb.append("- 命中场景「").append(bestScenarioName).append("」，置信度 ").append(Math.round(bestConfidence * 100)).append("%\n");
        } else {
            sb.append("- 未命中高置信场景\n");
        }
        sb.append("\n请按系统提示要求，只输出一个 JSON 对象。");
        return sb.toString();
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return "";
    }

    // ================= 响应解析 =================

    /** 解析 OpenAI 兼容响应：取 choices[0].message.content（兼容 choices[0].text），再解析其中的 JSON */
    private void parseInto(Advice a, String responseBody) {
        String content;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            content = choice.path("message").path("content").asText("");
            if (content.isEmpty()) {
                content = choice.path("text").asText("");
            }
            if (content.isEmpty()) {
                a.status = STATUS_DEGRADED;
                a.reason = "LLM 响应中未找到 choices[0].message.content，已自动降级为纯规则结论";
                a.raw = clip(responseBody, REASON_LIMIT);
                return;
            }
            if (root.hasNonNull("model")) {
                a.model = root.get("model").asText(a.model);
            }
        } catch (Exception e) {
            a.status = STATUS_DEGRADED;
            a.reason = "LLM 响应不是合法 JSON，已自动降级为纯规则结论";
            a.raw = clip(responseBody, REASON_LIMIT);
            return;
        }

        JsonNode obj = readJsonLoosely(content);
        if (obj == null) {
            a.status = STATUS_DEGRADED;
            a.reason = "LLM 输出无法解析为 JSON 对象，已自动降级为纯规则结论";
            a.raw = clip(content, REASON_LIMIT);
            return;
        }

        String rc = obj.path("root_cause").asText("").trim();
        a.root_cause = normalizeCategory(rc);
        a.root_cause_label = knowledge.categoryLabels.getOrDefault(a.root_cause, "无法确定");
        a.conclusion_text = obj.path("conclusion").asText("").trim();
        a.reasoning = obj.path("reasoning").asText("").trim();
        a.confidence = clampConfidence(obj.path("confidence"));
        a.verify_actions = readStringArray(obj.path("verify_actions"));
        a.refute_if = readStringArray(obj.path("refute_if"));

        if (a.conclusion_text.isEmpty()) {
            a.status = STATUS_DEGRADED;
            a.reason = "LLM 返回的 JSON 缺少 conclusion 字段，已自动降级为纯规则结论";
            a.raw = clip(content, REASON_LIMIT);
            return;
        }
        a.status = STATUS_OK;
        a.reason = "";
        a.raw = clip(content, PROMPT_BODY_LIMIT);
    }

    /** 容忍模型把 JSON 包在 ```json 代码块或多余文字里：退化为「截取第一个 { 到最后一个 }」 */
    private static JsonNode readJsonLoosely(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(t);
            if (n.isObject()) {
                return n;
            }
        } catch (Exception ignored) {
            // 落下去做宽松截取
        }
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(t.substring(s, e + 1));
            return n.isObject() ? n : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 归属只接受知识库已定义的 key；模型给出中文标签时反查为 key，否则归为 unknown */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        String v = raw.trim();
        if (knowledge.categoryLabels.containsKey(v)) {
            return v;
        }
        for (Map.Entry<String, String> e : knowledge.categoryLabels.entrySet()) {
            if (e.getValue().equals(v)) {
                return e.getKey();
            }
        }
        return "unknown";
    }

    private static Double clampConfidence(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        double v = n.asDouble(Double.NaN);
        if (Double.isNaN(v)) {
            return null;
        }
        if (v > 1) {
            // 兼容模型直接给百分数（如 85）
            v = v > 100 ? 1.0 : v / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, Math.round(v * 100.0) / 100.0));
    }

    private static List<String> readStringArray(JsonNode n) {
        List<String> list = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return list;
        }
        for (JsonNode item : n) {
            String s = item.asText("").trim();
            if (!s.isEmpty()) {
                list.add(clip(s, 300));
            }
        }
        return list;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 供健康检查 / 前端展示用：当前 LLM 层的配置状态（不含密钥） */
    public Map<String, Object> configStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("configured", isReady());
        m.put("endpoint", endpoint == null ? "" : endpoint.trim());
        m.put("model", model == null ? "" : model);
        m.put("timeout_seconds", timeoutSeconds);
        m.put("insecure_tls", insecureTls);
        m.put("has_api_key", apiKey != null && !apiKey.trim().isEmpty());
        return m;
    }

    /** 未命中任何规则时的兜底验证动作（LLM 结论升格为主结论时若数组为空则用它，避免出现空建议） */
    public static List<String> defaultVerifyActions() {
        return Collections.singletonList("按 F12 Network 完整保存该请求的请求头 / 响应体 / 时序，交给对应模块负责人复核");
    }
}
