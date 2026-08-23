package com.ghostty.android.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * A shell running on a pseudoterminal, with its output delivered as bytes.
 *
 * @param command Absolute path to the executable to run
 * @param argv Argument vector, including argv[0]. A leading dash asks a shell to
 *   behave as a login shell.
 * @param environment Complete environment for the process
 * @param cwd Working directory, or null to keep the caller's
 */
class TerminalSession(
    private val command: String = "/system/bin/sh",
    private val argv: List<String> = listOf("-sh"),
    private val environment: Map<String, String> = emptyMap(),
    private val cwd: String? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var pty: Pty? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start the process and stream its output.
     *
     * @param onOutput Receives each read from the terminal, on a background
     *   thread. The buffer is reused for every read, so it must be consumed
     *   before this returns rather than retained.
     * @param onExit Receives the exit status once the process is reaped.
     */
    fun start(
        cols: Int,
        rows: Int,
        onOutput: (ByteArray, Int) -> Unit,
        onExit: (Int) -> Unit = {},
    ) {
        if (_isRunning.value) {
            Log.w(TAG, "Session already running")
            return
        }

        val started = try {
            Pty.spawn(command, argv, environment, cwd, cols, rows)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start $command", e)
            return
        }

        pty = started
        _isRunning.value = true
        Log.d(TAG, "Started $command on pty, pid=${started.pid}, ${cols}x$rows")

        scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                // A pty master reports EIO rather than end of file once the
                // child is gone, so a read failure here is a normal exit.
                val count = try {
                    started.input.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (count <= 0) break
                onOutput(buffer, count)
            }

            val status = started.waitFor()
            _isRunning.value = false
            Log.d(TAG, "Process ${started.pid} exited with status $status")
            onExit(status)
        }
    }

    /**
     * Send bytes to the process. Keystrokes are small enough that writing on the
     * calling thread will not block; synchronization keeps them in order when
     * they arrive from more than one thread.
     */
    @Synchronized
    fun write(bytes: ByteArray, length: Int = bytes.size) {
        val target = pty ?: return
        try {
            target.output.write(bytes, 0, length)
            target.output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to terminal", e)
        }
    }

    /** Send text to the process, encoded as UTF-8. */
    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** Tell the process its terminal has been resized. */
    fun resize(cols: Int, rows: Int) {
        pty?.resize(cols, rows)
    }

    /** Hang up the terminal, ending the process. */
    fun stop() {
        pty?.close()
        pty = null
        _isRunning.value = false
    }

    companion object {
        private const val TAG = "TerminalSession"
        private const val READ_BUFFER_SIZE = 8192

        /**
         * The environment a shell needs on Android. The process inherits nothing
         * that is not listed here.
         *
         * @param home Directory the shell starts in and treats as $HOME
         * @param tmp Directory for temporary files, since /tmp does not exist
         */
        fun defaultEnvironment(home: String, tmp: String): Map<String, String> = mapOf(
            "TERM" to "xterm-256color",
            "HOME" to home,
            "TMPDIR" to tmp,
            "PATH" to "/system/bin:/system/xbin",
            "LANG" to "C.UTF-8",
        )
    }
}
