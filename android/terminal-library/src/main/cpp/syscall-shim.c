// Runs a program that expects a full Linux syscall ABI under Android's.
//
// Android grants an app an allowlist of syscalls covering what bionic calls,
// and bionic only ever uses the `*at` variants. Anything else -- `access`,
// `poll`, `pipe`, `dup2`, `unlink`, `fork` -- the policy traps: the call does
// not run and the process gets SIGSYS, which kills it. A runtime built for
// ordinary Linux uses those freely. Patching its libc is not enough, because a
// runtime like Bun issues some syscalls directly rather than through libc.
//
// The kernel runs the ptrace syscall-entry stop *before* it evaluates seccomp,
// specifically so a tracer's changes are the ones the filter judges. So a
// tracer that rewrites the legacy call into its `*at` equivalent -- inserting
// AT_FDCWD, shifting the arguments -- hands seccomp a syscall it permits. A
// call the policy traps with no translation is answered instead of raising
// SIGSYS: the set-id calls by proot's rule, the rest with EPERM or ENOSYS, as
// trapped-calls.h lists it.
//
// The shim traces the whole tree it starts, following forks and clones. A
// process that execs a proot is detached at that exec, since proot traces its
// own children. proot translates legacy calls too, but it also resolves every
// path a call names, a per-call cost the shim keeps off the rest of the tree.
//
// This is for x86_64. The aarch64 Linux ABI never had the legacy syscalls, so
// there is nothing to translate there and nothing to run this for.
//
//   syscall-shim <program> [args...]
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <linux/audit.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/user.h>
#include <sys/wait.h>

#if !defined(__x86_64__)

// Every other architecture Android runs on has only the `*at` syscalls in its
// ABI, which is exactly the set the policy allows. Nothing to translate, so the
// program simply takes over.
int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <program> [args...]\n", argv[0]);
        return 2;
    }
    execv(argv[1], &argv[1]);
    fprintf(stderr, "%s: cannot execute %s: %s\n",
            argv[0], argv[1], strerror(errno));
    return 127;
}

#else

// Legacy x86_64 syscall numbers.
#define NR_lstat         6
#define NR_poll          7
#define NR_access        21
#define NR_pipe          22
#define NR_select        23
#define NR_dup2          33
#define NR_pause         34
#define NR_getpgrp       111
#define NR_epoll_create  213
#define NR_epoll_wait    232
#define NR_inotify_init  253
#define NR_signalfd      282
#define NR_eventfd       284
#define NR_rename        82
#define NR_mkdir         83
#define NR_rmdir         84
#define NR_creat         85
#define NR_link          86
#define NR_unlink        87
#define NR_symlink       88
#define NR_chmod         90
#define NR_chown         92
#define NR_lchown        94
#define NR_accept        43
#define NR_faccessat2    439
#define NR_fork          57  // musl's _Fork issues it

#define O_CREAT_WRONLY_TRUNC 0x241  // O_WRONLY|O_CREAT|O_TRUNC

// The kernel's result for a call it restarts after a signal.
#define ERESTARTNOINTR 513
#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1  // si_code of a SIGSYS a seccomp filter raised
#endif

static int verbose;

// Where materialised arguments are written: far enough below the stack pointer
// to clear the red zone and any pending frame. Paths and timespecs get separate
// room so one call can rewrite both.
#define SCRATCH_OFFSET 512
#define PATH_SCRATCH_OFFSET 1024
#define PATH_MAX_MAPPED 256

// Stands in for /etc, which Android does not have. A program carrying its own
// resolver looks for /etc/resolv.conf and, finding nothing, falls back to
// localhost and times out.
static const char *etc_dir;
static size_t etc_dir_len;

static int poke_words(pid_t pid, unsigned long addr, const void *src, size_t len) {
    unsigned long words[4] = {0};
    if (len > sizeof words) return -1;
    memcpy(words, src, len);
    size_t n = (len + sizeof(long) - 1) / sizeof(long);
    for (size_t i = 0; i < n; i++) {
        if (ptrace(PTRACE_POKEDATA, pid, addr + i * sizeof(long),
                   (void *)words[i]) != 0)
            return -1;
    }
    return 0;
}

