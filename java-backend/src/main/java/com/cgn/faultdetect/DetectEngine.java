package com.cgn.faultdetect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检测引擎（对应 Python 版 engine/detector.py）
 * 流程：URL 解析 → TCP 存活检测 → HTTP 探测 → 状态码分析 → 响应特征提取 → 证据收集 → 日志/数据库分支
 */
@Component
public class DetectEngine {

    public static final int LARGE_BODY_LIMIT = 4000;
    private static final Pattern DIGITS3 = Pattern.compile("(\\d{3})");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile SSLContext trustAllSsl;

    // ================= 结果容器 =================

    public static class ParsedUrl {
        public final String url;
        public final String scheme;
        public final String host;
        public final int port;
        public final String path;

        public ParsedUrl(String url, String scheme, String host, int port, String path) {
            this.url = url;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
        }
    }

    public static class ProbeResult {
        public Integer statusCode;
        public String statusText = "pending";
        public Integer responseTimeMs;
        public Map<String, List<String>> headers = new LinkedHashMap<>();
        public String body = "";
        public int bodyLength = 0;
        public String error;
    }

    public static class TcpResult {
        public boolean ok;
        public String detail;
        public long elapsedMs;
    }

    public static class DetectionResult {
        public List<Map<String, Object>> steps = new ArrayList<>();
        public ProbeResult probe;
        public TcpResult service;
        public List<String> features = new ArrayList<>();
        public String normalizedStatus;
        public ParsedUrl parsed;
        public String error; // 非 null 表示检测失败
    }

    // ================= URL 解析 =================

    public ParsedUrl parseUrl(String urlRaw) {
        String url = urlRaw == null ? "" : urlRaw.trim();
        if (url.isEmpty()) {
            throw new ApiException(400, "URL 不能为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            if (host == null || host.isEmpty()) {
                throw new ApiException(400, "URL 格式不正确：缺少主机名（示例：http://192.168.1.100:8081）");
            }
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            if (uri.getQuery() != null) {
                path += "?" + uri.getQuery();
            }
            return new ParsedUrl(url, scheme, host, port, path);
        } catch (URISyntaxException e) {
            throw new ApiException(400, "URL 格式不正确：" + e.getMessage());
        }
    }

    // ================= 第 0 步：TCP 服务存活检测 =================

