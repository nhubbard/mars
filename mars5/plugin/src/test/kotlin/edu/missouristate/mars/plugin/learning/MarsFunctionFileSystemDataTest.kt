package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionFileSystemDataTest {
    private fun profile() = JsonParser.parseString("""{"formatVersion":20,"cases":[{"name":"virtual WAV","instructionLimit":10000000,"fileSystem":{"files":{"in.wav":"003180ff","out.wav":"6f6c64"},"writablePaths":["out.wav"],"maxReadSize":2,"maxWriteSize":1,"firstDescriptor":19,"failReadAfterBytes":3,"failWriteAfterBytes":2,"zeroWriteAfterBytes":1,"maxFileSize":4096},"calls":[{"entry":"pipeline","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"expectedFiles":{"out.wav":"0031"}}]}]}""").asJsonObject
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun JsonObject.fixture() = row().getAsJsonObject("fileSystem")
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(profile().apply(edit).toString().toByteArray()).single()
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `version twenty retains fixture controls exact results and explicit absence`() {
        val case = parse()
        val files = case.fileSystem!!
        assertEquals(mapOf("in.wav" to "003180ff","out.wav" to "6f6c64"),files.files)
        assertEquals(setOf("out.wav"),files.writablePaths)
        assertEquals(listOf(2,1,19,3,2,1,4096),listOf(files.maxReadSize,files.maxWriteSize,files.firstDescriptor,files.failReadAfterBytes,files.failWriteAfterBytes,files.zeroWriteAfterBytes,files.maxFileSize))
        assertEquals(mapOf("out.wav" to "0031"),case.calls.single().expectedFiles)
        val absent = parse { call().getAsJsonObject("expectedFiles").add("out.wav",JsonParser.parseString("null")) }
        assertEquals(setOf("out.wav"),absent.calls.single().expectedFiles!!.keys)
        assertNull(absent.calls.single().expectedFiles!!["out.wav"])
        val defaults = parse {
            for (key in listOf("maxReadSize","maxWriteSize","firstDescriptor","failReadAfterBytes","failWriteAfterBytes","zeroWriteAfterBytes","maxFileSize")) fixture().remove(key)
        }.fileSystem!!
        assertEquals(1_048_576,defaults.maxReadSize)
        assertEquals(3,defaults.firstDescriptor)
        assertNull(defaults.failReadAfterBytes)
        val readonly = parse {
            fixture().remove("writablePaths")
            call().add("expectedFiles",JsonObject())
        }
        assertEquals(emptySet<String>(),readonly.fileSystem!!.writablePaths)
        val repeated = parse {
            row().add("initialMemory",JsonParser.parseString("""[{"label":"buffer","bytes":"a5","repeat":8}]"""))
        }
        assertEquals(8,repeated.initialMemory.single().byteLength)
    }

    @Test
    fun `earlier formats reject both new fields rather than silently enabling writable files`() {
        for (version in 1..19) {
            reject { addProperty("formatVersion",version);row().addProperty("instructionLimit",1000) }
            reject { addProperty("formatVersion",version);row().addProperty("instructionLimit",1000);row().remove("fileSystem") }
        }
    }

    @Test
    fun `strict files sizes controls and complete writable results are validated`() {
        for (bad in listOf("null","[]","true","1","\"fixture\"")) {
            reject { row().add("fileSystem",JsonParser.parseString(bad)) }
            reject { call().add("expectedFiles",JsonParser.parseString(bad)) }
        }
        reject { fixture().remove("files") }
        reject { fixture().add("files",JsonObject().apply { repeat(65) { addProperty("file$it","00") } }) }
        reject { call().add("expectedFiles",JsonObject().apply { repeat(65) { addProperty("file$it","00") } }) }
        reject { fixture().addProperty("hostFiles",true) }
        reject { fixture().addProperty("maxOperations",1) }
        reject { fixture().add("writablePaths",JsonParser.parseString("[\"out.wav\",\"out.wav\"]")) }
        reject { call().getAsJsonObject("expectedFiles").remove("out.wav") }
        reject { call().getAsJsonObject("expectedFiles").addProperty("other.wav","00") }
        reject { fixture().getAsJsonObject("files").addProperty("in.wav","invalid") }
        reject { call().getAsJsonObject("expectedFiles").addProperty("out.wav",false) }
        reject { call().getAsJsonObject("expectedFiles").addProperty("out.wav","0") }
        for (key in listOf("maxReadSize","maxWriteSize","firstDescriptor","failReadAfterBytes","failWriteAfterBytes","zeroWriteAfterBytes","maxFileSize")) {
            for (bad in listOf("null","true","\"2\"","2.0","2e2","-1","10000000")) reject {
                fixture().add(key,JsonParser.parseString(bad))
            }
        }
    }
}
