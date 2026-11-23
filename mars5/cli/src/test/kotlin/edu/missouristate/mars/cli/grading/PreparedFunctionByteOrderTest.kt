package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.di.DaggerMarsComponent
import java.nio.ByteOrder
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PreparedFunctionByteOrderTest {
    @TempDir lateinit var directory: Path
    private fun machine() = DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context()
    private val source = $$"""
        .data
        constant: .word 0x12345678
        .text
        main: li $v0,10
            syscall
        returned: nop
        inspect:
            dla $t0,constant
            lwu $v0,0($t0)
            lbu $v1,0($t0)
            jrc $ra
    """.trimIndent()
    private fun image(order: ByteOrder) = machine().use { m ->
        m.memory.byteOrder = order == ByteOrder.LITTLE_ENDIAN
        val path = directory.resolve("native.asm")
        m.assembleSnapshot(AssemblySourceSnapshot(listOf(path), mapOf(path to source)), null, extended = true, warnings = false)
        m.captureAssemblyImage {}
    }
    private fun case(order: ByteOrder?) = FunctionCheckCase("native $order", List(2) {
        FunctionCallCheck("inspect", "returned", emptyMap(), mapOf("v0" to 0x12345678L,
            "v1" to if (order == ByteOrder.BIG_ENDIAN) 0x12L else 0x78L))
    }, byteOrder = order)

    @Test
    fun `native declarations are assembled separately and repeated calls retain each requested order`() {
        val little = image(ByteOrder.LITTLE_ENDIAN)
        val big = image(ByteOrder.BIG_ENDIAN)
        assertEquals(ByteOrder.LITTLE_ENDIAN, little.byteOrder)
        assertEquals(ByteOrder.BIG_ENDIAN, big.byteOrder)
        assertFalse(little.hasSameMachineImage(big) {})
        val variants = mutableMapOf(ByteOrder.BIG_ENDIAN to big)
        var acquired = 0
        val results = PreparedFunctionChecks.evaluate(little,
            listOf(case(null), case(ByteOrder.LITTLE_ENDIAN), case(ByteOrder.BIG_ENDIAN)),
            variants, { acquired++; variants.clear(); machine() })
        assertTrue(results.all { it.passed }, results.toString())
        assertEquals(3, acquired)
        assertTrue(PreparedFunctionChecks.evaluate(big, listOf(case(ByteOrder.BIG_ENDIAN)), ::machine).single().passed)
    }

    @Test
    fun `missing or mislabelled images cannot silently run in a different byte order`() {
        val little = image(ByteOrder.LITTLE_ENDIAN)
        var acquired = 0
        val results = PreparedFunctionChecks.evaluate(little, listOf(case(null), case(ByteOrder.BIG_ENDIAN)),
            { acquired++; machine() })
        assertEquals(listOf(true, false), results.map { it.passed })
        assertTrue(results.last().failure!!.contains("No assembled image"))
        assertEquals(1, acquired)
        assertThrows<IllegalArgumentException> {
            PreparedFunctionChecks.evaluate(little, listOf(case(ByteOrder.BIG_ENDIAN)),
                mapOf(ByteOrder.BIG_ENDIAN to little),
                { error("Invalid image map must reject before acquiring a machine") })
        }
    }
}
