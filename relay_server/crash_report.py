"""
B2: 崩溃上报接收端（从 relay_server/server.py 抽出）。

只收 Android 端 ACRA 上报的脱敏字段，存本地文件，不转发、不外传。
v2.2.1-E 加固：默认关闭（RELAY_ENABLE_CRASH_REPORT=1 才开）、请求体上限、
按 IP 限流、文件名清洗、目录配额。

本模块顶层只依赖标准库；FastAPI 相关在 register_routes 内延迟导入，
保证纯逻辑可零依赖单测。
"""
import json
import logging
import os
import time
from typing import Dict, Tuple, Optional

logger = logging.getLogger("OpenCodeRelay")

CRASH_REPORT_DIR = os.getenv("RELAY_CRASH_REPORT_DIR", "crash_reports")
CRASH_REPORT_ENABLED = os.getenv("RELAY_ENABLE_CRASH_REPORT", "0") == "1"
CRASH_REPORT_MAX_BYTES = int(os.getenv("RELAY_CRASH_REPORT_MAX_BYTES", str(128 * 1024)))
CRASH_REPORT_MAX_FILES = int(os.getenv("RELAY_CRASH_REPORT_MAX_FILES", "500"))
CRASH_REPORT_MAX_TOTAL_BYTES = int(os.getenv("RELAY_CRASH_REPORT_MAX_TOTAL_BYTES", str(100 * 1024 * 1024)))
CRASH_REPORT_MAX_PER_HOUR = int(os.getenv("RELAY_CRASH_REPORT_MAX_PER_HOUR", "20"))

# 脱敏白名单：只保留这些字段
ALLOWED_FIELDS = {
    "REPORT_ID", "APP_VERSION_CODE", "APP_VERSION_NAME", "PACKAGE_NAME",
    "ANDROID_VERSION", "PHONE_MODEL", "BRAND",
    "STACK_TRACE", "USER_APP_START_DATE", "USER_CRASH_DATE",
}

_crash_report_hits: Dict[str, list] = {}  # ip -> [timestamps]


def crash_report_allowed(ip: str) -> bool:
    """按 IP 限流：每小时最多 CRASH_REPORT_MAX_PER_HOUR 次。"""
    now = time.time()
    hits = _crash_report_hits.get(ip, [])
    hits = [t for t in hits if now - t < 3600]
    _crash_report_hits[ip] = hits
    if len(hits) >= CRASH_REPORT_MAX_PER_HOUR:
        return False
    hits.append(now)
    return True


def sanitize_report_id(rid: str) -> str:
    """文件名只保留 [A-Za-z0-9-]，防路径穿越。"""
    cleaned = "".join(c for c in str(rid) if c.isascii() and (c.isalnum() or c == "-"))
    return cleaned[:32] or "unknown"


def enforce_crash_dir_quota(crash_dir: str = CRASH_REPORT_DIR) -> None:
    """目录文件数/总大小上限，超了删最旧的。"""
    try:
        files = []
        for name in os.listdir(crash_dir):
            fp = os.path.join(crash_dir, name)
            if os.path.isfile(fp) and name.startswith("crash-") and name.endswith(".json"):
                st = os.stat(fp)
                files.append((st.st_mtime, st.st_size, fp))
        files.sort()
        total = sum(s for _, s, _ in files)
        while (len(files) > CRASH_REPORT_MAX_FILES or total > CRASH_REPORT_MAX_TOTAL_BYTES) and files:
            _, s, fp = files.pop(0)
            try:
                os.remove(fp)
                total -= s
                logger.info(f"[v2.2.1-E] crash report quota: removed old {fp}")
            except OSError:
                pass
    except FileNotFoundError:
        pass
    except Exception as e:
        logger.warning(f"[v2.2.1-E] quota enforce failed: {e}")


def process_crash_report(data: dict, crash_dir: str = CRASH_REPORT_DIR
                         ) -> Tuple[bool, str, Optional[str]]:
    """
    纯逻辑：校验 + 脱敏 + 落盘。
    返回 (ok, error_code, saved_path)。error_code 为 "" 表示成功。
    """
    if not isinstance(data, dict):
        return False, "invalid payload", None
    report = {k: data.get(k) for k in ALLOWED_FIELDS if k in data}
    if isinstance(report.get("STACK_TRACE"), str) and len(report["STACK_TRACE"]) > 65536:
        report["STACK_TRACE"] = report["STACK_TRACE"][:65536] + "\n...[truncated]"
    try:
        os.makedirs(crash_dir, exist_ok=True)
        ts = time.strftime("%Y%m%d-%H%M%S")
        rid = sanitize_report_id(report.get("REPORT_ID", "unknown"))
        fname = os.path.join(crash_dir, f"crash-{ts}-{rid}.json")
        with open(fname, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        enforce_crash_dir_quota(crash_dir)
        logger.warning(f"Crash report saved: {fname} "
                       f"(app {report.get('APP_VERSION_NAME')}, "
                       f"{report.get('PHONE_MODEL')}, Android {report.get('ANDROID_VERSION')})")
        return True, "", fname
    except Exception as e:
        logger.error(f"Failed to save crash report: {e}")
        return False, "save failed", None


def register_routes(app, get_client_ip) -> None:
    """把 POST /api/crash-report 挂到 FastAPI app 上。"""
    from fastapi import Request
    from fastapi.responses import JSONResponse

    @app.post("/api/crash-report")
    async def receive_crash_report(request: Request):
        """
        接收 Android 端 ACRA 上报的崩溃报告（JSON）。
        只保留脱敏字段，存入本地文件，不转发、不外传。
        """
        if not CRASH_REPORT_ENABLED:
            return JSONResponse(status_code=404, content={"ok": False, "error": "disabled"})
        client_ip = get_client_ip(request)
        if not crash_report_allowed(client_ip):
            return JSONResponse(status_code=429, content={"ok": False, "error": "rate limited"})
        try:
            clen = int(request.headers.get("content-length", "0") or 0)
        except ValueError:
            clen = 0
        if clen > CRASH_REPORT_MAX_BYTES:
            return JSONResponse(status_code=413, content={"ok": False, "error": "payload too large"})
        try:
            body = b""
            async for chunk in request.stream():
                body += chunk
                if len(body) > CRASH_REPORT_MAX_BYTES:
                    return JSONResponse(status_code=413, content={"ok": False, "error": "payload too large"})
            data = json.loads(body.decode("utf-8"))
        except Exception:
            return JSONResponse(status_code=400, content={"ok": False, "error": "invalid json"})
        ok, err, _ = process_crash_report(data)
        if not ok:
            code = 400 if err in ("invalid payload",) else 500
            return JSONResponse(status_code=code, content={"ok": False, "error": err})
        return {"ok": True}
