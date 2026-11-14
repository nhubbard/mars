package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64VectorRegisterTest {
    @ParameterizedTest
    @CsvSource(
        "b,7b004fde,0808080808080808", "h,7b014fde,0708070807080708",
        "w,7b024fde,0506070805060708", "d,7b034fde,0102030405060708",
    )
    fun `fill broadcasts the selected part of a GPR and aliases the scalar FPR`(
        suffix: String, encoding: String, expected: String, @TempDir directory: Path,
    ) {
        val path = Files.writeString(directory.resolve("fill.s"), $$"""
            fill.$$suffix $w31,$9
            dmfc1 $10,$f31
            dmtc1 $9,$f31
            move.v $w5,$w31
        """.trimIndent())
        TestSessions.create().use { session ->
            val word = encoding.toInt(16)
            assertNull(session.instructionSet.findByBinaryCode(word))
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(path), null, false, false)
            val instruction = program.machineList!!.first()
            assertEquals(word, instruction.binaryStatement)
            val decoded = ProgramStatement(word, instruction.address, session.instructionSet)
            assertEquals(listOf(31, 9), decoded.operands.take(2))
            assertTrue(decoded.printableBasicAssemblyStatement.contains($$"$w31"))
            val fpu = session.coprocessor1
            val first = 0x0102_0304_0506_0708L
            val broadcast = expected.toLong(16)
            session.editIntegerRegister(9, first)
            program.simulate(2)
            assertEquals(broadcast, session.registerFile.getLongValueNoNotify(10))
            assertEquals(VectorRegister(broadcast, broadcast), fpu.readVector(31))
            program.simulate(2)
            assertEquals(VectorRegister(first, broadcast), fpu.readVector(5))
            repeat(2) { session.backStepper.backStep() }
            assertEquals(VectorRegister(0, 0), fpu.readVector(5))
            assertEquals(VectorRegister(broadcast, broadcast), fpu.readVector(31))
            repeat(2) { session.backStepper.backStep() }
            assertEquals(VectorRegister(0, 0), fpu.readVector(31))
            Files.writeString(path, $$"fill.$$suffix $f31,$9\n")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(path), null, false, false) }
        }
    }

    @Test
    fun `logical vector operations apply to both halves even with overlapping operands`(@TempDir directory: Path) {
        val path = Files.writeString(directory.resolve("logic.s"), $$"""
            move.v $w31,$w3
            and.v $w31,$w31,$w5
            or.v $w31,$w31,$w5
            xor.v $w31,$w31,$w5
            nor.v $w31,$w31,$w5
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(path), null, false, false)
            assertEquals(listOf(0x78be1fd9, 0x7805ffde, 0x7825ffde, 0x7865ffde, 0x7845ffde),
                program.machineList!!.map { it.binaryStatement })
            val fpu = session.coprocessor1
            fpu.updateVector(3, VectorRegister(0xf0, 0xa5))
            fpu.updateVector(5, VectorRegister(0xff, 0x50))
            val expected = listOf(
                VectorRegister(0xf0, 0xa5),
                VectorRegister(0xf0, 0),
                VectorRegister(0xff, 0x50),
                VectorRegister(0, 0),
                VectorRegister(0xffL.inv(), 0x50L.inv()),
            )
            for (value in expected) {
                program.simulate(1)
                assertEquals(value, fpu.readVector(31))
            }
            session.backStepper.backStep()
            assertEquals(VectorRegister(0, 0), fpu.readVector(31))
            session.selectTarget(MarsTarget.MARS32)
            assertEquals(VectorRegister(0, 0), fpu.readVector(3))
        }
    }
}
