package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsNativeDebugFilePlanTest {
    private val content = """{"formatVersion":9,"introduction":"Read a fixture.","conclusion":"Distinguish errors.","steps":[{"kind":"debug","id":"file","explanation":"The lesson supplies virtual input.","integerInputs":[],"source":"worked/file.asm","stops":[{"id":"return","explanation":"Inspect the status.","anchor":{"label":"done","mnemonic":"nop","occurrence":0},"registers":{"${'$'}v1":"0x0000000000000002"},"success":"The error is explicit."}],"success":"Close the descriptor.","fileRead":{"path":"fixture.bin","bytes":"313233343536373839","maxRequest":7,"maxReadSize":3,"failAfterBytes":5,"firstDescriptor":19}}]}"""
    private fun parse(change: JsonObject.() -> Unit = {}): MarsNativeDebugStep {
        val root = JsonParser.parseString(content).asJsonObject.apply(change)
        return MarsNativeLessonPlan.parse(root.toString().toByteArray()).steps.single() as MarsNativeDebugStep
    }
    private fun JsonObject.step() = getAsJsonArray("steps")[0].asJsonObject

    @Test
    fun `version nine retains source registers and immutable input definition`() {
        val step = parse()
        assertEquals(step, parse { addProperty("formatVersion", 10) })
        assertEquals("worked/file.asm", step.source)
        assertEquals(2L, step.stops.single().registers["\$v1"])
        val fixture = requireNotNull(step.fileRead)
        assertEquals("fixture.bin", fixture.path)
        assertEquals(9, fixture.byteLength)
        assertEquals(7, fixture.maxRequest)
        assertEquals(3, fixture.maxReadSize)
        assertEquals(5, fixture.failAfterBytes)
        assertEquals(19, fixture.firstDescriptor)
        assertFalse(fixture.missing)
        assertNull(parse { step().remove("fileRead") }.fileRead)
        val defaults = parse {
            step().getAsJsonObject("fileRead").apply {
                remove("maxReadSize"); remove("failAfterBytes"); remove("firstDescriptor")
            }
        }.fileRead!!
        assertEquals(1048576, defaults.maxReadSize)
        assertNull(defaults.failAfterBytes)
        assertEquals(3, defaults.firstDescriptor)
    }

    @Test
    fun `old versions cannot silently start injecting files and malformed fixtures fail closed`() {
        for (version in 1..8) assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
        assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", 12) } }
        for (field in listOf("path", "bytes", "maxRequest")) {
            assertThrows<IllegalArgumentException> { parse { step().getAsJsonObject("fileRead").remove(field) } }
        }
        assertThrows<IllegalArgumentException> { parse { step().getAsJsonObject("fileRead").addProperty("hostPath", "/tmp/input") } }
        assertThrows<IllegalArgumentException> { parse { step().getAsJsonObject("fileRead").addProperty("missing", true) } }
        assertThrows<IllegalArgumentException> { parse { step().getAsJsonObject("fileRead").addProperty("maxReadSize", 0) } }
        assertThrows<IllegalArgumentException> { parse { step().getAsJsonObject("fileRead").addProperty("firstDescriptor", 32) } }
    }
}
