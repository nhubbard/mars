package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.FunctionFileReadCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionFileReadDataTest {
    private val content = """{"formatVersion":13,"cases":[{"name":"read CRC input","instructionLimit":2000000,"fileRead":{"path":"fixture.bin","bytes":"003180ff","maxRequest":7,"maxReadSize":3,"failAfterBytes":2,"firstDescriptor":19,"missing":false},"initialRegisterAddresses":{"sp":{"label":"workspace","offset":256}},"accessPolicies":[{"from":{"label":"data"},"until":{"label":"end"},"maxAccesses":100000}],"calls":[{"entry":"read_input","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"readFile":true}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun JsonObject.fixture() = row().getAsJsonObject("fileRead")
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray()).single()
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `format thirteen retains fixture configuration history capacity and explicit read invocations`() {
        val case = parse()
        assertEquals(FunctionFileReadCheck("fixture.bin", "003180ff", 7, 3, 2, 19, false), case.fileRead)
        assertTrue(case.calls.single().readFile)
        assertEquals(2_000_000L, case.instructionLimit)
        assertEquals(100_000, case.accessPolicies.single().maxAccesses)
        assertEquals(256, case.initialRegisterAddresses.getValue("sp").offset)
        val defaults = parse { for (field in listOf("maxReadSize", "failAfterBytes", "firstDescriptor", "missing")) fixture().remove(field) }.fileRead!!
        assertEquals(1_048_576, defaults.maxReadSize)
        assertEquals(3, defaults.firstDescriptor)
        assertFalse(defaults.missing)
        assertNull(defaults.failAfterBytes)
        val missing = parse { fixture().addProperty("bytes", ""); fixture().remove("failAfterBytes"); fixture().addProperty("missing", true) }.fileRead!!
        assertTrue(missing.missing)
        val pure = parse { row().remove("fileRead"); call().remove("readFile") }
        assertNull(pure.fileRead)
        assertFalse(pure.calls.single().readFile)
        val published = parse {
            row().getAsJsonArray("calls").add(call().deepCopy().apply { addProperty("entry", "publish"); addProperty("readFile", false) })
        }
        assertEquals(listOf(true, false), published.calls.map { it.readFile })
    }

    @Test
    fun `store-only policies are explicit versioned booleans at case and call scopes`() {
        val case = parse {
            row().getAsJsonArray("accessPolicies")[0].asJsonObject.addProperty("checkReads", false)
            call().add("accessPolicies", row().getAsJsonArray("accessPolicies").deepCopy())
        }
        assertFalse(case.accessPolicies.single().checkReads)
        assertFalse(case.calls.single().accessPolicies.single().checkReads)
        assertTrue(parse().accessPolicies.single().checkReads)
        for (bad in listOf("null", "[]", "1", "\"false\"")) reject {
            row().getAsJsonArray("accessPolicies")[0].asJsonObject.add("checkReads", JsonParser.parseString(bad))
        }
        reject {
            row().getAsJsonArray("accessPolicies")[0].asJsonObject.apply {
                addProperty("checkReads", false)
                add("readable", JsonParser.parseString("""[{"at":{"label":"data"},"length":4}]"""))
            }
        }
        for (version in 8..12) reject {
            addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000)
            row().remove("initialRegisterAddresses"); row().remove("fileRead"); call().remove("readFile")
            row().getAsJsonArray("accessPolicies")[0].asJsonObject.apply {
                remove("maxAccesses"); addProperty("checkReads", true)
            }
        }
        for (version in 9..12) reject {
            addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000)
            row().remove("initialRegisterAddresses"); row().remove("fileRead"); call().remove("readFile")
            row().remove("accessPolicies")
            call().add("accessPolicies", JsonParser.parseString("""[{"from":{"label":"data"},"until":{"label":"end"},"checkReads":true}]"""))
        }
    }

    @Test
    fun `older formats reject fixture and invocation fields rather than silently enabling files`() {
        for (version in 1..12) {
            reject {
                addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000)
                row().remove("initialRegisterAddresses"); row().remove("accessPolicies"); call().remove("readFile")
            }
            reject {
                addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000)
                row().remove("initialRegisterAddresses"); row().remove("accessPolicies"); row().remove("fileRead")
                call().addProperty("readFile", false)
            }
        }
    }

    @Test
    fun `strict fields text integers booleans and inconsistent read declarations fail import`() {
        for (bad in listOf("null", "[]", "true", "1", "\"fixture\"", "{}")) reject { row().add("fileRead", JsonParser.parseString(bad)) }
        for (field in listOf("path", "bytes", "maxRequest")) reject { fixture().remove(field) }
        reject { fixture().addProperty("writeFile", true) }
        reject { fixture().addProperty("script", "provideFiles()") }
        for (field in listOf("maxRequest", "maxReadSize", "failAfterBytes", "firstDescriptor")) {
            for (bad in listOf("null", "[]", "true", "\"3\"", "1.0", "1e2", "-1", "10000000")) reject {
                fixture().add(field, JsonParser.parseString(bad))
            }
        }
        for (bad in listOf("null", "[]", "1", "\"true\"")) {
            reject { call().add("readFile", JsonParser.parseString(bad)) }
            reject { fixture().add("missing", JsonParser.parseString(bad)) }
        }
        for (bad in listOf("", " ", "a\u0000b", "x".repeat(4097))) reject { fixture().addProperty("path", bad) }
        assertThrows<IllegalArgumentException> { MarsFunctionCaseData.parse(content.replace("fixture.bin", "\\ud800").toByteArray()) }
        for (bad in listOf("0", "not hex")) reject { fixture().addProperty("bytes", bad) }
        reject { fixture().add("bytes", JsonArray()) }
        reject { fixture().add("path", JsonArray()) }
        reject { fixture().addProperty("maxRequest", 0) }
        reject { fixture().addProperty("maxRequest", 1_048_577) }
        reject { fixture().addProperty("maxReadSize", 0) }
        reject { fixture().addProperty("maxReadSize", 1_048_577) }
        reject { fixture().addProperty("failAfterBytes", 1_048_577) }
        reject { fixture().addProperty("firstDescriptor", 2) }
        reject { fixture().addProperty("firstDescriptor", 32) }
        reject { fixture().addProperty("missing", true) }
        reject { fixture().addProperty("bytes", ""); fixture().addProperty("missing", true) }
        reject { call().addProperty("readFile", false) }
        reject { row().remove("fileRead") }
        reject { row().remove("fileRead"); call().addProperty("readFile", true) }
    }
}
