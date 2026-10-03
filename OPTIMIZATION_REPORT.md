# OpenCode Remote 架构优化与缺陷修复报告

根据优化需求文档，本项目已按 **P0 → P1 → P2** 顺序逐项完成修复与加固。以下为详细的文件改动、关键逻辑与手动验收步骤。

---

## 一、改动文件总览

| 文件路径 | 涉及优化项 | 改动核心要点 |
| :--- | :--- | :--- |
| `relay_server/server.py` | P0-1, P0-3, P1-7 | 强制 10s 握手 Secret 鉴权；内存滑动窗口 IP 限流防爆破；25s 应用层心跳与僵尸连接清理；支持 WSS 证书启动 |
| `desktop_agent/agent.py` | P0-1, P1-4, P1-6, P1-7, P2-8, P2-9 | 密钥持久化 (0600)；移除假模拟器，显式返回 `OPENCODE_UNREACHABLE`；基于 `asyncio.Task` 的真实任务中断；心跳回复；指数退避加抖动；终端 qrcode 打印 |
| `desktop_agent/opencode_api.py` | P1-5 | **[新建]** 封装 OpenCode REST/SSE 调用，API 路径/字段可配置化，启动自检心跳与版本探测 |
| `desktop_agent/requirements.txt` | P2-9 | 增加 `qrcode>=7.4.2` |
| `android_app/.../model/MessageModels.kt` | P0-1, P1-4, P2-10 | 数据模型增加 `secret`、`AppError` 结构、`isError` 状态与重连状态字段 |
| `android_app/.../local/PreferencesManager.kt`| P2-10 | **[新建]** 基于 `SharedPreferences` 实现 Account ID、Secret、Relay URL 的本地免输持久化 |
| `android_app/.../network/RelayWebSocketClient.kt`| P0-1, P1-4, P1-7, P2-10 | 首包发送 auth 帧；处理 auth_error 并停连；应用层 ping/pong 响应；指数退避自动重连 (3s~60s)；分发 AppError |
| `android_app/.../viewmodel/OpenCodeViewModel.kt` | P0-1, P1-4, P2-10 | 持久化自动回填；流式追加超长保护（限制 2000 行防卡顿）；消息队列上限 500 条；错误状态管理 |
| `android_app/.../ui/screens/PairingScreen.kt` | P0-1, P2-10 | 增加 Secret 密码输入框（支持明暗文切换）；顶部展示标准错误警告；配对指引更新 |
| `android_app/.../ui/screens/ChatScreen.kt` | P1-4, P2-10 | 顶部红色错误横幅（Error Banner）；系统错误气泡样式区分；连接/重连状态栏动态提示 |
| `android_app/.../MainActivity.kt` | P0-1, P1-4 | 接入 Secret 与 AppError 的双向状态联动及 Dismiss 逻辑 |
| `README.md` | P0-2, 交付要求 4 | 彻底移除“端到端加密”虚假宣传；补齐安全须知（Secret保管、自建relay必要性）；补充 WSS 生产部署与 Nginx 示例 |
| `docs/index.html` | P0-2 | 同步修正落地页文案，移除 E2EE，更新为真实的 WSS 传输层加密说明 |

---

## 二、各项优化详细说明与手动验收指南

### P0 · 安全加固

#### 1. 配对码只是房间名，等于没有认证 (P0-1)
* **改动文件**：`relay_server/server.py`, `desktop_agent/agent.py`, Android 端各模块
* **关键逻辑**：
  * Desktop 启动时在本地以 `0600` 权限生成/读取 32 字节高熵随机十六进制密钥 `secret`，内存保留，绝不打日志。
  * 客户端建立 WebSocket 连接后，必须在 10 秒超时内发送 `{"type":"auth", "account_id":..., "secret":..., "client_type":...}`。
  * Desktop 首次注册时绑定该房间密钥哈希；后续重连必须校验一致，**若密钥不符绝不替换已有正常连接**并直接 `close(code=4401)`。
  * Mobile 连入时同样必须核对 secret_hash，不一致直接断开并计入失败 IP。
* **验收步骤**：
  1. 启动 server: `python server.py`
  2. 启动 desktop: `python agent.py room1`（假设打印 Secret 为 `sec_abc123`）
  3. 用测试脚本/工具模拟攻击者连接并使用错误 Secret：
     ```bash
     python3 -c '
     import asyncio, websockets, json
     async def test():
         async with websockets.connect("ws://127.0.0.1:8765/ws/room1/desktop") as ws:
             await ws.send(json.dumps({"type":"auth","account_id":"room1","secret":"wrong_secret","client_type":"desktop"}))
             resp = await ws.recv()
             print("收到响应:", resp)
     asyncio.run(test())
     '
     ```
  4. **预期结果**：收到 `auth_error` 且连接被断开 (4401)；原来的 desktop_agent 不受任何影响，未被顶替。

#### 2. README 声称“端到端加密”，代码里根本没有 (P0-2)
* **改动文件**：`README.md`, `docs/index.html`
* **关键逻辑**：全面核查并删除“端到端加密”、“E2EE”字样，明确阐述本系统为“基于 TLS / WSS 的传输层加密”，中继服务器在内存中可见转发明文，强调“仅信任自部署 relay 的用户使用”。提供 uvicorn `--ssl-certfile/--ssl-keyfile` 与 Nginx 真实反代配置。
* **验收步骤**：执行 `grep -in "e2ee\|端到端加密" README.md docs/index.html`，确认结果输出为空。

