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

}
