# Android 端硬编码文案清单（i18n 收尾）

> 生成于 v5.0.1（脚本：`scripts/i18n_inventory.py`）。RELEASE_NOTES 的 v4.3.0 条目称
> 「561 条界面文案全部抽取到多语言资源」、v4.10.0 称「十语言补齐」，但实测 Kotlin
> 代码里仍有 **38 处**含中文的字符串字面量（已排除注释行）——这些文案不跟随语言切换，
> 字面量（已排除注释行）——这些文案不跟随语言切换，漏掉的 `contentDescription` 还会
> 影响无障碍。
>
> 本机没有 Android SDK/JDK，无法编译验证 Kotlin 改动，所以这里**只给清单**，
> 具体替换请在 Android Studio 里做（有实时预览与 Lint）。

## 建议做法

1. 逐条移入 `res/values/strings.xml`，并在其余 9 个 `values-*` 目录补译文。
2. Composable 里用 `stringResource(R.string.xxx)`，非 Composable 用 `context.getString(...)`。
3. 先判断是否**用户可见**：纯技术字符串（内部 tag、错误码前缀、正则、日志）不要搬进资源。
4. 屏幕阅读器用的 `contentDescription` 同样要资源化。
5. 改完跑 `gradle :app:lintDebug`（`MissingTranslation` / `HardcodedText` 会报出来）。

## 按文件统计

| 文件 | 处数 |
| --- | --- |
| `app/src/main/java/com/opencode/android/security/E2eeManager.kt` | 13 |
| `app/src/main/java/com/opencode/android/network/RelayWebSocketClient.kt` | 9 |
| `app/src/main/java/com/opencode/android/data/local/PreferencesManager.kt` | 5 |
| `app/src/main/java/com/opencode/android/viewmodel/OpenCodeViewModel.kt` | 3 |
| `app/src/main/java/com/opencode/android/network/RelayMessageParser.kt` | 2 |
| `app/src/main/java/com/opencode/android/util/LocaleHelper.kt` | 2 |
| `app/src/main/java/com/opencode/android/coordinator/PairingCoordinator.kt` | 1 |
| `app/src/main/java/com/opencode/android/data/local/TinkAeadStore.kt` | 1 |
| `app/src/main/java/com/opencode/android/data/local/TinkKeyManager.kt` | 1 |
| `app/src/main/java/com/opencode/android/network/CloudApiClient.kt` | 1 |

## 明细

### `app/src/main/java/com/opencode/android/security/E2eeManager.kt`

| 行 | 文案 |
| --- | --- |
| 64 | `ownPublicKeyB64 失败: ${e.message}` |
| 81 | `对端未提供公钥认证签名（旧版本），TOFU 保存` |
| 86 | `签名计算失败: ${e.message}` |
| 89 | `公钥签名校验失败，拒绝保存（疑似中继篡改）` |
| 92 | `$deviceId 的公钥已通过 HMAC 绑定认证` |
| 95 | `扫码配对：公钥走 TOFU 保存（无主 secret 可认证）` |
| 99 | `已保存 $deviceId 的公钥` |
| 102 | `保存对端公钥失败: ${e.message}` |
| 159 | `加密失败（fail-closed，不回退明文）: ${e.message}` |
| 183 | `d2m 序号非法/重放（seq=$seq, last=$last），丢弃` |
| 189 | `解密失败: ${e.message}` |
| 221 | `加密失败（fail-closed，不回退明文）: ${e.message}` |
| 232 | `清理对端公钥失败 deviceId=$deviceId: ${e.message}` |

### `app/src/main/java/com/opencode/android/network/RelayWebSocketClient.kt`

| 行 | 文案 |
| --- | --- |
| 424 | `解析异常: ${e.message}` |
| 430 | `v4.1 E2EE: 已解密来自 $it 的消息` |
| 433 | `v4.1 E2EE: 解密后 JSON 解析失败: ${outcome.error}` |
| 435 | `v4.1 E2EE: 解密失败，丢弃该消息` |
| 547 | `消息 JSON 解析失败: ${outcome.error}` |
| 926 | `消息分发异常兜底: ${e.message}` |
| 1097 | `sendPrompt 丢弃空消息 promptBlank=${prompt.isBlank()} sessionBlank=${sessionId.isBlank()}` |
| 1127 | `发送中止：${payloadResult.failure}` |
| 1149 | `v4.1 E2EE: send_prompt 已加密 -> $effectiveTarget` |

