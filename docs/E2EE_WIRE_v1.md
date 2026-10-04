# E2EE 线格式规范 v1（mobile ↔ desktop，relay 盲转发）

## 密钥协商

- 算法：X25519 ECDH。mobile 与 desktop 各生成 32 字节密钥对。
- 公钥交换：复用配对流程。配对确认消息加可选字段 `e2ee_pubkey`（base64，32 字节 X25519 公钥）。
  relay 只转发，不存储、不推导。
- 共享密钥：`shared = X25519(priv_self, pub_peer)`。
- salt：显式 32 零字节（`bytes(32)`），两端严格一致，避免库对空 salt 的边缘处理差异。
- 消息密钥（方向隔离）：
  - mobile→desktop：`k_m2d = HKDF-SHA256(shared, salt=b"", info=b"opencode-remote-e2ee-v1-m2d", 32)`
  - desktop→mobile：`k_d2m = HKDF-SHA256(shared, salt=b"", info=b"opencode-remote-e2ee-v1-d2m", 32)`

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
