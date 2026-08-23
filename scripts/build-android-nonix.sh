#!/usr/bin/env bash
# Build libghostty-vt and the GLES renderer for one Android ABI without nix-shell.
#
# Requires zig, patchelf and an Android NDK. The NDK host toolchain directory is
# detected, so an NDK installed for any host OS works as long as its sysroot is
# readable.
#
# Usage: build-android-nonix.sh <ABI> [OUTPUT_DIR]
#
#   ZIG                 zig executable            (default: zig)
#   ANDROID_NDK_ROOT    NDK root                  (required)
#   ANDROID_MIN_API     target API level          (default: 24)
#   ZIG_CACHE_ROOT      build cache location      (default: /tmp/ghostty-android-cache)

set -euo pipefail

ABI=${1:?"usage: $0 <ABI> [OUTPUT_DIR]"}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT_DIR=${2:-$ROOT/android/terminal-library/src/main/jniLibs/$ABI}

ZIG=${ZIG:-zig}
API=${ANDROID_MIN_API:-24}
CACHE_ROOT=${ZIG_CACHE_ROOT:-/tmp/ghostty-android-cache}
: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT not set}"

case $ABI in
  arm64-v8a)   TRIPLE=aarch64-linux-android;  RT_ARCH=aarch64 ;;
  armeabi-v7a) TRIPLE=arm-linux-androideabi;  RT_ARCH=arm     ;;
  x86_64)      TRIPLE=x86_64-linux-android;   RT_ARCH=x86_64  ;;
  *) echo "Unknown ABI: $ABI" >&2; exit 1 ;;
esac

# The NDK ships one host toolchain; its sysroot is host-independent.
TOOLCHAIN=$(echo "$ANDROID_NDK_ROOT"/toolchains/llvm/prebuilt/*)
[ -d "$TOOLCHAIN" ] || { echo "No NDK toolchain under $ANDROID_NDK_ROOT" >&2; exit 1; }
SYSROOT=$TOOLCHAIN/sysroot
CLANG_VERSION=$(ls "$TOOLCHAIN/lib/clang" | head -1)

# Zig resolves Android libc from an explicit libc file rather than the NDK's clang.
LIBC_FILE=$CACHE_ROOT/android-$ABI-libc.txt
mkdir -p "$CACHE_ROOT" "$OUTPUT_DIR"
cat > "$LIBC_FILE" <<EOF
include_dir=$SYSROOT/usr/include
sys_include_dir=$SYSROOT/usr/include/$TRIPLE
crt_dir=$SYSROOT/usr/lib/$TRIPLE/$API
lib_dir=$SYSROOT/usr/lib/$TRIPLE/$API
static_crt_dir=$TOOLCHAIN/lib/clang/$CLANG_VERSION/lib/linux/$RT_ARCH
msvc_lib_dir=
kernel32_lib_dir=
gcc_dir=
EOF

# Caches live outside the source tree so a source tree on a slow mount stays cheap.
export ZIG_GLOBAL_CACHE_DIR=$CACHE_ROOT/global
common=(-Dtarget="$TRIPLE.$API" -Doptimize=ReleaseFast --libc "$LIBC_FILE")

echo "== libghostty-vt ($ABI, API $API) =="
"$ZIG" build lib-vt "${common[@]}" \
  -Dapp-runtime=none -Dsimd=false -Dcpu=baseline \
  --cache-dir "$CACHE_ROOT/vt-$ABI" \
  --build-file "$ROOT/libghostty-vt/build.zig"
cp "$ROOT/libghostty-vt/zig-out/lib/libghostty-vt.so" "$OUTPUT_DIR/"

echo "== ghostty_renderer ($ABI) =="
"$ZIG" build "${common[@]}" \
  --cache-dir "$CACHE_ROOT/renderer-$ABI" \
  --build-file "$ROOT/android/renderer/build.zig"
cp "$ROOT/android/renderer/zig-out/lib/libghostty_renderer.so" "$OUTPUT_DIR/"

# The renderer calls into GLES and the Android log without linking them, so the
# dynamic linker needs to be told to load them.
patchelf --add-needed libGLESv3.so --add-needed liblog.so "$OUTPUT_DIR/libghostty_renderer.so"

echo "== done: $OUTPUT_DIR =="
ls -la "$OUTPUT_DIR"
