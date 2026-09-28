# -*- coding: utf-8 -*-
"""
场景匹配引擎：基于状态码 + 响应特征 + 症状关键词 对 92 场景库打分匹配
输出：最佳场景、置信度、备选场景、证据链、3 段式报告
"""
import re

from knowledge.scenarios import CATEGORY_LABELS, SCENARIOS


def _match_keywords(text, keywords):
    """计算文本命中关键词的个数"""
    if not text:
        return 0
    text_lower = text.lower()
    hits = 0
    for kw in keywords:
        kw_lower = str(kw).lower()
        if kw_lower and (kw_lower in text_lower or kw_lower in str(text).lower()):
            hits += 1
    return hits


def score_scenario(sc, normalized_status, features, symptoms_text):
    """对单个场景打分"""
    score = 0
    reasons = []

    # 1. 状态码匹配（最高权重）
    if normalized_status in sc["http_codes"]:
        score += 40
        reasons.append("状态码 %s 匹配" % normalized_status)
    elif "pending" in sc["http_codes"] and normalized_status in ("pending", "504", "502"):
        score += 30
        reasons.append("无响应类状态码接近匹配")
    else:
        return 0, reasons  # 状态码完全不匹配则跳过

    # 2. 响应特征匹配
    feat_hits = _match_keywords(" ".join(features), sc["response_patterns"])
    if feat_hits:
        score += min(feat_hits * 8, 24)
        reasons.append("响应特征命中 %d 项" % feat_hits)

    # 3. 症状关键词匹配
    if symptoms_text:
        sym_hits = _match_keywords(symptoms_text, sc["ui_symptoms"])
        if sym_hits:
            score += min(sym_hits * 6, 18)
            reasons.append("症状命中 %d 项" % sym_hits)

    # 4. 优先级加成
    if sc["priority"] == "高":
        score += 2

    # 5. 概率加权
    score = round(score * (0.7 + 0.3 * sc["probability"]), 2)
    return score, reasons


def match_scenario(normalized_status, features, symptoms_text, top_n=3):
    """匹配最佳场景，返回 top_n 备选"""
    results = []
    for sc in SCENARIOS:
        score, reasons = score_scenario(sc, normalized_status, features, symptoms_text)
        if score > 0:
            results.append({
                "scenario": sc,
                "score": score,
                "reasons": reasons,
            })
    results.sort(key=lambda x: x["score"], reverse=True)
    top = results[:top_n]
    if not top:
        # 兜底：按状态码找第一个场景
        for sc in SCENARIOS:
            if normalized_status in sc["http_codes"]:
                top = [{"scenario": sc, "score": 30, "reasons": ["状态码兜底匹配"]}]
                break
    return top


def confidence_from_score(score, max_possible=60):
    """将分数映射为置信度（0-1）"""
    return round(min(score / max_possible, 1.0), 2)


def _render_evidence(ev, parsed, probe):
    """安全渲染证据模板（仅替换 {url}/{host}/{port}/{status}/{time} 占位符，
    避免与证据文本中的 JSON 花括号 {code} 冲突）"""
    return (ev.replace("{url}", parsed["url"])
              .replace("{host}", parsed["host"])
              .replace("{port}", str(parsed["port"]))
              .replace("{status}", str(probe["status_text"]))
              .replace("{time}", str(probe.get("response_time_ms", "-"))))


def build_report(detect_result, symptoms_text=None, top_n=3):
    """
    构建完整诊断报告
    detect_result: run_detection() 的输出
    """
    if "error" in detect_result:
        return {"error": detect_result["error"]}

    normalized = detect_result["normalized_status"]
    features = detect_result["features"]
    parsed = detect_result["parsed"]
    probe = detect_result["probe"]
    steps = detect_result["steps"]

    matches = match_scenario(normalized, features, symptoms_text or "", top_n)
    best = matches[0] if matches else None

    # 证据链（场景证据模板 + 实时探测证据）
    evidence_chain = []
    # 实时证据
    evidence_chain.append({
        "type": "live",
        "title": "URL 解析",
        "content": "%s://%s:%s%s" % (parsed["scheme"], parsed["host"], parsed["port"], parsed["path"]),
    })
    if detect_result.get("service"):
        srv = detect_result["service"]
        evidence_chain.append({
            "type": "live",
            "title": "第 0 步 · 服务存活（TCP）",
            "content": srv["detail"],
        })
    evidence_chain.append({
        "type": "live",
        "title": "第 1 步 · HTTP 探测",
        "content": "Status: %s | 耗时: %sms | %s" % (
            probe["status_text"], probe["response_time_ms"], probe.get("error") or "已捕获响应"
        ),
    })
    if probe["body"]:
        evidence_chain.append({
            "type": "live",
            "title": "响应体（截断）",
            "content": probe["body"][:500],
        })
    # 场景证据模板
    if best:
        for ev in best["scenario"]["evidence"]:
            evidence_chain.append({
                "type": "template",
                "title": "知识库证据",
                "content": _render_evidence(ev, parsed, probe),
            })

    # 结论
    conclusion = {
        "root_cause": best["scenario"]["root_cause"] if best else "unknown",
        "root_cause_label": CATEGORY_LABELS.get(
            best["scenario"]["root_cause"], "无法确定") if best else "无法确定",
        "scenario_id": best["scenario"]["id"] if best else None,
        "scenario_name": best["scenario"]["name"] if best else "未匹配到具体场景",
        "conclusion_text": best["scenario"]["conclusion"] if best else "根据当前证据无法唯一判定，请按检测步骤进一步排查",
        "confidence": confidence_from_score(best["score"]) if best else 0.3,
        "solution": best["scenario"]["solution"] if best else [],
        "matches": [
            {
                "id": m["scenario"]["id"],
                "name": m["scenario"]["name"],
                "root_cause_label": CATEGORY_LABELS[m["scenario"]["root_cause"]],
                "score": m["score"],
                "confidence": confidence_from_score(m["score"]),
            }
            for m in matches
        ],
    }

    # 3 段式汇报
    report = {
        "phenomenon": "用户访问 %s，页面表现：%s（状态码 %s，耗时 %sms）" % (
            parsed["url"],
            symptoms_text or "见 F12 Network 记录",
            probe["status_text"],
            probe["response_time_ms"] or "-",
        ),
        "checked": "\n".join(
            "- %s（%s）→ %s" % (s["title"], s["action"], s["detail"]) for s in steps
        ),
        "conclusion": "%s（归属：%s，置信度 %d%%）" % (
            conclusion["conclusion_text"],
            conclusion["root_cause_label"],
            int(conclusion["confidence"] * 100),
        ),
    }

    return {
        "status_code": normalized,
        "status_text": probe["status_text"],
        "steps": steps,
        "evidence_chain": evidence_chain,
        "conclusion": conclusion,
        "report": report,
    }
