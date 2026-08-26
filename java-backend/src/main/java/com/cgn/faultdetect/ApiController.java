package com.cgn.faultdetect;

import com.cgn.faultdetect.DetectEngine.DetectionResult;
import com.cgn.faultdetect.Knowledge.Scenario;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * API 控制器（接口与 Python 版完全一致）
 *   GET  /api/health          健康检查
 *   GET  /api/status-codes    32 个状态码
 *   GET  /api/scenarios       92 个场景（支持 ?code= 过滤）
 *   POST /api/detect          执行故障检测
 *   POST /api/add-scenario    新增场景
 *   GET  /api/history         检测历史
 *   GET  /api/stats           统计信息
 * 前端页面由 src/main/resources/static 静态资源提供（/ 与 /static/*）
 */
@RestController
@CrossOrigin(origins = "*")
public class ApiController {

    private final Knowledge knowledge;
    private final DetectEngine detectEngine;
    private final MatcherEngine matcherEngine;
    private final HistoryRepository history;

    public ApiController(Knowledge knowledge, DetectEngine detectEngine,
                         MatcherEngine matcherEngine, HistoryRepository history) {
        this.knowledge = knowledge;
        this.detectEngine = detectEngine;
        this.matcherEngine = matcherEngine;
        this.history = history;
    }

    // ================= 请求模型 =================

    public static class DetectRequest {
        public String url;
        public String symptom = "";
        public boolean enable_service_check = true;
        public int timeout = 10;
        public Map<String, String> headers;   // 可选：自定义请求头（Token/Cookie 等）
    }

    public static class AddScenarioRequest {
        public String name;
        public List<String> http_codes;
        public List<String> response_patterns = new ArrayList<>();
        public List<String> ui_symptoms = new ArrayList<>();
        public String root_cause = "backend";
        public String conclusion = "";
        public List<String> solution = new ArrayList<>();
        public String priority = "中";
    }

    // ================= 辅助 =================

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]+$");

    /**
     * 校验自定义请求头：名称只允许字母/数字/连字符，值禁止换行（防 header 注入）。
     * 返回规范化后的请求头；null 表示未提供。
     */
    private Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || !HEADER_NAME.matcher(k).matches()) {
                throw new ApiException(400, "请求头名称不合法：" + k);
            }
            if (v != null && (v.contains("\r") || v.contains("\n"))) {
                throw new ApiException(400, "请求头值包含非法字符：" + k);
            }
            clean.put(k, v == null ? "" : v);
        }
        return clean;
    }

    private List<Scenario> allScenarios() {
        List<Scenario> all = new ArrayList<>(knowledge.scenarios);
        all.addAll(history.getCustomScenarios());
        return all;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String nowCompact() {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    // ================= 接口 =================

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("total_scenarios", allScenarios().size());
        m.put("total_status_codes", knowledge.statusCodes.size());
        return m;
    }

    @GetMapping("/api/status-codes")
    public Map<String, Object> statusCodes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", knowledge.statusCodes.size());
        m.put("status_codes", knowledge.statusCodes);
        return m;
    }

    @GetMapping("/api/scenarios")
    public Map<String, Object> scenarios(@RequestParam(required = false) String code) {
        List<Scenario> list = allScenarios();
        if (code != null && !code.isEmpty()) {
            list = list.stream()
                    .filter(s -> s.http_codes != null && s.http_codes.contains(code))
                    .collect(Collectors.toList());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", list.size());
        m.put("category_labels", knowledge.categoryLabels);
        m.put("category_colors", knowledge.categoryColors);
        m.put("scenarios", list);
        return m;
    }

    @PostMapping("/api/detect")
    public Map<String, Object> detect(@RequestBody DetectRequest req) {
        if (req.url == null || req.url.trim().isEmpty()) {
            throw new ApiException(400, "URL 不能为空");
        }
        String url = req.url.trim();
        // 先校验 URL（不合法直接 400，与 Python 版一致）
        detectEngine.parseUrl(url);

        int timeout = Math.max(3, Math.min(req.timeout, 30));
        Map<String, String> headers = validateHeaders(req.headers);
        DetectionResult dr = detectEngine.runDetection(url, req.enable_service_check, true, timeout, headers);
        String symptom = req.symptom == null ? "" : req.symptom;
        Map<String, Object> report = matcherEngine.buildReport(dr, symptom, 3);

        if (!report.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> conclusion = (Map<String, Object>) report.get("conclusion");
            history.saveHistory(url, (String) report.get("status_code"), conclusion);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_id", "RPT_" + nowCompact());
        out.put("timestamp", now());
        out.put("input_url", url);
        out.put("status_code", report.get("status_code"));
        out.put("status_text", report.get("status_text"));
        out.put("steps", report.get("steps"));
        out.put("evidence_chain", report.get("evidence_chain"));
        out.put("conclusion", report.get("conclusion"));
        out.put("report", report.get("report"));
        return out;
    }

    @PostMapping("/api/add-scenario")
    public Map<String, Object> addScenario(@RequestBody AddScenarioRequest req) {
        if (req.name == null || req.name.trim().isEmpty()) {
            throw new ApiException(400, "场景名称不能为空");
        }
        if (req.http_codes == null || req.http_codes.isEmpty()) {
            throw new ApiException(400, "至少需要一个状态码");
        }
        if (req.root_cause == null || !knowledge.categoryLabels.containsKey(req.root_cause)) {
            throw new ApiException(400, "问题归属不合法");
        }

        int maxId = 0;
        for (Scenario s : allScenarios()) {
            try {
                maxId = Math.max(maxId, Integer.parseInt(s.id.split("_")[1]));
            } catch (Exception ignored) {
                // 非 SCN_xxx 格式则忽略
            }
        }
        Scenario sc = new Scenario();
        sc.id = String.format("SCN_%03d", maxId + 1);
        sc.name = req.name;
        sc.http_codes = req.http_codes.stream().map(String::valueOf).collect(Collectors.toList());
        sc.response_patterns = req.response_patterns == null ? new ArrayList<>() : req.response_patterns;
        sc.ui_symptoms = req.ui_symptoms == null ? new ArrayList<>() : req.ui_symptoms;
        sc.root_cause = req.root_cause;
        sc.conclusion = (req.conclusion == null || req.conclusion.trim().isEmpty()) ? req.name : req.conclusion;
        sc.evidence = Collections.singletonList("（自定义场景）请按 SOP 手动补充证据");
        sc.solution = (req.solution == null || req.solution.isEmpty())
                ? Collections.singletonList("联系研发确认处理方案") : req.solution;
        sc.probability = 0.9;
        sc.priority = req.priority == null ? "中" : req.priority;
        sc.custom = true;

        history.addCustomScenario(sc);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("scenario", sc);
        out.put("total", allScenarios().size());
        return out;
    }

    @GetMapping("/api/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "30") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("history", history.listHistory(limit));
        return m;
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_scenarios", allScenarios().size());
        m.put("total_status_codes", knowledge.statusCodes.size());
        m.put("custom_scenarios", history.getCustomScenarios().size());
        m.put("history_count", history.listHistory(9999).size());
        m.put("category_labels", knowledge.categoryLabels);
        return m;
    }
}
