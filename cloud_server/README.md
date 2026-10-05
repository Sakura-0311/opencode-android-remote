# OpenCode 云端工作区部署指南 (Cloud Hosted Mode)

云端工作区模式允许你将 OpenCode 7x24 小时运行在任何云服务器（如腾讯云、阿里云、AWS、DigitalOcean、自建 VPS）上。**手机随时随地可以直接通过 App 连入云端写代码，完全无需本地电脑开机。**

---

## ⚠️ 生产安全规范 (P0-4 安全加固)

为杜绝携带模型 Key 及具备执行 Shell 命令的 Agent 裸奔公网：
1. **容器网络隔离**：OpenCode 容器端口**不发布到宿主机**（compose 里只用 `expose`，
   仅 Docker 内部网络可达）；唯一的公网入口是 Caddy（80/443）。不要加 `ports` 把
   4096 直接暴露出去。
2. **强制密码保护**：必须配置 `OPENCODE_SERVER_PASSWORD` 强密码（compose 用
   `${OPENCODE_SERVER_PASSWORD:?}` 强制显式指定，严禁弱口令）。
3. **强制启用 HTTPS**：Caddy 自动申请 Let's Encrypt 证书对外提供 HTTPS。
4. **暴力破解防护**（v4.7.0/D3）：Caddy 本身只有 HTTP Basic 认证、无失败限流。
   建议二选一：
   - 用 Cloudflare Tunnel / Zero Trust 在边缘挡（推荐）；
   - 或在宿主机装 fail2ban，监控 Caddy 日志里 401 密集的 IP 并封禁。

---

## 1. 快速一键启动 (Docker Compose)

在你的云服务器上执行：

```bash
# 1. 创建并进入目录
mkdir -p opencode-cloud && cd opencode-cloud

# 2. 设置你的大模型 API Key 与访问密码（强制要求显式指定，严禁使用弱口令）
export OPENCODE_SERVER_PASSWORD="your_custom_secure_password_min_16_chars"
export GEMINI_API_KEY="AIzaSy..."          # Google Gemini 官方 API Key
export OPENAI_API_KEY="sk-..."            # 或 ANTHROPIC_API_KEY / DEEPSEEK_API_KEY

# 3. 后台一键启动
docker compose up -d
```

启动完成后，OpenCode 只在 Docker 内部网络监听（不对宿主机发布端口），
对外统一走 Caddy 的 80/443（自动 HTTPS）。

---

## 2. 生产环境推荐：配置 Nginx + HTTPS (SSL)

通过 Nginx 对外提供带有 TLS 加密的访问：

```nginx
server {
    listen 443 ssl http2;
    server_name opencode.yourdomain.com;

    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:4096;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_buffering off; # 必须关闭缓冲以支持 SSE 流式实时输出
    }
}
```

---

## 3. 在 Android App 中连接云端

1. 打开 OpenCode Android App。
2. 在配对页面顶部切换到 **【☁️ 云端工作区】** 选项卡。
3. 填入：
   * **云端地址**：`https://opencode.yourdomain.com`
   * **访问 Token / 密码**：你在启动时设置的 `OPENCODE_SERVER_PASSWORD`
   * **工作区目录**：`/workspace`
4. 点击 **连入云端 OpenCode 工作区**。
5. 验证通过后，App 将直接进入云端对话界面，支持在云端拉取 GitHub 代码、安装依赖与执行测试！
