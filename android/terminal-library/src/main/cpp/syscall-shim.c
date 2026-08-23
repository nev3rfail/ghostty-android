// Runs a program that expects a full Linux syscall ABI under Android's.
//
// Android grants an app an allowlist of syscalls covering what bionic calls,
// and bionic only ever uses the `*at` variants. Anything else -- `access`,
// `poll`, `pipe`, `dup2`, `unlink` -- is answered with SECCOMP_RET_KILL_PROCESS.
// A runtime built for ordinary Linux uses those freely, and dies on the first
// one. Patching its libc is not enough, because a runtime like Bun issues some
// syscalls directly rather than through libc.
//
// The kernel runs the ptrace syscall-entry stop *before* it evaluates seccomp,
// specifically so a tracer's changes are the ones the filter judges. So a
// tracer that rewrites the legacy call into its `*at` equivalent -- inserting
// AT_FDCWD, shifting the arguments -- hands seccomp a syscall it permits.
//
// This is for x86_64. The aarch64 Linux ABI never had the legacy syscalls, so
// there is nothing to translate there and nothing to run this for.
//
//   syscall-shim <program> [args...]
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
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

#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_REMOVEDIR 0x200

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

#define O_CREAT_WRONLY_TRUNC 0x241  // O_WRONLY|O_CREAT|O_TRUNC

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

    default:
        (void)a3;
        return 0;
    }
}

// Points a path-taking syscall at the stand-in /etc, if that is what it asked
// for. Runs after the legacy rewrite, so it sees the final syscall number.
static int redirect_paths(pid_t pid, struct user_regs_struct *r) {
    unsigned long *slot = NULL;
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
static pid_t tracee[MAX_TRACEES];
static int in_entry[MAX_TRACEES];
// dup2(fd, fd) is answered by fcntl; the descriptor to report is kept here
// until the exit stop can substitute it for fcntl's flags.
static long dup2_result[MAX_TRACEES];

static int slot_for(pid_t pid) {
    for (int i = 0; i < MAX_TRACEES; i++) if (tracee[i] == pid) return i;
    for (int i = 0; i < MAX_TRACEES; i++) {
        if (tracee[i] == 0) {
            tracee[i] = pid; in_entry[i] = 1; dup2_result[i] = -1;
            return i;
        }
    }
    return -1;
}

static int get_regs(pid_t pid, struct user_regs_struct *r) {
    return ptrace(PTRACE_GETREGS, pid, 0, r);
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
    ptrace(PTRACE_SETOPTIONS, child, 0,
           PTRACE_O_EXITKILL | PTRACE_O_TRACECLONE |
           PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK);
    slot_for(child);
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
            int s = slot_for(pid); if (s >= 0) tracee[s] = 0;
            continue;
        }
        if (WIFSIGNALED(status)) {
            if (pid == child) { exit_code = 128 + WTERMSIG(status); break; }
            int s = slot_for(pid); if (s >= 0) tracee[s] = 0;
            continue;
        }
        if (!WIFSTOPPED(status)) continue;

        int slot = slot_for(pid);
        int signo = WSTOPSIG(status);
        int deliver = 0;

        if (signo == SIGTRAP) {
            unsigned event = (unsigned)status >> 16;
            if (event) {
                // A clone/fork/vfork report; the new tracee arrives on its own.
            } else if (slot >= 0) {
                struct user_regs_struct regs;
                if (in_entry[slot]) {
                    if (get_regs(pid, &regs) == 0) {
                        long original = (long)regs.orig_rax;
                        if (translate(pid, &regs)) {
                            ptrace(PTRACE_SETREGS, pid, 0, &regs);
                            if (original == NR_dup2 && regs.orig_rax == SYS_fcntl)
                                dup2_result[slot] = (long)regs.rdi;
                            if (verbose)
                                fprintf(stderr, "[shim] %ld -> %ld\n",
                                        original, (long)regs.orig_rax);
                        }
                    }
                } else if (dup2_result[slot] >= 0) {
                    if (get_regs(pid, &regs) == 0) {
                        if ((long)regs.rax >= 0) {
                            regs.rax = (unsigned long)dup2_result[slot];
                            ptrace(PTRACE_SETREGS, pid, 0, &regs);
                        }
                    }
                    dup2_result[slot] = -1;
                }
                in_entry[slot] = !in_entry[slot];
            }
        } else if (signo != SIGSTOP) {
            deliver = signo;
        }

        ptrace(PTRACE_SYSCALL, pid, 0, deliver);
    }
    return exit_code;
}

#endif  /* __x86_64__ */
