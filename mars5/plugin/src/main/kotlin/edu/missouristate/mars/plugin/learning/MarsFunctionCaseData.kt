package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.FunctionCallCheck
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.FunctionHelperCheck
import java.util.List.copyOf
import java.util.Map.copyOf as copyMap

/** Bounded function-call data interpreted by the existing grader, never executable content. */
internal object MarsFunctionCaseData {
    const val MAX_BYTES = 1024 * 1024
    private val bits = Regex("0x[0-9a-fA-F]{16}")
    private val integer = Regex("0|[1-9][0-9]{0,6}")
    private val nonPrintable = Regex("[\\p{C}\\p{Z}]")

    fun parse(bytes: ByteArray): List<FunctionCheckCase> {
        val root = MarsLessonJson.parse(bytes, MAX_BYTES)
        root.fields(setOf("formatVersion", "cases"))
        require(root.get("formatVersion").toString() == "1") { "Unsupported function-case format" }
        val cases = root.array("cases", 1, 256).map { value ->
            val row = value.objectValue()
            val required = setOf("name", "calls", "instructionLimit")
            row.fields(required + setOf("initialRegisters", "preservedRegisters", "helpers", "stackAlignment", "callerStackBytes"), required)
            val name = row.string("name", 128)
            require(name.isNotBlank() && !nonPrintable.containsMatchIn(name.replace(" ", ""))) { "Invalid function-case name" }
            val calls = row.array("calls", 1, 512).map {
                val call = it.objectValue()
                call.fields(setOf("entry", "returnTo", "arguments", "expectedRegisters"))
                FunctionCallCheck(call.string("entry", 128), call.string("returnTo", 128),
                    registers(call, "arguments"), registers(call, "expectedRegisters"))
            }
            val helpers = if (row.has("helpers")) row.array("helpers", 0, 32).map {
                val helper = it.objectValue()
                helper.fields(setOf("from", "until"))
                FunctionHelperCheck(helper.string("from", 128), helper.string("until", 128))
            } else emptyList()
            val preserved = if (row.has("preservedRegisters")) row.array("preservedRegisters", 0, 32).map { text(it, 32) } else emptyList()
            FunctionCheckCase(name, copyOf(calls), row.number("instructionLimit").toLong(),
                if (row.has("initialRegisters")) registers(row, "initialRegisters") else emptyMap(), copyOf(preserved), copyOf(helpers),
                if (row.has("stackAlignment")) row.number("stackAlignment") else 16,
                if (row.has("callerStackBytes")) row.number("callerStackBytes") else 32)
        }
        require(cases.map { it.name }.distinct().size == cases.size) { "Function-case names must be unique" }
        require(cases.sumOf { it.calls.size.toLong() } <= 4096 && cases.sumOf { it.instructionLimit } <= 10_000_000) {
            "Combined function cases exceed the call or instruction budget"
        }
        return copyOf(cases)
    }

    private fun registers(row: JsonObject, field: String): Map<String, Long> {
        val values = row.get(field).objectValue()
        require(values.size() <= 32) { "Declare at most 32 integer registers" }
        return copyMap(values.entrySet().associate { (name, value) ->
            require(name.length <= 32) { "Register name is too long" }
            val raw = text(value, 18)
            require(bits.matches(raw)) { "Register values need exactly 64 hexadecimal bits" }
            name to raw.substring(2).toULong(16).toLong()
        })
    }

    private fun JsonObject.number(field: String): Int {
        val value = get(field)
        require(value is JsonPrimitive && value.isNumber && integer.matches(value.asString)) { "Expected an exact bounded integer: $field" }
        return value.asInt
    }

    private fun JsonObject.string(field: String, maximum: Int) = text(get(field), maximum)
    private fun text(value: JsonElement?, maximum: Int): String {
        require(value is JsonPrimitive && value.isString && value.asString.length <= maximum) { "Expected bounded function-case text" }
        return value.asString
    }

    private fun JsonElement?.objectValue(): JsonObject {
        require(this is JsonObject) { "Expected a function-case object" }
        return this
    }

    private fun JsonObject.array(field: String, minimum: Int, maximum: Int): JsonArray {
        val value = get(field)
        require(value is JsonArray && value.size() in minimum..maximum) { "Invalid function-case array: $field" }
        return value
    }

    private fun JsonObject.fields(allowed: Set<String>, required: Set<String> = allowed) {
        require(keySet().containsAll(required) && keySet().all { it in allowed }) { "Unsupported or missing function-case fields" }
    }
}
