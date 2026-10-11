# -*- coding: utf-8 -*-
"""真机验收脚本：安装 APK、开启无障碍、跑满一轮定量轮换，并把 §7 要求的真实输出落盘。

产出（全部写入 _evidence/ 目录）：
  run_logcat.txt        SwitchController/SwitchService/SwitchAccessibilityService 连续日志
  focus_timeline.txt    PC 侧每约 0.25s 采样的前台窗口时间线
  s7_1_install.txt      adb install -r 真实输出
  s7_2_focus.txt        dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
  s7_3_resume.txt       logcat -b events | grep -E "am_on_(resume|paused)_called"
  s7_4_create.txt       logcat -b events | grep -E "am_(create_activity|proc_start|finish_activity)"
  s7_5_activities.txt   dumpsys activity activities | grep -E "mResumedActivity|Hist #0"
  summary.txt           统计与结论
"""
import os
import re
import subprocess
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
TMP = os.environ.get("TEMP", "")
ADB = os.path.join(TMP, "dsh-android", "android-sdk", "platform-tools", "adb.exe")
EV = os.path.join(ROOT, "_evidence")
APK = os.path.join(ROOT, "dist", "AppSwitcher-1.0-debug.apk")
PKG = "com.tester.appswitcher"
A11Y = PKG + "/" + PKG + ".SwitchAccessibilityService"
DEADLINE_SEC = 200

os.makedirs(EV, exist_ok=True)


def save(name, text):
    with open(os.path.join(EV, name), "w", encoding="utf-8") as f:
        f.write(text)
    print("[saved] %-24s %d chars" % (name, len(text)))


def run(*args, timeout=180):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout,
                          text=True, encoding="utf-8", errors="ignore")


def sh(cmd, timeout=180):
    return run("shell", cmd, timeout=timeout)


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------- UI 辅助
NODE = re.compile(r"<node\b[^>]*?/?>")


def dump_nodes():
    run("shell", "rm", "-f", "/sdcard/_v.xml")
    run("shell", "uiautomator", "dump", "/sdcard/_v.xml")
    r = run("shell", "cat", "/sdcard/_v.xml")
    out = []
    for m in NODE.finditer(r.stdout):
        tag = m.group(0)
        d = {}
        for k in ("text", "content-desc", "bounds", "class"):
            mm = re.search(k + r'="([^"]*)"', tag)
            d[k] = mm.group(1) if mm else ""
        out.append(d)
    return out


def tap_text(target):
    for n in dump_nodes():
        label = (n["text"] + n["content-desc"]).strip()
        if label == target:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
            if not m:
                continue
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            run("shell", "input", "tap", str(x), str(y))
            log("点击「%s」(%d,%d)" % (target, x, y))
            return True
    return False


def swipe(x1, y1, x2, y2, ms=400):
    run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def focus_now():
    r = run("shell", "dumpsys window")
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+?)/", r.stdout)
    return m.group(1) if m else "?"


def service_running():
    r = run("shell", "dumpsys activity services " + PKG)
    return "isForeground=true" in r.stdout


