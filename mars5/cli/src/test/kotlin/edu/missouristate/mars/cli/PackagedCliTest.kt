package edu.missouristate.mars.cli

import edu.missouristate.mars.mips.dump.DumpFormatLoader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Exercises the standalone distribution in a separate JVM. */
internal class PackagedCliTest {
  @TempDir lateinit var directory: Path

  @Test
  fun gradingUsesCaseLocalScriptedTimeWithoutHostSleep() {
    val script =
        source(
            "timing.kts",
            """
            import edu.missouristate.mars.cli.grading.TimingCheck.Operation
            for (index in 0..1) case("scripted clock " + index) {
                val clock = provideTiming(listOf(0x1234567880000001L, 7L))
                runToExit()
                expectRegister("s0", -2147483647)
                expectRegister("s1", 0x12345678)
                expectRegister("a0", 7)
                expectRegister("a1", 0)
                expectCondition(clock.snapshot().map { it.operation } == listOf(Operation.TIME, Operation.SLEEP, Operation.TIME), "Ordered service events")
                expectCondition(clock.snapshot().map { it.milliseconds } == listOf(0x1234567880000001L, 2147483647L, 7L), "Exact clock and sleep values")
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "timing.asm",
            $$"""
            .text
            li $v0, 30
            syscall
            move $s0, $a0
            move $s1, $a1
            li $a0, 2147483647
            li $v0, 32
            syscall
            li $v0, 30
            syscall
            li $v0, 10
            syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertEquals(
        listOf("PASS scripted clock 0", "PASS scripted clock 1"),
        result.out.lines().filter { it.isNotBlank() },
    )
  }

  @Test
  fun gradingSelectsByteOrderBeforeAssemblyAndKeepsCasesIndependent() {
    val script =
        source(
            "byte-order.kts",
            """
            import java.nio.ByteOrder
            fun verifyOrder(first:Int,loaded:Int) = Unit
            case("big endian", byteOrder=ByteOrder.BIG_ENDIAN) {
                expectBytes("word",0x11,0x22,0x33,0x44)
                runUntil("done")
                expectRegister("s0",0x11)
                expectRegister("s1",0x01020304)
                expectWord("output",0x04030201)
                expectBytes("output",4,3,2,1)
                setWord("word",0x89abcdef.toInt())
                expectBytes("word",0x89,0xab,0xcd,0xef)
            }
            case("explicit little endian", byteOrder=ByteOrder.LITTLE_ENDIAN) {
                expectBytes("word",0x44,0x33,0x22,0x11)
                runUntil("done")
                expectRegister("s0",0x44)
                expectRegister("s1",0x04030201)
                expectWord("output",0x04030201)
                expectBytes("output",1,2,3,4)
            }
            case("default remains little endian") {
                expectBytes("word",0x44,0x33,0x22,0x11)
                runUntil("done")
                expectRegister("s0",0x44)
                expectRegister("s1",0x04030201)
                expectWord("output",0x04030201)
                expectBytes("output",1,2,3,4)
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "byte-order.asm",
            $$"""
            .data
            word: .word 0x11223344
            bytes: .byte 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16
            output: .space 16
            .text
                la $t0,word
                lbu $s0,0($t0)
                la $t0,bytes
                lw $s1,0($t0)
                ld.b $w0,0($t0)
                la $t0,output
                st.w $w0,0($t0)
            done:
                nop
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertEquals(3, result.out.lineSequence().count { it.startsWith("PASS ") }, result.out)
  }

  @Test
  fun gradingCallsStatefulFunctionsWithoutResettingTheInstructionBudget() {
    val script =
        source(
            "calls.kts",
            """
            case("same session") {
                val trace = traceExecution()
                setRegisterToDataAddress("a0", "value")
                for(delta in listOf(2,5,-1)) {
                    setRegister("a1",delta)
                    callFunction("worker","done")
                }
                expectWord("value",6)
                expectCondition(trace.snapshot().instructions==18L,"External call setup is excluded")
                runToExit()
            }
            case("shared budget",instructionLimit=10) {
                setRegisterToDataAddress("a0","value");setRegister("a1",1)
                callFunction("worker","done")
                callFunction("worker","done")
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "calls.asm",
            $$"""
            .data 0x100000000
            value: .word 0
            .text
            done:
                li $v0, 10
                syscall
            worker:
                lw $t0, 0($a0)
                addu $t0, $t0, $a1
                sw $t0, 0($a0)
                move $v0, $t0
                jr $ra
                nop
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS same session"), result.out)
    assertTrue(
        result.out.contains(
            "FAIL shared budget: Expected execution to reach BREAKPOINT, stopped at INSTRUCTION_LIMIT",
        ),
        result.out,
    )
  }

  @Test
  fun showsHelpAndRunsQuietly() {
    val help = launch("h")
    assertEquals(0, help.status)
    assertTrue(help.out.contains("Usage:"))
    assertTrue(help.out.contains("dump"))
    assertTrue(help.out.contains("ae"))
    assertEquals("", help.err)
    val file = source("entry.s", "nop\n")
    assertProgramResult(listOf(file.toString()), "", 0, "")
    assertProgramResult(listOf("nc", file.toString()), "", 0, "")
  }

  @Test
  fun programArgumentsThatLookLikeOutputFlagsStayProgramArguments() {
    val file =
        source(
            "flags.s",
            $$"""
            move $s0,$a1
            lw $a0,0($s0)
            li $v0,4
            syscall
            lw $a0,4($s0)
            li $v0,4
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    assertProgramResult(listOf("nc", file.toString(), "pa", "me", "nc"), "", 0, "menc")

    val counted = launch("ic", file.toString(), "pa", "ME", "NC")
    assertEquals(0, counted.status, counted.err)
    assertEquals("", counted.err)
    assertEquals(listOf("MENC", "9"), counted.out.trimEnd().lines().toList())
  }

  @Test
  fun dumpFilenamesThatLookLikeOutputFlagsStayFilenames() {
    val file = source("dump-flags.s", "nop\n")
    val result =
        launch(
            "ad",
            "dump",
            ".text",
            "HexText",
            "me",
            "dump",
            ".text",
            "HexText",
            "nc",
            file.toString(),
        )
    assertEquals(0, result.status, result.err)
    assertEquals("", result.err)
    assertTrue(result.out.contains("ASSEMBLY BEGINS"))
    for (name in listOf("me", "nc")) {
      assertEquals(listOf("00000000"), Files.readAllLines(directory.resolve(name)))
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["d", "ad", "da", "a"])
  fun traceAndAssembleOnlyModesReportExpectedPhases(flag: String) {
    val file = source("trace.s", $$"li $a0,42\nli $v0,1\nsyscall\nli $v0,10\nsyscall\n")
    val arguments = listOf("nc", flag, "me", file.toString())
    val standalone = launch("", arguments)
    assertEquals(0, standalone.status)
    assertEquals(if (flag == "d") "42" else "", standalone.out.trim())
    assertEquals(if (flag == "d") 4 else if (flag == "a") 0 else 3, phaseLines(standalone).size)
  }

  @Test
  fun graderJarRegistersItsKotlinScriptTemplate() {
    val archive = Path.of(System.getProperty("mars.cli.jar"))
    ZipFile(archive.toFile()).use { jar ->
      URLClassLoader.newInstance(
              arrayOf(archive.toUri().toURL()),
              ClassLoader.getPlatformClassLoader(),
          )
          .use { loader ->
            val template = "edu.missouristate.mars.cli.grading.AssignmentScript"
            assertNotNull(
                jar.getEntry("META-INF/kotlin/script/templates/$template.classname"),
            )
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            try {
              thread.contextClassLoader = null
              val annotation =
                  Class.forName(template, true, loader).declaredAnnotations.first { candidate ->
                    candidate.annotationClass.java.simpleName == "KotlinScript"
                  }
              val configuration: Class<*> =
                  annotation.annotationClass.java
                      .getMethod("compilationConfiguration")
                      .invoke(annotation) as Class<*>
              assertNotNull(configuration.getField("INSTANCE").get(null))
            } finally {
              thread.contextClassLoader = previous
            }
          }
    }
  }

  @Test
  fun release6ReportsFullWidthRegisters() {
    val entry =
        source(
            "wide.s",
            $$"""
            daddiu $t0, $zero, 1
            dsll32 $t0, $t0, 0
            daddiu $t0, $t0, -1
            li $v0, 10
            syscall

            """
                .trimIndent(),
        )
    val wide = launch("--register", "t0", entry.toString())
    assertEquals(0, wide.status, wide.err)
    assertTrue(wide.out.contains($$"$t0\t0x00000000ffffffff"), wide.out)
  }

  @Test
  fun runsSourceAssembledAboveFourGiB() {
    val entry =
        source(
            "high-address.s",
            $$"""
            .data 0x100000000
            pointer: .dword main
            .text 0x100400000
            .globl main
            main:
                dla $a0,pointer
                ld $t0,0($a0)
                li $v0,10
                syscall

            """
                .trimIndent(),
        )
    val run = launch("--start-at-main", "--register", "t0", entry.toString())
    assertEquals(0, run.status, run.err)
    assertTrue(run.out.contains($$"$t0\t0x0000000100400000"), run.out)
  }

  @Test
  fun dumpsHighTextAndDataWithoutTruncatingAddresses() {
    val entry =
        source(
            "high-dump.s",
            $$"""
            .data 0x100000000
            .word 0x41424344
            .text 0x100400000
            .globl main
            main: addiu $v0,$zero,10
            syscall

            """
                .trimIndent(),
        )
    val data = directory.resolve("high-data.txt")
    val text = directory.resolve("high-text.txt")
    val run =
        launch(
            "--start-at-main",
            "dump",
            "0x100000000-0x100000000",
            "HexText",
            data.toString(),
            "dump",
            "0x100400000-0x100400000",
            "SegmentWindow",
            text.toString(),
            "0x100000000-0x100000000",
            entry.toString(),
        )
    assertEquals(0, run.status, run.err)
    assertEquals("41424344", Files.readString(data).trim())
    assertTrue(Files.readString(text).contains("0x0000000100400000  0x2402000a"))
    assertTrue(run.out.contains("Mem[0x0000000100000000]"), run.out)
  }

  @Test
  fun runsAnMsaLoadComputeAndStoreProgram() {
    val entry =
        source(
            "vector.s",
            $$"""
            .data
            input: .word 1, 2, 3, 4
            output: .space 16
            .text
            la $t0,input
            ld.w $w1,0($t0)
            slli.w $w1,$w1,1
            addv.w $w2,$w1,$w1
            st.w $w2,16($t0)
            lw $a0,24($t0)
            li $v0,1
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val run = launch("nc", entry.toString())
    assertEquals(0, run.status, run.err)
    assertEquals("12", run.out.trim())
  }

  @Test
  fun reportsBothPrecisionsForAnOddMARS64FloatingRegister() {
    val entry =
        source(
            "wide-fpu.s",
            $$"""
            lui $t0, 0x4004
            dsll32 $t0, $t0, 0
            dmtc1 $t0, $f31
            li $v0, 10
            syscall

            """
                .trimIndent(),
        )
    val wide = launch("--register", "f31", entry.toString())
    assertEquals(0, wide.status, wide.err)
    assertTrue(wide.out.contains($$"$f31\t0x00000000\t0x4004000000000000"), wide.out)
  }

  @Test
  fun kotlinAssignmentCanAssertFullMARS64Registers() {
    val script =
        source(
            "assignment64.kts",
            $$"""
            case("wide result") {
                setRegister("a0", 0x100000000L)
                runUntil("done")
                expectRegister("v0", 0x100000003L)
                expectVector("w1", low = -1L, high = 0L)
                expectVectorLane("${'$'}w1", lane = 7, width = 8, expected = 255)
                expectVectorLane("w1", lane = 8, width = 8, expected = 0)
                expectVectorLane("w1", lane = 0, width = 64, expected = -1L)
                runToExit()
            }

            """
                .trimIndent(),
        )
    val entry =
        source(
            "wide.s",
            $$"""
            daddiu $v0, $a0, 3
            ldi.d $w1,0
            daddiu $t0,$zero,-1
            dmtc1 $t0,$f1
            done: li $v0, 10
            syscall

            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err)
    assertTrue(result.out.contains("PASS wide result"), result.out)
  }

  @Test
  fun mips64StringSyscallsUseHighBuffers() {
    val script =
        source(
            "high string checks.kts",
            """
            case("high text", utf8Text = true, input = "é\n") {
                runToExit()
                expectOutput("é\n")
                expectBytes("buffer", 0xc3, 0xa9, 10, 0)
            }

            """
                .trimIndent(),
        )
    val entry =
        source(
            "high-string.mars",
            $$"""
            .data 0x100000000
            buffer: .space 8
            .text
                dla $a0,buffer
                addiu $a1,$zero,8
                addiu $v0,$zero,8
                syscall
                addiu $v0,$zero,4
                syscall
                addiu $v0,$zero,10
                syscall

            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS high text"), result.out)
  }

  @Test
  fun kotlinAssignmentCanStartStepAndInspectMemoryAboveFourGiB() {
    val script =
        source(
            "high-address checks.kts",
            """
            case("high routine") {
                requireTextLabel("routine")
                requireDataLabel("cell")
                setWord("cell", 41, offset = 4)
                setRegister("a0", 0x100000004L)
                startAt("routine")
                runUntil("store")
                expectRegister("t0", 42)
                expectWord("cell", 41, offset = 4)
                step()
                expectWord("cell", 42, offset = 4)
                expectBytes("cell", 42, 0, 0, 0, offset = 4)
                runToExit()
            }

            """
                .trimIndent(),
        )
    val entry =
        source(
            "high-routine.mars",
            $$"""
            .data 0x100000000
            cell: .word 0, 0
            .text 0x100400000
            routine:
                lw $t0,0($a0)
                addiu $t0,$t0,1
            store:
                sw $t0,0($a0)
                addiu $v0,$zero,10
                syscall

            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS high routine"), result.out)
  }

  @Test
  fun gradingChecksDataAddressesWithoutRequiringWordAlignment() {
    val script =
        source(
            "data-address checks.kts",
            """
            case("data addresses") {
                expectDataAddress("narrow", 0x10010000L)
                expectDataAddress("unaligned", 0x10010001L)
                expectDataAddress("wide", 0x100000000L)
            }
            case("moved data") {
                expectDataAddress("narrow", 0x10010004L)
            }
            case("missing data") {
                expectDataAddress("absent", 0x10010000L)
            }
            case("text is not data") {
                expectDataAddress("main", 0x00400000L)
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "data-addresses.asm",
            $$"""
            .data 0x10010000
            narrow: .byte 0
            unaligned: .byte 0
            .data 0x100000000
            wide: .word 0
            .text
            main:
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS data addresses"), result.out)
    assertTrue(
        result.out.contains("Expected address of data label narrow = 0x10010004, got 0x10010000"),
        result.out,
    )
    assertTrue(result.out.contains("Missing label: absent"), result.out)
    assertTrue(result.out.contains("Expected a data label: main"), result.out)
  }

  @Test
  fun gradingUsesTheSharedCacheWithColdBoundariesAndWideAddresses() {
    val script =
        source(
            "cache.kts",
            """
            import edu.missouristate.mars.devices.DataCacheSimulator.Options

            case("shared cache") {
                runUntil("begin")
                val cache = connectDataCache(Options(blocks = 1, wordsPerBlock = 2), logAccesses = true)
                runUntil("boundary")
                cache.expectCounts(2, 1)
                val address = readDataAddress("values")
                expectCondition(address == 0x100000000L, "full-width data address")
                expectCondition(cache.recordedAccesses().map { it.address } == listOf(address, address + 4), "native access order")
                expectCondition(cache.recordedAccesses().all { it.length == 4 && !it.write }, "native word widths")
                readWord("values")
                expectCondition(cache.snapshot().accesses == 2L, "inspection must not enter the cache")
                cache.reset()
                runToExit()
                cache.expectCounts(1, 0)
                expectCondition(cache.snapshot().blocks.single().tag == 0x20000001L, "wide cache tag")
                cache.close()
                cache.expectCounts(1, 0)
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "cache.asm",
            $$"""
                .data 0x100000000
            values: .word 1,2,3
                .text
                dla $t0, values
            begin:
                lw $t1, 0($t0)
                lw $t2, 4($t0)
            boundary:
                lw $t3, 8($t0)
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS shared cache"), result.out)
  }

  @Test
  fun gradingTracesLogicalReadBoundsWithNativeWidthsAndFullAddresses() {
    val script =
        source(
            "accesses.kts",
            """
            case("bounded input") {
                val accesses = traceMemoryAccesses("values", 8)
                val base = readDataAddress("values")
                runUntil("ready")
                accesses.expectReadsWithin(base, 4)
                val snapshot = accesses.snapshot()
                expectCondition(snapshot.map { it.length } == listOf(1, 4, 1), "native widths")
                expectCondition(snapshot.map { it.write } == listOf(false, false, true), "read and write kinds")
                expectCondition(snapshot.all { it.address >= 0x100000000L }, "full-width addresses")
                readBytes("values", 8)
                expectCondition(accesses.snapshot() == snapshot, "inspection excluded")
                accesses.close()
                runToExit()
                expectCondition(accesses.snapshot() == snapshot, "closed observation stays frozen")
            }
            case("crossing logical byte") {
                val access = traceMemoryAccesses("values", offset = 1)
                runUntil("ready")
                access.expectReadsWithin(readDataAddress("values", 1), 1)
            }
            case("overflow is explicit") {
                val access = traceMemoryAccesses("values", 8, maxAccesses = 2)
                runUntil("ready")
                access.snapshot()
            }
            case("fresh observation") {
                val access = traceMemoryAccesses("values", 8)
                access.expectReadsWithin(readDataAddress("values"), 0)
                runUntil("ready")
                expectCondition(access.snapshot().size == 3, "fresh independent history")
                runToExit()
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "accesses.asm",
            $$"""
                .data 0x100000000
            values: .word 1,2
                .text
                dla $t0, values
                lbu $t1, 0($t0)
                lw $t2, 0($t0)
                sb $t1, 4($t0)
            ready:
                lw $t3, 4($t0)
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS bounded input"), result.out)
    assertTrue(result.out.contains("FAIL crossing logical byte"), result.out)
    assertTrue(result.out.contains("observed 4-byte read at 0x100000000"), result.out)
    assertTrue(result.out.contains("Memory-access trace exceeds 2 notices"), result.out)
    assertTrue(result.out.contains("PASS fresh observation"), result.out)
  }

  @Test
  fun gradingChecksEquivalentRoutesFromDetachedMemorySnapshots() {
    val script =
        source(
            "routes.kts",
            """
            import kotlin.math.abs

            val routes = listOf(listOf(0, 1, 9), listOf(0, 8, 9))
            for ((index, route) in routes.withIndex()) case("route " + index) {
                for ((position, value) in route.withIndex()) setWord("route", value, offset = position * 4)
                val actual = (0..2).map { readWord("route", offset = it * 4) }
                expectCondition(actual.first() == 0 && actual.last() == 9, "route endpoints")
                expectCondition(actual.zipWithNext().all { (a, b) ->
                    abs(a / 8 - b / 8) + abs(a % 8 - b % 8) == 1
                }, "route must use orthogonal neighbors")
                val copy = readBytes("route", 12)
                copy[0] = 255
                expectWord("route", 0)
                expectCondition(readBytes("route", 1).single() == 0, "snapshot is detached")
                runToExit()
            }
            case("bad relation") { expectCondition(false, "parent chain is not shortest") }
            case("fresh case after failure") {
                expectCondition(readWord("route", offset = 4) == 0, "fresh memory")
                runToExit()
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "routes.asm",
            $$"""
                .data 0x100000000
            route: .word 0, 0, 0
                .text
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertEquals(3, result.out.lines().count { it.startsWith("PASS ") }, result.out)
    assertTrue(result.out.contains("FAIL bad relation: parent chain is not shortest"), result.out)
  }

  @Test
  fun gradingPassesFullWidthLabelAddressesToIndependentFunctions() {
    val script =
        source(
            "function-addresses.kts",
            """
            case("independent wide function") {
                setRegisterToDataAddress("a0", "wide", offset = 3)
                setRegisterToTextAddress("ra", "done")
                startAt("helper")
                expectRegister("a0", 0x100000003L)
                expectRegister("ra", 0x10040000cL)
                val work = traceExecution()
                runToExit()
                work.expectCounts(5, 0, 0)
                expectRegister("t0", 0x11)
            }
            case("data is not code") { setRegisterToTextAddress("ra", "wide") }
            case("code is not data") { setRegisterToDataAddress("a0", "done") }
            case("zero cannot hold an argument") { setRegisterToDataAddress("zero", "wide") }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "function-addresses.asm",
            $$"""
                .data 0x100000000
            wide: .word 0x11223344
                .text 0x100400000
            helper:
                lbu $t0, 0($a0)
                jr $ra
                nop
            done:
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS independent wide function"), result.out)
    assertEquals(3, result.out.lines().count { it.startsWith("FAIL ") })
  }

  @Test
  fun gradingCountsNativeBranchDecisionsAndCodeRangesInTheStandaloneJar() {
    val script =
        source(
            "execution-counts.kts",
            """
            for (index in 0..1) {
                case("counts " + index) {
                    val all = traceExecution()
                    val work = traceExecution("window", "finish")
                    runUntil("finish")
                    all.expectCounts(13, 2, 1)
                    work.expectCounts(8, 1, 1)
                    work.close()
                    runToExit()
                    all.expectCounts(15, 2, 1)
                    val counts = work.snapshot()
                    println("METRIC " + counts.instructions + "," + counts.branches + "," + counts.takenBranches)
                    expectOutput("")
                }
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "execution-counts.asm",
            $$"""
                li $t0, 2
            window:
                addiu $t0, $t0, -1
                bne $t0, $zero, window
                nop
                jal helper
                nop
            finish:
                li $v0, 10
                syscall
            helper:
                beq $zero, $zero, helper_done
                nop
            helper_done:
                jr $ra
                nop
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertEquals(2, result.out.lines().count { it == "METRIC 8,2,1" })
    assertEquals(2, result.out.lines().count { it.startsWith("PASS ") })
  }

  @Test
  fun gradingUsesTheIdeMarsBotModelAcrossPausesAndFreshCases() {
    val script =
        source(
            "robot.kts",
            """
            for (clock in listOf(8, 64, 97)) {
                case("east leg clock " + clock, instructionLimit = 2000) {
                    val bot = connectMarsBot(clock)
                    val readOnlyCoordinates = traceMemoryStores(0xffff8030L, 4)
                    runUntil("arrived")
                    bot.expectState(2.0, 0.0, 90, moving = false, penDown = true)
                    bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
                    runToExit()
                    readOnlyCoordinates.expectNone()
                    bot.expectState(2.0, 0.0, 90, moving = false, penDown = false)
                    bot.expectTrail(0.0 to 0.0, 2.0 to 0.0)
                }
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "robot.asm",
            $$"""
                dli $t0, 0xffff8000
                li $t1, 90
                sw $t1, 0x10($t0)
                li $t1, 1
                sw $t1, 0x20($t0)
                sw $t1, 0x50($t0)
                li $t2, 2
            wait:
                lw $t3, 0x30($t0)
                bne $t3, $t2, wait
                nop
                sw $zero, 0x50($t0)
            arrived:
                sw $zero, 0x20($t0)
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertEquals(3, result.out.lines().count { it.startsWith("PASS ") })
    val bad =
        source(
            "robot-bad.kts",
            """
            case("wrong endpoint") {
                val bot = connectMarsBot()
                runToExit()
                bot.expectState(0.0, 0.0, 90, false, false)
            }
            case("wrong trail") {
                val bot = connectMarsBot()
                runToExit()
                bot.expectTrail(0.0 to 0.0, 1.0 to 0.0)
            }
            """
                .trimIndent(),
        )
    val rejected = launch("grade", bad.toString(), entry.toString())
    assertEquals(1, rejected.status, rejected.err + rejected.out)
    assertEquals(2, rejected.out.lines().count { it.startsWith("FAIL ") })
  }

  @Test
  fun gradingTracesBytePortsAcrossStopsAndWideAddresses() {
    val script =
        source(
            "byte ports.kts",
            """
            case("stopped trace intervals") {
                val data = traceMemoryStores(0x10010000L, 4)
                val unchanged = traceMemoryStores("cell", 1, offset = 1)
                val ports = traceMemoryStores(0xffff0010L, 4)
                val wide = traceMemoryStores(0x100000003L)
                val wideSymbol = traceMemoryStores("wide", 1, offset = 3)
                setWord("cell", 99)
                runUntil("next")
                data.expectByteStores(0x10010000L, 255)
                ports.expectByteStores(0xffff0010L, 63, 63)
                val suffix = traceMemoryStores(0xffff0010L, 4)
                ports.close()
                runToExit()
                ports.expectByteStores(0xffff0010L, 63, 63)
                suffix.expectByteStores(0xffff0010L, 6)
                wide.expectByteStores(0x100000003L, 6)
                wideSymbol.expectByteStores(0x100000003L, 6)
                unchanged.expectNone()
            }
            case("fresh trace") {
                val ports = traceMemoryStores(0xffff0010L, 4)
                runToExit()
                ports.expectByteStores(0xffff0010L, 63, 63, 6)
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "ports.asm",
            $$"""
            .data
            cell: .word 0
            .data 0x100000000
            wide: .word 0
            .text
            main:
                la $t0, cell
                li $t2, -1
                sb $t2, 0($t0)
                lbu $t3, 0($t0)
                dli $t1, 0xffff0010
                li $t2, 63
                sb $t2, 0($t1)
                sb $t2, 0($t1)
            next:
                li $t2, 6
                sb $t2, 0($t1)
                dla $t0, wide
                sb $t2, 3($t0)
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(0, result.status, result.err + result.out)
    assertTrue(result.out.contains("PASS stopped trace intervals"), result.out)
    assertTrue(result.out.contains("PASS fresh trace"), result.out)
  }

  @Test
  fun gradingRejectsStoresEvenWhenTheOriginalBytesAreRestored() {
    val script =
        source(
            "unchanged source.kts",
            """
            case("restored bytes still received writes") {
                val stores = traceMemoryStores("cell", 4)
                runToExit()
                expectWord("cell", 0)
                stores.expectNone()
            }
            case("closed empty interval") {
                val stores = traceMemoryStores("cell", 4)
                stores.expectNone()
                stores.close()
                runToExit()
                stores.expectNone()
            }
            case("upper half of doubleword store") {
                val stores = traceMemoryStores("cell", 4, offset = 4)
                startAt("wide_store")
                runToExit()
                expectWord("cell", 0x11223344, offset = 4)
                stores.expectNone()
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "restore.asm",
            $$"""
            .data
            cell: .dword 0
            .text
                la $t0, cell
                li $t1, 1
                sw $t1, 0($t0)
                sw $zero, 0($t0)
                li $v0, 10
                syscall
            wide_store:
                la $t0, cell
                dli $t1, 0x1122334455667788
                sd $t1, 0($t0)
                li $v0, 10
                syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(1, result.status, result.err + result.out)
    assertTrue(result.out.contains("observed 2"), result.out)
    assertTrue(result.out.contains("PASS closed empty interval"), result.out)
    assertTrue(
        result.out.contains("FAIL upper half of doubleword store: Expected no stores"),
        result.out,
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["\n", "\r\n"])
  fun gradingChecksStructureAndIntermediateStateAndIsolatesCases(lineSeparator: String) {
    val script =
        source(
            "linked-list checks.kts",
            """
            case("double a word") {
                requireTextLabel("double")
                requireDataLabel("cell")
                setWord("cell", 21)
                startAt("double")
                runUntil("combine")
                expectRegister("t0", 21)
                step()
                expectRegister("a0", 42)
                runToExit()
                expectOutput("42")
            }
            case("UTF-8 fixture", input = "é\n", utf8Text = true) {
                startAt("read_input")
                runToExit(3)
                expectBytes("buffer", 0xc3, 0xa9, 10, 0)
            }
            case("fresh machine") {
                expectWord("cell", 0)
                expectRegister("t0", 0)
            }
            case("missing structure") {
                requireTextLabel("missing")
            }
            case("instruction budget across slices", instructionLimit = 2) {
                startAt("loop")
                step()
                runToExit()
            }
            case("file access") {
                startAt("file_access")
                runToExit()
            }

            """
                .trimIndent(),
        )
    val entry =
        source(
            "grading.s",
            $$"""
            .include "storage.inc"
            .text
            double: lw $t0, cell
            combine: addu $a0, $t0, $t0
            li $v0, 1
            syscall
            li $v0, 10
            syscall
            loop: bc loop
            file_access: li $v0, 13
            syscall
            read_input: la $a0, buffer
            li $a1, 4
            li $v0, 8
            syscall
            li $a0, 3
            li $v0, 17
            syscall

            """
                .trimIndent(),
        )
    val include = source("storage.inc", ".data\ncell: .word 0\nbuffer: .space 4\n")
    val result =
        launch(
            "grade",
            script.toString(),
            entry.toString(),
            "--include",
            include.toString(),
            lineSeparator = lineSeparator,
        )
    assertEquals(1, result.status, result.err)
    assertTrue(
        result.out.contains(
            listOf("PASS double a word", "PASS UTF-8 fixture", "PASS fresh machine", "")
                .joinToString(lineSeparator),
        ),
        result.out,
    )
    assertTrue(
        result.out.contains("FAIL missing structure: Missing label: missing"),
        result.out,
    )
    assertTrue(
        result.out.contains(
            "FAIL instruction budget across slices: Expected execution to reach NORMAL_EXIT, stopped at INSTRUCTION_LIMIT",
        ),
        result.out,
    )
    assertTrue(result.out.contains("syscall 13 is disabled by the host"), result.out)

    val withoutInclude = launch("grade", script.toString(), entry.toString())
    assertEquals(1, withoutInclude.status, withoutInclude.err)
    assertTrue(withoutInclude.out.contains("storage.inc"), withoutInclude.out)
  }

  @ParameterizedTest
  @ValueSource(strings = ["\n", "\r\n"])
  fun gradingReadsOnlyExplicitBinaryFixturesAndChecksCleanup(lineSeparator: String) {
    val script =
        source(
            "files.kts",
            """
            import edu.missouristate.mars.cli.grading.FileAccess.Operation
            case("binary fixture") {
                val files = provideFiles(mapOf("fixture.bin" to byteArrayOf(0, -128, -1)), maxReadSize = 2)
                runToExit()
                expectBytes("buffer", 0, 128, 42, 42)
                files.expectAllClosed()
                val reads = files.snapshot().filter { it.operation == Operation.READ }
                expectCondition(reads.size == 1 && reads[0].requestedBytes == 4 && reads[0].result == 2, "Record the actual short read")
            }
            case("fresh file policy") {
                val files = provideFiles(emptyMap())
                runUntil("opened")
                expectRegister("v0", -1)
                files.expectAllClosed()
            }
            """
                .trimIndent(),
        )
    val entry =
        source(
            "files.asm",
            $$"""
            .data
            path: .asciiz "fixture.bin"
            buffer: .byte 42:4
            .text
            la $a0, path
            li $a1, 0
            li $v0, 13
            syscall
            opened:
            move $s0, $v0
            move $a0, $s0
            la $a1, buffer
            li $a2, 4
            li $v0, 14
            syscall
            move $a0, $s0
            li $v0, 16
            syscall
            li $v0, 10
            syscall
            """
                .trimIndent(),
        )
    val result = launch("grade", script.toString(), entry.toString(), lineSeparator = lineSeparator)
    assertEquals(0, result.status, result.err + result.out)
    assertEquals(
        listOf("PASS binary fixture", "PASS fresh file policy", "").joinToString(lineSeparator),
        result.out,
    )
  }

  @Test
  fun gradingReportsScriptErrorsWithASeparateExitCode() {
    val script = source("assignment2.kts", "case(\"broken\") { missingFunction() }\n")
    val entry = source("entry.s", "nop\n")
    val result = launch("grade", script.toString(), entry.toString())
    assertEquals(2, result.status, result.err)
    assertTrue(result.err.contains("missingFunction"), result.err)
  }

  @Test
  fun gradingBoundsAssemblerExpansionAndProgramOutput() {
    val script = source("assignment3.kts", "case(\"bounded\") { runToExit() }\n")
    val largeData = source("large.s", ".data\n.byte 1:2000000\n.text\nnop\n")
    val assemblyLimit = launch("grade", script.toString(), largeData.toString())
    assertEquals(1, assemblyLimit.status, assemblyLimit.err)
    assertTrue(
        assemblyLimit.out.contains("Submission exceeds the assembly work limit"),
        assemblyLimit.out,
    )

    val largeOutput =
        source(
            "output.s",
            $$"""
            .data
            message: .asciiz "abcdefghijklmnopqrstuvwxyz0123456789"
            .text
            la $a0, message
            li $v0, 4
            loop: syscall
            bc loop

            """
                .trimIndent(),
        )
    val outputLimit = launch("grade", script.toString(), largeOutput.toString())
    assertEquals(1, outputLimit.status, outputLimit.err)
    assertTrue(outputLimit.out.contains("Program output exceeds 64 KiB"), outputLimit.out)
  }

  @Test
  fun utf8TextModePrintsUnicodeAndTruncatesInputAtAByteBoundary() {
    val file =
        source(
            "unicode.mars",
            $$"""
            .data
            greeting: .asciiz "鯉: "
            buffer: .byte 99, 99, 99, 99, 99, 99
            .text
            la $a0, greeting
            li $v0, 4
            syscall
            la $a0, buffer
            li $a1, 6
            li $v0, 8
            syscall
            li $v0, 4
            syscall
            li $v0, 10
            syscall

            """
                .trimIndent(),
        )
    assertProgramResult(listOf("--utf8-text", file.toString()), "é🐟X\n", 0, "鯉: é")
    assertProgramResult(listOf("--utf8-text", file.toString()), "🐟X\n", 0, "鯉: 🐟X")
    assertProgramResult(listOf("--utf8-text", file.toString()), "é\n", 0, "鯉: é")
  }

  @Test
  fun legacyTextModeKeepsOneByteStrings() {
    val file =
        source(
            "legacy.mars",
            $$"""
            .data
            greeting: .asciiz "é"
            .text
            la $a0, greeting
            li $v0, 4
            syscall
            li $v0, 10
            syscall

            """
                .trimIndent(),
        )
    assertProgramResult(listOf(file.toString()), "", 0, "é")
  }

  @Test
  fun projectModeCombinesSiblingAndExplicitSourcesWithInputAndArguments() {
    val main =
        source(
            "z-entry.s",
            $$"""
            move $s0,$a0
            lw $a0,0($a1)
            balc print
            li $v0,5
            syscall
            move $a0,$v0
            balc number
            move $a0,$s0
            li $v0,17
            syscall

            """
                .trimIndent(),
        )
    source("a-library.asm", $$".text\n.globl print\nprint: li $v0,4\nsyscall\njrc $ra\n")
    val extra =
        source("nested/number.s", $$".text\n.globl number\nnumber: li $v0,1\nsyscall\njrc $ra\n")
    source("ignored.txt", "not assembly\n")
    assertProgramResult(
        listOf("nc", "p", main.toString(), extra.toString(), "pa", "value=", "d"),
        "42\n",
        2,
        "value=42",
    )
  }

  @Test
  fun projectModeDiscoversMarsSourcesAndLoadsIncludeFilesOnDemand() {
    val main =
        source(
            "main.mars",
            $$"""
            balc increment
            move $a0,$t0
            li $v0,1
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    source("values.inc", ".eqv BASE 41\n")
    source(
        "math.MARS",
        $$"""
        .include "values.inc"
        .globl increment
        increment: li $t0,BASE
        addiu $t0,$t0,1
        jrc $ra

        """
            .trimIndent(),
    )
    source("unused.inc", "this fragment must not be assembled\n")
    assertProgramResult(listOf("nc", "p", main.toString()), "", 0, "42")
  }

  @Test
  fun explicitExtensionlessSourcesRunWithoutDirectoryDiscovery() {
    val entry =
        source(
            "entry",
            $$"""
            balc helper
            move $a0,$t0
            li $v0,1
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val helper =
        source(
            ".asm",
            $$"""
            .globl helper
            helper: li $t0,42
            jrc $ra

            """
                .trimIndent(),
        )
    assertProgramResult(listOf("nc", entry.toString(), helper.toString()), "", 0, "42")
  }

  @Test
  fun projectModeKeepsExtensionlessEntryAndExplicitSourcesAlongsideSiblings() {
    val entry =
        source(
            "entry",
            $$"""
            balc helper
            balc sibling
            addu $a0,$t0,$t1
            li $v0,1
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val helper =
        source(
            ".s",
            $$"""
            .globl helper
            helper: li $t0,42
            jrc $ra

            """
                .trimIndent(),
        )
    val sibling =
        source(
            ".local.S",
            $$"""
            .globl sibling
            sibling: li $t1,7
            jrc $ra

            """
                .trimIndent(),
        )
    source("unselected", "not assembly\n")
    assertProgramResult(listOf("nc", "p", entry.toString(), helper.toString()), "", 0, "49")
    assertProgramResult(
        listOf("nc", "p", entry.toString(), helper.toString(), sibling.toString()),
        "",
        0,
        "49",
    )
  }

  @Test
  fun startAtMainExecutesTheArchitecturalDelaySlot() {
    val file =
        source(
            "entry.s",
            $$"""
            .text
            .globl main
            addiu $a0,$zero,11
            addiu $v0,$zero,17
            syscall
            main:
            addiu $a0,$zero,19
            beq $zero,$zero,exit
            addiu $a0,$a0,1
            exit:
            addiu $v0,$zero,17
            syscall

            """
                .trimIndent(),
        )
    assertProgramResult(listOf("nc", file.toString()), "", 11, "")
    assertProgramResult(listOf("nc", "sm", file.toString()), "", 20, "")
  }

  @Test
  fun diagnosticsAndExplicitErrorStatusesApplyAcrossPhases() {
    val warning = source("warning.s", ".data\n.byte 1024\n.text\nnop\n")
    assertDiagnostics(listOf("a", warning.toString()), 0, "possibly truncated")
    assertDiagnostics(listOf("a", "we", "ae19", warning.toString()), 19, "possibly truncated")

    val pseudo = source("pseudo.s", $$"li $v0,999\nsyscall\n")
    assertDiagnostics(listOf("np", "ae19", pseudo.toString()), 19, "li")
    assertDiagnostics(listOf("se23", pseudo.toString()), 23, "syscall")
    assertDiagnostics(listOf("np", "ae0", pseudo.toString()), 0, "li")
    assertDiagnostics(listOf("se0", pseudo.toString()), 0, "syscall")
  }

  @Test
  fun reportsDefaultFailureStatuses() {
    val assembly = source("assembly-error.s", "bogus\n")
    val simulation = source("simulation-error.s", $$"li $v0,999\nsyscall\n")
    for (file in listOf(assembly, simulation)) {
      val result = launch("nc", "me", file.toString())
      assertEquals(1, result.status)
      assertTrue(result.err.startsWith("Error"))
    }
    for (arguments in
        listOf(
            listOf("--unknown-option", "nc", "me"),
            listOf("nc", "me", "mc"),
            listOf("nc", "me", $$"$notARegister", simulation.toString()),
            listOf("nc", "me"),
        )) {
      val failure = launch("", arguments)
      assertEquals(2, failure.status, arguments.toString())
      assertFalse(failure.err.isBlank(), arguments.toString())
    }
  }

  @Test
  fun selfModifyingCodeHonorsTheRuntimeOption() {
    val file =
        source(
            "patch.s",
            $$"""
            la $t0,patch
            li $t1,0x2404002a
            sw $t1,0($t0)
            patch: nop
            li $v0,17
            syscall

            """
                .trimIndent(),
        )
    assertProgramResult(listOf("nc", "smc", file.toString()), "", 42, "")
    assertDiagnostics(listOf("se23", file.toString()), 23, "text segment")
  }

  @ParameterizedTest
  @ValueSource(strings = ["hex", "dec b", "ascii me"])
  fun registerAndMemoryReportsUseTheSelectedStream(options: String) {
    val file =
        source(
            "reports.s",
            $$"""
            .data
            .word 1,2,3,4,5
            .text
            li $t0,0x41424344
            mtc1 $t0,$f0
            mtc1 $t0,$f1
            li $a0,7
            li $v0,1
            syscall
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val arguments = mutableListOf("nc")
    arguments.addAll(options.split(" "))
    arguments.addAll(
        listOf(
            "$8",
            "f0",
            $$"$f1",
            "0x10010000-0x10010010",
            "0x00400000-0x00400000",
            file.toString(),
        ),
    )
    val standalone = launch("", arguments)
    assertEquals(0, standalone.status)
    val reportsToError: Boolean = options.endsWith(" me")
    assertTrue(standalone.out.startsWith("7"))
    val report: String = if (reportsToError) standalone.err else standalone.out
    if (options.contains(" b")) {
      assertFalse(report.contains("Mem["))
      assertFalse(report.contains($$"$f0"))
      assertTrue(report.lines().any { it.trimEnd() == "1\t2\t3\t4" })
    } else {
      assertTrue(report.contains("Mem["))
      assertTrue(report.contains($$"$f0"))
    }
    if (reportsToError) {
      assertEquals("7", standalone.out)
    } else {
      assertEquals("", standalone.err)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun repeatedDumpsWriteEveryFormatAndIncludeExplicitRangeEndpoints(assembleOnly: Boolean) {
    val file =
        source(
            "dump.s",
            $$"""
            .data
            word: .word 11,22
            .text
            li $t0,42
            sw $t0,word
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val descriptors =
        DumpFormatLoader.FORMATS.map { format ->
          requireNotNull(format.commandDescriptor) {
            "Missing descriptor for ${format.description}"
          }
        }
    val arguments = mutableListOf("nc", "me")
    if (assembleOnly) {
      arguments.add("a")
    }
    for (descriptor in descriptors) {
      arguments.addAll(listOf("dump", ".text", descriptor, dump(descriptor).toString()))
    }
    arguments.addAll(
        listOf(
            "dump",
            ".data",
            "HexText",
            dump("data").toString(),
            "dump",
            "0x10010000-0x10010004",
            "HexText",
            dump("range").toString(),
            "dump",
            "0x10010004-0x10010004",
            "HexText",
            dump("single-word").toString(),
            file.toString(),
        ),
    )
    val result = launch("", arguments)
    assertEquals(0, result.status, result.err)
    assertTrue(result.err.isBlank(), result.err)
    for (descriptor in descriptors) {
      assertTrue(Files.size(dump(descriptor)) > 0, descriptor)
    }
    val first: String = if (assembleOnly) "0000000b" else "0000002a"
    val data = Files.readAllLines(dump("data"))
    assertEquals(listOf(first, "00000016"), data.subList(0, 2))
    assertTrue(data.subList(2, data.size).all { it == "00000000" })
    assertEquals(listOf(first, "00000016"), Files.readAllLines(dump("range")))
    assertEquals(listOf("00000016"), Files.readAllLines(dump("single-word")))
  }

  @Test
  fun memoryReportsUseUnsignedAddressOrderWithoutWrapping() {
    val file =
        source(
            "kernel-range.s",
            $$"""
            .ktext 0x80000000
            addiu $k0,$zero,7
            .text
            li $v0,10
            syscall

            """
                .trimIndent(),
        )
    val arguments =
        listOf("nc", "me", "0x7ffffffc-0x80000000", "0xfffffffc-0xfffffffc", file.toString())
    val standalone = launch("", arguments)
    assertEquals(0, standalone.status, standalone.err)
    assertEquals(
        listOf("Mem[0x7ffffffc]\t0x00000000\t0x241a0007", "Mem[0xfffffffc]\t0x00000000"),
        standalone.err.lines().filter { line -> line.isNotBlank() }.map { it.trimEnd() }.toList(),
    )
    for (range in
        listOf("0x80000000-0x7ffffffc", "4294967297-4294967300", "0x80000001-0x80000004")) {
      val failure = launch("nc", "me", range, file.toString())
      assertEquals(2, failure.status, range)
      assertTrue(failure.err.contains(range), failure.err)
    }
  }

  @Test
  fun assemblyFailuresLeaveExistingAndMissingDumpDestinationsAlone() {
    val file =
        source(
            "partial-data.s",
            $$"""
            .data
            .word 11
            .text
            addiu $t0,$zero

            """
                .trimIndent(),
        )
    val existing = Files.writeString(dump("existing"), "previous successful dump\n")
    val missing = dump("missing")
    for (status in listOf(19, 0)) {
      val result =
          launch(
              "nc",
              "me",
              "ae$status",
              "dump",
              ".data",
              "HexText",
              existing.toString(),
              "dump",
              ".data",
              "HexText",
              missing.toString(),
              file.toString(),
          )
      assertEquals(status, result.status, result.err)
      assertTrue(result.err.contains("addiu"), result.err)
      assertEquals("previous successful dump\n", Files.readString(existing))
      assertFalse(Files.exists(missing))
    }
  }

  @Test
  fun simulationFailuresStillExportTheFinalMachineState() {
    val file =
        source(
            "simulation-dump.s",
            $$"""
            .data
            word: .word 11
            .text
            li $t0,42
            sw $t0,word
            li $v0,999
            syscall

            """
                .trimIndent(),
        )
    val output = dump("simulation")
    val result =
        launch(
            "nc",
            "me",
            "se23",
            "dump",
            "0x10010000-0x10010000",
            "HexText",
            output.toString(),
            file.toString(),
        )
    assertEquals(23, result.status, result.err)
    assertTrue(result.err.contains("syscall"), result.err)
    assertEquals(listOf("0000002a"), Files.readAllLines(output))
  }

  @Test
  fun compactTextLoopCountsEveryStepAndReportsTheStepLimitStatus() {
    val file = source("loop.s", "loop: bc loop\n")
    for (limit in listOf("3", "32")) {
      val arguments = listOf("nc", "ic", "mc", "CompactTextAtZero", limit, file.toString())
      val standalone = launch("", arguments)
      assertEquals(3, standalone.status)
      assertEquals(limit, standalone.out.trim())
      assertTrue(standalone.err.contains("step limit"))
    }

    val exitsAtLimit = source("exits-at-limit.s", $$"li $a0,17\nli $v0,17\nsyscall\n")
    assertProgramResult(listOf("nc", "3", exitsAtLimit.toString()), "", 17, "")
  }

  private fun assertProgramResult(
      arguments: List<String>,
      input: String,
      status: Int,
      output: String,
  ) {
    val result = launch(input, arguments)
    assertEquals(status, result.status, result.err)
    assertEquals(output, result.out.trim())
    assertEquals("", result.err)
  }

  private fun assertDiagnostics(flags: List<String>, status: Int, expected: String) {
    val arguments = mutableListOf("nc", "me")
    arguments.addAll(flags)
    val standalone = launch("", arguments)
    assertEquals(status, standalone.status, standalone.err)
    val diagnostic = standalone.err.lines().filter { line -> line.isNotBlank() }.toList()
    assertTrue(diagnostic.any { it.contains(expected) }, standalone.err)
  }

  private fun source(name: String, contents: String): Path {
    val file = directory.resolve(name)
    Files.createDirectories(file.parent)
    return Files.writeString(file, contents)
  }

  private fun dump(descriptor: String): Path {
    return directory.resolve("dump-$descriptor")
  }

  private fun launch(vararg arguments: String, lineSeparator: String? = null): Result {
    return launch("", arguments.toList(), lineSeparator)
  }

  private fun launch(
      input: String,
      arguments: List<String>,
      lineSeparator: String? = null,
  ): Result {
    val bin = Path.of(System.getProperty("java.home"), "bin")
    val java = bin.resolve(if (Files.exists(bin.resolve("java.exe"))) "java.exe" else "java")
    // Redirected fixtures are UTF-8; Windows stdout/stderr can otherwise use a legacy code page.
    val command =
        mutableListOf(
            java.toString(),
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
    if (lineSeparator != null) command.add("-Dline.separator=$lineSeparator")
    command.add("-jar")
    command.add(System.getProperty("mars.cli.jar"))
    command.addAll(arguments)
    val stdout = Files.createTempFile(directory, "stdout-", ".txt")
    val stderr = Files.createTempFile(directory, "stderr-", ".txt")
    val stdin = Files.writeString(Files.createTempFile(directory, "stdin-", ".txt"), input)
    val process =
        ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectInput(stdin.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start()
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS)) { "CLI timed out: $command" }
      return Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
    } finally {
      if (process.isAlive) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
      }
    }
  }

  private data class Result(val status: Int, val out: String, val err: String)

  companion object {
    private fun phaseLines(result: Result): List<String> {
      return result.err.lines().filter { line -> line.startsWith("--------") }.toList()
    }
  }
}
