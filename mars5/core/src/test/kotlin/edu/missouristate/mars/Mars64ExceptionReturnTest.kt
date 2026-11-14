package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64ExceptionReturnTest {
    @ParameterizedTest
    @CsvSource("eret,42000018,0", "eretnc,42000058,1")
    fun `exception return applies the chosen reservation policy`(mnemonic: String, word: String, success: Long, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("return.s"), $$"""
            ll $9,0($10)
            dmtc0 $11,$14
            $$mnemonic
            break
            resumed: sc $9,0($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val statements = program.machineList!!
            assertEquals(word.toInt(16), statements[2].binaryStatement)
            session.editCoprocessor0Register(STATUS, 2)
            session.editIntegerRegister(10, session.memory.dataBaseAddress)
            session.editIntegerRegister(11, statements[4].address)
            program.simulate(3)
            assertEquals(0, session.coprocessor0.getValue(STATUS))
            assertEquals(statements[4].address, session.registerFile.programCounter)
            session.backStepper.backStep()
            assertEquals(2, session.coprocessor0.getValue(STATUS))
            assertEquals(statements[2].address, session.registerFile.programCounter)
            program.simulate(2)
            assertEquals(success, session.registerFile.getLongValueNoNotify(9))
        }
    }

    @Test
    fun `a delay slot fault saves the branch address and backstep restores exception state`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("slot.s"), $$"""
            beq $zero,$zero,target
            break
            target: nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val address = program.machineList!!.first().address
            assertThrows(ProcessingException::class.java) { program.simulate(2) }
            assertEquals(address, session.coprocessor0.getValue(EPC))
            assertEquals(1, session.coprocessor0.getValue(CAUSE) ushr 31)
            session.backStepper.backStep()
            assertEquals(0, session.coprocessor0.getValue(EPC))
            assertEquals(0, session.coprocessor0.getValue(CAUSE) ushr 31)
        }
    }

    @Test
    fun `an assembled handler can resume after a fault in a delay slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("handler.s"), $$"""
            beq $zero,$zero,target
            break
            addiu $9,$zero,7
            target: nop
            .ktext 0x80000180
            dmfc0 $10,$14
            mfc0 $11,$13
            daddiu $12,$10,8
            dmtc0 $12,$14
            eret
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            assertTrue(program.simulate(20))
            assertEquals(session.memory.textBaseAddress.toLong(), session.registerFile.getLongValueNoNotify(10))
            assertEquals(1, session.registerFile.getValueNoNotify(11) ushr 31)
            assertEquals(7, session.registerFile.getValueNoNotify(9))
            assertEquals(0, session.coprocessor0.getValue(STATUS) and 2)
        }
    }

    @Test
    fun `exception return faults on a wide unmapped EPC without replacing the saved EPC`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide.s"), $$"dmtc0 $9,$14\neret")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val address = 0x1_0000_0000L + session.memory.textBaseAddress
            session.editCoprocessor0Register(STATUS, 2)
            session.editIntegerRegister(9, address)
            assertThrows(ProcessingException::class.java) { program.simulate(2) }
            assertEquals(address, session.coprocessor0.getRegister(EPC)!!.longValueNoNotify)
            assertEquals(address, session.coprocessor0.getRegister(VADDR)!!.longValueNoNotify)
        }
    }
}
