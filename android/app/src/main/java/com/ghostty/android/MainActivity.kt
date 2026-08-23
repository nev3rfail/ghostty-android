package com.ghostty.android

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostty.android.renderer.TerminalEventListener
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import com.ghostty.android.terminal.GhosttyBridge
import com.ghostty.android.terminal.TerminalSession
import com.ghostty.android.testing.TestRunner
import com.ghostty.android.testing.TestSuite
import com.ghostty.android.ui.InputToolbar
import com.ghostty.android.ui.theme.GhosttyTheme

class MainActivity : ComponentActivity() {

    private lateinit var terminalSession: TerminalSession
    private var glSurfaceView: GhosttyGLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while terminal is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        terminalSession = agentSession()

        // A test id switches the app into the visual regression harness; without
        // one it is a terminal.
        val testId = intent.getStringExtra("TEST_ID")

        enableEdgeToEdge()

        setContent {
            GhosttyTheme {
                if (testId != null) {
                    val testRunnerState = remember { mutableStateOf<TestRunner?>(null) }
                    TestModeScreen(
                        testRunner = testRunnerState.value,
                        onExitTestMode = {},
                        onGLSurfaceViewCreated = { view ->
                            glSurfaceView = view
                            if (testRunnerState.value == null) {
                                testRunnerState.value = TestRunner(view.getRenderer(), applicationContext)
                            }
                        },
                        testId = testId,
                    )
                } else {
                    TerminalScreen(
                        session = terminalSession,
                        onGLSurfaceViewCreated = { view -> glSurfaceView = view },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause the GL rendering thread
        glSurfaceView?.onPauseView()
    }

    override fun onResume() {
        super.onResume()
        // Resume the GL rendering thread
        glSurfaceView?.onResumeView()
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession.stop()
    }

    /**
     * A session on the staged agent, or on a shell while there is nothing
     * staged to run.
     *
     * The agent is a Linux binary. What it needs from the platform is assembled
     * here: a home directory it may write, a resolver it can read, and on
     * x86_64 a tracer that rewrites the syscalls Android refuses.
     */
    private fun agentSession(): TerminalSession {
        val environment = TerminalSession.defaultEnvironment(
            home = filesDir.absolutePath,
            tmp = cacheDir.absolutePath,
        ).toMutableMap()

        val staged = File(filesDir, STAGE_DIRECTORY)
        val agent = File(staged, "claude")
        if (!agent.canExecute()) {
            return TerminalSession(
                environment = environment,
                cwd = filesDir.absolutePath,
            )
        }

        // Android answers a name lookup through netd rather than through a
        // nameserver in /etc/resolv.conf, so a program carrying its own
        // resolver has nothing to read. The tracer redirects /etc at this
        // directory; the file it will find there is written here.
        val etc = File(staged, "etc")
        etc.mkdirs()
        val resolvConf = File(etc, "resolv.conf")
        if (!resolvConf.exists()) {
            resolvConf.writeText(PUBLIC_RESOLVERS)
        }
        environment["SYSCALL_SHIM_ETC"] = etc.absolutePath

        // The aarch64 Linux ABI has only the *at syscalls, which is exactly
        // what Android's seccomp policy allows, so nothing needs rewriting
        // there. x86_64 still offers the legacy calls and the agent's runtime
        // uses them.
        val shim = File(applicationInfo.nativeLibraryDir, "libsyscallshim.so")
        val needsShim = Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
        val command = if (needsShim && shim.canExecute()) shim else agent
        val argv = if (command == shim) {
            listOf(shim.absolutePath, agent.absolutePath)
        } else {
            listOf(agent.absolutePath)
        }

        return TerminalSession(
            command = command.absolutePath,
            argv = argv,
            environment = environment,
            cwd = filesDir.absolutePath,
        )
    }

    private companion object {
        const val STAGE_DIRECTORY = "claude"
        const val PUBLIC_RESOLVERS = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
    }
}

/**
 * A terminal driven by [session].
 *
 * The renderer decides how many cells fit on screen, so the session is started
 * from [TerminalEventListener.onSurfaceReady] with the grid size it reports and
 * resized whenever that changes. Keystrokes arrive already encoded through
 * [TerminalEventListener.onInput].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    session: TerminalSession,
    onGLSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit,
) {
    var surfaceView by remember { mutableStateOf<GhosttyGLSurfaceView?>(null) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    // Without this the soft keyboard covers the toolbar.
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            InputToolbar(
                onKeyPress = { session.write(it) },
                onShowKeyboard = { surfaceView?.showKeyboard() },
                onToggleCtrl = {
                    ctrlActive = !ctrlActive
                    surfaceView?.ctrlPending = ctrlActive
                },
                onToggleAlt = {
                    altActive = !altActive
                    surfaceView?.altPending = altActive
                },
                ctrlActive = ctrlActive,
                altActive = altActive,
            )
        }
    ) { paddingValues ->
        AndroidView(
            factory = { context ->
                GhosttyGLSurfaceView(context).also { view ->
                    surfaceView = view
                    onGLSurfaceViewCreated(view)

                    view.onModifiersConsumed = {
                        ctrlActive = false
                        altActive = false
                    }

                    view.setEventListener(object : TerminalEventListener {
                        override fun onSurfaceReady(cols: Int, rows: Int) {
                            if (session.isRunning.value) {
                                session.resize(cols, rows)
                                return
                            }
                            session.start(
                                cols = cols,
                                rows = rows,
                                // Called on the reader thread. The terminal takes
                                // its own lock and the surface redraws
                                // continuously, so the bytes go straight in.
                                onOutput = { bytes, length ->
                                    view.getRenderer().processInput(bytes, length)
                                },
                            )
                        }

                        override fun onInput(bytes: ByteArray) = session.write(bytes)

                        override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {}

                        override fun onKeyboardOverlayStateChanged(expanded: Boolean) {}
                    })
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        )
    }

    LaunchedEffect(surfaceView) {
        surfaceView?.showKeyboard()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestModeScreen(
    testRunner: TestRunner?,
    onExitTestMode: () -> Unit,
    onGLSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit,
    testId: String?,
    onTestsStarted: () -> Unit = {}
) {
    android.util.Log.i("TestModeScreen", "Compose: testRunner=$testRunner, testId=$testId")

    val isRunning by testRunner?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
    val testResults by testRunner?.testResults?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val currentTest by testRunner?.currentTest?.collectAsState() ?: remember { mutableStateOf(null) }
    val currentTestIndex by testRunner?.currentTestIndexFlow?.collectAsState() ?: remember { mutableStateOf(0) }
    val totalTests by testRunner?.totalTests?.collectAsState() ?: remember { mutableStateOf(0) }

    // Initialize tests when both testRunner and testId are available
    if (testRunner != null && testId != null) {
        LaunchedEffect(testId, testRunner) {
            android.util.Log.i("TestModeScreen", "LaunchedEffect: initializing tests testId=$testId")
            testRunner.initializeTestById(testId)
            onTestsStarted()
        }
    } else {
        android.util.Log.w("TestModeScreen", "Waiting for initialization: testRunner=$testRunner, testId=$testId")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visual Regression Tests") },
                actions = {
                    IconButton(onClick = onExitTestMode, enabled = !isRunning) {
                        Text("EXIT", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Test progress indicator
                    if (totalTests > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Test ${currentTestIndex + 1} of $totalTests",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }

                    // Navigation buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { testRunner?.previousTest() },
                            enabled = !isRunning && testRunner?.hasPreviousTest() == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Previous")
                        }
                        Button(
                            onClick = { testRunner?.nextTest() },
                            enabled = !isRunning && testRunner?.hasNextTest() == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Terminal view (takes most of the space)
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        GhosttyGLSurfaceView(context).also { view ->
                            onGLSurfaceViewCreated(view)
                            view.setTerminalSize(80, 24)

                            // Configure bottom offset for keyboard gesture testing
                            view.setMaxBottomOffset(300f)
                            view.setEventListener(object : TerminalEventListener {
                                override fun onSurfaceReady(cols: Int, rows: Int) {
                                    android.util.Log.i("Terminal", "Surface ready: ${cols}x${rows}")
                                }
                                override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {
                                    android.util.Log.d("KeyboardOverlay", "progress=$offset, max=$maxOffset")
                                }
                                override fun onKeyboardOverlayStateChanged(expanded: Boolean) {
                                    android.util.Log.i("KeyboardOverlay", "expanded=$expanded")
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay showing current test
                currentTest?.let { test ->
                    Surface(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Running: ${test.id}",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Test results summary
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Results: ${testResults.size} tests",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (testResults.isNotEmpty()) {
                        val passed = testResults.count { it.status == com.ghostty.android.testing.TestStatus.PASSED }
                        val failed = testResults.count { it.status == com.ghostty.android.testing.TestStatus.FAILED }
                        Text(
                            text = "Passed: $passed, Failed: $failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed > 0) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
