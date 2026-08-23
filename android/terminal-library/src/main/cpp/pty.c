// Pseudoterminal support for the terminal library.
//
// A terminal emulator needs a pty rather than a pipe: interactive programs ask
// the kernel whether their output is a tty, put it in raw mode, and expect
// SIGWINCH when it is resized. None of that is possible over pipes.
//
// The master file descriptor is handed to the JVM, which owns it from then on
// and does its own reading and writing. This file only creates the pty, resizes
// it, and reaps the child.

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "Pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void throw_io_exception(JNIEnv *env, const char *what, int err) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", what, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
    LOGE("%s", message);
}

/// Copy a Java string array into a NULL-terminated char* array.
/// Returns NULL and leaves nothing allocated if the array cannot be read.
static char **to_string_array(JNIEnv *env, jobjectArray array) {
    jsize count = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t)count + 1, sizeof(char *));
    if (out == NULL) return NULL;

    for (jsize i = 0; i < count; i++) {
        jstring element = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, element, NULL);
        out[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, element, chars);
        (*env)->DeleteLocalRef(env, element);
        if (out[i] == NULL) {
            for (jsize j = 0; j < i; j++) free(out[j]);
            free(out);
            return NULL;
        }
    }
    return out;
}

static void free_string_array(char **array) {
    if (array == NULL) return;
    for (char **p = array; *p != NULL; p++) free(*p);
    free(array);
}

/// The state a freshly opened terminal is expected to be in. A shell inherits
/// this and reconfigures whatever it wants; programs that never touch termios
/// rely on it being sane.
static void sane_termios(struct termios *tios) {
    memset(tios, 0, sizeof(*tios));
    tios->c_iflag = ICRNL | IXON | IUTF8;
    tios->c_oflag = OPOST | ONLCR;
    tios->c_cflag = CREAD | CS8 | HUPCL | B38400;
    tios->c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHOCTL | ECHOKE | IEXTEN;

    tios->c_cc[VINTR] = 003;    // ^C
    tios->c_cc[VQUIT] = 034;    // ^\
    tios->c_cc[VERASE] = 0177;  // DEL
    tios->c_cc[VKILL] = 025;    // ^U
    tios->c_cc[VEOF] = 004;     // ^D
    tios->c_cc[VSTART] = 021;   // ^Q
    tios->c_cc[VSTOP] = 023;    // ^S
    tios->c_cc[VSUSP] = 032;    // ^Z
    tios->c_cc[VREPRINT] = 022; // ^R
    tios->c_cc[VWERASE] = 027;  // ^W
    tios->c_cc[VLNEXT] = 026;   // ^V
    tios->c_cc[VMIN] = 1;
    tios->c_cc[VTIME] = 0;
}

JNIEXPORT jint JNICALL
Java_com_ghostty_android_terminal_PtyNative_spawn(
        JNIEnv *env, jobject thiz,
        jstring command, jobjectArray argv, jobjectArray envp, jstring cwd,
        jint cols, jint rows, jintArray pid_out) {
    (void)thiz;

    const char *command_chars = (*env)->GetStringUTFChars(env, command, NULL);
    char *command_path = strdup(command_chars);
    (*env)->ReleaseStringUTFChars(env, command, command_chars);

    char *cwd_path = NULL;
    if (cwd != NULL) {
        const char *cwd_chars = (*env)->GetStringUTFChars(env, cwd, NULL);
        cwd_path = strdup(cwd_chars);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_chars);
    }

    char **argv_array = to_string_array(env, argv);
    char **envp_array = to_string_array(env, envp);

    if (command_path == NULL || argv_array == NULL || envp_array == NULL) {
        free(command_path);
        free(cwd_path);
        free_string_array(argv_array);
        free_string_array(envp_array);
        throw_io_exception(env, "allocating spawn arguments", ENOMEM);
        return -1;
    }

    struct termios tios;
    sane_termios(&tios);

    struct winsize ws = {
        .ws_col = (unsigned short)cols,
        .ws_row = (unsigned short)rows,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };

    int master = -1;
    pid_t pid = forkpty(&master, NULL, &tios, &ws);

    if (pid < 0) {
        int err = errno;
        free(command_path);
        free(cwd_path);
        free_string_array(argv_array);
        free_string_array(envp_array);
        throw_io_exception(env, "forkpty", err);
        return -1;
    }

    if (pid == 0) {
        // Child. forkpty has already given us a new session with the pty slave
        // as controlling terminal. The JVM runs with signals blocked and
        // handlers installed, neither of which a shell expects to inherit.
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int signo = 1; signo < NSIG; signo++) signal(signo, SIG_DFL);

        if (cwd_path != NULL) {
            if (chdir(cwd_path) != 0) {
                dprintf(STDERR_FILENO, "cannot enter %s: %s\n", cwd_path, strerror(errno));
            }
        }

        execve(command_path, argv_array, envp_array);

        // Only reachable if exec failed. The message goes to the pty, so it
        // lands on the terminal the user is looking at.
        dprintf(STDERR_FILENO, "cannot execute %s: %s\n", command_path, strerror(errno));
        _exit(127);
    }

    // Parent.
    free(command_path);
    free(cwd_path);
    free_string_array(argv_array);
    free_string_array(envp_array);

    fcntl(master, F_SETFD, FD_CLOEXEC);

    jint pid_value = (jint)pid;
    (*env)->SetIntArrayRegion(env, pid_out, 0, 1, &pid_value);
    return master;
}

JNIEXPORT void JNICALL
Java_com_ghostty_android_terminal_PtyNative_setWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint cols, jint rows) {
    (void)env;
    (void)thiz;

    struct winsize ws = {
        .ws_col = (unsigned short)cols,
        .ws_row = (unsigned short)rows,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };

    // The kernel raises SIGWINCH on the foreground process group for us.
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGE("TIOCSWINSZ %dx%d: %s", cols, rows, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_ghostty_android_terminal_PtyNative_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    (void)env;
    (void)thiz;

    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t)pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_ghostty_android_terminal_PtyNative_signal(JNIEnv *env, jobject thiz, jint pid, jint signo) {
    (void)env;
    (void)thiz;

    // Negated so the whole foreground process group is signalled, matching what
    // a terminal driver does.
    kill((pid_t)-pid, signo);
}
