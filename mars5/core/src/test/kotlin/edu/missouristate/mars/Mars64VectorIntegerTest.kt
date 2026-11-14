package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64VectorIntegerTest {
    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `integer operations match instruction words and operate on each lane independently`(suffix: String, @TempDir directory: Path) {
        // Results for lanes (-1, 2), (2, -1), (-1, -1), and (2, 2), with destination initially equal to the left operand.
        val cases = listOf(
            Triple("addv", 0x78051fce, listOf(1, 1, -2, 4)),
            Triple("subv", 0x78851fce, listOf(-3, 3, 0, 0)),
            Triple("mulv", 0x78051fd2, listOf(-2, -2, 1, 4)),
            Triple("maddv", 0x78851fd2, listOf(-3, 0, 0, 6)),
            Triple("msubv", 0x79051fd2, listOf(1, 4, -2, -2)),
            Triple("max_s", 0x79051fce, listOf(2, 2, -1, 2)),
            Triple("max_u", 0x79851fce, listOf(-1, -1, -1, 2)),
            Triple("min_s", 0x7a051fce, listOf(-1, -1, -1, 2)),
            Triple("min_u", 0x7a851fce, listOf(2, 2, -1, 2)),
            Triple("ceq", 0x78051fcf, listOf(0, 0, -1, -1)),
            Triple("clt_s", 0x79051fcf, listOf(-1, 0, 0, 0)),
            Triple("clt_u", 0x79851fcf, listOf(0, -1, 0, 0)),
            Triple("cle_s", 0x7a051fcf, listOf(-1, 0, -1, -1)),
            Triple("cle_u", 0x7a851fcf, listOf(0, -1, -1, -1)),
        )
        val source = Files.writeString(directory.resolve("integer.s"), buildString {
            for ((name) in cases) {
                appendLine($$"$$name.$$suffix $w31,$w3,$w5")
                appendLine($$"$$name.$$suffix $w3,$w3,$w5")
            }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val instructions = program.machineList!!
            val format = "bhwd".indexOf(suffix)
            val bits = 8 shl format
            val mask = -1L ushr (64 - bits)
            fun pack(lanes: List<Int>, start: Int): Long =
                (0 until 64 step bits).fold(0L) { result, shift ->
                    result or ((lanes[(start + shift / bits) % lanes.size].toLong() and mask) shl shift)
                }
            fun vector(lanes: List<Int>) = VectorRegister(pack(lanes, 0), pack(lanes, 1))
            val left = vector(listOf(-1, 2, -1, 2))
            val right = vector(listOf(2, -1, -1, 2))
            for ((index, case) in cases.withIndex()) {
                val (name, byteWord, results) = case
                val first = instructions[index * 2]
                val word = byteWord or (format shl 21)
                assertEquals(word, first.binaryStatement, name)
                val decoded = ProgramStatement(word, first.address, session.instructionSet)
                assertEquals(listOf(31, 3, 5), decoded.operands.take(3), name)
                val fpu = session.coprocessor1
                fpu.updateVector(3, left)
                fpu.updateVector(5, right)
                fpu.updateVector(31, left)
                program.simulate(2)
                assertEquals(vector(results), fpu.readVector(31), name)
                assertEquals(vector(results), fpu.readVector(3), name)
                repeat(2) { session.backStepper.backStep() }
                assertEquals(left, fpu.readVector(3), name)
                assertEquals(left, fpu.readVector(31), name)
                program.simulate(2)
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `vector shifts mask register counts and round the discarded bit`(suffix: String, @TempDir directory: Path) {
        val cases = listOf(
            Triple("sll", 0x78051fcd, -6L),
            Triple("sra", 0x78851fcd, -2L),
            Triple("srl", 0x79051fcd, Long.MAX_VALUE - 1),
            Triple("srar", 0x78851fd5, -1L),
            Triple("srlr", 0x79051fd5, Long.MAX_VALUE),
        )
        val unary = listOf("pcnt" to 0x7b041fde, "nloc" to 0x7b081fde, "nlzc" to 0x7b0c1fde)
        val immediate = listOf(0x78001fc9, 0x78801fc9, 0x79001fc9, 0x79001fca, 0x79801fca)
        val source = Files.writeString(directory.resolve("shift.s"), buildString {
            for ((name) in cases) {
                appendLine($$"$$name.$$suffix $w31,$w3,$w5")
                appendLine($$"$${name}i.$$suffix $w31,$w3,1")
            }
            for ((name) in unary) appendLine($$"$$name.$$suffix $w31,$w3")
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val instructions = program.machineList!!
            val format = "bhwd".indexOf(suffix)
            val bits = 8 shl format
            val mask = -1L ushr (64 - bits)
            fun repeat(value: Long): Long =
                (0 until 64 step bits).fold(0L) { result, shift -> result or ((value and mask) shl shift) }
            val fpu = session.coprocessor1
            fpu.updateVector(3, VectorRegister(repeat(-3), repeat(-3)))
            fpu.updateVector(5, VectorRegister(repeat(bits + 1L), repeat(bits.toLong())))
            val sizeField = listOf(0x700000, 0x600000, 0x400000, 0)[format]
            for ((index, case) in cases.withIndex()) {
                val (name, word, widestResult) = case
                val expected = if (name == "srl" || name == "srlr") (mask ushr 1) - if (name == "srl") 1 else 0
                else widestResult
                assertEquals(word or (format shl 21), instructions[2 * index].binaryStatement)
                assertEquals(immediate[index] or sizeField or (1 shl 16), instructions[2 * index + 1].binaryStatement)
                program.simulate(1)
                assertEquals(VectorRegister(repeat(expected), repeat(-3)), fpu.readVector(31), name)
                program.simulate(1)
                assertEquals(VectorRegister(repeat(expected), repeat(expected)), fpu.readVector(31), name)
            }
            val counts = listOf(bits - 1, bits - 2, 0)
            for ((index, pair) in unary.withIndex()) {
                val (name, word) = pair
                assertEquals(word or (format shl 16), instructions[10 + index].binaryStatement)
                program.simulate(1)
                assertEquals(VectorRegister(repeat(counts[index].toLong()), repeat(counts[index].toLong())), fpu.readVector(31), name)
            }
            Files.writeString(source, $$"slli.$$suffix $w31,$w3,$$bits")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(source), null, false, false) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `immediate operations sign extend only signed fields`(suffix: String, @TempDir directory: Path) {
        val cases = listOf(
            Triple("addvi", 0x787f1fc6, 15L), Triple("subvi", 0x78ff1fc6, -47L),
            Triple("maxi_s", 0x79701fc6, -16L), Triple("maxi_u", 0x79ff1fc6, -16L),
            Triple("mini_s", 0x7a701fc6, -16L), Triple("mini_u", 0x7aff1fc6, 31L),
            Triple("ceqi", 0x78701fc7, -1L),
            Triple("clti_s", 0x79701fc7, 0L), Triple("clti_u", 0x79ff1fc7, 0L),
            Triple("clei_s", 0x7a701fc7, -1L), Triple("clei_u", 0x7aff1fc7, 0L),
        )
        val signed = setOf("maxi_s", "mini_s", "ceqi", "clti_s", "clei_s")
        val source = Files.writeString(directory.resolve("immediate.s"), buildString {
            for ((name) in cases) {
                val value = if (name in signed) -16 else 31
                appendLine($$"$$name.$$suffix $w31,$w3,$$value")
            }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val format = "bhwd".indexOf(suffix)
            val bits = 8 shl format
            val mask = -1L ushr (64 - bits)
            fun repeat(value: Long): Long =
                (0 until 64 step bits).fold(0L) { result, shift -> result or ((value and mask) shl shift) }
            session.coprocessor1.updateVector(3, VectorRegister(repeat(-16), repeat(-16)))
            for ((index, case) in cases.withIndex()) {
                val (name, word, result) = case
                val statement = program.machineList!![index]
                assertEquals(word - ((3 - format) shl 21), statement.binaryStatement, name)
                val decoded = ProgramStatement(statement.binaryStatement, statement.address, session.instructionSet)
                assertEquals(if (name in signed) -16 else 31, decoded.operands[2], name)
                program.simulate(1)
                val expected = repeat(result)
                assertEquals(VectorRegister(expected, expected), session.coprocessor1.readVector(31), name)
            }
            for ((name, value) in listOf("ceqi" to 16, "ceqi" to -17, "addvi" to 32, "addvi" to -1)) {
                Files.writeString(source, $$"$$name.$$suffix $w31,$w3,$$value")
                assertThrows(ProcessingException::class.java) { session.assemble(listOf(source), null, false, false) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `saturating and averaging arithmetic keeps intermediate precision`(suffix: String, @TempDir directory: Path) {
        val cases = listOf(
            "add_a" to 0x78051fd0, "adds_a" to 0x78851fd0, "adds_s" to 0x79051fd0, "adds_u" to 0x79851fd0,
            "ave_s" to 0x7a051fd0, "ave_u" to 0x7a851fd0, "aver_s" to 0x7b051fd0, "aver_u" to 0x7b851fd0,
            "subs_s" to 0x78051fd1, "subs_u" to 0x78851fd1, "asub_s" to 0x7a051fd1, "asub_u" to 0x7a851fd1,
        )
        val format = "bhwd".indexOf(suffix)
        val bits = 8 shl format
        val boundary = BigInteger.ONE.shiftLeft(bits - 1)
        val maximum = boundary - BigInteger.ONE
        val minimum = -boundary
        val mask = boundary.shiftLeft(1) - BigInteger.ONE
        val pairs = listOf(maximum to maximum, minimum to minimum, minimum to maximum, maximum to minimum,
            -BigInteger.ONE to BigInteger.valueOf(2), BigInteger.ZERO to -BigInteger.ONE)
        val source = Files.writeString(directory.resolve("saturation.s"), buildString {
            for ((name) in cases) repeat(pairs.size) { appendLine($$"$$name.$$suffix $w31,$w3,$w5") }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            fun broadcast(value: BigInteger): VectorRegister {
                val word = (0 until 64 step bits).fold(0L) { acc, shift -> acc or ((value and mask).toLong() shl shift) }
                return VectorRegister(word, word)
            }
            val fpu = session.coprocessor1
            for ((index, case) in cases.withIndex()) {
                val (name, word) = case
                assertEquals(word or (format shl 21), program.machineList!![index * pairs.size].binaryStatement)
                for ((a, b) in pairs) {
                    fpu.updateVector(3, broadcast(a))
                    fpu.updateVector(5, broadcast(b))
                    val ua = a and mask
                    val ub = b and mask
                    val expected = when (name) {
                        "add_a" -> a.abs() + b.abs()
                        "adds_a" -> minOf(a.abs() + b.abs(), maximum)
                        "adds_s" -> (a + b).coerceIn(minimum, maximum)
                        "adds_u" -> minOf(ua + ub, mask)
                        "ave_s" -> (a + b).shiftRight(1)
                        "ave_u" -> (ua + ub).shiftRight(1)
                        "aver_s" -> (a + b + BigInteger.ONE).shiftRight(1)
                        "aver_u" -> (ua + ub + BigInteger.ONE).shiftRight(1)
                        "subs_s" -> (a - b).coerceIn(minimum, maximum)
                        "subs_u" -> maxOf(ua - ub, BigInteger.ZERO)
                        "asub_s" -> (a - b).abs()
                        "asub_u" -> (ua - ub).abs()
                        else -> error(name)
                    }
                    program.simulate(1)
                    assertEquals(broadcast(expected), fpu.readVector(31), "$name: $a, $b")
                }
            }
        }
    }

}
