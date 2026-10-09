# OpenCode Android Remote

[中文版](README.md) | **English**

Put the OpenCode coding agent — on your PC or in the cloud — into your phone.

A native Android client built with **Kotlin + Jetpack Compose**, supporting two modes
for mobile use: **Desktop Relay** (remote-control your own machine) and
**Cloud Hosted** (connect directly to a cloud workspace).

> **Maintenance status:** v5.1.0 is the current sealed release. Protocol v4 is frozen;
> going forward only compatibility fixes and security updates, no new features.
> Privacy: [PRIVACY.md](PRIVACY.md) — your data stays on your own phone and is only
> sent to servers you configured yourself.

---

## ⚡ Get started in 3 minutes

### Mode A: Desktop Relay (remote-control your PC)
Remotely control OpenCode and the local Git repos on your office PC or home dev machine
through a relay service:

1. **Start the OpenCode service on your PC**:
   ```bash
   opencode serve --port 4096
   ```
   > **Upstream-compatible version (B1)**: verified against opencode **1.18.x**
   > (contract pinned to the `/doc` of 1.18.34).
   > On startup the agent pulls `GET /doc` to verify the endpoint contract: if key
   > endpoints are missing it **refuses to start and lists what's missing**;
   > older versions without `/doc` fall back to a warning. Endpoint table:
   > `desktop_agent/endpoints.py`.
2. **Start the bridge agent on your PC**:
   ```bash
   cd desktop_agent
   pip install -r requirements.txt
   python agent.py user_dev_001 wss://your-relay-domain.com:8765
   ```
   *The terminal prints a 128-bit high-entropy random Secret and a pairing QR code.*
   *P0-2: use wss:// (TLS) in production; ws://localhost / ws://127.0.0.1 is for
   local development only.*
3. **Connect from the phone**:
   Open the app, enter the room name `user_dev_001`, tap the **Paste** button next to
   the Secret field (or type the key manually), then connect to start coding remotely.

---

### Mode B: Cloud Hosted (direct cloud workspace)
No PC needed — connect straight to an OpenCode instance running 24/7 on your own
cloud server (VPS/Docker):

1. **Start OpenCode on the cloud server**:
   ```bash
   cd cloud_server
   # Set the access password and model key
   export OPENCODE_SERVER_PASSWORD="your_secure_password"
   export GEMINI_API_KEY="AIzaSy..." # or OPENAI_API_KEY / ANTHROPIC_API_KEY
   docker compose up -d
   ```
2. **Put Nginx HTTPS reverse proxy in front** (TLS is mandatory).
3. **Connect from the phone**:
   Open the app, switch to the **Cloud Workspace** tab, enter the cloud address and
   access password to connect directly.

---

## 🔒 Security notes and architecture boundaries (must read)

1. **Transport security and data boundaries**:
   * By default **transport-layer encryption (TLS / WSS)** protects the connection;
     the relay server can then see forwarded commands and responses in memory.
   * Optional **end-to-end encryption (E2EE, off by default)**: after the phone and
     the PC negotiate an X25519 key, prompts, reply streams, approval requests and
     file contents are encrypted with ChaCha20-Poly1305 and the relay only forwards
     blindly; after negotiation, control messages without a valid envelope are
     rejected (fail-closed), and ciphertext carries sequence numbers against replay.
     See `docs/E2EE_WIRE_v1.md` and `docs/E2EE_THREAT_MODEL.md` (with an honest
     statement of the protection scope).
   * Since v5.0.1, E2EE has **no silent multi-peer downgrade**: when two or more
     phones pair to the same room, the desktop side cannot tell whom to encrypt for
     and will **refuse to send content messages, returning `E2EE_UNAVAILABLE`**,
     instead of quietly falling back to plaintext. Multiple phones online at once is
     out of scope for now (see "multi-mobile not considered" in the threat model) —
     one phone per room.
   * **Strongly recommended: only use a relay_server you deployed yourself. Never
     connect your code and control access to an unverified third-party public relay.**
