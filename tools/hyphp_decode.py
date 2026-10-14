#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hyphp 字符串池解码器（hywebview / playkeytool / HY 套件通用）
============================================================
原理：APP 全部字符串 = short[] 表（f38short，4907 项）加密存放；
解码函数（ue0.L2/e7/b2/y4/C0100.m137… 全部同构，已实锤）：
    str[i] = (char)( table[off + i] ^ key )
表在 d2j 输出里为 hex 小端串：4 字符一组 -> int(g[2:4]+g[0:2], 16)

用法：
  python3 hyphp_decode.py known                    # 输出已破译的已知字符串
  python3 hyphp_decode.py at <off> <len> <key>     # 指定位置解码
  python3 hyphp_decode.py scan <key> [prefix]      # 滑窗扫描可读串（含点号/类名片段）
"""
import re, sys

API_SRC = r"D:/Development/DiAuto-DiPlay/_analysis/hylink_src/UP_unpacked/sources/com/hyphp/sdk/api.java"

def load_table(path=API_SRC):
    api = open(path, encoding="utf-8", errors="ignore").read()
    m = re.search(r'f38short = \$d2j\$hex\$[0-9a-f]+\$decode_S\("([0-9a-f]+)"\)', api)
    if not m:
        sys.exit("f38short 表未找到")
    hexs = m.group(1)
    return [int(hexs[i+2:i+4] + hexs[i:i+2], 16) for i in range(0, len(hexs), 4)]

TABLE = load_table()

def dec(off, ln, key):
    return "".join(chr((TABLE[off+i] & 0xFFFF) ^ key) for i in range(min(ln, len(TABLE)-off)))

def printable_run(off, key, maxlen=80):
    s = dec(off, maxlen, key)
    n = 0
    while n < maxlen and 32 <= ord(s[n]) < 127:
        n += 1
    return s[:n]

KNOWN = [
    # (off, len, key, 说明)
    (1017, 24, 3056, "ServiceName -> Intent action"),
    (962, 19, 2229, "ServicePkgName (CS11 基础包)"),
    (962, 27, 2229, "ServicePkgName (E03 变体 e03tools)"),
    (1041, 27, 1870, "ServicePkgName 变体2 (dcltools)"),
    (859, 27, 2710, "ComponentName 包 (e03tools)"),
    (886, 38, 2157, "ComponentName 类 (MyReceiver)"),
]

def main():
    args = sys.argv[1:]
    if not args or args[0] == "known":
        print(f"表大小: {len(TABLE)} shorts\n")
        for off, ln, key, note in KNOWN:
            print(f"[off={off} len={ln} key={key}] {note}\n  {dec(off, ln, key)!r}")
    elif args[0] == "at" and len(args) == 4:
        off, ln, key = int(args[1]), int(args[2]), int(args[3])
        print(dec(off, ln, key))
    elif args[0] == "scan" and len(args) >= 2:
        key = int(args[1])
        pref = args[2] if len(args) > 2 else None
        off = 0
        while off < len(TABLE) - 10:
            s = printable_run(off, key)
            if len(s) >= 4 and ("." in s or "_" in s or "/" in s):
                if not pref or s.startswith(pref):
                    print(f"off={off} len={len(s)}: {s!r}")
                    off += len(s)
                    continue
            off += 1
    else:
        print(__doc__)

if __name__ == "__main__":
    main()
