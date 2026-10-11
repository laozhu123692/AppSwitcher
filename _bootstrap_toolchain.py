#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Android 构建工具链引导脚本（纯 Python，绕过 PowerShell 的 schannel 限制）。
下载 JDK17 + Android platform-34 + build-tools 34.0.0 + platform-tools + Gradle 8.7
到会话临时目录（不在 OneDrive 工作区内，避免同步污染）。
"""
import os
import sys
import time
import hashlib
import zipfile
import urllib.request
import urllib.error
import shutil

TC = os.path.join(os.environ.get("TEMP", "."), "dsh-android")
DL = os.path.join(TC, "_downloads")
os.makedirs(DL, exist_ok=True)

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}


def human(n):
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f}{u}"
        n /= 1024
    return f"{n:.1f}TB"


def sha1_of(path, buf=1 << 20):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while True:
            b = f.read(buf)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def download(name, urls, sha1=None, size=None):
    """按顺序尝试候选 URL，成功后校验 sha1。返回本地路径或 None。"""
    dest = os.path.join(DL, name)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        if sha1 is None or sha1_of(dest) == sha1:
            print(f"[skip] {name} 已存在 ({human(os.path.getsize(dest))})", flush=True)
            return dest
        print(f"[redo] {name} 校验不符，重新下载", flush=True)
        os.remove(dest)

    for url in urls:
        tmp = dest + ".part"
        try:
            print(f"[get ] {name}\n       <- {url}", flush=True)
            t0 = time.time()
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
                total = int(r.headers.get("Content-Length") or 0)
                got = 0
                last = 0.0
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
                    got += len(chunk)
                    now = time.time()
                    if now - last > 5:
                        last = now
                        pct = f"{got*100/total:5.1f}%" if total else "  ?  "
                        spd = got / max(now - t0, 0.001)
                        print(f"       {pct} {human(got)}/{human(total)} @ {human(spd)}/s", flush=True)
            if sha1:
                actual = sha1_of(tmp)
                if actual != sha1:
                    print(f"[bad ] {name} sha1 不符: {actual} != {sha1}", flush=True)
                    os.remove(tmp)
                    continue
            os.replace(tmp, dest)
            print(f"[ok  ] {name} {human(os.path.getsize(dest))} 用时 {time.time()-t0:.1f}s", flush=True)
            return dest
        except Exception as e:
            print(f"[fail] {name} <- {url}\n       {type(e).__name__}: {e}", flush=True)
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass
    print(f"[DEAD] {name} 所有源均失败", flush=True)
    return None


def extract(zip_path, dest_dir, strip_root=None, rename_to=None):
    """解压；strip_root 指定剥离的顶层目录名；rename_to 重命名顶层目录。"""
    if not zip_path:
        return False
    if os.path.exists(dest_dir) and os.listdir(dest_dir):
        print(f"[skip] 解压目标已存在: {dest_dir}", flush=True)
        return True
    tmp = dest_dir + ".extracting"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    print(f"[unzip] {os.path.basename(zip_path)} -> {dest_dir}", flush=True)
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        z.extractall(tmp)
    tops = sorted({n.split("/")[0] for n in names if n.strip("/")})
    print(f"        顶层条目: {tops[:10]}", flush=True)

    # 单一顶层目录 -> 剥离该层，得到 AGP 需要的扁平 SDK 布局
    if len(tops) == 1:
        single = os.path.join(tmp, tops[0])
        if os.path.isdir(single):
            shutil.rmtree(dest_dir, ignore_errors=True)
            os.makedirs(os.path.dirname(dest_dir), exist_ok=True)
            shutil.move(single, dest_dir)
            shutil.rmtree(tmp, ignore_errors=True)
            print(f"[ok  ] 解压完成（剥离顶层 {tops[0]}/）: {dest_dir}", flush=True)
            return True

    os.makedirs(dest_dir, exist_ok=True)
    for item in os.listdir(tmp):
        target = os.path.join(dest_dir, item)
        if os.path.exists(target):
            shutil.rmtree(target, ignore_errors=True)
        shutil.move(os.path.join(tmp, item), target)
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"[ok  ] 解压完成: {dest_dir}", flush=True)
    return True


def main():
    print("=" * 72, flush=True)
    print(f"工具链根目录: {TC}", flush=True)
    print("=" * 72, flush=True)

    # ---------- 1. JDK 17 ----------
    jdk_zip = download(
        "jdk17-windows-x64.zip",
        [
            "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip",
            "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
            "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse",
        ],
    )
    jdk_dir = os.path.join(TC, "jdk17")
    jdk_dirs = [i for i in os.listdir(TC) if i.startswith(("jdk-17", "microsoft-jdk", "openjdk-17"))]
    if os.path.isdir(jdk_dir) and os.path.exists(os.path.join(jdk_dir, "bin", "javac.exe")):
        print("[skip] JDK 已就绪", flush=True)
    elif jdk_dirs:
        found = os.path.join(TC, jdk_dirs[0])
        print(f"[jdk ] 已解压的 JDK: {found}", flush=True)
        jdk_dir = found
    else:
        extract(jdk_zip, jdk_dir)

    # ---------- 2. Android SDK 组件（Tencent 镜像）----------
    MIRROR = "https://mirrors.cloud.tencent.com/AndroidSDK/"
    sdk = os.path.join(TC, "android-sdk")
    os.makedirs(sdk, exist_ok=True)

    plat_zip = download(
        "platform-34-ext7_r03.zip",
        [MIRROR + "platform-34-ext7_r03.zip"],
        sha1="1f2e9478d6a7601425ceaa553311dc43191f103d",
        size=63180081,
    )
    bt_zip = download(
        "build-tools_r34-windows.zip",
        [MIRROR + "build-tools_r34-windows.zip"],
        sha1="62cfde1b6fcc3ad12a4d2ba1b537e752768bfd47",
        size=58253258,
    )
    pt_zip = download(
        "platform-tools_r37.0.1-win.zip",
        [MIRROR + "platform-tools_r37.0.1-win.zip"],
        sha1="e03e78b1d80b396f1c3358e31251cb31740e1110",
        size=8044989,
    )

    # build-tools 解压后顶层是 android-14/ -> build-tools/34.0.0
    extract(bt_zip, os.path.join(sdk, "build-tools", "34.0.0"))
    # platform-tools 解压后顶层是 platform-tools/
    extract(pt_zip, os.path.join(sdk, "platform-tools"), strip_root="platform-tools")

    # platform 解压后顶层是 android-34-ext7/ 或 android-14/，统一成 platforms/android-34
    plat_dest = os.path.join(sdk, "platforms", "android-34")
    if not (os.path.exists(plat_dest) and os.listdir(plat_dest)):
        stage = os.path.join(sdk, "platforms", "_stage")
        shutil.rmtree(stage, ignore_errors=True)
        if plat_zip:
            os.makedirs(stage, exist_ok=True)
            with zipfile.ZipFile(plat_zip) as z:
                z.extractall(stage)
            tops = [i for i in os.listdir(stage) if os.path.isdir(os.path.join(stage, i))]
            print(f"[plat ] 顶层: {tops}", flush=True)
            src = os.path.join(stage, tops[0]) if len(tops) == 1 else stage
            shutil.rmtree(plat_dest, ignore_errors=True)
            os.makedirs(os.path.dirname(plat_dest), exist_ok=True)
            shutil.move(src, plat_dest)
            shutil.rmtree(stage, ignore_errors=True)
            print(f"[ok  ] platform -> {plat_dest}", flush=True)
            for probe in ("android.jar", "source.properties", "build.prop"):
                p = os.path.join(plat_dest, probe)
                print(f"        {probe}: {'OK' if os.path.exists(p) else '缺失'}", flush=True)

    # ---------- 3. Gradle 8.7 ----------
    gradle_zip = download(
        "gradle-8.7-bin.zip",
        [
            "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip",
            "https://services.gradle.org/distributions/gradle-8.7-bin.zip",
        ],
    )
    if not os.path.exists(os.path.join(TC, "gradle-8.7", "bin", "gradle.bat")):
        extract(gradle_zip, os.path.join(TC, "gradle-8.7"), strip_root="gradle-8.7")

    # ---------- 4. SDK licenses ----------
    lic = os.path.join(sdk, "licenses")
    os.makedirs(lic, exist_ok=True)
    with open(os.path.join(lic, "android-sdk-license"), "w", encoding="utf-8") as f:
        f.write("\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n"
                "d56f5187479451eabf01fb78af6dfcb131a6481e\n"
                "8933bad161af4178b1185d1a37fbf41ea5269c55\n")
    with open(os.path.join(lic, "android-sdk-preview-license"), "w", encoding="utf-8") as f:
        f.write("\n84831b9409646a918e30573bab4c9c91346d8abd\n")
    print("[ok  ] licenses 已写入", flush=True)

    # ---------- 汇总 ----------
    print("=" * 72, flush=True)
    print("工具链自检：", flush=True)
    checks = {
        "JDK javac": os.path.join(jdk_dir, "bin", "javac.exe"),
        "android.jar": os.path.join(plat_dest, "android.jar"),
        "aapt2": os.path.join(sdk, "build-tools", "34.0.0", "aapt2.exe"),
        "d8": os.path.join(sdk, "build-tools", "34.0.0", "d8.bat"),
        "apksigner": os.path.join(sdk, "build-tools", "34.0.0", "lib", "apksigner.jar"),
        "zipalign": os.path.join(sdk, "build-tools", "34.0.0", "zipalign.exe"),
        "adb": os.path.join(sdk, "platform-tools", "adb.exe"),
        "gradle.bat": os.path.join(TC, "gradle-8.7", "bin", "gradle.bat"),
    }
    missing = []
    for k, v in checks.items():
        ok = os.path.exists(v)
        print(f"  [{'OK ' if ok else 'MISS'}] {k:14s} {v}", flush=True)
        if not ok:
            missing.append(k)
    print("=" * 72, flush=True)
    print("BOOTSTRAP_RESULT=" + ("SUCCESS" if not missing else "MISSING:" + ",".join(missing)), flush=True)
    print("TOOLCHAIN_ROOT=" + TC, flush=True)
    print("JDK_HOME=" + jdk_dir, flush=True)
    print("ANDROID_SDK=" + sdk, flush=True)
    print("GRADLE_HOME=" + os.path.join(TC, "gradle-8.7"), flush=True)
    return 0 if not missing else 1


if __name__ == "__main__":
    sys.exit(main())
