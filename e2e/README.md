# E2E UI 测试（B4）

## 框架选型：Maestro（而非 Espresso）

| 维度 | Espresso | Maestro（选用） |
|---|---|---|
| 黄金路径表达 | 需写 Kotlin + IdlingResource 等待 websocket/relay，约 200+ 行 | YAML 声明式 flow，30 行；内置智能等待重试 |
| 跨屏流程 | ActivityScenario + 手动同步点 | 原生支持多屏流程 |
| 维护成本 | 文案/布局一改就得改代码 | 靠 `testTag` 锚点，与中文文案解耦 |
| CI 安装 | 无额外依赖 | 多一步装 Maestro CLI（约 1 分钟） |
| 适用边界 | 组件级交互断言仍用 Espresso/Robolectric | E2E 级跨屏流程用 Maestro |

结论：**E2E 黄金路径用 Maestro；组件级测试继续用现有单测体系。**
Espresso 不适合这条路径：配对→连接→发消息→收回复涉及 websocket 异步、
relay 外部进程、fake desktop 回声，Espresso 的同步机制写起来是 5–10 倍代码量，
且 fragile。

## 架构

```
┌─────────────┐     ws     ┌──────────────┐     ws     ┌──────────────┐
│  Maestro CLI │──adb──────▶│ 模拟器 (API29)│            │ 宿主机        │
│  golden-path │   驱动UI   │ 真机 APK      │──reverse──▶│ relay:8765    │
│  .yaml       │            │ 配对→连接→发消息│   tcp:8765 │ fake_desktop  │
└─────────────┘            └──────────────┘            │ 回声机器人     │
                                                      └──────────────┘
```

- `fake_desktop.py`：最小 v4 协议实现（hello/auth/建房 + send_prompt 回声
  stream_start/chunk/end）。E2EE 默认关闭，走明文。
- `maestro/golden-path.yaml`：配对（填房间/密钥/relay 地址）→ 连接 →
  发「你好 E2E」→ 断言看到「E2E 回声：你好 E2E」。
- UI 锚点（Compose `testTag`，与文案解耦）：
  `pair_account` / `pair_secret` / `pair_relay_url` / `pair_connect` /
  `chat_input` / `chat_send`

## 运行

CI：`.github/workflows/e2e.yml`，每周日 12:00 CST + 手动触发。

本地（需 Android SDK + 模拟器已启动）：
```bash
# 1. 起 relay
cd relay_server && python3 server.py --port 8765 &
# 2. 起 fake desktop（建房）
E2E_RELAY_URL=ws://127.0.0.1:8765 python3 e2e/fake_desktop.py &
# 3. 模拟器里 adb reverse
adb reverse tcp:8765 tcp:8765
# 4. 装 APK 后跑 flow
maestro test e2e/maestro/golden-path.yaml
```
