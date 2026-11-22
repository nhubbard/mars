package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.List.copyOf

internal data class MarsLessonAnchor(val label: String, val mnemonic: String, val occurrence: Int)
internal data class MarsLessonHint(val id: String, val text: String)
internal data class MarsNativeLessonStep(
    val explanation: String, val prediction: MarsPrediction,
    val anchor: MarsLessonAnchor?, val hints: List<MarsLessonHint>,
)

/** Data interpreted by the native trainer; downloaded content cannot name executable actions or scripts. */
internal data class MarsNativeLessonPlan(
    val introduction: String, val conclusion: String, val steps: List<MarsNativeLessonStep>,
) {
    companion object {
        const val MAX_BYTES = 1024 * 1024
        private val ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")

        fun parse(bytes: ByteArray): MarsNativeLessonPlan {
            require(bytes.size <= MAX_BYTES) { "Native lesson content is too large" }
            val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            val element = JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.nestingLimit = 32
                // STRICT throws on trailing top-level tokens when the next token is requested.
                readValue(reader).also { reader.peek() }
            }
            val root = element.objectValue()
            root.fields(setOf("formatVersion", "introduction", "conclusion", "steps"))
            require(root.get("formatVersion").toString() == "1") { "Unsupported native lesson format" }
            val steps = root.array("steps")
            require(steps.size() in 1..128) { "A native lesson needs 1 through 128 steps" }
            val parsed = steps.map { parseStep(it.objectValue()) }
            require(parsed.map { it.prediction.id }.distinct().size == parsed.size) { "Duplicate native lesson step ID" }
            return MarsNativeLessonPlan(root.text("introduction", 8192), root.text("conclusion", 8192), copyOf(parsed))
        }

        private fun parseStep(step: JsonObject): MarsNativeLessonStep {
            val required = setOf("id", "explanation", "question", "choices", "correctChoice")
            step.fields(required + setOf("anchor", "hints"), required)
            val id = step.identifier("id")
            val choices = step.array("choices")
            require(choices.size() in 2..6) { "A prediction needs 2 through 6 choices" }
            val parsedChoices = choices.map {
                val choice = it.objectValue()
                choice.fields(setOf("id", "text", "feedback"))
                MarsPredictionChoice(choice.identifier("id"), choice.text("text", 2048), choice.text("feedback", 8192))
            }
            require(parsedChoices.map { it.id }.distinct().size == parsedChoices.size) { "Duplicate prediction choice ID" }
            val correct = step.identifier("correctChoice")
            require(parsedChoices.any { it.id == correct }) { "The correct choice must exist" }
            val anchor = if (step.has("anchor")) parseAnchor(step.get("anchor").objectValue()) else null
            val hints = if (step.has("hints")) step.array("hints").map {
                val hint = it.objectValue()
                hint.fields(setOf("id", "text"))
                MarsLessonHint(hint.identifier("id"), hint.text("text", 8192))
            } else emptyList()
            require(hints.size <= 4 && hints.map { it.id }.distinct().size == hints.size) { "Invalid graduated hints" }
            return MarsNativeLessonStep(
                step.text("explanation", 8192),
                MarsPrediction(id, step.text("question", 2048), copyOf(parsedChoices), correct), anchor, copyOf(hints),
            )
        }

        private fun parseAnchor(anchor: JsonObject): MarsLessonAnchor {
            anchor.fields(setOf("label", "mnemonic", "occurrence"))
            val label = anchor.text("label", 128)
            val mnemonic = anchor.text("mnemonic", 32)
            require(Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(label)) { "Invalid source label anchor" }
            require(Regex("[a-z][a-z0-9.]*").matches(mnemonic)) { "Invalid instruction anchor" }
            val occurrence = anchor.get("occurrence")
            require(occurrence is JsonPrimitive && occurrence.isNumber && Regex("0|[1-9][0-9]{0,2}").matches(occurrence.asString)) {
                "Invalid anchor occurrence"
            }
            val index = occurrence.asInt
            require(index <= 127) { "Anchor occurrence exceeds lesson limit" }
            return MarsLessonAnchor(label, mnemonic, index)
        }

        private fun JsonObject.identifier(name: String) = text(name, 64).also { require(ID.matches(it)) { "Invalid lesson ID" } }
        private fun JsonElement.objectValue(): JsonObject {
            require(this is JsonObject) { "Expected a lesson object" }
            return this
        }
        private fun JsonObject.array(name: String): JsonArray {
            val value = get(name)
            require(value is JsonArray) { "Expected lesson array: $name" }
            return value
        }
        private fun JsonObject.text(name: String, maximum: Int): String {
            val value = get(name)
            require(value is JsonPrimitive && value.isString) { "Expected lesson text: $name" }
            return value.asString.also { require(it.isNotBlank() && it.length <= maximum) { "Invalid lesson text: $name" } }
        }
        private fun JsonObject.fields(allowed: Set<String>, required: Set<String> = allowed) {
            require(keySet().all { it in allowed } && keySet().containsAll(required)) { "Unsupported or missing native lesson fields" }
        }

        /** Gson's DOM accepts duplicate keys by default; reject ambiguity at every object depth. */
        private fun readValue(reader: JsonReader): JsonElement = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name)) { "Duplicate native lesson field: $name" }
                    add(name, readValue(reader))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(readValue(reader))
                reader.endArray()
            }
            // The public parser handles scalar syntax and unexpected tokens. Objects and
            // arrays stay here so nested duplicate fields cannot be silently overwritten.
            else -> JsonParser.parseReader(reader)
        }
    }
}