static int peek_words(pid_t pid, unsigned long addr, void *dst, size_t len) {
    unsigned long words[4] = {0};
    if (len > sizeof words) return -1;
    size_t n = (len + sizeof(long) - 1) / sizeof(long);
    for (size_t i = 0; i < n; i++) {
        errno = 0;
        long w = ptrace(PTRACE_PEEKDATA, pid, addr + i * sizeof(long), 0);
        if (w == -1 && errno) return -1;
        words[i] = (unsigned long)w;
    }
    memcpy(dst, words, len);
    return 0;
}

static int read_string(pid_t pid, unsigned long addr, char *out, size_t max) {
    size_t written = 0;
    while (written < max - 1) {
        errno = 0;
        long word = ptrace(PTRACE_PEEKDATA, pid, addr + written, 0);
        if (word == -1 && errno) return -1;
        char bytes[sizeof(long)];
        memcpy(bytes, &word, sizeof word);
        for (size_t i = 0; i < sizeof bytes && written < max - 1; i++) {
            out[written++] = bytes[i];
            if (bytes[i] == '\0') return 0;
        }
    }
    out[max - 1] = '\0';
    return -1;
}

// Redirects a path under /etc to the directory standing in for it. Returns the
// address of the replacement, or 0 to leave the argument alone.
static unsigned long map_etc_path(pid_t pid, unsigned long stack_top,
                                  unsigned long path_arg) {
    if (!etc_dir || !path_arg) return 0;

    char path[PATH_MAX_MAPPED];
    if (read_string(pid, path_arg, path, sizeof path) != 0) return 0;
    if (strncmp(path, "/etc/", 5) != 0) return 0;

    char mapped[PATH_MAX_MAPPED];
    if (etc_dir_len + strlen(path + 4) + 1 > sizeof mapped) return 0;
    memcpy(mapped, etc_dir, etc_dir_len);
    strcpy(mapped + etc_dir_len, path + 4);  // keeps the leading slash

    unsigned long dest = stack_top - PATH_SCRATCH_OFFSET;
    size_t len = strlen(mapped) + 1;
    for (size_t off = 0; off < len; off += sizeof(long)) {
        unsigned long word = 0;
        size_t chunk = len - off < sizeof(long) ? len - off : sizeof(long);
        memcpy(&word, mapped + off, chunk);
        if (ptrace(PTRACE_POKEDATA, pid, dest + off, (void *)word) != 0) return 0;
    }
    if (verbose) fprintf(stderr, "[shim] %s -> %s\n", path, mapped);
    return dest;
}

