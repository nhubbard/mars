package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/** Strict, bounded syntax shared by native lesson and public assessment data. */
internal object MarsLessonJson {
    fun parse(bytes: ByteArray, maximum: Int): JsonObject {
        require(bytes.size <= maximum) { "Native lesson content is too large" }
        val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val value = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.nestingLimit = 32
            readValue(reader).also { reader.peek() }
        }
        require(value is JsonObject) { "Expected a lesson object" }
        return value
    }

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
        else -> JsonParser.parseReader(reader)
    }
}
