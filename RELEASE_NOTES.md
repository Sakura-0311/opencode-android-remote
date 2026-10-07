# OpenCode Android Remote - Release v5.1.0（优化方案落地批次）

## 说明

v5.1.0 按 `OpenCode_Android_Remote_v4.8.0_进一步优化与迭代方案.docx` 的对照表，
在 v5.0.3 基础上补齐仍未落地的优化项。协议 v4 既有帧语义与字段未改动。

## 本次落地项（对照优化方案）

### §3.2 连接质量指标
- 新增 `ConnectionQuality` 枚举（GOOD / FAIR / POOR / UNKNOWN），
  由最近一次 ping 往返延迟推导（<150ms 良好 / 150~499ms 一般 / ≥500ms 较差）。
- `OpenCodeUiState` 新增 `connectionQuality` 字段，`onLatencyMeasured` 同步更新。
- 诊断中心「WebSocket/鉴权」层追加连接质量分级文案。
- 单测 `ConnectionQualityTest` 覆盖阈值边界。

### §8 诊断中心增强
- 新增「DNS 解析」检测层：异步解析目标 host（IO 线程），区分成功/失败/未配置/解析中。
- 新增「最近错误」层：展示最近一条错误码与信息，以及错误记录总数。
- 一键复制诊断信息追加「连接质量」与「最近错误数」两项。
- `OpenCodeUiState` 新增 `recentErrors: List<AppError>` 环形缓冲（最多 10 条），
  所有错误入口（transport/auth/app/network）统一写入。

### §9 远程任务队列生命周期
- `OpenCodeUiState` 新增 `taskId` 与 `taskCreatedAtMs` 字段；
  任务开始（`startTaskUi`）时生成 UUID 并记录创建时间，供诊断与恢复使用。

### §7.3 配置版本化迁移机制
- `PreferencesManager` 新增 `CONFIG_VERSION` 常量与 `runMigrations()` 方法；
  迁移注册表 `MIGRATIONS` 支持按版本顺序执行、单步失败不阻断、最终推进版本号。
- `OpenCodeApp.onCreate` 在读取任何偏好前调用 `runMigrations()`，幂等安全。

## 验证
- 10 个 locale 的 `strings.xml` 均为合法 XML、key 集合一致（650 键）。
- 新增 13 个 `diag_*` 文案键覆盖 10 语种。
- 静态校验：修改文件括号/花括号配平；新增字段均有默认值，不破坏既有调用点。
- Android 侧未编译（本环境无 Android SDK），请按本地环境跑
  `./gradlew :app:testDebugUnitTest` 与 `:app:assembleDebug` 验证。

---

# OpenCode Android Remote - Release v5.0.3（优化审查批次）

## 说明

v5.0.3 按 `docs/OPTIMIZATION_v5.0.3.md` 的对照表落实 v5.0.2 的优化审查报告
（P0 + P1 + P2 + P3）。**协议 v4 既有帧语义与字段未改动**，唯一新增是附加帧
`approval_rejected`（旧版 App 直接忽略）。

## 重点

- **A-1（P0）开启 E2EE 后审批/取消/新建会话/文件浏览全部失效**：控制类消息
  此前只有 `send_prompt` 走加密，其余五类发明文，被电脑端静默丢弃。现在六类
  统一经 `sendControl` 出口，加密失败即拒发。另修了一个报告未发现的阻塞点：
  多桌面路由关闭时 relay 不下发 `desktop_list`，加密目标原本恒解析不出来，
  E2EE 实际等于没开——现在按「显式目标 → 主 desktop → 最近协商过的对端」
  三级回退。
- **A-2（P0）点通知清空当前聊天**：切到同一会话不再清屏。
- **B-7（P1）反代部署下一个人输错密码封所有人 15 分钟**：封禁改按
  `(IP, account_id)` 记账；反代提示的判据修回「未配置 `TRUSTED_PROXIES`」。
- **B-1（B-3/B-4/B-5/B-6）**：退避归零移到 `auth_ok` 并加最大重试次数；
  审批过期倒计时 + 被拒反馈；发送结果三态（只有真发出才算「运行中」）；
  分发层容错；聊天记录本地缓存。
- 新增 relay 统计接口可选鉴权（`RELAY_STATS_TOKEN`）、共享 OkHttpClient、
  `collectAsStateWithLifecycle`、`versionCode` 撞号守卫；删除不可达的旧流式路径。

