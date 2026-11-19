package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.grading.TimingCheck.Event
import edu.missouristate.mars.cli.grading.TimingCheck.Operation.SLEEP
import edu.missouristate.mars.cli.grading.TimingCheck.Operation.TIME
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TimingCheckTest {
    @Test
    fun `scripted time retains bit patterns and copied input with detached ordered history`() {
        val values = mutableListOf(0x1234567880000001L, 7, -1)
        val timing = TimingCheck(values, 6)
        values.fill(99)
        assertEquals(0x1234567880000001L, timing.currentTimeMillis())
        val prefix = timing.snapshot()
        timing.sleepMillis(Int.MAX_VALUE)
        timing.sleepMillis(0)
        assertEquals(7, timing.currentTimeMillis())
        assertEquals(-1, timing.currentTimeMillis())
        assertEquals(listOf(Event(TIME, 0x1234567880000001L)), prefix)
        assertEquals(listOf(TIME, SLEEP, SLEEP, TIME, TIME), timing.snapshot().map { it.operation })
        assertEquals(listOf(0x1234567880000001L, Int.MAX_VALUE.toLong(), 0, 7, -1), timing.snapshot().map { it.milliseconds })
        assertThrows<UnsupportedOperationException> { (prefix as MutableList<Event>).clear() }
        assertThrows<CaseFailure> { timing.currentTimeMillis() }
        assertEquals(5, timing.snapshot().size)
    }

    @Test
    fun `invalid configuration does not enable timing and configured state cannot be replaced`() {
        val services = GradingTiming()
        assertFalse(services.enabled)
        assertThrows<CaseFailure> { services.currentTimeMillis() }
        assertThrows<CaseFailure> { services.sleepMillis(1) }
        for (limit in listOf(0, -1)) assertThrows<IllegalArgumentException> { services.configure(emptyList(), limit) }
        assertFalse(services.enabled)
        val timing = services.configure(emptyList(), 1)
        assertTrue(services.enabled)
        assertThrows<CaseFailure> { services.configure(listOf(17), 3) }
        assertThrows<CaseFailure> { services.sleepMillis(-1) }
        assertThrows<CaseFailure> { services.currentTimeMillis() }
        services.sleepMillis(0)
        assertEquals(listOf(Event(SLEEP, 0)), timing.snapshot())
        assertThrows<CaseFailure> { services.sleepMillis(1) }
        assertEquals(listOf(Event(SLEEP, 0)), timing.snapshot())
    }

    @Test
    fun `time event overflow never truncates history or silently consumes a reading`() {
        val timing = TimingCheck(listOf(1, 2), 1)
        assertEquals(1, timing.currentTimeMillis())
        repeat(2) { assertThrows<CaseFailure> { timing.currentTimeMillis() } }
        assertEquals(listOf(Event(TIME, 1)), timing.snapshot())
    }

    @Test
    fun `service boundary checks use dynamic low service bits and full text addresses without enabling calls`(@TempDir directory: Path) {
        val path = directory.resolve("boundary.asm")
        val source = $$"""
            .data 0x100000000
        value: .word 0
            .text 0x100400000
        main:
            li $v0, 32
            nop
            li $v0, 30
            syscall
            dla $t0, value
            li $t1, 17
            sw $t1, 0($t0)
            li $a0, 0
            dli $v0, 0x100000020
            syscall
            li $v0, 10
            syscall
        """.trimIndent()
        val assignment = Assignment(AssemblySourceSnapshot(listOf(path), mapOf(path to source)))
        assignment.case("pause before each selected service") {
            assertThrows<IllegalArgumentException> { runUntilSyscall(-1) }
            val clock = provideTiming(listOf(99))
            val trace = traceMemoryAccesses("value", 4)
            startAt("main")
            runUntilSyscall(30)
            assertTrue(clock.snapshot().isEmpty())
            expectWord("value", 0)
            assertTrue(trace.snapshot().isEmpty())
            runUntilSyscall(30)
            assertTrue(clock.snapshot().isEmpty())
            step()
            expectRegister("a0", 99)
            runUntilSyscall(32)
            expectWord("value", 17)
            assertEquals(1, trace.snapshot().size)
            assertEquals(listOf(Event(TIME, 99)), clock.snapshot())
            step()
            runToExit()
            assertEquals(listOf(Event(TIME, 99), Event(SLEEP, 0)), clock.snapshot())
        }
        assignment.case("pause does not enable time") { startAt("main"); runUntilSyscall(30); step() }
        assignment.case("missing service fails at ordinary termination") { provideTiming(listOf(99)); startAt("main"); runUntilSyscall(31) }
        assertEquals(null, assignment.results[0].failure)
        assertTrue(assignment.results[1].failure.orEmpty().contains("syscall 30 is disabled"))
        assertTrue(assignment.results[2].failure.orEmpty().contains("NORMAL_EXIT"))
    }

    @Test
    fun `actual syscalls split unsigned halves preserve case independence and never host sleep`(@TempDir directory: Path) {
        val path = directory.resolve("clock.asm")
        val source = $$"""
            .text
        main:
            li $v0, 30
            syscall
        first:
            move $s0, $a0
            move $s1, $a1
            li $a0, 2147483647
            li $v0, 32
            syscall
            li $v0, 30
            syscall
        second:
            li $v0, 10
            syscall
        sleep_only:
            li $a0, 0
            li $v0, 32
            syscall
            li $v0, 10
            syscall
        negative_sleep:
            li $a0, -1
            li $v0, 32
            syscall
        """.trimIndent()
        val assignment = Assignment(AssemblySourceSnapshot(listOf(path), mapOf(path to source)))
        repeat(2) { n ->
            assignment.case("independent clock $n") {
                val timing = provideTiming(listOf(0x1234567880000001L, -1))
                runUntil("first")
                expectRegister("a0", -2147483647)
                expectRegister("a1", 0x12345678)
                runUntil("second")
                expectRegister("s0", -2147483647)
                expectRegister("s1", 0x12345678)
                expectRegister("a0", -1)
                expectRegister("a1", -1)
                runToExit()
                assertEquals(listOf(Event(TIME, 0x1234567880000001L), Event(SLEEP, Int.MAX_VALUE.toLong()), Event(TIME, -1)), timing.snapshot())
            }
        }
        assignment.case("default time disabled") { runToExit() }
        assignment.case("default sleep disabled") { startAt("sleep_only"); runToExit() }
        assignment.case("empty clock sleep only") {
            val timing = provideTiming(emptyList())
            startAt("sleep_only"); runToExit()
            assertEquals(listOf(Event(SLEEP, 0)), timing.snapshot())
        }
        assignment.case("exhausted clock") { provideTiming(emptyList()); runToExit() }
        assignment.case("negative sleep") { provideTiming(emptyList()); startAt("negative_sleep"); runToExit() }
        assertEquals(listOf(null, null), assignment.results.take(2).map { it.failure })
        assertTrue(assignment.results[2].failure.orEmpty().contains("syscall 30 is disabled"))
        assertTrue(assignment.results[3].failure.orEmpty().contains("syscall 32 is disabled"))
        assertEquals(null, assignment.results[4].failure)
        assertTrue(assignment.results[5].failure.orEmpty().contains("readings exhausted"))
        assertTrue(assignment.results[6].failure.orEmpty().contains("Negative sleep request"))
    }
}