// Rewrites a legacy syscall into an equivalent the policy allows.
// Returns 1 if the registers were changed.
static int translate_legacy(pid_t pid, struct user_regs_struct *r) {
    unsigned long a0 = r->rdi, a1 = r->rsi, a2 = r->rdx, a3 = r->r10;
    unsigned long scratch = r->rsp - SCRATCH_OFFSET;

    switch ((long)r->orig_rax) {
    case NR_access:  // access(path, mode)
        r->orig_rax = SYS_faccessat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1; r->r10 = 0;
        return 1;

    case NR_faccessat2:  // faccessat2(dirfd, path, mode, flags)
        // faccessat has no flags argument; AT_EACCESS and AT_SYMLINK_NOFOLLOW
        // are dropped, which loosens the check rather than tightening it.
        r->orig_rax = SYS_faccessat;
        r->r10 = 0;
        return 1;

    case NR_lstat:  // lstat(path, statbuf)
        r->orig_rax = SYS_newfstatat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1;
        r->r10 = AT_SYMLINK_NOFOLLOW;
        return 1;

    case NR_pipe:  // pipe(fds)
        r->orig_rax = SYS_pipe2;
        r->rsi = 0;
        return 1;

    case NR_dup2:  // dup2(oldfd, newfd)
        if (a0 == a1) {
            // dup3 rejects equal descriptors, where dup2 returns newfd for a
            // valid one. F_GETFD validates it; the exit stop restores newfd as
            // the result.
            r->orig_rax = SYS_fcntl;
            r->rsi = 1 /* F_GETFD */; r->rdx = 0;
            return 1;
        }
        r->orig_rax = SYS_dup3;
        r->rdx = 0;
        return 1;

    case NR_pause:  // pause(void)
        // ppoll with nothing to wait on and no timeout returns only on a
        // signal, which is what pause promises.
        r->orig_rax = SYS_ppoll;
        r->rdi = 0; r->rsi = 0; r->rdx = 0; r->r10 = 0; r->r8 = 0;
        return 1;

    case NR_poll: {  // poll(fds, nfds, timeout_ms)
        r->orig_rax = SYS_ppoll;
        r->rdi = a0; r->rsi = a1;
        if ((long)a2 < 0) {
            r->rdx = 0;  // no timeout
        } else {
            long long ts[2] = { (long long)(a2 / 1000),
                                (long long)((a2 % 1000) * 1000000) };
            if (poke_words(pid, scratch, ts, sizeof ts) != 0) return 0;
            r->rdx = scratch;
        }
        r->r10 = 0; r->r8 = 0;
        return 1;
    }

    case NR_select: {  // select(n, r, w, e, timeval*)
        r->orig_rax = SYS_pselect6;
        if (r->r8) {
            long long tv[2];
            if (peek_words(pid, r->r8, tv, sizeof tv) != 0) return 0;
            long long ts[2] = { tv[0], tv[1] * 1000 };  // usec -> nsec
            if (poke_words(pid, scratch, ts, sizeof ts) != 0) return 0;
            r->r8 = scratch;
        }
        r->r9 = 0;
        return 1;
    }

    case NR_rename:  // rename(old, new)
        r->orig_rax = SYS_renameat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0;
        r->rdx = (unsigned long)AT_FDCWD; r->r10 = a1;
        return 1;

    case NR_mkdir:  // mkdir(path, mode)
        r->orig_rax = SYS_mkdirat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1;
        return 1;

    case NR_rmdir:  // rmdir(path)
        r->orig_rax = SYS_unlinkat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = AT_REMOVEDIR;
        return 1;

    case NR_unlink:  // unlink(path)
        r->orig_rax = SYS_unlinkat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = 0;
        return 1;

    case NR_creat:  // creat(path, mode)
        r->orig_rax = SYS_openat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0;
        r->rdx = O_CREAT_WRONLY_TRUNC; r->r10 = a1;
        return 1;

    case NR_link:  // link(old, new)
        r->orig_rax = SYS_linkat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0;
        r->rdx = (unsigned long)AT_FDCWD; r->r10 = a1; r->r8 = 0;
        return 1;

    case NR_symlink:  // symlink(target, linkpath)
        r->orig_rax = SYS_symlinkat;
        r->rdi = a0; r->rsi = (unsigned long)AT_FDCWD; r->rdx = a1;
        return 1;

    case NR_chmod:  // chmod(path, mode)
        r->orig_rax = SYS_fchmodat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1; r->r10 = 0;
        return 1;

    case NR_chown:  // chown(path, uid, gid)
        r->orig_rax = SYS_fchownat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1;
        r->r10 = a2; r->r8 = 0;
        return 1;

    case NR_lchown:  // lchown(path, uid, gid)
        r->orig_rax = SYS_fchownat;
        r->rdi = (unsigned long)AT_FDCWD; r->rsi = a0; r->rdx = a1;
        r->r10 = a2; r->r8 = AT_SYMLINK_NOFOLLOW;
        return 1;

    case NR_getpgrp:  // getpgrp(void)
        r->orig_rax = SYS_getpgid;
        r->rdi = 0;
        return 1;

    case NR_accept:  // accept(fd, addr, addrlen)
        r->orig_rax = SYS_accept4;
        r->r10 = 0;
        return 1;

    case NR_epoll_create:  // epoll_create(size)
        r->orig_rax = SYS_epoll_create1;
        r->rdi = 0;
        return 1;

    case NR_epoll_wait:  // epoll_wait(epfd, events, maxevents, timeout)
        r->orig_rax = SYS_epoll_pwait;
        r->r8 = 0; r->r9 = 0;
        return 1;

    case NR_inotify_init:  // inotify_init(void)
        r->orig_rax = SYS_inotify_init1;
        r->rdi = 0;
        return 1;

    case NR_signalfd:  // signalfd(fd, mask, sizemask)
        r->orig_rax = SYS_signalfd4;
        r->r10 = 0;
        return 1;

    case NR_eventfd:  // eventfd(count)
        r->orig_rax = SYS_eventfd2;
        r->rsi = 0;
        return 1;

    case NR_fork:  // fork(void)
        r->orig_rax = SYS_clone;
        r->rdi = SIGCHLD; r->rsi = 0; r->rdx = 0; r->r10 = 0; r->r8 = 0;
        return 1;

    default:
        (void)a3;
        return 0;
    }
}