## 验证

Python 侧 12 个套件 192 项全过（含 2 个新增套件），v4 协议契约端到端测试
25/25 全过。**Android 侧未编译**——本次改动环境无 Java/Android SDK，
编译与真机验证清单见 `docs/OPTIMIZATION_v5.0.3.md` 第 3 节。

---

# OpenCode Android Remote - Release v5.0.2（安全修复 + 工程质量）

## 说明

v5.0.2 是在 v5.0.0 冻结承诺（协议 v4 / API / 配置格式不变）下的一轮修复：
不改协议、不改线格式，只修缺陷、补测试、补文档。

## 内容

### 工具审批链路（此前很可能整体不工作）
- 上游真实事件是 `permission.updated`，properties 为扁平
  `Permission{id,type,pattern?,title,metadata}`；原实现只认
  `permission.asked/request/permission`，且去读根本不存在的
  `tool.name/diff/file_path` → 手机端不弹审批框，或弹出无内容的框让用户盲签。
- 现在解析抽成纯函数 `parse_permission_event()`，兼容新旧结构，
  从 `title` / `metadata.command` / `pattern` 取真实内容；审批卡新增
  `command`/`patterns` 字段（旧 App 忽略）。
- 增量输出补上游真实事件名 `message.part.updated`（原实现只认 `message.part.delta`）。

### E2EE：不再有任何静默降级明文
- 新增 `E2EEUnavailable` 与统一出口 `send_d2m_secure()`：加密不可用时**拒发**并
  回报 `E2EE_UNAVAILABLE`，绝不降级明文。此前多对端会 `return msg` 直接发明文，
  流式 chunk 加密失败也只 warning 后照发。
- `E2EE_ENABLED=1` 但缺少 `cryptography` 时改为**拒绝启动**（此前只打 warning 就继续跑明文）。
- **多手机 E2EE 真正可用**：relay 支持 d2m `target_device_id` 定向投递
  （无 target 仍广播，旧客户端不受影响），desktop 为每个对端各加密一份、
  各自独立 seq；relay 的断线补发按目标过滤，不会把别人的密文还给这台手机。

### relay 加固
- `TRUSTED_PROXIES` 默认改为**空**（不信任任何代理）：此前默认信任回环地址，
  配合「只绑回环」的默认部署，本机任意进程可自带 `X-Forwarded-For` 伪造来源 IP，
  既绕过限流，也能反向把正常用户封禁 15 分钟。
- 新增 `RELAY_ALLOWED_ORIGINS`：原生 App 不发 `Origin` 不受影响；带 `Origin` 的
  浏览器连接必须在白名单内（防任意网页直连本机 relay）。
- 控制类消息（`create_pairing`/`list_devices`/…）此前完全不受限速，现补上。
- 修复 `--trusted-proxies` 参数完全失效（只改 `__main__` 的 globals，
  而 uvicorn 会再 import 一份 server 模块对外服务）。

### SSE
- 按「帧」解析而非按行：多行 `data:` 会被拼成一个事件，此前逐行 `json.loads`
  导致整帧被静默吞掉且无日志；解析失败改为 warning。

### Android
- 关闭 release 后门：测试用假审批入口（`triggerMockToolApprovalForTest()`）
  此前在 release 菜单里也能点到，现加 `BuildConfig.DEBUG` 门控 + 方法内守卫。
- 修复多 profile 凭据串号：`getProfileSecret` 无条件回退全局密钥，
  导致切到未配对的新 profile 时把上一个房间 secret 发往新 relayUrl；
  现在只在「该 profile 就是旧的单配置本身」时回退。
- 诊断信息导出、E2EE 失败提示改为走资源，10 语种补齐（key 数 605 → 630，
  语种间 key 集合一致）。
- E2EE 失败原因从「中文字符串常量」改为**错误码枚举**（`E2eeManager.Failure`），
  由持有 Context 的 `RelayWebSocketClient` 映射到资源——安全类不再携带用户文案。
- 清掉界面残留的 16 处硬编码文案：7 个 ChatScreen contentDescription、
  ModelAgentDialog 的标题与两个 Tab、PairingScreen 与 SessionsManagementModal
  的 contentDescription（无障碍朗读此前固定英文）。
- 三处静默吞异常补日志：切换 profile 的断连失败、CloudApiClient 的 SSE 帧解析失败、
  撤销设备时清理公钥失败。
