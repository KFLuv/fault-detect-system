# -*- coding: utf-8 -*-
"""
故障检测系统 - FastAPI 后端
接口：
  GET  /                        → 前端页面
  GET  /api/health              → 健康检查
  GET  /api/status-codes        → 32 个状态码
  GET  /api/scenarios           → 92 个场景（支持 ?code= 过滤）
  POST /api/detect              → 执行故障检测
  POST /api/add-scenario        → 新增场景
  GET  /api/history             → 检测历史
  GET  /api/stats               → 统计信息
"""
import json
import os
import sqlite3
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from knowledge.scenarios import CATEGORY_LABELS, CATEGORY_COLORS, SCENARIOS
from knowledge.status_codes import STATUS_CODES, STATUS_CODE_MAP
from engine.detector import run_detection, parse_url
from engine.matcher import build_report

BASE_DIR = Path(__file__).resolve().parent
# 支持环境变量覆盖数据目录（便于部署/测试）
DATA_DIR = Path(os.environ.get("FAULT_DETECT_DATA_DIR", str(BASE_DIR / "data")))
DATA_DIR.mkdir(exist_ok=True, parents=True)
DB_PATH = DATA_DIR / "fault_detect.db"
FRONTEND_DIR = BASE_DIR.parent / "frontend" / "static"

app = FastAPI(title="故障检测系统", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 自定义场景（运行时可动态添加）
custom_scenarios = []
custom_scenarios_file = DATA_DIR / "custom_scenarios.json"


def load_custom_scenarios():
    global custom_scenarios
    if custom_scenarios_file.exists():
        try:
            custom_scenarios = json.loads(custom_scenarios_file.read_text(encoding="utf-8"))
        except Exception:
            custom_scenarios = []


def save_custom_scenarios():
    custom_scenarios_file.write_text(
        json.dumps(custom_scenarios, ensure_ascii=False, indent=2), encoding="utf-8")


def all_scenarios():
    return SCENARIOS + custom_scenarios


def init_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("""
        CREATE TABLE IF NOT EXISTS detect_history (
            id TEXT PRIMARY KEY,
            ts TEXT,
            url TEXT,
            status_code TEXT,
            root_cause TEXT,
            scenario_name TEXT,
            confidence REAL,
            report TEXT
        )
    """)
    conn.commit()
    conn.close()


def save_history(url, normalized_status, conclusion):
    conn = sqlite3.connect(str(DB_PATH))
    try:
        conn.execute(
            "INSERT INTO detect_history (id, ts, url, status_code, root_cause, scenario_name, confidence, report) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (
                uuid.uuid4().hex[:12],
                time.strftime("%Y-%m-%d %H:%M:%S"),
                url,
                normalized_status,
                conclusion.get("root_cause_label", ""),
                conclusion.get("scenario_name", ""),
                conclusion.get("confidence", 0),
                json.dumps(conclusion, ensure_ascii=False),
            ),
        )
        conn.commit()
    finally:
        conn.close()


def list_history(limit=30):
    conn = sqlite3.connect(str(DB_PATH))
    try:
        rows = conn.execute(
            "SELECT id, ts, url, status_code, root_cause, scenario_name, confidence, report "
            "FROM detect_history ORDER BY ts DESC LIMIT ?", (limit,)
        ).fetchall()
        return [
            {
                "id": r[0], "ts": r[1], "url": r[2], "status_code": r[3],
                "root_cause": r[4], "scenario_name": r[5], "confidence": r[6],
            }
            for r in rows
        ]
    finally:
        conn.close()


load_custom_scenarios()
init_db()


# ================= 数据模型 =================

class DetectRequest(BaseModel):
    url: str
    symptom: str = ""
    enable_service_check: bool = True
    timeout: int = 10


class AddScenarioRequest(BaseModel):
    name: str
    http_codes: list
    response_patterns: list = []
    ui_symptoms: list = []
    root_cause: str = "backend"
    conclusion: str = ""
    solution: list = []
    priority: str = "中"


