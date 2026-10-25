#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""sdk_extract.py — 解压SDK zip到目标目录; 若zip内只有单个顶层目录,
   其内容直接展开到目标目录(消除 build-tools_r34-windows.zip 内层
   android-XX 这类不可预期的目录名)。
   用法: python sdk_extract.py <zip> <dest>"""
import os
import sys
import zipfile

def main():
    zip_path, dest = sys.argv[1], sys.argv[2]
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(dest)
    entries = os.listdir(dest)
    if len(entries) == 1 and os.path.isdir(os.path.join(dest, entries[0])):
        top = os.path.join(dest, entries[0])
        for name in os.listdir(top):
            os.rename(os.path.join(top, name), os.path.join(dest, name))
        os.rmdir(top)
    print("[extract] %s -> %s (%d entries)" % (zip_path, dest, len(os.listdir(dest))))

if __name__ == "__main__":
    main()
