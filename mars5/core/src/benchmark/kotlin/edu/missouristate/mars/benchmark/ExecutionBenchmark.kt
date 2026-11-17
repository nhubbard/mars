package edu.missouristate.mars.benchmark

import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.simulator.ProgramArgumentList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Measures sustained PNG execution through the CLI or IntelliJ core entry point. */
object ExecutionBenchmark {
    private const val LIMIT = 250_000_000
    private const val EXPECTED_INSTRUCTIONS = 53_035_658L

    @JvmStatic
    fun main(args: Array<String>) {
        val source = Path.of(args[0]).toAbsolutePath()
        val mode = args[1]
        require(mode == "sync" || mode == "async")
        val image = newSession().use { session ->
            session.assemble(listOf(source), handler = null, extended = true, warnings = false)
            session.captureAssemblyImage {}
        }
        println("${System.getProperty("os.arch")}, JDK ${Runtime.version()}, $mode, 1 warmup, 3 measured")
        val samples = List(4) { index ->
            execute(image, source.parent, mode == "async").also { nanos ->
                println(String.format(Locale.ROOT, "%s %.3f s (%d instructions)",
                    if (index == 0) "warmup" else "sample", nanos / 1e9, EXPECTED_INSTRUCTIONS))
            }
        }
        val medianSeconds = samples.drop(1).sorted()[1] / 1e9
        println(String.format(Locale.ROOT, "median %.3f s (%.2f M instructions/s)",
            medianSeconds, EXPECTED_INSTRUCTIONS / medianSeconds / 1e6))
    }

    private fun execute(image: AssemblyImage, directory: Path, asynchronous: Boolean): Long = newSession().use { session ->
        session.loadAssemblyImage(image, true) {}
        ProgramArgumentList(listOf("torture.png")).storeProgramArguments(session)
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { output ->
            SessionSyscallEnvironment(session.memory, ByteArrayInputStream(byteArrayOf()), output, output, directory).use { io ->
                val program = checkNotNull(session.program)
                program.setSyscallEnvironment(io)
                var steps = 0L
                val elapsed = if (asynchronous) {
                    val simulator = session.simulator()
                    simulator.addListener(object : SimulatorListener {
                        override fun onStep(event: StepEvent) {
                            steps = event.stepCount
                        }

                        override fun onStop(event: StopEvent) = Unit

                        override fun onError(event: ErrorEvent) = Unit
                    })
                    val start = System.nanoTime()
                    val stop = simulator.runAsync(LIMIT.toLong(), null).get(2, TimeUnit.MINUTES)
                    val elapsed = System.nanoTime() - start
                    check(stop.reason == StopEvent.Reason.NORMAL_EXIT && stop.exitCode == 0) { stop }
                    elapsed
                } else {
                    val start = System.nanoTime()
                    val finished = program.simulate(LIMIT) { steps = it }
                    val elapsed = System.nanoTime() - start
                    check(finished && program.exitCode == 0)
                    elapsed
                }
                val console = bytes.toString(Charsets.UTF_8)
                check(console.lineSequence().any { it == "PNG displayed." }) { console }
                check(steps == EXPECTED_INSTRUCTIONS) { "Expected $EXPECTED_INSTRUCTIONS instructions, got $steps" }
                elapsed
            }
        }
    }

    private fun newSession(): MarsContext = DaggerMarsComponent.builder()
        .coreModule(CoreModule(Settings()))
        .guiProxy(QuietConsole)
        .build()
        .context().also {
            it.settings.setMemoryConfiguration("Default").setSelfModifyingCodeEnabled(false)
        }

    private object QuietConsole : MarsGuiProxy {
        override val isGuiMode = false
        override fun postMessage(message: String) = Unit
        override fun postRunMessage(message: String) = Unit
        override fun readInput(maxLength: Int) = ""
        override fun readInputWithPrompt(prompt: String) = ""
        override fun clearRunConsole() = Unit
    }
}
