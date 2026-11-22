package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.FunctionCallCheck
import edu.missouristate.mars.cli.grading.FunctionAccessCheck
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.FunctionDataAddress
import edu.missouristate.mars.cli.grading.FunctionMemoryCheck
import edu.missouristate.mars.cli.grading.FunctionNoStoreCheck
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
        val version = root.get("formatVersion").toString()
        require(version in setOf("1", "2", "3", "4")) { "Unsupported function-case format" }
        val cases = root.array("cases", 1, 256).map { value ->
            val row = value.objectValue()
            val required = setOf("name", "calls", "instructionLimit")
            val extensions = when (version) {
                "4" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack")
                "3" -> setOf("initialMemory", "noStores")
                "2" -> setOf("initialMemory")
                else -> emptySet()
            }
            row.fields(required + setOf("initialRegisters", "preservedRegisters", "helpers", "stackAlignment", "callerStackBytes") + extensions, required)
            val name = row.string("name", 128)
            require(name.isNotBlank() && !nonPrintable.containsMatchIn(name.replace(" ", ""))) { "Invalid function-case name" }
            val calls = row.array("calls", 1, 512).map {
                val call = it.objectValue()
                val callRequired = setOf("entry", "returnTo", "arguments", "expectedRegisters")
                call.fields(callRequired + if (version != "1") setOf("argumentAddresses", "memory") else emptySet(), callRequired)
                FunctionCallCheck(call.string("entry", 128), call.string("returnTo", 128),
                    registers(call, "arguments"), registers(call, "expectedRegisters"),
                    addresses(call), memory(call, "memory"))
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
                if (row.has("callerStackBytes")) row.number("callerStackBytes") else 32,
                memory(row, "initialMemory"), protectedRegions(row), accessBounds(row), row.callerStackProtection())
        }
        require(cases.map { it.name }.distinct().size == cases.size) { "Function-case names must be unique" }
        require(cases.sumOf { it.calls.size.toLong() } <= 4096 && cases.sumOf { it.instructionLimit } <= 10_000_000) {
            "Combined function cases exceed the call or instruction budget"
        }
        return copyOf(cases)
    }

    private fun accessBounds(row: JsonObject): List<FunctionAccessCheck> {
        if (!row.has("accessBounds")) return emptyList()
        return copyOf(row.array("accessBounds", 0, 64).map {
            val region = it.objectValue()
            val required = setOf("label", "length", "permittedOffset", "permittedLength")
            region.fields(required + "offset", required)
            FunctionAccessCheck(region.string("label", 128), region.number("length"),
                region.offset("permittedOffset"), region.number("permittedLength"), region.offset())
        })
    }

    private fun JsonObject.callerStackProtection(): Boolean {
        if (!has("protectCallerStack")) return false
        val value = get("protectCallerStack")
        require(value is JsonPrimitive && value.isBoolean) { "Expected boolean caller stack protection" }
        return value.asBoolean
    }

    private fun protectedRegions(row: JsonObject): List<FunctionNoStoreCheck> {
        if (!row.has("noStores")) return emptyList()
        return copyOf(row.array("noStores", 0, 64).map {
            val region = it.objectValue()
            region.fields(setOf("label", "length", "offset"), setOf("label", "length"))
            FunctionNoStoreCheck(region.string("label", 128), region.number("length"), region.offset())
        })
    }

    private fun addresses(row: JsonObject): Map<String, FunctionDataAddress> {
        if (!row.has("argumentAddresses")) return emptyMap()
        val values = row.get("argumentAddresses").objectValue()
        require(values.size() <= 32) { "Declare at most 32 data-address arguments" }
        return copyMap(values.entrySet().associate { (name, value) ->
            require(name.length <= 32) { "Register name is too long" }
            val address = value.objectValue()
            address.fields(setOf("label", "offset"), setOf("label"))
            name to FunctionDataAddress(address.string("label", 128), address.offset())
        })
    }

    private fun memory(row: JsonObject, field: String): List<FunctionMemoryCheck> {
        if (!row.has(field)) return emptyList()
        return copyOf(row.array(field, 0, 64).map {
            val range = it.objectValue()
            range.fields(setOf("label", "bytes", "offset"), setOf("label", "bytes"))
            FunctionMemoryCheck(range.string("label", 128), range.string("bytes", 2 * MAX_BYTES), range.offset())
        })
    }

    private fun JsonObject.offset(field: String = "offset"): Int {
        if (!has(field)) return 0
        val value = get(field)
        require(value is JsonPrimitive && value.isNumber && Regex("0|-?[1-9][0-9]{0,6}").matches(value.asString)) {
            "Expected an exact bounded byte offset"
        }
        return value.asInt
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