- 配对流程里最后一处用户可见硬编码文案（`PairingCoordinator` 的「对端公钥认证失败，
  已拒绝（疑似中继篡改）」）改走资源 `vm_n01`——该文件本来就用
  `dispatch.getString(...)`，这处属于漏用；Reducer 的「文案由调用方传入」红线未动。

### 构建与工程
- **依赖整体升级（停留在 2024 → 当前工具链能吃下的最新）**。
  版本不是「取最新」，而是逐个读候选版本 AAR 里的 `aar-metadata.properties`
  （`minCompileSdk` / `minAndroidGradlePluginVersion`）定出来的，因为再上一档就要求
  compileSdk 37 + AGP 9.1：

  | 依赖 | 原 → 现 | 上限原因 |
  | --- | --- | --- |
  | Kotlin | 2.0.21 → **2.4.20** | — |
  | AGP | 8.5.2 → **8.13.2** | 新 AndroidX 库要求 AGP ≥ 8.9.1 |
  | Gradle | 8.7 → **8.14.3** | AGP 8.13 要求 ≥ 8.13 |
  | Compose BOM | 2024.10.01 → **2026.05.01**（Compose 1.11.2） | 1.12.x 要求 minCompileSdk 37 + AGP 9.1 |
  | core-ktx | 1.12.0 → **1.18.0** | 1.19.x 要求 37 / AGP 9.1 |
  | lifecycle | 2.7.0 → **2.10.0** | 2.11.x 要求 37 / AGP 9.1 |
  | OkHttp | 4.12.0 → **5.4.0** | 5.5.x 要求 compileSdk 37 |
  | Coroutines | 1.7.3 → **1.11.0** | 已是最新稳定版 |
  | CameraX | 1.3.1 → **1.6.2** / ML Kit 17.2.0 → **17.3.0** / ACRA 5.11.3 → **5.13.1** | ACRA 5.14.x 要求 37 |
  | Tink | 1.23.0（不变） | 已是最大稳定版 |

  配套变更：`compileSdk`/`targetSdk` 35 → **36**；JVM target 1.8 → **17**
  （Kotlin 2.4 移除了 `kotlinOptions.jvmTarget` 字符串 DSL，改用 `compilerOptions`）；
  CI 与 Wrapper 的 Gradle 同步到 8.14.3；`verification-metadata.xml` 重新生成
  （382 KB → 545 KB）。
  注：debug APK 体积 40.3 → 47.0 MB（新库更大；release 走 R8 不受此影响）。
  想继续升到 Compose 1.12 / core 1.19 / okhttp 5.5，前提是把 **AGP 提到 9.1+、
  Gradle 提到 9.x、compileSdk 提到 37**，那是整链迁移，单独一轮做。
- **删除全部遗留迁移层**（项目尚无线上用户，无存量数据需要迁移）：
  - 移除 `androidx.security:security-crypto` 依赖与整套 `EncryptedSharedPreferences` 后端；
  - 删除 `SecureMigration`（含 6 项单测，单测总数 224 → 218）、`SecureBackend.LEGACY`
    回退分支、legacy 使用计数、旧加密文件清理、迁移回退用户提示；
  - 删除「旧单配置 → 首个 profile」的 profile 迁移、旧中文会话标签迁移映射表、
    桌面端旧位置 secret 迁移；
  - 安全存储只剩一种实现：Tink AEAD + Android Keystore，不可用时 fail-closed 拒绝写入；
  - 随之删除 7 个不再使用的字符串资源（10 语种同步，key 数 618 → 611）。
- 补上 **Gradle Wrapper**（此前无 wrapper，构建依赖机器上装的 gradle，不可复现）。
- 新增 **`.gitattributes`**：脚本/配置强制 LF（此前 Windows 检出会让 shell 脚本变 CRLF）。
- 修复 `verification-metadata.xml` 只覆盖 Linux 导致 **Windows 构建必然失败**
  （缺 `aapt2-…-windows.jar` 校验和）；已合并 Windows 条目，linux 条目保留。
- CI 补跑此前遗漏的测试：`test_e2ee.py`、`test_crash_report.py`、
  `test_hardening.py`、`test_sse_parser.py`、`test_targeted_fanout.py`、`test_security.py`。
