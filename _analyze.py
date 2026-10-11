# -*- coding: utf-8 -*-
"""离线分析 _evidence/run_logcat.txt 与 focus_timeline.txt，可反复运行，无需重新跑真机。"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.join(HERE, "_evidence")


def load(name):
    p = os.path.join(EV, name)
    if not os.path.exists(p):
        return ""
    return open(p, encoding="utf-8", errors="ignore").read()


def secs(s):
    h, m, rest = s.split(":")
    return int(h) * 3600 + int(m) * 60 + float(rest)


def main():
    txt = load("run_logcat.txt")
    ok = re.findall(
        r"(\d\d:\d\d:\d\d\.\d+).*?I[/ ]SwitchController.*?切换成功：(\S+?)（第 (\d+) 次校验", txt)
    fails = re.findall(r"切换到目标应用失败：([^\s（]+)", txt)
    stops = [ln for ln in txt.splitlines() if "停止轮换" in ln]
    dialogs = [ln for ln in txt.splitlines() if "系统弹窗" in ln or "系统授权弹窗" in ln]
    fallbacks = [ln for ln in txt.splitlines() if "降级" in ln]

    L = []
    L.append("成功切换次数：%d" % len(ok))
    L.append("失败次数：%d" % len(fails))
    L.append("失败目标：%s" % (sorted(set(fails)) if fails else "无"))
    L.append("系统弹窗相关行数：%d" % len(dialogs))
    L.append("降级（最近任务）行数：%d" % len(fallbacks))
    L.append("停止原因：%s" % (
        re.sub(r"^.*?SwitchController(\(\d+\))?: ", "", stops[-1]).strip() if stops else "未记录"))
    L.append("")

    deltas = []
    for i in range(1, len(ok)):
        deltas.append(secs(ok[i][0]) - secs(ok[i - 1][0]))
    if deltas:
        L.append("相邻成功切换时间差：n=%d" % len(deltas))
        L.append("  最小 %.3fs  最大 %.3fs  平均 %.3fs" %
                 (min(deltas), max(deltas), sum(deltas) / len(deltas)))
        L.append("  满足 2.0s±0.4s 的比例：%d/%d" %
                 (sum(1 for d in deltas if 1.6 <= d <= 2.4), len(deltas)))
        L.append("  前 5 个时间差：%s" % ", ".join("%.3f" % d for d in deltas[:5]))
        L.append("  后 5 个时间差：%s" % ", ".join("%.3f" % d for d in deltas[-5:]))
    L.append("")

    seq = [p for _, p, _ in ok]
    alternations = sum(1 for i in range(1, len(seq)) if seq[i] != seq[i - 1])
    L.append("A/B 交替：%d 次相邻不同 / %d 对" % (alternations, max(len(seq) - 1, 0)))
    L.append("序列（前 8 个）：%s" % seq[:8])
    L.append("序列（后 8 个）：%s" % seq[-8:])
    L.append("校验轮次分布(第几次校验成功)：%s" %
             sorted(set(int(a) for _, _, a in ok)))
    L.append("")

    # startActivity flag 检查
    flags = sorted(set(re.findall(r"flags=(\d+)", txt)))
    L.append("startActivity flags 取值：%s" % flags)
    L.append("禁止的 flag(0x8000 CLEAR_TOP / 0x80000 CLEAR_TASK) 是否出现：%s" % (
        "出现！" if any(int(f) & 0x8000 or int(f) & 0x80000 for f in flags) else "未出现（正确）"))
    L.append("")

    # PC 侧前台时间线
    tl = [ln for ln in load("focus_timeline.txt").splitlines() if ln.strip()]
    L.append("PC 侧前台窗口变化次数：%d" % len(tl))
    for ln in tl[:40]:
        L.append("  " + ln)

    out = "\n".join(L)
    open(os.path.join(EV, "analysis.txt"), "w", encoding="utf-8").write(out + "\n")
    sys.stdout.write(out + "\n")


if __name__ == "__main__":
    main()
