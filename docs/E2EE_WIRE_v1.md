# E2EE 线格式规范 v1（mobile ↔ desktop，relay 盲转发）

## 密钥协商

- 算法：X25519 ECDH。mobile 与 desktop 各生成 32 字节密钥对。
- 公钥交换：复用配对流程。配对确认消息加可选字段 `e2ee_pubkey`（base64，32 字节 X25519 公钥）。
  relay 只转发，不存储、不推导。
- 共享密钥：`shared = X25519(priv_self, pub_peer)`。
- salt：显式 32 零字节（`bytes(32)`），两端严格一致，避免库对空 salt 的边缘处理差异。
- 消息密钥（方向隔离）：
  - mobile→desktop：`k_m2d = HKDF-SHA256(shared, salt=bytes(32), info=b"opencode-remote-e2ee-v1-m2d", 32)`
  - desktop→mobile：`k_d2m = HKDF-SHA256(shared, salt=bytes(32), info=b"opencode-remote-e2ee-v1-d2m", 32)`
  > v5.0.1 勘误：此处原写作 `salt=b""`，与上一行的「32 零字节」自相矛盾
  > （HKDF 空 salt 等价于全零 salt，实现一直是 `bytes(32)` / Kotlin `ByteArray(32)`）。

## 消息加密

- 算法：ChaCha20-Poly1305（IETF，12 字节 nonce）。
- AAD：`"{sender_device_id}:{session_id}"` 的 UTF-8 字节（防跨会话重放）。
- 载荷格式：`nonce(12B) || ciphertext`，整体 base64 → 放入 `encrypted_payload` 字段。
- nonce：每次加密随机 12 字节。

## 协议消息（全部只加可选字段，v4 兼容）

- hello capability 新增 `"e2ee"`；`hello_ack`/`auth_ok` 的 `server_capabilities` 同步。
- 配对公钥交换（relay 只透传、不校验、不落盘）：
  - desktop→relay `create_pairing` 加可选 `e2ee_pubkey`（base64，32B）；relay 存入配对会话（内存，随过期丢弃）
  - mobile→relay `pair_claim` 加可选 `e2ee_pubkey`
  - relay→desktop `device_paired` 加 `e2ee_pubkey`（mobile 的，若有）
  - relay→mobile `pair_success` 加 `e2ee_pubkey`（desktop 的，若有）与
    `desktop_device_id`（desktop 的 device_id，用于 mobile 绑定公钥）
  - 任一端缺失则静默跳过，走明文旧流程
- mobile→desktop `send_prompt`（实际为 action+payload 信封）：
  ```json
  {"action":"send_prompt","session_id":"s1","target_device_id":"d1",
   "e2ee":true,"encrypted_payload":"<base64(nonce||ct)>",
   "client_msg_id":"...","req_id":"..."}
  ```
  加密对象为原 `payload` 的 JSON 字符串；`e2ee` 时不带明文 `payload` 字段。
  `e2ee` 缺失/false 时为旧格式，互通。
- desktop→mobile（如 `stream_chunk`）：
  ```json
  {"type":"stream_chunk","session_id":"s1","e2ee":true,
   "encrypted_payload":"<base64(nonce||ct)>",
   "source_device_id":"d1","relay_seq":123}
  ```
  解密后明文为 `{"chunk":"..."}`（按原消息类型还原对应内容字段）。
  路由字段（`type`/`session_id`/`relay_seq`/`source_device_id`）保持明文。

## 密钥存储

- Android：X25519 私钥存 Tink Keystore（`tink-android` subtle API 自管 keyset，
  与 v3.2 的 SecureKvStore 同一机制）；对端公钥随设备记录存加密存储。
- desktop：私钥存 `~/.config/opencode-remote/e2ee_privkey`（0600）；对端公钥同目录。

## 降级与开关

- Android：`PreferencesManager.isE2eeEnabled` 运行时开关（菜单可切，默认 false）；
  desktop `E2EE_ENABLED` 环境变量（默认 0）。
- 任一端未启用/未协商 → 明文互通（原有行为）。

## 元数据声明（v4.3 M-5）

- **加密的**：消息内容载荷（prompt 文本、回复流、文件内容）。
- **不加密的**：路由元数据——`type`、`action`、`session_id`、`target_device_id`、
  `relay_seq`、`req_id`、`client_msg_id`、时间戳、载荷字节数。relay 需要它们做
  路由、缓冲、补发，这是设计取舍（不断线恢复能力优先）。
- 即使 E2EE 开启，中继/网络观察者仍能看到「谁在何时发了多少字节给哪个设备」，
  只是看不到内容。用户应在「安全」设置页与配对成功提示中知晓这一点。

---

## 内层格式 v2（v4.6.0，P1-2/P1-3 修复）

v1 的两处互操作缺陷（AAD sender 两端不一致、内层格式两端不一致）在 v4.6.0 修复。
E2EE 默认关闭且 v1 实际不可用，故无迁移成本；v4.6.0 起只实现 v2。

### AAD sender 身份统一

- mobile→desktop：AAD sender = **手机的 relay device_id**
  （`pair_success.device_id`，relay 在 `claim_pairing` 时分配；手机存入偏好，
  加密时用它；relay 在 `device_paired` 里带上同一 `device_id`，desktop 以它为
  peer id 保存手机公钥并做 AAD——两端一致）。
- desktop→mobile：AAD sender = desktop 的 `device_id`
  （`config.get_desktop_device_id()`，auth/配对时上报的同一值）。

### 内层 JSON（v2）

- m2d 内层：`{"action":"<action>","payload":{...},"seq":<int>}`
  desktop 解密后用内层 `action`/`payload` 覆盖外层（外层 `action` 保留明文仅供
  relay 兼容，desktop 以内层为准）。
- d2m 内层：`{"type":"<type>",...原内容字段...,"seq":<int>}`
  手机解密后把内层字段合并进外层（与 v1 的合并逻辑兼容）。
- `seq`：按对端、按方向独立的单调递增计数器，落盘持久化；
  接收方要求严格递增，否则视为重放/乱序丢弃（fail-closed）。

### fail-closed（P1-3）

- desktop 已协商对端后，以下控制类消息必须带合法 e2ee 信封，否则拒绝：
  `send_prompt`、`cancel`、`tool_approval_response`、`create_session`、
  `file_list`、`file_read`。未协商时沿旧明文流程。
- d2m 加密覆盖（v2）：`stream_chunk`、`tool_approval_request`（含 diff/nonce）、
  `file_read_result`、`file_list_result`。仍明文的：`sessions_list`（会话标题）、
  `projects_data`、错误信息、路由元数据（见元数据声明）。

### 互操作向量

`tests/e2ee/interop_vectors.json`（由 `tests/e2ee/gen_interop_vectors.py` 生成，
提交进仓库）：固定 X25519 密钥对、HKDF 派生密钥、固定 nonce 加密向量。
Kotlin（`E2eeInteropTest`）与 Python（`test_e2ee_v2.py`）单测读取同一份向量，
保证跨端密钥派生与加解密一致。
