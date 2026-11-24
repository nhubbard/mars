package edu.missouristate.mars.benchmark

import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/** Repeatable assembly throughput measurement, separate from correctness tests. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class AssemblerBenchmark {
  private lateinit var session: MarsContext
  private lateinit var source: Path

  @Setup(Level.Trial)
  fun prepare() {
    session = DaggerMarsComponent.builder().guiProxy(QuietConsole).build().context()
    session.settings
        .setWarningsAreErrors(false)
        .setBareMachineEnabled(false)
        .setExtendedAssemblerEnabled(true)
    source = Files.createTempFile("mars-assembly-benchmark", ".s")
    Files.writeString(source, ".text\n" + $$"addiu $t0,$t0,1\n".repeat(10_000))
  }

  @Benchmark
  fun assembleTenThousandInstructions(): Int = session.assemble(listOf(source)).instructionCount()

  @TearDown(Level.Trial)
  fun release() {
    try {
      session.close()
    } finally {
      Files.deleteIfExists(source)
    }
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
