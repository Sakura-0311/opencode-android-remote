# OpenCode Android Remote - Release v2.6.0（工程化）

## 内容

- **agent.py 模块化**：拆为 `modules/{config,secrets,state,fileops,protocol}`，`agent.py` 为兼容入口；冒烟测试全过
- **CLI 参数**：agent.py（`--account-id/--secret/--relay-url/--workspace`）、relay server.py（`--port/--admin-token/--trusted-proxies`），默认从环境变量读取
- **供应链安全**：CI 加 gitleaks（扫 `docs/`）、OSV-Scanner（Python 依赖 + SBOM）
- **SBOM**：每次发布自动生成 `cyclonedx-bom.json`，随 release 产物附带
- **依赖校验和锁定**：`gradle/verification-metadata.xml`，构建时自动校验依赖完整性
- **checksums.txt**：release 产物附 SHA-256 校验文件

## 兼容性

- agent.py 调用方式不变（`python agent.py [pair]` 仍可用）
- 详见 `docs/COMPATIBILITY.md`

---

# OpenCode Android Remote - Release v2.5.0（体验）

## 内容

- **统一连接状态条**：顶部按当前模式二选一显示（中继订阅 Relay 状态机 / 云端订阅 SSE 状态机），不再只看圆点猜
- **多连接 profiles**：多个服务器/账号配置一键切换；密钥按配置单独存加密存储；旧单配置自动迁移为"默认连接"（旧 key 保留作保底）
- **错误码映射表**：错误条显示标题 + 处理建议，隧道类文案复用 Cloudflare/SakuraFrp 排障口径
- **配置导入/导出**：JSON 默认不含密钥；导入后提示重新配对
- **本地操作记录**：审批/拒绝/中断/撤销/配对等只记时间与对象，不含对话内容，上限 200 条

## 兼容性

- profiles 数据只增 key；旧版本无 profiles 概念，升级后自动迁移
- 详见 `docs/COMPATIBILITY.md`

---

# OpenCode Android Remote - Release v2.4.0（安全二期）

## 内容

- **撤销完整化**：设备改按 `device_id`（UUID）标识，名称仅展示；撤销时**主动断开该设备在线连接**（4401，被撤销设备不再自动重连）；非桌面设备只能撤销自己；两部同名手机撤销互不影响
- **建房令牌**：`RELAY_ADMIN_TOKEN` 未设置时启动打印警告；`docs/SECURITY.md` 列为生产必设项（`relay_server/.env.example`）
- **代理 IP**：`TRUSTED_PROXIES` 文档化；relay 在 Docker/反代后、所有连接来自同一私网 IP 时启动提示
- **限流器清理**：定期清理过期 IP 记录（每 25s 心跳顺带执行）
- **权限审计**：移除无使用点的 `WAKE_LOCK`；`docs/SECURITY.md` 列出全部权限及理由
- **评估结论**（`docs/EVALUATIONS.md`）：security-crypto 已确认 deprecated，本版不动，v2.5+ 迁 Tink；R8 评估未完全通过（ACRA 无 keep rules、ML Kit 未证实），`minify` 保持关闭
- **debug/release 分离**：debug 包加 `applicationIdSuffix ".debug"`（release 的 applicationId 不变）

## 兼容性

- 撤销/重命名协议改按 `device_id`，同时兼容旧 App 的 `device_name` 参数；`device_list` 新增 `device_id` 字段（旧 App 忽略）
- `pair_success` 新增 `device_id`（旧 App 忽略）
- 详见 `docs/COMPATIBILITY.md`

---

# OpenCode Android Remote - Release v2.3.0（稳定性/弱网）

## 内容

- **网络回调**：`NetworkMonitor`（500ms 防抖），断网时暂停重连计时器，网络恢复时直接重建连接，不再等 OkHttp ping 超时（`FeatureFlags.USE_NETWORK_MONITOR` 可回退旧行为）
- **重同步语义**：`RESYNC_REQUIRED` / epoch 变化不再走任务失败路径；新增 `onResyncRequired`，只追加同步提示，不中止进度、不弹失败通知
- **统一重连**：`Backoff` 退避器（Relay 与 Cloud SSE 共用；鉴权失败/被封禁绝不重试）；Cloud SSE 重试上限从固定 8 次改为持续离线 30 分钟，并向 UI 暴露重连状态
- **connect() 清理**：先 cancel 旧 socket；每个 socket 带代号，过期回调直接丢弃
- **写操作幂等**：`send_prompt` / `cancel` 带 `client_msg_id`，agent 侧 TTL（10 分钟）去重；发送失败（socket 不可用）显示「未确认」由用户手动重试，绝不自动重发
- **日志**：`AppLog` 环形文件日志（2×1MB），统一脱敏（Secret/口令/token/URL query，prompt 与代码正文不进日志），可导出
- **配置 schema**：`PreferencesManager` 加 `schema_version` 与幂等迁移（v1）
- **seq 持久化**：改成「处理后再写盘」（at-least-once，重复由去重消化）

## 测试

- CI：`testDebugUnitTest`（`BackoffTest`、`AppLogRedactTest`）+ 冒烟测试
- 待人工真机验证：飞行模式开关 / Wi-Fi↔蜂窝切换后 ≤10s 恢复；relay 重启后（有 epoch）手机能收到新消息；导出日志无 Secret；断线下重复点击发送不产生重复任务

## 兼容性

协议只加可选字段（`client_msg_id`、`duplicate_ignored`）；存储只增 key。详见 `docs/COMPATIBILITY.md`。

---

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
