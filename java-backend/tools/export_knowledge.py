# -*- coding: utf-8 -*-
"""
知识库导出脚本（一次性使用）
把 Python 版知识库（92 场景 / 32 状态码）导出为 JSON，
供 Java 版后端（java-backend）读取，保证数据与 Python 版完全一致。

运行：.venv\\Scripts\\python.exe java-backend\\tools\\export_knowledge.py
"""
import json
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[2] / "backend"
sys.path.insert(0, str(BACKEND))

from knowledge.scenarios import CATEGORY_LABELS, CATEGORY_COLORS, SCENARIOS
from knowledge.status_codes import STATUS_CODES, STATUS_CODE_MAP

OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "knowledge"
OUT.mkdir(parents=True, exist_ok=True)

scenarios_data = {
    "category_labels": CATEGORY_LABELS,
    "category_colors": CATEGORY_COLORS,
    "scenarios": SCENARIOS,
}
status_data = {
    "status_codes": STATUS_CODES,
    "status_code_map": STATUS_CODE_MAP,
}

(OUT / "scenarios.json").write_text(
    json.dumps(scenarios_data, ensure_ascii=False, indent=2), encoding="utf-8")
(OUT / "status_codes.json").write_text(
    json.dumps(status_data, ensure_ascii=False, indent=2), encoding="utf-8")

print("OK: %d scenarios, %d status codes -> %s" % (len(SCENARIOS), len(STATUS_CODES), OUT))
