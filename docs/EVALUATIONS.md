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
