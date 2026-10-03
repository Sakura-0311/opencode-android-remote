# OpenCode Android 原生客户端 (Native Android Remote)

这是一个基于 **Kotlin + Jetpack Compose** 构建的 OpenCode 原生安卓远程控制系统。针对跨网络连接的痛点，本项目设计了**“账号/配对码云端中继（Zero-Config Cloud Relay）”**架构：**手机与电脑无需在同一局域网、无需配置路由器端口映射或公网 IP**，只需在双端使用相同的账号及安全 Secret，即可建立长连接实时控制。

---

## ⚠️ 重要安全须知 (必读)

1. **安全边界与加密说明**：
   * 本系统采用**传输层加密（TLS / WSS）**保障通信安全，**非端到端应用层密文转发**。
   * 中继服务器作为消息路由中转，**在内存中可见转发的明文指令与响应内容**。
   * **强烈建议仅使用自己部署的 relay_server，切勿将个人代码与控制权限连接到未经验证的第三方公共中继**。
2. **Account ID 与 Secret 机制**：
   * `Account ID` 仅作为逻辑房间名（方便记忆和区分设备）。
   * `Secret` 是 32 字节高熵鉴权密钥，保存在电脑端本地（文件权限 `0600`，仅当前系统用户可读写）。
   * 任何试图连接房间的客户端（无论是桌面代理还是手机端）必须在 WebSocket 握手后 10 秒内完成 `auth` 验证，密码不符直接断开连接；未认证的连接绝对无法替换已有正常运行的电脑端连接。
   * 单 IP 连续 5 次认证失败将被限流器自动封禁 15 分钟，防止暴力破解。

---

## 1. 系统架构与通信机制

```
+-----------------------------------+             +----------------------------------+
|      Android 原生客户端 App         |             |       电脑端 Desktop Agent       |
|    (Kotlin + Jetpack Compose)     |             |      (Python 后台守护进程)        |
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

## 2. 目录结构

```
.
├── android_app/               # Android 原生工程目录
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/opencode/android/
│   │   │   │   ├── MainActivity.kt               # 应用入口
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/PreferencesManager.kt# 配对信息持久化 (免重复输入)
│   │   │   │   │   └── model/MessageModels.kt    # 数据模型与错误结构
│   │   │   │   ├── network/RelayWebSocketClient.kt# WebSocket 中继连接层 (指数退避重连+心跳)
│   │   │   │   ├── ui/
│   │   │   │   │   ├── screens/PairingScreen.kt  # 账号/Secret 输入界面
│   │   │   │   │   ├── screens/ChatScreen.kt     # 对话与代码输出主界面 (带错误态横幅)
│   │   │   │   │   └── theme/Theme.kt            # 极客暗黑主题
│   │   │   │   └── viewmodel/OpenCodeViewModel.kt# 响应式状态管理 (流式超长行保护)
│   │   │   └── res/
│   │   ├── .github/workflows/build-apk.yml       # GitHub Actions 自动化编译 APK
│   │   ├── build_apk.sh / build_apk.bat          # 本地一键打包脚本
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── desktop_agent/             # 电脑端桥接脚本
│   ├── agent.py               # 监听本地 OpenCode、管理 Secret 并连接中继 (支持任务中断)
│   ├── opencode_api.py        # 本地 OpenCode API 映射配置与启动健康自检
│   └── requirements.txt
├── relay_server/              # 云端中继转发服务
│   ├── server.py              # FastAPI / WebSockets 鉴权路由、心跳与 IP 防爆破限流
│   ├── Dockerfile             # 容器化部署文件
│   └── requirements.txt
├── docs/                      # 官方落地页 (支持 GitHub Pages)
└── README.md
```

---

## 3. 部署与使用流程

### 第一步：部署中继服务器 (Relay Server)

> 推荐部署在你自己的云服务器（如腾讯云、阿里云、海外 VPS）上。

1. **生产环境推荐：启用 WSS (TLS)**
   你可以直接配置 SSL 证书启动：
   ```bash
   cd relay_server
   pip install -r requirements.txt
   
   export SSL_CERTFILE="/path/to/fullchain.pem"
   export SSL_KEYFILE="/path/to/privkey.pem"
   python server.py
   ```
   或者通过 Nginx 反向代理终结 TLS 并转发给本地 `8765` 端口：
   ```nginx
   location /ws/ {
       proxy_pass http://127.0.0.1:8765;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header X-Real-IP $remote_addr;
   }
   ```

2. **Docker 一键部署**：
   ```bash
   cd relay_server
   docker build -t opencode-relay .
   docker run -d -p 8765:8765 --name opencode-relay opencode-relay
   ```

---

### 第二步：在电脑端启动 OpenCode 与桥接代理

1. 启动你的 OpenCode 后台服务：
   ```bash
   opencode serve --port 4096
   ```

2. 启动电脑端桥接代理（可自定义你的专属账号 ID，例如 `my_account_888`）：
   ```bash
   cd desktop_agent
   pip install -r requirements.txt
   
   # 参数格式: python agent.py <账号名> <中继服务器地址>
   python agent.py my_account_888 wss://your-relay-domain.com:8765
   ```
3. 启动后终端将显示如下信息：
   * `Account ID`: 你的房间名
   * `Secret`: 随机生成的 32 字节鉴权密钥（只在控制台打印，且以 `0600` 权限保存在本地文件）
   * 控制台二维码：手机扫码可快速录入地址与房间名。
   * 同时输出 OpenCode API 自检结果（`✔ OpenCode API 自检通过`）。

---

### 第三步：在手机端运行 Android App

1. **获取 APK**：
   * **方式 A (免环境)**：将工程推送到你自己的 GitHub 仓库，GitHub Actions 会在 2 分钟内自动编译并在 **Actions -> Artifacts** 生成安装包供下载。
   * **方式 B (本地打包)**：在 `android_app` 目录下运行 `./build_apk.sh` (Linux/Mac) 或 `build_apk.bat` (Windows)，在 `app/build/outputs/apk/debug/` 获得 APK。
2. 打开 App 后：
   * **账号/房间名**：输入 `my_account_888`。
   * **Secret**：输入电脑端终端显示的配对密钥（App 本地自动加密持久化保存，下次免输）。
   * **中继地址**：输入 `wss://your-relay-domain.com:8765`（本地模拟器调试可输入 `ws://10.0.2.2:8765`）。
   * 点击 **连接到电脑端 OpenCode**。
3. 顶部指示灯变为绿色 `● 电脑在线 · 已安全鉴权` 后，即可下达指令。如果电脑端未启动 OpenCode，手机端将收到红色警告横幅，绝不返回虚假数据。点暂停按钮可真正中断电脑端正在生成的流式请求。
