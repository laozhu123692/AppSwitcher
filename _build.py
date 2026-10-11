#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""本机构建包装器：设置 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME 后调用 Gradle。

用法:  python _build.py assembleDebug --stacktrace
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


def _find_tc():
    """工具链目录：优先 TC 环境变量，其次 %TEMP%\\dsh-android，
    最后回退到本机已知的历史会话目录（DSH 每次会话的 TEMP 会变，工具链只在第一次下载过）。
    """
    cands = []
    if os.environ.get("TC"):
        cands.append(os.environ["TC"])
    if os.environ.get("TEMP"):
        cands.append(os.path.join(os.environ["TEMP"], "dsh-android"))
    base = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Temp")
    if os.path.isdir(base):
        try:
            for n in os.listdir(base):
                cands.append(os.path.join(base, n, "dsh-android"))
        except OSError:
            pass
    for c in cands:
        if os.path.exists(os.path.join(c, "jdk17", "bin", "javac.exe")):
            return c
    return cands[0] if cands else ""


TC = _find_tc()
# 工具链目录可能来自「上一个会话的 TEMP」，当前会话对它没有写权限
# （Gradle 要往 GRADLE_USER_HOME 解压 native-platform.dll，会报 Could not initialize native services）。
# 因此 JAVA_HOME / ANDROID_HOME 允许只读，但 GRADLE_USER_HOME / ANDROID_USER_HOME 一律指向当前可写目录。
WRITABLE = os.path.join(os.environ.get("TEMP") or TC, "dsh-android")

env = dict(os.environ)
env["JAVA_HOME"] = os.path.join(TC, "jdk17")
env["ANDROID_HOME"] = os.path.join(TC, "android-sdk")
env["ANDROID_SDK_ROOT"] = os.path.join(TC, "android-sdk")
env["GRADLE_USER_HOME"] = os.path.join(WRITABLE, "gradle-home")
# 沙箱下 C:\Users\<user>\.android 不可写，AGP 会打不出 metrics 告警；指到可写目录
env["ANDROID_USER_HOME"] = os.path.join(WRITABLE, "android-user")
env["PATH"] = os.pathsep.join([
    os.path.join(TC, "jdk17", "bin"),
    os.path.join(TC, "android-sdk", "platform-tools"),
    env.get("PATH", ""),
])
GRADLE = os.path.join(TC, "gradle-8.7", "bin", "gradle.bat")

# --via-wrapper：用工程自带的 gradlew.bat（验收要求 ./gradlew assembleDebug 可复现）
argv = sys.argv[1:]
if argv and argv[0] == "--via-wrapper":
    argv = argv[1:]
    GRADLE = os.path.join(ROOT, "gradlew.bat")
    if not os.path.exists(GRADLE):
        print(f"[ERROR] 缺少 {GRADLE}")
        sys.exit(9)

if not os.path.exists(os.path.join(TC, "jdk17", "bin", "javac.exe")):
    print(f"[ERROR] 工具链缺失: {TC}")
    print("        请先运行: D:\\Scripts\\python.exe -u _bootstrap_toolchain.py")
    sys.exit(9)

print(f"[env] JAVA_HOME={env['JAVA_HOME']}")
print(f"[env] ANDROID_HOME={env['ANDROID_HOME']}")
print(f"[env] GRADLE_USER_HOME={env['GRADLE_USER_HOME']}")
print(f"[run] gradle {' '.join(argv)}")
sys.stdout.flush()

# 沙箱禁止 Gradle native 文件监视器打开线程（"Couldn't open current thread, error = 5"），关掉它
DEFAULTS = ["-Dorg.gradle.vfs.watch=false", "--console=plain"]

cmd = [GRADLE] + DEFAULTS + argv
rc = subprocess.call(cmd, cwd=ROOT, env=env)
print(f"[exit] gradle 返回码 = {rc}")
sys.exit(rc)
