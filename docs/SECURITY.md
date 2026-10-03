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
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | 受信任的反向代理 IP（逗号分隔）。只有来自这些地址的连接才信任 `X-Forwarded-For`。relay 放在 Docker/反代后面时必须配置，否则所有用户共用一个 IP，一个人输错 5 次会导致所有人被封 15 分钟 |
| `RELAY_STATE_FILE` | `~/.config/opencode-remote/relay_state.json` | 设备密钥持久化文件（0600） |
| `RELAY_ENABLE_CRASH_REPORT` | `0` | `1` 才开启 `/api/crash-report` 崩溃上报接收 |
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
```

## 数据边界

- 中继服务器在内存中可见转发的指令与响应内容（传输层 TLS 加密，非端到端加密）。**只用自己部署的 relay**。
- 崩溃上报默认关闭；开启后仅收集脱敏字段（版本/机型/Android 版本/堆栈/时间），无 logcat、无设备 ID。
- 文件日志（AppLog）已脱敏：不含 Secret、口令、token、prompt 与代码正文。
- `allowBackup=false`：密钥不随系统云备份泄露。
