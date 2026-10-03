# OpenCode 云端工作区部署指南 (Cloud Hosted Mode)

云端工作区模式允许你将 OpenCode 7x24 小时运行在任何云服务器（如腾讯云、阿里云、AWS、DigitalOcean、自建 VPS）上。**手机随时随地可以直接通过 App 连入云端写代码，完全无需本地电脑开机。**

---

## 1. 快速一键启动 (Docker Compose)

在你的云服务器上执行：

```bash
# 1. 创建并进入目录
mkdir -p opencode-cloud && cd opencode-cloud

# 2. 下载或编写 docker-compose.yml
# 设置你的大模型 API Key 与自定义访问 Token
export OPENCODE_AUTH_TOKEN="your_custom_secret_token"
export OPENAI_API_KEY="sk-..."       # 或 ANTHROPIC_API_KEY / DEEPSEEK_API_KEY

# 3. 后台一键启动
docker compose up -d
```

启动完成后，云端将在 `http://<云服务器公网IP>:4096` 运行 OpenCode 核心服务。

---

## 2. 生产环境推荐：配置 Nginx + HTTPS (SSL)

为了确保手机与云端通信安全，推荐通过 Nginx 反向代理配置 HTTPS 证书：

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
   * **云端地址**：`https://opencode.yourdomain.com`（或 `http://<IP>:4096`）
   * **访问 Token**：你在启动时设置的 `OPENCODE_AUTH_TOKEN`
   * **工作区目录**：`/workspace`
4. 点击 **连入云端 OpenCode 工作区**。
5. 验证通过后，App 将直接进入云端对话界面，支持在云端拉取 GitHub 代码、安装依赖与执行测试！
