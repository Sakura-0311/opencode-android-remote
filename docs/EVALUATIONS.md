# v2.4 评估结论（存储库 / R8）

## 1. security-crypto 弃用评估（v2.4 item 6）

**现状**：`androidx.security:security-crypto:1.1.0-alpha06`，用于 `EncryptedSharedPreferences`
（Secret / 云端 Key / AccountId）+ `MasterKey(AES256_GCM)`。

**核实**（2026-10-04，AndroidX 官方文档与搜索）：
- `EncryptedSharedPreferences`、`MasterKey`、`EncryptedFile` 已被官方标记 **deprecated**，
  新代码指引改为平台 API / 直接使用 Android Keystore。
- 1.1.0-alpha06 是多年未更新的 alpha，底层 Tink 1.8.0；
  社区有该版本在部分机型上启动时 `AEADBadTagException` 崩溃的报告。

**选项**：
| 方案 | 说明 |
| --- | --- |
| A. 锁定版本继续用 | 零改动；风险是无人维护的 alpha + 已知偶发崩溃 |
| B. 迁到 Tink 直连 | `com.google.crypto.tink:tink-android`，自管 keyset + Android Keystore；官方推荐方向 |
| C. 自研 Keystore AES-GCM 封装 | 依赖最少，但自己实现 envelope，易错 |

**结论（v3.2 已执行）**：按方案 B 迁移完成（tink-android 1.23.0）。v2.4 时本版不动，
迁移必须带：旧数据迁移（读旧 EncryptedSharedPreferences → 写新存储 → 校验后删旧）
与回滚（保留旧文件至少一个版本，迁移失败自动回退旧实现）。

**v5.0.2 收尾**：项目确认**尚无线上用户**，B3 的删除前置条件（legacy 计数归零）
不再适用，因此把整条迁移链一次性删除——
`androidx.security:security-crypto` 依赖、`SecureMigration`（含 6 项单测）、
`SecureBackend.LEGACY` 回退、legacy 使用计数、旧加密文件清理、迁移回退提示
（`main_011`/`main_012` 等 7 个字符串资源）全部移除。安全存储只剩
Tink AEAD + Android Keystore 一种实现，不可用时 fail-closed 拒绝写入。

**v5.0.2 依赖升级**：把停留在 2024 的依赖整体前移，但**不是取最新，而是取
「当前工具链能吃下的最新」**——我逐个读了候选版本 AAR 里的
`aar-metadata.properties`（`minCompileSdk` / `minAndroidGradlePluginVersion`），
因为再往上一档就要求 compileSdk 37 + AGP 9.1：

| 依赖 | 原 | 现 | 为什么不再往前 |
| --- | --- | --- | --- |
| Kotlin | 2.0.21 | **2.4.20** | — |
| AGP | 8.5.2 | **8.13.2** | 新 AndroidX 库要求 AGP ≥ 8.9.1；AGP 9.x 需 Gradle 9 整链迁移 |
| Gradle | 8.7 | **8.14.3** | AGP 8.13 要求 ≥ 8.13；Kotlin 2.5 起要求 ≥ 8.14.4（现为弃用警告） |
| Compose BOM | 2024.10.01 | **2026.05.01**（Compose 1.11.2） | 1.12.x 要求 minCompileSdk=37 + AGP ≥ 9.1.0 |
| core-ktx | 1.12.0 | **1.18.0** | 1.19.x 要求 37 / AGP 9.1 |
| lifecycle | 2.7.0 | **2.10.0** | 2.11.x 要求 37 / AGP 9.1 |
| OkHttp | 4.12.0 | **5.4.0** | 5.5.x 要求 compileSdk 37 |
| Coroutines | 1.7.3 | **1.11.0** | 纯 JVM 库，已到最新稳定版 |
| CameraX | 1.3.1 | **1.6.2** | — |
| ML Kit 条码 | 17.2.0 | **17.3.0** | — |
| ACRA | 5.11.3 | **5.13.1** | 5.14.x 要求 compileSdk 37 |
| Tink | 1.23.0 | 1.23.0 | 已是最大稳定版 |
| compileSdk / targetSdk | 35 | **36** | AGP 8.13 支持的上限 |
| JVM target | 1.8 | **17** | Kotlin 2.4 移除了 `kotlinOptions.jvmTarget` 字符串 DSL |

升级后 `verification-metadata.xml` 已重新生成（382 KB → 545 KB），
开启依赖校验的构建通过；单测 218 项全过。

## 2. R8 / minify 评估（v2.4 item 7）

**核实**（2026-10-04，解包各 AAR/JAR 实测）：
- OkHttp 4.12.0：自带 `META-INF/proguard/okhttp3.pro` ✓
- ACRA 5.11.3（acra-http AAR）：**无** consumer keep rules，需手写
 （如 `-keep class org.acra.** { *; }` 及 sender 相关类）
- ML Kit barcode-scanning：17.2.0 产物路径在 Maven Central 上 404，
  未能验证其 consumer rules（历史版本一般自带，但本次未证实）

**结论（v3.3 已重估并开启）**：
- ACRA 5.11.3：实测 AAR 自带 proguard.txt（含插件反射、枚举、ErrorReporter keep）——v2.4 的"缺规则"结论过时
- ML Kit barcode-scanning 17.2.0：实测 AAR 自带 proguard.txt（含 proto 字段、native 方法）——已证实
- tink-android 1.23.0：自带 META-INF/proguard/protobuf.pro（shaded protobuf 反射）
- OkHttp 4.12.0：自带（v2.4 已确认）
- 项目无反射、无 Gson 解析（org.json 手动解析），Compose 由 AGP 处理
- 唯一缺口：security-crypto（legacy 路径）无 consumer rules → 已在 proguard-rules.pro 手写 keep
- `isMinifyEnabled=true`，`shrinkResources=true`（v4.2.0 开启；此前保守关闭）；CI 加模拟器启动冒烟兜底；
  真机核心流程回归仍标"未验证"，见 TESTING_CHECKLIST 第 10 节。
