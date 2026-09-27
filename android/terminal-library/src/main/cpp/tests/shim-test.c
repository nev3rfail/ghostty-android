/*
 * The tracee side of run.sh: each mode checks one thing the shim does and exits
 * 0 when it holds.
 *
 *   trap MODE ARGS...   traps getpgrp, fork, uselib, acct, epoll_create and the
 *                       set-id calls the way Android's policy does, then runs
 *                       MODE ARGS... in this program
 *   parity N            a raw getpgrp and a raised SIGTRAP, then N execs of
 *                       itself that do the same
 *   thread-exec N       N execs, each from a thread other than the leader, each
 *                       thread making a raw getpgrp first
 *   threads N           N threads alive at once, each making a raw getpgrp
 *   enosys              a raw uselib returns ENOSYS and a raw acct EPERM, as
 *                       trapped-calls.txt marks them, then a raw getpgrp works
 *   setid               set-id calls that change no id succeed, ones that change
 *                       one get EPERM, setgroups succeeds, and setfsuid and
 *                       setfsgid return the effective ids
 *   epoll               epoll_create gives EINVAL for a size of 0 or less, and
 *                       a descriptor otherwise
 *   own-trap            traps getppid with a filter and handler of its own; the
 *                       handler runs, then a raw getpgrp works
 *   fork N              N raw forks, checking the argument registers in parent
 *                       and child
 *   fork-interrupted N  as fork, in one process, with a timer signal
 *                       interrupting some of them
 *   exec PATH ARGS...   execs PATH with ARGS... as its argv
 *   anything else       prints this process's TracerPid
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

static char *self;

static int raw_getpgrp(void) {
	return syscall(SYS_getpgrp) == getpgid(0);
}

static int install(struct sock_filter *f, unsigned short n) {
	struct sock_fprog prog = { n, f };
	return prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
	       syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog);
}

static const int legacy[] = {
	SYS_getpgrp, SYS_fork, SYS_uselib, SYS_acct, SYS_setuid, SYS_setgid,
	SYS_setreuid, SYS_setregid, SYS_setfsuid, SYS_setfsgid, SYS_setresgid, SYS_setgroups,
	SYS_epoll_create,
};
#define NLEGACY (sizeof legacy / sizeof *legacy)

static int trap_legacy(void) {
	struct sock_filter f[NLEGACY + 3];
	unsigned short n = 0;
	f[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
	for (size_t i = 0; i < NLEGACY; i++)
		f[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, legacy[i],
		                                      (unsigned char)(NLEGACY - i), 0);
	f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
	f[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
	return install(f, n);
}

static volatile sig_atomic_t own_sigsys;
static void on_sigsys(int sig, siginfo_t *si, void *uc) {
	(void)sig; (void)uc;
	if (si->si_syscall == SYS_getppid) own_sigsys = 1;
}

/* A program's own filter, trapping a call the policy allows, and its own
 * SIGSYS handler. */
static int own_trap(void) {
	struct sock_filter f[] = {
		BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
		BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 1, 0),
		BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
	};
	struct sigaction sa = { .sa_sigaction = on_sigsys, .sa_flags = SA_SIGINFO };
	if (sigaction(SIGSYS, &sa, 0) || install(f, sizeof f / sizeof *f)) return 1;
	syscall(SYS_getppid);
	return !own_sigsys || !raw_getpgrp();
}

static void reexec(const char *mode, int n) {
	char arg[16];
	snprintf(arg, sizeof arg, "%d", n);
	execl(self, self, mode, arg, (char *)0);
	perror("exec");
	_exit(1);
}

static volatile sig_atomic_t trapped;
static void on_trap(int sig) { (void)sig; trapped = 1; }

static int parity(int n) {
	signal(SIGTRAP, on_trap);
	raise(SIGTRAP);
	if (!trapped || !raw_getpgrp()) return 1;
	if (n > 0) reexec("parity", n - 1);
	return 0;
}

static int remaining;

static void *exec_from_thread(void *arg) {
	(void)arg;
	if (!raw_getpgrp()) _exit(1);
	reexec("thread-exec", remaining - 1);
	return 0;
}

static int thread_exec(int n) {
	pthread_t t;
	if (!raw_getpgrp()) return 1;
	if (n == 0) return 0;
	remaining = n;
	if (pthread_create(&t, 0, exec_from_thread, 0)) return 1;
	/* The leader waits outside any system call, so the exec is its only
	 * reason to expect a syscall exit next. */
	for (volatile int spin = 1; spin;)
		;
	return 1;
}

static pthread_barrier_t all_started;

static void *getpgrp_together(void *arg) {
	(void)arg;
	pthread_barrier_wait(&all_started);
	return raw_getpgrp() ? 0 : (void *)1;
}

static int threads(int n) {
	pthread_t t[n];
	void *failed;
	int bad = 0;
	pthread_barrier_init(&all_started, 0, (unsigned)n);
	for (int i = 0; i < n; i++)
		if (pthread_create(&t[i], 0, getpgrp_together, 0)) return 1;
	for (int i = 0; i < n; i++)
		if (pthread_join(t[i], &failed) || failed) bad = 1;
	return bad;
}

static int enosys(void) {
	errno = 0;
	if (syscall(SYS_uselib, 0) != -1 || errno != ENOSYS) return 1;
	errno = 0;
	if (syscall(SYS_acct, 0) != -1 || errno != EPERM) return 1;
	return !raw_getpgrp();
}

/* Whether a raw call returns want: 0 or a result, or -EPERM for an EPERM. */
static int gives(long ret, long want) {
	return want == -EPERM ? ret == -1 && errno == EPERM : ret == want;
}

