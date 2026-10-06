# 安全说明（SECURITY.md）

## Android 权限审计（v2.4）

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| INTERNET | 网络连接 | Relay WebSocket / Cloud SSE 必需 |
| CAMERA | 扫码配对 | 扫描桌面端配对二维码（CameraX + ML Kit），仅配对页使用 |
| ACCESS_NETWORK_STATE | 网络状态 | v2.3 `NetworkMonitor` 监听网络变化 |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE | 后台保活 | 任务执行期间前台服务保活（`specialUse` 类型） |
| POST_NOTIFICATIONS | 任务通知 | 任务完成/失败/等待输入通知 |
| VIBRATE | 通知震动 | 审批请求的触觉反馈 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 保活 | 用户手动点击才申请（配对页按钮），用于任务执行期间不被 Doze 杀死；不申请则长任务可能中断 |

**已移除**：`WAKE_LOCK`（v2.4 审计确认代码中无使用点）。

## Relay 服务端环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `RELAY_ADMIN_TOKEN` | 空 | **生产必设**。设置后 desktop 首次建房必须携带相符的 `admin_token`，防止 relay 重启后房间被抢注。未设置时启动打印警告 |
| `TRUSTED_PROXIES` | 空（不信任任何代理） | 受信任的反向代理 IP（逗号分隔）。**只有来自这些地址的连接才采信 `X-Forwarded-For`**。v5.0.1 起默认留空＝完全忽略 XFF（直连部署的正确行为）。此前默认 `127.0.0.1,::1`，而默认部署恰好只绑回环，导致本机任意进程可自带 XFF 伪造来源 IP——既绕过限流，也能反向用受害者 IP 连发失败把正常用户封禁 15 分钟（未认证 DoS）。relay 在 Docker/反代后面时必须显式配置，否则所有用户共用一个 IP。v5.0.3 起认证失败封禁按 `(IP, 账号)` 记账，共享 IP 不再导致「一个人输错 5 次封所有人」，但连接频次限流仍按 IP |
| `RELAY_ALLOWED_ORIGINS` | 空（拒绝一切带 Origin 的连接） | v5.0.1 新增：WebSocket `Origin` 白名单（逗号分隔）。原生 App 不发送 `Origin`，留空不影响 App；带 `Origin` 的浏览器连接必须在白名单内，防「任意网页直连本机 relay 发指令」 |
| `RELAY_STATE_FILE` | `~/.config/opencode-remote/relay_state.json` | 设备密钥持久化文件（0600） |
| `RELAY_ENABLE_CRASH_REPORT` | `0` | `1` 才开启 `/api/crash-report` 崩溃上报接收 |
| `RELAY_STATS_TOKEN` | 空（不鉴权） | v5.0.3 新增：设置后 `/api/stats` 必须带 `X-Stats-Token` 头，否则 401（用 `hmac.compare_digest` 比对）。公网暴露统计接口时建议设置；未设置保持原有行为 |
| 认证失败封禁 | 单 IP 5 次 → 900 秒 | v5.0.3：封禁与失败计数按 `(来源 IP, account_id)` 记账——账号已知时，攻击者只能封自己那个账号，不会牵连同一反代出口 IP 下的其他用户。账号还未知时的失败（握手超时、首帧畸形、hello 后不发 auth）仍按 IP 计 |
| `RELAY_ROOM_BUFFER_MAX_COUNT` / `RELAY_ROOM_BUFFER_MAX_AGE_SEC` / `RELAY_ROOM_BUFFER_MAX_BYTES` | `1000` / `300` / `4MB` | 房间消息缓冲三重上限 |
| `RELAY_PAIRING_TTL` | `120` | 配对码有效期（秒） |
| `AGENT_FILE_ROOTS` | agent 启动目录 | 文件浏览器允许根目录（`:` 分隔多个）；使用时打印警告 |

示例（`relay_server/.env.example`）：

```bash
# 生产必设：建房管理令牌（桌面端 auth 消息携带 admin_token）
RELAY_ADMIN_TOKEN=换成你自己的随机字符串
# relay 在 Nginx/Caddy 反代后时，填反代的出口 IP
TRUSTED_PROXIES=127.0.0.1,::1
# 默认关闭；需要接收 App 崩溃上报时设为 1
RELAY_ENABLE_CRASH_REPORT=0
# 可选：公网暴露 /api/stats 时加一层 token（请求需带 X-Stats-Token 头）
# RELAY_STATS_TOKEN=换成你自己的随机字符串
```

## 数据边界

- 中继服务器在内存中可见转发的指令与响应内容（传输层 TLS 加密，非端到端加密）。**只用自己部署的 relay**。
- 崩溃上报默认关闭；开启后仅收集脱敏字段（版本/机型/Android 版本/堆栈/时间），无 logcat、无设备 ID。
- 文件日志（AppLog）已脱敏：不含 Secret、口令、token、prompt 与代码正文。
- `allowBackup=false`：密钥不随系统云备份泄露。

## v4.1.0 审计结论与 v4.2.0 落实情况（方案 1：E2EE 发版后实施）

来源：用户 2026-10-04 上传《OpenCode 项目漏洞排查与优化建议》8 项，全部只读审计；v4.2.0 落实 5 项改动，3 项只注记。

**已实施（v4.2.0）：**

- V2：桌面端 `keyring` 可选依赖——Windows Credential Manager / macOS Keychain 优先，
  try-import 失败或后端不可用时回退原有 0600 文件存储（`desktop_agent/modules/secrets.py`）。
- V4-1：`TinkAeadStore` 解密失败日志去掉 key 名（key 名非密钥值，风险低，按审计要求清理）。
- V4-2：`proguard-rules.pro` 加 `-assumenosideeffects` 剥除 `android.util.Log.d/v`（release 纵深防御）。
- O1：release 包开启 `shrinkResources=true`（全仓无 `getIdentifier` 动态资源引用；
  CI 报告模板同步更新；emulator-smoke 兜底；`fullMode` 不开）。
- O3：`relay_server/Dockerfile` 多阶段构建（builder 装依赖到 `/install`，final 只拷
  `/usr/local` + `server.py`；`python:3.11-slim` 不变）。
- O4：CI 提速——`python-tests` job 加 pip 缓存；Android 单测从 `android-build` 拆独立 job 并行。

**只注记不改（决策理由）：**

- V1（SSL Pinning 不做）：relay 由用户自建自配 URL（含局域网 ws），pin 会断连；
  main 的 `network_security_config.xml` 已是 `cleartextTrafficPermitted="false"`（v2.1 起）。
- V3（不加 pip-audit）：CI 已有 supply-chain job（gitleaks + osv-scanner），pip-audit 与
  osv-scanner 同源（OSV 数据库），冗余不加；依赖均为新版无已知高危。
- O2（`Backoff.kt` 无需改）：已有 0.85~1.15 乘法抖动 + 单测覆盖。
