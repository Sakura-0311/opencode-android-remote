# E2EE 威胁模型与设计方案（草案 v0.1，待评审）

> 状态：草案。实现前必须经评审确认。路线图要求"v4.0 稳定后 + 明确威胁模型"，
> 本文档即为威胁模型部分。

## 1. 现状信任模型（v4.0）

| 环节 | 现状 |
|------|------|
| 配对 | relay 签发一次性配对码（120s 过期）；agent 终端显示二维码；Android 扫码后 relay 签发设备专用密钥，存 Android Keystore（Tink 管理） |
| 传输 | WS 经 TLS（wss，依赖反代/隧道终止）；App 主 flavor 禁明文 HTTP |
| relay 可见性 | **relay 能看到全部明文**：`send_prompt` 的 prompt 内容、消息流、session_id、device_id；`RoomBuffer` 存明文环形缓冲（1000 条/5 分钟/4MB） |
| 存储 | 密钥在 Android Keystore；relay 存 secret_hash（不存明文 secret） |

结论：当前模型信任 relay 运营者（用户自建）+ TLS 链路。一旦 relay 被入侵、或使用不受信任的公共 relay，对话内容完全暴露。

## 2. 威胁模型

### 2.1 保护目标

- **T1. 不受信任的 relay**：relay 运营者 / 入侵者无法读取消息内容（prompt、回复流、文件内容）
- **T2. 网络窃听者**：TLS 被剥离或终止点不可信时，仍无法读取内容（纵深防御）

### 2.2 非目标（明确不做）

- 端点安全：手机 / desktop 被入侵则无解（密钥就在端点上）
- 恶意 desktop：desktop 本来就是对话参与方，不防它
- 流量分析：relay 仍能看到"谁在何时发了多少字节"（元数据不加密）
- 拒绝服务、重放攻击超出本期范围（重放由 seq 机制部分覆盖）

### 2.3 假设

- v4.3 起：desktop 公钥经房间主 secret 做 HMAC-SHA256 绑定（`e2ee_pubkey_sig`），
  relay 只存 `sha256(secret)` 无法伪造。**手动配对**（mobile 持有主 secret）时
  mobile 校验签名，签名无效拒绝保存；**扫码配对**（mobile 只有 device_secret，
  relay 明文知晓）无法做不可伪造绑定，走 TOFU——主动替换公钥的 relay 在扫码
  配对场景下仍可 MITM，此为已知取舍
- Android Keystore / desktop 本地密钥存储可信
- Tink 原语实现正确

## 3. 设计方案

### 3.1 总体：端到端加密 mobile ↔ desktop，relay 只做盲转发

- 加密粒度：消息 **内容载荷**（prompt 文本、回复流、文件内容）；路由元数据
  （`type`、`session_id`、`target_device_id`、`relay_seq`）保持明文，relay 仍可路由、
  缓冲、补发——**不断线恢复能力**
- 算法：X25519 密钥协商 + ChaCha20-Poly1305（IETF，12 字节随机 nonce；
  Android 侧 Tink subtle，desktop 侧 Python 用 `cryptography` 库）。
  v4.3 勘误：早期草案误写为 XChaCha20-Poly1305（24 字节 nonce），与线格式
  `docs/E2EE_WIRE_v1.md` 及两端实现不符，实现一直是 IETF ChaCha20-Poly1305。

### 3.2 密钥协商：复用配对流程

- 配对扫码时，mobile 生成 X25519 临时密钥对，把公钥随配对确认发给 relay→desktop；
  desktop 回自己的公钥。双方经 ECDH 得出共享密钥，HKDF 派生消息密钥
- 每对 mobile↔desktop 独立密钥；多 desktop 场景每台单独协商
- relay 只转发公钥，**无法推导共享密钥**（ECDH 安全性）

### 3.3 密钥轮换与前向安全（可选，v1 可不做）

- 简版：配对时协商长期密钥，不轮换；撤销设备即删密钥
- 增强版（以后）：每次会话或定期轮换，Double Ratchet 过重，暂不考虑

### 3.4 对现有功能的影响

| 功能 | 影响 |
|------|------|
| 断线补发（RoomBuffer） | relay 存密文，可照常补发；解密在端点 |
| 通知栏内容脱敏 | 本来就脱敏，无影响 |
| relay 诊断 / crash 上报 | 只能看到元数据，内容不可见（符合预期） |
| 明文搜索 / relay 侧审计 | 失去，接受 |
| 旧版本兼容 | E2EE 为 capability，协商开启；旧客户端走明文（FeatureFlags 默认关闭） |

### 3.5 不做的

- 不加密 `type`/`session_id`/路由字段（relay 需要它们工作）
- 第一版不做前向安全轮换、不做群组密钥（多 mobile 暂不考虑）

## 4. 待评审决策

1. **是否接受"relay 可见元数据"**（谁、何时、多少字节）？要隐藏则需填充流量，成本高
2. **密钥轮换**：第一版做不做？（建议不做，先保证协商正确）
3. **desktop 侧 Python 依赖**：引入 `cryptography` 库（relay 不需要，desktop 需要）
4. **协议版本**：E2EE 协商走 v4 的 capability（`e2ee`），还是等 v5？（建议 v4 capability + FeatureFlag）
5. **Cloud 模式**：云端 SSE 路径同样端到端加密（relay 盲转发），确认无例外

## 5. 实施计划（评审通过后）

1. desktop_agent：X25519 + 加解密模块（`cryptography`），单测
2. relay_server：公钥转发 + `e2ee` capability（只加字段，不碰转发逻辑）
3. Android：Tink HybridEncrypt 协商 + 加解密，FeatureFlags 默认关闭
4. 契约测试：E2EE 握手、密文 relay 不可见（断言 relay 日志/缓冲无明文）、降级互通
5. 文档：`docs/E2EE.md`，RELEASE_NOTES，版本号 v4.1.0（非破坏性：capability 可选）
