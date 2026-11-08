package edu.missouristate.mars.benchmark;

import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.DaggerMarsComponent;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Repeatable assembly throughput measurement, separate from correctness tests. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AssemblerBenchmark {
  private MarsContext session;
  private Path source;

  @Setup(Level.Trial)
  public void prepare() throws Exception {
    session =
        DaggerMarsComponent.builder()
            .guiProxy(
                new MarsGuiProxy() {
                  public boolean isGuiMode() {
                    return false;
                  }

                  public void postMessage(String value) {}

                  public void postRunMessage(String value) {}

                  public String readInput(int length) {
                    return "";
                  }

                  public String readInputWithPrompt(String prompt) {
                    return "";
                  }

                  public void clearRunConsole() {}
                })
            .build()
            .context();
    session
        .settings
        .setWarningsAreErrors(false)
        .setBareMachineEnabled(false)
        .setExtendedAssemblerEnabled(true);
    source = Files.createTempFile("mars-assembly-benchmark", ".s");
    Files.writeString(source, ".text\n" + "addiu $t0,$t0,1\n".repeat(10_000));
  }

  @Benchmark
  public int assembleTenThousandInstructions() throws Exception {
    return session.assemble(List.of(source)).instructionCount();
  }

  @TearDown(Level.Trial)
  public void release() throws Exception {
    session.close();
    Files.deleteIfExists(source);
  }
}
