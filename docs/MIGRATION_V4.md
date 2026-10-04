# MIGRATION_V4（v4.0 正式版，2026-10-04）

> v4.0 是**唯一破坏性版本**。本文档为正式版迁移指南。

## 破坏性变更清单

| # | 变更 | 影响 |
|---|------|------|
| 1 | 协议 `v=4`：`hello` 变为**必需** | 无 hello 的 legacy（v2）连接被 relay 直接拒绝（4401） |
| 2 | 移除 legacy 路径 | relay 删除无 hello 的 auth 分支、legacy 计数器；App 删除 legacyMode 降级逻辑 |
| 3 | 旧版 App（v3.x）连 v4 relay | 可连接（v3 App 会发 hello），功能正常；但 v2 App 无法连接 |

## 升级步骤（v3.x → v4.0）

顺序固定：**先 relay + agent，后 App**。

1. 确认无 legacy 流量：`GET /api/stats` 的 `connections.legacy_v2_total` 连续 4 周为 0
   （个人项目可按实际观察缩短，如实记录）
2. 备份 relay/agent 配置与 state 文件
3. 更新 relay 到 v4.0 并重启；更新 agent 到 v4.0
4. App 覆盖安装 v4.0 APK（同签名，数据保留）
5. 验证：`/api/stats` 的 `protocol_version` 为 4；手机正常收发；多 desktop 路由正常

## 回滚方案

- relay/agent 回到 v3.5 代码重启；App 回到 v3.5.x
- v4 不做数据格式变更，回滚不丢数据
- 若 App 已升 v4 而 relay 仍是 v3.5：可正常工作（v4 App 兼容 v3 relay 的 hello 流程）

## 不做的事

- E2EE：暂缓（v4.0 稳定后 + 明确威胁模型才重估）
- applicationId / 签名：永不更改
