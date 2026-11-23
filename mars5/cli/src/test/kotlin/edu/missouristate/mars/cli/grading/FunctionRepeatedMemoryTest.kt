package edu.missouristate.mars.cli.grading

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FunctionRepeatedMemoryTest {
    @Test
    fun `repetition preserves exact byte order and detached decoded arrays`() {
        val range = FunctionMemoryCheck("samples", "00Ff3180", 5, 3)
        assertEquals(12, range.byteLength)
        assertArrayEquals(intArrayOf(0,255,49,128,0,255,49,128,0,255,49,128), range.decoded())
        range.decoded()[0] = 99
        assertEquals(0, range.decoded()[0])
        assertArrayEquals(intArrayOf(0,255), FunctionMemoryCheck("samples", "00ff").decoded())
    }

    @Test
    fun `expanded sizes count against existing per range and combined case bounds`() {
        assertEquals(1_048_576, FunctionMemoryCheck("samples", "a5", repeat = 1_048_576).byteLength)
        for (repeat in listOf(0, -1, 1_048_577, Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { FunctionMemoryCheck("samples", "00", repeat = repeat) }
        }
        assertThrows<IllegalArgumentException> { FunctionMemoryCheck("samples", "0001", repeat = 524_289) }
        val half = FunctionMemoryCheck("samples", "00", repeat = 524_288)
        functionMemory(listOf(half, half))
        assertThrows<IllegalArgumentException> { functionMemory(listOf(half, half, FunctionMemoryCheck("samples", "00"))) }
        val call = FunctionCallCheck("function", "returned", emptyMap(), mapOf("v0" to 0L), memory = listOf(half))
        FunctionCheckCase("fits", listOf(call), initialMemory = listOf(half))
        assertThrows<IllegalArgumentException> {
            FunctionCheckCase("too much", listOf(call), initialMemory = listOf(half, FunctionMemoryCheck("samples", "00")))
        }
    }
}
