"""
B2: crash_report.py 单测（先补测试再拆）。
零第三方依赖：python3 relay_server/test_crash_report.py
"""
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crash_report


def check(name, cond, detail=""):
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" -- {detail}" if detail and not cond else ""))
    if not cond:
        raise AssertionError(name)


# 1. sanitize_report_id: 路径穿越与非法字符
check("sanitize 正常 id", crash_report.sanitize_report_id("abc-123") == "abc-123")
check("sanitize 路径穿越", crash_report.sanitize_report_id("../../etc/passwd") == "etcpasswd")
check("sanitize 非 ascii", crash_report.sanitize_report_id("报告-001") == "-001")
check("sanitize 超长截断", len(crash_report.sanitize_report_id("x" * 100)) == 32)
check("sanitize 空串", crash_report.sanitize_report_id("") == "unknown")
check("sanitize 非法字符全清", crash_report.sanitize_report_id("!!!") == "unknown")

# 2. crash_report_allowed: 限流
crash_report._crash_report_hits.clear()
ip = "10.0.0.99"
ok_count = sum(1 for _ in range(crash_report.CRASH_REPORT_MAX_PER_HOUR)
               if crash_report.crash_report_allowed(ip))
check("限流阈值内全过", ok_count == crash_report.CRASH_REPORT_MAX_PER_HOUR)
check("超阈值拒绝", not crash_report.crash_report_allowed(ip))
# 1 小时前的记录不计数
crash_report._crash_report_hits[ip] = [time.time() - 3700] * crash_report.CRASH_REPORT_MAX_PER_HOUR
check("过期记录不计数", crash_report.crash_report_allowed(ip))

# 3. process_crash_report: 脱敏 + 落盘
with tempfile.TemporaryDirectory() as d:
    data = {
        "REPORT_ID": "r-001",
        "APP_VERSION_NAME": "4.3.3",
        "STACK_TRACE": "java.lang.RuntimeException: boom",
        "SECRET_SHOULD_NOT_SAVE": "s3cret",  # 不在白名单，必须丢掉
        "PHONE_MODEL": "iQOO 15T",
    }
    ok, err, path = crash_report.process_crash_report(data, crash_dir=d)
    check("落盘成功", ok and err == "" and path and os.path.isfile(path), f"{ok} {err}")
    saved = json.load(open(path, encoding="utf-8"))
    check("白名单字段保留", saved.get("APP_VERSION_NAME") == "4.3.3")
    check("非白名单字段丢弃", "SECRET_SHOULD_NOT_SAVE" not in saved)

    # 非 dict
    ok, err, _ = crash_report.process_crash_report(["not", "dict"], crash_dir=d)
    check("非 dict 拒绝", not ok and err == "invalid payload")

    # 堆栈截断
    big = {"STACK_TRACE": "x" * 70000}
    ok, _, path = crash_report.process_crash_report(big, crash_dir=d)
    saved = json.load(open(path, encoding="utf-8"))
    check("堆栈截断 64KB", ok and len(saved["STACK_TRACE"]) < 70000 and saved["STACK_TRACE"].endswith("[truncated]"))

# 4. enforce_crash_dir_quota: 超文件数删最旧
with tempfile.TemporaryDirectory() as d:
    old_max = crash_report.CRASH_REPORT_MAX_FILES
    crash_report.CRASH_REPORT_MAX_FILES = 3
    try:
        for i in range(5):
            fp = os.path.join(d, f"crash-2026010{i}-r{i}.json")
            with open(fp, "w") as f:
                f.write("{}")
            # 让 mtime 有序
            ts = time.time() - (5 - i)
            os.utime(fp, (ts, ts))
        crash_report.enforce_crash_dir_quota(d)
        remain = sorted(os.listdir(d))
        check("超量删最旧", len(remain) == 3 and remain[0].endswith("-r2.json"), str(remain))
        # 非 crash-*.json 文件不受影响
        with open(os.path.join(d, "keep.txt"), "w") as f:
            f.write("x")
        crash_report.enforce_crash_dir_quota(d)
        check("非报告文件保留", os.path.exists(os.path.join(d, "keep.txt")))
    finally:
        crash_report.CRASH_REPORT_MAX_FILES = old_max

# 5. 不存在的目录不炸
crash_report.enforce_crash_dir_quota("/tmp/definitely-not-exist-xyz-123")
print("PASS: 不存在的目录静默跳过")

print("\nALL CRASH REPORT UNIT TESTS PASSED")
