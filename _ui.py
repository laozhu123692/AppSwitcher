#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""真机 UI 辅助脚本（adb + uiautomator）。

用法：
    python _ui.py dump [关键词]         # 打印当前界面可见节点的文本与坐标
    python _ui.py tap-text 文本          # 点击文本包含该字符串的第一个节点
    python _ui.py tap-class EditText    # 点击类名包含该字符串的第一个可点击节点
    python _ui.py tap X Y               # 直接点击坐标
    python _ui.py input 文本             # 向当前焦点输入文本（ASCII）
    python _ui.py clear                 # 清空当前焦点输入框
    python _ui.py swipe X1 Y1 X2 Y2 [毫秒]
    python _ui.py key back|home|KEYCODE_xxx
"""
import os
import re
import subprocess
import sys

TMP = os.environ.get("TEMP", r"C:\Users\LAOZHU\AppData\Local\Temp\dsh-um35jI")
TC = os.path.join(TMP, "dsh-android")
ADB = os.path.join(TC, "android-sdk", "platform-tools", "adb.exe")
REMOTE = "/sdcard/_ui.xml"
LOCAL = os.path.join(TMP, "_ui.xml")

NODE_RE = re.compile(r"<node\b[^>]*?/?>")


def adb(*args):
    env = dict(os.environ)
    env["TEMP"] = TMP
    env["TMP"] = TMP
    proc = subprocess.run([ADB] + list(args), capture_output=True, env=env)
    return (
        proc.returncode,
        proc.stdout.decode("utf-8", "replace"),
        proc.stderr.decode("utf-8", "replace"),
    )


def pull_ui():
    try:
        os.remove(LOCAL)
    except OSError:
        pass
    adb("shell", "uiautomator", "dump", REMOTE)
    code, out, err = adb("pull", REMOTE, LOCAL)
    if not os.path.exists(LOCAL):
        raise SystemExit("无法获取界面 XML：%s %s" % (out, err))
    with open(LOCAL, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def nodes(xml):
    result = []
    for raw in NODE_RE.findall(xml):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', raw))
        label = attrs.get("text") or attrs.get("content-desc") or ""
        bounds = attrs.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        result.append(
            {
                "text": label,
                "hint": attrs.get("hint", ""),
                "cls": attrs.get("class", ""),
                "clickable": attrs.get("clickable") == "true",
                "checked": attrs.get("checked") == "true",
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                "bounds": bounds,
            }
        )
    return result


def do_tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]

    if cmd == "dump":
        keyword = sys.argv[2] if len(sys.argv) > 2 else None
        for node in nodes(pull_ui()):
            if keyword and keyword not in node["text"] and keyword not in node["cls"]:
                continue
            print(
                "%-38s %-24s clickable=%-5s checked=%-5s %s"
                % (
                    (node["text"] or "(空/提示:%s)" % node["hint"]).replace("\n", " ")[:38],
                    node["cls"].split(".")[-1],
                    node["clickable"],
                    node["checked"],
                    node["bounds"],
                )
            )
        return

    if cmd == "tap-text":
        target = sys.argv[2]
        hits = [n for n in nodes(pull_ui()) if target in n["text"]]
        if not hits:
            raise SystemExit("未找到包含该文本的节点：%s" % target)
        # 优先精确匹配，其次优先可点击节点
        hits.sort(key=lambda n: (n["text"] != target, not n["clickable"]))
        node = hits[0]
        x, y = node["center"]
        print("tap %r -> (%d,%d)" % (node["text"][:30], x, y))
        do_tap(x, y)
        return

    if cmd == "tap-class":
        target = sys.argv[2]
        for node in nodes(pull_ui()):
            if target in node["cls"]:
                x, y = node["center"]
                print("tap %s -> (%d,%d)" % (node["cls"].split(".")[-1], x, y))
                do_tap(x, y)
                return
        raise SystemExit("未找到该类型的节点：%s" % target)

    if cmd == "tap":
        do_tap(sys.argv[2], sys.argv[3])
        return

    if cmd == "input":
        adb("shell", "input", "text", sys.argv[2])
        return

    if cmd == "clear":
        for _ in range(12):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        return

    if cmd == "swipe":
        ms = sys.argv[6] if len(sys.argv) > 6 else "300"
        adb("shell", "input", "swipe", sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], ms)
        return

    if cmd == "key":
        key = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME"}.get(sys.argv[2], sys.argv[2])
        adb("shell", "input", "keyevent", key)
        return

    raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
