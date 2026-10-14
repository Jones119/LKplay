#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
lossprobe_summarize.py — cs11_lossprobe.sh 的分析器
====================================================
回答一个问题：丢包发生的时间片里，有没有哪个核被打满？
  - 若丢包时间片内 无任何核 ≥75%  → CPU 饱和假设(A)不成立，指向调度延迟(B)/流控(C)
  - 若丢包时间片内 有核 ≥90%      → 支持单核饱和假设(A)（至少局部）

用法: python3 lossprobe_summarize.py lossprobe.log
"""
import sys

def parse(path):
    rows = []
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line or not line[0].isdigit():
            continue
        p = line.split(",")
        if len(p) < 33:
            continue
        seq = int(p[0]); ts = int(p[1])
        cores = []
        for c in range(4):
            base = 2 + c * 7
            vals = [int(x) for x in p[base:base + 7]]
            cores.append(vals)  # user,nice,sys,idle,iowait,irq,softirq
        udperr = int(p[30]); udprcv = int(p[31])
        try:
            load1 = float(p[32])
        except ValueError:
            load1 = float("nan")
        rows.append((seq, ts, cores, udperr, udprcv, load1))
    return rows

def delta(cur, prev):
    out = []
    for c in range(4):
        a, b = prev[2][c], cur[2][c]
        dt = sum(b) - sum(a)
        if dt <= 0:
            out.append((0.0, 0.0, 0.0))
            continue
        busy = 100.0 * (1 - (b[3] + b[4] - a[3] - a[4]) / dt)
        sirq = 100.0 * (b[6] - a[6]) / dt
        irq_ = 100.0 * (b[5] - a[5]) / dt
        out.append((max(0.0, busy), max(0.0, sirq), max(0.0, irq_)))
    udp = cur[3] - prev[3]
    rcv = cur[4] - prev[4]
    return out, udp, rcv

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "lossprobe.log"
    rows = parse(path)
    if len(rows) < 3:
        sys.exit("数据行不足（<3）")
    intervals = []
    for i in range(1, len(rows)):
        cores, udp, rcv = delta(rows[i], rows[i - 1])
        intervals.append((rows[i][0], rows[i][1], cores, udp, rcv, rows[i][5]))

    loss = [x for x in intervals if x[3] > 0]
    print(f"总时间片: {len(intervals)}（~0.5s/片）· 丢包片: {len(loss)} · 丢包合计: {sum(x[3] for x in loss)}")
    print()
    if not loss:
        print("★ 窗口内无 Udp6InErrors 增量——丢包不在本采样窗口（或已归零）。")
        return

    print("== 丢包片逐片明细（核 busy% / sirq% / irq%）==")
    ge90 = ge75 = lt75 = 0
    for seq, ts, cores, udp, rcv, load1 in loss[:80]:
        mb = max(c[0] for c in cores)
        if mb >= 90: ge90 += 1
        elif mb >= 75: ge75 += 1
        else: lt75 += 1
        cs = " | ".join(f"c{i}:{c[0]:4.0f}/{c[1]:3.0f}/{c[2]:3.0f}" for i, c in enumerate(cores))
        print(f"  #{seq:5d} udp+{udp} rcv+{rcv} max={mb:4.0f}% load={load1:4.1f}  {cs}")
    if len(loss) > 80:
        print(f"  ...（其余 {len(loss) - 80} 片省略）")
    print()
    print(f"== 判定 ==  丢包片中 max核≥90%: {ge90} · 75~90%: {ge75} · <75%: {lt75}")
    total = len(loss)
    if ge90 >= total * 0.5:
        print("→ 结论倾向 (A) 单核饱和：多数丢包片存在 ≥90% 的核。")
    elif lt75 >= total * 0.7:
        print("→ 结论倾向 (B/C) 调度延迟或链路流控：大部分丢包片无核过 75%。")
    else:
        print("→ 混合信号（A 与 B/C 均有）：建议加大采样率或细分窗口再看。")

    # 全窗口最大核利用率
    mb_all = max(max(c[0] for c in x[2]) for x in intervals)
    print(f"== 全窗口单核峰值: {mb_all:.0f}% ==")

if __name__ == "__main__":
    main()
