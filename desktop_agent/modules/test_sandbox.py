"""文件沙盒黑名单参数化单测：python3 desktop_agent/modules/test_sandbox.py

黑名单原先只匹配最终文件名（fnmatch basename），导致
.aws/credentials 这类路径被放行。本用例锁定 11 条路径的行为：
- 7 条新增拦截（目录组件 + 文件名模式补强后应拒绝）
- notes.txt 应放行
- .env / id_rsa / ../outside 保持拒绝（回归）
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# fileops 经 modules/keystore.py 间接依赖 opencode_api（desktop_agent/ 下）
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 自测用临时项目根，不碰真实目录
_tmp = tempfile.mkdtemp(prefix="sandbox_test_")
os.environ["AGENT_FILE_ROOTS"] = _tmp

import fileops

# 清掉模块级 roots 缓存，确保读到上面的环境变量
fileops._file_roots_cache = None

# 建出测试文件/目录结构
for d in [".aws", ".ssh", ".kube"]:
    os.makedirs(os.path.join(_tmp, d), exist_ok=True)
for name, content in [
    (".aws/credentials", "[default]\naws_secret=xxx"),
    (".git-credentials", "https://user:pass@github.com"),
    ("debug.keystore", "fake-keystore"),
    ("release.jks", "fake-jks"),
    ("keystore.properties", "storePassword=xxx"),
    (".kube/config", "fake-kube-config"),
    ("terraform.tfstate", "fake-tfstate"),
    ("notes.txt", "hello"),
    (".env", "SECRET=xxx"),
    ("id_rsa", "fake-key"),
]:
    with open(os.path.join(_tmp, name), "w") as f:
        f.write(content)

# (相对路径, 应拒绝?)
CASES = [
    (".aws/credentials", True),    # 目录组件 .aws 曾被放行
    (".git-credentials", True),    # 文件名模式曾被放行
    ("debug.keystore", True),      # *.keystore 曾被放行
    ("release.jks", True),         # *.jks 曾被放行
    ("keystore.properties", True), # 文件名曾被放行
    (".kube/config", True),        # 目录组件 .kube 曾被放行
    ("terraform.tfstate", True),   # *.tfstate 曾被放行
    ("notes.txt", False),          # 正常文件放行
    (".env", True),                # 回归：原有黑名单
    ("id_rsa", True),              # 回归：原有黑名单
    ("../outside.txt", True),      # 回归：目录穿越
]

PASS = True
for rel, should_reject in CASES:
    real_path, err = fileops._resolve_sandboxed_path(os.path.join(_tmp, rel))
    rejected = err is not None
    ok = rejected == should_reject
    PASS = PASS and ok
    print(f"[{'PASS' if ok else 'FAIL'}] {rel}: "
          f"期望{'拒绝' if should_reject else '放行'}，实际{'拒绝' if rejected else '放行'}"
          f"{' (' + err + ')' if rejected else ''}")

# 目录列表也应过滤敏感条目（避免出现在手机端）
entries = fileops._list_dir_entries(_tmp)
names = [e["name"] for e in entries]
leaked = [n for n in names if n in (".aws", ".git-credentials", "debug.keystore")]
ok = not leaked
PASS = PASS and ok
print(f"[{'PASS' if ok else 'FAIL'}] 目录列表过滤敏感条目：{'无泄漏' if ok else '泄漏 ' + str(leaked)}")

print("ALL PASS" if PASS else "SOME FAILED")
sys.exit(0 if PASS else 1)