def main():
    started = time.time()

    # ---------------------------------------------------------- 1. 安装
    r = run("install", "-r", APK)
    save("s7_1_install.txt", r.stdout + r.stderr)
    log("安装完成：%s" % (r.stdout + r.stderr).strip().replace("\n", " / "))

    # ---------------------------------------------------------- 2. 无障碍
    sh("settings put secure enabled_accessibility_services " + A11Y)
    sh("settings put secure accessibility_enabled 1")
    time.sleep(1)
    r = sh("dumpsys accessibility")
    bound = [ln for ln in r.stdout.splitlines() if "appswitcher" in ln]
    save("a11y_state.txt", "\n".join(bound))

    # ---------------------------------------------------------- 3. 启动
    sh("am force-stop " + PKG)
    time.sleep(2)
    sh("settings put secure enabled_accessibility_services " + A11Y)
    sh("settings put secure accessibility_enabled 1")
    run("shell", "am", "start", "-n", PKG + "/.MainActivity")
    # 等主界面真正就位
    for _ in range(20):
        if focus_now() == PKG:
            break
        time.sleep(0.5)
    time.sleep(1.5)

    # 主界面比较长，需要下滑到「开始轮换」按钮
    tapped = False
    for attempt in range(6):
        if tap_text("开始轮换"):
            tapped = True
            break
        swipe(720, 2600, 720, 900, 400)
        time.sleep(1.5)
    if not tapped:
        log("!! 没点中「开始轮换」")
    time.sleep(1.5)

    # ---------------------------------------------------------- 4. 连续抓日志
    lf = open(os.path.join(EV, "run_logcat.txt"), "w", encoding="utf-8", errors="ignore")
    proc = subprocess.Popen(
        [ADB, "logcat", "-v", "time",
         "SwitchController:V", "SwitchService:V", "SwitchAccessibilityService:V", "*:S"],
        stdout=lf, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="ignore",
    )

    # ---------------------------------------------------------- 5. 前台窗口采样
    # 先等循环真的起来
    for _ in range(30):
        if service_running():
            break
        time.sleep(0.5)
    log("循环已启动：%s" % service_running())

    timeline = []
    t0 = time.time()
    last = None
    while time.time() - t0 < DEADLINE_SEC:
        f = focus_now()
        now = time.time()
        if f != last:
            timeline.append("%s  %s" % (time.strftime("%H:%M:%S", time.localtime(now)), f))
            last = f
        if not service_running() and now - t0 > 15:
            break
        time.sleep(0.25)
    log("采样结束，前台变化 %d 次" % len(timeline))

    time.sleep(2)
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except Exception:
        proc.kill()
    lf.close()

    save("focus_timeline.txt", "\n".join(timeline) + "\n")

    # ---------------------------------------------------------- 6. §7 命令真实输出
    sh("cmd appops get " + PKG + " SYSTEM_ALERT_WINDOW")

    r = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    save("s7_2_focus.txt", r.stdout + r.stderr)

    r = sh("logcat -b events -d | grep -E 'am_on_(resume|paused)_called'")
    save("s7_3_resume.txt", (r.stdout + r.stderr).strip() + "\n")

    r = sh("logcat -b events -d | grep -E 'am_(create_activity|proc_start|finish_activity)'")
    save("s7_4_create.txt", (r.stdout + r.stderr).strip() + "\n")

    r = run("shell", "dumpsys activity activities")
    keep = [ln.strip() for ln in r.stdout.splitlines()
            if "mResumedActivity" in ln or "Hist #0" in ln]
    save("s7_5_activities.txt", "\n".join(keep) + "\n")

    # ---------------------------------------------------------- 7. 统计
    txt = open(os.path.join(EV, "run_logcat.txt"), encoding="utf-8", errors="ignore").read()
    ok = re.findall(
        r"(\d\d:\d\d:\d\d\.\d+).*?I[/ ]SwitchController.*?切换成功：(\S+?)（第 (\d+) 次校验", txt)
    fails = re.findall(r"切换到目标应用失败：([^\s（]+)", txt)
    stops = [ln for ln in txt.splitlines() if "停止轮换" in ln]

    lines = []
    lines.append("成功切换次数：%d" % len(ok))
    lines.append("失败次数：%d" % len(fails))
    lines.append("失败目标：%s" % (sorted(set(fails)) if fails else "无"))
    lines.append("停止原因：%s" % (
        re.sub(r"^.*?SwitchController(\(\d+\))?: ", "", stops[-1]).strip() if stops else "未记录"))
    lines.append("")

    def secs(s):
        h, m, rest = s.split(":")
        return int(h) * 3600 + int(m) * 60 + float(rest)

    deltas = []
    for i in range(1, len(ok)):
        deltas.append(secs(ok[i][0]) - secs(ok[i - 1][0]))
    if deltas:
        lines.append("相邻成功切换时间差：n=%d" % len(deltas))
        lines.append("  最小 %.3fs  最大 %.3fs  平均 %.3fs" %
                     (min(deltas), max(deltas), sum(deltas) / len(deltas)))
        lines.append("  满足 2.0s±0.4s 的比例：%d/%d" %
                     (sum(1 for d in deltas if 1.6 <= d <= 2.4), len(deltas)))
        lines.append("  前 5 个时间差：%s" % ", ".join("%.3f" % d for d in deltas[:5]))
        lines.append("  后 5 个时间差：%s" % ", ".join("%.3f" % d for d in deltas[-5:]))
    lines.append("")
    seq = [p for _, p, _ in ok]
    alternations = sum(1 for i in range(1, len(seq)) if seq[i] != seq[i - 1])
    lines.append("A/B 交替：%d 次相邻不同 / %d 对" % (alternations, max(len(seq) - 1, 0)))
    lines.append("序列（去重后循环）：%s" % seq[:6])
    lines.append("校验轮次分布(第几次校验成功)：%s" %
                 sorted(set(int(a) for _, _, a in ok)))
    lines.append("")
    lines.append("PC 侧前台窗口变化次数：%d" % len(timeline))
    lines.append("总耗时：%.0fs" % (time.time() - started))

    save("summary.txt", "\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
