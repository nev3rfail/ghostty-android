package com.ghostty.android.terminal

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A process attached to a pseudoterminal.
 *
 * Interactive programs behave differently when their output is a tty: they ask
 * the kernel with `isatty`, drive the terminal in raw mode, and resize
 * themselves when the kernel sends `SIGWINCH`. A pipe supports none of that, so
 * a terminal emulator needs the process on a pty.
 *
 * [input] and [output] are two views of the same file descriptor. Closing
 * either closes the pty; use [close] instead.
 */
class Pty private constructor(
    private val descriptor: ParcelFileDescriptor,

    /** Process id of the child, and of its process group. */
    val pid: Int,
) : Closeable {

    /** Everything the process writes to its terminal. */
    val input: InputStream = FileInputStream(descriptor.fileDescriptor)

    /** Everything typed at the process. */
    val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    /**
     * Tell the process how large its terminal is. The kernel raises `SIGWINCH`
     * on the foreground process group in response.
     */
    fun resize(cols: Int, rows: Int) = PtyNative.setWindowSize(descriptor.fd, cols, rows)

    /** Blocks until the process exits and returns its exit status. */
    fun waitFor(): Int = PtyNative.waitFor(pid)

    /** Send a signal to the process group. */
    fun signal(signal: Int) = PtyNative.signal(pid, signal)

    /**
     * Hang up the terminal and release the file descriptor. A process that
     * ignores `SIGHUP` also sees its terminal reach end of file.
     */
    override fun close() {
        signal(SIGHUP)
        descriptor.close()
    }

    companion object {
        const val SIGHUP = 1
        const val SIGINT = 2
        const val SIGKILL = 9
        const val SIGTERM = 15

        /**
         * Start [command] on a new pseudoterminal.
         *
         * @param command Absolute path to the executable
         * @param argv Argument vector, including argv[0]
         * @param environment Complete environment; the process inherits nothing else
         * @param cwd Working directory, or null to keep the caller's
         * @param cols Initial terminal width in cells
         * @param rows Initial terminal height in cells
         */
        fun spawn(
            command: String,
            argv: List<String> = listOf(command),
            environment: Map<String, String> = emptyMap(),
            cwd: String? = null,
            cols: Int = 80,
            rows: Int = 24,
        ): Pty {
            val pidHolder = IntArray(1)
            val fd = PtyNative.spawn(
                command,
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                cols,
                rows,
                pidHolder,
            )
            return Pty(ParcelFileDescriptor.adoptFd(fd), pidHolder[0])
        }
    }
}

/**
 * The pty system calls. Kept in its own object because externals declared in a
 * companion resolve against the companion class, not the enclosing one.
 */
internal object PtyNative {
    init {
        System.loadLibrary("pty")
    }

    external fun spawn(
        command: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        cols: Int,
        rows: Int,
        pidOut: IntArray,
    ): Int

    external fun setWindowSize(fd: Int, cols: Int, rows: Int)

    external fun waitFor(pid: Int): Int

    external fun signal(pid: Int, signo: Int)
}
