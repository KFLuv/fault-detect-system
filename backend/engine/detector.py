# -*- coding: utf-8 -*-
"""
检测引擎：URL 解析、服务存活检测（TCP）、HTTP 探测、响应特征提取
对应排障 SOP：第 0 步（服务存活）→ 第 1 步（F12 抓包）
"""
import json
import re
import socket
import time
import warnings
from urllib.parse import urlparse

import requests
from urllib3.exceptions import InsecureRequestWarning

warnings.simplefilter("ignore", InsecureRequestWarning)

DEFAULT_TIMEOUT = 10  # 秒
LARGE_BODY_LIMIT = 4000  # 响应体截断长度


def parse_url(url):
    """解析 URL，返回 host/port/path 等信息"""
    url = url.strip()
    if not url:
        return {"error": "URL 不能为空"}
    if not url.startswith(("http://", "https://")):
        url = "http://" + url
    try:
        parsed = urlparse(url)
        host = parsed.hostname or ""
        scheme = parsed.scheme or "http"
        if not host:
            return {"error": "URL 格式不正确：缺少主机名（示例：http://192.168.1.100:8081）"}
        port = parsed.port or (443 if scheme == "https" else 80)
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        return {
            "url": url,
            "scheme": scheme,
            "host": host,
            "port": port,
            "path": path,
        }
    except Exception as e:
        return {"error": "URL 格式不正确：%s" % str(e)}


def tcp_check(host, port, timeout=5):
    """第 0 步：TCP 服务存活检测"""
    start = time.time()
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        result = sock.connect_ex((host, port))
        sock.close()
        elapsed = round((time.time() - start) * 1000)
        return {
            "ok": result == 0,
            "detail": "TcpTestSucceeded: %s（耗时 %dms）" % ("True" if result == 0 else "False", elapsed),
            "elapsed_ms": elapsed,
        }
    except socket.gaierror:
        return {"ok": False, "detail": "DNS 解析失败：无法解析主机 %s" % host, "elapsed_ms": 0}
    except Exception as e:
        return {"ok": False, "detail": "连接异常：%s" % str(e), "elapsed_ms": 0}


def http_probe(url, timeout=DEFAULT_TIMEOUT, method="GET"):
    """第 1 步：HTTP 请求探测，捕获状态码与响应"""
    result = {
        "status_code": None,
        "status_text": "pending",
        "response_time_ms": None,
        "headers": {},
        "body": "",
        "error": None,
    }
    start = time.time()
    try:
        # trust_env=False：禁用系统代理。现场排查多为内网地址，直连而非走企业代理
        session = requests.Session()
        session.trust_env = False
        resp = session.request(
            method, url, timeout=timeout, allow_redirects=False, verify=False,
        )
        result["status_code"] = resp.status_code
        result["status_text"] = str(resp.status_code)
        result["response_time_ms"] = round((time.time() - start) * 1000)
        result["headers"] = dict(resp.headers)
        body = resp.text
        result["body"] = body[:LARGE_BODY_LIMIT]
        result["body_length"] = len(body)
        # 统一关闭 SSL 告警（探测场景）
    except requests.exceptions.ConnectionError as e:
        result["error"] = "连接失败（后端服务不可达）：%s" % str(e)
        result["status_text"] = "502 模拟（连接被拒绝）"
    except requests.exceptions.Timeout as e:
        result["error"] = "请求超时（超过 %ds 无响应）" % timeout
        result["status_text"] = "pending → 504（超时）"
    except requests.exceptions.SSLError as e:
        result["error"] = "SSL 证书错误：%s" % str(e)
        result["status_text"] = "SSL 异常"
    except requests.exceptions.InvalidURL as e:
        result["error"] = "URL 无效：%s" % str(e)
        result["status_text"] = "URL 无效"
    except Exception as e:
        result["error"] = "请求异常：%s" % str(e)
        result["status_text"] = "请求异常"
    finally:
        result["response_time_ms"] = result["response_time_ms"] or round((time.time() - start) * 1000)
    return result


