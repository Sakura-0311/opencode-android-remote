#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.0.3 (B-7) 回归测试：反代部署下的封禁范围与反代提示。

覆盖两处已确认问题：
  1. 封禁键此前只有 IP。未配置 TRUSTED_PROXIES 时 get_client_ip 只能取到反代
     出口地址，于是「同一 IP 连续 5 次认证失败封 15 分钟」等价于
     「任何人输错 5 次，整个 relay 对所有用户封 15 分钟」。现在账号已知时
     按 (ip, account) 记账，攻击者只能封自己那个账号；账号未知的失败
     （握手超时等）仍按 IP 计，且该 IP 键仍参与 is_jailed 检查，不能被绕过。
  2. _check_proxy_hint 的判据此前是 TRUSTED_PROXIES == {"127.0.0.1","::1"}，
     而 v5.0.1 起默认值为空集，该条件永远为假——恰好在最需要提示的反代
     部署下不再触发。现在判据是「未配置」。
"""
import logging
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


PROXY_IP = "10.0.0.9"  # 反代出口：所有客户端共享

# ---- 1. 封禁键形状 ----
check("账号已知时键为 ip|account",
      server.jail_key(PROXY_IP, "alice") == f"{PROXY_IP}|alice")
check("账号未知时键退回 ip", server.jail_key(PROXY_IP, "") == PROXY_IP)
check("账号前后空白视为同一账号",
      server.jail_key(PROXY_IP, "  alice ") == server.jail_key(PROXY_IP, "alice"))

# ---- 2. 一个账号失败不再牵连同 IP 的其他账号 ----
rl = server.RateLimiter(max_auth_fails=5, jail_seconds=900)
for _ in range(5):
    rl.record_auth_failure(PROXY_IP, "alice")
check("连续失败后该账号被封", rl.is_jailed(PROXY_IP, "alice"))
check("同 IP 的其他账号不受牵连", not rl.is_jailed(PROXY_IP, "bob"))
check("IP 级未被封（不会被 check_connection_allowed 拒绝）",
      rl.is_jailed(PROXY_IP) is False)
rl.record_auth_success(PROXY_IP, "alice")
check("认证成功后清零该账号失败计数",
      rl.ip_auth_fails.get(server.jail_key(PROXY_IP, "alice"), 0) == 0)
check("封禁是时间制，不会被一次成功认证提前解除",
      rl.is_jailed(PROXY_IP, "alice"))
rl.record_auth_failure(PROXY_IP, "alice")
check("成功后重新计数从 1 开始",
      rl.ip_auth_fails[server.jail_key(PROXY_IP, "alice")] == 1)

# 账号已知但 IP 级另有封禁时，is_jailed 仍应为 True（否则可绕过）
rl2 = server.RateLimiter(max_auth_fails=2, jail_seconds=900)
for _ in range(2):
    rl2.record_auth_failure(PROXY_IP)  # 账号未知
check("账号未知的失败按 IP 封禁", rl2.is_jailed(PROXY_IP))
check("IP 级封禁对任意账号都生效（不被绕过）",
      rl2.is_jailed(PROXY_IP, "carol"))

# ---- 3. 反代提示：未配置 TRUSTED_PROXIES 时必须触发 ----
class _Capture(logging.Handler):
    def __init__(self):
        super().__init__()
        self.messages = []

    def emit(self, record):
        self.messages.append(record.getMessage())


cap = _Capture()
server.logger.addHandler(cap)
_saved_proxies = server.TRUSTED_PROXIES
_saved_seen = dict(server.manager.seen_ips)
try:
    server.manager.seen_ips.clear()
    server.manager.seen_ips[PROXY_IP] = __import__("time").time()

    server.TRUSTED_PROXIES = set()
    server._check_proxy_hint()
    check("TRUSTED_PROXIES 未配置时触发反代提示",
          any("TRUSTED_PROXIES" in m for m in cap.messages))

    cap.messages.clear()
    server.TRUSTED_PROXIES = {"10.0.0.1"}
    server._check_proxy_hint()
    check("已配置 TRUSTED_PROXIES 时不再提示",
          not any("TRUSTED_PROXIES" in m for m in cap.messages))

    cap.messages.clear()
    server.TRUSTED_PROXIES = {"127.0.0.1", "::1"}  # v5.0.1 前的默认值
    server._check_proxy_hint()
    check("旧默认值（127.0.0.1/::1）也算已配置，不再提示",
          not any("TRUSTED_PROXIES" in m for m in cap.messages))
finally:
    server.logger.removeHandler(cap)
    server.TRUSTED_PROXIES = _saved_proxies
    server.manager.seen_ips.clear()
    server.manager.seen_ips.update(_saved_seen)

print(f"\n全部 {len(PASS)} 项通过")