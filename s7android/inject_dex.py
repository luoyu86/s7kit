#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""inject_dex.py — 把 classes.dex 注入 aapt2 link 产出的 APK(未签名),
   逐条目复制并保留原压缩方式(resources.arsc 必须保持 STORED, 否则
   Android 11+ 安装会报 resources.arsc 必须未压缩且对齐)。
   用法: python inject_dex.py <in.apk> <classes.dex> <out.apk>"""
import sys
import zipfile

def main():
    src, dex_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    dex = open(dex_path, "rb").read()
    zin = zipfile.ZipFile(src)
    zout = zipfile.ZipFile(dst, "w")
    names = set()
    for it in zin.infolist():
        if it.filename == "classes.dex":
            continue
        data = zin.read(it.filename)
        zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
        zi.compress_type = it.compress_type          # 保留原压缩方式(STORED/DEFLATED)
        zi.external_attr = it.external_attr
        zi.internal_attr = it.internal_attr
        zi.create_system = it.create_system
        zout.writestr(zi, data)
        names.add(it.filename)
    assert "classes.dex" not in names, "源APK里已有classes.dex?"
    zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, dex)
    zout.close()
    zin.close()
    print("[inject] %d 个条目 + classes.dex(%dB) -> %s" % (len(names), len(dex), dst))

if __name__ == "__main__":
    main()