2. **Secret key and room protection**:
   * The `Secret` is a 128-bit high-entropy auth key, stored on the PC with `0600`
     restricted permissions (readable/writable only by the current OS user).
   * Since v4.7.0 the agent no longer prints the secret in plaintext at startup
     (masked display); it is printed once when first generated. To view it later,
     run `python agent.py pair --show-secret`.
   * Every connection must complete key verification within 10 seconds of the
     handshake; a wrong key is rejected and disconnected immediately.
   * 5 consecutive auth failures from one IP trigger an automatic 15-minute ban,
     preventing brute force. Since v5.0.3, bans are accounted per
     `(source IP, account_id)`: when the account is known, an attacker can only get
     their own account banned and won't take down other users behind the same
     reverse-proxy egress IP (failures with unknown account — handshake timeouts,
     malformed first frames — are still counted per IP).
   * Since v5.0.1, `TRUSTED_PROXIES` **defaults to empty = ignores `X-Forwarded-For`**:
     previously loopback was trusted by default, so combined with the default
     "bind loopback only" deployment, any local process could forge XFF to fake its
     source IP (bypassing rate limits, or getting legitimate users banned). Only
     fill in your reverse proxy's egress IP when you actually run one.
   * Since v5.0.1, `RELAY_ALLOWED_ORIGINS` is available: the native app sends no
     `Origin` and is unaffected; when left empty, any browser connection carrying
     an `Origin` is rejected, preventing "any web page connecting straight to your
     local relay".
   * Since v4.7.0 the relay **refuses to start without `RELAY_ADMIN_TOKEN`**
     (missing/too-short = won't start); room creation requires the admin token,
     preventing room squatting.
   * Since v4.8.0 the app shows a plaintext warning when you type `ws://` /
     `http://` on the pairing page, and release builds require a second
     confirmation before connecting.
   * The client sets `allowBackup="false"`, so sensitive keys never leak through
     system cloud backups.
3. **Hardening for cloud mode**:
   * The Docker container binds only to the host's `127.0.0.1:4096`; never expose it
     passwordless on `0.0.0.0` to the public internet.

---

## 🌟 Core features

* **Real official protocol contract**: strictly follows the OpenCode official API
  contract (`/global/health` health check, `/session` session management,
  `/session/:id/message` prompt interaction, `/event` SSE incremental subscription,
  `/session/:id/abort` task abort).
* **Real tool approval on the phone + compact diff preview**:
  * When the AI requests file changes, approvals are pushed in real time via the
    official `/permissions` protocol.
  * Compact diff preview optimized for small phone screens: unmodified context is
    auto-folded, only added (green) and removed (red) lines are highlighted, with
    expand-to-full-code support.
  * Approval decisions are sent back through the official authorization API — no
    fake command forwarding, ever.
* **One-tap connectivity test and tunnel troubleshooting**:
  * End-to-end connectivity and network speed test from the pairing page.
  * Precise handling of Cloudflare Tunnel signature status codes (HTTP 521, 522,
    524, Zero Trust blocks) plus SakuraFrp/frp troubleshooting hints for China.
* **Background keep-alive with live progress**:
  * While a task runs, the notification shade shows live elapsed time (m:ss) and
    step count.
  * Android 13+ runtime notification permission is requested dynamically; tool
    approvals trigger strong vibration alerts.
* **Session management and Markdown export**:
  * Real-time sync of the server session list, with pinning, tag-based grouping and
    batch archiving.
  * One-tap export of session history to standard Markdown — copy to clipboard or
    invoke the system share sheet.
* **Local chat history**:
  * Since v5.0.3, the 200 most recent messages per session are cached (purely
    local, no credentials). Dismissing a recent task or having the process
    reclaimed by the system no longer wipes the conversation; switching sessions
    no longer clears the screen either.

---

## 📁 Project layout

```
.
├── README.md / README_EN.md               # Usage & security guide (CN / EN)
├── PRIVACY.md                             # Privacy statement
├── RELEASE_NOTES.md                       # Per-version release log
├── TESTING_CHECKLIST.md                   # Manual testing checklist
├── docs/                                  # Design docs (E2EE wire protocol,
│                                          # threat model, migrations, security…)
├── cloud_server/                          # Cloud direct-connect Docker deploy
│   ├── docker-compose.yml                 # Binds 127.0.0.1 only (secure)
│   ├── Caddyfile
│   └── README.md                          # Cloud deploy + reverse-proxy guide
├── desktop_agent/                         # PC bridge agent (Python)
│   ├── agent.py                           # Long-lived connection daemon + /event
│   ├── opencode_api.py                    # Official REST/SSE HTTP API client
│   ├── endpoints.py                       # Endpoint contract table (/doc)
│   └── requirements.txt
├── relay_server/                          # Relay server (FastAPI)
│   ├── server.py                          # WSS relay routing, anti-brute-force
│   │                                      # rate limiting and heartbeats
│   ├── crash_report.py                    # Crash reporting endpoint
│   ├── Dockerfile
│   └── requirements.txt
├── android_app/                           # Native Android client (Kotlin+Compose)
│   └── app/src/main/
│       ├── AndroidManifest.xml            # No cloud backup; permissions & foreground service
│       └── java/com/opencode/android/
│           ├── MainActivity.kt            # Runtime notification permission
│           ├── data/local/PreferencesManager.kt  # Local persistence, no fake defaults
│           ├── network/
│           │   ├── CloudApiClient.kt      # Real-contract cloud client + SSE
│           │   ├── RelayWebSocketClient.kt # Relay long connection, real session/approval protocol
│           │   └── TunnelDiagnosticsHelper.kt # Connectivity test & tunnel troubleshooting
│           ├── service/OpenCodeKeepAliveService.kt # Foreground keep-alive + crash capture
│           ├── ui/components/
│           │   ├── CompactDiffView.kt     # Compact diff preview
│           │   └── ToolApprovalDialog.kt  # Real tool-approval dialog
│           └── ui/screens/
│               ├── ChatScreen.kt          # Chat screen, search highlight, anti-autoscroll
│               └── PairingScreen.kt       # Clipboard one-tap secret paste, dual-mode pairing
├── e2e/                                   # End-to-end tests (Maestro)
├── tests/                                 # E2EE protocol tests
└── scripts/                               # Contract smoke tests, i18n tooling
```

---

## License

MIT — see [LICENSE](LICENSE).