# ================= 页面 =================

@app.get("/", include_in_schema=False)
async def index():
    return FileResponse(str(FRONTEND_DIR / "index.html"))


app.mount("/static", StaticFiles(directory=str(FRONTEND_DIR)), name="static")


# ================= API =================

@app.get("/api/health")
async def health():
    return {"status": "ok", "total_scenarios": len(all_scenarios()),
            "total_status_codes": len(STATUS_CODES)}


@app.get("/api/status-codes")
def get_status_codes():
    return {"total": len(STATUS_CODES), "status_codes": STATUS_CODES}


@app.get("/api/scenarios")
def get_scenarios(code: str = None):
    scenarios = all_scenarios()
    if code:
        scenarios = [s for s in scenarios if code in s["http_codes"]]
    return {
        "total": len(scenarios),
        "category_labels": CATEGORY_LABELS,
        "category_colors": CATEGORY_COLORS,
        "scenarios": scenarios,
    }


# 注意：detect 为同步端点（含阻塞式网络探测），FastAPI 会放入线程池执行，
# 避免阻塞事件循环导致"自己探测自己"超时。
@app.post("/api/detect")
def detect(req: DetectRequest):
    if not req.url.strip():
        raise HTTPException(status_code=400, detail="URL 不能为空")
    parsed = parse_url(req.url)
    if "error" in parsed:
        raise HTTPException(status_code=400, detail=parsed["error"])

    timeout = min(max(req.timeout, 3), 30)
    detect_result = run_detection(
        req.url,
        enable_service_check=req.enable_service_check,
        timeout=timeout,
    )
    report = build_report(detect_result, symptoms_text=req.symptom)

    if "error" not in report:
        save_history(req.url, report["status_code"], report["conclusion"])

    return {
        "report_id": "RPT_%s" % time.strftime("%Y%m%d%H%M%S"),
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "input_url": req.url,
        "status_code": report.get("status_code"),
        "status_text": report.get("status_text"),
        "steps": report.get("steps", []),
        "evidence_chain": report.get("evidence_chain", []),
        "conclusion": report.get("conclusion", {}),
        "report": report.get("report", {}),
    }


@app.post("/api/add-scenario")
def add_scenario(req: AddScenarioRequest):
    if not req.name.strip():
        raise HTTPException(status_code=400, detail="场景名称不能为空")
    if not req.http_codes:
        raise HTTPException(status_code=400, detail="至少需要一个状态码")
    if req.root_cause not in CATEGORY_LABELS:
        raise HTTPException(status_code=400, detail="问题归属不合法")

    max_id = max((int(s["id"].split("_")[1]) for s in all_scenarios()), default=0)
    new_scenario = {
        "id": "SCN_%03d" % (max_id + 1),
        "name": req.name,
        "http_codes": [str(c) for c in req.http_codes],
        "response_patterns": req.response_patterns,
        "ui_symptoms": req.ui_symptoms,
        "root_cause": req.root_cause,
        "conclusion": req.conclusion or req.name,
        "evidence": ["（自定义场景）请按 SOP 手动补充证据"],
        "solution": req.solution or ["联系研发确认处理方案"],
        "probability": 0.9,
        "priority": req.priority,
        "custom": True,
    }
    custom_scenarios.append(new_scenario)
    save_custom_scenarios()
    return {"status": "ok", "scenario": new_scenario,
            "total": len(all_scenarios())}


@app.get("/api/history")
def get_history(limit: int = 30):
    return {"history": list_history(limit)}


@app.get("/api/stats")
def stats():
    return {
        "total_scenarios": len(all_scenarios()),
        "total_status_codes": len(STATUS_CODES),
        "custom_scenarios": len(custom_scenarios),
        "history_count": len(list_history(9999)),
        "category_labels": CATEGORY_LABELS,
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
