"""
OpenCode 真实契约冒烟测试 (B-4 重写版)

B-4 修复：旧版只用 urllib 手写请求打 mock，项目里 0 行代码被验证，
导致 agent.py 缺 import time 这种必崩缺陷能溜进 CI。
本版直接 import 真实项目代码并用其发请求，mock 仅作为服务端替身。
"""
import asyncio
import json
import sys
import os
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading

# 把仓库根目录和 desktop_agent 目录加入 sys.path，
# 使得 `import desktop_agent.agent` 可用（agent.py 内部用 from opencode_api import ...）
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESKTOP_AGENT_DIR = os.path.join(REPO_ROOT, "desktop_agent")
for p in (REPO_ROOT, DESKTOP_AGENT_DIR):
    if p not in sys.path:
        sys.path.insert(0, p)

# B-4 核心断言：能 import agent 本身（B-1 的缺 import time 会在这里直接炸）
import desktop_agent.agent as agent_mod
from desktop_agent import opencode_api
import aiohttp


class MockOpenCodeHandler(BaseHTTPRequestHandler):
    sessions = [{"id": "ses_01JABCDEF0123456789", "title": "Real OpenCode Workspace"}]
    aborted_sessions = set()
    permissions_recorded = {}
    messages_received = []

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        if self.path == "/global/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "version": "1.0.4"}).encode("utf-8"))
        elif self.path == "/session":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(self.sessions).encode("utf-8"))
        elif self.path in ("/api/sessions", "/api/chat", "/health"):
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"detail": "Not Found"}).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body_raw = self.rfile.read(length) if length > 0 else b"{}"
        body = json.loads(body_raw.decode("utf-8")) if body_raw else {}

        if self.path == "/session":
            title = body.get("title", "New Session")
            new_id = f"ses_01J{len(self.sessions) + 1:04d}XYZ"
            new_item = {"id": new_id, "title": title}
            self.sessions.append(new_item)
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(new_item).encode("utf-8"))
        elif self.path.startswith("/session/") and self.path.endswith("/message"):
            sid = self.path.split("/")[2]
            parts = body.get("parts", [])
            # 真实契约断言：必须按 parts: [{type:'text', text:...}] 发送
            assert len(parts) > 0 and parts[0].get("type") == "text", f"Invalid parts contract: {body}"
            self.messages_received.append({"session_id": sid, "body": body})
            self.send_response(202)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "accepted", "session_id": sid}).encode("utf-8"))
        elif self.path.startswith("/session/") and self.path.endswith("/abort"):
            sid = self.path.split("/")[2]
            self.aborted_sessions.add(sid)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"aborted": True}).encode("utf-8"))
        elif "/permissions/" in self.path:
            parts = self.path.split("/")
            pid = parts[4]
            self.permissions_recorded[pid] = body
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()


def run_mock_server(port=4199):
    server = HTTPServer(('127.0.0.1', port), MockOpenCodeHandler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    return server


async def run_smoke_tests():
    port = 4199
    base_url = f"http://127.0.0.1:{port}"
    server = run_mock_server(port)
    print(f"[SmokeTest] Mock OpenCode Server listening on {base_url}")
    print(f"[SmokeTest] Project code imported from: {agent_mod.__file__}")

    try:
        async with aiohttp.ClientSession() as http_session:
            # 0. B-4 冒烟：agent 模块可 import（B-1 的 NameError 在此被拦）
            assert hasattr(agent_mod, "tool_guard"), "tool_guard missing"
            assert hasattr(agent_mod, "ToolApprovalManager"), "ToolApprovalManager missing"
            # 顺手验证 B-1 修复：create_approval 内部 time.time() 可调用
            rec = agent_mod.tool_guard.create_approval("ses_smoke", "perm_smoke_0", "edit", {})
            assert rec["expires_at"] > 0
            print("✔ 0. import desktop_agent.agent passed (B-1 guard: time.time() callable)")

            # 1. 真实 check_opencode_health 打 mock
            ok, version, err = await opencode_api.check_opencode_health(http_session, base_url, None)
            assert ok, f"health check failed: {err}"
            print(f"✔ 1. check_opencode_health via real code passed: version={version}")

            # 2. 真实 query_sessions
            sessions = await opencode_api.query_sessions(http_session, base_url, None)
            assert len(sessions) >= 1 and sessions[0]["id"].startswith("ses_")
            print(f"✔ 2. query_sessions via real code passed: {sessions[0]['id']}")

            # 3. 真实 create_session
            new_sess = await opencode_api.create_session(http_session, "Smoke Test Task", base_url, None)
            assert new_sess["id"].startswith("ses_")
            print(f"✔ 3. create_session via real code passed: {new_sess['id']}")

            # 4. 真实 send_session_message_async，mock 断言 parts 契约
            res = await opencode_api.send_session_message_async(
                http_session, new_sess["id"], "Run verification", base_url, None)
            assert res.get("status") == "accepted"
            sent = MockOpenCodeHandler.messages_received[-1]
            assert sent["body"]["parts"][0] == {"type": "text", "text": "Run verification"}
            print(f"✔ 4. send_session_message_async via real code passed, parts contract verified")

            # 5. 真实 abort_session
            assert await opencode_api.abort_session(http_session, new_sess["id"], base_url, None)
            assert new_sess["id"] in MockOpenCodeHandler.aborted_sessions
            print(f"✔ 5. abort_session via real code passed")

            # 6. 真实 respond_to_permission
            assert await opencode_api.respond_to_permission(
                http_session, new_sess["id"], "perm_smoke_99", True, "User allowed", base_url, None)
            recorded = MockOpenCodeHandler.permissions_recorded.get("perm_smoke_99", {})
            assert recorded.get("action") == "allow" and recorded.get("response") == "allow"
            print(f"✔ 6. respond_to_permission via real code passed")
    finally:
        server.shutdown()

    print("\n🎉 ALL OPENCODE CONTRACT SMOKE TESTS PASSED 100% (real project code exercised)")


if __name__ == "__main__":
    asyncio.run(run_smoke_tests())