def extract_features(probe_result):
    """从响应体中提取特征，供场景匹配使用"""
    features = []
    body = probe_result.get("body", "")
    status = probe_result.get("status_code")
    status_text = probe_result.get("status_text", "")

    if status is None or "504" in status_text or "pending" in status_text:
        features.append("no response")
    if status == 200:
        features.append("data")
        # JSON 特征
        try:
            data = json.loads(body)
            if isinstance(data, dict):
                if "data" in data:
                    v = data["data"]
                    if v == [] or v is None or (isinstance(v, (list, dict)) and not v):
                        features.append("empty")
                    else:
                        features.append("有数据")
                if "code" in data:
                    features.append(str(data["code"]))
                if "message" in data:
                    features.append(str(data["message"])[:50])
        except Exception:
            pass
    # 错误关键词（大小写不敏感）
    lower = body.lower()
    error_keywords = [
        "sqlexception", "sql syntax", "cannot be null", "connection refused",
        "nullpointer", "outofmemory", "redis", "timeout", "exception",
        "unauthorized", "token", "forbidden", "权限", "未登录", "接口不存在",
        "文件过大", "payload too large", "validation", "duplicate", "cors",
    ]
    for kw in error_keywords:
        if kw in lower:
            features.append(kw)

    if probe_result.get("error"):
        features.append("timeout" if "超时" in probe_result["error"] else "connect")
    return features


def normalize_status(status_text):
    """将状态文本规整为知识库中的状态码 key"""
    if status_text is None:
        return "pending"
    s = str(status_text)
    m = re.search(r"(\d{3})", s)
    if m:
        return m.group(1)
    if "pending" in s or "超时" in s or "504" in s:
        return "504"
    if "连接" in s or "拒绝" in s:
        return "502"
    return "pending"


