package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64VectorPermutationTest {
    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `permutations match LLVM and use the original source lanes`(suffix: String, @TempDir directory: Path) {
        val format = "bhwd".indexOf(suffix)
        val bits = 8 shl format
        val count = 128 / bits
        val left = (1..count).toList()
        val right = (33 until 33 + count).toList()
        fun alternating(a: List<Int>, b: List<Int>) = a.zip(b).flatMap { (x, y) -> listOf(x, y) }
        val cases = listOf(
            Triple("pckev", 0x79051fd4, right.filterIndexed { i, _ -> i % 2 == 0 } + left.filterIndexed { i, _ -> i % 2 == 0 }),
            Triple("pckod", 0x79851fd4, right.filterIndexed { i, _ -> i % 2 == 1 } + left.filterIndexed { i, _ -> i % 2 == 1 }),
            Triple("ilvl", 0x7a051fd4, alternating(right.takeLast(count / 2), left.takeLast(count / 2))),
            Triple("ilvr", 0x7a851fd4, alternating(right.take(count / 2), left.take(count / 2))),
            Triple("ilvev", 0x7b051fd4, alternating(right.filterIndexed { i, _ -> i % 2 == 0 }, left.filterIndexed { i, _ -> i % 2 == 0 })),
            Triple("ilvod", 0x7b851fd4, alternating(right.filterIndexed { i, _ -> i % 2 == 1 }, left.filterIndexed { i, _ -> i % 2 == 1 })),
            Triple("vshf", 0x78051fd5, listOf(left.last(), 0) + List(count - 2) { right.last() }),
        )
        val source = Files.writeString(directory.resolve("permute.s"), cases.joinToString("\n") { (name) ->
            $$"$$name.$$suffix $w31,$w3,$w5"
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val fpu = session.coprocessor1
            fpu.updateVector(3, vector(bits, left))
            fpu.updateVector(5, vector(bits, right))
            for ((index, case) in cases.withIndex()) {
                val (name, word, result) = case
                val control = vector(bits, listOf(count * 2 - 1, 0x40) + List(count - 2) { count - 1 })
                fpu.updateVector(31, control)
                val encoded = word or (format shl 21)
                val statement = program.machineList!![index]
                assertEquals(encoded, statement.binaryStatement, name)
                assertEquals(listOf(31, 3, 5), ProgramStatement(encoded, statement.address, session.instructionSet).operands.take(3))
                program.simulate(1)
                assertEquals(vector(bits, result), fpu.readVector(31), name)
                session.backStepper.backStep()
                assertEquals(control, fpu.readVector(31), name)
                program.simulate(1)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `slides shift rows of bytes and splats broadcast integer elements`(suffix: String, @TempDir directory: Path) {
        val format = "bhwd".indexOf(suffix)
        val bits = 8 shl format
        val columns = 128 / bits
        val source = Files.writeString(directory.resolve("indexed.s"), $$"""
            sld.$$suffix $w31,$w3[$9]
            sldi.$$suffix $w31,$w3[1]
            splat.$$suffix $w31,$w3[$9]
            splati.$$suffix $w31,$w3[1]
        """.trimIndent() + if (format < 3) $$"\nshf.$$suffix $w31,$w3,255" else "")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val fpu = session.coprocessor1
            val input = (1..16).toList()
            val previous = (41..56).toList()
            fpu.updateVector(3, vector(8, input))
            session.editIntegerRegister(9, columns + 1)
            val size = listOf(0, 0x20, 0x30, 0x38)[format] shl 16
            val words = listOf(
                0x78091fd4 or (format shl 21), 0x78011fd9 or size,
                0x78891fd4 or (format shl 21), 0x78411fd9 or size,
            ) + if (format < 3) listOf(0x78ff1fc2 or (format shl 24)) else emptyList()
            assertEquals(words, program.machineList!!.map { it.binaryStatement })
            val slid = input.chunked(columns).zip(previous.chunked(columns)).flatMap { (a, b) -> a.drop(1) + b.first() }
            val selected = input.drop(bits / 8).take(bits / 8)
            val broadcast = List(columns) { selected }.flatten()
            val expected = listOf(slid, slid, broadcast, broadcast) + if (format < 3) {
                listOf(input.chunked(bits / 8).chunked(4).flatMap { group -> List(4) { group.last() }.flatten() })
            } else emptyList()
            for (lanes in expected) {
                fpu.updateVector(31, vector(8, previous))
                program.simulate(1)
                assertEquals(vector(8, lanes), fpu.readVector(31))
            }
        }
    }

    private fun vector(bits: Int, values: List<Int>): VectorRegister {
        fun pack(lanes: List<Int>) = lanes.foldIndexed(0L) { i, word, lane -> word or (lane.toLong() shl (i * bits)) }
        return VectorRegister(pack(values.take(64 / bits)), pack(values.drop(64 / bits)))
    }
}
