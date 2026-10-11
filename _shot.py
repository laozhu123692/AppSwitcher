# -*- coding: utf-8 -*-
"""启动轮换并截图，用于定位悬浮面板坐标。用法: python _shot.py <名字> [启动前等待秒]"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _verify2 as V

V.log("安装: " + V.out("install", "-r", V.APK).strip().splitlines()[-1])
V.set_prefs(a="com.lerist.fakelocation", b="com.vphonegaga.titan",
            interval=2000, mode=0, rounds=100, minutes=10)
V.log("无障碍: %s" % V.enable_a11y())
V.launch()
V.dismiss_coloros_safety()
name = sys.argv[1] if len(sys.argv) > 1 else "panel_shot"
if not V.start_switching():
    V.log("!! 开始失败")
    sys.exit(1)
time.sleep(5)
V.dismiss_coloros_safety()
V.sh("screencap -p /sdcard/%s.png" % name)
V.adb("pull", "/sdcard/%s.png" % name, os.path.join(V.HERE, name + ".png"))
V.log("截图: %s.png  焦点=%s  服务运行=%s" % (name, V.focus(), V.service_running()))
