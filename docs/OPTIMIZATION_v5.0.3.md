# v5.0.3 优化批次变更说明

依据：`OpenCode_v5.0.2_优化审查报告-2.docx`（审查对象为 v5.0.2 源码）。
日期：2026-10-06。范围：P0 + P1 + P2 + P3 全部条目，按用户要求一次做完。

协议 v4 既有帧语义与字段**未改动**；唯一新增是附加帧 `approval_rejected`
（旧版 App 的 `else -> {}` 会忽略），详见 `docs/E2EE_WIRE_v1.md`。

---

## 1. 结论对照表

| 编号 | 级别 | 问题 | 本次改动 | 状态 |
|---|---|---|---|---|
| A-1 | P0 | 开启 E2EE 后审批/取消/新建会话/文件浏览被电脑端静默丢弃 | 新增 `sendControl(action, payload, sessionId, …)` 作为控制类消息**唯一出口**，`cancel`/`tool_approval_response`/`create_session`/`file_list`/`file_read` 与 `send_prompt` 统一走 `encryptInnerForDesktop`，加密失败即拒发（fail-closed）。线协议不变 | ✅ |
| A-1 extra | — | **报告未发现的阻塞点**：加密目标来自 `cachedDesktops`，而它只在多桌面路由开启时才填充；默认配置下 `desktop_list` 从不下发 → `effectiveTarget` 恒空 → 加密永不触发，E2EE 实际等于没开 | 记录「最近协商成功的对端 device_id」（`e2ee_last_peer_id`），并作为第三级回退；`refreshE2eePeerReady` 同样回退，UI 不再误报「未就绪」 | ✅ |
| A-2 | P0 | 点通知会清空当前聊天 | `switchSession` 对同一 id 直接返回；切换时改为从本地缓存恢复而非清空 | ✅ |
| B-1 | P1 | 退避在握手成功时就清零（固定 3 秒无限重连） | `backoff.reset()` 与重试计数移到 `auth_ok`；`Backoff` 新增 `maxRetries`（Relay 10 次，Cloud SSE 保持不限次），耗尽返回 `null` | ✅ |
| B-2 | P1 | 审批/取消排队会误伤（补发取消新任务、nonce 过期、切 profile 串房） | 审批与取消改为不排队（走 `onWriteUnconfirmed` 由用户重试）；队列项记录 `(relayUrl, accountId)`，`connect()` 检测到换房、`disconnect()`、`auth_error` 时清空，补发前再核对归属 | ✅ |
| B-3 | P1 | 审批被拒手机无感知；`expiresAt` 全工程无人读 | ① 审批卡片按 `expires_at` 倒计时，过期禁用按钮并提示；ViewModel 层 `approveTool`/`rejectTool` 过期直接不发帧并提示。② agent 三种拒绝（缺 call_id / 缺 nonce / nonce 失效）各回一帧 `approval_rejected`，客户端收起卡片并在聊天里说明 | ✅ |
| B-4 | P1 | 发送失败时任务状态与前台通知卡在「运行中」 | 出站改为 `SendResult.SENT/QUEUED/FAILED` 三态；只有 `SENT` 才置 `RUNNING` 并启动计时，`QUEUED` 只给「已排队」提示，`FAILED` 不残留 generating | ✅ |
| B-5 | P1 | 分发层异常兜底与注释不符；一条畸形数据让连接显示断开 | `sessions_list`/`file_list_result`/`diff_lines` 改用 `opt*` + 跳过坏条目；兜底只记日志 + 提示，不再写 `DISCONNECTED`（真要断开请走 `cancel()` + `scheduleReconnect()`） | ✅ |
| B-6 | P1 | 聊天记录不持久化 | 新增 `MessageStore`（纯逻辑、可 JVM 单测）：按会话缓存最近 200 条，IO 线程写入；落盘时机为流结束、切换会话、App 退后台、ViewModel 销毁；切换会话与启动时恢复 | ✅ |
| B-7 | P1 | relay 反代下限流按代理 IP 生效；反代提示已失效 | 封禁与失败计数改按 `(ip, account_id)` 记账（账号已知时），并在鉴权后加账号级封禁检查（4429）；`_check_proxy_hint` 判据改为「未配置 `TRUSTED_PROXIES`」 | ✅ |
| C-1 | P2 | 流式期间每条消息都写 SharedPreferences | ① relay 序号落盘节流（≥500ms 或 ≥20 条），`stream_end`/鉴权成功/断开/App 退后台强制落盘；② E2EE 序号搬到独立 `opencode_e2ee_seq`，**含自动迁移**（丢序号会让 desktop 判重放并拒收） | ✅ |
| C-2 | P2 | `StateFlow.update{}` 里做磁盘写 | 会话列表落盘移出 CAS lambda（`updateAndGet` + `persistSessions`），单线程 IO 按序执行 | ✅ |
| C-3 | P2 | E2EE 先解密、后去重 | `RelayMessageParser` 新增 `Duplicate` 结果：解析外层 JSON 后先用明文 `relay_seq` 预检，重复则**不解密**直接丢弃；主线程 track 时同步 `@Volatile` 预检水位，纪元重置时一并归零 | ✅ |
| C-4 | P2 | Cloud 模式 listener 绑在 ViewModel 实例上 | `CloudApiClient.setListener()` + 回调时解析当前监听器；ViewModel init 时重挂。进行中的 SSE 流现在能回调到新 ViewModel | ✅ |
| C-5 | P2 | 构建/依赖小项 | proguard 注释对齐真实版本（ACRA 5.13.1 / ML Kit 17.3.0 / OkHttp 5.4.0）；`collectAsState` → `collectAsStateWithLifecycle`（新增 `lifecycle-runtime-compose:2.10.0`，该版本已在依赖校验元数据中）；`versionCode` 加 `require(次版本/补丁 < 100)` 防撞号；`appVersionName` → 5.0.3 | ✅ |
| C-6 | P2 | 死代码与已冻结功能 | 删除 `ENABLE_STREAM_WINDOW` 开关与不可达的旧流式路径（`streamBuffer`、`StreamReducer.appendToMessage`/`collapseIfNeeded`/`CollapseResult`，含对应单测）。见下方「未做项」 | 部分 |
| P3 | — | 若干小项 | 共享 base `OkHttpClient`（连接池/线程池合一）；`onStreamStart` 只在 Cloud 模式写 `cloudConnectionState`；`config_data` 注释改为只描述实际实现的一种格式；`/api/stats` 可选 `RELAY_STATS_TOKEN`；`Backoff`/手动 `reconnect()`/网络恢复时立即重连（v5.1 计划项 1~3）；`ControlEnvelopeTest` 覆盖 JSON→信封映射 | ✅ |

