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
   *终端将打印 128 bit 高熵随机 Secret 密钥及配对二维码。*
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
   * 默认采用**传输层加密（TLS / WSS）**保障通信安全；此时中继服务器在内存中
     可见转发的指令与响应内容。
   * 可选**端到端加密（E2EE，默认关闭）**：手机与电脑协商 X25519 密钥后，
     prompt、回复流、审批请求、文件内容经 ChaCha20-Poly1305 加密，relay 只盲转发；
     协商后控制类消息无合法信封一律拒绝（fail-closed），密文带序号防重放。
     详见 `docs/E2EE_WIRE_v1.md` 与 `docs/E2EE_THREAT_MODEL.md`（含如实保护范围）。
   * v5.0.1 起 E2EE **不再有多对端静默降级**：同一房间配对了两台以上手机时，
     desktop 无法确定该加密给谁，会**拒发内容型消息并回报 `E2EE_UNAVAILABLE`**，
     而不是像以前那样悄悄发明文。多手机同时在线不属于当前支持范围
     （见威胁模型「多 mobile 暂不考虑」），请一台手机一个房间。
   * **强烈建议仅使用自己部署的 relay_server，切勿将个人代码与控制权限连接到未经验证的第三方公共中继**。
2. **Secret 密钥与房间防护**：
   * `Secret` 是 128 bit 高熵鉴权密钥，在电脑端本地以 `0600` 受限权限存储（仅当前系统用户可读写）。
   * v4.7.0 起 agent 启动默认**不再明文打印** secret（打码显示），仅首次生成时打印一次；
     需要查看用 `python agent.py pair --show-secret`。
   * 所有连接必须在握手后 10 秒内完成密钥验证，密码错误直接拒绝并断开。
   * 单 IP 连续 5 次认证失败自动封禁 15 分钟，防止暴力破解。
     v5.0.3 起封禁按 `(来源 IP, account_id)` 记账：账号已知时，攻击者只能封自己
     那个账号，不会牵连同一反代出口 IP 下的其他用户（账号未知时的失败，如握手
     超时、首帧畸形，仍按 IP 计）。
   * v5.0.1 起 `TRUSTED_PROXIES` **默认留空＝忽略 `X-Forwarded-For`**：此前默认信任
     回环地址，配合「只绑回环」的默认部署，本机任意进程可自带 XFF 伪造来源 IP
     （绕过限流，或反向把正常用户封禁）。只有真的接了反代才填反代出口 IP。
   * v5.0.1 起新增 `RELAY_ALLOWED_ORIGINS`：原生 App 不发 `Origin` 不受影响；
     留空时一切带 `Origin` 的浏览器连接被拒，防「任意网页直连本机 relay」。
   * v4.7.0 起 relay **必须配置 `RELAY_ADMIN_TOKEN` 才能启动**（未设置/过短则拒绝启动），
     建房需携带管理令牌，防房间被抢注。
   * v4.8.0 起 App 在配对页输入 `ws://` / `http://` 会显示明文警告，release 包连接前需二次确认。
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
* **聊天记录本地留存**：
     * v5.0.3 起按会话缓存最近 200 条消息（纯本地，不含凭据）。
       划掉最近任务或进程被系统回收后回来仍能看到此前的对话，切换会话也不再清屏。

---

## 📁 项目目录结构

```
.
├── README.md                                # 项目使用与安全指南
├── docs/archive/                            # 历史报告归档（含 v1.x 优化报告）
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
