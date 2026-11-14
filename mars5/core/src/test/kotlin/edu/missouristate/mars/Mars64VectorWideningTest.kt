package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64VectorWideningTest {
    @ParameterizedTest
    @ValueSource(strings = ["h", "w", "d"])
    fun `widening reads the correct half-width elements and old destination`(suffix: String, @TempDir directory: Path) {
        val format = "bhwd".indexOf(suffix)
        val bits = 8 shl format
        val narrow = bits / 2
        val limit = 1L shl narrow
        val dotUnsigned = 16L - 5 * limit
        val cases = listOf(
            Triple("hadd_s", 0x7a251fd5, 2L),
            Triple("hadd_u", 0x7aa51fd5, limit + 2),
            Triple("hsub_s", 0x7b251fd5, -8L),
            Triple("hsub_u", 0x7ba51fd5, limit - 8),
            Triple("dotp_s", 0x78251fd3, 16L),
            Triple("dotp_u", 0x78a51fd3, dotUnsigned),
            Triple("dpadd_s", 0x79251fd3, 23L),
            Triple("dpadd_u", 0x79a51fd3, 7 + dotUnsigned),
            Triple("dpsub_s", 0x7a251fd3, -9L),
            Triple("dpsub_u", 0x7aa51fd3, 7 - dotUnsigned),
        )
        val file = Files.writeString(directory.resolve("widen.s"), cases.joinToString("\n") { (name) ->
            $$"$$name.$$suffix $w31,$w3,$w5"
        })
        fun lanes(value: Long): Long {
            val mask = -1L ushr (64 - bits)
            return (0 until 64 step bits).fold(0L) { acc, shift -> acc or ((value and mask) shl shift) }
        }
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val fpu = session.coprocessor1
            val left = lanes(((limit - 3) shl narrow) or 2)
            val right = lanes(((limit - 2) shl narrow) or 5)
            fpu.updateVector(3, VectorRegister(left, left))
            fpu.updateVector(5, VectorRegister(right, right))
            val old = VectorRegister(lanes(7), lanes(7))
            for ((index, case) in cases.withIndex()) {
                val (name, word, expected) = case
                assertEquals(word + ((format - 1) shl 21), program.machineList!![index].binaryStatement, name)
                fpu.updateVector(31, old)
                program.simulate(1)
                assertEquals(VectorRegister(lanes(expected), lanes(expected)), fpu.readVector(31), name)
                session.backStepper.backStep()
                assertEquals(old, fpu.readVector(31), name)
                program.simulate(1)
            }
            Files.writeString(file, $$"dotp_s.b $w31,$w3,$w5")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, false, false) }
        }
    }
}