- `e2e.yml` 补 `RELAY_ADMIN_TOKEN`（relay 自 v4.7.0 起缺它必然 `SystemExit(2)`，
  该 workflow 一直红）；`emulator-e2ee.yml` 去掉起不来的 relay 步骤。
- 修掉 `tests/e2ee/e2ee_protocol_test.py`（自己复制了一份加密实现、从不 import
  真实模块的自欺测试）与 `scripts/test_contract_v4.py` 的间歇性假失败。

## 兼容性

- 非破坏性；协议 v4 / 线格式 / 配置格式均未变。
- 行为变更（有意）：
  - 多手机房间的 E2EE 内容型消息改为**定向投递**，其他手机不再收到自己解不开的密文；
  - `TRUSTED_PROXIES` 默认值语义变更（反代部署需显式配置）；
  - 带 `Origin` 的浏览器连接默认被拒。

## 验证

- Python：12/12 测试通过（含 relay 集成契约 25/25）、`ruff F821/F811` 通过。
- Android：`gradle :app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL
  （依赖校验开启），单测 224 项 0 失败。

---

# OpenCode Android Remote - Release v5.0.0（长期维护版本）

## 说明

v5.0.0 是架构稳定里程碑。此版本起协议 v4、API 与配置格式冻结，
后续只做兼容性修复与安全更新，不再做破坏性变更。

## 本轮路线（v4.9.0 → v5.0.0）

- v4.9.0：诊断中心一键复制、延迟测量
- v4.10.0：出站消息队列（断线排队、重连补发）
- v4.11.0：防截屏录屏（FLAG_SECURE）
- v5.0.0：版本号里程碑，长期维护版本

## 兼容性

- 非破坏性。versionCode 50000
- 协议 v4 稳定；relay / agent / App 三端版本对齐

## 验证

- CI 全绿

---

# OpenCode Android Remote - Release v4.11.0（安全强化）

## 内容

- **防截屏/录屏**：MainActivity 加 FLAG_SECURE，App 可远程执行代码、
  显示密钥与配对二维码，默认禁止截屏录屏防泄露

## 兼容性

- 非破坏性。versionCode 41100

## 验证

- CI 全绿

---

# OpenCode Android Remote - Release v4.10.0（发送队列）

## 内容

- **出站消息队列**：断线时用户操作（发消息、审批、建会话、取消）自动排队，
  不再丢消息；鉴权成功后按序补发；队列上限 50 条、5 分钟过期
- **排队提示**：排队时聊天区显示系统提示，重连补发自动进行
- **vm_040**：十语言补齐

## 兼容性

- 非破坏性。versionCode 41000

## 验证

- CI 全绿

---

# OpenCode Android Remote - Release v4.9.0（诊断中心）

## 内容

- **一键复制诊断信息**：诊断弹窗加「复制诊断信息」按钮，文本含 App 版本、
  连接模式、服务器 host（脱敏）、连接状态、延迟、最近错误码、设备信息；
  不含 secret / API key
- **延迟测量**：应用层 ping/pong 测往返延迟，诊断弹窗显示「延迟：xx ms」
- **10 语言**：diag_037–039 全补齐

## 兼容性

- 非破坏性。versionCode 40900

## 验证

- CI 全绿

---

# OpenCode Android Remote - Release v4.8.0（Android 质量）

## 内容

- **明文连接明示**（M1）：配对页输入 `ws://` / `http://` 显示红色警告；
  release 包连接前弹二次确认框；debug 包直接连（本地调试）
- **翻译补齐**（M2）：9 语言 × 36 条（崩溃上报、错误码 err_061–084、明文警告），
  缺失 0 条
- **依赖升级**（M3）：desktop_agent 依赖锁定到 PyPI 最新（cryptography 50.0.2、
  qrcode 8.2、keyring 25.7.0）；relay 早已锁定；Android 工具链 v4.5.0 刚升过
- **security-crypto 评估**（M4）：继续保留——仍是 Tink 迁移源与回退后端，
  B3 删除条件未满足
- **MarkdownText**（M5）：代码高亮内联正则提为文件级常量
- **UpdateChecker**（M6）：HttpURLConnection 改 OkHttp
- **相机可选**（M7）：`uses-feature camera required=false`，无摄像头设备可安装
- **ViewModel 继续拆**（M8）：诊断/日志区抽为 `DiagnosticsCoordinator` + 4 个单测
- **文档对齐**（E2）：README 安全须知补建房令牌/secret 打码/明文警告；
  PRIVACY 更新日期、Tink 存储说明、相机可选说明
