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

class Mars64VectorFloatingTest {
    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `quiet and signaling comparison predicates match their encodings and IEEE relations`(
        suffix: String, @TempDir directory: Path,
    ) {
        val double = suffix == "d"
        val cases = listOf(
            Triple("af", 0x78051fda, "0000"), Triple("un", 0x78451fda, "0001"),
            Triple("eq", 0x78851fda, "0100"), Triple("ueq", 0x78c51fda, "0101"),
            Triple("lt", 0x79051fda, "1000"), Triple("ult", 0x79451fda, "1001"),
            Triple("le", 0x79851fda, "1100"), Triple("ule", 0x79c51fda, "1101"),
            Triple("or", 0x78451fdc, "1110"), Triple("une", 0x78851fdc, "1011"),
            Triple("ne", 0x78c51fdc, "1010"),
        )
        val file = Files.writeString(directory.resolve("compare.s"), buildString {
            for (prefix in listOf("fc", "fs")) for ((name) in cases) repeat(2) { appendLine($$"$$prefix$$name.$$suffix $w31,$w3,$w5") }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val fpu = session.coprocessor1
            fun value(number: Double): Long =
                if (double) number.toRawBits() else number.toFloat().toRawBits().toUInt().toLong().let { it or (it shl 32) }
            for ((signalingIndex, signaling) in listOf(false, true).withIndex()) {
                for ((index, case) in cases.withIndex()) {
                    val (name, word, expected) = case
                    val address = (signalingIndex * cases.size + index) * 2
                    val encoded = word or (if (double) 0x00200000 else 0) or (if (signaling) 0x02000000 else 0)
                    assertEquals(encoded, program.machineList!![address].binaryStatement, name)
                    val values = listOf(-1.0, -0.0, 3.0, Double.NaN)
                    for (pair in 0..1) {
                        fpu.updateVector(3, VectorRegister(value(values[pair * 2]), value(values[pair * 2 + 1])))
                        fpu.updateVector(5, VectorRegister(value(if (pair == 0) 0.0 else 2.0), value(0.0)))
                        program.simulate(1)
                        fun mask(lane: Int) = if (expected[lane] == '1') -1L else 0
                        assertEquals(VectorRegister(mask(pair * 2), mask(pair * 2 + 1)), fpu.readVector(31), name)
                        assertEquals(if (signaling && pair == 1) 0x10000 else 0, fpu.msaStatus and 0x3f000)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `enabled exceptions either trap or encode causes only in faulting lanes`(suffix: String, @TempDir directory: Path) {
        val double = suffix == "d"
        val file = Files.writeString(directory.resolve("trap.s"), $$"""
            fclass.$$suffix $w31,$w3
            fceq.$$suffix $w31,$w3,$w5
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            for (nonTrapping in listOf(false, true)) {
                val program = session.assemble(listOf(file), null, false, false)
                val fpu = session.coprocessor1
                val nan = if (double) 0x7ff0000000000001L else 0x7f8000017f800001L
                val subnormal = if (double) 1L else 0x0000000100000001L
                val classification = if (double) VectorRegister(1, 256) else VectorRegister(0x0000000100000001, 0x0000010000000100)
                fpu.updateVector(3, VectorRegister(nan, subnormal))
                fpu.updateMsaStatus(0x01000800 or if (nonTrapping) 0x40000 else 0)
                program.simulate(1)
                assertEquals(classification, fpu.readVector(31))
                if (nonTrapping) {
                    program.simulate(1)
                    val exception = if (double) 0x7ff0000000000010L else 0x7f8000107f800010L
                    assertEquals(VectorRegister(exception, -1), fpu.readVector(31))
                    assertEquals(0x01040800, fpu.msaStatus)
                } else {
                    assertThrows(ProcessingException::class.java) { program.simulate(1) }
                    assertEquals(classification, fpu.readVector(31))
                    assertEquals(0x01010800, fpu.msaStatus)
                }
                session.backStepper.backStep()
                assertEquals(classification, fpu.readVector(31))
                assertEquals(0x01000800 or if (nonTrapping) 0x40000 else 0, fpu.msaStatus)
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `extrema select numeric values and order signed zero and equal magnitudes`(suffix: String, @TempDir directory: Path) {
        val names = listOf("fmin", "fmin_a", "fmax", "fmax_a")
        val file = Files.writeString(directory.resolve("extrema.s"), names.joinToString("\n") {
            $$"$$it.$$suffix $w31,$w3,$w5"
        })
        val double = suffix == "d"
        fun bits(value: Double): Long = if (double) value.toRawBits()
            else value.toFloat().toRawBits().toUInt().toLong().let { it or (it shl 32) }
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val fpu = session.coprocessor1
            val expected = listOf(-0.0 to -2.0, -0.0 to -2.0, 0.0 to 2.0, 0.0 to 2.0)
            for ((i, pair) in expected.withIndex()) {
                assertEquals(0x7b051fdb + (i shl 22) + if (double) 0x200000 else 0,
                    program.machineList!![i].binaryStatement)
                fpu.updateVector(3, VectorRegister(bits(-0.0), bits(-2.0)))
                fpu.updateVector(5, VectorRegister(bits(0.0), bits(2.0)))
                program.simulate(1)
                assertEquals(VectorRegister(bits(pair.first), bits(pair.second)), fpu.readVector(31))
                session.backStepper.backStep()
                fpu.updateVector(3, VectorRegister(bits(Double.NaN), bits(Double.NaN)))
                val signaling = if (double) 0x7ff0000000000123L else 0x7f8001237f800123L
                fpu.updateVector(5, VectorRegister(bits(2.0), signaling))
                program.simulate(1)
                val quiet = if (double) 0x0008000000000000L else 0x0040000000400000L
                assertEquals(VectorRegister(bits(2.0), signaling or quiet), fpu.readVector(31))
                assertEquals(0x10040, fpu.msaStatus)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `vector round to integral uses MSACSR and preserves negative zero`(suffix: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("round.s"), List(4) { $$"frint.$$suffix $w31,$w3" }.joinToString("\n"))
        val double = suffix == "d"
        fun bits(value: Double): Long = if (double) value.toRawBits()
            else value.toFloat().toRawBits().toUInt().toLong().let { it or (it shl 32) }
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val word = if (double) 0x7b2d1fde else 0x7b2c1fde
            assertEquals(word, program.machineList!!.first().binaryStatement)
            val fpu = session.coprocessor1
            fpu.updateVector(3, VectorRegister(bits(-0.5), bits(2.5)))
            for ((mode, result) in listOf(-0.0 to 2.0, -0.0 to 2.0, -0.0 to 3.0, -1.0 to 2.0).withIndex()) {
                fpu.updateMsaStatus(mode)
                program.simulate(1)
                assertEquals(VectorRegister(bits(result.first), bits(result.second)), fpu.readVector(31))
                assertEquals(mode or 0x1004, fpu.msaStatus)
            }
        }
    }

}
