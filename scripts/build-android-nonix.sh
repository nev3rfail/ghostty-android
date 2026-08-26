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
#   ZIG_LIB_DIR         zig standard library      (default: the compiler's own)

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

# The NDK usually ships one host toolchain and its sysroot is host-independent, so
# any host's NDK will do. A rebuild for another host can sit beside the official
# one, though, and then the glob matches more than one directory -- so prefer the
# toolchain built for this machine and fall back to whatever single one is there.
PREBUILT=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt
TOOLCHAIN=$PREBUILT/linux-$(uname -m)
[ -d "$TOOLCHAIN" ] || TOOLCHAIN=$(echo "$PREBUILT"/*)
[ -d "$TOOLCHAIN" ] || { echo "No NDK toolchain under $ANDROID_NDK_ROOT" >&2; exit 1; }
SYSROOT=$TOOLCHAIN/sysroot
CLANG_VERSION=$(ls "$TOOLCHAIN/lib/clang" | head -1)

# The vt library's build reaches for the NDK itself, through a helper that names
# the sysroot with a host tag hardcoded per operating system, so it only finds an
# NDK built for the host it runs on. Point it at a shadow root whose single
# prebuilt directory carries the name that helper looks for.
case $(uname -s) in
  Linux)  HOST_TAG=linux-x86_64  ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *)      HOST_TAG=$(basename "$TOOLCHAIN") ;;
esac
if [ "$(basename "$TOOLCHAIN")" = "$HOST_TAG" ]; then
  export ANDROID_NDK_HOME=$ANDROID_NDK_ROOT
else
  SHADOW=$CACHE_ROOT/ndk/toolchains/llvm/prebuilt
  rm -rf "$SHADOW"
  mkdir -p "$SHADOW"
  ln -s "$TOOLCHAIN" "$SHADOW/$HOST_TAG"
  export ANDROID_NDK_HOME=$CACHE_ROOT/ndk
fi

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

# One level of a shadow directory: every entry symlinked back to the real tree,
# except the one name that has to stay real so the level below it can be
# shadowed in turn.
shadow_level() {
  mkdir -p "$2"
  for entry in "$1"/*; do
    name=$(basename "$entry")
    [ "$name" = "$3" ] || ln -s "$entry" "$2/$name"
  done
}

# The options step materialises its generated file from an unnamed temporary
# with a hard link, and Android refuses to hard link inside an app's own
# storage, so a build that runs on the phone fails with AccessDenied before it
# compiles anything. A named temporary reaches the same destination through a
# rename, and which kind gets created is one word in the standard library. So
# hand the compiler a shadow standard library: symlinks to the real tree at
# every level, and one patched copy of the file that chooses. A compiler whose
# options step already renames does not carry that word, and its absence is
# what switches this off. A caller who names its own ZIG_LIB_DIR keeps it.
if [ -z "${ZIG_LIB_DIR:-}" ]; then
  ZIG_LIB=$("$ZIG" env | sed -n 's/^ *\.lib_dir = "\(.*\)",*$/\1/p')
  OPTIONS_ZIG=$ZIG_LIB/std/Build/Step/Options.zig
  PROBE=$CACHE_ROOT/hardlink-probe
  rm -f "$PROBE" "$PROBE.link"
  : > "$PROBE"
  if ! ln "$PROBE" "$PROBE.link" 2>/dev/null && grep -q '\.replace = false,' "$OPTIONS_ZIG"; then
    SHADOW_LIB=$CACHE_ROOT/zig-lib
    rm -rf "$SHADOW_LIB"
    shadow_level "$ZIG_LIB"                  "$SHADOW_LIB"                  std
    shadow_level "$ZIG_LIB/std"              "$SHADOW_LIB/std"              Build
    shadow_level "$ZIG_LIB/std/Build"        "$SHADOW_LIB/std/Build"        Step
    shadow_level "$ZIG_LIB/std/Build/Step"   "$SHADOW_LIB/std/Build/Step"   Options.zig
    sed 's/\.replace = false,/.replace = true,/' "$OPTIONS_ZIG" \
      > "$SHADOW_LIB/std/Build/Step/Options.zig"
    if cmp -s "$OPTIONS_ZIG" "$SHADOW_LIB/std/Build/Step/Options.zig"; then
      echo "The shadow standard library is identical to the real one: the patch matched nothing" >&2
      exit 1
    fi
    export ZIG_LIB_DIR=$SHADOW_LIB
    echo "== shadow standard library ($SHADOW_LIB) =="
  fi
  rm -f "$PROBE" "$PROBE.link"
fi

# Caches live outside the source tree so a source tree on a slow mount stays cheap.
# A caller that already has a package cache keeps it: fetching these again costs
# a network round trip per dependency, and some hosts have no resolver at all.
export ZIG_GLOBAL_CACHE_DIR=${ZIG_GLOBAL_CACHE_DIR:-$CACHE_ROOT/global}
common=(-Dtarget="$TRIPLE.$API" -Doptimize=ReleaseFast --libc "$LIBC_FILE")

echo "== libghostty-vt ($ABI, API $API) =="
"$ZIG" build "${common[@]}" \
  -Demit-lib-vt=true -Dapp-runtime=none -Dsimd=false -Dcpu=baseline \
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
