# -*- mode: python ; coding: utf-8 -*-
"""
C1: desktop_agent PyInstaller 打包配置（一文件可执行程序）。

用法（本地）:
    cd desktop_agent
    pip install -r requirements.txt pyinstaller
    pyinstaller agent.spec
    # 产物: dist/opencode-desktop-agent[.exe]

CI（三平台）见 .github/workflows/desktop-agent.yml。
"""
import os

block_cipher = None

a = Analysis(
    ["agent.py"],
    pathex=[os.path.abspath(".")],
    binaries=[],
    datas=[],
    hiddenimports=[
        # v2.6 模块化拆分后的子模块（import 分析一般能抓到，这里兜底）
        "modules.config",
        "modules.secrets",
        "modules.state",
        "modules.fileops",
        "modules.protocol",
        "modules.e2ee",
        "endpoints",
        "opencode_api",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # 测试/开发期脚本不进包，减小体积
        "modules.gen_interop_vector",
        "modules.test_e2ee",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="opencode-desktop-agent",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,  # 控制台程序（bridge 常驻，日志打 stdout）
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
