# OpenCode Android Remote - Release v1.5.0 (GitHub Production Ready)

本版本为面向生产与公开发布的里程碑版本，完成了深度漏洞加固与架构重塑，彻底解决旧版本的契约失配与安全隐患，达到 GitHub 开源发布标准。

---

## 🚀 核心更新亮点

### 1. 安全加固 (Security Hardening)
* **Caddy 自动 TLS 编排 (SEC-01)**：云端模式集成 Caddy 2 反向代理，自动申请 Let's Encrypt 证书并终结 TLS，OpenCode 容器仅在内部桥接网络暴露，彻底解决公网明文传输与绑定死锁。
* **HMAC 恒定时间防计时侧信道攻击 (SEC-03)**：中继服务器密钥比对升级为 `hmac.compare_digest`，严格限制受信任反代 IP 提取。
* **工具审批 Nonce 防重放与熔断守卫 (SEC-04)**：桌面代理为每个审批请求生成 128-bit 随机 Nonce，支持 120 秒超时自动熔断与幂等消费，杜绝网络重发或劫持风险。
* **Android Keystore 硬件级存储 (SEC-05)**：配对密钥与云端口令升级为 `EncryptedSharedPreferences` AES-256-GCM 硬件加密存储，禁用 `allowBackup`。
* **严格网络安全策略 (NET-01)**：新增 `network_security_config.xml`，默认禁用 Cleartext HTTP 明文传输。

### 2. 协议与可靠性增强 (Reliability & Streaming)
* **SSE 增量流断线游标补偿 (PRT-01)**：桌面代理与 Android 客户端全面支持标准 `Last-Event-ID` 请求头，弱网切换重连不丢字。
* **官方 OpenCode HTTP 契约完全对齐**：探活严格请求 `/global/health` (HTTP 200)，消息基于 `/session/:id/message` parts 结构，会话基于真实 ULID，杜绝假数据。

### 3. 工程与交付规范 (GitHub CI/CD & Tooling)
* **GitHub Actions CI/CD**：新增 `.github/workflows/ci.yml`，每次提交自动执行官方契约冒烟测试与 Android 编译。
* **发布脚本套件**：配套 `scripts/package_release.sh`、`scripts/smoke_test_contract.py` 与文档生成工具。

---

## 📦 快速部署指令

### 💻 电脑中继模式 (Desktop Relay)
```bash
# 启动本地 OpenCode 服务
opencode serve --port 4096

# 启动桥接代理
cd desktop_agent
pip install -r requirements.txt
python agent.py user_dev_001 ws://your-relay-domain.com:8765
```

### ☁️ 云端工作区模式 (Cloud Hosted)
```bash
cd cloud_server
export DOMAIN_NAME="opencode.yourdomain.com"
export OPENCODE_SERVER_PASSWORD="your_secure_password"
export GEMINI_API_KEY="AIzaSy..."
docker compose up -d
```