// Points a path-taking syscall at the stand-in /etc, if that is what it asked
// for. Runs after the legacy rewrite, so it sees the final syscall number.
static int redirect_paths(pid_t pid, struct user_regs_struct *r) {
    __typeof__(r->rdi) *slot = NULL;
    switch ((long)r->orig_rax) {
    case SYS_open: case SYS_stat: case SYS_readlink:
        slot = &r->rdi; break;
    case SYS_openat: case SYS_newfstatat: case SYS_faccessat:
    case SYS_readlinkat: case SYS_statx:
        slot = &r->rsi; break;
    default:
        return 0;
    }

    unsigned long mapped = map_etc_path(pid, r->rsp, *slot);
    if (!mapped) return 0;
    *slot = mapped;
    return 1;
}

static int translate(pid_t pid, struct user_regs_struct *r) {
    int changed = translate_legacy(pid, r);
    changed |= redirect_paths(pid, r);
    return changed;
}

#define MAX_TRACEES 512

struct tracee {
    pid_t pid;  // 0 for a free slot
    // The next syscall stop is an entry.
    int in_entry;
    // Its first stop has been handled.
    int started;
    // Stopped at its first stop, waiting for its parent's fork event, and the
    // signal of that stop, delivered when it starts.
    int held;
    int held_signal;
    // Exited before its parent's fork event, which frees the slot.
    int dead;
    // dup2(fd, fd) is answered by fcntl; the descriptor to report is kept here
    // until the exit stop can substitute it for fcntl's flags.
    long dup2_result;
    // fork runs as clone, which takes other arguments. The caller's argument
    // registers are kept here and put back where fork returns, since the
    // system-call ABI preserves them: in the parent at its exit stop, and in
    // the child, which gets a copy, at its first stop.
    int fork_saved;
    unsigned long long fork_args[6];
};

static struct tracee tracees[MAX_TRACEES];

static struct tracee *find(pid_t pid) {
    for (int i = 0; i < MAX_TRACEES; i++)
        if (tracees[i].pid == pid) return &tracees[i];
    return NULL;
}

static struct tracee *add(pid_t pid) {
    struct tracee *t = find(0);
    if (t) {
        memset(t, 0, sizeof *t);
        t->pid = pid; t->in_entry = 1; t->dup2_result = -1;
    }
    return t;
}

static void drop(pid_t pid) {
    struct tracee *t = find(pid);
    if (t) t->pid = 0;
}

// A slot for a new tracee. Without one its registers and parity cannot be
// kept, so a tracee beyond MAX_TRACEES is killed.
static struct tracee *add_new(pid_t pid) {
    struct tracee *t = add(pid);
    if (!t) {
        fprintf(stderr, "syscall-shim: more than %d processes and threads, killing %d\n",
                MAX_TRACEES, pid);
        kill(pid, SIGKILL);
    }
    return t;
}

// pid exited. A tracee that has not had its parent's fork event keeps its slot
// as dead until that event, so the event cannot give the slot to a later
// process with the same pid.
static void exited(pid_t pid) {
    struct tracee *t = find(pid);
    if (t && (t->started || !t->held)) { t->pid = 0; return; }
    if (t || (t = add(pid))) { t->held = 0; t->dead = 1; }
}

static int get_regs(pid_t pid, struct user_regs_struct *r) {
    return ptrace(PTRACE_GETREGS, pid, 0, r);
}

static void save_fork_args(struct tracee *t, const struct user_regs_struct *r) {
    t->fork_args[0] = r->rdi; t->fork_args[1] = r->rsi; t->fork_args[2] = r->rdx;
    t->fork_args[3] = r->r10; t->fork_args[4] = r->r8;  t->fork_args[5] = r->r9;
    t->fork_saved = 1;
}

