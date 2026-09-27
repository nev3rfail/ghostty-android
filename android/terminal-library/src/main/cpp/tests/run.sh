#!/bin/sh
# Host tests for syscall-shim, with the host C compiler (Linux, x86_64). The
# tracee installs a seccomp filter that traps the way Android's policy does.
#
# Usage: run.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
CC=${CC:-gcc}

W=$(mktemp -d /tmp/shim.XXXXXX)
trap 'rm -rf "$W"' EXIT

S=$W/libsyscallshim.so
T=$W/shim-test
$CC -std=gnu11 -O1 -g -Wall -Wextra -Werror -DSHIM_LINKERS="\"$W/linker64\"" \
  "$here/../syscall-shim.c" -o "$S" || exit 1
$CC -std=gnu11 -O1 -g -Wall -Wextra -Werror -pthread "$here/shim-test.c" -o "$T" || exit 1

# libproot.so beside the shim, a linker64, $HARNESS_PREFIX/bin/proot as a link
# to a proot elsewhere, and a program that is none of them. Each prints its
# TracerPid.
for f in libproot.so linker64 real-proot other; do cp "$T" "$W/$f"; done
mkdir -p "$W/prefix/bin"
ln -s ../../real-proot "$W/prefix/bin/proot"
export HARNESS_PREFIX=$W/prefix

pass=0
fail=0
ok() {
  if eval "$2"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $1"; fi
}
shim() { timeout 60 "$S" "$T" "$@"; }

ok "syscall-stop parity across execs and a raised SIGTRAP" 'shim trap parity 3'
ok "execs from threads other than the leader" 'shim trap thread-exec 600'
ok "ENOSYS and EPERM for untranslated trapped calls, then a translated one" 'shim trap enosys'
ok "set-id calls by P4's rule" 'shim trap setid'
ok "a program's own SIGSYS for a call the policy allows" 'shim trap own-trap'
# Enough forks for some child to stop before its parent's fork event.
ok "fork keeps the argument registers in parent and child" 'shim trap fork 3000'
ok "an interrupted fork restarts as fork" 'shim trap fork-interrupted 2000'

ok "libproot.so runs untraced" \
  '[ "$(shim exec "$W/libproot.so" libproot.so)" = 0 ]'
ok "linker64 running \$HARNESS_PREFIX/bin/proot runs untraced" \
  '[ "$(shim exec "$W/linker64" linker64 "$W/prefix/bin/proot")" = 0 ]'
ok "linker64 running a relative proot runs untraced" \
  '[ "$(cd "$W" && shim exec "$W/linker64" linker64 prefix/bin/proot)" = 0 ]'
ok "linker64 running another program stays traced" \
  '[ "$(shim exec "$W/linker64" linker64 "$W/other")" -gt 0 ]'
ok "another program stays traced" \
  '[ "$(shim exec "$W/other" other)" -gt 0 ]'

# trapped-calls.h holds the x86_64 entries of the superproject's trapped-call
# list, EPERM where the list marks it and ENOSYS otherwise, with each number as
# the host headers have it when they name the call.
H=$here/../trapped-calls.h
{
  echo '#include <sys/syscall.h>'
  sed -n 's/^TRAPPED(\([0-9]*\), \([a-z0-9_]*\), .*)$/#ifdef SYS_\2\n_Static_assert(SYS_\2 == \1, "\2");\n#endif/p' "$H"
} > "$W/numbers.c"
ok "trapped-calls.h numbers match the host headers" \
  '$CC -fsyntax-only "$W/numbers.c" && [ "$(grep -c _Static_assert "$W/numbers.c")" -gt 0 ]'
super=$(git -C "$here" rev-parse --show-superproject-working-tree 2>/dev/null)
list=$super/app/src/main/cpp/proot/trapped-calls.txt
if [ -n "$super" ] && [ -f "$list" ]; then
  ok "trapped-calls.h matches $list" \
    '[ "$(awk '\''$1 == "x86_64" { print $2, ($3 == "EPERM" ? "EPERM" : "ENOSYS") }'\'' "$list" | sort)" = \
       "$(sed -n '\''s/^TRAPPED([0-9]*, \(.*\), \(.*\))$/\1 \2/p'\'' "$H" | sort)" ]'
else
  echo "SKIP: trapped-calls.h against the list: no harness.apk superproject here"
fi

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
