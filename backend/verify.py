# -*- coding: utf-8 -*-
"""
故障检测系统 - 离线验证脚本（无需启动服务 / 无需写文件）
运行：python -B verify.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from knowledge.scenarios import SCENARIOS, CATEGORY_LABELS
from knowledge.status_codes import STATUS_CODES, STATUS_CODE_MAP
from engine.matcher import match_scenario, build_report, score_scenario
from engine.detector import parse_url, normalize_status

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        print("  [FAIL] %s  %s" % (name, detail))


print("=" * 60)
print("1. 知识库数量校验")
check("场景库 = 92", len(SCENARIOS) == 92, "当前 %d" % len(SCENARIOS))
check("状态码库 = 32", len(STATUS_CODES) == 32, "当前 %d" % len(STATUS_CODES))

print("=" * 60)
print("2. 场景字段完整性校验")
required = ["id", "name", "http_codes", "response_patterns", "ui_symptoms",
            "root_cause", "conclusion", "evidence", "solution", "probability"]
bad = [s["id"] for s in SCENARIOS if any(k not in s for k in required)]
check("92 个场景字段完整", not bad, "缺字段: %s" % bad)
bad_cause = [s["id"] for s in SCENARIOS if s["root_cause"] not in CATEGORY_LABELS]
check("归属分类合法", not bad_cause, "非法归属: %s" % bad_cause)
ids = [s["id"] for s in SCENARIOS]
check("场景 ID 唯一", len(set(ids)) == len(ids))

print("=" * 60)
print("3. 32 状态码 → 场景覆盖校验")
sc_by_code = {}
for s in SCENARIOS:
    for c in s["http_codes"]:
        sc_by_code.setdefault(c, []).append(s["id"])
missing = [c["code"] for c in STATUS_CODES if c["code"] not in sc_by_code]
check("每个状态码都有对应场景", not missing, "无场景覆盖: %s" % missing)
extra = [c for c in sc_by_code if c not in STATUS_CODE_MAP]
check("场景引用的状态码都在库中", not extra, "库外状态码: %s" % extra)

print("=" * 60)
print("4. 状态码覆盖各场景数量")
for c in STATUS_CODES:
    print("    %-8s → %d 个场景" % (c["code"], len(sc_by_code.get(c["code"], []))))

print("=" * 60)
print("5. 场景匹配抽样验证")
cases = [
    ("200", ["data", "empty"], "页面显示空表格", "SCN_004"),
    ("200", ["data"], "表格显示 undefined", "SCN_010"),
    ("401", ["unauthorized", "token"], "提示未登录", "SCN_036"),
    ("404", [], "点击按钮提示接口不存在", "SCN_045"),
    ("500", ["sqlexception", "cannot be null"], "操作失败提示服务器内部错误", "SCN_072"),
    ("500", ["nullpointer"], "操作报 500", "SCN_073"),
    ("403", [], "提示权限不足", "SCN_041"),
    ("413", [], "上传报文件过大", "SCN_061"),
    ("504", ["timeout", "slow"], "导出报表超时", "SCN_087"),
    ("pending", ["no response"], "页面转圈后提示超时", "SCN_001"),
]
for code, feats, sym, expect in cases:
    top = match_scenario(code, feats, sym, top_n=1)
    hit = top and top[0]["scenario"]["id"] == expect
    check("%s + %s → %s" % (code, sym, expect),
          hit, "实际: %s" % (top[0]["scenario"]["id"] if top else "无匹配"))

print("=" * 60)
print("6. 诊断报告构建验证（模拟探测结果）")
mock_detect = {
    "parsed": {"scheme": "http", "host": "192.168.1.100", "port": 8081,
               "path": "/api/users", "url": "http://192.168.1.100:8081/api/users"},
    "service": {"ok": True, "detail": "TcpTestSucceeded: True（耗时 12ms）"},
    "probe": {"status_code": 200, "status_text": "200",
              "response_time_ms": 45, "headers": {}, "body_length": 20,
              "body": '{"code":200,"message":"success","data":[]}'},
    "features": ["data", "empty", "200", "success"],
    "normalized_status": "200",
    "steps": [{"step": 0, "title": "服务存活检测", "action": "TCP", "result": "pass", "detail": "ok"}],
}
report = build_report(mock_detect, symptoms_text="页面显示空表格")
check("报告构建成功", "error" not in report)
if "error" not in report:
    c = report["conclusion"]
    check("根因=数据库", c["root_cause"] == "database", "实际: %s" % c["root_cause"])
    check("置信度 > 0.5", c["confidence"] > 0.5, "实际: %s" % c["confidence"])
    check("有证据链", len(report["evidence_chain"]) >= 3)
    check("有解决建议", len(c["solution"]) > 0)
    check("3 段式报告完整",
          report["report"]["phenomenon"] and report["report"]["checked"] and report["report"]["conclusion"])
    print("    [现象] %s" % report["report"]["phenomenon"][:80])
    print("    [结论] %s" % report["report"]["conclusion"][:80])

print("=" * 60)
print("7. URL 解析与状态规整")
check("标准 URL", parse_url("http://192.168.1.100:8081/api/users")["host"] == "192.168.1.100")
check("无协议 URL 自动补 http", parse_url("192.168.1.100:8081/a")["scheme"] == "http")
check("空 URL 报错", "error" in parse_url("   "))
check("无主机名 http:// 报错", "error" in parse_url("http://"))
check("无主机名 http://?a=1 报错", "error" in parse_url("http://?a=1"))
check("空白→pending", normalize_status(None) == "pending")
check("超时→504", normalize_status("pending → 504（超时）") == "504")

print("=" * 60)
print("结果：通过 %d 项，失败 %d 项" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