static void restore_fork_args(struct tracee *t) {
    struct user_regs_struct r;
    t->fork_saved = 0;
    if (get_regs(t->pid, &r) != 0) return;
    r.rdi = t->fork_args[0]; r.rsi = t->fork_args[1]; r.rdx = t->fork_args[2];
    r.r10 = t->fork_args[3]; r.r8 = t->fork_args[4];  r.r9 = t->fork_args[5];
    // A clone interrupted by a signal is restarted from orig_rax, with the
    // registers restored here, so it restarts as the fork it was.
    if ((long)r.rax == -ERESTARTNOINTR) r.orig_rax = NR_fork;
    ptrace(PTRACE_SETREGS, t->pid, 0, &r);
}

// Starts a new tracee at its first stop, whose signal is signo. That is
// usually the SIGSTOP every new tracee starts with, which is not delivered.
static void start(struct tracee *t, int signo) {
    t->started = 1; t->held = 0;
    if (t->fork_saved) restore_fork_args(t);
    ptrace(PTRACE_SYSCALL, t->pid, 0, signo == SIGSTOP ? 0 : signo);
}

// The linker64 paths, and their realpaths.
#ifndef SHIM_LINKERS
#define SHIM_LINKERS "/system/bin/linker64", "/apex/com.android.runtime/bin/linker64", \
                     "/system/bin/bootstrap/linker64"
#endif
static const char *const linker_paths[] = { SHIM_LINKERS };
static char linkers[sizeof linker_paths / sizeof *linker_paths][PATH_MAX];
// libproot.so, beside this program.
static char proot_lib[PATH_MAX];
static const char *prefix;

static void find_proots(void) {
    for (size_t i = 0; i < sizeof linkers / sizeof *linkers; i++)
        if (!realpath(linker_paths[i], linkers[i])) linkers[i][0] = '\0';
    char *slash = realpath("/proc/self/exe", proot_lib) ? strrchr(proot_lib, '/') : NULL;
    if (slash && (size_t)(slash - proot_lib) + sizeof "/libproot.so" <= sizeof proot_lib)
        strcpy(slash, "/libproot.so");
    else
        proot_lib[0] = '\0';
    prefix = getenv("HARNESS_PREFIX");
}

// Whether real, a realpath, is libproot.so or $HARNESS_PREFIX/bin/proot.
static int is_proot(const char *real) {
    char bin[PATH_MAX], path[PATH_MAX];
    if (proot_lib[0] && !strcmp(real, proot_lib)) return 1;
    if (!prefix || snprintf(bin, sizeof bin, "%s/bin/proot", prefix) >= (int)sizeof bin)
        return 0;
    return realpath(bin, path) && !strcmp(real, path);
}

// Whether the program pid has just exec'd is a proot: run itself, or run by
// linker64 as its argv[1], which is resolved against pid's working directory.
static int execd_proot(pid_t pid) {
    char path[PATH_MAX], real[PATH_MAX], cmdline[PATH_MAX];
    snprintf(path, sizeof path, "/proc/%d/exe", pid);
    if (!realpath(path, real)) return 0;
    if (is_proot(real)) return 1;

    int linker = 0;
    for (size_t i = 0; i < sizeof linkers / sizeof *linkers; i++)
        if (linkers[i][0] && !strcmp(real, linkers[i])) linker = 1;
    if (!linker) return 0;

    snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, cmdline, sizeof cmdline - 1);
    close(fd);
    if (n <= 0) return 0;
    cmdline[n] = '\0';
    size_t argv0 = strlen(cmdline);
    if ((ssize_t)argv0 + 1 >= n) return 0;
    const char *arg1 = cmdline + argv0 + 1;
    int len = arg1[0] == '/' ? snprintf(path, sizeof path, "%s", arg1)
                             : snprintf(path, sizeof path, "/proc/%d/cwd/%s", pid, arg1);
    return len < (int)sizeof path && realpath(path, real) && is_proot(real);
}

// The error answering nr when Android's policy traps it, or 0. A SIGSYS for
// any other call comes from a filter of the program's own, and is its to
// handle.
static int policy_error(int nr) {
    switch (nr) {
#define TRAPPED(nr, name, error) case nr: return error;
#include "trapped-calls.h"
#undef TRAPPED
    default:
        return 0;
    }
}