#### 3. 给 relay 加最基本的防爆破 (P0-3)
* **改动文件**：`relay_server/server.py`
* **关键逻辑**：实现内存滑动窗口 `RateLimiter` 类。单 IP 每分钟最多 30 次连接；单 IP 累计 5 次认证失败直接封禁（jail）15 分钟，封禁期内立即返回 `close(code=4429)`。
* **验收步骤**：
  1. 编写循环连续 5 次发送错误 auth 认证请求。
  2. 第 6 次连接尝试发起时，服务端直接拒绝并返回 4429 状态码；控制台打印 IP 被封禁日志。

---

### P1 · 正确性加固

#### 4. agent.py 移除模拟器假回复，改为显式报错 (P1-4)
* **改动文件**：`desktop_agent/agent.py`, Android 端各模块
* **关键逻辑**：彻底剔除假的 `Target files scanned successfully` 假数据代码。当检测到本地 OpenCode 未启动时，直接返回：
  ```json
  {"type":"error", "code":"OPENCODE_UNREACHABLE", "message":"本地 opencode serve 未运行，请先执行 opencode serve --port 4096"}
  ```
  App 端拦截该协议后，在顶部触发醒目的红色警示横幅，并在聊天中标记为系统错误气泡。
* **验收步骤**：
  1. 电脑端不启动 OpenCode，直接启动 `agent.py` 与 `server.py`。
  2. 手机端连接成功后发送任意 prompt。
  3. **预期结果**：手机端顶部立即弹出红色错误横幅 `[OPENCODE_UNREACHABLE] 本地 opencode serve 未运行...`，且不再产生假完成输出。

#### 5. 核对 opencode serve 的真实 API 并支持配置 (P1-5)
* **改动文件**：`desktop_agent/opencode_api.py`, `desktop_agent/agent.py`
* **关键逻辑**：将 `/health`、`/sessions`、`/sessions/{session_id}/message` 等接口路径、请求体字段键名（`text`/`prompt`）全部抽离为常量与环境变量支持，并标注版本比对注释。`agent.py` 启动时主动发起一次健康检查并输出自检日志。
* **验收步骤**：
  1. 启动 `python agent.py`。
  2. 观察控制台日志：未起服务时提示自检警告；起服务后打印 `✔ OpenCode API 自检通过：检测到服务就绪`。

#### 6. cancel 真实中断执行 (P1-6)
* **改动文件**：`desktop_agent/agent.py`
* **关键逻辑**：引入 `TaskManager`，将每一个 prompt 请求封装为独立的 `asyncio.Task`。当收到手机发来的 `action: "cancel"` 时，执行 `task.cancel()`，中断 aiohttp 的异步连接与 SSE 读取流，向客户端确认 `cancelled`。
* **验收步骤**：
  1. 手机端发送一个耗时长的复杂生成指令。
  2. 在生成过程中点击手机界面的红色停止按钮（Stop）。
  3. **预期结果**：电脑端代理日志立即打印 `Canceled execution task for session...`，本地网络连接中断，不再继续产生 chunk。

#### 7. 心跳与僵尸连接清理 (P1-7)
* **改动文件**：`relay_server/server.py`, `desktop_agent/agent.py`, `RelayWebSocketClient.kt`
* **关键逻辑**：服务端启动后台协程，每 25 秒向所有连接发送 `{"type":"ping"}`。双端收到均回复 `{"type":"pong"}` 并刷新时间戳。超过 35 秒未回复 pong 的连接被服务端判定为僵尸连接并主动关闭清理。
* **验收步骤**：
  1. 启动手机和桌面客户端连接进入房间。
  2. 强制杀掉手机 App 进程（模拟断电/掉线）。
  3. **预期结果**：30~35 秒内，服务端控制台打印 `Closing zombie connection for mobile in <room_id> (Heartbeat timeout)`，房间内的 mobile 连接被安全移除。

---

### P2 · 体验与工程优化

#### 8. agent 重连加退避与抖动 (P2-8)
* **改动文件**：`desktop_agent/agent.py`
* **关键逻辑**：从 3.0s 起步，退避系数 1.5，上限 60.0s，每次乘以 `random.uniform(0.85, 1.15)` 的抖动系数。重连成功后重置。
* **验收步骤**：关停 relay_server，观察 desktop_agent 日志，重连间隔从 3s、4.5s、6.8s 逐步拉长，不再刷屏打爆日志。

#### 9. 配对流程：终端二维码生成 (P2-9)
* **改动文件**：`desktop_agent/agent.py`, `desktop_agent/requirements.txt`
* **关键逻辑**：引入 `qrcode` 库。在终端输出大字标题的同时，输出包含 `account_id` 与 `relay_url` 的 ASCII 二维码（注意：**绝不包含 Secret**，遵循安全要求）。
* **验收步骤**：运行 `python agent.py`，终端打印清晰的 ASCII 二维码与受保护的 Secret 提示。

#### 10. App 端基础补齐 (P2-10)
* **改动文件**：`PreferencesManager.kt`, `OpenCodeViewModel.kt`, `PairingScreen.kt`, `ChatScreen.kt`, `RelayWebSocketClient.kt`
* **关键逻辑**：
  1. **持久化**：首次成功连接后，自动将账号、Secret、中继地址存入 `SharedPreferences`，下次冷启动自动填入。
  2. **超长输出防御**：单条消息超过 2000 行自动做折叠处理，防止 Compose 崩溃或 GC 掉帧。
  3. **指数退避重连**：网络意外断开后，Android 端启动 3s ~ 60s 带抖动的自动重连并在顶部显示倒计时横幅。
  4. **错误态 UI**：错误横幅独立置顶，支持手动 Dismiss，区分于普通消息。
* **验收步骤**：
  1. 在手机上输入账号和 Secret 成功连接后退出 App 并杀掉进程。
  2. 再次打开 App，所有配对参数已被自动还原，无需重新敲打键盘。
