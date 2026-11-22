package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.cli.grading.ConsoleMemoryCheck
import java.nio.charset.StandardCharsets.UTF_8
import java.util.List.copyOf

/** The same data-only public case contract used by the book's existing-DSL generator. */
internal object MarsConsoleCaseData {
    const val MAX_BYTES = 1024 * 1024
    private val nonPrintable = Regex("[\\p{C}\\p{Z}]")

    fun parse(bytes: ByteArray): List<ConsoleCheckCase> {
        val root = MarsLessonJson.parse(bytes, MAX_BYTES)
        val version = root.get("formatVersion")?.toString()
        require(root.keySet() == setOf("formatVersion", "cases") && version in setOf("1", "2")) {
            "Unsupported console-case format or fields"
        }
        val rows = root.get("cases")
        require(rows is JsonArray && rows.size() in 1..256) { "Declare one through 256 console cases" }
        val cases = rows.map {
            val fields = setOf("name", "input", "stdout", "instructionLimit")
            require(it is JsonObject && (it.keySet() == fields || (version == "2" && it.keySet() == fields + "memory"))) {
                "Unsupported or missing console-case fields"
            }
            val name = text(it, "name")
            require(name.isNotBlank() && name.codePointCount(0, name.length) <= 128 &&
                !nonPrintable.containsMatchIn(name.replace(" ", ""))) { "Invalid console-case name" }
            val limit = it.get("instructionLimit")
            require(limit is JsonPrimitive && limit.isNumber && Regex("[1-9][0-9]{0,6}").matches(limit.asString)) {
                "Expected a positive integer instruction limit"
            }
            val instructions = limit.asLong
            require(instructions <= 1_000_000) { "Console-case instruction limit exceeds 1000000" }
            val memory = if (it.has("memory")) {
                val ranges = it.get("memory")
                require(ranges is JsonArray && ranges.size() <= ConsoleMemoryCheck.MAX_RANGES) { "Declare at most 64 memory expectation ranges" }
                copyOf(ranges.map { range ->
                    require(range is JsonObject && range.keySet() == setOf("address", "bytes")) { "Expected memory address and bytes only" }
                    val address = range.get("address")
                    val bytes = range.get("bytes")
                    require(address is JsonPrimitive && address.isString && Regex("0x[0-7][0-9a-fA-F]{15}").matches(address.asString)) {
                        "Memory addresses must be nonnegative 64-bit hexadecimal strings"
                    }
                    require(bytes is JsonPrimitive && bytes.isString) { "Expected hexadecimal memory bytes" }
                    ConsoleMemoryCheck(address.asString.substring(2).toLong(16), bytes.asString)
                })
            } else emptyList()
            ConsoleCheckCase(name, text(it, "input"), text(it, "stdout"), instructions, memory)
        }
        require(cases.map { it.name }.distinct().size == cases.size) { "Console-case names must be unique" }
        require(cases.sumOf { it.instructionLimit } <= 10_000_000) { "Combined console-case limits exceed 10000000" }
        return copyOf(cases)
    }

    private fun text(row: JsonObject, field: String): String {
        val value = row.get(field)
        require(value is JsonPrimitive && value.isString) { "Expected console-case text: $field" }
        return value.asString.also {
            require(UTF_8.newEncoder().canEncode(it) && it.toByteArray(UTF_8).size <= 64 * 1024) {
                "Console-case text must be UTF-8 and at most 64 KiB"
            }
        }
    }
}
