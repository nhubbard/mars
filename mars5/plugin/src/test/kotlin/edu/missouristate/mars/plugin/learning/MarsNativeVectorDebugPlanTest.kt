package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.mips.hardware.VectorRegister
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsNativeVectorDebugPlanTest {
    private val content = """{"formatVersion":7,"introduction":"Inspect lanes.","conclusion":"Explain the bits.","steps":[{"kind":"debug","id":"gather","explanation":"Debug the gather.","integerInputs":[],"source":"worked/lanes.asm","stops":[{"id":"gathered","explanation":"Inspect red halfwords.","anchor":{"label":"gathered","mnemonic":"srli.h","occurrence":0},"registers":{},"vectorRegisters":{"${'$'}w4":"0x8000000000000001fffffffffffffffe"},"success":"Explain both views."}],"success":"Explain the gather."}]}"""
    private fun JsonObject.step() = getAsJsonArray("steps")[0].asJsonObject
    private fun JsonObject.stop() = step().getAsJsonArray("stops")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsNativeLessonPlan.parse(
        JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray()).steps.single() as MarsNativeDebugStep

    @Test
    fun `format seven retains all 128 bits and immutable vector-only or mixed stops`() {
        val step = parse(); val stop = step.stops.single()
        assertEquals("worked/lanes.asm", step.source)
        assertEquals(VectorRegister(-2, Long.MIN_VALUE + 1), stop.vectorRegisters.getValue("\$w4"))
        assertTrue(stop.registers.isEmpty())
        assertThrows<UnsupportedOperationException> { (stop.vectorRegisters as MutableMap).clear() }
        val mixed = parse { stop().getAsJsonObject("registers").addProperty("\$t0", "0xffffffffffffffff") }.stops.single()
        assertEquals(-1L, mixed.registers.getValue("\$t0"))
        assertEquals(stop.vectorRegisters, mixed.vectorRegisters)
        assertEquals(32, parse { stop().add("vectorRegisters", JsonObject().apply {
            repeat(32) { addProperty("\$w$it", "0x00000000000000000000000000000000") }
        }) }.stops.single().vectorRegisters.size)
    }

    @Test
    fun `previous editions reject vector fields and retain ordinary integer observations`() {
        for (version in 3..6) {
            assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
            val old = parse {
                addProperty("formatVersion", version); step().remove("source"); stop().remove("vectorRegisters")
                stop().getAsJsonObject("registers").addProperty("\$t0", "0x0000000000000001")
            }
            assertTrue(old.stops.single().vectorRegisters.isEmpty())
        }
        assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", 9) } }
        assertTrue(parse {
            stop().remove("vectorRegisters"); stop().getAsJsonObject("registers").addProperty("\$t0", "0x0000000000000000")
        }.stops.single().vectorRegisters.isEmpty())
        assertThrows<IllegalArgumentException> { parse { stop().remove("vectorRegisters") } }
        assertThrows<IllegalArgumentException> { parse { stop().add("vectorRegisters", JsonObject()) } }
    }

    @Test
    fun `format seven inherits source-selected runs and function assessments`() {
        val text = """{"formatVersion":7,"introduction":"Inspect lanes.","conclusion":"Explain it.","steps":[{"kind":"run","id":"run","source":"worked/lanes.asm","explanation":"Run it.","integerInputs":[],"stdout":"","success":"Explain it."},{"kind":"assessment","id":"function","source":"independent/pack.asm","explanation":"Write it.","caseProfile":"function.json","checkKind":"function","success":"Explain it."}]}"""
        val plan = MarsNativeLessonPlan.parse(text.toByteArray()) {
            """{"formatVersion":8,"cases":[{"name":"return","instructionLimit":100,"calls":[{"entry":"pack","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}]}]}""".toByteArray()
        }
        assertEquals("worked/lanes.asm", (plan.steps.first() as MarsNativeRunStep).source)
        assertEquals(1, (plan.steps.last() as MarsNativeAssessmentStep).functionCases.size)
    }

    @Test
    fun `strict banks and complete hexadecimal values reject expressions and malformed data`() {
        for (name in listOf("w0", "\$f0", "\$w32", "\$w-1", "\$w01", "\$W4", "pc", "", "read(0)")) {
            assertThrows<IllegalArgumentException> { parse { stop().add("vectorRegisters", JsonObject().apply {
                addProperty(name, "0x00000000000000000000000000000000")
            }) } }
        }
        for (encoded in listOf("null", "true", "0", "{}", "\"0x0\"", "\"0x0000000000000000\"",
            "\"0x000000000000000000000000000000000\"", "\"0x0000000000000000000000000000000g\"")) {
            assertThrows<IllegalArgumentException> { parse { stop().getAsJsonObject("vectorRegisters").add("\$w4", JsonParser.parseString(encoded)) } }
        }
        for (bad in listOf("null", "true", "[]", "0")) assertThrows<IllegalArgumentException> {
            parse { stop().add("vectorRegisters", JsonParser.parseString(bad)) }
        }
        assertThrows<IllegalArgumentException> { parse { stop().add("vectorRegisters", JsonObject().apply {
            repeat(33) { addProperty("\$w$it", "0x00000000000000000000000000000000") }
        }) } }
        assertThrows<IllegalArgumentException> { parse { stop().addProperty("predicate", "true") } }
    }
}
