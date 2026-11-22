package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.ConsoleMemoryCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsMemoryCaseDataTest {
    private val profile="""{"formatVersion":2,"cases":[{"name":"red component","input":"3368601\n","stdout":"RGB: ","instructionLimit":20000,"memory":[{"address":"0x0000000010010000","bytes":"9966AA00df9b5713"}]}]}"""
    private fun bytes(change:JsonObject.()->Unit={})=JsonParser.parseString(profile).asJsonObject.apply(change).toString().toByteArray()
    private fun JsonObject.row()=getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.range()=row().getAsJsonArray("memory")[0].asJsonObject
    private fun reject(change:JsonObject.()->Unit) { assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(bytes(change)) } }

    @Test
    fun `version two retains exact address bytes and immutable range definitions while version one stays unchanged`() {
        val case=MarsConsoleCaseData.parse(bytes()).single()
        assertEquals("3368601\n",case.input); assertEquals("RGB: ",case.expectedOutput)
        assertEquals(listOf(ConsoleMemoryCheck(0x10010000,"9966AA00df9b5713")),case.memory)
        assertThrows<UnsupportedOperationException> { (case.memory as MutableList).clear() }
        assertTrue(MarsConsoleCaseData.parse(bytes { row().remove("memory") }).single().memory.isEmpty())
        assertTrue(MarsConsoleCaseData.parse(bytes { row().add("memory",JsonArray()) }).single().memory.isEmpty())
        reject { addProperty("formatVersion",1) }
        assertTrue(MarsConsoleCaseData.parse(bytes { addProperty("formatVersion",1); row().remove("memory") }).single().memory.isEmpty())
        assertEquals(Long.MAX_VALUE,MarsConsoleCaseData.parse(bytes { range().addProperty("address","0x7fffffffffffffff"); range().addProperty("bytes","ff") }).single().memory.single().address)
    }

    @Test
    fun `memory data cannot add scripts types arbitrary numeric coercion or ambiguous ranges`() {
        for (raw in listOf("null","{}","0","true")) reject { row().add("memory",JsonParser.parseString(raw)) }
        reject { row().addProperty("unexpected", "downloaded code is not a case field") }
        reject { row().getAsJsonArray("memory").set(0,JsonParser.parseString("null")) }
        reject { range().addProperty("class","AnyClass") }; reject { range().remove("address") }; reject { range().remove("bytes") }
        for (value in listOf("null","[]","true","42")) {
            reject { range().add("address",JsonParser.parseString(value)) }
            reject { range().add("bytes",JsonParser.parseString(value)) }
        }
        for (address in listOf("0x10010000","0X0000000010010000","0xffffffffffffffff","-1","0x000000001001000g"," 0x0000000010010000"))
            reject { range().addProperty("address",address) }
        for (hex in listOf("","0","0g","00 ff","+1","éé")) reject { range().addProperty("bytes",hex) }
        reject { range().addProperty("address","0x7fffffffffffffff"); range().addProperty("bytes","0000") }
        reject { row().getAsJsonArray("memory").add(range().deepCopy()) }
        val duplicate=profile.replace("\"bytes\":\"9966AA00df9b5713\"","\"bytes\":\"00\",\"bytes\":\"ff\"")
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(duplicate.toByteArray()) }
    }

    @Test
    fun `range counts are bounded independently of addresses and actual execution mapping`() {
        fun ranges(count:Int)=JsonArray().apply { repeat(count) { add(JsonObject().apply {
            addProperty("address","0x"+it.toString(16).padStart(16,'0')); addProperty("bytes","00")
        }) } }
        assertEquals(64,MarsConsoleCaseData.parse(bytes { row().add("memory",ranges(64)) }).single().memory.size)
        reject { row().add("memory",ranges(65)) }
        val allowed=MarsConsoleCaseData.parse(bytes { range().addProperty("bytes","00".repeat(4096)) }).single()
        assertEquals(4096,allowed.memory.single().byteLength)
    }
}
