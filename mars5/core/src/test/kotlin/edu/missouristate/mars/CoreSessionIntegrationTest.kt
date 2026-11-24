package edu.missouristate.mars

import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.MemoryState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.useDirectoryEntries
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class CoreSessionIntegrationTest {
  @TempDir lateinit var directory: Path

  @ParameterizedTest
  @MethodSource("corpus")
  fun assemblesExistingCorpusWithoutCompatibilityClasses(source: Path) {
    Assertions.assertThatThrownBy {
          Class.forName("edu.missouristate.mars.Globals")
        }
        .isInstanceOf(ClassNotFoundException::class.java)
    TestSessions.create().use { session ->
      Assertions.assertThat(session.assemble(listOf(source)).instructionCount()).isPositive()
    }
  }

  @Test
  fun independentComponentsAssembleAndRunConcurrently() {
    TestSessions.create().use { first ->
      TestSessions.create().use { second ->
        val a = directory.resolve("a.s")
        val b = directory.resolve("b.s")
        val template: String =
            $$"""
            .data
            value: .word 0
            .text
            li $t0,%d
            la $t1,value
            sw $t0,0($t1)
            li $v0,10
            syscall

            """
                .trimIndent()
        Files.writeString(a, template.format(42))
        Files.writeString(b, template.format(99))
        first.assemble(listOf(a))
        second.assemble(listOf(b))
        val left: CompletableFuture<StopEvent> = first.simulator().runAsync(0, null)
        val right: CompletableFuture<StopEvent> = second.simulator().runAsync(0, null)
        Assertions.assertThat<StopEvent.Reason?>(left.get(5, TimeUnit.SECONDS)!!.reason)
            .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
        Assertions.assertThat<StopEvent.Reason?>(right.get(5, TimeUnit.SECONDS)!!.reason)
            .isEqualTo(StopEvent.Reason.NORMAL_EXIT)
        Assertions.assertThat(first.registerFile.getValue(8)).isEqualTo(42)
        Assertions.assertThat(second.registerFile.getValue(8)).isEqualTo(99)
        Assertions.assertThat(first.memory.getWord(first.memory.dataBaseAddress)).isEqualTo(42)
        Assertions.assertThat(second.memory.getWord(second.memory.dataBaseAddress)).isEqualTo(99)
        Assertions.assertThat<IntegerRegisterFile?>(first.registerFile)
            .isNotSameAs(second.registerFile)
        Assertions.assertThat<MemoryState?>(first.memory).isNotSameAs(second.memory)
      }
    }
  }

  companion object {
    @JvmStatic
    fun corpus(): List<Path> {
      val resources =
          checkNotNull(CoreSessionIntegrationTest::class.java.getResource("/testPrograms")) {
            "testPrograms"
          }
      return Path.of(resources.toURI()).useDirectoryEntries("*.asm") { paths ->
        paths.sorted().toList()
      }
    }
  }
}
