# OpenCode Desktop Bridge Agent

电脑端桥接：把手机 App 的指令转给本地 `opencode serve`，再把回复流式推回。

## 可执行文件（C1，打包版）

不想装 Python 的用户可直接用打包版（一文件，内嵌解释器与依赖）：

1. 去 GitHub Actions 的 **Desktop Agent binaries** workflow，
   下载对应平台的 artifact：
   `opencode-desktop-agent-Windows-X64` /
   `opencode-desktop-agent-macOS-ARM64` /
   `opencode-desktop-agent-Linux-X64`
2. 解压得到 `opencode-desktop-agent`（Windows 下是 `.exe`）
3. 运行：
   ```bash
   # 一键扫码配对（首次）
   ./opencode-desktop-agent pair
   # 启动桥接（常驻）
   ./opencode-desktop-agent run
   ```

常用参数：`--account-id` / `--secret` / `--relay-url` / `--workspace`
（优先级：CLI > 环境变量 > 内置默认），详见 `--help`。

## 源码运行

```bash
cd desktop_agent
pip install -r requirements.txt
python agent.py pair   # 一键扫码配对
python agent.py run    # 启动桥接
```

## 本地打包

```bash
cd desktop_agent
pip install -r requirements.txt pyinstaller
pyinstaller agent.spec
# 产物：dist/opencode-desktop-agent[.exe]
```

CI（三平台矩阵）见 `.github/workflows/desktop-agent.yml`：
`desktop_agent/` 有变更的 push、每周日、手动触发均会构建。
