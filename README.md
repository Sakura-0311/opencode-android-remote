# OpenCode Android Remote (OpenCode 原生安卓远程客户端)

把电脑与云端上的 OpenCode 编码 Agent，装进你的手机。

基于 **Kotlin + Jetpack Compose** 构建，结合手机移动端的使用场景，提供**“电脑远程中继 (Desktop Relay)”**与**“云端工作区直连 (Cloud Hosted)”**双模支持。

> 隐私说明：[PRIVACY.md](PRIVACY.md) —— 你的数据只保存在你自己的手机上，只发往你自己配置的服务器。

---

## ⚡ 3 分钟快速上手

### 模式 A：电脑远程控制 (Desktop Relay)
通过中继服务远程控制办公电脑或家庭开发机上的 OpenCode 与本地 Git 仓库：

1. **启动电脑端 OpenCode 服务**：
   ```bash
   opencode serve --port 4096
   ```
   > **上游兼容版本（B1）**：已验证 opencode **1.18.x**（契约以 1.18.34 的 `/doc` 为准）。
   > agent 启动时会自动拉取 `GET /doc` 做端点契约校验：关键端点缺失则**拒绝启动并列出缺失项**；
   > 老版本无 `/doc` 时降级为警告。端点配置表见 `desktop_agent/endpoints.py`。
2. **启动电脑端代理桥接**：
   ```bash
   cd desktop_agent
   pip install -r requirements.txt
   python agent.py user_dev_001 wss://your-relay-domain.com:8765
   ```
   *终端将打印 32 字节高熵随机 Secret 密钥及配对二维码。*
   *P0-2：生产环境请使用 wss://（TLS）；仅本地开发调试可用 ws://localhost/ws://127.0.0.1。*
3. **手机端连接**：
   打开 App，输入房间名 `user_dev_001`，点击 Secret 输入框右侧的 **【粘贴】** 按钮或手动填入密钥，点击连接即可开始远程编程。

---

### 模式 B：云端工作区直连 (Cloud Hosted)
无需开电脑，直接连入 7x24 小时运行在云服务器（VPS/Docker）上的 OpenCode 实例：

1. **在云服务器启动 OpenCode**：
   ```bash
   cd cloud_server
   # 设置访问口令与模型 Key
   export OPENCODE_SERVER_PASSWORD="your_secure_password"
   export GEMINI_API_KEY="AIzaSy..." # 或 OPENAI_API_KEY / ANTHROPIC_API_KEY
   docker compose up -d
   ```
2. **配合 Nginx 启用 HTTPS 反向代理**（必须启用 TLS 保护）。
3. **手机端直连**：
   打开 App 切换到 **【☁️ 云端工作区】**，输入云端地址与访问口令即可直连。

---

## 🔒 安全须知与架构边界 (必读)

1. **传输安全与数据边界**：
   * 本系统采用**传输层加密（TLS / WSS）**保障通信安全，**非端到端应用层密文转发**。
   * 中继服务器作为消息路由中转，在内存中可见转发的指令与响应内容。
   * **强烈建议仅使用自己部署的 relay_server，切勿将个人代码与控制权限连接到未经验证的第三方公共中继**。
2. **Secret 密钥与房间防护**：
   * `Secret` 是 32 字节高熵鉴权密钥，在电脑端本地以 `0600` 受限权限存储（仅当前系统用户可读写）。
   * 所有连接必须在握手后 10 秒内完成密钥验证，密码错误直接拒绝并断开。
   * 单 IP 连续 5 次认证失败自动封禁 15 分钟，防止暴力破解。
   * 客户端已配置 `allowBackup="false"`，杜绝敏感密钥随系统云备份泄露。
3. **云端模式生产加固**：
   * Docker 容器仅绑定宿主机 `127.0.0.1:4096`，严禁在 `0.0.0.0` 上无密码暴露公网。

---

## 🌟 核心特性与能力

* **真实官方协议契约**：严格对接 OpenCode 官方 API 契约（`/global/health` 探活、`/session` 会话管理、`/session/:id/message` 提示词交互、`/event` SSE 增量订阅、`/session/:id/abort` 任务中断）。
* **真机工具审批与精简 Diff 预览**：
  * AI 申请执行文件改动时，通过官方 `/permissions` 协议实时推送审批。
  * 专为手机小屏幕优化的精简 Diff 预览：自动折叠未修改上下文，仅高亮新增行（绿色）与删除行（红色），并支持展开查看完整代码。
  * 审批决定直接调用官方授权接口回传，绝无虚假指令转发。
* **一键连通性测试与隧道排障**：
  * 配对页面一键发起端到端连通性与网络测速。
  * 精准适配 Cloudflare Tunnel 特征状态码（HTTP 521、522、524、Zero Trust 拦截）与 SakuraFrp/frp 国内穿透排查提示。
* **后台保活与常驻进度**：
  * 任务执行期间在通知栏实时展示运行时长（分:秒）与执行步骤。
  * Android 13+ 运行时通知权限动态请求，工具审批强震动提醒。
* **会话管理与 Markdown 导出**：
  * 实时同步服务端会话列表，支持会话置顶、按标签分类与批量归档。
  * 会话记录一键格式化为标准 Markdown，支持一键复制到剪贴板或唤起系统分享。

---

## 📁 项目目录结构

```
.
├── README.md                                # 项目使用与安全指南
├── OPTIMIZATION_REPORT.md                   # 历史加固与审计记录
├── scripts/
│   └── smoke_test_contract.py               # OpenCode 官方真实契约端到端自动化冒烟测试
├── cloud_server/                            # 云端直连 Docker 部署
│   ├── docker-compose.yml                   # 仅绑定 127.0.0.1 安全配置
│   └── README.md                            # 云端部署与 Nginx 反代配置说明
├── desktop_agent/                           # 电脑端代理桥接 (Python)
│   ├── agent.py                             # 电脑端长连接守护与 /event 订阅
│   ├── opencode_api.py                      # 官方真实 REST/SSE HTTP API 客户端
│   └── requirements.txt
├── relay_server/                            # 安全中继服务器 (FastAPI)
│   ├── server.py                            # WSS 中继路由、防爆破限流与心跳
│   ├── Dockerfile
│   └── requirements.txt
└── android_app/                             # Android 原生客户端 (Kotlin + Compose)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml          # 禁用云备份，声明权限与前台服务
    │       ├── java/com/opencode/android/
    │       │   ├── MainActivity.kt          # 运行时通知权限申请
    │       │   ├── data/local/PreferencesManager.kt # 本地持久化与无默认假地址
    │       │   ├── network/
    │       │   │   ├── CloudApiClient.kt    # 真实契约云端直连与 SSE 客户端
    │       │   │   ├── RelayWebSocketClient.kt # 中继长连接与真实会话/审批协议
    │       │   │   └── TunnelDiagnosticsHelper.kt # 连通性测试与隧道排错
    │       │   ├── service/OpenCodeKeepAliveService.kt # 前台保活与异常捕获
    │       │   ├── ui/components/
    │       │   │   ├── CompactDiffView.kt   # 精简 Diff 预览组件
    │       │   │   └── ToolApprovalDialog.kt # 真实工具审批弹窗
    │       │   └── ui/screens/
    │       │       ├── ChatScreen.kt        # 聊天主屏、搜索高亮与防滚屏
    │       │       └── PairingScreen.kt     # 剪贴板一键粘贴密钥与双模配对
    │       └── res/values/strings.xml
    └── build.gradle.kts
```
