package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
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
    fun `compare branches share encodings without changing the legacy branches`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("compare.s"), $$"""
            bgezc $t0,back
            addiu $t2,$t2,1
            back: bltzc $t0,next
            addiu $t3,$t3,1
            next: bnec $t1,$t0,done
            addiu $t4,$t4,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(8, -1)
            assertTrue(program.simulate(8))
            assertEquals(listOf(1, 0, 0), (10..12).map(machine.registerFile::getValueNoNotify))
            assertEquals(0x61090001, program.machineList!![4].binaryStatement)
            assertEquals("blez", machine.instructionSet.findByBinaryCode(0x19000001)?.name)
            assertEquals("bgtz", machine.instructionSet.findByBinaryCode(0x1d000001)?.name)
            assertEquals("addi", machine.instructionSet.findByBinaryCode(0x212a0001)?.name)
            val backward = ProgramStatement(0x5908fffe, machine.memory.textBaseAddress, machine.instructionSet)
            assertEquals("bgezc", backward.instruction?.name)
            assertEquals(8, backward.operands[0])
            assertEquals(-2, backward.operands[1])
        }
    }

    @Test
    fun `compact register comparisons reject ambiguous register fields`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("ambiguous.s"), $$"""
            bgec $t1,$t1,done
            bltuc $zero,$t2,done
            bgtzc $zero,done
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertEquals(3, failure.errors()!!.generateErrorReport().lineSequence().count { "distinct nonzero registers" in it })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compact zero branches use full operands and no delay slots`(delayed: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("zero.s"), $$"""
            beqzc $t0,skip
            addiu $t2,$t2,1
            skip: bnezc $t0,done
            addiu $t3,$t3,1
            done: nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setDelayedBranchingEnabled(delayed)
            val program = machine.assemble(listOf(source))
            assertEquals(0xd9000001.toInt(), program.machineList!![0].binaryStatement)
            assertEquals(0xf9000001.toInt(), program.machineList!![2].binaryStatement)
            machine.editIntegerRegister(8, 0x1_0000_0000L)
            assertTrue(program.simulate(6))
            assertEquals(1, machine.registerFile.getValue(10))
            assertEquals(0, machine.registerFile.getValue(11))
            repeat(2) { machine.backStepper.backStep() }
            assertEquals(machine.memory.textBaseAddress + 8, machine.registerFile.getPC())
            val decoded = ProgramStatement(0xf91ffffe.toInt(), machine.memory.textBaseAddress, machine.instructionSet)
            assertEquals(-2, decoded.operands[1])
            assertEquals("jic", machine.instructionSet.findByBinaryCode(0xd8080004.toInt())?.name)
            assertEquals("jialc", machine.instructionSet.findByBinaryCode(0xf8080004.toInt())?.name)
        }
    }

    @Test
    fun `compact zero branches reject the register field reserved for indirect jumps`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("reserved.s"), $$"beqzc $zero,target\ntarget: nop\n")
        TestSessions.create().use { machine ->
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertTrue(failure.errors()!!.generateErrorReport().contains("requires a nonzero register number"))
        }
    }

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
