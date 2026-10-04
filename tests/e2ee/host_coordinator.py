#!/usr/bin/env python3
"""
E2EE 联调 host 协调服务（:8080）。

- GET /token  → mock desktop 的 pairing token（未就绪时返回 WAIT）
- GET /secret → 主 secret（测试用，与 mock desktop --secret 一致）
- GET /result → E2EE_OK / E2EE_FAIL:<reason> / WAIT（mock desktop 写文件）
"""
import http.server
import os

TOKEN_FILE = "/tmp/mock-pairing-token.txt"
RESULT_FILE = "/tmp/mock-e2ee-result.txt"
MASTER_SECRET = os.environ.get("E2EE_TEST_SECRET", "testsecret")


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            if self.path == "/token":
                body = self._read(TOKEN_FILE) or "WAIT"
            elif self.path == "/secret":
                body = MASTER_SECRET
            elif self.path == "/result":
                body = self._read(RESULT_FILE) or "WAIT"
            else:
                self.send_response(404)
                self.end_headers()
                return
            data = body.encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except BrokenPipeError:
            pass

    def _read(self, path):
        try:
            with open(path) as f:
                return f.read().strip()
        except FileNotFoundError:
            return ""

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    # 清理旧状态
    for f in (TOKEN_FILE, RESULT_FILE):
        try:
            os.remove(f)
        except FileNotFoundError:
            pass
    http.server.HTTPServer(("127.0.0.1", 8080), H).serve_forever()