// 0 when every id names the current one or is -1, -EPERM otherwise; uid tells
// user ids from group ids. The shim's ids are its tracees'.
static long unchanged_ids(int uid, const unsigned long long ids[3]) {
    unsigned current[3];
    int status = uid ? getresuid(&current[0], &current[1], &current[2])
                     : getresgid(&current[0], &current[1], &current[2]);
    if (status < 0) return -EPERM;
    for (int i = 0; i < 3; i++)
        if ((unsigned)ids[i] != (unsigned)-1 && (unsigned)ids[i] != current[i]) return -EPERM;
    return 0;
}

// The result of a call the policy trapped, into *result, or 0 to leave its
// SIGSYS to the program. The set-id calls succeed when they change no id and
// get EPERM otherwise, setgroups succeeds, and setfsuid and setfsgid return the
// current id, as proot answers them; every other call gets its error from
// trapped-calls.h.
static int trapped_result(int nr, const struct user_regs_struct *r, long *result) {
    int error = policy_error(nr);
    if (!error) return 0;
    switch (nr) {
    case SYS_setuid: case SYS_setgid: {
        unsigned long long ids[3] = { r->rdi, r->rdi, r->rdi };
        *result = unchanged_ids(nr == SYS_setuid, ids);
        return 1;
    }
    case SYS_setreuid: case SYS_setregid: {
        unsigned long long ids[3] = { r->rdi, r->rsi, (unsigned)-1 };
        *result = unchanged_ids(nr == SYS_setreuid, ids);
        return 1;
    }
    case SYS_setresuid: case SYS_setresgid: {
        unsigned long long ids[3] = { r->rdi, r->rsi, r->rdx };
        *result = unchanged_ids(nr == SYS_setresuid, ids);
        return 1;
    }
    case SYS_setgroups: *result = 0; return 1;
    case SYS_setfsuid: *result = geteuid(); return 1;
    case SYS_setfsgid: *result = getegid(); return 1;
    default: *result = -error; return 1;
    }
}

// One syscall stop: the entry rewrites the call, the exit fixes up its result.
static void syscall_stop(struct tracee *t) {
    struct user_regs_struct regs;
    if (t->in_entry) {
        if (get_regs(t->pid, &regs) == 0) {
            struct user_regs_struct asked = regs;
            if (translate(t->pid, &regs)) {
                ptrace(PTRACE_SETREGS, t->pid, 0, &regs);
                if ((long)asked.orig_rax == NR_dup2 && (long)regs.orig_rax == SYS_fcntl)
                    t->dup2_result = (long)asked.rdi;
                if ((long)asked.orig_rax == NR_fork) save_fork_args(t, &asked);
                if (verbose)
                    fprintf(stderr, "[shim] %ld -> %ld\n",
                            (long)asked.orig_rax, (long)regs.orig_rax);
            }
        }
    } else if (t->fork_saved) {
        restore_fork_args(t);
    } else if (t->dup2_result >= 0) {
        if (get_regs(t->pid, &regs) == 0 && (long)regs.rax >= 0) {
            regs.rax = (unsigned long)t->dup2_result;
            ptrace(PTRACE_SETREGS, t->pid, 0, &regs);
        }
        t->dup2_result = -1;
    }
    t->in_entry = !t->in_entry;
}