static int setid(void) {
	long u = getuid(), g = getgid(), eu = geteuid(), eg = getegid();
	return !(gives(syscall(SYS_setuid, u), 0) && gives(syscall(SYS_setuid, u + 1), -EPERM) &&
	         gives(syscall(SYS_setgid, g), 0) && gives(syscall(SYS_setgid, g + 1), -EPERM) &&
	         gives(syscall(SYS_setreuid, u, u), 0) && gives(syscall(SYS_setreuid, -1, -1), 0) &&
	         gives(syscall(SYS_setreuid, u, u + 1), -EPERM) &&
	         gives(syscall(SYS_setregid, g, g), 0) && gives(syscall(SYS_setregid, -1, -1), 0) &&
	         gives(syscall(SYS_setregid, g + 1, -1), -EPERM) &&
	         gives(syscall(SYS_setresgid, g, -1, g), 0) && gives(syscall(SYS_setresgid, -1, g + 1, -1), -EPERM) &&
	         gives(syscall(SYS_setgroups, 0, 0), 0) &&
	         gives(syscall(SYS_setfsuid, u + 7), eu) && gives(syscall(SYS_setfsgid, g + 7), eg));
}

static int epoll(void) {
	errno = 0;
	if (syscall(SYS_epoll_create, 0) != -1 || errno != EINVAL) return 1;
	errno = 0;
	if (syscall(SYS_epoll_create, -1) != -1 || errno != EINVAL) return 1;
	return syscall(SYS_epoll_create, 1) < 0;
}

/* fork with every argument register set; returns its result and the registers
 * as the call left them. */
static long raw_fork(const long in[6], long out[6]) {
	long ret, a = in[0], b = in[1], c = in[2];
	register long r10 __asm__("r10") = in[3];
	register long r8 __asm__("r8") = in[4];
	register long r9 __asm__("r9") = in[5];
	__asm__ volatile("syscall"
	                 : "=a"(ret), "+D"(a), "+S"(b), "+d"(c), "+r"(r10), "+r"(r8), "+r"(r9)
	                 : "0"((long)SYS_fork)
	                 : "rcx", "r11", "memory");
	out[0] = a; out[1] = b; out[2] = c; out[3] = r10; out[4] = r8; out[5] = r9;
	return ret;
}

static int forks_in_turn(int n) {
	static const long in[6] = { 0x1111, 0x2222, 0x3333, 0x4444, 0x5555, 0x6666 };
	for (int i = 0; i < n; i++) {
		long out[6];
		int status;
		long pid = raw_fork(in, out);
		int same = !memcmp(in, out, sizeof out);
		if (pid == 0) _exit(same ? 0 : 3);
		if (pid < 0) { fprintf(stderr, "fork: %s\n", strerror((int)-pid)); return 1; }
		if (!same) { fprintf(stderr, "parent registers changed\n"); return 1; }
		if (waitpid((pid_t)pid, &status, 0) != pid || !WIFEXITED(status) || WEXITSTATUS(status)) {
			fprintf(stderr, "child registers changed\n");
			return 1;
		}
	}
	return 0;
}

/* Forkers side by side keep the tracer busy, so that some child stops before
 * its parent's fork event. */
#define FORKERS 4

static int forks(int n) {
	int status, failed = 0;
	for (int i = 0; i < FORKERS; i++)
		if (fork() == 0) _exit(forks_in_turn(n / FORKERS));
	for (int i = 0; i < FORKERS; i++)
		if (wait(&status) < 0 || !WIFEXITED(status) || WEXITSTATUS(status)) failed = 1;
	return failed;
}

static void on_alarm(int sig) { (void)sig; }

/* A timer signal every 200 us interrupts some forks, which the kernel then
 * restarts. */
static int forks_interrupted(int n) {
	struct sigaction sa = { .sa_handler = on_alarm, .sa_flags = SA_RESTART };
	struct itimerval every = { { 0, 200 }, { 0, 200 } };
	if (sigaction(SIGALRM, &sa, 0) || setitimer(ITIMER_REAL, &every, 0)) return 1;
	return forks_in_turn(n);
}

static int tracer_pid(void) {
	char line[256];
	FILE *f = fopen("/proc/self/status", "r");
	if (!f) return 1;
	while (fgets(line, sizeof line, f))
		if (!strncmp(line, "TracerPid:", 10)) printf("%d\n", atoi(line + 10));
	return 0;
}

int main(int argc, char **argv) {
	self = argv[0];
	if (argc > 2 && !strcmp(argv[1], "trap")) {
		if (trap_legacy()) { perror("seccomp"); return 1; }
		argv[1] = self;
		execv(self, argv + 1);
		perror("exec");
		return 1;
	}
	if (argc > 2 && !strcmp(argv[1], "exec")) {
		execv(argv[2], argv + 3);
		perror("exec");
		return 1;
	}
	if (argc > 2 && !strcmp(argv[1], "parity")) return parity(atoi(argv[2]));
	if (argc > 2 && !strcmp(argv[1], "thread-exec")) return thread_exec(atoi(argv[2]));
	if (argc > 2 && !strcmp(argv[1], "fork")) return forks(atoi(argv[2]));
	if (argc > 2 && !strcmp(argv[1], "fork-interrupted")) return forks_interrupted(atoi(argv[2]));
	if (argc > 1 && !strcmp(argv[1], "enosys")) return enosys();
	if (argc > 1 && !strcmp(argv[1], "own-trap")) return own_trap();
	if (argc > 1 && !strcmp(argv[1], "epoll")) return epoll();
	if (argc > 2 && !strcmp(argv[1], "threads")) return threads(atoi(argv[2]));
	if (argc > 1 && !strcmp(argv[1], "setid")) return setid();
	return tracer_pid();
}
