# 兼容性与签名记录（COMPATIBILITY.md）

> v2.2.1 起维护。每次发版核对本文件的签名指纹与 applicationId，**两者永不更改**。

## 应用标识

| 项 | 值 |
| --- | --- |
| applicationId | `com.opencode.android` |
| release 签名证书 SHA-256 | `D9:21:C0:EB:AE:4A:DD:89:73:0B:BD:E7:49:1F:27:FD:26:41:95:0C:E1:19:1F:30:53:A3:E7:66:00:9E:9E:78` |
| 签名方案 | APK Signature Scheme v2（v2.0 起的新密钥；v1.x 为旧密钥，已作废） |

核对方法（任选其一）：

```bash
# 有 Android SDK 时
apksigner verify --print-certs app-release.apk
# 无 SDK 时（仓库 scripts/ 下有解析脚本思路，见 v2.2.1 开发记录）
```

覆盖升级规则：只有 **applicationId + 签名证书** 都一致，Android 才允许覆盖安装。
v2.0 换过一次密钥（v1.x 用户必须卸载重装）；**v2.x 之间永不再换**。

## versionCode 规则

`versionCode = major*10000 + minor*100 + patch`，严格递增。

| 版本 | versionCode |
| --- | --- |
| 2.2.0 | 20200 |
| 2.2.1 | 20201 |
| 2.3.0 | 20300 |
| 2.4.0 | 20400 |
| 2.5.0 | 20500 |
| 2.6.0 | 20600 |
| 3.0.0 | 30000 |
| 3.0.1 | 30001 |
| 3.0.2 | 30002 |
| 3.1.0 | 30100 |
| 3.2.0 | 30200 |
| 3.3.0 | 30300 |

## 协议兼容（v2.2.1）

- 只加可选字段：`auth_ok` / `seq_sync` 新增 `room_epoch`（字符串，可空）。旧 App 忽略未知字段；新 App 连旧 relay（无 epoch）时保持旧行为。
- `file_list_result` 新增 `roots`（允许根目录列表）；旧 App 忽略。
- 错误码新增 `PATH_NOT_ALLOWED`（文件沙盒拒绝）；旧 App 会显示为普通错误文案，不影响其他功能。
- v2.3：`send_prompt` / `cancel` 信封新增可选 `client_msg_id`；agent 可能回复 `duplicate_ignored`（旧 App 忽略未知 type）。
- v2.4：`revoke_device` / `rename_device` 改按 `device_id`（仍兼容 `device_name`）；`device_list` / `pair_success` 新增 `device_id`（旧 App 忽略）。
- v3.1：新增 capability `desktop_routing`。`send_prompt` 信封新增可选 `target_device_id`（缺省走主 desktop）；desktop→mobile 消息新增可选 `source_device_id`；新增 `list_desktops` → `desktop_list` 查询在线 desktop。旧 App 忽略未知字段；旧 relay 不识别 `target_device_id` 时 App 通过 `serverSupports(desktop_routing)` 退回主路由。

## 存储兼容

- Android：只增不改 key。新增 `last_relay_seq_<hash>_<account>_<uuid>_epoch`（序号纪元）。
- Relay：`~/.config/opencode-remote/relay_state.json`（0600，schema_version=1），只存设备密钥哈希与主密钥哈希。
- Agent：主 Secret 默认位置从启动目录 `.opencode_secret` 迁移到 `~/.config/opencode-remote/.opencode_secret`；旧位置有有效密钥时自动迁移（目录 0700 / 文件 0600）。
- v2.3：`PreferencesManager` 新增 `schema_version`（当前 1），只增不改 key。

## 发布顺序

先 relay/agent，后 App。新版 relay/agent 必须兼容旧 App（见上）。