### `app/src/main/java/com/opencode/android/data/local/PreferencesManager.kt`

| 行 | 文案 |
| --- | --- |
| 41 | `Tink 初始化失败，敏感凭据将被拒绝保存（fail-closed）` |
| 87 | `安全存储不可用，拒绝保存 E2EE 私钥` |
| 95 | `安全存储不可用，拒绝保存 E2EE 对端公钥` |
| 207 | `P0-3: 拒绝保存配对凭据——加密存储不可用` |
| 242 | `P0-3: 拒绝保存云端凭据——加密存储不可用` |

### `app/src/main/java/com/opencode/android/viewmodel/OpenCodeViewModel.kt`

| 行 | 文案 |
| --- | --- |
| 505 | `断开 relay 失败: ${e.message}` |
| 510 | `取消云端流失败: ${e.message}` |
| 1030 | `开关: $enabled` |

### `app/src/main/java/com/opencode/android/network/RelayMessageParser.kt`

| 行 | 文案 |
| --- | --- |
| 31 | `JSON 解析失败` |
| 43 | `内层 JSON 解析失败` |

### `app/src/main/java/com/opencode/android/util/LocaleHelper.kt`

| 行 | 文案 |
| --- | --- |
| 20 | `中文（简体）` |
| 22 | `日本語` |

### `app/src/main/java/com/opencode/android/coordinator/PairingCoordinator.kt`

| 行 | 文案 |
| --- | --- |
| 125 | `对端公钥认证失败，已拒绝（疑似中继篡改）` |

### `app/src/main/java/com/opencode/android/data/local/TinkAeadStore.kt`

| 行 | 文案 |
| --- | --- |
| 43 | `解密失败` |

### `app/src/main/java/com/opencode/android/data/local/TinkKeyManager.kt`

| 行 | 文案 |
| --- | --- |
| 39 | `Tink AEAD 自检失败` |

### `app/src/main/java/com/opencode/android/network/CloudApiClient.kt`

| 行 | 文案 |
| --- | --- |
| 438 | `事件解析失败: ${e.message}` |

---

## 分类结论（v5.0.2 复核，第五轮更新）

上面的清单是机械匹配「含中文的字符串字面量」，逐条看过后**大部分不是 i18n 缺陷**。

### A. 已修复

| 位置 | 处理 |
| --- | --- |
| `ConnectionDiagnoseDialog.kt` 诊断导出 12 处 | `diag_n02`–`diag_n13`（10 语种），`未配置` 复用 `diag_n01` |
| `RelayWebSocketClient.kt` E2EE 失败提示 | `relay_n01` |
| `E2eeManager.kt` 9 条失败原因 | 改为错误码 `Failure` 枚举 + `e2ee_n01`–`e2ee_n04`，文案集中在 `RelayWebSocketClient.e2eeFailureText()` |
| 界面残留 16 处硬编码（英文 Text 标签 + contentDescription） | `chat_n01`–`n09`、`model_n03`–`n05`、`pair_n01`–`n03` |

### B. 不应本地化（保持原样）

| 位置 | 理由 |
| --- | --- |
| `PreferencesManager.kt`、`TinkAeadStore.kt`、`TinkKeyManager.kt`、`RelayMessageParser.kt`、`E2eeManager.kt` 的 Log 文案 | 只进 logcat；release 还用 `-assumenosideeffects` 剥掉了 Log.d/v |
| `LocaleHelper.kt` 语言名 | 按惯例以**自身语言**显示 |
| `OpenCodeViewModel.kt` `开关: $enabled` | 日志 |
| markdown 列表符号 `• `/`1. `、`/workspace` 默认路径 | 不是文案 |

### C. 仍未处理的（需要接口改动）

- `PairingCoordinator.kt` 的配对失败提示：需要把资源解析下沉到
  `PairingStateReducer` 层（与 E2eeManager 同一类问题，改法已在上方确立，照做即可）。

字面量计数：74 → 61 → **38**，剩余项全部有归类结论。
