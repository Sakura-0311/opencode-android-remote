"""v5.0.2: SSE 帧解析单测 —— python3 desktop_agent/modules/test_sse_parser.py

回归背景：原实现逐行 `json.loads(line[5:])`，而 SSE 规范允许一个事件由多行
`data:` 组成（用 \n 连接后再交给上层）。多行 data 时每一行都不是合法 JSON，
于是被 `except Exception: pass` 静默吞掉 —— 整个事件丢失且没有任何日志。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# opencode_api.py 在 desktop_agent/ 顶层（不在 modules/ 里）
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from opencode_api import SSEFrameParser  # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


def feed_all(lines):
    """喂完整个行流（含结尾空行），返回解析出的事件列表。"""
    p = SSEFrameParser()
    out = []
    for ln in lines:
        ev = p.feed(ln)
        if ev is not None:
            out.append(ev)
    tail = p.flush()
    if tail is not None:
        out.append(tail)
    return out


# 1. 单行 data（opencode 的常见形态）
evs = feed_all(['data: {"type":"message.part.updated","properties":{"delta":"hi"}}', ""])
check("单行 data 解析出一个事件", len(evs) == 1)
check("单行 data 内容正确", evs[0]["type"] == "message.part.updated")

# 2. 多行 data —— 旧实现在这里会整帧丢失
evs = feed_all(['data: {"type":"x",', 'data: "n":1}', ""])
check("多行 data 合成一个事件（旧实现会丢）", len(evs) == 1)
check("多行 data 拼接后 JSON 正确", evs[0] == {"type": "x", "n": 1})

# 3. 一帧结束后再发一帧
evs = feed_all(['data: {"a":1}', "", 'data: {"a":2}', ""])
check("连续两帧各自成事件", [e["a"] for e in evs] == [1, 2])

# 4. id: / event: / 注释 / retry 行不污染数据
evs = feed_all(['id: 42', 'event: message', ': keep-alive', 'retry: 3000',
                'data: {"a":3}', ""])
check("控制行被忽略、只产出数据帧", [e["a"] for e in evs] == [3])

# 5. [DONE] 与空 data 不产出事件
evs = feed_all(['data: [DONE]', '', 'data:', ''])
check("[DONE] / 空 data 不产出事件", evs == [])

# 6. 流结束但服务端没发结尾空行 → flush 补齐
p = SSEFrameParser()
check("未闭合帧在 feed 时不产出", p.feed('data: {"b":9}') is None)
check("flush 补齐未闭合帧", p.flush() == {"b": 9})

# 7. 空行但没有 data 时不应产出（也不应抛异常）
p = SSEFrameParser()
check("空帧不产出", p.feed("") is None and p.flush() is None)

# 8. 非 JSON 数据交给调用方处理（feed 抛异常，由 subscribe 循环记 warning）
p = SSEFrameParser()
p.feed("data: not-json")
try:
    p.feed("")
    check("非法 JSON 会抛异常（而不是静默返回 None）", False)
except json.JSONDecodeError:
    check("非法 JSON 会抛异常（而不是静默返回 None）", True)

print(f"\n全部 {len(PASS)} 项通过")
