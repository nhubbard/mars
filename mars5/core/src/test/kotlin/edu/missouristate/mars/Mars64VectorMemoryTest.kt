package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64VectorMemoryTest {
    @ParameterizedTest
    @CsvSource(
        "b,7bf857e0,780857e4,true,0807060504030201,100f0e0d0c0b0a09",
        "b,7bf857e0,780857e4,false,0807060504030201,100f0e0d0c0b0a09",
        "h,7bfc57e1,780457e5,true,0807060504030201,100f0e0d0c0b0a09",
        "h,7bfc57e1,780457e5,false,0708050603040102,0f100d0e0b0c090a",
        "w,7bfe57e2,780257e6,true,0807060504030201,100f0e0d0c0b0a09",
        "w,7bfe57e2,780257e6,false,0506070801020304,0d0e0f10090a0b0c",
        "d,7bff57e3,780157e7,true,0807060504030201,100f0e0d0c0b0a09",
        "d,7bff57e3,780157e7,false,0102030405060708,090a0b0c0d0e0f10",
    )
    fun `vector memory transfers preserve element order with unaligned addresses`(
        suffix: String, load: String, store: String, little: Boolean, low: String, high: String, @TempDir directory: Path,
    ) {
        val path = Files.writeString(directory.resolve("memory.s"), $$"""
            ld.$$suffix $w31,-8($10)
            st.$$suffix $w31,8($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(path), null, extended = false, warnings = false)
            val memory = session.memory
            memory.byteOrder = little
            val start = memory.dataBaseAddress + 1
            session.editIntegerRegister(10, start + 8)
            repeat(16) { memory.setByte(start + it, it + 1) }
            assertEquals(listOf(load.toInt(16), store.toInt(16)), program.machineList!!.map { it.binaryStatement })
            program.machineList!!.forEach {
                val decoded = ProgramStatement(it.binaryStatement, it.address, session.instructionSet)
                assertEquals(it.operands.take(3), decoded.operands.take(3))
            }

            program.simulate(1)
            assertEquals(VectorRegister(low.toLong(16), high.toLong(16)), session.coprocessor1.readVector(31))
            val destination = start + 16
            program.simulate(1)
            assertEquals((1..16).toList(), (0 until 16).map { memory.getByte(destination + it) })
            session.backStepper.backStep()
            assertEquals(List(16) { 0 }, (0 until 16).map { memory.getByte(destination + it) })
            session.backStepper.backStep()
            assertEquals(VectorRegister(0, 0), session.coprocessor1.readVector(31))

            val width = 1 shl "bhwd".indexOf(suffix)
            Files.writeString(path, $$"ld.$$suffix $w31[$$width],$10")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(path), null, extended = false, warnings = false) }
            Files.writeString(path, $$"ld.$$suffix $w31,$${512 * width}($10)")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(path), null, extended = false, warnings = false) }
        }
    }
}