- **CI 补 handler 测试**（E3）：`relay_server/test_handlers.py`（路由 6 项）
- **icon_concepts 移出仓库**（E4）：git rm + .gitignore

## 兼容性

- 非破坏性。versionCode 40800

## 验证

- CI 全绿；Python 单测全过；翻译缺失 0 条；emulator-smoke 待跑

---

# OpenCode Android Remote - Release v4.7.0（安全默认值与运维）

## 内容

- **建房令牌强制**（P1-6）：未设置/占位符/短于 16 字符则 relay 拒绝启动；
  `.env.example` 留空附生成命令；agent 新增 `--admin-token` 参数
- **Secret 不再明文打印**（P1-7）：启动 banner 默认打码，仅首次生成或
  `pair --show-secret` 时打印；新增 `--secret-file`（`--secret` 会进进程列表）；
  README 修正 128 bit
- **relay 加固**（R1–R9）：扇出 gather+超时（慢连接断开）；单帧上限 2 MiB +
  每会话 100 条/10 秒限速；mobile→desktop 无 target 时跳过解析；设备按
  device_id 识别、重名自动加后缀；存盘节流 + 90 天未活跃设备清理；
  XFF 取最右侧可信地址；握手类型校验 + 超时计入失败；版本号统一来源；
  lifespan 替代 on_event，任务引用防回收
- **部署**（D1–D3）：compose 默认绑 127.0.0.1；Dockerfile 改 `python server.py`
  启动；cloud_server README 对齐现实 + 暴力破解防护指引
- **agent 质量**（A1–A8）：SSE 游标节流 + 放配置目录；空注册表告警 +
  `AGENT_STRICT_SESSION_GUARD`；黑名单大小写不敏感 + O_NOFOLLOW；
  e2ee 密钥原子 0600 创建 + keyring 优先；`secrets.py` 改名 `keystore.py`；
  依赖锁定；SSE 指数退避；.gitignore 补运行时文件

## 兼容性

- relay：无令牌的旧部署启动会失败，需先配 `RELAY_ADMIN_TOKEN`（破坏性默认值变更，
  按路线图要求执行）
- 其余非破坏性。versionCode 40700

## 验证

- CI 全绿；Python 单测（sandbox/e2ee/e2ee_v2）全过；relay 导入 + 令牌强制本地验证

---

# OpenCode Android Remote - Release v4.6.0（E2EE 互操作修复）

## 内容

- AAD 身份统一：relay 的 `device_paired` 带手机 relay device_id，
  desktop 以它做 peer id 和 AAD
- 内层格式统一为 JSON v2：m2d `{action,payload,seq}`，d2m `{type,...,seq}`
- 序号按对端、按方向独立计数，落盘持久化，防重放
- 协商对端后控制类消息无合法信封一律拒绝（fail-closed）
- d2m 加密覆盖扩大到审批请求、文件内容/目录列表
- 互操作向量 `tests/e2ee/interop_vectors.json`（Kotlin/Python 共用）+
  真实代码整链 19 项
- 威胁模型文档如实更新保护范围

## 兼容性

- E2EE 默认关闭，此前实际不可用，无迁移成本；开启后需重新配对

## 验证

- CI 全绿；APK 验签通过（包名/40600/指纹 D9:21:…9E:78）

---

# OpenCode Android Remote - Release v4.5.0（工具链升级 + 序号追踪拆分）

## 内容

- **工具链升级**：AGP 8.2.2→8.5.2、Kotlin 1.9.22→2.0.21、compileSdk/targetSdk 34→35、
  Compose BOM 2024.02.00→2024.10.01；Compose 编译器改走 `org.jetbrains.kotlin.plugin.compose`
  插件（Kotlin 2.0 不再支持旧写法）；minSdk 保持 24
- **序号追踪拆分**：`RelaySeqTracker`（去重+纪元重置，纯逻辑可 JVM 单测）与
  `RelayMessageParser` 拆分，8+9 个新单测；`RelayWebSocketClient` 改调，行为不变
- **relay 工程项**：requirements 锁版本（fastapi/uvicorn/websockets）；Dockerfile 非 root
  用户 appuser；cloud_server compose 删废弃 version 字段、opencode 镜像 pin 到 digest；
  配对页 relay 地址默认值留空，公网 release 请用 wss；fileops.py 删未使用导入、
  import 时不再无条件 basicConfig

