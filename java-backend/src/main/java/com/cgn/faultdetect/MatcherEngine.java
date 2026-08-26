package com.cgn.faultdetect;

import com.cgn.faultdetect.DetectEngine.DetectionResult;
import com.cgn.faultdetect.Knowledge.Scenario;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景匹配引擎（对应 Python 版 engine/matcher.py）
 * 基于状态码 + 响应特征 + 症状关键词对场景库打分匹配，输出最佳场景/置信度/证据链/3 段式报告
 */
@Component
public class MatcherEngine {

    private final Knowledge knowledge;

    public MatcherEngine(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    public static class MatchResult {
        public final Scenario scenario;
        public final double score;
        public final List<String> reasons;

        public MatchResult(Scenario scenario, double score, List<String> reasons) {
            this.scenario = scenario;
            this.score = score;
            this.reasons = reasons;
        }
    }

    public static class ScoreResult {
        public final double score;
        public final List<String> reasons;

        public ScoreResult(double score, List<String> reasons) {
            this.score = score;
            this.reasons = reasons;
        }
    }

    /** 计算文本命中关键词的个数 */
    private int matchKeywords(String text, List<String> keywords) {
        if (text == null || text.isEmpty() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String textLower = text.toLowerCase();
        int hits = 0;
        for (Object kw : keywords) {
            String kwLower = String.valueOf(kw).toLowerCase();
            if (!kwLower.isEmpty() && (textLower.contains(kwLower))) {
                hits++;
            }
        }
        return hits;
    }

    /** 对单个场景打分 */
    public ScoreResult scoreScenario(Scenario sc, String normalizedStatus, List<String> features, String symptomsText) {
        double score = 0;
        List<String> reasons = new ArrayList<>();

        // 1. 状态码匹配（最高权重）
        if (sc.http_codes != null && sc.http_codes.contains(normalizedStatus)) {
            score += 40;
            reasons.add("状态码 " + normalizedStatus + " 匹配");
        } else if (sc.http_codes != null && sc.http_codes.contains("pending")
                && (normalizedStatus.equals("pending") || normalizedStatus.equals("504") || normalizedStatus.equals("502"))) {
            score += 30;
            reasons.add("无响应类状态码接近匹配");
        } else {
            return new ScoreResult(0, reasons); // 状态码完全不匹配则跳过
        }

        // 2. 响应特征匹配
        String featText = String.join(" ", features == null ? Collections.emptyList() : features);
        int featHits = matchKeywords(featText,
                sc.response_patterns == null ? Collections.emptyList() : sc.response_patterns);
        if (featHits > 0) {
            score += Math.min(featHits * 8, 24);
            reasons.add("响应特征命中 " + featHits + " 项");
        }

        // 3. 症状关键词匹配
        if (symptomsText != null && !symptomsText.isEmpty()) {
            int symHits = matchKeywords(symptomsText,
                    sc.ui_symptoms == null ? Collections.emptyList() : sc.ui_symptoms);
            if (symHits > 0) {
                score += Math.min(symHits * 6, 18);
                reasons.add("症状命中 " + symHits + " 项");
            }
        }

        // 4. 优先级加成
        if ("高".equals(sc.priority)) {
            score += 2;
        }

        // 5. 概率加权（保留 2 位小数）
        score = Math.round(score * (0.7 + 0.3 * sc.probability) * 100.0) / 100.0;
        return new ScoreResult(score, reasons);
    }

    /** 匹配最佳场景，返回 topN 备选（仅内置场景，与 Python 版一致） */
    public List<MatchResult> matchScenario(String normalizedStatus, List<String> features, String symptomsText, int topN) {
        List<MatchResult> results = new ArrayList<>();
        for (Scenario sc : knowledge.scenarios) {
            ScoreResult sr = scoreScenario(sc, normalizedStatus, features, symptomsText);
            if (sr.score > 0) {
                results.add(new MatchResult(sc, sr.score, sr.reasons));
            }
        }
        results.sort((a, b) -> Double.compare(b.score, a.score));
        List<MatchResult> top = new ArrayList<>(results.subList(0, Math.min(topN, results.size())));

        if (top.isEmpty()) {
            // 兜底：按状态码找第一个场景
            for (Scenario sc : knowledge.scenarios) {
                if (sc.http_codes != null && sc.http_codes.contains(normalizedStatus)) {
                    top = new ArrayList<>();
                    top.add(new MatchResult(sc, 30, Collections.singletonList("状态码兜底匹配")));
                    break;
                }
            }
        }
        return top;
    }

    /** 将分数映射为置信度（0-1） */
    public double confidenceFromScore(double score) {
        return Math.round(Math.min(score / 60.0, 1.0) * 100.0) / 100.0;
    }

    /** 安全渲染证据模板（仅替换 {url}/{host}/{port}/{status}/{time} 占位符） */
    private String renderEvidence(String ev, DetectionResult dr) {
        if (ev == null) {
            return "";
        }
        String time = dr.probe.responseTimeMs == null ? "-" : String.valueOf(dr.probe.responseTimeMs);
        return ev.replace("{url}", dr.parsed.url)
                .replace("{host}", dr.parsed.host)
                .replace("{port}", String.valueOf(dr.parsed.port))
                .replace("{status}", dr.probe.statusText)
                .replace("{time}", time);
    }

    /** 构建完整诊断报告 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildReport(DetectionResult dr, String symptomsText, int topN) {
        if (dr.error != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", dr.error);
            return m;
        }

        String normalized = dr.normalizedStatus;
        List<String> features = dr.features;
        String symText = symptomsText == null ? "" : symptomsText;

        List<MatchResult> matches = matchScenario(normalized, features, symText, topN);
        MatchResult best = matches.isEmpty() ? null : matches.get(0);

        // ---- 证据链 ----
        List<Map<String, Object>> evidenceChain = new ArrayList<>();
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("type", "live");
        e1.put("title", "URL 解析");
        e1.put("content", dr.parsed.scheme + "://" + dr.parsed.host + ":" + dr.parsed.port + dr.parsed.path);
        evidenceChain.add(e1);

        if (dr.service != null) {
            Map<String, Object> e2 = new LinkedHashMap<>();
            e2.put("type", "live");
            e2.put("title", "第 0 步 · 服务存活（TCP）");
            e2.put("content", dr.service.detail);
            evidenceChain.add(e2);
        }
        Map<String, Object> e3 = new LinkedHashMap<>();
        e3.put("type", "live");
        e3.put("title", "第 1 步 · HTTP 探测");
        e3.put("content", "Status: " + dr.probe.statusText + " | 耗时: "
                + (dr.probe.responseTimeMs == null ? "-" : dr.probe.responseTimeMs + "ms")
                + " | " + (dr.probe.error != null ? dr.probe.error : "已捕获响应"));
        evidenceChain.add(e3);

        if (dr.probe.body != null && !dr.probe.body.isEmpty()) {
            Map<String, Object> e4 = new LinkedHashMap<>();
            e4.put("type", "live");
            e4.put("title", "响应体（截断）");
            String body = dr.probe.body;
            e4.put("content", body.length() > 500 ? body.substring(0, 500) : body);
            evidenceChain.add(e4);
        }
        if (best != null && best.scenario.evidence != null) {
            for (String ev : best.scenario.evidence) {
                Map<String, Object> et = new LinkedHashMap<>();
                et.put("type", "template");
                et.put("title", "知识库证据");
                et.put("content", renderEvidence(ev, dr));
                evidenceChain.add(et);
            }
        }

        // ---- 结论 ----
        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("root_cause", best != null ? best.scenario.root_cause : "unknown");
        conclusion.put("root_cause_label", best != null
                ? knowledge.categoryLabels.getOrDefault(best.scenario.root_cause, "无法确定") : "无法确定");
        conclusion.put("scenario_id", best != null ? best.scenario.id : null);
        conclusion.put("scenario_name", best != null ? best.scenario.name : "未匹配到具体场景");
        conclusion.put("conclusion_text", best != null ? best.scenario.conclusion
                : "根据当前证据无法唯一判定，请按检测步骤进一步排查");
        conclusion.put("confidence", best != null ? confidenceFromScore(best.score) : 0.3);
        conclusion.put("solution", best != null && best.scenario.solution != null
                ? best.scenario.solution : Collections.emptyList());

        List<Map<String, Object>> matchList = new ArrayList<>();
        for (MatchResult m : matches) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.scenario.id);
            mm.put("name", m.scenario.name);
            mm.put("root_cause_label", knowledge.categoryLabels.getOrDefault(m.scenario.root_cause, "无法确定"));
            mm.put("score", m.score);
            mm.put("confidence", confidenceFromScore(m.score));
            matchList.add(mm);
        }
        conclusion.put("matches", matchList);

        // ---- 3 段式汇报 ----
        StringBuilder checked = new StringBuilder();
        for (int i = 0; i < dr.steps.size(); i++) {
            if (i > 0) {
                checked.append("\n");
            }
            Map<String, Object> s = dr.steps.get(i);
            checked.append("- ").append(s.get("title")).append("（").append(s.get("action"))
                    .append("）→ ").append(s.get("detail"));
        }
        String confidencePct = String.valueOf((int) (((Double) conclusion.get("confidence")) * 100));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phenomenon", "用户访问 " + dr.parsed.url + "，页面表现："
                + (symText.isEmpty() ? "见 F12 Network 记录" : symText)
                + "（状态码 " + dr.probe.statusText + "，耗时 "
                + (dr.probe.responseTimeMs == null ? "-" : dr.probe.responseTimeMs + "ms") + "）");
        report.put("checked", checked.toString());
        report.put("conclusion", conclusion.get("conclusion_text") + "（归属：" + conclusion.get("root_cause_label")
                + "，置信度 " + confidencePct + "%）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status_code", normalized);
        out.put("status_text", dr.probe.statusText);
        out.put("steps", dr.steps);
        out.put("evidence_chain", evidenceChain);
        out.put("conclusion", conclusion);
        out.put("report", report);
        return out;
    }
}
