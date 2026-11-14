#!/usr/bin/env python3
"""Rewrite /data/data/com.winlator -> /data/data/pro.winlator inside the assets.

Winlator Pro installs next to the normal Winlator, so its package name is
different. The prebuilt rootfs and components contain the original data
path in scripts, configs, symlinks and ELF binaries. "pro.winlator" has the
same length as "com.winlator", so a byte-for-byte replacement is safe, even
inside binaries.

Usage: repackage_assets.py <assets_dir>   (needs the zstd command)
"""
import io
import os
import subprocess
import sys
import tarfile

OLD = b"/data/data/com.winlator"
NEW = b"/data/data/pro.winlator"
assert len(OLD) == len(NEW)


def fix(s):
    return s.replace(OLD.decode(), NEW.decode()) if s else s


def process_tzst(path):
    raw = subprocess.run(["zstd", "-dc", path], check=True, capture_output=True).stdout
    if OLD not in raw:
        print(f"  {path}: no change")
        return
    src = tarfile.open(fileobj=io.BytesIO(raw), mode="r:")
    out = io.BytesIO()
    dst = tarfile.open(fileobj=out, mode="w:", format=src.format)
    files = links = 0
    for m in src.getmembers():
        data = None
        if m.isfile():
            data = src.extractfile(m).read()
            if OLD in data:
                data = data.replace(OLD, NEW)
                files += 1
        if (m.issym() or m.islnk()) and OLD.decode() in m.linkname:
            m.linkname = fix(m.linkname)
            links += 1
        m.name = fix(m.name)
        dst.addfile(m, io.BytesIO(data) if data is not None else None)
    dst.close()
    tar_bytes = out.getvalue()
    assert OLD not in tar_bytes, "old path still present"
    comp = subprocess.run(["zstd", "-19", "-T0", "-q", "-c"], input=tar_bytes,
                          check=True, capture_output=True).stdout
    with open(path, "wb") as f:
        f.write(comp)
    print(f"  {path}: {files} files, {links} links patched")


def main():
    for d, _, names in os.walk(sys.argv[1]):
        for n in sorted(names):
            p = os.path.join(d, n)
            if n.endswith(".tzst"):
                process_tzst(p)
            elif not n.endswith((".png", ".jpg", ".sf2")):
                with open(p, "rb") as f:
                    b = f.read()
                if OLD in b:
                    with open(p, "wb") as f:
                        f.write(b.replace(OLD, NEW))
                    print(f"  {p}: patched")


if __name__ == "__main__":
    main()
