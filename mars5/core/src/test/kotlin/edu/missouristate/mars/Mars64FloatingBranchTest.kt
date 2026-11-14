package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64FloatingBranchTest {
    @ParameterizedTest
    @CsvSource("bc1eqz,453f0002,0,true", "bc1eqz,453f0002,1,false",
        "bc1nez,45bf0002,0,false", "bc1nez,45bf0002,1,true")
    fun `bit branches always execute the delay slot and backstep restores it`(
        branch: String, encoding: String, lowBit: Long, taken: Boolean, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("branch.s"), $$"""
            $$branch $f31,target
            addiu $t0,$t0,1
            addiu $t1,$t1,1
            target: nop
        """.trimIndent())
        TestSessions.create().use { session ->
            val word = encoding.toInt(16)
            assertNull(session.instructionSet.findByBinaryCode(word))
            assertNotNull(session.instructionSet.findByBinaryCode(0x45000000))
            session.selectTarget(MarsTarget.MARS64)
            assertNull(session.instructionSet.findByBinaryCode(0x45000000))
            assertNull(session.instructionSet.findByBinaryCode(0x45010000))
            assertFalse(session.settings.isDelayedBranchingEnabled)
            val program = session.assemble(listOf(source), null, false, false)
            val statement = program.machineList!!.first()
            assertEquals(word, statement.binaryStatement)
            val decoded = ProgramStatement(word, statement.address, session.instructionSet)
            assertEquals(listOf(31, 2), decoded.operands.take(2))
            session.editCoprocessor1Pair(31, Long.MIN_VALUE or lowBit)
            program.simulate(1)
            assertEquals(statement.address + 4, session.registerFile.getPC())
            assertTrue(session.delayedBranch.isTriggered)
            program.simulate(1)
            assertEquals(1, session.registerFile.getValueNoNotify(8))
            val target = statement.address + if (taken) 12 else 8
            assertEquals(target, session.registerFile.getPC())
            assertFalse(session.delayedBranch.isTriggered)

            session.backStepper.backStep()
            assertTrue(session.delayedBranch.isTriggered)
            assertEquals(0, session.registerFile.getValueNoNotify(8))
            program.simulate(1)
            assertEquals(target, session.registerFile.getPC())
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, 1L])
    fun `a jump in a floating branch delay slot is reserved on both paths`(lowBit: Long, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("slot.s"), $$"""
            bc1eqz $f31,target
            j target
            target: nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            session.editCoprocessor1Pair(31, lowBit)
            program.simulate(1)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("control transfer in a delay slot"))
        }
    }
}
