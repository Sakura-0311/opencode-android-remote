# v2 协议弃用公告（v3.5 起生效，v4.0 已执行 EOL）

## 状态（2026-10-04 更新）

v2 协议（无 hello 能力协商、单 desktop）已于 **v4.0 正式 EOL**：

- v4.0+ relay 直接拒绝无 hello 的 legacy 连接（`hello_required` + 4401）
- v4.0+ App 移除 legacy 降级逻辑；连 v2 relay 时报 `PROTOCOL_MISMATCH`
- v3.x relay / App 是最后的兼容版本（v3.5 为 v3 系列最终版）

## 历史记录（v3.5 维护模式时期）

- 不再新增功能，只修严重安全问题
- v3.x relay / App 继续兼容 v2（App 连 v2 relay 时自动降级 legacy，不阻断，一次性提示）
- **EOL 计划**：v4.0 发布后 4 周，relay 将拒绝无 hello 的 legacy 连接（以实际公告为准；
  本仓库为个人项目，4 周观察期为建议值，执行前会在此文档更新确切日期）

## 如何判断自己在用 v2

- relay 日志出现 `v3.5: legacy v2 客户端已连接（无 hello）`
- `GET /api/stats` 的 `connections.legacy_v2_total` 在增长
- App 弹出"服务端版本过旧"提示

## 升级步骤（v2 → v3.x）

顺序固定：**先 relay + agent，后 App**。

1. 备份：`relay_server/` 与 `desktop_agent/` 现有配置（`.env`、state 文件）
2. 更新 relay：拉取 v3.5+ 代码，重启 `relay_server/server.py`
3. 更新 agent：`desktop_agent/agent.py`（device_id 会自动生成存 `~/.config/opencode-remote/.desktop_device_id`）
4. App 覆盖安装 v3.5+ APK（同签名，数据保留）
5. 验证：手机连上后无"服务端版本过旧"提示；`/api/stats` 的 `legacy_v2_total` 不再增长

## 回滚

- relay/agent 回到旧代码重启即可；App 回到 v3.0.x
- 协议只加可选字段，无数据迁移，回滚不丢数据
