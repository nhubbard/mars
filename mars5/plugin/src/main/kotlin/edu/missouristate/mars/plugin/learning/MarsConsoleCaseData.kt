package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.cli.grading.ConsoleMemoryCheck
import edu.missouristate.mars.cli.grading.ConsoleReadCheck
import edu.missouristate.mars.cli.grading.ConsoleStoreCheck
import java.nio.charset.StandardCharsets.UTF_8
import java.util.List.copyOf

/** The same data-only public case contract used by the book's existing-DSL generator. */
internal object MarsConsoleCaseData {
    const val MAX_BYTES = 1024 * 1024
    private val nonPrintable = Regex("[\\p{C}\\p{Z}]")
    private val addressPattern = Regex("0x[0-7][0-9a-fA-F]{15}")

    fun parse(bytes: ByteArray): List<ConsoleCheckCase> {
        val root = MarsLessonJson.parse(bytes, MAX_BYTES)
        val version = root.get("formatVersion")?.toString()
        require(root.keySet() == setOf("formatVersion", "cases") && version in setOf("1", "2", "3")) {
            "Unsupported console-case format or fields"
        }
        val rows = root.get("cases")
        require(rows is JsonArray && rows.size() in 1..256) { "Declare one through 256 console cases" }
        val required = setOf("name", "input", "stdout", "instructionLimit")
        val allowed = required + when (version) {
            "3" -> setOf("memory", "initialMemory", "stores", "reads")
            "2" -> setOf("memory")
            else -> emptySet()
        }
        val cases = rows.map {
            require(it is JsonObject && it.keySet().containsAll(required) && it.keySet().all { field -> field in allowed }) {
                "Unsupported or missing console-case fields"
            }
            val name = text(it, "name")
            require(name.isNotBlank() && name.codePointCount(0, name.length) <= 128 &&
                !nonPrintable.containsMatchIn(name.replace(" ", ""))) { "Invalid console-case name" }
            val instructions = integer(it, "instructionLimit").toLong()
            require(instructions <= 1_000_000) { "Console-case instruction limit exceeds 1000000" }
            ConsoleCheckCase(name, text(it, "input"), text(it, "stdout"), instructions,
                memory(it, "memory"), memory(it, "initialMemory"), stores(it), reads(it))
        }
        require(cases.map { it.name }.distinct().size == cases.size) { "Console-case names must be unique" }
        require(cases.sumOf { it.instructionLimit } <= 10_000_000) { "Combined console-case limits exceed 10000000" }
        return copyOf(cases)
    }

    private fun array(row: JsonObject, field: String): List<JsonObject> {
        if (!row.has(field)) return emptyList()
        val rows = row.get(field)
        require(rows is JsonArray && rows.size() <= 64) { "Declare at most 64 $field ranges" }
        return rows.map {
            require(it is JsonObject) { "Expected a $field range object" }
            it
        }
    }

    private fun memory(row: JsonObject, field: String): List<ConsoleMemoryCheck> = copyOf(array(row, field).map {
        require(it.keySet() == setOf("address", "bytes")) { "Expected memory address and bytes only" }
        ConsoleMemoryCheck(address(it, "address"), string(it, "bytes"))
    })

    private fun stores(row: JsonObject): List<ConsoleStoreCheck> = copyOf(array(row, "stores").map {
        val kind = string(it, "kind")
        val fields = setOf("kind", "address", "length")
        when (kind) {
            "none" -> {
                require(it.keySet() == fields) { "A no-store check needs only its region" }
                ConsoleStoreCheck(address(it, "address"), integer(it, "length"))
            }
            "bytes" -> {
                require(it.keySet() == fields + setOf("port", "bytes")) { "A byte-store check needs its region, port and sequence" }
                ConsoleStoreCheck(address(it, "address"), integer(it, "length"), address(it, "port"), string(it, "bytes"))
            }
            else -> throw IllegalArgumentException("Unknown store check kind")
        }
    })

    private fun reads(row: JsonObject): List<ConsoleReadCheck> = copyOf(array(row, "reads").map {
        require(it.keySet() == setOf("address", "length", "allowedAddress", "allowedLength")) { "A read check needs its traced and permitted regions" }
        ConsoleReadCheck(address(it, "address"), integer(it, "length"), address(it, "allowedAddress"), integer(it, "allowedLength", zero = true))
    })

    private fun address(row: JsonObject, field: String): Long {
        val value = string(row, field)
        require(addressPattern.matches(value)) { "Memory addresses must be nonnegative 64-bit hexadecimal strings" }
        return value.substring(2).toLong(16)
    }

    private fun integer(row: JsonObject, field: String, zero: Boolean = false): Int {
        val value = row.get(field)
        val pattern = if (zero) "0|[1-9][0-9]{0,6}" else "[1-9][0-9]{0,6}"
        require(value is JsonPrimitive && value.isNumber && Regex(pattern).matches(value.asString)) { "Expected an exact bounded integer: $field" }
        return value.asInt
    }

    private fun string(row: JsonObject, field: String): String {
        val value = row.get(field)
        require(value is JsonPrimitive && value.isString) { "Expected console-case text: $field" }
        return value.asString
    }

    private fun text(row: JsonObject, field: String): String = string(row, field).also {
        require(UTF_8.newEncoder().canEncode(it) && it.toByteArray(UTF_8).size <= 64 * 1024) {
            "Console-case text must be UTF-8 and at most 64 KiB"
        }
    }
}
