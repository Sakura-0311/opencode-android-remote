"""
OpenCode Desktop Bridge Agent —— v2.6 模块化入口（兼容垫片）。

实现已按模块拆分到 modules/：
    config    环境变量 / 常量 / CLI 覆盖
    secrets   设备密钥生成、迁移与配对横幅
    state     任务跟踪、审批、会话注册表、SSE 游标与事件流
    fileops   文件沙盒（路径白名单 + 敏感文件黑名单）
    protocol  手机消息处理、主运行循环、扫码配对流程

本文件保留 `python agent.py [pair]` 调用方式与 `import desktop_agent.agent`
的兼容（冒烟测试依赖 agent_mod.tool_guard）。
"""
import argparse
import asyncio

from modules import config
from modules.keystore import get_or_create_secret
from modules.protocol import run_desktop_agent, run_pairing_flow
# 兼容：冒烟测试与外部引用经由 agent 模块访问 tool_guard / ToolApprovalManager
from modules.state import tool_guard, ToolApprovalManager  # noqa: F401


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="OpenCode Desktop Bridge Agent")
    p.add_argument("command", nargs="?", default="run", choices=["run", "pair"],
                   help="run=启动中继桥（默认），pair=一键扫码配对")
    # v2.6: CLI 参数，默认从环境变量读取（优先级：CLI > 环境变量 > 内置默认）
    p.add_argument("--account-id", default=None, help="房间/账号 ID（默认 $OPENCODE_ACCOUNT_ID）")
    p.add_argument("--secret", default=None, help="设备密钥（默认 $OPENCODE_SECRET_FILE 或自动生成）")
    # v4.7.0/P1-7: --secret 会暴露在进程列表里，推荐用文件
    p.add_argument("--secret-file", default=None, help="从文件读取设备密钥（推荐，避免进进程列表）")
    p.add_argument("--show-secret", action="store_true", help="打印当前 secret 明文后退出（pair 时用）")
    p.add_argument("--relay-url", default=None, help="Relay 地址（默认 $RELAY_SERVER_URL）")
    p.add_argument("--workspace", default=None, help="覆盖文件沙盒根目录（默认 $AGENT_FILE_ROOTS）")
    # v4.7.0/P1-6: 建房管理令牌（默认 $RELAY_ADMIN_TOKEN）
    p.add_argument("--admin-token", default=None, help="建房管理令牌（默认 $RELAY_ADMIN_TOKEN）")
    # 兼容旧位置参数：python agent.py [account_id] [relay_url]
    p.add_argument("pos_account", nargs="?")
    p.add_argument("pos_relay", nargs="?")
    return p


def main(argv=None):
    # C1: Windows 控制台默认非 UTF-8（如 GBK/CP1252），中文 help 与日志输出会
    # UnicodeEncodeError 直接崩溃（CI windows-latest 实测）。强制 stdout/stderr
    # 用 UTF-8（失败则忽略，保持原样）。
    try:
        import sys as _sys
        if _sys.platform == "win32":
            _sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            _sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = build_parser().parse_args(argv)
    if args.account_id is None and args.pos_account:
        args.account_id = args.pos_account
    if args.relay_url is None and args.pos_relay:
        args.relay_url = args.pos_relay
    config.apply_cli_overrides(args)

    account_id = args.account_id or config.DEFAULT_ACCOUNT_ID
    relay_url = args.relay_url or config.RELAY_SERVER_URL
    # v4.7.0/P1-7: --secret-file 优先于 --secret（避免密钥进进程列表）
    if args.secret_file:
        try:
            with open(args.secret_file, "r", encoding="utf-8") as f:
                secret = f.read().strip()
        except Exception as e:
            print(f"[错误] 无法从 {args.secret_file} 读取 secret: {e}")
            raise SystemExit(1)
    else:
        secret = args.secret or get_or_create_secret()
        if args.secret:
            print("[警告] --secret 会把密钥暴露在进程列表里，建议改用 --secret-file")

    if args.show_secret:
        print(secret)
        return

    # v5.0.1: E2EE 开启时在启动阶段就校验加密库可用（fail-fast）。
    # 此前缺失 cryptography 只打一条 warning 就继续跑明文，
    # 用户会以为自己在加密。现在直接拒绝启动。
    from modules.state import _e2ee as _load_e2ee
    _mod = _load_e2ee()
    if _mod is not None:
        print("[E2EE] 已启用：X25519 + ChaCha20-Poly1305（relay 仅盲转发）")

    try:
        if args.command == "pair":
            asyncio.run(run_pairing_flow(account_id, secret, relay_url))
        else:
            asyncio.run(run_desktop_agent(account_id, secret, relay_url))
    except KeyboardInterrupt:
        print("\n[OpenCode Desktop Bridge] Terminated gracefully by user.")


if __name__ == "__main__":
    main()
