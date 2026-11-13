package edu.missouristate.mars

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CompactControlFlowTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compact branches skip slots and preserve their call links`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("compact.s"), $$"""
            .text
            bc caller
            addiu $t0,$t0,1
            caller: balc callee
            resume: addiu $t1,$t1,1
            bc done
            addiu $t0,$t0,1
            callee: jic $ra,0
            addiu $t0,$t0,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            val base = machine.memory.textBaseAddress
            assertTrue(program.simulate(10))
            assertEquals(0, machine.registerFile.getValue(8))
            assertEquals(1, machine.registerFile.getValue(9))
            assertEquals(base + 12, machine.registerFile.getValue(31))
            repeat(4) { machine.backStepper.backStep() }
            assertEquals(base + 24, machine.registerFile.getPC())
            machine.backStepper.backStep()
            assertEquals(0, machine.registerFile.getValue(31))
        }
    }

    @Test
    fun `compact control flow in an active legacy delay slot reports an exception`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("slot.s"), ".text\nb target\nbc target\ntarget: nop\n")
        TestSessions.create().use { machine ->
            machine.settings.setDelayedBranchingEnabled(true)
            val program = machine.assemble(listOf(source))
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(10) }
            assertTrue(failure.message!!.contains("compact control transfer in a delay slot"))
        }
    }
}
