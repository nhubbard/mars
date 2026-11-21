package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedConsoleChecksTest {
    @TempDir lateinit var directory: Path
    private fun session() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private fun image(source: String, target: MarsTarget = MarsTarget.DEFAULT): AssemblyImage = session().use { machine ->
        machine.selectTarget(target)
        val path = directory.resolve("submission.asm")
        machine.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, true, false)
        machine.captureAssemblyImage {}
    }

    @Test
    fun `prepared checks run independent machines and accept different arithmetic implementations`() {
        for (arithmetic in listOf(
            "addu \$t0, \$v0, \$v0\naddu \$t0, \$t0, \$v0\naddiu \$a0, \$t0, 1",
            "sll \$s1, \$v0, 1\naddu \$s1, \$v0, \$s1\naddiu \$a0, \$s1, 1",
        )) {
            val program = image(".text\nli \$v0,5\nsyscall\n$arithmetic\nli \$v0,1\nsyscall\nli \$v0,10\nsyscall\n")
            val machines = mutableListOf<MarsContext>()
            val cases = listOf(20, 0, -3, -1000, 1000).map { ConsoleCheckCase("input $it", "$it\n", "${3 * it + 1}", 100) }
            val results = PreparedConsoleChecks.evaluate(program, cases, { session().also(machines::add) })
            assertEquals(cases.map { it.name }, results.map { it.name })
            assertTrue(results.all { it.passed })
            assertEquals(5, machines.toSet().size)
            for (machine in machines) assertThrows<IllegalStateException> { machine.claimStoppedSession() }
        }
    }

    @Test
    fun `loading restores data and registers independently for every case on both targets`() {
        val source = $$"""
            .data
        counter: .word 0
            .text
            lw $t0,counter
            addu $t0,$t0,$s0
            addiu $t0,$t0,1
            sw $t0,counter
            move $a0,$t0
            li $s0,99
            li $v0,1
            syscall
            li $v0,10
            syscall
        """.trimIndent()
        for (target in MarsTarget.entries) {
            val results = PreparedConsoleChecks.evaluate(image(source, target),
                listOf(ConsoleCheckCase("first", "", "1"), ConsoleCheckCase("second", "", "1")), ::session)
            assertTrue(results.all { it.passed })
        }
    }

    @Test
    fun `failures retain names and explain mismatched output and execution limits`() {
        val constant = image(".text\nli \$a0,41\nli \$v0,1\nsyscall\nli \$v0,10\nsyscall\n")
        val results = PreparedConsoleChecks.evaluate(constant, listOf(
            ConsoleCheckCase("positive", "20\n", "61"), ConsoleCheckCase("constant matches once", "", "41")), ::session)
        assertFalse(results.first().passed)
        assertTrue(results.first().failure!!.contains("Expected output \"61\", got \"41\""))
        assertTrue(results.last().passed)
        val loop = image(".text\nagain: b again\nnop\n")
        val limited = PreparedConsoleChecks.evaluate(loop, listOf(ConsoleCheckCase("limit", "", "", 20)), ::session)
        assertTrue(limited.single().failure!!.contains("INSTRUCTION_LIMIT"))
    }

    @Test
    fun `console checking keeps host files disabled and bounds output`() {
        val file = image(".data\nname: .asciiz \"must-not-open-host-file\"\n.text\nla \$a0,name\nli \$a1,1\nli \$v0,13\nsyscall\nli \$v0,10\nsyscall\n")
        val denied = PreparedConsoleChecks.evaluate(file, listOf(ConsoleCheckCase("files disabled", "", "")), ::session)
        assertFalse(denied.single().passed)
        val output = image(".text\nli \$a0,2147483647\nli \$v0,1\nagain: syscall\nb again\nnop\n")
        val bounded = PreparedConsoleChecks.evaluate(output, listOf(ConsoleCheckCase("bounded", "", "", 100_000)), ::session)
        assertTrue(bounded.single().failure!!.contains("64 KiB"))
    }

    @Test
    fun `case contracts and returned results are detached from caller collections`() {
        val program = image(".text\nli \$v0,10\nsyscall\n")
        val cases = mutableListOf(ConsoleCheckCase("first", "", ""), ConsoleCheckCase("second", "", ""))
        val results = PreparedConsoleChecks.evaluate(program, cases, { cases.clear(); session() })
        assertEquals(listOf("first", "second"), results.map { it.name })
        assertThrows<UnsupportedOperationException> { (results as MutableList<ConsoleCheckResult>).clear() }
    }

    @Test
    fun `invalid contracts fail before creating a session`() {
        for (case in listOf(
            { ConsoleCheckCase(" ", "", "") }, { ConsoleCheckCase("limit", "", "", 0) },
            { ConsoleCheckCase("input", "x".repeat(65_537), "") },
            { ConsoleCheckCase("output", "", "é".repeat(32_769)) },
        )) assertThrows<IllegalArgumentException> { case() }
        val program = image(".text\nli \$v0,10\nsyscall\n")
        assertThrows<IllegalArgumentException> { PreparedConsoleChecks.evaluate(program, emptyList(), { error("must not create") }) }
        val same = ConsoleCheckCase("same", "", "")
        assertThrows<IllegalArgumentException> { PreparedConsoleChecks.evaluate(program, listOf(same, same), { error("must not create") }) }
    }

    @Test
    fun `cancellation propagates before a case and closes a session during image load`() {
        val program = image(".text\nli \$v0,10\nsyscall\n")
        val machines = mutableListOf<MarsContext>()
        var checks = 0
        assertThrows<CancellationException> {
            PreparedConsoleChecks.evaluate(program, listOf(ConsoleCheckCase("cancel", "", "")),
                { session().also(machines::add) }, { if (++checks == 3) throw CancellationException("cancelled") })
        }
        assertEquals(1, machines.size)
        assertThrows<IllegalStateException> { machines.single().claimStoppedSession() }
        assertThrows<CancellationException> {
            PreparedConsoleChecks.evaluate(program, listOf(ConsoleCheckCase("cancel", "", "")),
                { error("cancel before creating") }, { throw CancellationException("cancelled") })
        }
    }
    @Test
    fun `cancellation after bounded execution propagates after closing the machine`() {
        val program = image(".text\nli \$v0,10\nsyscall\n")
        var exited = false
        lateinit var machine: MarsContext
        assertThrows<CancellationException> {
            PreparedConsoleChecks.evaluate(program, listOf(ConsoleCheckCase("cancel after exit", "", "")), {
                session().also {
                    machine = it
                    it.simulator().addListener(object : SimulatorListener {
                        override fun onStep(event: StepEvent) = Unit
                        override fun onError(event: ErrorEvent) = Unit
                        override fun onStop(event: StopEvent) {
                            if (event.reason == StopEvent.Reason.NORMAL_EXIT) exited = true
                        }
                    })
                }
            }, { if (exited) throw CancellationException("cancelled after exit") })
        }
        assertTrue(exited)
        assertThrows<IllegalStateException> { machine.claimStoppedSession() }
    }

}