## 兼容性

- 非破坏性；覆盖升级，无需迁移。versionCode 40500

## 验证

- CI 全绿；APK 验签通过（包名/40500/指纹 D9:21:…9E:78 与历史一致，可覆盖升级）

---

# OpenCode Android Remote - Release v4.3.3（PreferencesManager 单例修复）

## 内容

- 修复模拟升级挖出的真 bug：v4.1 在 `OpenCodeApp.onCreate` 又 new 了一个
  PreferencesManager，与 ViewModel 里的实例并发初始化导致 keyset 竞争，加密存储
  keyset 写坏，升级迁移失败回退 LEGACY

## 兼容性

- 非破坏性。versionCode 40303

## 验证

- v3.1.0→v4.3.3 upgrade 复验通过（迁移无回退，keyset 正常写入，单例修复生效）；
  验签通过（包名/40303/指纹 D9:21:…9E:78）

---

# OpenCode Android Remote - Release v4.3.2（relay 配对验签透传修复）

## 内容

- relay 端修复：E2EE 配对时 `e2ee_pubkey_sig` 透传补漏（mobile 验签永远失败的真 bug）；
  App 端无代码变更，仅版本号递增

## 兼容性

- 非破坏性。versionCode 40302

## 验证

- 验签通过（包名/40302/指纹 D9:21:…9E:78）

---

# OpenCode Android Remote - Release v4.3.1（发版事故修复）

## 内容

- v4.3.0 的 release APK 被替换过（同一版本号两份不同构建），以 4.3.1 重新发版
  保证版本历史干净；代码与 v4.3.0 加固版一致
- CI 新增版本守卫（tag 必须等于 appVersionName、禁止复用已发布 tag）；
  新增 PR 模板（含「是否需要发版」勾选项）

## 兼容性

- 非破坏性。versionCode 40301

---

# OpenCode Android Remote - Release v4.3.0（多语言）

## 内容

- **多语言界面**：菜单 → 语言，可切换 10 种语言 + 跟随系统（中文简体默认置顶）
  - 中文（简体）、English、日本語、한국어、Español、Français、Deutsch、Русский、Português、العربية
  - 选择后即时生效；API 33+ 同步到系统「应用语言」设置页；阿拉伯语 RTL 已开
- 561 条界面文案全部抽取到 `values/strings.xml`（另有 9 个语言目录）；硬编码中文清零（日志/数据键除外）
- 多语言翻译由 AI 生成，欢迎校对

## 兼容性

- 非破坏性；默认跟随系统，无行为变化
- 新增偏好 `app_locale`（明文，空=跟随系统）

## 验证

- CI 全绿；aapt 校验各语言占位符一致性
- 真机：切换 3 种语言无崩溃、无缺字（待验证，见 TESTING_CHECKLIST.md）

---

# OpenCode Android Remote - Release v4.2.0（E2EE 运行时开关 + 安全审计优化）

## 内容

### E2EE 运行时开关

- **E2EE 改为运行时开关**：菜单 → 端到端加密（默认关闭），无需重新编译
  - 开启后需重新配对以交换 X25519 公钥；desktop 侧 `E2EE_ENABLED=1` 本来就是运行时
  - 开关状态变更下次连接生效；任一端未启用则走明文（原有降级）

### 安全审计优化（全部非破坏性；E2EE 相关文件未动）

- **V2 桌面端系统级密钥存储**：`desktop_agent` 加 `keyring` **可选依赖**（try-import）——
  优先 Windows Credential Manager / macOS Keychain，成功则不再落盘；无依赖或后端不可用时
  回退原有 0600 文件存储（行为不变）。`requirements.txt` 按平台条件安装（仅 win32/darwin）。
- **V4-1 日志去密钥名**：`TinkAeadStore` 解密失败日志不再打印 key 名（只保留异常对象）。
- **V4-2 proguard 纵深防御**：`-assumenosideeffects` 剥除 `android.util.Log.d/v`（release）。
- **O1 shrinkResources 开启**：全仓无 `getIdentifier` 动态资源引用，release 包资源裁剪开启；
  CI 的 ci-report 模板同步更新；emulator-smoke 兜底（tag 构建）。`fullMode` 不开，只做评估注记。
