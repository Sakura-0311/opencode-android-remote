# OpenCode 云端工作区部署指南 (Cloud Hosted Mode)

云端工作区模式允许你将 OpenCode 7x24 小时运行在任何云服务器（如腾讯云、阿里云、AWS、DigitalOcean、自建 VPS）上。**手机随时随地可以直接通过 App 连入云端写代码，完全无需本地电脑开机。**

---

## ⚠️ 生产安全规范 (P0-4 安全加固)

为杜绝携带模型 Key 及具备执行 Shell 命令的 Agent 裸奔公网：
1. **本地回环绑定**：OpenCode 容器端口仅绑定在服务器内部 `127.0.0.1:4096`，禁止直接对公网 `0.0.0.0` 开放。
2. **强制密码保护**：必须配置 `OPENCODE_SERVER_PASSWORD` 强密码。
3. **强制启用 HTTPS**：必须经由反向代理（Nginx / Caddy / Cloudflare Tunnel）配置 SSL 证书对外提供服务。

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

启动完成后，容器将在宿主机 `127.0.0.1:4096` 运行服务。

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
