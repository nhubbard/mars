package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64VectorBitsTest {
    @ParameterizedTest
    @ValueSource(strings = ["b", "h", "w", "d"])
    fun `lane bit operations mask register indices and preserve unselected destination bits`(
        suffix: String, @TempDir directory: Path,
    ) {
        val format = "bhwd".indexOf(suffix)
        val bits = 8 shl format
        val cases = listOf("bclr", "bset", "bneg", "binsl", "binsr")
        val file = Files.writeString(directory.resolve("lane.s"), buildString {
            for (name in cases) {
                appendLine($$"$$name.$$suffix $w31,$w3,$w5")
                appendLine($$"$${name}i.$$suffix $w31,$w3,$${bits - 1}")
            }
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val fpu = session.coprocessor1
            val sign = 1L shl (bits - 1)
            val mask = -1L ushr (64 - bits)
            fun fill(value: Long) = (0 until 64 step bits).fold(0L) { acc, shift -> acc or ((value and mask) shl shift) }
            fpu.updateVector(3, VectorRegister(fill(sign or 1), fill(1)))
            fpu.updateVector(5, VectorRegister(fill(2L * bits - 1), 0))
            val original = VectorRegister(fill(2), fill(-1))
            val low = listOf(1L, sign or 1, 1L, sign or 1, sign or 1)
            val high = listOf(0L, 1L, 0L, mask xor sign, mask)
            val immediateHigh = listOf(1L, sign or 1, sign or 1, 1L, 1L)
            val sizeField = listOf(0x700000, 0x600000, 0x400000, 0)[format]
            for ((index, name) in cases.withIndex()) {
                val opcode = (index + 3) shl 23
                val vectorWord = 0x78051fcd or opcode or (format shl 21)
                val immediateWord = 0x78001fc9 or opcode or sizeField or ((bits - 1) shl 16)
                assertEquals(vectorWord, program.machineList!![index * 2].binaryStatement, name)
                assertEquals(immediateWord, program.machineList!![index * 2 + 1].binaryStatement, name)
                fpu.updateVector(31, original)
                program.simulate(1)
                assertEquals(VectorRegister(fill(low[index]), fill(high[index])), fpu.readVector(31), name)
                session.backStepper.backStep()
                assertEquals(original, fpu.readVector(31), name)
                program.simulate(2)
                assertEquals(VectorRegister(fill(low[index]), fill(immediateHigh[index])), fpu.readVector(31), name)
            }
            Files.writeString(file, $$"bseti.$$suffix $w31,$w3,$$bits")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, extended = false, warnings = false) }
        }
    }

    @Test
    fun `byte masks broadcast and vector masks merge in both halves`(@TempDir directory: Path) {
        val cases = listOf(
            Triple("andi.b", 0x780f1fc0, 0x0c to 0x03),
            Triple("ori.b", 0x790f1fc0, 0xcf to 0x3f),
            Triple("nori.b", 0x7a0f1fc0, 0x30 to 0xc0),
            Triple("xori.b", 0x7b0f1fc0, 0xc3 to 0x3c),
            Triple("bmnzi.b", 0x780f1fc1, 0xac to 0x53),
            Triple("bmzi.b", 0x790f1fc1, 0xca to 0x35),
            Triple("bseli.b", 0x7a0f1fc1, 0x4e to 0x27),
            Triple("bmnz.v", 0x78851fde, 0xac to 0x35),
            Triple("bmz.v", 0x78a51fde, 0xca to 0x53),
            Triple("bsel.v", 0x78c51fde, 0x4e to 0x72),
        )
        val file = Files.writeString(directory.resolve("mask.s"), cases.joinToString("\n") { (name) ->
            val operand = if (name.endsWith(".b")) "15" else $$"$w5"
            $$"$$name $w31,$w3,$$operand"
        })
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val fpu = session.coprocessor1
            fun fill(byte: Int) = byte.toLong() * 0x0101010101010101L
            fpu.updateVector(3, VectorRegister(fill(0xcc), fill(0x33)))
            fpu.updateVector(5, VectorRegister(fill(0x0f), fill(0xf0)))
            for ((index, case) in cases.withIndex()) {
                val (name, word, result) = case
                assertEquals(word, program.machineList!![index].binaryStatement, name)
                fpu.updateVector(31, VectorRegister(fill(0xaa), fill(0x55)))
                program.simulate(1)
                assertEquals(VectorRegister(fill(result.first), fill(result.second)), fpu.readVector(31), name)
            }
        }
    }
}