    public TcpResult tcpCheck(String host, int port, int timeoutSec) {
        TcpResult r = new TcpResult();
        long start = System.currentTimeMillis();
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new InetSocketAddress(host, port), timeoutSec * 1000);
            long elapsed = System.currentTimeMillis() - start;
            r.ok = true;
            r.detail = "TcpTestSucceeded: True（耗时 " + elapsed + "ms）";
        } catch (UnknownHostException e) {
            r.ok = false;
            r.detail = "DNS 解析失败：无法解析主机 " + host;
        } catch (Exception e) {
            // 连接被拒绝/超时等：对齐 Python connect_ex 返回非 0 的语义
            long elapsed = System.currentTimeMillis() - start;
            r.ok = false;
            r.detail = "TcpTestSucceeded: False（耗时 " + elapsed + "ms）";
        }
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }

    // ================= 第 1 步：HTTP 请求探测 =================

    private static SSLContext trustAllSslContext() {
        if (trustAllSsl == null) {
            synchronized (DetectEngine.class) {
                if (trustAllSsl == null) {
                    try {
                        TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] c, String a) {
                            }

                            public void checkServerTrusted(X509Certificate[] c, String a) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }};
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, tm, new SecureRandom());
                        trustAllSsl = ctx;
                    } catch (Exception e) {
                        throw new IllegalStateException("初始化 SSLContext 失败", e);
                    }
                }
            }
        }
        return trustAllSsl;
    }

    public ProbeResult httpProbe(String url, int timeoutSec) {
        return httpProbe(url, timeoutSec, null);
    }

    public ProbeResult httpProbe(String url, int timeoutSec, Map<String, String> headers) {
        ProbeResult r = new ProbeResult();
        long start = System.currentTimeMillis();
        try {
            // 禁用代理（内网直连），对应 Python 版 trust_env=False
            // 连接阶段超时独立控制（min(timeout, 5s)），避免对丢包目标等待满整个超时
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSec, 5)))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(ProxySelector.of(null))
                    .sslContext(trustAllSslContext())
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .GET();
            // 自定义请求头（支持带 Token/Cookie 检测需要认证的接口）
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
            HttpRequest req = builder.build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            r.statusCode = resp.statusCode();
            r.statusText = String.valueOf(resp.statusCode());
            r.responseTimeMs = (int) (System.currentTimeMillis() - start);
            resp.headers().map().forEach((k, v) -> r.headers.put(k, v));
            String body = resp.body();
            r.bodyLength = body.length();
            r.body = body.length() > LARGE_BODY_LIMIT ? body.substring(0, LARGE_BODY_LIMIT) : body;
        } catch (HttpTimeoutException e) {
            r.error = "请求超时（超过 " + timeoutSec + "s 无响应）";
            r.statusText = "pending → 504（超时）";
        } catch (ConnectException e) {
            r.error = "连接失败（后端服务不可达）：" + e.getMessage();
            r.statusText = "502 模拟（连接被拒绝）";
        } catch (SSLException e) {
            r.error = "SSL 证书错误：" + e.getMessage();
            r.statusText = "SSL 异常";
        } catch (IllegalArgumentException e) {
            r.error = "URL 无效：" + e.getMessage();
            r.statusText = "URL 无效";
        } catch (Exception e) {
            r.error = "请求异常：" + e.getMessage();
            r.statusText = "请求异常";
        } finally {
            if (r.responseTimeMs == null) {
                r.responseTimeMs = (int) (System.currentTimeMillis() - start);
            }
        }
        return r;
    }

    // ================= 响应特征提取 =================

    public List<String> extractFeatures(ProbeResult p) {
        List<String> features = new ArrayList<>();
        String body = p.body == null ? "" : p.body;
        String statusText = p.statusText == null ? "" : p.statusText;

        if (p.statusCode == null || statusText.contains("504") || statusText.contains("pending")) {
            features.add("no response");
        }
        if (p.statusCode != null && p.statusCode == 200) {
            features.add("data");
            try {
                JsonNode data = MAPPER.readTree(body);
                if (data != null && data.isObject()) {
                    if (data.has("data")) {
                        JsonNode v = data.get("data");
                        boolean empty = v == null || v.isNull()
                                || ((v.isArray() || v.isObject()) && v.isEmpty());
                        if (empty) {
                            features.add("empty");
                        } else {
                            features.add("有数据");
                        }
                    }
                    if (data.has("code")) {
                        features.add(data.get("code").asText());
                    }
                    if (data.has("message")) {
                        String msg = data.get("message").asText();
                        features.add(msg.length() > 50 ? msg.substring(0, 50) : msg);
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 响应体，跳过
            }
        }

        String lower = body.toLowerCase();
        String[] errorKeywords = {
                "sqlexception", "sql syntax", "cannot be null", "connection refused",
                "nullpointer", "outofmemory", "redis", "timeout", "exception",
                "unauthorized", "token", "forbidden", "权限", "未登录", "接口不存在",
                "文件过大", "payload too large", "validation", "duplicate", "cors",
        };
        for (String kw : errorKeywords) {
            if (lower.contains(kw)) {
                features.add(kw);
            }
        }

        if (p.error != null && !p.error.isEmpty()) {
            features.add(p.error.contains("超时") ? "timeout" : "connect");
        }
        return features;
    }

    // ================= 状态码规整 =================

    public String normalizeStatus(String statusText) {
        if (statusText == null) {
            return "pending";
        }
        Matcher m = DIGITS3.matcher(statusText);
        if (m.find()) {
            return m.group(1);
        }
        if (statusText.contains("pending") || statusText.contains("超时") || statusText.contains("504")) {
            return "504";
        }
        if (statusText.contains("连接") || statusText.contains("拒绝")) {
            return "502";
        }
        return "pending";
    }

    // ================= 完整检测流程 =================

    private Map<String, Object> step(int step, String title, String action, String result, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("title", title);
        m.put("action", action);
        m.put("result", result);
        m.put("detail", detail);
        return m;
    }

    public DetectionResult runDetection(String url, boolean enableServiceCheck, boolean enableDbCheck, int timeout) {
        return runDetection(url, enableServiceCheck, enableDbCheck, timeout, null);
    }

    public DetectionResult runDetection(String url, boolean enableServiceCheck, boolean enableDbCheck, int timeout,
                                        Map<String, String> headers) {
        DetectionResult dr = new DetectionResult();
        try {
            dr.parsed = parseUrl(url);
        } catch (ApiException e) {
            dr.error = e.getMessage();
            return dr;
        }
        ParsedUrl parsed = dr.parsed;

        dr.steps.add(step(0, "URL 解析", "解析输入 URL", "ok",
                parsed.scheme + "://" + parsed.host + ":" + parsed.port + parsed.path));

        // 第 0 步（服务存活检测）与第 1 步（HTTP 探测）并行执行：
        // 两项检测互不依赖，串行时最坏等待 = TCP 超时(5s) + HTTP 超时(timeout)
        // 并行后总耗时 = max(TCP, HTTP)，显著加快目标不可达/超时场景的检测
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TcpResult service;
        ProbeResult probe;
        try {
            CompletableFuture<TcpResult> tcpFuture = enableServiceCheck
                    ? CompletableFuture.supplyAsync(() -> tcpCheck(parsed.host, parsed.port, 5), pool)
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<ProbeResult> httpFuture =
                    CompletableFuture.supplyAsync(() -> httpProbe(parsed.url, timeout, headers), pool);
            service = tcpFuture.join();
            probe = httpFuture.join();
        } finally {
            pool.shutdownNow();
        }

        // 第 0 步步骤记录（保持 0 → 1 顺序展示）
        if (enableServiceCheck) {
            dr.service = service;
            dr.steps.add(step(0, "服务存活检测（TCP）",
                    "Test-NetConnection " + parsed.host + " -Port " + parsed.port,
                    service.ok ? "pass" : "fail", service.detail));
            if (!service.ok) {
                dr.steps.add(step(0, "服务存活检测（TCP）", "判断结论", "fail",
                        "TCP 连接失败 → 请求未到达后端 → 服务/网络/防火墙问题，先行排查服务进程与端口"));
            }
        } else {
            dr.steps.add(step(0, "服务存活检测（TCP）",
                    "Test-NetConnection " + parsed.host + " -Port " + parsed.port,
                    "skip", "已由用户关闭该检查项"));
        }

        // 第 1 步：HTTP 探测
        dr.probe = probe;
        dr.steps.add(step(1, "HTTP 请求探测", "GET " + parsed.url,
                probe.statusCode != null ? "ok" : "fail",
                "状态码：" + probe.statusText + " | 耗时：" + ms(probe.responseTimeMs)
                        + " | " + (probe.error != null ? probe.error : "响应已捕获")));

        // 第 2 步：状态码分析
        String normalized = normalizeStatus(probe.statusText);
        dr.normalizedStatus = normalized;
        dr.steps.add(step(2, "状态码分析", "对照 SOP 状态码速查表", "info",
                CODE_HINT.getOrDefault(normalized, "状态码 " + normalized + " 分析")));

        // 第 3 步：响应数据分析
        List<String> features = extractFeatures(probe);
        dr.features = features;
        if (probe.body != null && !probe.body.isEmpty()) {
            int len = Math.min(probe.body.length(), 300);
            dr.steps.add(step(3, "响应数据分析", "解析响应体特征", "info",
                    "响应体（前 " + len + " 字符）：" + probe.body.substring(0, len)));
        }

        // 第 4 步：证据收集
        dr.steps.add(step(4, "证据收集", "固定 3 样：接口记录 / 请求面板 / 响应面板", "ok",
                "已自动捕获：URL=" + parsed.url + ", Status=" + probe.statusText + ", 耗时="
                        + ms(probe.responseTimeMs) + ", 响应体长度=" + probe.bodyLength));

        // 第 5 步：日志/数据库分析提示（依据状态码分支）
        if ("500".equals(normalized)) {
            dr.steps.add(step(5, "后端日志分析（500 必做）",
                    "tail -f logs/app.log | grep -E 'Exception|Error'", "info",
                    "500 错误需查看后端日志堆栈：定位第一个 Caused by，记录类名与行号，截图给研发"));
        } else if ("200".equals(normalized) && features.contains("empty")) {
            dr.steps.add(step(5, "数据库排查（200 + 空数组必做）",
                    "mysql -u root -p → SELECT * FROM 表名 LIMIT 10", "info",
                    "响应 data 为空数组 → 需验证数据库：表是否存在、是否有数据"));
        } else {
            // 前后端隔离验证：系统已用裸请求（等价 Postman）自动完成，无需人工操作
            dr.steps.add(step(5, "前后端隔离验证（自动完成）",
                    "等价 Postman：绕过浏览器环境直连后端", "info",
                    autoIsolation(probe, normalized, features)));
        }
        return dr;
    }

    /**
     * 前后端隔离验证（系统自动完成，无需手动 Postman）：
     * 第 1 步的裸请求已获取后端真实响应，据此直接判定问题归属。
     */
    private static String autoIsolation(ProbeResult probe, String normalized, List<String> features) {
        if (probe.error != null && probe.error.contains("超时")) {
            return "后端无响应（超时）→ 服务未启动 / 网络不通 / 防火墙拦截，直接排查服务与网络，无需手动验证";
        }
        if (probe.statusCode != null) {
            int code = probe.statusCode;
            if (code >= 500) {
                return "后端可达但返回 " + code + " → 后端服务异常，直接查后端日志，无需手动验证";
            }
            if (code >= 400) {
                return "后端可达但返回 " + code + " → 前端请求参数 / 权限问题，检查 F12 Payload 与 Token，无需手动验证";
            }
            if (features.contains("empty")) {
                return "后端可达且返回 " + code + "，但数据为空 → 优先排查数据库（表是否存在 / 是否有数据）";
            }
            return "后端可达且响应正常（" + code + "）→ 若页面仍有异常，问题在前端渲染（系统已自动完成隔离验证）";
        }
        return "后端不可达 → 网络 / 服务 / 防火墙问题（系统已自动完成隔离验证，无需手动验证）";
    }

    private static String ms(Integer v) {
        return v == null ? "-" : v + "ms";
    }

    private static final Map<String, String> CODE_HINT = new HashMap<>();

    static {
        CODE_HINT.put("200", "后端正常返回 → 需分析响应数据（空数组=数据库问题）");
        CODE_HINT.put("201", "资源创建成功 → 检查前端是否处理成功响应");
        CODE_HINT.put("204", "成功但无内容 → 检查前端是否处理成功响应");
        CODE_HINT.put("400", "参数错误 → 检查前端提交参数（F12 → Payload）");
        CODE_HINT.put("401", "未授权 → 检查登录状态 / Token");
        CODE_HINT.put("403", "权限不足 → 检查 RBAC 权限配置");
        CODE_HINT.put("404", "接口不存在 → Postman 验证后端是否已有该接口");
        CODE_HINT.put("405", "方法不允许 → 检查请求方法（GET/POST 是否用错）");
        CODE_HINT.put("408", "请求超时 → 检查网络与后端超时配置");
        CODE_HINT.put("409", "资源冲突 → 检查唯一约束/重复数据");
        CODE_HINT.put("410", "资源已删除 → 确认资源是否下线，更新前端");
        CODE_HINT.put("413", "请求体过大 → 检查文件大小与上传配置");
        CODE_HINT.put("415", "媒体类型不支持 → 检查 Content-Type");
        CODE_HINT.put("422", "参数校验失败 → 检查字段级校验错误");
        CODE_HINT.put("429", "请求频繁 → 检查限流配置");
        CODE_HINT.put("500", "服务器内部错误 → 必须查后端日志（第 5 步）");
        CODE_HINT.put("501", "功能未实现 → 确认后端是否实现该方法");
        CODE_HINT.put("502", "Bad Gateway → 后端服务崩溃/Nginx 配置问题");
        CODE_HINT.put("503", "服务不可用 → 服务维护/过载");
        CODE_HINT.put("504", "网关超时 → 慢 SQL / 网关超时配置");
        CODE_HINT.put("505", "HTTP 版本不支持 → 升级浏览器");
        CODE_HINT.put("pending", "无响应 → 服务/网络/防火墙问题，先做第 0 步");
    }
}
