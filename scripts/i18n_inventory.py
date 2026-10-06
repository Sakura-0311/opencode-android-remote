import io
import os
import re

root = "android_app/app/src/main/java"
lit = re.compile(r'"((?:[^"\\]|\\.)*[\u4e00-\u9fff](?:[^"\\]|\\.)*)"')
rows = []
for dp, dn, fn in os.walk(root):
    for f in sorted(fn):
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dp, f)
        for i, line in enumerate(io.open(p, encoding="utf-8"), 1):
            s = line.strip()
            if s.startswith(("//", "*", "/*")):
                continue
            for m in lit.finditer(line.split("//")[0]):
                rows.append((os.path.relpath(p, "android_app").replace("\\", "/"), i, m.group(1)))

byfile = {}
for p, i, t in rows:
    byfile.setdefault(p, []).append((i, t))

L = []
A = L.append
A("# Android 端硬编码文案清单（i18n 收尾）")
A("")
A("> 生成于 v5.0.1（脚本：`scripts/i18n_inventory.py`）。RELEASE_NOTES 的 v4.3.0 条目称")
A("> 「561 条界面文案全部抽取到多语言资源」、v4.10.0 称「十语言补齐」，但实测 Kotlin")
A("> 代码里仍有 **%d 处**含中文的字符串字面量（已排除注释行）——这些文案不跟随语言切换，" % len(rows))
A("> 字面量（已排除注释行）——这些文案不跟随语言切换，漏掉的 `contentDescription` 还会")
A("> 影响无障碍。")
A(">")
A("> 本机没有 Android SDK/JDK，无法编译验证 Kotlin 改动，所以这里**只给清单**，")
A("> 具体替换请在 Android Studio 里做（有实时预览与 Lint）。")
A("")
A("## 建议做法")
A("")
A("1. 逐条移入 `res/values/strings.xml`，并在其余 9 个 `values-*` 目录补译文。")
A("2. Composable 里用 `stringResource(R.string.xxx)`，非 Composable 用 `context.getString(...)`。")
A("3. 先判断是否**用户可见**：纯技术字符串（内部 tag、错误码前缀、正则、日志）不要搬进资源。")
A("4. 屏幕阅读器用的 `contentDescription` 同样要资源化。")
A("5. 改完跑 `gradle :app:lintDebug`（`MissingTranslation` / `HardcodedText` 会报出来）。")
A("")
A("## 按文件统计")
A("")
A("| 文件 | 处数 |")
A("| --- | --- |")
for p in sorted(byfile, key=lambda k: (-len(byfile[k]), k)):
    A("| `%s` | %d |" % (p, len(byfile[p])))
A("")
A("## 明细")
A("")
for p in sorted(byfile, key=lambda k: (-len(byfile[k]), k)):
    A("### `%s`" % p)
    A("")
    A("| 行 | 文案 |")
    A("| --- | --- |")
    for i, t in byfile[p]:
        A("| %d | `%s` |" % (i, t.replace("|", "\\|")))
    A("")

io.open("docs/I18N_TODO.md", "w", encoding="utf-8").write("\n".join(L))
print("wrote docs/I18N_TODO.md: rows=%d files=%d" % (len(rows), len(byfile)))
