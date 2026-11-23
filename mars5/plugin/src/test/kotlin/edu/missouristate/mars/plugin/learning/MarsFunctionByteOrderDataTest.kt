package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionByteOrderDataTest {
    private fun profile() = JsonParser.parseString("""{"formatVersion":21,"cases":[{"name":"native order","instructionLimit":1000,"calls":[{"entry":"inspect","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000012345678"}}]}]}""").asJsonObject
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(profile().apply(edit).toString().toByteArray()).single()

    @Test
    fun `format twenty one selects either native assembly order and omission retains the supplied image`() {
        assertNull(parse().byteOrder)
        assertEquals(ByteOrder.LITTLE_ENDIAN, parse { row().addProperty("byteOrder", "little") }.byteOrder)
        assertEquals(ByteOrder.BIG_ENDIAN, parse { row().addProperty("byteOrder", "big") }.byteOrder)
        for (version in 1..20) assertThrows<IllegalArgumentException> {
            parse { addProperty("formatVersion", version); row().addProperty("byteOrder", "little") }
        }
        for (bad in listOf("null", "true", "[]", "{}", "0", "\"native\"", "\"LITTLE\"", "\"big-endian\"")) {
            assertThrows<IllegalArgumentException> { parse { row().add("byteOrder", JsonParser.parseString(bad)) } }
        }
    }

    @Test
    fun `format twenty one retains the complete older WAV file and repeated byte contracts`() {
        ZipInputStream(javaClass.getResourceAsStream("/learning/wav-reverse-profiles-v20.zip")!!).use { zip ->
            var profiles = 0
            while (zip.nextEntry != null) {
                val original = zip.readBytes()
                val upgraded = JsonParser.parseString(original.toString(Charsets.UTF_8)).asJsonObject
                upgraded.addProperty("formatVersion", 21)
                assertEquals(MarsFunctionCaseData.parse(original), MarsFunctionCaseData.parse(upgraded.toString().toByteArray()))
                profiles++
            }
            assertEquals(7, profiles)
        }
    }
}
