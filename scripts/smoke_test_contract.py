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
            # N-4: mock 返回官方形状 {"healthy": true, "version": ...}，与真实一致
            self._json(200, {"healthy": True, "version": "1.0.4"})
        elif self.path == "/session":
            self._json(200, self.sessions)
        elif self.path == "/agent":
            # N-4: Agent 列表 mock（官方契约：数组）
            self._json(200, [{"name": "build", "mode": "primary"},
                             {"name": "plan", "mode": "primary"}])
        elif self.path == "/config/providers":
            # N-4: Provider 列表 mock（官方契约：{providers: [...], default: {...}}）
            self._json(200, {"providers": [{"id": "anthropic",
                                            "models": {"claude-x": {}}}],
                             "default": {"anthropic": "claude-x"}})
        elif self.path == "/project":
            # N-4: 项目列表 mock（官方契约：Project[]）
            self._json(200, [{"id": "p1", "worktree": "/home/u/proj", "vcs": "git"}])
        elif self.path == "/project/current":
            # N-4: 当前项目 mock
            self._json(200, {"id": "p1", "worktree": "/home/u/proj", "vcs": "git"})
        elif self.path == "/vcs":
            # N-4: VCS 信息 mock
            self._json(200, {"type": "git", "root": "/home/u/proj"})
        elif self.path in ("/api/sessions", "/api/chat", "/health"):
            self._json(404, {"detail": "Not Found"})
        else:
            self.send_response(404)
            self.end_headers()

    def _json(self, code, obj):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode("utf-8"))

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
        elif self.path.startswith("/session/") and self.path.endswith("/prompt_async"):
            # N-5: 异步发消息接口 mock——立即返回 204，输出走 /event
            sid = self.path.split("/")[2]
            parts = body.get("parts", [])
            # 真实契约断言：必须按 parts: [{type:'text', text:...}] 发送
            assert len(parts) > 0 and parts[0].get("type") == "text", f"Invalid parts contract: {body}"
            self.messages_received.append({"session_id": sid, "body": body})
            self.send_response(204)
            self.end_headers()
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

            # 4. 真实 send_session_message_async（N-5: 已改用 prompt_async，204 立即返回）
            res = await opencode_api.send_session_message_async(
                http_session, new_sess["id"], "Run verification", base_url, None)
            assert res.get("status") == "accepted"
            sent = MockOpenCodeHandler.messages_received[-1]
            assert sent["body"]["parts"][0] == {"type": "text", "text": "Run verification"}
            print(f"✔ 4. send_session_message_async via real code passed (prompt_async 204), parts contract verified")

            # 5. 真实 abort_session
            assert await opencode_api.abort_session(http_session, new_sess["id"], base_url, None)
            assert new_sess["id"] in MockOpenCodeHandler.aborted_sessions
            print(f"✔ 5. abort_session via real code passed")

            # 6. 真实 respond_to_permission（N-2: 官方契约 body 只能是 {"response": "once"/"reject"}）
            assert await opencode_api.respond_to_permission(
                http_session, new_sess["id"], "perm_smoke_99", True, "User allowed", base_url, None)
            recorded = MockOpenCodeHandler.permissions_recorded.get("perm_smoke_99", {})
            assert recorded == {"response": "once"}, f"N-2 contract violated: {recorded}"
            print(f"✔ 6. respond_to_permission via real code passed (body={recorded})")

            # 7. N-4: 5 个新端点的真实函数断言
            agents, agents_err = await opencode_api.get_agents(http_session, base_url, None)
            assert agents_err is None, f"get_agents error: {agents_err}"
            assert len(agents) > 0 and "build" in str(agents), "get_agents 解析失败"
            print(f"✔ 7. get_agents via real code passed: {[a.get('name') for a in agents]}")

            providers, providers_err = await opencode_api.get_providers(http_session, base_url, None)
            assert providers_err is None, f"get_providers error: {providers_err}"
            assert "providers" in providers, f"get_providers 结构不符: {providers}"
            print(f"✔ 8. get_providers via real code passed")

            projects, projects_err = await opencode_api.get_projects(http_session, base_url, None)
            assert projects_err is None, f"get_projects error: {projects_err}"
            assert len(projects) > 0, "get_projects 返回空"
            print(f"✔ 9. get_projects via real code passed: {[p.get('id') for p in projects]}")

            current, current_err = await opencode_api.get_current_project(http_session, base_url, None)
            assert current_err is None, f"get_current_project error: {current_err}"
            assert current.get("id") == "p1", f"get_current_project 结构不符: {current}"
            print(f"✔ 10. get_current_project via real code passed")

            vcs, vcs_err = await opencode_api.get_vcs_info(http_session, base_url, None)
            assert vcs_err is None, f"get_vcs_info error: {vcs_err}"
            assert vcs.get("type") == "git", f"get_vcs_info 结构不符: {vcs}"
            print(f"✔ 11. get_vcs_info via real code passed")

            # 12. N-6: 非 200 时函数必须返回 error 而非静默空值
            bad_agents, bad_err = await opencode_api.get_agents(
                http_session, base_url + "/nonexistent", None)
            assert bad_agents == [] and bad_err is not None, "N-6: 404 时应返回 error"
            print(f"✔ 12. N-6 error visibility passed (error={bad_err})")
    finally:
        server.shutdown()

    print("\n🎉 ALL OPENCODE CONTRACT SMOKE TESTS PASSED 100% (real project code exercised)")


if __name__ == "__main__":
    asyncio.run(run_smoke_tests())
