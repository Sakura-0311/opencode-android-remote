#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.0.1 安全回归测试：relay 的来源识别与浏览器 Origin 校验。

覆盖三处已确认问题：
  1. XFF 默认可伪造：此前 TRUSTED_PROXIES 默认含 127.0.0.1/::1，而默认部署
     恰好只绑回环 → 本机任意进程自带 X-Forwarded-For 即可伪造来源 IP，
     既绕过限流，也能反向用受害者 IP 连发失败把正常用户 jail（未认证 DoS）。
     现在只有显式配置 TRUSTED_PROXIES 才采信 XFF。
  2. 无 Origin 校验：任意网页可直连本机 relay。原生 App 不发 Origin，
     因此现在放行无 Origin 的连接，拒绝白名单外的浏览器来源。
  3. --trusted-proxies CLI 参数此前完全失效（只改 __main__ 的 globals()，
     而 uvicorn.run("server:app") 会再 import 一份 server 模块）。
"""
import os
import sys

os.environ.setdefault("RELAY_ADMIN_TOKEN", "test-admin-token-0123456789abcdef")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server  # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


class FakeClient:
    def __init__(self, host):
        self.host = host


class FakeWS:
    def __init__(self, host="127.0.0.1", headers=None):
        self.client = FakeClient(host)
        self.headers = headers or {}


# ---- 1. 默认不信任代理 ----
check("默认 TRUSTED_PROXIES 为空（不信任任何代理）", server.TRUSTED_PROXIES == set())
ws_spoof = FakeWS(host="127.0.0.1", headers={"x-forwarded-for": "1.2.3.4"})
check("未配置代理时忽略 XFF（本机伪造无效）", server.get_client_ip(ws_spoof) == "127.0.0.1")
ws_spoof2 = FakeWS(host="203.0.113.9", headers={"x-forwarded-for": "10.0.0.1"})
check("外部直连也忽略 XFF", server.get_client_ip(ws_spoof2) == "203.0.113.9")

# 显式配置后按最右侧可信规则取
_old = server.TRUSTED_PROXIES
server.TRUSTED_PROXIES = {"127.0.0.1"}
check("受信代理的 XFF 被采信",
      server.get_client_ip(FakeWS("127.0.0.1", {"x-forwarded-for": "8.8.8.8"})) == "8.8.8.8")
check("客户端伪造的最左段被跳过（取最右不可信地址）",
      server.get_client_ip(
          FakeWS("127.0.0.1", {"x-forwarded-for": "9.9.9.9, 8.8.8.8"})) == "8.8.8.8")
check("非受信来源的 XFF 不采信",
      server.get_client_ip(FakeWS("203.0.113.9", {"x-forwarded-for": "8.8.8.8"}))
      == "203.0.113.9")
server.TRUSTED_PROXIES = _old

# ---- 2. Origin 校验 ----
check("原生客户端（无 Origin）放行", server.origin_allowed(FakeWS()))
check("浏览器来源默认拒绝",
      not server.origin_allowed(FakeWS(headers={"origin": "https://evil.example"})))
_old_o = server.ALLOWED_ORIGINS
server.ALLOWED_ORIGINS = {"https://mine.example"}
check("白名单内的 Origin 放行",
      server.origin_allowed(FakeWS(headers={"origin": "https://mine.example"})))
check("白名单外的 Origin 拒绝",
      not server.origin_allowed(FakeWS(headers={"origin": "https://evil.example"})))
server.ALLOWED_ORIGINS = _old_o

# ---- 3. CLI 参数真的写进环境变量（uvicorn 会重新 import server 模块）----
import inspect  # noqa: E402
_src = inspect.getsource(server.__main__) if hasattr(server, "__main__") else ""
if not _src:
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.py"),
              encoding="utf-8") as f:
        _src = f.read()
check("--trusted-proxies 同步写入 os.environ",
      'os.environ["TRUSTED_PROXIES"] = _args.trusted_proxies' in _src)
check("--allowed-origins 同步写入 os.environ",
      'os.environ["RELAY_ALLOWED_ORIGINS"] = _args.allowed_origins' in _src)

print(f"\n全部 {len(PASS)} 项通过")