- **O3 relay Dockerfile 多阶段**：builder 阶段 `pip install --prefix=/install`，final 阶段只拷
  `/usr/local` + `server.py`；基础镜像保持 `python:3.11-slim`，保留 `--no-cache-dir`。
- **O4 CI 提速**：`python-tests` job 加 pip 缓存（`setup-python` 的 `cache: 'pip'`）；
  Android 单测从 `android-build` 拆成独立 job，与 build 并行。

## 审计注记（不改，已写进 docs/SECURITY.md）

- V1 SSL Pinning 不做：relay 用户自建自配 URL（含局域网 ws），pin 会断连；
  main 的 `network_security_config.xml` 已是 `cleartextTrafficPermitted="false"`（v2.1）。
- V3 供应链已有 supply-chain job（gitleaks + osv-scanner）；pip-audit 与 osv-scanner 同源（OSV），
  冗余不加；依赖均为新版无已知高危。
- O2 `Backoff.kt` 已有乘法抖动（0.85~1.15）+ 单测，无需改。

## 兼容性

- 非破坏性：覆盖升级，无需迁移。versionCode 40200，随 versionName 自动递增。

## 验证

- 契约测试 25/25（含 2 项 E2EE 盲转发，未动）；Python 单测通过；CI 全绿
- emulator-smoke（tag 构建）：云端模拟器启动无 FATAL

---

# OpenCode Android Remote - Release v4.1.0（E2EE 端到端加密，默认关闭）

## 内容

- **E2EE**（暂缓项第一条，正式启动）：mobile ↔ desktop 端到端加密，relay 只盲转发
  - X25519 ECDH 协商（复用扫码配对流程交换公钥）→ HKDF-SHA256 派生方向隔离密钥 →
    ChaCha20-Poly1305 加密内容载荷；路由元数据（type/session_id/seq/device_id）保持明文，
    断线补发不受影响
  - `FeatureFlags.ENABLE_E2EE`（Android，默认 false）/ `E2EE_ENABLED=1`（desktop）双门控；
    任一端未启用则走原明文流程
  - 威胁模型与线格式：`docs/E2EE_THREAT_MODEL.md`、`docs/E2EE_WIRE_v1.md`
  - 第一版不做密钥轮换/前向安全；relay 可见元数据（谁、何时、多少字节）

## 兼容性

- 非破坏性：全部只加可选字段（`e2ee` capability、`e2ee_pubkey`、`encrypted_payload`）
- 默认关闭，无行为变化；旧客户端互通不受影响

## 验证

- 契约测试 25/25（含 2 项 E2EE 盲转发）；Python 原语 RFC 向量通过；
  Android 单测新增 E2eeCryptoTest（6 项）；CI 全绿
- 真机联调（Python ↔ Kotlin 互操作）：未验证——需两端真实启用后验证

---

# OpenCode Android Remote - Release v4.0.0（协议 v4，唯一破坏性版本）

## 破坏性变更（本版唯一一次）

- **hello 变为强制**：relay 拒绝无 hello 的连接（`hello_required` + 4401 关闭）；
  v2 旧客户端（relay/agent/App）无法再连接
- **移除 legacy 路径**：relay 删除 v2 兼容分支与 legacy 埋点；App 删除 v3.5 的 legacy 降级逻辑
- **desktop 必须上报 device_id**（多 desktop 区分键，无回退）
- 协议 `v=4`：hello/hello_ack 的 `v` 字段为 4

## 清理（本版逐项）

1. `USE_NETWORK_MONITOR` 回退开关移除，NetworkMonitor 常开
2. App 移除 v3.5 的 legacy 降级逻辑与"v2 弃用提示"（改为 v3 relay 一次性升级提示，不阻断）
3. Tink 迁移遗留的旧加密存储文件：Tink 生效后删除；非敏感偏好搬到明文 `opencode_remote_settings`
   （LEGACY 回退时保留旧文件）
4. relay 移除 legacy 连接计数器；`/api/stats` 不再含 `connections` 计数

## 兼容性

- v3 客户端（hello v=3）仍被 v4 relay 接受（hello_ack 回 v=4），功能正常
- v4 App 连 v3 relay：功能可用，一次性提示"建议升级服务端"（不阻断）
- v4 App 连 v2 relay：明确报错"服务端版本过旧"，无自动降级
- **升级顺序（强制）：先 relay + agent 到 v4.0，再覆盖安装 App**
- 回滚：relay/agent 回到 v3.5 代码重启；App 回到 v3.5.0（无数据格式变更，不丢数据）

