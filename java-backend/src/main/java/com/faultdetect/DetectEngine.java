package com.faultdetect;

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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
    /** 【P0-2】异常类名 / 错误类名匹配（如 TooManyResultsException、NullPointerException、SQLException） */
    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error|Throwable))\\b");
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
        /** 【P0-2】响应关键信息（只呈现、不参与打分）：业务码 / 业务信息 / 异常类型 / 堆栈线索 */
        public Map<String, String> keyInfo = new LinkedHashMap<>();
        /** 【P1】差分隔离实验结果（对照矩阵 + 差分推导结论），由 ApiController 在检测后填充 */
        public ContrastProbe.ContrastResult contrast;
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
        // 【P1】保持原三参签名与行为完全不变，内部委托给支持指定方法的新实现
        return httpProbe(url, timeoutSec, headers, "GET");
    }

    /**
     * 【P1】支持指定 HTTP 方法：差分隔离层需要 GET ↔ HEAD 对照实验。
     * 安全约束：调用方只允许传入幂等只读方法（GET / HEAD）；
     * 非幂等方法（POST / PUT / PATCH / DELETE）由 ContrastProbe 在入口处拦截，禁止自动重放。
     */
    public ProbeResult httpProbe(String url, int timeoutSec, Map<String, String> headers, String method) {
        ProbeResult r = new ProbeResult();
        long start = System.currentTimeMillis();
        try {
            // 禁用代理（内网直连），对应 Python 版 trust_env=False
            // 连接阶段超时独立控制（min(timeout, 5s)），避免对丢包目标等待满整个超时
            HttpClient client = HttpClient.newBuilder()
                    // 【P0 修复】显式指定 HTTP/1.1。JDK 默认版本为 HTTP_2，对明文 http:// 目标会先发送
                    // h2c 协议升级握手（Upgrade: h2c + HTTP2-Settings）；Express / 部分网关收到该头后
                    // 既不回 101 也不忽略，而是把连接吊住，导致客户端必然等满超时报「无响应」。
                    // 浏览器与 curl 对明文 HTTP 均使用 HTTP/1.1，此处对齐其行为以避免误判。
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSec, 5)))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(ProxySelector.of(null))
                    .sslContext(trustAllSslContext())
                    .build();
            // 【P1】原实现写死 .GET()，现改为按入参指定方法（无请求体，仅用于 GET / HEAD 对照）
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            // 自定义请求头（支持带 Token/Cookie 检测需要认证的接口）
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
            HttpRequest req = builder.build();
            // 【P0 修复】改为按字节接收，再依据 Content-Type 声明的 charset 自行解码。
            // ofString() 无参版本固定按 UTF-8 解码且忽略响应头里的 charset，
            // 遇到 GBK / gb2312 的内网系统会把中文响应解成乱码，
            // 导致异常类名、业务码、错误关键词全部匹配失败，进而给出错误归因。
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            r.statusCode = resp.statusCode();
            r.statusText = String.valueOf(resp.statusCode());
            r.responseTimeMs = (int) (System.currentTimeMillis() - start);
            resp.headers().map().forEach((k, v) -> r.headers.put(k, v));
            String body = decodeBody(resp.body(), resp.headers());
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

    // ================= 响应体解码 =================

    /** 从 Content-Type 中提取 charset 参数（如 text/html; charset=GBK） */
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 【P0 修复】按响应头声明的 charset 解码响应体，保证「观测层」拿到的文本与目标真实返回一致。
     *
     * 优先级：Content-Type 中的 charset → 无声明时先按 UTF-8 解码 → 若出现替换字符（U+FFFD）则回退 GBK。
     * 之所以需要回退探测：部分内网系统响应头不写 charset 却返回 GBK 字节，此时 UTF-8 解码必然乱码。
     */
    static String decodeBody(byte[] bytes, HttpHeaders headers) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset cs = null;
        if (headers != null) {
            String ct = headers.firstValue("content-type").orElse("");
            if (!ct.isEmpty()) {
                Matcher m = CHARSET_PATTERN.matcher(ct);
                if (m.find()) {
                    try {
                        cs = Charset.forName(m.group(1).trim());
                    } catch (Exception ignored) {
                        // 声明了无法识别的 charset 名，交由下面的回退探测处理
                    }
                }
            }
        }
        if (cs != null) {
            return new String(bytes, cs);
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            String gbk = new String(bytes, Charset.forName("GBK"));
            if (gbk.indexOf('\uFFFD') < 0) {
                return gbk;
            }
        }
        return utf8;
    }

    // ================= 响应特征提取 =================

    public List<String> extractFeatures(ProbeResult p) {
        List<String> features = new ArrayList<>();
        String body = p.body == null ? "" : p.body;
        String statusText = p.statusText == null ? "" : p.statusText;

        if (p.statusCode == null || statusText.contains("504") || statusText.contains("pending")) {
            features.add("no response");
        }
        // 【P0-1】原实现仅在 statusCode==200 时解析响应体，导致非 2xx 响应体里的
        // code / message 被整体丢弃（实测 417 响应体里的关键业务错误因此丢失）。
        // 现改为：任何状态码都尝试解析 code / message；
        // 但 data / empty / 有数据 属于「成功信封」语义，仍仅在 200 时产出，
        // 避免给错误响应打上错误特征而干扰既有场景匹配（200 的行为与原来完全一致）。
        if (p.statusCode != null && !body.isEmpty()) {
            try {
                JsonNode data = MAPPER.readTree(body);
                if (data != null && data.isObject()) {
                    if (p.statusCode == 200) {
                        features.add("data");
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

    // ================= 响应关键信息提取（P0-2） =================

    /**
     * 【P0-2】响应关键信息提取：只呈现，不参与打分、不做归因。
     * 目的：只要拿到了响应体，就把服务端真正想说的话（业务码 / 错误信息 / 异常类名 / 堆栈线索）
     * 原样拎出来，避免非 2xx 响应体被丢弃后，排障时看不到关键线索。
     */
    public Map<String, String> extractKeyInfo(ProbeResult p) {
        Map<String, String> info = new LinkedHashMap<>();
        if (p == null || p.body == null) {
            return info;
        }
        String body = p.body.trim();
        if (body.isEmpty()) {
            return info;
        }

        // 1) 按 JSON 解析，提取业务错误字段（字段名做常见别名兼容）
        boolean isJson = false;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject()) {
                isJson = true;
                for (String f : new String[]{"code", "status", "errcode", "errCode"}) {
                    if (root.has(f) && !root.get(f).isNull()) {
                        info.put("业务码（" + f + "）", clip(root.get(f).asText(), 200));
                        break;
                    }
                }
                for (String f : new String[]{"message", "msg", "error", "errorMessage", "detail", "reason"}) {
                    if (root.has(f) && !root.get(f).isNull()) {
                        info.put("业务信息（" + f + "）", clip(root.get(f).asText(), 500));
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 响应体，走下面的文本抽取
        }

        // 2) 异常类名（响应体任意位置命中即可，包括堆栈）
        Matcher em = EXCEPTION_PATTERN.matcher(body);
        if (em.find()) {
            info.put("异常类型", em.group(1));
        }

        // 3) 堆栈线索：取第一行含异常类名 / Caused by / at 的内容
        for (String line : body.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("at ") || t.contains("Caused by") || EXCEPTION_PATTERN.matcher(t).find()) {
                info.put("异常/堆栈线索", clip(t, 300));
                break;
            }
        }

        // 4) 非 JSON 响应体：抽取可读纯文本片段（去掉不可打印控制字符）
        if (!isJson) {
            info.put("响应文本片段", clip(body.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ""), 500));
        }
        return info;
    }

    /** 截断超长文本，避免把整段堆栈塞进展示区 */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…（已截断）" : s;
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

    // 【P1】由 private 放宽为包级：供 ContrastProbe 追加「差分隔离实验」检测步骤
    // 【P2】改为 static：供 MatcherEngine 追加「通用假设推理」检测步骤（不依赖实例状态）
    static Map<String, Object> step(int step, String title, String action, String result, String detail) {
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
                codeHint(normalized)));

        // 第 3 步：响应数据分析
        List<String> features = extractFeatures(probe);
        dr.features = features;
        if (probe.body != null && !probe.body.isEmpty()) {
            int len = Math.min(probe.body.length(), 300);
            dr.steps.add(step(3, "响应数据分析", "解析响应体特征", "info",
                    "响应体（前 " + len + " 字符）：" + probe.body.substring(0, len)));
        }

        // 【P0-2】第 3 步（补充）：响应关键信息提取 —— 原样呈现，不做归因
        dr.keyInfo = extractKeyInfo(probe);
        if (!dr.keyInfo.isEmpty()) {
            StringBuilder ki = new StringBuilder();
            for (Map.Entry<String, String> en : dr.keyInfo.entrySet()) {
                if (ki.length() > 0) {
                    ki.append(" | ");
                }
                ki.append(en.getKey()).append("：").append(en.getValue());
            }
            dr.steps.add(step(3, "响应关键信息提取",
                    "提取响应体中的业务错误信息（原样呈现，不做归因）", "info", ki.toString()));
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
                // 【P0-3】原实现把一切 4xx 一律归因「前端请求参数 / 权限问题」，
                // 实测 417 实际是后端数据重复，该归因会把排查方向带偏。
                // 改为：仅对标准语义明确的状态码给归属，其余不再归因，只陈述事实并指向响应体业务信息。
                String hint = CODE_HINT.get(String.valueOf(code));
                if (hint != null) {
                    return "后端可达但返回 " + code + "（标准语义）→ " + hint;
                }
                return "后端可达但返回 " + code + " → 请求已到达后端业务层（非网络 / 服务 / 网关问题）。"
                        + "该状态码语义不常用，禁止仅凭状态码归因，请以「响应关键信息」与证据链中的响应体业务错误为准";
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

    /**
     * 【P0-4】状态码解释：优先精确匹配 CODE_HINT；未收录时按状态码段位给通用解释。
     * 原实现未收录时降级为「状态码 X 分析」，几乎无信息量，现改为段位通用解释。
     */
    static String codeHint(String normalized) {
        String hint = CODE_HINT.get(normalized);
        if (hint != null) {
            return hint;
        }
        if (normalized != null && normalized.matches("\\d{3}")) {
            switch (Integer.parseInt(normalized) / 100) {
                case 1:
                    return "1xx 信息响应 → 请求已被接收，仍在处理中，确认是否需要继续等待";
                case 2:
                    return "2xx 成功 → 后端已正常返回，重点分析响应体数据是否符合预期";
                case 3:
                    return "3xx 重定向 → 检查跳转目标与缓存配置（CDN / Nginx 301 / 302 / 304）";
                case 4:
                    return "4xx 客户端侧错误 → 语义不常用，需结合响应体业务信息判断，勿仅凭状态码归因（见第 3 步响应关键信息）";
                case 5:
                    return "5xx 服务端错误 → 必须查看后端日志堆栈（第 5 步），定位第一个 Caused by";
                default:
                    break;
            }
        }
        return "无响应 / 未知状态 → 先确认第 0 步服务存活与网络连通性";
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
        // 【P0-4】补齐 scenarios.json 中出现但 CODE_HINT 缺失的状态码（此前会降级成无信息量文案）
        CODE_HINT.put("202", "请求已接受但未处理完 → 确认是否为异步接口，前端需轮询结果");
        CODE_HINT.put("300", "多种选择 → 检查后端是否返回多个可选资源，前端需明确处理");
        CODE_HINT.put("301", "永久重定向 → 检查域名/路径是否正确，前端应更新为新地址");
        CODE_HINT.put("302", "临时重定向 → 常见于未登录跳登录页，检查鉴权跳转配置");
        CODE_HINT.put("303", "见其他位置 → 检查重定向目标与前端跳转逻辑");
        CODE_HINT.put("304", "缓存未修改 → 优先怀疑缓存（CDN / Nginx / 浏览器强缓存），非接口故障");
        CODE_HINT.put("307", "临时重定向（保持方法）→ 检查网关/负载均衡转发配置");
        CODE_HINT.put("308", "永久重定向（保持方法）→ 检查网关/负载均衡转发配置");
        CODE_HINT.put("402", "需付费/账户受限 → 检查账户状态与授权额度");
        CODE_HINT.put("406", "不可接受的响应类型 → 检查 Accept 请求头与后端返回格式是否匹配");
    }
}