// A fork, vfork or clone event in parent, which may be NULL, naming child.
static void new_child(struct tracee *parent, pid_t child, unsigned event) {
    struct tracee *c = find(child);
    if (c && c->dead) { c->pid = 0; return; }
    if (!c && !(c = add_new(child))) return;
    if (c->started) return;
    if (parent && parent->fork_saved && event == PTRACE_EVENT_FORK) {
        memcpy(c->fork_args, parent->fork_args, sizeof c->fork_args);
        c->fork_saved = 1;
    }
    if (c->held) start(c, c->held_signal);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <program> [args...]\n", argv[0]);
        return 2;
    }
    verbose = getenv("SYSCALL_SHIM_VERBOSE") != NULL;
    etc_dir = getenv("SYSCALL_SHIM_ETC");
    if (etc_dir) {
        etc_dir_len = strlen(etc_dir);
        while (etc_dir_len && etc_dir[etc_dir_len - 1] == '/') etc_dir_len--;
    }

    pid_t child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        execv(argv[1], &argv[1]);
        fprintf(stderr, "%s: cannot execute %s: %s\n",
                argv[0], argv[1], strerror(errno));
        _exit(127);
    }

    // The traced program owns the terminal. Job-control signals reach it
    // directly through the process group, so the tracer must not take them
    // and die first.
    signal(SIGINT, SIG_IGN);
    signal(SIGQUIT, SIG_IGN);
    signal(SIGTSTP, SIG_IGN);
    signal(SIGTTIN, SIG_IGN);
    signal(SIGTTOU, SIG_IGN);

    int status;
    waitpid(child, &status, 0);
    // A syscall stop is SIGTRAP | 0x80, apart from a SIGTRAP sent to the
    // program. An exec is reported as an event stop.
    ptrace(PTRACE_SETOPTIONS, child, 0,
           PTRACE_O_EXITKILL | PTRACE_O_TRACECLONE |
           PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK |
           PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXEC);
    find_proots();
    add(child)->started = 1;
    ptrace(PTRACE_SYSCALL, child, 0, 0);

    int exit_code = 0;
    for (;;) {
        pid_t pid = waitpid(-1, &status, __WALL);
        if (pid < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (WIFEXITED(status)) {
            if (pid == child) { exit_code = WEXITSTATUS(status); break; }
            exited(pid);
            continue;
        }
        if (WIFSIGNALED(status)) {
            if (pid == child) { exit_code = 128 + WTERMSIG(status); break; }
            exited(pid);
            continue;
        }
        if (!WIFSTOPPED(status)) continue;

        struct tracee *t = find(pid);
        int signo = WSTOPSIG(status);
        unsigned event = (unsigned)status >> 16;
        int deliver = 0;

        // A dead slot names an earlier process with this pid.
        if (t && t->dead) { t->pid = 0; t = NULL; }
        if (!t) {
            // A new tracee's first stop, which can arrive before its parent's
            // fork event. It waits for that event, which says whether it has
            // fork's registers to restore.
            if ((t = add_new(pid))) { t->held = 1; t->held_signal = signo; }
            continue;
        }
        if (!t->started) { start(t, signo); continue; }

        if (signo == SIGTRAP && event) {
            unsigned long msg = 0;
            ptrace(PTRACE_GETEVENTMSG, pid, 0, &msg);
            if (event == PTRACE_EVENT_EXEC) {
                // A thread other than the leader that execs takes the leader's
                // pid; msg is its former one, which reports no exit.
                if ((pid_t)msg != pid) drop((pid_t)msg);
                if (t) { t->in_entry = 0; t->dup2_result = -1; t->fork_saved = 0; }
                // proot has not forked yet, so it starts untraced and can trace
                // its own children.
                if (execd_proot(pid)) {
                    if (verbose) fprintf(stderr, "[shim] detached proot %d\n", pid);
                    drop(pid);
                    ptrace(PTRACE_DETACH, pid, 0, 0);
                    continue;
                }
            } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK ||
                       event == PTRACE_EVENT_CLONE) {
                new_child(t, (pid_t)msg, event);
            }
        } else if (signo == (SIGTRAP | 0x80)) {
            if (t) syscall_stop(t);
        } else if (signo == SIGSYS) {
            // The kernel may report no exit stop for a trapped call.
            if (t) t->in_entry = 1;
            siginfo_t si;
            struct user_regs_struct regs;
            long result;
            if (ptrace(PTRACE_GETSIGINFO, pid, 0, &si) == 0 && si.si_code == SYS_SECCOMP &&
                si.si_arch == AUDIT_ARCH_X86_64 && get_regs(pid, &regs) == 0 &&
                trapped_result(si.si_syscall, &regs, &result)) {
                regs.rax = (unsigned long)result;
                ptrace(PTRACE_SETREGS, pid, 0, &regs);
                if (verbose) fprintf(stderr, "[shim] %d -> %ld\n", si.si_syscall, result);
            } else {
                deliver = signo;
            }
        } else if (signo != SIGSTOP) {
            deliver = signo;
        }

        ptrace(PTRACE_SYSCALL, pid, 0, deliver);
    }
    return exit_code;
}

#endif  /* __x86_64__ */