def run_detection(url, enable_service_check=True, enable_db_check=True, timeout=DEFAULT_TIMEOUT):
    """
    完整 7 步检测流程
    返回：{steps: [...], probe: {...}, service: {...}, features: [...], normalized_status: str}
    """
    steps = []
    parsed = parse_url(url)
    if "error" in parsed:
        return {"error": parsed["error"], "steps": steps}

    steps.append({
        "step": 0,
        "title": "URL 解析",
        "action": "解析输入 URL",
        "result": "ok",
        "detail": "%s://%s:%s%s" % (parsed["scheme"], parsed["host"], parsed["port"], parsed["path"]),
    })

    # 第 0 步：服务存活检测
    service = None
    if enable_service_check:
        service = tcp_check(parsed["host"], parsed["port"])
        steps.append({
            "step": 0,
            "title": "服务存活检测（TCP）",
            "action": "Test-NetConnection %s -Port %s" % (parsed["host"], parsed["port"]),
            "result": "pass" if service["ok"] else "fail",
            "detail": service["detail"],
        })
        if not service["ok"]:
            steps.append({
                "step": 0,
                "title": "服务存活检测（TCP）",
                "action": "判断结论",
                "result": "fail",
                "detail": "TCP 连接失败 → 请求未到达后端 → 服务/网络/防火墙问题，先行排查服务进程与端口",
            })
    else:
        steps.append({
            "step": 0,
            "title": "服务存活检测（TCP）",
            "action": "Test-NetConnection %s -Port %s" % (parsed["host"], parsed["port"]),
            "result": "skip",
            "detail": "已由用户关闭该检查项",
        })

    # 第 1 步：HTTP 探测
    probe = http_probe(parsed["url"], timeout=timeout)
    steps.append({
        "step": 1,
        "title": "HTTP 请求探测",
        "action": "GET %s" % parsed["url"],
        "result": "ok" if probe["status_code"] is not None else "fail",
        "detail": "状态码：%s | 耗时：%sms | %s" % (
            probe["status_text"],
            probe["response_time_ms"] or "-",
            probe["error"] or "响应已捕获",
        ),
    })

    # 第 2 步：状态码分析
    normalized = normalize_status(probe["status_text"])
    code_hint = {
        "200": "后端正常返回 → 需分析响应数据（空数组=数据库问题）",
        "201": "资源创建成功 → 检查前端是否处理成功响应",
        "204": "成功但无内容 → 检查前端是否处理成功响应",
        "400": "参数错误 → 检查前端提交参数（F12 → Payload）",
        "401": "未授权 → 检查登录状态 / Token",
        "403": "权限不足 → 检查 RBAC 权限配置",
        "404": "接口不存在 → Postman 验证后端是否已有该接口",
        "405": "方法不允许 → 检查请求方法（GET/POST 是否用错）",
        "408": "请求超时 → 检查网络与后端超时配置",
        "409": "资源冲突 → 检查唯一约束/重复数据",
        "410": "资源已删除 → 确认资源是否下线，更新前端",
        "413": "请求体过大 → 检查文件大小与上传配置",
        "415": "媒体类型不支持 → 检查 Content-Type",
        "422": "参数校验失败 → 检查字段级校验错误",
        "429": "请求频繁 → 检查限流配置",
        "500": "服务器内部错误 → 必须查后端日志（第 5 步）",
        "501": "功能未实现 → 确认后端是否实现该方法",
        "502": "Bad Gateway → 后端服务崩溃/Nginx 配置问题",
        "503": "服务不可用 → 服务维护/过载",
        "504": "网关超时 → 慢 SQL / 网关超时配置",
        "505": "HTTP 版本不支持 → 升级浏览器",
        "pending": "无响应 → 服务/网络/防火墙问题，先做第 0 步",
    }
    steps.append({
        "step": 2,
        "title": "状态码分析",
        "action": "对照 SOP 状态码速查表",
        "result": "info",
        "detail": code_hint.get(normalized, "状态码 %s 分析" % normalized),
    })

    # 第 3 步：响应数据分析
    features = extract_features(probe)
    if probe["body"]:
        steps.append({
            "step": 3,
            "title": "响应数据分析",
            "action": "解析响应体特征",
            "result": "info",
            "detail": "响应体（前 %d 字符）：%s" % (min(len(probe["body"]), 300), probe["body"][:300]),
        })

    # 第 4 步：证据收集
    steps.append({
        "step": 4,
        "title": "证据收集",
        "action": "固定 3 样：接口记录 / 请求面板 / 响应面板",
        "result": "ok",
        "detail": "已自动捕获：URL=%s, Status=%s, 耗时=%sms, 响应体长度=%s" % (
            parsed["url"], probe["status_text"], probe["response_time_ms"],
            probe.get("body_length", len(probe["body"])),
        ),
    })

    # 第 5 步：日志/数据库分析提示（依据状态码分支）
    if normalized == "500":
        steps.append({
            "step": 5,
            "title": "后端日志分析（500 必做）",
            "action": "tail -f logs/app.log | grep -E 'Exception|Error'",
            "result": "info",
            "detail": "500 错误需查看后端日志堆栈：定位第一个 Caused by，记录类名与行号，截图给研发",
        })
    elif normalized == "200" and "empty" in features:
        steps.append({
            "step": 5,
            "title": "数据库排查（200 + 空数组必做）",
            "action": "mysql -u root -p → SELECT * FROM 表名 LIMIT 10",
            "result": "info",
            "detail": "响应 data 为空数组 → 需验证数据库：表是否存在、是否有数据",
        })
    else:
        steps.append({
            "step": 5,
            "title": "Postman 隔离验证（区分前后端）",
            "action": "复制 URL/Header/Body 到 Postman 复现",
            "result": "info",
            "detail": "Postman 正常 + 页面异常 = 100% 前端问题；Postman 也报错 = 后端/网络问题",
        })

    return {
        "steps": steps,
        "probe": probe,
        "service": service,
        "features": features,
        "normalized_status": normalized,
        "parsed": parsed,
    }
