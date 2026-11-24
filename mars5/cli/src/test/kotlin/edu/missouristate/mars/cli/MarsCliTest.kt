package edu.missouristate.mars.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class MarsCliTest {
  @TempDir lateinit var directory: Path

  @Test
  fun runsWithInputAndArgumentsWithoutLegacyClasses() {
    assertThrows<ClassNotFoundException> { Class.forName("edu.missouristate.mars.Globals") }
    val file = directory.resolve("input.s")
    Files.writeString(
        file,
        $$"""
        .text
        move $s0,$a0
        li $v0,5
        syscall
        move $a0,$v0
        li $v0,1
        syscall
        move $a0,$s0
        li $v0,17
        syscall

        """
            .trimIndent(),
    )
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val code: Int =
        MarsCli.execute(
            arrayOf(file.toString(), "--", "one", "two"),
            ByteArrayInputStream("42\n".toByteArray()),
            PrintStream(output),
            PrintStream(error),
        )
    assertEquals(2, code)
    assertEquals("42", output.toString())
    assertEquals("", error.toString())
  }

  @Test
  fun reportsAssemblyErrorsLimitsAndHelp() {
    val file = directory.resolve("program.s")
    val error = ByteArrayOutputStream()
    val output = ByteArrayOutputStream()
    Files.writeString(file, ".text\nloop: bc loop\n")
    assertEquals(3, execute(arrayOf("--max-steps", "4", file.toString()), output, error))
    assertTrue(error.toString().contains("step limit"))
    assertEquals(0, execute(arrayOf("--assemble-only", file.toString()), output, error))
    Files.writeString(file, $$".text\ninvalid $t0\n")
    assertEquals(1, execute(arrayOf(file.toString()), output, error))
    assertTrue(error.toString().contains("invalid"))
    assertEquals(0, execute(arrayOf("--help"), output, error))
    assertTrue(output.toString().contains("Usage:"))
    assertEquals(2, execute(arrayOf("--missing"), output, error))
  }

  @ParameterizedTest
  @ValueSource(strings = ["p", "--project"])
  fun assemblesSiblingsAndExtraSourcesWithTheFirstFileAsEntry(projectFlag: String) {
    val main = directory.resolve("z-entry.s")
    val sibling = directory.resolve("a-library.asm")
    val outside = Files.createDirectory(directory.resolve("nested")).resolve("extra.txt")
    Files.writeString(main, $$".text\nbalc library\nli $v0,17\nsyscall\n")
    Files.writeString(sibling, ".text\n.globl library\nlibrary: bc extra\n")
    Files.writeString(outside, $$".text\n.globl extra\nextra: li $a0,42\njrc $ra\n")
    Files.writeString(directory.resolve("ignored.txt"), "invalid\n")
    Files.writeString(outside.resolveSibling("ignored.s"), "invalid\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(1, execute(arrayOf(main.toString(), outside.toString()), output, error))
    assertTrue(error.toString().contains("library"))
    error.reset()
    assertEquals(
        42,
        execute(
            arrayOf(projectFlag, main.toString(), sibling.toString(), outside.toString()),
            output,
            error,
        ),
    )
    assertEquals("", error.toString())
  }

  @Test
  fun appliesErrorStatusesOnlyToTheirExecutionPhaseAndKeepsProgramExitAndStepLimit() {
    val file = directory.resolve("status.s")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    Files.writeString(file, "invalid\n")
    assertEquals(19, execute(arrayOf("AE0x13", "se23", file.toString()), output, error))
    assertTrue(error.toString().contains("invalid"))
    assertEquals(
        19,
        execute(arrayOf("--assembly-error-exit-code", "19", file.toString()), output, error),
    )
    error.reset()

    Files.writeString(file, $$"li $v0,999\nsyscall\n")
    assertEquals(
        23,
        execute(
            arrayOf("ae19", "--simulation-error-exit-code", "027", file.toString()),
            output,
            error,
        ),
    )
    assertTrue(error.toString().contains("syscall"))
    error.reset()
    assertEquals(0, execute(arrayOf("a", "se23", file.toString()), output, error))

    Files.writeString(file, $$"li $a0,37\nli $v0,17\nsyscall\n")
    assertEquals(37, execute(arrayOf("ae19", "se23", "-1", file.toString()), output, error))
    assertEquals(3, execute(arrayOf("ae19", "se23", "0x1", file.toString()), output, error))
    assertTrue(error.toString().contains("step limit"))
    error.reset()
    assertEquals(2, execute(arrayOf("--assembly-error-exit-code"), output, error))
    assertTrue(error.toString().contains("Missing assembly error exit code"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["smc", "--self-modifying-code"])
  fun selfModifyingCodeIsEnabledOnlyForTheRequestedInvocation(flag: String) {
    val file = directory.resolve("patch.s")
    Files.writeString(
        file,
        $$"""
        la $t0, patch
        li $t1, 0x2404002a
        sw $t1, 0($t0)
        patch: nop
        li $v0,17
        syscall

        """
            .trimIndent(),
    )
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(42, execute(arrayOf(flag, file.toString()), output, error))
    assertEquals("", error.toString())
    assertEquals(1, execute(arrayOf(file.toString()), output, error))
    assertTrue(error.toString().contains("text segment"))
  }

  @Test
  fun dumpsSegmentsAndInclusiveRangesFromAssemblyAndFinishedExecution() {
    val source = directory.resolve("dump.s")
    val assembled = directory.resolve("assembled.txt")
    val ran = directory.resolve("ran.txt")
    val text = directory.resolve("text.txt")
    Files.writeString(assembled, "old contents\n")
    Files.writeString(
        source,
        $$"""
        .data
        item: .word 11, 22
        .text
        li $t0,42
        sw $t0,item
        li $v0,10
        syscall

        """
            .trimIndent(),
    )
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(
        0,
        execute(
            arrayOf(
                "a",
                "dump",
                "0x10010000-0x10010004",
                "HexText",
                assembled.toString(),
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals(listOf("0000000b", "00000016"), Files.readAllLines(assembled))
    assertEquals(
        0,
        execute(
            arrayOf(
                "--dump",
                "0x10010000-0x10010000",
                "HexText",
                ran.toString(),
                "dump",
                ".text",
                "SegmentWindow",
                text.toString(),
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals(listOf("0000002a"), Files.readAllLines(ran))
    assertTrue(Files.readString(text).contains($$"sw $t0,item"))
    assertEquals("", output.toString())
    assertEquals("", error.toString())
  }

  @Test
  fun dumpFailuresLeaveTheExistingDestinationIntactAndAllowOtherDumps() {
    val source = directory.resolve("empty.s")
    val destination = directory.resolve("existing.txt")
    val text = directory.resolve("text.txt")
    Files.writeString(source, "nop\n")
    Files.writeString(destination, "keep this file\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(
        0,
        execute(
            arrayOf(
                "a",
                "dump",
                ".data",
                "HexText",
                destination.toString(),
                "dump",
                ".text",
                "HexText",
                directory.resolve("missing/out.txt").toString(),
                "dump",
                ".text",
                "HexText",
                text.toString(),
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals("keep this file\n", Files.readString(destination))
    assertEquals(listOf("00000000"), Files.readAllLines(text))
    assertTrue(error.toString().contains("no initialized words"))
    assertTrue(error.toString().contains("missing"))
    error.reset()

    assertEquals(2, execute(arrayOf("dump", ".text", "HexText"), output, error))
    assertTrue(error.toString().contains("Missing dump filename"))
    error.reset()
    assertEquals(
        2,
        execute(
            arrayOf("dump", ".text", "Bogus", destination.toString(), source.toString()),
            output,
            error,
        ),
    )
    assertTrue(error.toString().contains("Unknown memory dump format"))
    error.reset()
    assertEquals(
        2,
        execute(
            arrayOf(
                "dump",
                "0x10010001-0x10010004",
                "HexText",
                destination.toString(),
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertTrue(error.toString().contains("Invalid or unaligned memory range"))
    assertEquals("keep this file\n", Files.readString(destination))
  }

  @Test
  fun dumpsKernelMemoryOnSimulationErrorAndSkipsExportOnAssemblyError() {
    val source = directory.resolve("kernel.s")
    val kernel = directory.resolve("kernel.hex")
    val window = directory.resolve("kernel-window.txt")
    Files.writeString(source, $$".kdata\n.word 0xabcd\n.text\nli $v0,999\nsyscall\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val arguments =
        arrayOf(
            "ae19",
            "se23",
            "dump",
            "0x90000000-0x90000000",
            "HexText",
            kernel.toString(),
            "dump",
            "0x90000000-0x90000000",
            "SegmentWindow",
            window.toString(),
            source.toString(),
        )

    assertEquals(23, execute(arguments, output, error))
    assertEquals(listOf("0000abcd"), Files.readAllLines(kernel))
    assertFalse(Files.exists(window))
    assertTrue(
        error.toString().contains("SegmentWindow requires a range within user text or user data"),
    )
    assertTrue(error.toString().contains("syscall"))
    Files.delete(kernel)
    Files.writeString(source, "invalid\n")
    error.reset()

    assertEquals(19, execute(arguments, output, error))
    assertTrue(error.toString().contains("invalid"))
    assertFalse(Files.exists(kernel))
    assertFalse(Files.exists(window))
  }

  @Test
  fun reportsIntegerAndFloatingPointRegistersAndMemoryInHexadecimal() {
    val source = directory.resolve("report.s")
    Files.writeString(
        source,
        $$"""
        .data
        .word 1, 2, 3, 4, 5
        .text
        li $t0,-7
        li $t1,0x3ff00000
        mtc1 $t1,$f1
        li $v0,10
        syscall

        """
            .trimIndent(),
    )
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(
        0,
        execute(
            arrayOf(
                "hex",
                "--register",
                "t0",
                "$8",
                "f0",
                $$"$f1",
                "0x10010000-0x10010010",
                "--memory-range",
                "0x00400000-0x00400000",
                "--memory-range",
                "0x00400100-0x00400100",
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals(
        listOf(
            "",
            $$"$t0\t0xfffffffffffffff9",
            "$8\t0xfffffffffffffff9",
            $$"$f0\t0x00000000\t0x0000000000000000",
            $$"$f1\t0x3ff00000\t0x000000003ff00000",
            "Mem[0x10010000]\t0x00000001\t0x00000002\t0x00000003\t0x00000004\t",
            "Mem[0x10010010]\t0x00000005\t",
            "Mem[0x00400000]\t0x2408fff9\t",
            "Mem[0x00400100]\t0x00000000\t",
        ),
        output.toString().reader().readLines(),
    )
    assertEquals("", error.toString())
  }

  @Test
  fun sendsBriefDecimalAndAsciiReportsToStderrWhileProgramOutputStaysOnStdout() {
    val source = directory.resolve("brief.s")
    Files.writeString(
        source,
        $$"""
        .data
        .word 0x41424344
        .text
        li $t0,0x41424344
        dsll32 $t1,$t0,0
        or $t1,$t1,$t0
        dmtc1 $t1,$f0
        li $a0,7
        li $v0,1
        syscall
        li $v0,10
        syscall

        """
            .trimIndent(),
    )
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    assertEquals(
        0,
        execute(arrayOf("dec", "b", "me", $$"$a0", $$"$f0", source.toString()), output, error),
    )
    assertEquals("7", output.toString())
    assertEquals(
        listOf("", "7", "12.141422\t2393736.509834679"),
        error.toString().reader().readLines(),
    )
    output.reset()
    error.reset()

    assertEquals(
        0,
        execute(
            arrayOf(
                "ascii",
                "--brief",
                "--messages-to-stderr",
                "t0",
                "f0",
                "0x10010000-0x10010000",
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals("7", output.toString())
    assertEquals(
        listOf(
            "",
            " \\0 \\0 \\0 \\0  A  B  C  D",
            "  A  B  C  D\t  A  B  C  D  A  B  C  D",
            "  A  B  C  D\t",
        ),
        error.toString().reader().readLines(),
    )
  }

  @Test
  fun reportsKernelMemoryAndInvalidAddressesAndRejectsMalformedSelections() {
    val source = directory.resolve("ranges.s")
    Files.writeString(source, ".kdata\n.word 34\n.text\nnop\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(
        0,
        execute(
            arrayOf(
                "dec",
                "0x90000000-0x90000000",
                "0x00000000-0x00000000",
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals(
        listOf("", "Mem[0x90000000]\t34\t", "Mem[0x00000000]\tInvalid address: 0\t"),
        output.toString().reader().readLines(),
    )
    assertEquals("", error.toString())
    output.reset()

    assertEquals(
        0,
        execute(
            arrayOf("a", $$"$t0", "0x90000000-0x90000000", source.toString()),
            output,
            error,
        ),
    )
    assertEquals("", output.toString())
    assertEquals(2, execute(arrayOf($$"$f", source.toString()), output, error))
    assertTrue(error.toString().contains("Invalid register name"))
    error.reset()
    assertEquals(2, execute(arrayOf("0x90000000-0x8ffffffc", source.toString()), output, error))
    assertTrue(error.toString().contains("Invalid or unaligned memory range"))
  }

  @Test
  fun countsRepeatedInstructionsAtAddressZeroUpToTheStepLimit() {
    val source = directory.resolve("loop.s")
    Files.writeString(source, ".text\nloop: bc loop\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(
        3,
        execute(
            arrayOf(
                "ic",
                "mc",
                "CompactTextAtZero",
                "--max-steps",
                "4",
                source.toString(),
            ),
            output,
            error,
        ),
    )
    assertEquals(listOf("", "4", ""), output.toString().reader().readLines())
    assertTrue(error.toString().contains("step limit"))
  }

  @Test
  fun countsNormalExitButSkipsTheEmptySlotAndFailingInstructions() {
    val source = directory.resolve("count.s")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val arguments = arrayOf("--instruction-count", "se23", source.toString())

    Files.writeString(source, $$"li $v0,10\nsyscall\n")
    assertEquals(0, execute(arguments, output, error))
    assertEquals(listOf("", "2", ""), output.toString().reader().readLines())
    output.reset()

    Files.writeString(source, "nop\n")
    assertEquals(0, execute(arguments, output, error))
    assertEquals(listOf("", "1", ""), output.toString().reader().readLines())
    assertEquals("", error.toString())
    output.reset()

    Files.writeString(source, $$"li $v0,999\nsyscall\n")
    assertEquals(23, execute(arguments, output, error))
    assertEquals(listOf("", "1", ""), output.toString().reader().readLines())
    assertTrue(error.toString().contains("syscall"))
    output.reset()
    error.reset()
    assertEquals(0, execute(arrayOf("a", "ic", source.toString()), output, error))
    assertEquals("", output.toString())
    assertEquals("", error.toString())
  }

  @ParameterizedTest
  @ValueSource(strings = ["ad", "da"])
  fun tracesOnlyAssemblyInCombinedLegacyMode(flag: String) {
    val source = directory.resolve("trace.s")
    Files.writeString(source, $$"li $v0,999\nsyscall\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(0, execute(arrayOf(flag, source.toString()), output, error))
    assertEquals(
        listOf(
            "--------  TOKENIZING BEGINS  -----------",
            "--------  ASSEMBLY BEGINS  -----------",
            "",
            "--------  ALL PROCESSING COMPLETE  -----------",
        ),
        output.toString().reader().readLines(),
    )
    assertEquals("", error.toString())
  }

  @Test
  fun tracesRealPhasesAndKeepsProgramOutputOnItsOwnStream() {
    val source = directory.resolve("trace.s")
    Files.writeString(source, $$"li $a0,42\nli $v0,1\nsyscall\nli $v0,10\nsyscall\n")
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()

    assertEquals(0, execute(arrayOf("--trace-phases", "me", source.toString()), output, error))
    assertEquals("42", output.toString())
    assertEquals(
        listOf(
            "--------  TOKENIZING BEGINS  -----------",
            "--------  ASSEMBLY BEGINS  -----------",
            "--------  SIMULATION BEGINS  -----------",
            "",
            "--------  ALL PROCESSING COMPLETE  -----------",
        ),
        error.toString().reader().readLines(),
    )
    output.reset()
    error.reset()

    Files.writeString(source, "bogus\n")
    assertEquals(19, execute(arrayOf("d", "ae19", source.toString()), output, error))
    assertEquals(
        listOf(
            "--------  TOKENIZING BEGINS  -----------",
            "--------  ASSEMBLY BEGINS  -----------",
        ),
        output.toString().reader().readLines(),
    )
    assertTrue(error.toString().contains("bogus"))
    output.reset()
    error.reset()

    Files.delete(source)
    assertEquals(19, execute(arrayOf("d", "ae19", source.toString()), output, error))
    assertEquals(
        listOf("--------  TOKENIZING BEGINS  -----------"),
        output.toString().reader().readLines(),
    )
    assertTrue(error.toString().contains(source.fileName.toString()))
  }

  @Test
  fun distinguishesInterruptedExecutionFromTheStepLimit() {
    val source = directory.resolve("cancel.s")
    Files.writeString(source, $$"li $a0,42\nli $v0,1\nsyscall\nloop: bc loop\n")
    val output =
        object : ByteArrayOutputStream() {
          @Synchronized
          override fun write(bytes: ByteArray, start: Int, length: Int) {
            super.write(bytes, start, length)
            Thread.currentThread().interrupt()
          }
        }
    val error = ByteArrayOutputStream()

    try {
      assertEquals(
          130,
          execute(arrayOf("--max-steps", "40", source.toString()), output, error),
      )
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      // Clear the interrupt before JUnit reuses the test thread.
      Thread.interrupted()
    }
    assertEquals("42", output.toString())
    assertEquals("Execution cancelled.", error.toString().trim())
  }

  companion object {
    private fun execute(
        args: Array<String>,
        out: ByteArrayOutputStream,
        err: ByteArrayOutputStream,
    ): Int {
      return MarsCli.execute(
          arrayOf(*args),
          InputStream.nullInputStream(),
          PrintStream(out),
          PrintStream(err),
      )
    }
  }
}
