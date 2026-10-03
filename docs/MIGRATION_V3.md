# v3.0 迁移指南（MIGRATION_V3）

v3.0 是架构版本：协议加了 `hello`/`hello_ack` 能力协商，relay 支持多 desktop 共存，
App 端网络层拆出 `Transport` 抽象。本文说明升级顺序、兼容矩阵与回滚路径。

## 升级顺序（重要）

1. **先升级服务端**：`relay_server/server.py` 部署到 v3.0，电脑端 `desktop_agent/` 更新到 v3.0 并重启。
2. **再升级 App**：安装 v3.0 APK（与 v2.x 同签名，可直接覆盖安装，数据保留）。

顺序反了会怎样：v3 App 连 v2 relay，hello 无响应会被 4401 关闭，
App 会显示「协议版本不匹配（PROTOCOL_MISMATCH）」，不会静默失败。

## 兼容矩阵

| 客户端 \ 服务端 | v2.x relay | v3.0 relay |
|---|---|---|
| v2.x App / agent | ✅ 正常 | ✅ 正常（无 hello，直接 auth；多 desktop 退化为旧"顶替"逻辑） |
| v3.0 App / agent | ❌ PROTOCOL_MISMATCH（需先升级服务端） | ✅ 完整能力 |

- 协议只加可选字段：`auth_ok` 新增的 `v` / `server_capabilities` 旧客户端忽略。
- `hello` 是可选握手：v2 客户端不发 hello，服务端按旧流程直接处理 auth。

## 多 desktop 语义（v3.0 新行为）

- 同一 `account_id` 下多个 desktop 按 `device_id` 区分，可同时在线。
- 只有**同 device_id** 的新连接才会顶替旧连接（v2 是"后来者顶替一切"）。
- `device_id` 由各端自动生成并持久化：
  - agent：`~/.config/opencode-remote/.desktop_device_id`
  - App：沿用既有 `device_uuid_v1`
- mobile 发出的消息路由到**主 desktop**（最近认证/活跃的那台）。
- v2 agent 连 v3 relay：`device_id` 为空，归入 `"legacy"` 键，行为与 v2 一致。

## 回滚

- **只回滚 App**：重装 v2.6.0 APK 即可（同签名，覆盖安装，聊天记录/配对信息保留）。
  注意 v3 写入的新存储 key（如有）会被旧版忽略，不影响使用。
- **回滚服务端**：用 git checkout v2.6.0 的 `relay_server/` 与 `desktop_agent/` 重启。
  v3 App 在 v2 服务端下无法连接（会明确提示升级服务端），请同步回滚 App。
- **回滚 agent**：同上，checkout v2.6.0 的 `desktop_agent/` 重启即可。

## 能力协商（hello/hello_ack）

客户端 hello：
```json
{"type": "hello", "v": 3, "capabilities": ["hello", "write_idempotency", "..."], "device_id": "..."}
```
服务端 hello_ack：
```json
{"type": "hello_ack", "v": 3, "server_capabilities": ["hello", "multi_desktop", "device_id_revoke", "room_buffer", "pairing"]}
```

当前服务端能力（`relay_server/server.py` 的 `SERVER_CAPABILITIES`）：
- `hello` — hello/hello_ack 协商
- `multi_desktop` — 多 desktop 共存
- `device_id_revoke` — 按 device_id 撤销（v2.4 引入）
- `room_buffer` — 房间消息缓冲与断线补发（v1.6 引入）
- `pairing` — 扫码配对（v1.6 引入）

客户端可通过 `serverSupports(cap)`（Android）判断服务端能力，
做渐进增强；v3 服务端五项全支持。
