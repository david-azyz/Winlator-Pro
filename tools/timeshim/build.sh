#!/bin/sh
# Builds the x86_64 date/time shim that Winlator preloads into Wine via
# BOX64_LD_PRELOAD, and copies it into the app assets.
set -e
cd "$(dirname "$0")"
OUT=${1:-build}
mkdir -p "$OUT"
clang --target=x86_64-linux-gnu -O2 -fPIC -ffreestanding -fno-builtin -fno-stack-protector \
      -nostdlib -shared -fuse-ld=lld -Wl,-soname,libwinlator_timeshim.so -Wl,--allow-shlib-undefined \
      -o "$OUT/libwinlator_timeshim.so" timeshim.c
mkdir -p ../../app/src/main/assets/time
cp "$OUT/libwinlator_timeshim.so" ../../app/src/main/assets/time/libwinlator_timeshim.so
echo "Built $OUT/libwinlator_timeshim.so"
