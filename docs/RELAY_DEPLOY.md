# Relay 5 分钟部署（C2）

手机 App 与电脑 agent 之间的中继服务器。一台有公网 IP 的机器即可。

## 前提

- Docker + Docker Compose（Docker Desktop 自带；Linux 按官方文档装 docker-ce）
- 一个公网可访问的端口（默认 8765），或反向代理后的域名

## 步骤（约 5 分钟）

```bash
# 1. 拿代码（只要 relay_server 目录也行）
git clone https://github.com/Sakura-0311/opencode-android-remote.git
cd opencode-android-remote/relay_server

# 2. 配建房令牌（强烈建议：防止陌生人建房占用）
cp .env.example .env
# 编辑 .env，把 RELAY_ADMIN_TOKEN 换成随机字符串
#   openssl rand -hex 24

# 3. 一键启动
docker compose up -d --build

# 4. 验证
docker compose ps            # relay 显示 healthy
curl http://127.0.0.1:8765/api/stats
```

看到 `{"status":"ok","protocol_version":4,...}` 即部署成功。

## 手机 / 电脑怎么连

- 手机 App → 配对 → 中继地址填 `http://你的服务器IP:8765`
  （有域名+HTTPS 则填 `https://你的域名`，App 会自动用 wss）
- 电脑 agent：
  ```bash
  ./opencode-desktop-agent pair --relay-url http://你的服务器IP:8765
  # 扫码或手动输入后
  ./opencode-desktop-agent run --relay-url http://你的服务器IP:8765
  ```
  首次建房需在 agent 的 auth 中携带 `admin_token`
  （与 `.env` 里的 `RELAY_ADMIN_TOKEN` 一致，`--admin-token` 参数）。

## 运维

| 操作 | 命令 |
|---|---|
| 看状态（含健康检查） | `docker compose ps` |
| 看日志 | `docker compose logs -f relay` |
| 重启 | `docker compose restart relay` |
| 停服 | `docker compose down`（数据在 `relay-data` 卷中保留） |
| 升级 | `git pull` 后 `docker compose up -d --build` |

- **自动重启**：`restart: unless-stopped`，宿主机重启或进程崩溃后自动恢复。
- **健康检查**：每 30 秒请求 `/api/stats`，连续 3 次失败标为 unhealthy。
- **数据位置**：具名卷 `relay-data`（房间状态、崩溃上报文件）。

## HTTPS（推荐）

最省事：前面加 Caddy/Nginx 反代做 TLS，relay 保持 http。
直连 wss：在 `.env` 里配 `SSL_CERTFILE` / `SSL_KEYFILE` 并把证书目录
挂进容器（compose 另加 volumes 映射）。

> 地址选择：`ws://` 只用于同一局域网/可信网络调试；任何经公网的
> release 部署请用 `wss://`（或经反代的 https），否则 secret 与消息明文传输。

## 崩溃上报接收端（可选，默认关闭）

`.env` 里 `RELAY_ENABLE_CRASH_REPORT=1` 后重启，relay 开始接收已 opt-in
手机的崩溃上报，存 `./data` 卷的 `crash_reports/` 目录（只收白名单字段，
按 IP 限流）。详见 PRIVACY.md「崩溃上报」。
