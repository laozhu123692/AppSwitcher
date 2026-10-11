# -*- coding: utf-8 -*-
"""启动轮换循环，并自动点击 ColorOS 的「关联启动」确认弹窗（30天内允许）。

ColorOS 在 A 应用后台拉起 B 应用时会弹出
com.oplus.securitypermission/...AppStartConfirmDialogActivity，
按钮为「30天内允许 / 仅本次允许 / 取消」。不点掉它，目标 App 永远到不了前台。
"""
import os
import re
import subprocess
import sys
import time

ADB = os.path.join(os.environ["TEMP"], "dsh-android", "android-sdk", "platform-tools", "adb.exe")
NODE = re.compile(r"<node\b[^>]*?/?>")


def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def dump():
    adb("shell", "rm", "-f", "/sdcard/_ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/_ui.xml")
    r = adb("shell", "cat", "/sdcard/_ui.xml")
    return r.stdout.decode("utf-8", "ignore")


def nodes(xml):
    out = []
    for m in NODE.finditer(xml):
        tag = m.group(0)
        d = {}
        for k in ("text", "content-desc", "resource-id", "class", "bounds", "clickable"):
            mm = re.search(k + r'="([^"]*)"', tag)
            d[k] = mm.group(1) if mm else ""
        out.append(d)
    return out


def tap_bounds(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb("shell", "input", "tap", str(x), str(y))
    print("点击 (%d,%d)" % (x, y))
    return True


def main():
    seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 90
    deadline = time.time() + seconds
    taps = 0
    while time.time() < deadline:
        xml = dump()
        hit = None
        for n in nodes(xml):
            label = (n["text"] + n["content-desc"]).strip()
            if "天内允许" in label or "仅本次允许" in label:
                hit = n
                break
        if hit:
            print("发现弹窗按钮：%s" % hit["text"])
            tap_bounds(hit["bounds"])
            taps += 1
            time.sleep(1.5)
        else:
            time.sleep(0.4)
    print("本次共点击 %d 次" % taps)


if __name__ == "__main__":
    main()
