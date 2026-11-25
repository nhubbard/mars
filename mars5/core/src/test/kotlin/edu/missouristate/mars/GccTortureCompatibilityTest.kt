package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.MemoryState
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GccTortureCompatibilityTest {
  @ParameterizedTest
  @MethodSource("programs")
  fun `freestanding GCC torture cases pass`(
      name: String,
      windowsLineEndings: Boolean,
      @TempDir directory: Path,
  ) {
    val source = directory.resolve("$name.s")
    val assembly =
        checkNotNull(javaClass.getResourceAsStream("/gcc-torture/$name.s")).bufferedReader().use {
          it.readText().replace("\r\n", "\n")
        }
    val checkedOutAssembly = if (windowsLineEndings) assembly.replace("\n", "\r\n") else assembly
    // Drop the ELF debug-section switch and allocate the one file-local BSS object.
    Files.writeString(
        source,
        checkedOutAssembly
            .replace("\r\n", "\n")
            .replace("\t.section .mdebug.abi64\n\t.previous\n", "")
            .replace(
                "\t.local\ttest\n\t.comm\ttest,4,4\n",
                "\t.data\n\t.align 2\ntest: .space 4\n\t.text\n",
            ),
    )
    val runtime =
        Files.writeString(
            directory.resolve("runtime.mars"),
            $$"""
            .text
            jal mars_gcc_main
            nop
            or $s0,$v0,$zero
            ori $v0,$zero,10
            syscall
            .globl mars_gcc_exit
            mars_gcc_exit:
            or $s0,$a0,$zero
            ori $v0,$zero,10
            syscall
            .globl mars_gcc_abort
            mars_gcc_abort:
            daddiu $s0,$zero,-1
            ori $v0,$zero,10
            syscall
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      machine.memory.byteOrder = MemoryState.BIG_ENDIAN
      val program =
          try {
            machine.assemble(listOf(runtime, source))
          } catch (failure: ProcessingException) {
            throw AssertionError(failure.errors()?.generateErrorAndWarningReport(), failure)
          }
      val stack = machine.registerFile.getLongValueNoNotify(29) and -16L
      machine.editIntegerRegister(29, stack)
      machine.editIntegerRegister(16, -2)
      assertTrue(program.simulate(4096), "The GCC case must terminate")
      assertEquals(
          0L,
          machine.registerFile.getLongValueNoNotify(16),
          "The GCC case must return or exit with status zero",
      )
    }
  }

  companion object {
    @JvmStatic
    fun programs(): List<Arguments> =
        checkNotNull(javaClass.getResourceAsStream("/gcc-torture/sources.tsv"))
            .bufferedReader()
            .useLines { lines ->
              lines
                  .filterNot { it.startsWith('#') }
                  .flatMap { line ->
                    val name = line.substringBefore('\t').removeSuffix(".c")
                    sequenceOf("$name-O0", "$name-O2").flatMap { variant ->
                      sequenceOf(Arguments.of(variant, false), Arguments.of(variant, true))
                    }
                  }
                  .toList()
            }
  }
}
