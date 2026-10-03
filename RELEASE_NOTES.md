# OpenCode Android Remote - Release v2.2.1（热修）

> 基于 v2.2 源码的代码审计修订（P0 先修，不加新功能）。v2.x 之间可覆盖升级（签名不变）。

## P0 修复

- **A 文件沙盒**（`desktop_agent/agent.py`）：文件浏览器限制在 agent 启动目录（OpenCode 项目目录）内，可用 `AGENT_FILE_ROOTS` 环境变量放宽（用时打印警告）；`realpath` 解析防 `..` 与符号链接绕行；`.env`、`id_rsa*`、`*.pem`、`.opencode_secret` 等敏感文件即使在根内也拒绝，返回 `PATH_NOT_ALLOWED`。主 Secret 默认位置迁至 `~/.config/opencode-remote/`（旧位置自动迁移）。
- **B relay 设备密钥持久化**（`relay_server/server.py`）：`device_secrets` 与主密钥哈希落盘到 `~/.config/opencode-remote/relay_state.json`（0600，原子写，schema_version=1）；房间销毁/relay 重启后，桌面用主密钥重建房间时自动恢复设备密钥，手机无需重新配对。
- **C 序号纪元**：房间创建时生成随机 `room_epoch`，随 `auth_ok`/`seq_sync` 下发（可选字段）；App 检测到 epoch 变化时 seq 归零并提示重同步，relay 重启后不再静默丢消息。
- **D 发布签名 fail-fast**：无 release 密钥时 `assembleRelease` 直接失败，不再静默降级为 debug 签名；CI 上 PR 只构建 debug，`v*` tag 才构建 release（缺 Secret 即失败）；新增无密钥 fail-fast 验证 job。
- **E crash-report 加固**：默认关闭（`RELAY_ENABLE_CRASH_REPORT=1` 才开）；请求体上限 128KB（先查 Content-Length 再流式限长读）；按 IP 限流（每小时 20 次）；文件名只保留 `[A-Za-z0-9-]`；目录配额（500 文件 / 100MB，超了删最旧）。

## 兼容性

详见 `docs/COMPATIBILITY.md`。release 签名指纹（SHA-256）：`D9:21:C0:EB:AE:4A:DD:89:73:0B:BD:E7:49:1F:27:FD:26:41:95:0C:E1:19:1F:30:53:A3:E7:66:00:9E:9E:78`，applicationId `com.opencode.android`，两者永不更改。

---

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
