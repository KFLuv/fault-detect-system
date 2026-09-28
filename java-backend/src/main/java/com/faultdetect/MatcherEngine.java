package com.faultdetect;

import com.faultdetect.DetectEngine.DetectionResult;
import com.faultdetect.Knowledge.Scenario;
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
    /** 【P2】通用推理层：知识库未命中或置信度不足时，由通用规则库产出多假设结论 */
    private final HypothesisEngine hypothesisEngine;
    /** 【P3】可选 LLM 增强层：默认关闭，仅在调用方显式要求（enable_llm=true）且后端已配置时才联网 */
    private final LlmAdvisor llmAdvisor;

    public MatcherEngine(Knowledge knowledge, HypothesisEngine hypothesisEngine, LlmAdvisor llmAdvisor) {
        this.knowledge = knowledge;
        this.hypothesisEngine = hypothesisEngine;
        this.llmAdvisor = llmAdvisor;
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

    /** 构建完整诊断报告（兼容既有调用：不启用可选 LLM 增强层） */
    public Map<String, Object> buildReport(DetectionResult dr, String symptomsText, int topN) {
        return buildReport(dr, symptomsText, topN, false);
    }

    /**
     * 构建完整诊断报告。
     *
     * @param enableLlm 是否请求【P3】可选 LLM 增强层。
     *                  为 true 且后端 faultdetect.llm.enabled=true 时才会发起外部调用；
     *                  未配置 / 内网不可达 / 返回异常都会自动降级，既有结论不受影响。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildReport(DetectionResult dr, String symptomsText, int topN, boolean enableLlm) {
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
        double bestConfidence = best != null ? confidenceFromScore(best.score) : 0;

        // 【P2】通用假设推理：把本次观测事实喂给通用技术规则库，产出多假设排序结果。
        // 必须在证据链/结论/3 段式汇报之前完成，其步骤与结论才能一并进入报告。
        HypothesisEngine.InferenceResult inference = hypothesisEngine.infer(
                dr, best != null ? best.scenario : null, bestConfidence);
        if (!inference.hypotheses.isEmpty()) {
            dr.steps.add(DetectEngine.step(7, "通用假设推理（多假设排序）",
                    "把观测事实喂给通用技术规则库（不依赖状态码查表），产出带置信度 / 反证条件 / 验证动作的假设列表",
                    "info", summarizeHypotheses(inference)));
        }

        // 【P3】可选 LLM 增强层：仅在调用方显式要求（enable_llm=true）时才联网。
        // 未请求 / 未启用 / 未配置 / 内网不可达 / 返回异常 → 一律安全降级，绝不抛异常、绝不影响以上结论。
        LlmAdvisor.Advice advice = enableLlm
                ? llmAdvisor.advise(dr, inference,
                        best != null && bestConfidence >= HypothesisEngine.FAST_PATH_MIN_CONFIDENCE,
                        best != null ? best.scenario.name : "", bestConfidence)
                : llmAdvisor.notRequested();
        boolean llmOk = LlmAdvisor.STATUS_OK.equals(advice.status) && advice.root_cause != null;
        Map<String, Object> adviceMap = advice.toMap();
        if (advice.attempted) {
            // 步骤号跟随上一步：未产生假设时第 7 步为空缺，LLM 步骤顺延为 7，避免出现空档
            int llmStep = inference.hypotheses.isEmpty() ? 7 : 8;
            dr.steps.add(DetectEngine.step(llmStep, "LLM 增强分析（可选）",
                    "把观测事实 / 差分结论 / 通用假设交给外部大模型，请其补充根因判断、可执行验证动作与反证条件",
                    llmOk ? "info" : "skip", summarizeAdvice(advice)));
        }

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
        // 【P0-2】响应关键信息：原样呈现服务端业务错误，不参与打分（追加条目，不影响既有条目）
        if (dr.keyInfo != null && !dr.keyInfo.isEmpty()) {
            Map<String, Object> ek = new LinkedHashMap<>();
            ek.put("type", "live");
            ek.put("title", "响应关键信息");
            StringBuilder ksb = new StringBuilder();
            for (Map.Entry<String, String> en : dr.keyInfo.entrySet()) {
                if (ksb.length() > 0) {
                    ksb.append("\n");
                }
                ksb.append(en.getKey()).append("：").append(en.getValue());
            }
            ek.put("content", ksb.toString());
            evidenceChain.add(ek);
        }
        // 【P1】差分隔离实验：原样呈现对照矩阵与差分推导结论（追加条目，不影响既有条目）
        if (dr.contrast != null && !dr.contrast.experiments.isEmpty()) {
            Map<String, Object> ec = new LinkedHashMap<>();
            ec.put("type", "live");
            ec.put("title", "差分隔离实验");
            StringBuilder cb = new StringBuilder();
            cb.append("基线：").append(dr.contrast.baseline);
            for (ContrastProbe.Experiment ex : dr.contrast.experiments) {
                cb.append("\n- ").append(ex.title).append("：").append(ex.change)
                        .append(" → ").append(ex.observed);
            }
            for (ContrastProbe.Conclusion c : dr.contrast.conclusions) {
                cb.append("\n【推导 ").append(c.id).append("·").append(c.title).append("】").append(c.derived)
                        .append("（置信度 ").append((int) Math.round(c.confidence * 100))
                        .append("%；依据：").append(c.basis).append("）");
            }
            for (String n : dr.contrast.notes) {
                cb.append("\n注：").append(n);
            }
            ec.put("content", cb.toString());
            evidenceChain.add(ec);
        }
        // 【P3】LLM 增强分析（可选层：未启用 / 已降级 / 成功都会如实记录）：追加条目，不影响既有条目
        if (advice.requested) {
            Map<String, Object> el = new LinkedHashMap<>();
            el.put("type", "external");
            el.put("title", "LLM 增强分析（可选，外部模型）");
            el.put("content", adviceEvidenceText(advice));
            evidenceChain.add(el);
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
        // 【P2】结论优先级：
        //   1) 知识库命中且置信度 ≥ FAST_PATH_MIN_CONFIDENCE → 「高置信快速路径」，直接采纳场景库结论（与改造前一致）
        //   2) 其余情况 → 由通用假设推理的最高置信假设驱动，不再退化成「未匹配到具体场景」
        boolean fastPath = best != null && bestConfidence >= HypothesisEngine.FAST_PATH_MIN_CONFIDENCE;
        HypothesisEngine.Hypothesis top = inference.top;
        // 【P3】LLM 结论只在「通用规则 / 差分推导 / 知识库三条路径都没给出任何假设」时才升格为主结论，
        // 避免外部模型抢走本就有证据支撑的结论；其余情况 LLM 只作为独立区块并列展示。
        boolean llmPrimary = llmOk && !fastPath && top == null;
        String authNote = HypothesisEngine.authExclusionNote(dr.contrast);

        String rootCause;
        String rootCauseLabel;
        String scenarioId;
        String scenarioName;
        String conclusionText;
        double confidence;
        List<String> solution;
        List<String> refuteIf = new ArrayList<>();

        if (fastPath) {
            rootCause = best.scenario.root_cause;
            rootCauseLabel = knowledge.categoryLabels.getOrDefault(rootCause, "无法确定");
            scenarioId = best.scenario.id;
            scenarioName = best.scenario.name;
            conclusionText = best.scenario.conclusion;
            confidence = bestConfidence;
            solution = best.scenario.solution == null ? Collections.emptyList() : best.scenario.solution;
        } else if (top != null) {
            rootCause = top.category;
            rootCauseLabel = knowledge.categoryLabels.getOrDefault(rootCause, "无法确定");
            scenarioId = null;
            scenarioName = "假设推理（" + top.title + "）";
            conclusionText = top.description + (authNote.isEmpty() ? "" : "　※ " + authNote);
            confidence = top.confidence;
            solution = top.verify_actions;
            refuteIf = top.refute_if;
        } else if (llmPrimary) {
            // 【P3】库外故障兜底：规则 / 差分 / 知识库都无结论时，采用 LLM 的根因判断
            rootCause = advice.root_cause;
            rootCauseLabel = knowledge.categoryLabels.getOrDefault(rootCause, "无法确定");
            scenarioId = null;
            scenarioName = "LLM 增强推理（" + rootCauseLabel + "）";
            conclusionText = advice.conclusion_text
                    + (advice.reasoning == null || advice.reasoning.isEmpty() ? "" : "　（推理链：" + advice.reasoning + "）")
                    + "　※ 该结论由可选 LLM 增强层给出，通用规则 / 差分推导均未命中，请按验证动作复核后再采信";
            confidence = advice.confidence == null ? 0.5 : advice.confidence;
            solution = advice.verify_actions.isEmpty() ? LlmAdvisor.defaultVerifyActions() : advice.verify_actions;
            refuteIf = advice.refute_if;
        } else {
            rootCause = "unknown";
            rootCauseLabel = "无法确定";
            scenarioId = null;
            scenarioName = "未匹配到具体场景";
            conclusionText = "根据当前证据无法唯一判定，请按检测步骤进一步排查";
            confidence = 0.3;
            solution = Collections.emptyList();
        }

        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("root_cause", rootCause);
        conclusion.put("root_cause_label", rootCauseLabel);
        conclusion.put("scenario_id", scenarioId);
        conclusion.put("scenario_name", scenarioName);
        conclusion.put("conclusion_text", conclusionText);
        conclusion.put("confidence", confidence);
        conclusion.put("solution", solution);
        // 【P2】结论来源 / 是否走了知识库快速路径 / 反证条件（追加字段，既有字段顺序与含义均不变）
        // 【P3】reasoning_source 增加第 4 档取值 llm：库外故障且 LLM 层给出结论时
        conclusion.put("reasoning_source", fastPath ? "knowledge"
                : (top != null ? top.source : (llmPrimary ? "llm" : "none")));
        conclusion.put("knowledge_fast_path", fastPath);
        conclusion.put("refute_if", refuteIf);
        // 【P3】四档来源标注：把本次结论链路上实际用到过的推理来源全部列出（知识库 / 差分推导 / 通用规则 / LLM）
        conclusion.put("reasoning_sources", collectSources(inference, fastPath, llmOk));
        // 【P3】LLM 增强分析结果（可选层：未请求 / 未启用 / 已降级 / 成功均如实呈现）：追加字段
        conclusion.put("llm_advice", adviceMap);
        // 【P1】差分推导出的全部结论（追加字段，既有字段顺序与含义均不变）
        List<Map<String, Object>> derivedConclusions = new ArrayList<>();
        if (dr.contrast != null) {
            for (ContrastProbe.Conclusion c : dr.contrast.conclusions) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("id", c.id);
                dm.put("title", c.title);
                dm.put("derived", c.derived);
                dm.put("confidence", c.confidence);
                dm.put("basis", c.basis);
                derivedConclusions.add(dm);
            }
        }
        conclusion.put("derived_conclusions", derivedConclusions);

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

        // 【P2】多假设列表（追加字段，既有字段顺序与含义均不变）
        conclusion.put("hypotheses", inference.toMaps());

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
        // 【P2】结论来源不同，汇报括注也不同：快速路径标注归属，假设推理标注推理来源
        // 【P3】若结论来自可选 LLM 增强层，括注如实标注，便于汇报时区分「证据推导」与「外部模型判断」
        String ct = String.valueOf(conclusion.get("conclusion_text"));
        report.put("conclusion", fastPath
                ? ct + "（归属：" + conclusion.get("root_cause_label") + "，置信度 " + confidencePct + "%）"
                : (top != null
                    ? ct + "（假设推理，置信度 " + confidencePct + "%）"
                    : (llmPrimary
                        ? ct + "（LLM 增强推理，置信度 " + confidencePct + "%）"
                        : ct + "（置信度 " + confidencePct + "%）")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status_code", normalized);
        out.put("status_text", dr.probe.statusText);
        out.put("steps", dr.steps);
        out.put("evidence_chain", evidenceChain);
        out.put("conclusion", conclusion);
        out.put("report", report);
        // 【P0-2】响应关键信息（追加字段，既有字段顺序与含义均不变）
        out.put("key_info", dr.keyInfo == null ? Collections.emptyMap() : dr.keyInfo);
        // 【P2】多假设推导结果（与 conclusion.hypotheses 同源，便于外部直接消费）：追加字段
        out.put("hypotheses", conclusion.get("hypotheses"));
        // 【P3】LLM 增强分析结果（与 conclusion.llm_advice 同源）：追加字段
        out.put("llm", adviceMap);
        return out;
    }

    /** 【P2】把多假设列表压成一行行的步骤摘要，用于「检测流程」区块展示 */
    private static String summarizeHypotheses(HypothesisEngine.InferenceResult inf) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (HypothesisEngine.Hypothesis h : inf.hypotheses) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String src = "knowledge".equals(h.source) ? "场景库快速路径"
                    : ("contrast".equals(h.source) ? "差分推导" : "通用规则");
            sb.append(i++).append(". [").append(h.category_label).append("｜")
                    .append(Math.round(h.confidence * 100)).append("%｜").append(src).append("] ")
                    .append(h.title).append(" → ").append(h.description);
        }
        sb.append("\n（按置信度降序取前 ").append(inf.hypotheses.size())
                .append(" 条；每条假设均带支持证据、反证条件与可执行验证动作）");
        return sb.toString();
    }

    /** 【P3】把 LLM 增强层的执行情况压成一行摘要，用于「检测流程」区块展示（含降级原因，便于排障） */
    private static String summarizeAdvice(LlmAdvisor.Advice a) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(adviceStatusText(a.status));
        if (a.model != null && !a.model.isEmpty()) {
            sb.append("｜模型：").append(a.model);
        }
        if (a.latency_ms > 0) {
            sb.append("｜耗时：").append(a.latency_ms).append("ms");
        }
        sb.append("\n").append(a.reason == null || a.reason.isEmpty() ? "调用成功，结果见「LLM 增强分析」区块" : a.reason);
        if (LlmAdvisor.STATUS_OK.equals(a.status)) {
            sb.append("\n根因判断：").append(a.root_cause_label).append("｜置信度 ")
                    .append(Math.round((a.confidence == null ? 0 : a.confidence) * 100)).append("%");
            sb.append("\n结论：").append(a.conclusion_text);
        }
        return sb.toString();
    }

    /** 【P3】LLM 增强层的证据链条目文本（成功时给结论，降级时给原因，保证可追溯） */
    private static String adviceEvidenceText(LlmAdvisor.Advice a) {
        StringBuilder sb = new StringBuilder();
        sb.append("调用来源：外部大模型（可选层，需显式开启）｜状态：").append(adviceStatusText(a.status));
        if (a.model != null && !a.model.isEmpty()) {
            sb.append("｜模型：").append(a.model);
        }
        sb.append("\n说明：").append(a.reason == null || a.reason.isEmpty() ? "调用成功" : a.reason);
        if (LlmAdvisor.STATUS_OK.equals(a.status)) {
            sb.append("\n根因判断：").append(a.root_cause_label).append("（").append(a.root_cause).append("）");
            sb.append("\n结论：").append(a.conclusion_text);
            if (a.reasoning != null && !a.reasoning.isEmpty()) {
                sb.append("\n推理链：").append(a.reasoning);
            }
            for (String v : a.verify_actions) {
                sb.append("\n验证动作：").append(v);
            }
            for (String r : a.refute_if) {
                sb.append("\n反证条件：").append(r);
            }
        } else if (a.raw != null && !a.raw.isEmpty()) {
            sb.append("\n原始响应（截断）：").append(a.raw);
        }
        return sb.toString();
    }

    /** 【P3】四档来源标注：按固定顺序汇总本次结论链路上实际用到过的推理来源 */
    private static List<String> collectSources(HypothesisEngine.InferenceResult inf, boolean fastPath, boolean llmOk) {
        List<String> sources = new ArrayList<>();
        if (fastPath) {
            sources.add("knowledge");
        }
        if (inf != null) {
            for (HypothesisEngine.Hypothesis h : inf.hypotheses) {
                if (h.source != null && !sources.contains(h.source)) {
                    sources.add(h.source);
                }
            }
        }
        if (llmOk) {
            sources.add("llm");
        }
        return sources;
    }

    /** 【P3】LLM 层状态的中文说明 */
    private static String adviceStatusText(String status) {
        if (LlmAdvisor.STATUS_OK.equals(status)) {
            return "调用成功";
        }
        if (LlmAdvisor.STATUS_DEGRADED.equals(status)) {
            return "已自动降级（不影响规则结论）";
        }
        return "未启用";
    }
}
