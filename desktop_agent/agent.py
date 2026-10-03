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
from modules.secrets import get_or_create_secret
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
    p.add_argument("--relay-url", default=None, help="Relay 地址（默认 $RELAY_SERVER_URL）")
    p.add_argument("--workspace", default=None, help="覆盖文件沙盒根目录（默认 $AGENT_FILE_ROOTS）")
    # 兼容旧位置参数：python agent.py [account_id] [relay_url]
    p.add_argument("pos_account", nargs="?")
    p.add_argument("pos_relay", nargs="?")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.account_id is None and args.pos_account:
        args.account_id = args.pos_account
    if args.relay_url is None and args.pos_relay:
        args.relay_url = args.pos_relay
    config.apply_cli_overrides(args)

    account_id = args.account_id or config.DEFAULT_ACCOUNT_ID
    relay_url = args.relay_url or config.RELAY_SERVER_URL
    secret = args.secret or get_or_create_secret()

    try:
        if args.command == "pair":
            asyncio.run(run_pairing_flow(account_id, secret, relay_url))
        else:
            asyncio.run(run_desktop_agent(account_id, secret, relay_url))
    except KeyboardInterrupt:
        print("\n[OpenCode Desktop Bridge] Terminated gracefully by user.")


if __name__ == "__main__":
    main()