## 其他

- MIGRATION_V4.md 正式版（含升级顺序、兼容矩阵、回滚路径）
- E2EE：按计划暂缓（v4.0 稳定后 + 明确威胁模型才重估）
- applicationId / 签名：不变（v2.2 起同指纹，可覆盖安装）

## 验证

- 契约测试 23/23（v4：无 hello 拒绝、v3 兼容、无 device_id 拒绝、多 desktop、路由）；
  smoke 全过；CI 全绿；模拟器冒烟通过
- 验签：SHA-256 `D9:21:C0:EB:…:9E:9E:78` 与 v2.2 至今一致
- 真机验证：未验证


---


# OpenCode Android Remote - Release v3.5.0（v2 弃用准备）

## 内容

- **v2 协议进入维护模式**：见 `docs/DEPRECATION_V2.md`；EOL 计划为 v4.0 发布后 4 周
  （个人项目，4 周观察期为建议值，执行前会更新确切日期——本版如实标注，未等待观察期）
- **relay 埋点**：统计 legacy（无 hello）连接数，打 warning 日志；新增 `GET /api/stats`
 （v3/legacy 计数、房间数、在线会话数）
- **App 降级不断连**：连 v2 旧 relay 时自动降级 legacy（跳过 hello 直接 auth），不阻断；
  每个 relayUrl 一次性"服务端版本过旧"提示；v3 功能经 `serverSupports` 自动降级
- **MIGRATION_V4 草案**：`docs/MIGRATION_V4.md`（破坏性变更清单、升级步骤、回滚方案）

## 兼容性

- 无协议破坏性变更；v2/v3 互通保持
- 回滚：回到 v3.4.x 即可

## 验证

- 契约测试 22/22（含 3 项新增 legacy 埋点用例）；CI 全绿
- 真机验证：未验证（需 v2 relay + v3 App 组合验证降级提示）

---

# OpenCode Android Remote - Release v3.4.0（稳定性二期）

## 内容

- **数据驱动结论**：检查 GitHub Issues（零）/ 用户反馈（无）/ ACRA（默认关闭，无上报）——
  本版无已知真实稳定性问题，不编造问题，只做防御性加固
- **防御性小改**（minimal）：
  - A. 消息分发未知异常兜底：记 AppLog、状态机回 DISCONNECTED（触发重连）、每步独立 guard，不向上传播崩溃
  - B. 空值防御：`sendPrompt` 空 prompt/sessionId 直接丢弃不发送；`switchSession` 空 sessionId 忽略不清空当前会话

## 兼容性

- 无协议变更，无数据迁移；覆盖安装即可

## 验证

- 契约测试 19/19；单测全过；CI 全绿
- 真机验证：未验证

---

# OpenCode Android Remote - Release v3.3.0（R8 + CI 扩展）

## 内容

- **R8 开启**：release 包 `minify=true`（`shrinkResources=false` 保守）
  - 评估结论：ACRA 5.11.3 / ML Kit 17.2.0 / tink-android 1.23.0 / OkHttp 4.12.0 的 consumer keep rules
    已全部实测齐全（v2.4 时的两个 blocker 已消除）；项目无反射、无 Gson 解析
  - `proguard-rules.pro` 补 keep：security-crypto（legacy 路径，无 consumer rules）、
    Tink Android 集成、数据模型类名（崩溃上报可读性）
  - 回滚：`isMinifyEnabled=false` 一行回退
- **CI 扩展**：
  - APK 体积回归检查（release 超 40MB 告警）
  - release 产物校验：签名指纹 / 包名 / versionCode+versionName 与 tag 一致（不一致即失败）
  - R8 包模拟器启动冒烟（防混淆导致闪退，沿用 v3.0.1 验证模式）
  - CI 报告 artifact 可下载

## 兼容性

- 无协议变更，无数据迁移；覆盖安装即可

## 验证

- 契约测试 19/19；单测全过；CI 全绿（含新增校验项）
- 模拟器冒烟：R8 release 包启动无 FATAL、进程存活
- 真机验证：未验证（R8 包建议在真机走一遍配对/连接/对话核心流程）

---

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

---

## 封版说明（2026-10-07）

v5.1.0 为当前封版版本。协议 v4 已冻结，后续只做兼容修复与安全更新，不再主动迭代新功能。