## 2. 未做 / 有意保留（附理由）

| 项 | 理由 |
|---|---|
| C-5 的 FLAG_SECURE 设置开关 | 报告本身写的是「建议」。默认开启是安全默认，改成可关需要新增设置项与一次真机验证；本次保持默认开启，未加开关 |
| C-5 的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 政策复核 | 报告原话是「若将来上架 Google Play 需先确认政策」，无可执行的代码改动 |
| C-6 `triggerMockToolApprovalForTest` 移到 debug 源集 | 该方法在 `src/main` 的 ViewModel 里，调用点在同样属于 `src/main` 的 `MainActivity`/`ChatScreen`。要真正移出 release，必须把菜单项也搬进 debug 变体，属于结构性重构；当前已有 `BuildConfig.DEBUG` 守卫，release 包不可达 |
| C-6 多桌面路由（`ENABLE_DESKTOP_ROUTING=false`）相关代码清理 | 同上，删除会牵动 `DesktopListDialog`/路由 coordinator/多个回调；本次改为在 `docs` 里标注 deferred 及其原因 |
| P3 `parseExecutor.shutdown()` | 该执行器属 Application 级单例，进程退出即消失，没有可靠的生命周期回调可挂；加一个无人调用的 `shutdown()` 就是 C-6 要删的死代码 |
| B-3 步骤①的 agent 端回帧时机 | 报告建议「严守协议冻结则放到 v5.2」。本次按「全做」实现为**附加帧**，不改动 v4 既有帧；风险与兼容性分析见 `docs/E2EE_WIRE_v1.md` |

## 3. 验证情况（重要）

