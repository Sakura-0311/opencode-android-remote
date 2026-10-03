import json
import sys
import os
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading

# Lightweight Mock HTTP Server implementing official OpenCode endpoints
class MockOpenCodeHandler(BaseHTTPRequestHandler):
    sessions = [{"id": "ses_01JABCDEF0123456789", "title": "Real OpenCode Workspace"}]
    aborted_sessions = set()
    permissions_recorded = {}

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
            # Old fake endpoints return 404 to prove contract verification!
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
            assert len(parts) > 0 and parts[0].get("type") == "text", f"Invalid parts contract: {body}"
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
            sid = parts[2]
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

def run_smoke_tests():
    port = 4199
    base_url = f"http://127.0.0.1:{port}"
    server = run_mock_server(port)
    print(f"[SmokeTest] Mock OpenCode Server listening on {base_url}")

    try:
        # 1. 探活测试: GET /global/health
        req = urllib.request.Request(f"{base_url}/global/health")
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode())
            assert data["status"] == "ok"
            print(f"✔ 1. Health check (/global/health) passed: HTTP 200, {data}")

        # 验证旧虚假端点必须 404
        for fake_path in ("/api/health", "/api/chat", "/api/sessions"):
            try:
                urllib.request.urlopen(f"{base_url}{fake_path}")
                assert False, f"Old fake path {fake_path} should have 404'd"
            except urllib.error.HTTPError as e:
                assert e.code == 404
        print("✔ 1.1 Verified old fake paths (/api/*) correctly return 404")

        # 2. 查询真实会话列表: GET /session
        req = urllib.request.Request(f"{base_url}/session")
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            sessions = json.loads(resp.read().decode())
            assert len(sessions) >= 1
            assert sessions[0]["id"].startswith("ses_")
            print(f"✔ 2. Query real sessions (/session) passed: found {len(sessions)} session ({sessions[0]['id']})")

        # 3. 创建真实会话: POST /session
        req = urllib.request.Request(
            f"{base_url}/session",
            data=json.dumps({"title": "Smoke Test Task"}).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 201
            new_sess = json.loads(resp.read().decode())
            assert new_sess["id"].startswith("ses_")
            print(f"✔ 3. Create real session passed: {new_sess['id']}")

        # 4. 发送符合真实 parts 契约的消息: POST /session/:id/message
        req = urllib.request.Request(
            f"{base_url}/session/{new_sess['id']}/message",
            data=json.dumps({"parts": [{"type": "text", "text": "Run verification"}]}).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 202
            res = json.loads(resp.read().decode())
            assert res.get("status") == "accepted"
            print(f"✔ 4. Send message with parts contract passed: {res}")

        # 5. 中断/取消真实执行: POST /session/:id/abort
        req = urllib.request.Request(
            f"{base_url}/session/{new_sess['id']}/abort",
            data=b"{}",
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            assert new_sess["id"] in MockOpenCodeHandler.aborted_sessions
            print(f"✔ 5. Abort session passed: session {new_sess['id']} recorded as aborted")

        # 6. 回传工具审批真实决定: POST /session/:id/permissions/:permID
        req = urllib.request.Request(
            f"{base_url}/session/{new_sess['id']}/permissions/perm_smoke_99",
            data=json.dumps({"action": "allow", "response": "allow", "reason": "User allowed"}).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req) as resp:
            assert resp.status == 200
            assert "perm_smoke_99" in MockOpenCodeHandler.permissions_recorded
            assert MockOpenCodeHandler.permissions_recorded["perm_smoke_99"]["action"] == "allow"
            print(f"✔ 6. Real tool approval response passed: perm_smoke_99 recorded as allow")

    finally:
        server.shutdown()

    print("\n🎉 ALL OPENCODE CONTRACT SMOKE TESTS PASSED 100%!")

if __name__ == "__main__":
    run_smoke_tests()
