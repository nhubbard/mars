package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsNativeExecutionPlanTest {
    private val content = """{"formatVersion":2,"introduction":"Read and run.","conclusion":"Explain both values.","steps":[{"kind":"prediction","id":"before","explanation":"Predict the low byte.","question":"How many bits?","choices":[{"id":"a","text":"Eight","feedback":"Eight low bits."},{"id":"b","text":"Sixteen","feedback":"Count the mask bits."}],"correctChoice":"a"},{"kind":"run","id":"run-150","explanation":"Run with 150.","integerInputs":[150],"stdout":"Unsigned: 150\nSigned: -106\n","success":"The two interpretations agree.","anchor":{"label":"interpret","mnemonic":"andi","occurrence":0},"hints":[{"id":"console","text":"Use the Run console."}]}]}"""
    private fun changed(change: JsonObject.() -> Unit) = JsonParser.parseString(content).asJsonObject.apply(change).toString().toByteArray()
    private fun JsonObject.run() = getAsJsonArray("steps")[1].asJsonObject
    private fun reject(change: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(changed(change)) } }

    @Test
    fun `format two preserves interleaved activity order and immutable public contracts`() {
        val plan = MarsNativeLessonPlan.parse(content.toByteArray())
        assertEquals(listOf("before", "run-150"), plan.steps.map { it.id })
        assertTrue(plan.steps.first() is MarsNativeLessonStep)
        val run = plan.steps[1] as MarsNativeRunStep
        assertEquals(listOf(150), run.integerInputs)
        assertEquals("Unsigned: 150\nSigned: -106\n", run.stdout)
        assertEquals("The two interpretations agree.", run.success)
        assertEquals(MarsLessonAnchor("interpret", "andi", 0), run.anchor)
        assertEquals(listOf(MarsLessonHint("console", "Use the Run console.")), run.hints)
        assertThrows<UnsupportedOperationException> { (run.integerInputs as MutableList).clear() }
        val boundary = MarsNativeLessonPlan.parse(changed { run().add("integerInputs", JsonArray().apply {
            add(Int.MIN_VALUE); add(0); add(Int.MAX_VALUE)
        }) }).steps[1] as MarsNativeRunStep
        assertEquals(listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE), boundary.integerInputs)
        val noInput = MarsNativeLessonPlan.parse(changed { run().add("integerInputs", JsonArray()) }).steps[1] as MarsNativeRunStep
        assertTrue(noInput.integerInputs.isEmpty())
    }

    @Test
    fun `activity tags fields and identities cannot acquire executable behavior`() {
        reject { run().addProperty("kind", "script") }
        reject { run().remove("kind") }
        reject { run().addProperty("class", "LoadMe") }
        reject { run().addProperty("action", "RunWhatever") }
        reject { run().remove("success") }
        reject { run().addProperty("id", "before") }
        reject { addProperty("formatVersion", 1) }
        reject { addProperty("formatVersion", "2") }
        reject { run().addProperty("stdout", "x".repeat(16385)) }
    }

    @Test
    fun `Run observations preserve empty and whitespace-only console output`() {
        for (output in listOf("", " ", "\n\t")) {
            val run = MarsNativeLessonPlan.parse(changed { run().addProperty("stdout", output) }).steps[1] as MarsNativeRunStep
            assertEquals(output, run.stdout)
        }
    }

    @Test
    fun `integer input contracts are bounded exact signed words`() {
        for (bad in listOf("true", "null", "\"150\"", "[]", "0.5", "1e2", "2147483648", "-2147483649", "12345678901")) {
            reject { run().add("integerInputs", JsonArray().apply { add(JsonParser.parseString(bad)) }) }
        }
        reject { run().add("integerInputs", JsonArray().apply { repeat(65) { add(it) } }) }
        val maximum = MarsNativeLessonPlan.parse(changed { run().add("integerInputs", JsonArray().apply { repeat(64) { add(it) } }) })
        assertEquals(64, (maximum.steps[1] as MarsNativeRunStep).integerInputs.size)
    }
}
