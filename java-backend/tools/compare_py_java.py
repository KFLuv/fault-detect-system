# -*- coding: utf-8 -*-
"""Python 版 vs Java 版 接口对比脚本（临时，对比后删除）"""
import json
import re
import requests

JAVA = "http://127.0.0.1:8000"
PY = "http://127.0.0.1:8001"


def norm(v):
    # 归一化动态值：耗时、时间戳、报告号、历史 id
    if isinstance(v, str):
        v = re.sub(r"\d+ms", "Nms", v)
        v = re.sub(r"RPT_\d+", "RPT_N", v)
        v = re.sub(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", "TS", v)
        v = re.sub(r"[0-9a-f]{12}", "ID", v)
    return v


def diff(a, b, path=""):
    issues = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k in ("report_id", "timestamp", "ts", "id", "response_time_ms", "elapsed_ms"):
                continue
            if k not in a:
                issues.append("%s.%s: Java 缺少 key" % (path, k))
                continue
            if k not in b:
                issues.append("%s.%s: Python 缺少 key" % (path, k))
                continue
            issues += diff(a[k], b[k], path + "." + k)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            issues.append("%s: 长度 Java=%d Python=%d" % (path, len(a), len(b)))
        for i in range(min(len(a), len(b))):
            issues += diff(a[i], b[i], "%s[%d]" % (path, i))
    elif norm(a) != norm(b):
        issues.append("%s: Java=%s | Python=%s" % (path,
                       json.dumps(a, ensure_ascii=False)[:130],
                       json.dumps(b, ensure_ascii=False)[:130]))
    return issues


def compare(name, method, url, **kw):
    try:
        rj = requests.request(method, JAVA + url, timeout=30, **kw)
        rp = requests.request(method, PY + url, timeout=30, **kw)
        if rj.status_code != rp.status_code:
            print("[FAIL] %s: HTTP Java=%s Python=%s" % (name, rj.status_code, rp.status_code))
            return
        if rj.status_code >= 400:
            print("[OK]   %s: 双方均返回 %s" % (name, rj.status_code))
            return
        issues = diff(rj.json(), rp.json())
        if issues:
            print("[DIFF] %s: %d 处差异" % (name, len(issues)))
            for it in issues[:8]:
                print("       " + it)
        else:
            print("[OK]   %s: 完全一致" % name)
    except Exception as e:
        print("[ERR]  %s: %s" % (name, e))


print("=" * 72)
compare("GET /api/health", "GET", "/api/health")
compare("GET /api/status-codes", "GET", "/api/status-codes")
compare("GET /api/scenarios", "GET", "/api/scenarios")
compare("GET /api/scenarios?code=401", "GET", "/api/scenarios?code=401")
compare("GET /api/scenarios?code=pending", "GET", "/api/scenarios?code=pending")
compare("GET /api/scenarios?code=999", "GET", "/api/scenarios?code=999")
compare("GET /api/stats", "GET", "/api/stats")

compare("POST /api/detect (200 有数据)",
        "POST", "/api/detect",
        json={"url": "http://127.0.0.1:8001/api/health",
              "symptom": "页面显示空表格，没有任何数据",
              "enable_service_check": True, "timeout": 10})
compare("POST /api/detect (端口不通)",
        "POST", "/api/detect",
        json={"url": "http://127.0.0.1:1/", "symptom": "",
              "enable_service_check": True, "timeout": 5})
compare("POST /api/detect (404 路径)",
        "POST", "/api/detect",
        json={"url": "http://127.0.0.1:8001/nonexistent-abc", "symptom": "",
              "enable_service_check": True, "timeout": 10})
compare("POST /api/detect (空 URL)", "POST", "/api/detect", json={"url": ""})
compare("POST /api/detect (非法 URL)", "POST", "/api/detect", json={"url": "http://"})
compare("POST /api/detect (超长 URL)",
        "POST", "/api/detect", json={"url": "http://" + "a" * 5000})
compare("POST /api/detect (缺 url 字段)",
        "POST", "/api/detect", json={"symptom": "x"})

compare("POST /api/add-scenario", "POST", "/api/add-scenario",
        json={"name": "对比测试场景", "http_codes": ["418"],
              "response_patterns": ["teapot"], "ui_symptoms": ["测试"],
              "root_cause": "backend", "conclusion": "对比测试",
              "solution": ["方案A"], "priority": "中"})
compare("GET /api/scenarios (新增后)", "GET", "/api/scenarios")
compare("GET /api/stats (新增后)", "GET", "/api/stats")
compare("GET /api/history", "GET", "/api/history")
compare("POST /api/add-scenario (缺名称)", "POST", "/api/add-scenario",
        json={"http_codes": ["500"]})
compare("POST /api/add-scenario (缺状态码)", "POST", "/api/add-scenario",
        json={"name": "x"})
compare("POST /api/add-scenario (非法归属)", "POST", "/api/add-scenario",
        json={"name": "x", "http_codes": ["500"], "root_cause": "bad"})
print("=" * 72)