**已实跑（本机可执行）**

- Python 全量 12 个套件 **192 项全过**（改动前 10 个套件 165 项全过）：
  - `desktop_agent/modules/`：test_e2ee(21) / test_e2ee_v2(19) / test_hardening(36) /
    test_sandbox(12) / test_sse_parser(11) / **test_approval_reject(13，新增)**
  - `tests/e2ee/`：e2ee_protocol_test(21)
  - `relay_server/`：test_crash_report(16) / test_handlers(6) / test_security(12) /
    test_targeted_fanout(11) / **test_rate_limit_b7(14，新增)**
  - 本机 Python 3.13 + cryptography + fastapi + aiohttp 均可用，因此报告第 9 节
    「没能运行」的三套件（test_hardening / test_sandbox / test_sse_parser）与
    relay 全部测试本次都真跑了。
- **v4 协议契约端到端测试 `scripts/test_contract_v4.py`：25/25 全过**
  （真启 uvicorn relay + websockets 客户端，覆盖 hello 强制、legacy 移除、
  多 desktop 路由、E2EE 信封原样透传、主 desktop 回退）。B-7 改了鉴权失败
  记账与新增账号级封禁检查，这条端到端测试确认认证流程未回归。
- 新增测试覆盖的关键断言：
  - `test_rate_limit_b7.py`：同 IP 下账号 A 输错 5 次只封 A，B 不受影响；
    IP 级封禁（握手超时这类无法归属账号的失败）仍对所有账号生效，不能被绕过；
    `TRUSTED_PROXIES` 为空时反代提示必触发、已配置时不触发（含旧默认值场景）。
  - `test_approval_reject.py`：缺 nonce / nonce 失效 / 缺 call_id 三种拒绝各回一帧
    `approval_rejected` 且 reason 正确；**合法 nonce 仍走正常路径、不回该帧**
    （防止把修复做成回归）。
  - `ControlEnvelopeTest`：客户端 `CONTROL_ACTIONS` 与 `e2ee.py` 逐项一致；
    六个动作在加密后 `e2ee=true`、`encrypted_payload` 存在且**不含明文 payload**。
  - `BackoffTest`：耗尽返回 `null`、`reset` 清零计数、**握手成功但鉴权前断开时
    延迟递增而不是回到 3 秒**（B-1 的回归断言）。
- 静态校验（弥补无编译器）：`R.string.*` 引用全部存在于 `values/strings.xml`；
  10 个 locale 的 `strings.xml` 均为合法 XML、无 BOM、6 个新 key 齐全；
  括号/花括号配平；已删除符号无残留引用；`AndroidManifest.xml` 合法。

**未验证（需要 Android SDK / Gradle / 设备）**

本机**没有 Java 与 Android SDK**，Kotlin 侧无法编译，因此以下均为静态审查
结论，请在本地/Android 环境按报告每条的「验证」项实测：

1. 编译与全部 Android 单测：`./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`。
2. A-1 联调：`emulator-e2ee` 工作流里补一条「开启 E2EE 后批准审批」的用例；
   特别验证取消、新建会话、文件浏览在 E2EE 开启后可用（本次修复的正主）。
3. A-2：任务完成后点通知，聊天仍在。
4. B-4：E2EE 加密失败时任务状态不应变成「运行中」，通知不刷新计时。
5. B-6：任务运行中划掉 App → 重开能看到此前消息。
6. C-1 迁移：升级安装后 E2EE 仍可用（序号已从旧 prefs 搬过来）。
7. release 包 R8 裁剪：`emulator-smoke`（新增了 `lifecycle-runtime-compose`
   依赖与 `require()` 守卫，建议跑一次 release 变体）。

## 4. 建议的合并顺序

报告第 7 节建议分四版发布。本次是一次性交付，若要拆开发布，建议：

1. **relay 先行**（B-7 + `/api/stats` token）：纯服务端，旧 App 不受影响。
2. **App + agent 一起发**（A/B/C 全部）：B-3 的附加帧需要两端配套；
   A-1 的加密补齐需要 v5.0.3 App 对任意版本 agent 都成立（agent 侧无需改）。
3. agent 的 `approval_rejected` 可独立先发（旧 App 会忽略）。