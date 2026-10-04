# OpenCode Android Remote - Release v3.2.0（Tink 迁移）

## 内容

- **安全存储迁移**：`androidx.security:security-crypto:1.1.0-alpha06`（已弃用）→ `com.google.crypto.tink:tink-android:1.23.0`
  - AEAD 主密钥由 Android Keystore 保护，keyset 存私有 SharedPreferences；值 AES256-GCM 加密，associatedData 取 key 名
  - 旧数据自动迁移（读旧 → 写新 → 回读校验），幂等；旧文件保留不删，供回退
  - 迁移失败自动回退旧实现：ACRA 非致命上报 + 一次性用户提示 + 诊断页「安全存储」层显示 WARN
  - 旧 EncryptedSharedPreferences 实现保留至少 1 个版本（仍需编译依赖）
- 诊断页新增「安全存储」层：显示当前后端与状态

## 兼容性

- 纯客户端内部改动，无协议变更；旧版本数据可迁移
- 回滚：回到 v3.1.x，旧加密文件仍在，数据不丢

## 验证

- 新增 SecureMigrationTest（6 项，纯 JVM）：全量迁移、幂等、失败回滚清理、空存储、TinkAeadStore round-trip、key 隔离
- 真机验证：未验证（需在真机上覆盖安装，观察迁移提示与诊断页状态）

---

# OpenCode Android Remote - Release v3.1.0（多 desktop 体验）

## 内容

- **定向路由（协议，可选字段）**：新增 capability `desktop_routing`；mobile→desktop 信封可带 `target_device_id`（缺省仍走主 desktop）；desktop→mobile 消息带 `source_device_id`；新增 `list_desktops` 查询在线 desktop 列表
- **App**：设备页显示在线 desktop 列表（名称、ID 缩写、最近活动、主标记）；可选择目标电脑并持久化（按连接 profile 隔离）；会话与电脑绑定，切换目标时若有进行中会话会弹出确认；目标离线时给针对性 `DESKTOP_OFFLINE` 提示
- **开关**：所有新行为受 `FeatureFlags.ENABLE_DESKTOP_ROUTING` 控制（默认关闭，保持 v3.0 主路由行为）；旧 relay 自动退回主路由
- **序号语义不变**：relay_seq 仍按房间单调递增，断线补发逻辑不动

## 兼容性

- 协议只加可选字段；旧 App 忽略 `source_device_id`，行为不变
- 旧 relay 不识别 `target_device_id` 时，App 退回主 desktop 路由
- 回滚：App 回到 v3.0.x；服务端新字段可被忽略，无需数据迁移

## 验证

- 契约测试 19/19 通过（含新增 7 项路由用例）
- 真机验证：未验证（需两台电脑 + 一部手机，见 TESTING_CHECKLIST 第 8 节）

---

# OpenCode Android Remote - Release v3.0.2（补丁：CI 与文档）

## 内容

- **CI 接入 v3 契约测试**：`scripts/test_contract_v3.py` 正式进入 CI（此前只在手动跑），hello 协商 / 多 desktop 共存 / 主回退有自动回归保护
- **主 desktop 掉线回退修正**：按「最近认证时间」回退（此前取字典第一项，与文档语义不一致）；契约测试新增对应用例，本地实测 12/12 通过
- **文档补齐**：COMPATIBILITY 的 versionCode 表补到 30002，RELEASE_NOTES 补 v3.0.0/v3.0.1 条目；核对签名指纹与 applicationId 无变化

## 兼容性

- 协议、存储、App 逻辑都不变；v3.0.0 ↔ v3.0.1 ↔ v3.0.2 可互相覆盖安装
- 回滚：重装 v3.0.1 APK；relay 回退到 v3.0.1 的 server.py 即可

---

# OpenCode Android Remote - Release v3.0.1（热修：启动闪退）

## 内容

- **修复启动闪退**：v2.1 引入的 WS 状态机让 `setListener` 同步回调 `onConnectionStateChanged`，而 `OpenCodeViewModel` init 先调 `setListener`、后初始化 `_uiState` → NPE。v2.1~v3.0 全版本启动即闪退。修复：`_uiState` 就绪后再 `setListener`
- **验证**：GitHub Actions 云端模拟器（API 36/Android 16）跑 v3.0.0 复现崩溃、跑 v3.0.1 零 FATAL 且进程存活
- 教训：`setListener` 这类同步回调必须在依赖就绪后调用；以后加回调先查调用时机

## 兼容性

- 与 v3.0.0 完全兼容，可直接覆盖安装；签名证书、applicationId 不变

---

# OpenCode Android Remote - Release v3.0.0（架构）

## 内容

- **hello/hello_ack 能力协商**：协议 v=3，server_capabilities 含 `multi_desktop` / `device_id_revoke` / `room_buffer` / `pairing`；v2 客户端无 hello 仍可直接 auth（向后兼容）
- **relay 多 desktop 共存**：按 `device_id` 区分，同 `device_id` 才顶替；主 desktop=最近认证
- **Android Transport 抽象**：`Transport` 接口 + `RelayTransport` / `CloudTransport` + `TransportFactory`，ViewModel 保持 `RelayListener` / `CloudStreamListener` 身份
- **PROTOCOL_MISMATCH 错误码**：v3 App 连 v2 relay 时明确提示
- **docs/MIGRATION_V3.md**：升级与回滚指南

## 兼容性

- 升级顺序：先 relay/agent 到 v3，再覆盖装 APK（同签名，数据保留）
- 签名证书与 applicationId 不变

---

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
