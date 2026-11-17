package edu.missouristate.mars.benchmark

import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Run once per JVM to observe cold startup, then compare against repeated cached-image launches. */
object SessionStartupBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = Path.of(args.first()).toAbsolutePath()
        val warmup = args.getOrNull(1)?.toInt() ?: 30
        val iterations = args.getOrNull(2)?.toInt() ?: 80
        require(warmup >= 0 && iterations > 0)

        println("${System.getProperty("os.arch")}, JDK ${Runtime.version()}, $warmup warmup, $iterations measured")
        val start = System.nanoTime()
        val session = createSession()
        val constructed = System.nanoTime()
        // Prepare the image outside the timed phases. On a cold JVM, the load and step
        // therefore follow assembly, as they do on the first launch of an uncached source.
        val image = session.use {
            it.selectTarget(MarsTarget.MARS64)
            it.assemble(listOf(source))
            it.captureAssemblyImage {}
        }
        val coldLaunch = launch(image).copy(construction = constructed - start)
        printTimings("cold", listOf(coldLaunch))
        repeat(warmup) { launch(image) }
        printTimings("warm", List(iterations) { launch(image) })
    }

    private fun createSession(): MarsContext = DaggerMarsComponent.builder()
        .coreModule(CoreModule(Settings()))
        .guiProxy(QuietConsole)
        .build()
        .context()

    private fun launch(image: AssemblyImage): Timings {
        val start = System.nanoTime()
        return createSession().use { session ->
            val constructed = System.nanoTime()
            session.loadAssemblyImage(image, true) {}
            val loaded = System.nanoTime()
            val simulator = session.simulator()
            var firstInstruction = 0L
            simulator.addListener(object : SimulatorListener {
                override fun onStep(event: StepEvent) {
                    firstInstruction = System.nanoTime()
                }

                override fun onStop(event: StopEvent) = Unit

                override fun onError(event: ErrorEvent) = Unit
            })
            simulator.runAsync(1, null).get(30, TimeUnit.SECONDS)
            check(firstInstruction != 0L) { "The first instruction did not execute" }
            Timings(constructed - start, loaded - constructed, firstInstruction - loaded)
        }
    }

    private fun printTimings(label: String, samples: List<Timings>) {
        fun percentile(values: List<Long>, fraction: Double): Double =
            values.sorted()[ceil(values.size * fraction).toInt() - 1] / 1_000_000.0

        println("$label (p50 / p95 ms)")
        for ((name, read) in listOf(
            "session construction" to Timings::construction,
            "cached image load" to Timings::load,
            "first instruction" to Timings::step,
            "total of timed phases" to Timings::total,
        )) {
            val values = samples.map(read)
            println(String.format(Locale.ROOT, "  %-22s %8.3f / %8.3f", name,
                percentile(values, 0.50), percentile(values, 0.95)))
        }
    }

    private data class Timings(val construction: Long, val load: Long, val step: Long) {
        val total: Long get() = construction + load + step
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
