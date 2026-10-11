# -*- coding: utf-8 -*-
"""真机行为验证：悬浮面板拖动 / 暂停继续 / 面板停止 / 通知停止 / 定时自动停止。

用法：
    python _verify2.py panel     悬浮面板拖动 + 暂停/继续 + 面板「停止」
    python _verify2.py notif     常驻通知「停止」按钮
    python _verify2.py timer     定时自动停止（1 分钟）
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TC = os.environ.get("TC") or os.path.join(os.environ["TEMP"], "dsh-android")
ADB = os.path.join(TC, "android-sdk", "platform-tools", "adb.exe")
EV = os.path.join(HERE, "_evidence")
PKG = "com.tester.appswitcher"
A11Y = PKG + "/" + PKG + ".SwitchAccessibilityService"
APK = os.path.join(HERE, "dist", "AppSwitcher-1.0-debug.apk")
os.makedirs(EV, exist_ok=True)

NODE = re.compile(r"<node\b[^>]*?/?>")


def adb(*args, timeout=180):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout,
                          text=True, encoding="utf-8", errors="ignore")


def sh(cmd):
    return adb("shell", cmd)


def out(*args, **kw):
    r = adb(*args, **kw)
    return (r.stdout or "") + (r.stderr or "")


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg))
    sys.stdout.flush()


def nodes():
    adb("shell", "rm", "-f", "/sdcard/_v2.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/_v2.xml")
    r = adb("shell", "cat", "/sdcard/_v2.xml")
    res = []
    for tag in NODE.findall(r.stdout or ""):
        d = {}
        for k in ("text", "content-desc", "resource-id", "class", "bounds",
                  "clickable", "checked", "scrollable", "package"):
            m = re.search(k + r'="([^"]*)"', tag)
            d[k] = m.group(1) if m else ""
        res.append(d)
    return res


def center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find(*texts, contains=False):
    """返回第一个匹配节点的 (node, (cx,cy))。"""
    for n in nodes():
        t = (n["text"] + n["content-desc"]).strip()
        if not t:
            continue
        for want in texts:
            if (want in t) if contains else (t == want):
                c = center(n["bounds"])
                if c:
                    return n, c
    return None, None


def dump_all(title=""):
    if title:
        log("---- UI (%s) ----" % title)
    for n in nodes():
        t = (n["text"] + n["content-desc"]).strip()
        if t:
            log("   %-30s %-28s %-22s clickable=%s %s"
                % (t[:30], n["class"].split(".")[-1], n["package"], n["clickable"], n["bounds"]))
    sys.stdout.flush()


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def swipe(x1, y1, x2, y2, ms=500):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def focus():
    r = adb("shell", "dumpsys", "window")
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s+(\S+?)/", r.stdout)
    return m.group(1) if m else "?"


def service_running():
    r = adb("shell", "dumpsys", "activity", "services", PKG)
    return "SwitchService" in r.stdout


def prefs_xml(a, b, interval, mode, rounds, minutes):
    return (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        "    <string name=\"package_a\">%s</string>\n"
        "    <string name=\"package_b\">%s</string>\n"
        "    <int name=\"interval_ms\" value=\"%d\" />\n"
        "    <int name=\"stop_mode\" value=\"%d\" />\n"
        "    <int name=\"stop_rounds\" value=\"%d\" />\n"
        "    <int name=\"stop_minutes\" value=\"%d\" />\n"
        "</map>\n" % (a, b, interval, mode, rounds, minutes))


def set_prefs(**kw):
    adb("shell", "am", "force-stop", PKG)
    p = os.path.join(EV, "_prefs.xml")
    open(p, "w", encoding="utf-8").write(prefs_xml(**kw))
    adb("push", p, "/data/local/tmp/_prefs.xml")
    r = adb("shell", "run-as", PKG, "cp", "/data/local/tmp/_prefs.xml",
            "shared_prefs/app_switcher_prefs.xml")
    log("写入配置: %s" % (r.stdout or "").strip())
    log("读回: " + (adb("shell", "run-as", PKG, "cat",
                    "shared_prefs/app_switcher_prefs.xml").stdout or "").replace("\n", " "))


def enable_a11y():
    sh("settings put secure enabled_accessibility_services " + A11Y)
    sh("settings put secure accessibility_enabled 1")
    time.sleep(1.5)
    r = adb("shell", "dumpsys", "accessibility")
    return A11Y in r.stdout and "Bound services" in r.stdout


def dismiss_coloros_safety():
    """ColorOS 用 settings put 开启无障碍后会弹「检测到…获取无障碍权限」，需要点「保持开启」。"""
    for _ in range(3):
        n, c = find("保持开启", contains=True)
        if not n:
            return False
        log("弹窗：%s -> 点「保持开启」@%s" % (n["text"][:24], c))
        tap(*c)
        time.sleep(1.5)
    return True


def launch():
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    for _ in range(20):
        if focus() == PKG:
            break
        time.sleep(0.5)
    time.sleep(2)
    dismiss_coloros_safety()
    # 首次启动可能弹通知权限对话框
    n, c = find("允许", "Allow", "仅在使用该应用时允许")
    if n and "permissioncontroller" in (n["package"] or "") + focus():
        tap(*c)
        log("点了通知权限「允许」")
        time.sleep(1)


def start_switching():
    """下滑到「开始轮换」并点击；实在找不到就按实测固定坐标兜底点击。"""
    for _ in range(5):
        n, c = find("开始轮换")
        if n:
            tap(*c)
            log("点击「开始轮换」%s" % (c,))
            return True
        # 系统弹窗挡着时先把弹窗处理掉再继续
        dismiss_coloros_safety()
        swipe(720, 2600, 720, 900, 400)
        time.sleep(1.2)
    # 兜底：实测「开始轮换」固定位于 [64,2601][700,2793]，中心 (382,2697)
    log("!! UI 未匹配到，按固定坐标 (382,2697) 兜底点击")
    tap(382, 2697)
    time.sleep(1.0)
    return True


def start_capture(name):
    f = open(os.path.join(EV, name), "w", encoding="utf-8", errors="ignore")
    p = subprocess.Popen(
        [ADB, "logcat", "-v", "time", "-s",
         "SwitchController:V", "SwitchService:V", "SwitchAccessibilityService:V", "FloatingPanel:V"],
        stdout=f, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="ignore")
    return p, f


def count_switch(path):
    t = open(path, encoding="utf-8", errors="ignore").read()
    return len(re.findall(r"第 \d+ 次切换", t))


def tail(path, n=12):
    lines = open(path, encoding="utf-8", errors="ignore").read().splitlines()
    return lines[-n:]


def shot(name):
    sh("screencap -p /sdcard/%s.png" % name)
    adb("pull", "/sdcard/%s.png" % name, os.path.join(EV, name + ".png"))
    log("截图 _evidence/%s.png" % name)


# 悬浮面板默认右上角；实测坐标（设备像素，1440x3168）
PANEL_HEADER = (1017, 513)
PANEL_BTN_A = (766, 681)    # ▶ 开始
PANEL_BTN_B = (1012, 681)   # ⏸ 暂停 / ⏸ 继续
PANEL_BTN_C = (1258, 681)   # ■ 停止


def prelude(mode, minutes=10, rounds=100, interval=2000):
    log("安装: " + out("install", "-r", APK).strip().splitlines()[-1])
    set_prefs(a="com.lerist.fakelocation", b="com.vphonegaga.titan",
              interval=interval, mode=mode, rounds=rounds, minutes=minutes)
    log("无障碍已开启: %s" % enable_a11y())
    launch()
    dismiss_coloros_safety()


# --------------------------------------------------------------------------
def stage_panel():
    log("== 阶段 1：暂停 / 继续 / 悬浮面板停止（坐标点击） ==")
    prelude(mode=0)
    logpath = os.path.join(EV, "run2_panel.txt")
    p, f = start_capture("run2_panel.txt")
    try:
        if not start_switching():
            return
        time.sleep(6)
        dismiss_coloros_safety()
        n0 = count_switch(logpath)
        log("运行中，已切换 %d 次" % n0)
        shot("t1_running")

        # ---- 暂停 ----
        tap(*PANEL_BTN_B)
        log("点击「暂停」@%s" % (PANEL_BTN_B,))
        time.sleep(1.5)
        shot("t2_paused")
        before = count_switch(logpath)
        time.sleep(5)
        after = count_switch(logpath)
        log("暂停后 5 秒内新增切换：%d（应为 0）" % (after - before))

        # ---- 继续 ----
        tap(*PANEL_BTN_B)
        log("点击「继续」@%s" % (PANEL_BTN_B,))
        time.sleep(1.5)
        shot("t3_resumed")
        before = count_switch(logpath)
        time.sleep(5)
        after = count_switch(logpath)
        log("继续后 5 秒内新增切换：%d（应 > 0）" % (after - before))

        # ---- 面板停止 ----
        tap(*PANEL_BTN_C)
        log("点击「停止」@%s" % (PANEL_BTN_C,))
        time.sleep(3)
        shot("t4_panel_stopped")
        log("服务仍在运行：%s（应为 False）" % service_running())
        log("最近日志：")
        for ln in tail(logpath, 12):
            log("   " + ln)
    finally:
        p.terminate()
        f.close()
        time.sleep(0.5)


def stage_drag():
    log("== 阶段 1b：悬浮面板拖动 ==")
    prelude(mode=0)
    logpath = os.path.join(EV, "run2_drag.txt")
    p, f = start_capture("run2_drag.txt")
    try:
        if not start_switching():
            return
        time.sleep(6)
        dismiss_coloros_safety()
        shot("d1_before_drag")
        log("拖动：%s -> %s" % (PANEL_HEADER, (640, 1200)))
        adb("shell", "input", "swipe", str(PANEL_HEADER[0]), str(PANEL_HEADER[1]),
            "640", "1200", "900")
        time.sleep(1.5)
        shot("d2_after_drag")
        log("拖动后服务仍在运行：%s，切换 %d 次" % (service_running(), count_switch(logpath)))
        # 按拖动后的新位置点「停止」：面板中心右移 -377, 下移 +687
        nx = PANEL_BTN_C[0] - (PANEL_HEADER[0] - 640)
        ny = PANEL_BTN_C[1] + (1200 - PANEL_HEADER[1])
        tap(nx, ny)
        log("按新坐标点「停止」@(%d,%d)" % (nx, ny))
        time.sleep(3)
        shot("d3_stopped")
        log("服务仍在运行：%s（应为 False）" % service_running())
    finally:
        p.terminate()
        f.close()
        time.sleep(0.5)


def stage_notif():
    log("== 阶段 2：常驻通知「停止」按钮 ==")
    prelude(mode=0)
    logpath = os.path.join(EV, "run2_notif.txt")
    p, f = start_capture("run2_notif.txt")
    try:
        if not start_switching():
            return
        time.sleep(5)
        log("切换已开始：%d 次" % count_switch(logpath))
        sh("cmd statusbar expand-notifications")
        time.sleep(2.5)
        shot("n1_shade")
        dump_all("通知栏")
        n, c = find("停止", contains=True)
        if not n:
            log("!! 通知栏里没找到「停止」")
        else:
            tap(*c)
            log("点击通知「停止」@%s" % (c,))
            time.sleep(3)
            log("服务仍在运行：%s（应为 False）" % service_running())
        sh("cmd statusbar collapse")
        log("最近日志：")
        for ln in tail(logpath, 12):
            log("   " + ln)
    finally:
        p.terminate()
        f.close()
        time.sleep(0.5)


def stage_timer():
    log("== 阶段 3：定时自动停止（1 分钟） ==")
    prelude(mode=2, minutes=1)
    logpath = os.path.join(EV, "run2_timer.txt")
    p, f = start_capture("run2_timer.txt")
    try:
        if not start_switching():
            return
        time.sleep(3)
        shot("m1_timer_start")
        t0 = time.time()
        while time.time() - t0 < 100:
            if not service_running() and time.time() - t0 > 20:
                break
            time.sleep(2)
        log("耗时 %.0fs，服务仍在运行：%s" % (time.time() - t0, service_running()))
        shot("m2_timer_stopped")
        t = open(logpath, encoding="utf-8", errors="ignore").read()
        for ln in tail(logpath, 8):
            log("   " + ln)
        m = re.search(r"停止轮换：(.*)", t)
        log("停止原因：%s" % (m.group(1).strip() if m else "未记录"))
    finally:
        p.terminate()
        f.close()
        time.sleep(0.5)


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "panel"
    {"panel": stage_panel, "drag": stage_drag,
     "notif": stage_notif, "timer": stage_timer}[which]()
