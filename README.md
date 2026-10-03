# OpenCode Android 原生客户端 (Native Android Remote & Cloud)

基于 **Kotlin + Jetpack Compose** 构建的 [OpenCode](https://github.com/anomalyco/opencode) 原生安卓远程控制客户端。结合真实手机移动端云端开发场景，提供**“电脑远程中继 (Desktop Relay)”**与**“云端工作区直连 (Cloud Hosted)”**双模支持，并具备针对移动端优化的轻量高效管控体验。

---

## ⚠️ 重要安全须知 (必读)

1. **安全边界与加密说明**：
   * 本系统采用**传输层加密（TLS / WSS）**保障通信安全，**非端到端应用层密文转发**。
   * 中继服务器作为消息路由中转，**在内存中可见转发的明文指令与响应内容**。
   * **强烈建议仅使用自己部署的 relay_server，切勿将个人代码与控制权限连接到未经验证的第三方公共中继**。
2. **Account ID 与 Secret 机制**：
   * `Account ID` 仅作为逻辑房间名（方便记忆和区分设备）。
   * `Secret` 是 32 字节高熵鉴权密钥，保存在电脑端本地（文件权限 `0600`，仅当前系统用户可读写）。
   * 任何客户端连接必须在 WebSocket 握手后 10 秒内完成 `auth` 验证，密码不符直接断开连接；未认证的连接绝对无法替换已有正常运行的电脑端连接。
   * 单 IP 连续 5 次认证失败将被限流器自动封禁 15 分钟，防止暴力破解。

---

## 🌟 核心特性与深度优化功能 (六大模块全落地)

### 一、高优先级优化建议（核心刚需）
1. **会话分组与标签管理功能** (`SessionItem`, `PreferencesManager.kt`, `OpenCodeViewModel.kt`)：
   * **自定义分类标签**：支持为会话设置 `自动化任务`、`代码调试`、`脚本生成` 等自定义标签。
   * **按标签实时筛选**：通过顶部横向滑动的 FilterChip 一键过滤当前分类下的活跃会话。
   * **会话置顶 (Pinned)**：重要长期任务一键置顶展示，不受时间排序影响。
   * **一键批量归档 (Batch Archive)**：长任务堆积时，一键批量归档非置顶的历史旧会话，保持移动端界面轻爽简洁。
   * **本地持久化**：使用轻量级 `SharedPreferences` + JSON 序列化，离线也能完整保留会话分组与标签配置。

2. **工具审批页面精简代码 Diff 预览** (`CompactDiffView.kt`, `ToolApprovalDialog.kt`)：
   * **精简改动高亮**：弹窗内仅高亮新增行（绿色）与删除行（红色），自动折叠隐藏连续无变动的冗长上下文（仅保留周边 1 行上下文）。
   * **展开查看完整文件**：支持点击右上角【展开完整文件 / 收起】无缝切换全量代码视图。
   * **改动统计 Chip**：顶部直观标注 `+N` 和 `-N` 代码变动行数。
   * **拇指友好的审批操作**：底部提供大尺寸【同意执行】与【拒绝】按钮，锁屏收到推送点开即审，大幅提升远程决策效率。

### 二、中优先级优化建议（体验刚需）
1. **多服务器连通性检测与隧道排查提示** (`TunnelDiagnosticsHelper.kt`)：
   * **一键探活测速**：在配对页面一键发起端到端连通性与延迟测算（ms 级呈现）。
   * **国内主流隧道精准适配**：
     * **Cloudflare Tunnel (cloudflared)**：智能检测并排查 HTTP 521（源站 OpenCode 服务未启动）、522（源站握手超时）、520（反代异常）、524（长任务超过 100s 网关限制）及 Cloudflare Zero Trust 访问拦截。
     * **SakuraFrp / frp**：针对国内穿透环境给出隧道离线、端口未映射、大陆节点 HTTP 80/443 未备案拦截等针对性排查指引。
     * **SSL 证书诊断**：自动捕获自签名证书、Let's Encrypt 证书过期等握手异常并提供配置指引。
     * **防火墙与安全组**：准确区分 Connection Refused（端口未监听）与 Socket Timeout（云安全组未放行）。

2. **后台保活机制与任务常驻进度通知** (`OpenCodeKeepAliveService.kt`)：
   * **前台常驻服务 (Foreground Service)**：长任务运行期间在通知栏常驻展示，防止国内系统自动杀后台进程。
   * **秒级运行时长与执行步骤**：通知栏实时更新当前已运行时间（如 `01:45`）及 AI 正在执行的具体操作步骤（如 `AI 正在生成/执行: git diff...`）。
   * **强震动与弹窗提醒**：工具审批到达时触发强震动与高优先级 Heads-up 通知；任务执行完毕时触发短震动与完成卡片。
   * **忽略电池优化引导**：内置一键跳转系统“忽略电池优化”权限设置，双重保障后台保活稳定性。

### 三、低优先级优化建议（细节体验升级）
1. **日志页面功能优化** (`ChatScreen.kt`)：
   * **实时关键词搜索与金黄色高亮**：输入关键字后，消息与日志正文中的匹配文本自动以金黄色（`#FACC15`）高亮显示，方便快速定位报错与关键信息。
   * **触摸暂停自动滚动**：用户用手指滑动或查看上方历史日志时，自动锁定滚动位置暂停滚底；松手后出现贴心悬浮浮标，点击即可一键回到底部并恢复流式跟随。

2. **会话一键导出为 Markdown** (`MarkdownExporter.kt`)：
   * **标准 Markdown 格式化**：将用户指令、AI 输出、工具调用轨迹整理为清晰优雅的标准 Markdown 文档。
   * **系统分享与剪贴板集成**：支持一键复制到剪贴板，或直接调起系统分享面板将记录发送到微信、语雀、Notion、系统备忘录或邮件。

---

## 🏗️ 架构概览

```
+-----------------------------------+             +----------------------------------+
|      Android 原生客户端 App         |             |       电脑端 Desktop Agent       |
|    (Kotlin + Jetpack Compose)     |             |      (Python 后台守护进程)        |
| - ViewModel / UI State 状态驱动   |             | - opencode_api.py (本地健康/流)   |
| - KeepAliveService 前台进度保活   |             | - 自动重连与取消机制              |
| - CompactDiffView 精简审批        |             | - 0600 受限本地密钥保管           |
+-----------------+-----------------+             +-----------------+----------------+
                  |                                                 |
                  | WSS 安全长连接 (带 Secret 握手)                   | WSS 安全长连接 (带 Secret 握手)
                  | /ws/{accountId}/mobile                          | /ws/{accountId}/desktop
                  v                                                 v
         +-------------------------------------------------------------------+
         |                    OpenCode Secure Relay Server                   |
         |  - 强制 Secret 鉴权校验与防暴力枚举（IP 限流滑动窗口）             |
         |  - 应用层心跳探活 (25s ping/pong) 剔除僵尸连接                    |
         |  - 实时双向流转发（Mobile Prompt <-> Desktop SSE Chunks）         |
         |  - 工具调用审批双向透传 (Approval Request <-> Response)          |
         +-------------------------------------------------------------------+
                                                                    |
                                                                    | 本地 HTTP / SSE (127.0.0.1:4096)
                                                                    v
                                                       +-----------------------------+
                                                       |      本地 OpenCode 核心     |
                                                       |   (`opencode serve` 运行)   |
                                                       +-----------------------------+
```

---

## 📁 项目目录结构

```
.
├── README.md                                # 项目全局开发与优化文档
├── android_app/                             # Android 原生客户端 (Kotlin + Compose)
│   ├── app/
│   │   ├── build.gradle.kts                 # App 构建与依赖配置
│   │   └── src/main/
│   │       ├── AndroidManifest.xml          # 权限与前台服务声明
│   │       ├── java/com/opencode/android/
│   │       │   ├── MainActivity.kt          # 主入口 Activity
│   │       │   ├── data/
│   │       │   │   ├── local/
│   │       │   │   │   └── PreferencesManager.kt       # 本地会话与配置持久化存储
│   │       │   │   └── model/
│   │       │   │       └── MessageModels.kt            # 核心数据模型与 UI 状态
│   │       │   ├── network/
│   │       │   │   ├── CloudApiClient.kt               # 云端工作区直连与 SSE 客户端
│   │       │   │   ├── RelayWebSocketClient.kt         # 中继长连接与审批透传
│   │       │   │   └── TunnelDiagnosticsHelper.kt      # 隧道适配诊断与排障提示
│   │       │   ├── service/
│   │       │   │   └── OpenCodeKeepAliveService.kt     # 后台保活、常驻进度通知与震动
│   │       │   ├── ui/
│   │       │   │   ├── components/
│   │       │   │   │   ├── CompactDiffView.kt          # 精简代码 Diff 预览与上下文折叠
│   │       │   │   │   └── ToolApprovalDialog.kt       # 原生工具审批弹窗
│   │       │   │   ├── screens/
│   │       │   │   │   ├── ChatScreen.kt               # 聊天主屏、搜索高亮与触摸防滚屏
│   │       │   │   │   └── PairingScreen.kt            # 双模配对与一键连通性测试
│   │       │   │   └── theme/
│   │       │   │       └── Theme.kt                    # 深色/浅色沉浸式主题
│   │       │   └── util/
│   │       │       └── MarkdownExporter.kt             # 会话一键导出 Markdown 与系统分享
│   │       │   └── viewmodel/
│   │       │       └── OpenCodeViewModel.kt            # 状态调度与业务控制核心
│   │       └── res/values/strings.xml
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── settings.gradle.kts
├── desktop_agent/                           # 电脑端代理桥接 (Python)
│   ├── agent.py                             # 电脑端 WebSocket 客户端与任务守护
│   ├── opencode_api.py                      # 本地 OpenCode API 健康与 SSE 流式请求
│   └── requirements.txt
└── relay_server/                            # 生产级安全中继服务器 (FastAPI)
    ├── server.py                            # WSS 中继路由、滑动窗口防爆破限流
    ├── Dockerfile
    └── requirements.txt
```

---

## 🚀 快速上手与运行指南

### 1. 部署中继服务 (Relay Server)
```bash
cd relay_server
pip install -r requirements.txt
python server.py
# 默认监听 8765 端口（生产环境推荐配合 Nginx 配置 WSS 证书）
```

### 2. 启动电脑端桥接代理 (Desktop Agent)
```bash
# 启动本地 OpenCode 服务
opencode serve --port 4096

# 启动桥接代理 (可自定义房间名，如: my_dev_001)
cd desktop_agent
pip install -r requirements.txt
python agent.py my_dev_001 ws://your-relay-domain.com:8765
```

### 3. 手机端连接使用
1. 在 Android Studio 打开 `android_app`，编译安装至手机（或推送至 GitHub 触发 Actions 自动编译下载 APK）。
2. 在 App 界面选择：
   * **💻 电脑中继模式**：输入相同的房间名与控制台显示的 Secret 密钥即可连通；
   * **☁️ 云端工作区**：输入云端 OpenCode 地址与 Token 直连；
3. 点击 **连通性检测** 按钮，系统将自动测试网络延迟并给出隧道适配提示。连通成功后即可开始流畅远程管控！
