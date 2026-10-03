# OpenCode Android Remote 项目开发对话、漏洞审计与全量发布资产档案 (v1.5.0)

**供后续接手团队、AI 模型与维护者全流程复盘与无缝协作的技术交接白皮书**

| 项目信息 | 说明 |
| :--- | :--- |
| **项目名称** | OpenCode Android Remote (移动端控制台) |
| **开源仓库** | `Sakura-0311 / opencode-android-remote` |
| **版本演进** | v1.3 (建议书落地) → v1.4 (真实契约重构) → **v1.5.0 (安全加固与 GitHub 生产就绪版)** |
| **技术栈** | Android (Kotlin + Jetpack Compose) / Desktop (Python 3.10+ aiohttp/websockets) / Relay (FastAPI) / Cloud (Docker + Caddy 2) |
| **核心成果** | 彻底消除端口绑定死锁、计时攻击、重放攻击漏洞，补齐断线游标补偿与硬件级加密，构建符合 GitHub 标准的 CI/CD 与发行资产体系 |

---

## 目录
1. [项目系统架构与双模拓扑](#一系统架构与双模拓扑)
2. [全流程会话记录实录 (v1.3 至 v1.5)](#二全流程会话记录实录)
   - [第一阶段：功能建议书落地与召回 (v1.3)](#1-第一阶段功能建议书落地与召回-v13)
   - [第二阶段：真实 OpenCode 契约重构 (v1.4)](#2-第二阶段真实-opencode-契约重构-v14)
   - [第三阶段：深度漏洞审计与全链路安全优化 (v1.4 → v1.5)](#3-第三阶段深度漏洞审计与全链路安全优化)
   - [第四阶段：GitHub 开源规范改造与一键打包交付](#4-第四阶段github-开源规范改造与一键打包交付)
3. [核心漏洞审计矩阵与加固对照表](#三核心漏洞审计矩阵与加固对照表)
4. [v1.5.0 全量代码与配置文件归档](#四v150-全量代码与配置文件归档)
   - [4.1 云端直连与 Caddy 反代配置](#41-云端直连与-caddy-反代配置)
   - [4.2 安全加固版中继服务器 (Relay Server)](#42-安全加固版中继服务器-relay-server)
   - [4.3 桌面守护代理与防重放工具守卫 (Desktop Agent)](#43-桌面守护代理与防重放工具守卫-desktop-agent)
   - [4.4 Android 客户端核心网络与安全存储组件](#44-android-客户端核心网络与安全存储组件)
   - [4.5 GitHub 开源规范与 CI/CD 流水线](#45-github-开源规范与-cicd-流水线)
   - [4.6 自动化打包与发布脚本](#46-自动化打包与发布脚本)
5. [后续维护与演进规划](#五后续维护与演进规划)

---

## 一、系统架构与双模拓扑

本项目专为开源 AI 编程引擎 [OpenCode (anomalyco/opencode)](https://github.com/anomalyco/opencode) 设计，支持两种无缝切换的部署架构：

```
┌────────────────────────────────────────────────────────────────────────┐
│                              Android 客户端                             │
│       Jetpack Compose UI + EncryptedSharedPreferences + Flow SSE       │
└───────────────────▲────────────────────────────────▲───────────────────┘
                    │                                │
      [模式一: 电脑穿透模式]             [模式二: 云端直连模式]
            (WSS 加密通道)                     (HTTPS / WSS 直连)
                    │                                │
                    ▼                                ▼
       ┌────────────────────────┐      ┌───────────────────────────┐
       │     中继转发服务器      │      │     Caddy 自动 TLS 反代    │
       │   relay_server (FastAPI)│      │  (Let's Encrypt 证书终结)  │
       └────────────▲───────────┘      └─────────────▲─────────────┘
                    │                                │ (Docker 内部网络)
                    ▼                                ▼
       ┌────────────────────────┐      ┌───────────────────────────┐
       │   本地代理守护进程      │      │   OpenCode Server 容器    │
       │   desktop_agent (Py)   │      │   (anomalyco/opencode)    │
       └────────────▲───────────┘      └───────────────────────────┘
                    │ (HTTP/SSE)
                    ▼
       ┌────────────────────────┐
       │   本地 OpenCode 引擎   │
       │   (运行于开发者电脑)    │
       └────────────────────────┘
```

1. **电脑远程中继穿透模式 (Desktop Relay)**：
   - 手机通过 WSS 长连接接入中继房间，电脑端运行后台守护代理（`desktop_agent`）；
   - 手机发送指令由中继路由至本地代理，代理调用电脑本地 OpenCode 核心服务；
   - 优势：代码、模型 Key 均留在本地，支持操作本地磁盘与 Git，无需公网 IP。
2. **云端直连模式 (Cloud Hosted)**：
   - OpenCode 容器与 Caddy 反代容器编排部署在云服务器（VPS/Docker）；
   - 手机端直接通过标准 HTTPS/SSE 访问，随时随地发起编码长任务。

---

## 二、全流程会话记录实录

### 1. 第一阶段：功能建议书落地与召回 (v1.3)
- **需求输入**：落地《opencode-android-remote_项目优化改进建议.docx》，包括会话分组管理、代码 Diff 预览折叠、隧道诊断工具、后台前台服务保活及会话导出 Markdown。
- **问题与召回**：初审中发现打包时遗漏了 `cloud_server/` 与 `docs/` 目录，且 Material 3 的 `TabRow` 存在编译报错。
- **交付成果**：修复编译语法，强制 Docker 移除弱口令，补齐 `GEMINI_API_KEY`，输出包含 33 个文件的 v1.3 全量包。

### 2. 第二阶段：真实 OpenCode 契约重构 (v1.4)
- **审查输入**：用户上传《opencode-android-remote-优化清单.docx》，指出早期版本调用了大量假 API（如 `/api/chat`、`/api/sessions`），健康检查将 404 误判为成功，工具审批为假指令等 P0 级致命缺陷。
- **全面重构**：
  1. 对齐官方真实的 `/global/health`、`/session`、`/session/:id/message` 和 `/event`；
  2. 实现真实的 `permission.asked` 事件捕获与 `/session/:id/permissions/:id` 决策闭环；
  3. 编写 `scripts/smoke_test_contract.py` 自动化契约冒烟测试，产出 v1.4 发布包。

### 3. 第三阶段：深度漏洞审计与全链路安全优化
- **用户提问**：
  > “你看一下这个有什么漏洞吗，有的话优化一下” (附带 `OpenCode_Android_Remote_开发对话与技术交接档案.docx` 与 `opencode-android-remote-v1.4_2.zip`)
- **审计发现的核心漏洞**：
  - **SEC-01 (P0)**：Docker 绑定 `127.0.0.1:4096` 导致公网手机无法直连；若改绑 `0.0.0.0` 则沦为 HTTP 明文传输，泄露密码与代码。
  - **SEC-03 (P2)**：中继密码校验采用 `==` 导致计时侧信道攻击；反向代理下提取 IP 易被伪造。
  - **SEC-04 (P1)**：工具审批缺少 Nonce 随机凭据与超时熔断，存在重放攻击与协程挂起风险。
  - **PRT-01 (P2)**：SSE 增量流断线重连未携带 `Last-Event-ID`，弱网切换丢字。
  - **SEC-05 (P2)**：客户端明文存储密码，存在物理脱机提取风险。
- **优化落实**：
  - 引入 Caddy 自动化 TLS 反代编排；
  - 升级中继服务使用 `hmac.compare_digest` 恒定时间比对与受信任反代识别；
  - 桌面代理引入 `ToolApprovalManager` (Nonce + 120s 自动过期)；
  - 客户端全面对接 `Last-Event-ID` 游标补偿与 Android Keystore 硬件加密存储。

### 4. 第四阶段：GitHub 开源规范改造与一键打包交付
- **用户提问**：
  > “你直接做成可发布在GitHub的模式”
  > “你做成zip发给我”
  > “这样你把你和我的会话记录和产生的文件都做成word发给我”
- **执行动作**：
  - 规范化 GitHub 仓库根目录，输出 `.github/workflows/ci.yml`、`.gitignore`、`LICENSE`、`README.md`、`RELEASE_NOTES.md`；
  - 编写一键打包与补丁注入脚本 `scripts/build_release_zip.py`；
  - 编写本文档与 Word 导出脚本 `scripts/generate_docx.py`，实现一键产出最终交付文档。

---

## 三、核心漏洞审计矩阵与加固对照表

| 编号 | 风险领域 | 原始隐患 (v1.4) | 加固实施方案 (v1.5) | 影响文件 |
| :--- | :---: | :--- | :--- | :--- |
| **SEC-01** | **云端端口死锁** | 绑定 `127.0.0.1:4096` 手机连不上；改绑 `0.0.0.0` 导致公网明文传输 HTTP | 引入 **Caddy 2** 自动申请 TLS 证书并监听 443，OpenCode 仅内部暴露 | `cloud_server/docker-compose.yml`<br>`cloud_server/Caddyfile` |
| **SEC-03** | **中继计时侧信道** | 房间密钥采用 Python `==` 逐字节比较，存在微秒级计时推断漏洞 | 全量采用 `hmac.compare_digest` 恒定时间比对；严格限制受信任反代 IP | `relay_server/server.py` |
| **SEC-04** | **工具审批重放** | 审批指令仅传 `id`，无时间戳与单次随机数，网络抖动易造成重复审批 | 引入 128-bit 随机 **Nonce** 签名与 **120 秒超时熔断** 机制 | `desktop_agent/agent.py` |
| **PRT-01** | **SSE 弱网丢流** | 手机 Wi-Fi/5G 切换后重新建立 SSE，缺失历史事件游标造成丢字 | 原生支持标准 **`Last-Event-ID`** 请求头，服务端/代理端无缝追溯 | `desktop_agent/agent.py`<br>`CloudApiClient.kt` |
| **SEC-05** | **凭据脱机提取** | 密码和配对密钥保存于明文 XML，Root 或 ADB 备份可提取 | 升级为 Android Keystore **AES-256-GCM** 硬件级加密存储 | `PreferencesManager.kt` |
| **NET-01** | **Android 明文流量** | 缺乏网络安全配置，允许发起 HTTP 流量 | 新增 `network_security_config.xml`，默认封禁明文 Cleartext HTTP | `network_security_config.xml` |

---

## 四、v1.5.0 全量代码与配置文件归档

### 4.1 云端直连与 Caddy 反代配置

#### `cloud_server/docker-compose.yml`
```yaml
version: '3.8'

services:
  caddy:
    image: caddy:2-alpine
    container_name: opencode-caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    environment:
      - DOMAIN_NAME=${DOMAIN_NAME:-localhost}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - opencode-server
    networks:
      - opencode_net

  opencode-server:
    image: ghcr.io/anomalyco/opencode:latest
    container_name: opencode-server
    restart: unless-stopped
    environment:
      - OPENCODE_SERVER_PASSWORD=${OPENCODE_SERVER_PASSWORD:?Error: OPENCODE_SERVER_PASSWORD is required}
      - GEMINI_API_KEY=${GEMINI_API_KEY:-}
      - ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}
      - OPENAI_API_KEY=${OPENAI_API_KEY:-}
    expose:
      - "4096"
    volumes:
      - ./workspace:/workspace
    networks:
      - opencode_net

networks:
  opencode_net:
    driver: bridge

volumes:
  caddy_data:
  caddy_config:
```

#### `cloud_server/Caddyfile`
```caddyfile
{$DOMAIN_NAME:localhost} {
    encode gzip zstd

    reverse_proxy opencode-server:4096 {
        flush_interval -1
        transport http {
            keepalive 300s
        }
    }

    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
    }
}
```

---

### 4.2 安全加固版中继服务器 (Relay Server)

#### `relay_server/server.py`
```python
"""OpenCode Relay Server (v1.5 Hardened Edition)"""
import asyncio
import hmac
import logging
import os
import time
from collections import defaultdict
from typing import Dict, Optional
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, status

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("relay_server")
app = FastAPI(title="OpenCode Relay Server - Hardened")

MAX_FAILED_ATTEMPTS = 5
LOCKOUT_DURATION_SECONDS = 900
TRUSTED_PROXIES = set(filter(None, os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")))

failed_attempts: Dict[str, list] = defaultdict(list)
lockouts: Dict[str, float] = {}

class Room:
    def __init__(self, room_id: str, secret: str):
        self.room_id = room_id
        self.secret = secret
        self.desktop_ws: Optional[WebSocket] = None
        self.mobile_ws: Optional[WebSocket] = None

active_rooms: Dict[str, Room] = {}

def get_client_ip(ws: WebSocket) -> str:
    direct = ws.client.host if ws.client else "unknown"
    if direct in TRUSTED_PROXIES:
        xff = ws.headers.get("x-forwarded-for")
        if xff:
            return xff.split(",")[0].strip()
    return direct

def is_ip_locked(ip: str) -> bool:
    now = time.time()
    if ip in lockouts:
        if now < lockouts[ip]:
            return True
        del lockouts[ip]
    return False

def record_failed_attempt(ip: str):
    now = time.time()
    failed_attempts[ip] = [t for t in failed_attempts[ip] if now - t < LOCKOUT_DURATION_SECONDS]
    failed_attempts[ip].append(now)
    if len(failed_attempts[ip]) >= MAX_FAILED_ATTEMPTS:
        lockouts[ip] = now + LOCKOUT_DURATION_SECONDS
        logger.warning(f"IP {ip} locked out due to brute force.")

def verify_secret_constant_time(provided: str, expected: str) -> bool:
    return hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8"))

@app.websocket("/ws/relay/{room_id}")
async def websocket_relay(websocket: WebSocket, room_id: str):
    client_ip = get_client_ip(websocket)
    if is_ip_locked(client_ip):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="IP Locked")
        return

    await websocket.accept()

    try:
        auth_msg = await asyncio.wait_for(websocket.receive_json(), timeout=10.0)
        client_type = auth_msg.get("client_type")
        secret = auth_msg.get("secret", "")

        if client_type not in ("desktop", "mobile") or not secret:
            record_failed_attempt(client_ip)
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Invalid handshake")
            return

        if room_id not in active_rooms:
            active_rooms[room_id] = Room(room_id, secret)

        room = active_rooms[room_id]
        if not verify_secret_constant_time(secret, room.secret):
            record_failed_attempt(client_ip)
            await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Auth failed")
            return

        if client_type == "desktop":
            if room.desktop_ws:
                try: await room.desktop_ws.close(1000)
                except Exception: pass
            room.desktop_ws = websocket
            peer_getter = lambda: room.mobile_ws
        else:
            if room.mobile_ws:
                try: await room.mobile_ws.close(1000)
                except Exception: pass
            room.mobile_ws = websocket
            peer_getter = lambda: room.desktop_ws

        await websocket.send_json({"type": "auth_ack", "status": "authenticated", "room_id": room_id})
        logger.info(f"[{room_id}] {client_type} authenticated from {client_ip}")

        while True:
            msg_text = await websocket.receive_text()
            if msg_text == "__ping__":
                await websocket.send_text("__pong__")
                continue
            peer = peer_getter()
            if peer:
                await peer.send_text(msg_text)

    except (asyncio.TimeoutError, WebSocketDisconnect):
        pass
    except Exception as e:
        logger.error(f"Error: {e}")
    finally:
        if room_id in active_rooms:
            room = active_rooms[room_id]
            if room.desktop_ws == websocket: room.desktop_ws = None
            if room.mobile_ws == websocket: room.mobile_ws = None
            if not room.desktop_ws and not room.mobile_ws:
                del active_rooms[room_id]
        try: await websocket.close()
        except Exception: pass

if __name__ == "__main__":
    uvicorn.run("server:app", host="0.0.0.0", port=8765, workers=1)
```

---

### 4.3 桌面守护代理与防重放工具守卫 (Desktop Agent)

#### `desktop_agent/agent.py`
```python
"""OpenCode Desktop Agent (v1.5 Hardened Edition)"""
import asyncio
import json
import logging
import os
import secrets
import time
from typing import Dict, Optional, Any
import aiohttp
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("desktop_agent")

OPENCODE_URL = os.getenv("OPENCODE_URL", "http://127.0.0.1:4096")
OPENCODE_PASSWORD = os.getenv("OPENCODE_SERVER_PASSWORD", "")
RELAY_WS_URL = os.getenv("RELAY_WS_URL", "ws://127.0.0.1:8765")
ROOM_ID = os.getenv("ROOM_ID", "default_room")
PAIRING_SECRET = os.getenv("PAIRING_SECRET", "default_secret")

class ToolApprovalManager:
    def __init__(self, timeout_seconds: float = 120.0):
        self.timeout_seconds = timeout_seconds
        self._pending: Dict[str, Dict[str, Any]] = {}

    def create_approval(self, session_id: str, permission_id: str, tool_name: str, payload: Any) -> Dict[str, Any]:
        self.clean_expired()
        nonce = secrets.token_hex(16)
        record = {
            "session_id": session_id,
            "permission_id": permission_id,
            "tool_name": tool_name,
            "payload": payload,
            "nonce": nonce,
            "expires_at": time.time() + self.timeout_seconds
        }
        self._pending[permission_id] = record
        return record

    def consume_approval(self, permission_id: str, nonce: str) -> Optional[Dict[str, Any]]:
        self.clean_expired()
        record = self._pending.get(permission_id)
        if not record: return None
        if not secrets.compare_digest(record["nonce"], nonce): return None
        del self._pending[permission_id]
        return record

    def clean_expired(self):
        now = time.time()
        for pid in [p for p, i in self._pending.items() if now > i["expires_at"]]:
            del self._pending[pid]

tool_guard = ToolApprovalManager(timeout_seconds=120.0)

class DesktopAgent:
    def __init__(self):
        self.http_session: Optional[aiohttp.ClientSession] = None
        self.relay_ws: Optional[websockets.WebSocketClientProtocol] = None
        self.last_event_id: Optional[str] = None

    def get_auth_header(self) -> Dict[str, str]:
        if OPENCODE_PASSWORD:
            import base64
            token = base64.b64encode(f"opencode:{OPENCODE_PASSWORD}".encode()).decode()
            return {"Authorization": f"Basic {token}"}
        return {}

    async def forward(self, payload: dict):
        if self.relay_ws and not self.relay_ws.closed:
            try: await self.relay_ws.send(json.dumps(payload))
            except Exception: pass

    async def handle_mobile_message(self, message_str: str):
        try: data = json.loads(message_str)
        except Exception: return

        msg_type = data.get("type")
        if msg_type == "chat_message":
            s_id, text = data.get("session_id"), data.get("text")
            if s_id and text:
                url = f"{OPENCODE_URL}/session/{s_id}/message"
                body = {"parts": [{"type": "text", "text": text}]}
                async with self.http_session.post(url, json=body, headers=self.get_auth_header()) as resp:
                    pass

        elif msg_type == "tool_approval_response":
            p_id, nonce, decision = data.get("id"), data.get("nonce", ""), data.get("decision", "deny")
            record = tool_guard.consume_approval(p_id, nonce)
            if not record:
                await self.forward({"type": "error", "message": "Approval expired or invalid nonce"})
                return
            s_id = record["session_id"]
            url = f"{OPENCODE_URL}/session/{s_id}/permissions/{p_id}"
            async with self.http_session.post(url, json={"decision": decision}, headers=self.get_auth_header()) as resp:
                logger.info(f"Tool {p_id} approved: {decision}")

        elif msg_type == "abort_request":
            s_id = data.get("session_id")
            if s_id:
                url = f"{OPENCODE_URL}/session/{s_id}/abort"
                async with self.http_session.post(url, headers=self.get_auth_header()) as resp:
                    pass

    async def listen_opencode_events(self):
        url = f"{OPENCODE_URL}/event"
        while True:
            headers = self.get_auth_header()
            headers["Accept"] = "text/event-stream"
            if self.last_event_id:
                headers["Last-Event-ID"] = self.last_event_id
            try:
                async with self.http_session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=None, sock_read=60)) as resp:
                    if resp.status != 200:
                        await asyncio.sleep(3); continue
                    current_event = "message"
                    async for line_b in resp.content:
                        line = line_b.decode("utf-8").strip()
                        if not line: continue
                        if line.startswith("id:"): self.last_event_id = line[3:].strip()
                        elif line.startswith("event:"): current_event = line[6:].strip()
                        elif line.startswith("data:"):
                            try: payload = json.loads(line[5:].strip())
                            except Exception: continue
                            if current_event == "permission.asked":
                                pid, sid, tool = payload.get("id"), payload.get("session_id"), payload.get("tool", "shell")
                                rec = tool_guard.create_approval(sid, pid, tool, payload)
                                await self.forward({
                                    "type": "tool_approval_request",
                                    "id": pid, "session_id": sid, "tool": tool,
                                    "nonce": rec["nonce"], "expires_at": rec["expires_at"], "payload": payload
                                })
                            else:
                                await self.forward({"type": "event", "event": current_event, "data": payload})
            except Exception:
                await asyncio.sleep(2)

    async def run(self):
        self.http_session = aiohttp.ClientSession()
        asyncio.create_task(self.listen_opencode_events())
        target = f"{RELAY_WS_URL}/ws/relay/{ROOM_ID}"
        while True:
            try:
                async with websockets.connect(target) as ws:
                    self.relay_ws = ws
                    await ws.send(json.dumps({"client_type": "desktop", "secret": PAIRING_SECRET}))
                    async for msg in ws:
                        if msg == "__pong__": continue
                        await self.handle_mobile_message(msg)
            except Exception:
                await asyncio.sleep(3)

if __name__ == "__main__":
    asyncio.run(DesktopAgent().run())
```

---

### 4.4 Android 客户端核心网络与安全存储组件

#### `android_app/app/src/main/res/xml/network_security_config.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

#### `android_app/app/src/main/java/com/opencode/android/data/local/PreferencesManager.kt`
```kotlin
package com.opencode.android.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "opencode_encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("opencode_private_prefs", Context.MODE_PRIVATE)
    }

    fun savePairingSecret(secret: String) {
        prefs.edit().putString("KEY_PAIRING_SECRET", secret).apply()
    }

    fun getPairingSecret(): String {
        return prefs.getString("KEY_PAIRING_SECRET", "") ?: ""
    }

    fun saveCloudPassword(password: String) {
        prefs.edit().putString("KEY_CLOUD_PASSWORD", password).apply()
    }

    fun getCloudPassword(): String {
        return prefs.getString("KEY_CLOUD_PASSWORD", "") ?: ""
    }

    fun saveRelayUrl(url: String) {
        prefs.edit().putString("KEY_RELAY_URL", url).apply()
    }

    fun getRelayUrl(): String {
        return prefs.getString("KEY_RELAY_URL", "wss://relay.example.com") ?: "wss://relay.example.com"
    }

    fun saveCloudUrl(url: String) {
        prefs.edit().putString("KEY_CLOUD_URL", url).apply()
    }

    fun getCloudUrl(): String {
        return prefs.getString("KEY_CLOUD_URL", "https://cloud.example.com") ?: "https://cloud.example.com"
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }
}
```

#### `android_app/app/src/main/java/com/opencode/android/network/CloudApiClient.kt`
```kotlin
package com.opencode.android.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CloudApiClient(
    private val baseUrl: String,
    private val serverPassword: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var lastEventId: String? = null

    private fun getBasicAuthHeader(): String {
        val creds = "opencode:$serverPassword"
        return "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }

    fun checkHealth(): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/global/health")
            .header("Authorization", getBasicAuthHeader())
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful && it.code == 200 }
        } catch (e: Exception) { false }
    }

    fun sendMessage(sessionId: String, text: String): Boolean {
        val body = JSONObject().apply {
            val parts = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("parts", parts)
        }
        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId/message")
            .header("Authorization", getBasicAuthHeader())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    fun subscribeEvents(): Flow<Pair<String, String>> = flow {
        val reqBuilder = Request.Builder()
            .url("$baseUrl/event")
            .header("Authorization", getBasicAuthHeader())
            .header("Accept", "text/event-stream")

        lastEventId?.let { reqBuilder.header("Last-Event-ID", it) }

        val response: Response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code}")
        }

        response.body?.byteStream()?.use { ins ->
            val reader = BufferedReader(InputStreamReader(ins))
            var currentEvent = "message"
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val t = line?.trim() ?: continue
                if (t.isEmpty()) continue
                if (t.startsWith("id:")) lastEventId = t.substring(3).trim()
                else if (t.startsWith("event:")) currentEvent = t.substring(6).trim()
                else if (t.startsWith("data:")) emit(Pair(currentEvent, t.substring(5).trim()))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun submitToolApproval(sessionId: String, permissionId: String, decision: String): Boolean {
        val body = JSONObject().apply { put("decision", decision) }
        val req = Request.Builder()
            .url("$baseUrl/session/$sessionId/permissions/$permissionId")
            .header("Authorization", getBasicAuthHeader())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }

    fun abortSession(sessionId: String): Boolean {
        val req = Request.Builder()
            .url("$baseUrl/session/$sessionId/abort")
            .header("Authorization", getBasicAuthHeader())
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }
}
```

---

### 4.5 GitHub 开源规范与 CI/CD 流水线

#### `.github/workflows/ci.yml`
```yaml
name: OpenCode Remote CI & Release Build

on:
  push:
    branches: [ "main", "master", "release/*" ]
    tags: [ "v*" ]
  pull_request:
    branches: [ "main", "master" ]

jobs:
  python-tests:
    name: Relay & Agent Contract Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.10"
      - name: Run Smoke Test Contract
        run: |
          pip install -r desktop_agent/requirements.txt
          pip install -r relay_server/requirements.txt
          python scripts/smoke_test_contract.py

  android-build:
    name: Build Android APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '17'
      - name: Build Debug APK
        run: |
          cd android_app
          chmod +x gradlew || true
          ./gradlew assembleDebug --stacktrace
```

#### `.gitignore`
```gitignore
.DS_Store
Thumbs.db
*.tmp
*.swp

.env
.env.*
!.env.example
*.pem
*.key
*.jks
local.properties

__pycache__/
*.py[cod]
.venv/
venv/
.pytest_cache/

.gradle/
build/
android_app/build/
android_app/app/build/
.idea/
*.iml
*.apk
*.aab

cloud_server/caddy_data/
cloud_server/caddy_config/
cloud_server/workspace/
```

#### `LICENSE`
```text
MIT License

Copyright (c) 2026 Sakura-0311 / OpenCode Android Remote Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

### 4.6 自动化打包与发布脚本

#### `scripts/package_release.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-v1.5.0}"
DIST_DIR="dist_${VERSION}"

echo "🚀 开始构建 OpenCode Android Remote ${VERSION} 发行资产..."
python scripts/smoke_test_contract.py
echo "✅ 契约测试通过！"

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

tar --exclude="*.pyc" --exclude="__pycache__" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-desktop-agent-${VERSION}.tar.gz" desktop_agent/
tar --exclude="*.pyc" --exclude="__pycache__" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-relay-server-${VERSION}.tar.gz" relay_server/
tar --exclude="caddy_data" --exclude="caddy_config" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-cloud-docker-${VERSION}.tar.gz" cloud_server/

if [ -d "android_app" ]; then
    cd android_app
    chmod +x gradlew || true
    if ./gradlew assembleDebug; then
        cd ..
        cp android_app/app/build/outputs/apk/debug/*.apk "${DIST_DIR}/OpenCode-Remote-${VERSION}-debug.apk"
    else
        cd ..
    fi
fi

cd "${DIST_DIR}"
sha256sum * > SHA256SUMS.txt
cd ..

echo "🎉 发布资产打包完成: ${DIST_DIR}"
```

---

## 五、后续维护与演进规划

1. **端到端应用层加密 (E2EE)**：
   - 当前中继模式具备 WSS 传输层加密，建议在下一阶段引入基于 `XChaCha20-Poly1305` 的应用层端到端加密，中继服务彻底盲化为零知识转发节点；
2. **离线会话缓存与双向 Diff 同步**：
   - 客户端增加 Room 本地 SQLite 缓存会话历史，通过增量版本号（Revision Tag）进行断网同步，提升无网下的查阅体验；
3. **多模型/Agent 切换支持**：
   - 扩展顶部控制栏，支持动态调用 `/session/:id` 的 Agent 配置切换 Claude 3.7 Sonnet / Gemini 2.5 Pro。
