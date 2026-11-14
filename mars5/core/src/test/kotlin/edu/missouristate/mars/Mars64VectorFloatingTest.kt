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

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `float to integer conversions round saturate and aggregate lane causes`(suffix: String, @TempDir directory: Path) {
        val double = suffix == "d"
        val cases = listOf("ftint_s" to 0x7b381fde, "ftint_u" to 0x7b3a1fde,
            "ftrunc_s" to 0x7b221fde, "ftrunc_u" to 0x7b241fde)
        val file = Files.writeString(directory.resolve("integer-convert.s"), buildString {
            for ((name) in cases) repeat(3) { appendLine($$"$$name.$$suffix $w31,$w3") }
        })
        fun floatBits(value: Double): Long = if (double) value.toRawBits()
            else value.toFloat().toRawBits().toUInt().toLong().let { it or (it shl 32) }
        fun intBits(value: Long): Long = if (double) value else value.toUInt().toLong().let { it or (it shl 32) }
        val unsignedHigh = if (double) 1L shl 62 else 1L shl 30
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val fpu = session.coprocessor1
            for ((index, case) in cases.withIndex()) {
                val (name, word) = case
                val signed = name.endsWith("_s")
                assertEquals(word + if (double) 0x10000 else 0, program.machineList!![index * 3].binaryStatement)
                fpu.updateMsaStatus(2)
                fpu.updateVector(3, VectorRegister(floatBits(2.5), floatBits(-2.5)))
                program.simulate(1)
                val positive = if (name.startsWith("ftint")) 3L else 2L
                assertEquals(VectorRegister(intBits(positive), intBits(if (signed) -2 else 0)), fpu.readVector(31))
                assertEquals(if (signed) 0x1006 else 0x11046, fpu.msaStatus)
                fpu.updateVector(3, VectorRegister(floatBits(Double.POSITIVE_INFINITY), floatBits(Double.NaN)))
                program.simulate(1)
                val max = if (!signed) -1L else if (double) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
                assertEquals(VectorRegister(intBits(max), 0), fpu.readVector(31))
                fpu.updateVector(3, VectorRegister(floatBits(unsignedHigh.toDouble()), floatBits((unsignedHigh shl 1).toULong().toDouble())))
                program.simulate(1)
                assertEquals(VectorRegister(intBits(unsignedHigh), intBits(if (signed) max else unsignedHigh shl 1)), fpu.readVector(31))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `integer conversion preserves full unsigned input and fixed-point conversion selects a half`(
        suffix: String, @TempDir directory: Path,
    ) {
        val double = suffix == "d"
        val file = Files.writeString(directory.resolve("from-integer.s"), $$"""
            ffint_s.$$suffix $w31,$w3
            ffint_u.$$suffix $w31,$w3
            ffint_u.$$suffix $w31,$w3
            ffql.$$suffix $w31,$w5
            ffqr.$$suffix $w31,$w5
        """.trimIndent())
        fun bits(value: Double): Long = if (double) value.toRawBits()
            else value.toFloat().toRawBits().toUInt().toLong().let { it or (it shl 32) }
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val format = if (double) 1 shl 16 else 0
            val words = listOf(0x7b3c1fde, 0x7b3e1fde, 0x7b3e1fde, 0x7b342fde, 0x7b362fde)
            assertEquals(words.map { it or format }, program.machineList!!.map { it.binaryStatement })
            val fpu = session.coprocessor1
            fpu.updateVector(3, VectorRegister(-1, -1))
            program.simulate(1)
            assertEquals(VectorRegister(bits(-1.0), bits(-1.0)), fpu.readVector(31))
            program.simulate(1)
            val limit = Math.scalb(1.0, if (double) 64 else 32)
            assertEquals(VectorRegister(bits(limit), bits(limit)), fpu.readVector(31))
            assertEquals(0x1004, fpu.msaStatus)
            fpu.updateMsaStatus(1)
            program.simulate(1)
            val below = if (double) Math.nextDown(limit) else Math.nextDown(limit.toFloat()).toDouble()
            assertEquals(VectorRegister(bits(below), bits(below)), fpu.readVector(31))
            val low = if (double) 0x4000000040000000L else 0x4000400040004000L
            val high = if (double) 0x8000000080000000uL.toLong() else 0x8000800080008000uL.toLong()
            fpu.updateVector(5, VectorRegister(low, high))
            program.simulate(1)
            assertEquals(VectorRegister(bits(-1.0), bits(-1.0)), fpu.readVector(31))
            assertEquals(0x5, fpu.msaStatus)
            program.simulate(1)
            assertEquals(VectorRegister(bits(0.5), bits(0.5)), fpu.readVector(31))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `widening preserves NaN payloads and only flushes single precision input`(suffix: String, @TempDir directory: Path) {
        val double = suffix == "d"
        val file = Files.writeString(directory.resolve("widen.s"), $$"""
            fexupl.$$suffix $w31,$w3
            fexupr.$$suffix $w31,$w3
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val format = if (double) 1 shl 16 else 0
            assertEquals(listOf(0x7b301fde or format, 0x7b321fde or format), program.machineList!!.map { it.binaryStatement })
            val fpu = session.coprocessor1
            val low = if (double) 0x3f80000080000000L else 0x000000003c008000L
            val high = if (double) 0x800000017f800123uL.toLong() else 0x0000000000017c01L
            fpu.updateVector(3, VectorRegister(low, high))
            fpu.updateMsaStatus(0x1000000)
            program.simulate(1)
            val expected = if (double) VectorRegister(0x7ff8002460000000L, Long.MIN_VALUE)
                else VectorRegister(0x338000007fc02000L, 0)
            assertEquals(expected, fpu.readVector(31))
            assertEquals(if (double) 0x1011044 else 0x1010040, fpu.msaStatus)
            program.simulate(1)
            assertEquals(if (double) VectorRegister(Long.MIN_VALUE, 1.0.toBits())
                else VectorRegister(0x3f80000080000000L, 0), fpu.readVector(31))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["h", "w"])
    fun `narrowing packs target first and reports overflow and invalid lanes`(suffix: String, @TempDir directory: Path) {
        val half = suffix == "h"
        val file = Files.writeString(directory.resolve("narrow.s"), $$"""
            fexdo.$$suffix $w31,$w3,$w5
            fexdo.$$suffix $w31,$w3,$w5
            ftq.$$suffix $w31,$w3,$w5
        """.trimIndent())
        fun input(low: Double, high: Double) = if (!half) VectorRegister(low.toBits(), high.toBits())
        else VectorRegister(
            low.toFloat().toRawBits().toUInt().toLong() * 0x100000001L,
            high.toFloat().toRawBits().toUInt().toLong() * 0x100000001L,
        )
        fun packed(low: Long, high: Long): Long =
            if (half) low or (low shl 16) or (high shl 32) or (high shl 48) else low or (high shl 32)
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val format = if (half) 0 else 0x200000
            assertEquals(listOf(0x7a051fdb, 0x7a051fdb, 0x7a851fdb).map { it or format },
                program.machineList!!.map { it.binaryStatement })
            val fpu = session.coprocessor1
            val midpoint = 1.0 + Math.scalb(1.0, if (half) -11 else -24)
            val subnormal = Math.scalb(1.0, if (half) -24 else -149)
            val one = if (half) 0x3c00L else 0x3f800000L
            val infinity = if (half) 0x7c00L else 0x7f800000L
            fpu.updateVector(3, input(if (half) Float.MAX_VALUE.toDouble() else Double.MAX_VALUE, -0.0))
            fpu.updateVector(5, input(midpoint, subnormal))
            fpu.updateMsaStatus(if (half) 0x1000000 else 0)
            program.simulate(1)
            val negativeZero = if (half) 0x8000L else 0x80000000L
            assertEquals(VectorRegister(packed(one, 1), packed(infinity, negativeZero)), fpu.readVector(31))
            fpu.updateMsaStatus(2)
            program.simulate(1)
            assertEquals(VectorRegister(packed(one + 1, 1), packed(infinity, negativeZero)), fpu.readVector(31))
            fpu.updateVector(3, input(2.0, Double.NaN))
            fpu.updateVector(5, input(0.5, -1.0))
            program.simulate(1)
            val scale = if (half) 0x8000L else 0x80000000L
            assertEquals(VectorRegister(packed(scale / 2, scale), packed(scale - 1, 0)), fpu.readVector(31))
            assertEquals(0x15056, fpu.msaStatus)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["w", "d"])
    fun `arithmetic rounds once and reports each lanes exceptions`(suffix: String, @TempDir directory: Path) {
        val double = suffix == "d"
        val file = Files.writeString(directory.resolve("arithmetic.s"), $$"""
            fadd.$$suffix $w31,$w3,$w5
            fsub.$$suffix $w31,$w3,$w5
            fmul.$$suffix $w31,$w3,$w5
            fdiv.$$suffix $w31,$w3,$w5
            fmadd.$$suffix $w31,$w3,$w5
            fmsub.$$suffix $w31,$w3,$w5
        """.trimIndent())
        fun bits(value: Double): Long = if (double) value.toRawBits()
            else value.toFloat().toRawBits().toUInt().toLong() * 0x100000001L
        fun vector(low: Double, high: Double) = VectorRegister(bits(low), bits(high))
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            assertEquals(List(6) { 0x78051fdb + (it shl 22) + if (double) 0x200000 else 0 },
                program.machineList!!.map { it.binaryStatement })
            val fpu = session.coprocessor1
            val halfUlp = Math.scalb(1.0, if (double) -53 else -24)
            val next = if (double) Math.nextUp(1.0) else Math.nextUp(1f).toDouble()
            fpu.updateVector(3, vector(1.0, -1.0))
            fpu.updateVector(5, vector(halfUlp, 1.0))
            fpu.updateMsaStatus(2)
            program.simulate(1)
            assertEquals(vector(next, 0.0), fpu.readVector(31))
            assertEquals(0x1006, fpu.msaStatus)
            fpu.updateVector(3, vector(1.0, 0.0))
            fpu.updateVector(5, vector(1.0, -0.0))
            fpu.updateMsaStatus(3)
            program.simulate(1)
            assertEquals(vector(-0.0, 0.0), fpu.readVector(31))

            val maximum = if (double) Double.MAX_VALUE else Float.MAX_VALUE.toDouble()
            val minimum = Math.scalb(1.0, if (double) -1022 else -126)
            fpu.updateVector(3, vector(maximum, minimum))
            fpu.updateVector(5, vector(2.0, 0.5))
            fpu.updateMsaStatus(0x1000001)
            program.simulate(1)
            assertEquals(vector(maximum, 0.0), fpu.readVector(31))
            assertEquals(0x100701d, fpu.msaStatus)

            fpu.updateVector(3, vector(1.0, 0.0))
            fpu.updateVector(5, vector(-0.0, 0.0))
            fpu.updateMsaStatus(0)
            program.simulate(1)
            assertEquals(vector(Double.NEGATIVE_INFINITY, Double.NaN), fpu.readVector(31))
            assertEquals(0x18060, fpu.msaStatus)

            val epsilon = Math.scalb(1.0, if (double) -52 else -23)
            fpu.updateVector(3, vector(1 + epsilon, 1 + epsilon))
            fpu.updateVector(5, vector(1 - epsilon, 1 - epsilon))
            fpu.updateVector(31, vector(-1.0, -1.0))
            program.simulate(1)
            assertEquals(vector(-epsilon * epsilon, -epsilon * epsilon), fpu.readVector(31))
            fpu.updateVector(31, vector(1.0, 1.0))
            program.simulate(1)
            assertEquals(vector(epsilon * epsilon, epsilon * epsilon), fpu.readVector(31))
        }
    }

}
