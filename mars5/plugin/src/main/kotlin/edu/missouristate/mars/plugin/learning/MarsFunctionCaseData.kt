package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.FunctionCallerAccessCheck
import edu.missouristate.mars.cli.grading.FunctionAccessHistoryCheck
import edu.missouristate.mars.cli.grading.FunctionExitCheck
import edu.missouristate.mars.cli.grading.FunctionFileReadCheck
import edu.missouristate.mars.cli.grading.FunctionFileReadInvocation
import edu.missouristate.mars.cli.grading.FunctionCallCheck
import edu.missouristate.mars.cli.grading.FunctionAccessCheck
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.FunctionDataAddress
import edu.missouristate.mars.cli.grading.FunctionMemoryCheck
import edu.missouristate.mars.cli.grading.FunctionNoStoreCheck
import edu.missouristate.mars.cli.grading.FunctionHelperCheck
import edu.missouristate.mars.cli.grading.FunctionTransferCheck
import edu.missouristate.mars.cli.grading.FunctionStoreBoundsCheck
import edu.missouristate.mars.cli.grading.FunctionAddressMemoryCheck
import edu.missouristate.mars.cli.grading.FunctionInitializationCheck
import edu.missouristate.mars.cli.grading.FunctionAccessPolicyCheck
import edu.missouristate.mars.cli.grading.FunctionDataWindow
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
        require(version in setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18")) { "Unsupported function-case format" }
        val cases = root.array("cases", 1, 256).map { value ->
            val row = value.objectValue()
            val required = setOf("name", "calls", "instructionLimit")
            val extensions = when (version) {
                "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack", "transfers", "storeBounds", "noExecution", "initializedOutput", "accessPolicies")
                "7" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack", "transfers", "storeBounds", "noExecution", "initializedOutput")
                "6" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack", "transfers", "storeBounds", "noExecution")
                "5" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack", "transfers")
                "4" -> setOf("initialMemory", "noStores", "accessBounds", "protectCallerStack")
                "3" -> setOf("initialMemory", "noStores")
                "2" -> setOf("initialMemory")
                else -> emptySet()
            }
            row.fields(required + setOf("initialRegisters", "preservedRegisters", "helpers", "stackAlignment", "callerStackBytes") + extensions + (if (version in setOf("10", "11", "12", "13", "14", "15", "16", "17", "18")) setOf("initialRegisterAddresses") else emptySet()) + (if (version in setOf("13", "14", "15", "16", "17", "18")) setOf("fileRead") else emptySet()) + (if (version in setOf("14", "15", "16", "17", "18")) setOf("exit") else emptySet()), required)
            val name = row.string("name", 128)
            require(name.isNotBlank() && !nonPrintable.containsMatchIn(name.replace(" ", ""))) { "Invalid function-case name" }
            val calls = row.array("calls", 1, 512).map {
                val call = it.objectValue()
                val callRequired = setOf("entry", "returnTo", "arguments", "expectedRegisters")
                call.fields(callRequired + (if (version == "18") setOf("callerAccess") else emptySet()) + (if (version in setOf("16", "17", "18")) setOf("fileReadInvocation") else emptySet()) + (if (version in setOf("15", "16", "17", "18")) setOf("setupMemory") else emptySet()) + (if (version != "1") setOf("argumentAddresses", "memory") else emptySet()) +
                    (if (version in setOf("6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18")) setOf("expectedAddresses", "addressMemory") else emptySet()) +
                    (if (version in setOf("9", "10", "11", "12", "13", "14", "15", "16", "17", "18")) setOf("accessPolicies") else emptySet()) + (if (version in setOf("13", "14", "15", "16", "17", "18")) setOf("readFile") else emptySet()) + (if (version in setOf("14", "15", "16", "17", "18")) setOf("accessHistory") else emptySet()), callRequired)
                FunctionCallCheck(call.string("entry", 128), call.string("returnTo", 128),
                    registers(call, "arguments"), registers(call, "expectedRegisters"),
                    addresses(call), memory(call, "memory"), addresses(call, "expectedAddresses"), addressMemory(call), accessPolicies(call, version), call.boolean("readFile"), accessHistory(call), memory(call, "setupMemory"), fileReadInvocation(call), callerAccess(call))
            }
            val helpers = intervals(row, "helpers")
            val preserved = if (row.has("preservedRegisters")) row.array("preservedRegisters", 0, 32).map { text(it, 32) } else emptyList()
            val instructionLimit = row.instructionLimit(version)
            val maximum = when (version) { "17", "18" -> 40_000_000; "14", "15", "16" -> 6_000_000; "11", "12", "13" -> 3_000_000; else -> 1_000_000 }
            require(instructionLimit <= maximum) { "Function-case format $version permits at most $maximum instructions per case" }
            FunctionCheckCase(name, copyOf(calls), instructionLimit,
                if (row.has("initialRegisters")) registers(row, "initialRegisters") else emptyMap(), copyOf(preserved), copyOf(helpers),
                if (row.has("stackAlignment")) row.number("stackAlignment") else 16,
                if (row.has("callerStackBytes")) row.number("callerStackBytes") else 32,
                memory(row, "initialMemory"), protectedRegions(row), accessBounds(row), row.callerStackProtection(), transfers(row), storeBounds(row), intervals(row, "noExecution"), initializedOutput(row), accessPolicies(row, version), addresses(row, "initialRegisterAddresses"), fileRead(row, version), exit(row))
        }
        require(cases.map { it.name }.distinct().size == cases.size) { "Function-case names must be unique" }
        val profileMaximum = if (version in setOf("17", "18")) 40_000_000 else 10_000_000
        require(cases.sumOf { it.calls.size.toLong() } <= 4096 && cases.sumOf { it.instructionLimit } <= profileMaximum) {
            "Combined function cases exceed the call or instruction budget"
        }
        return copyOf(cases)
    }

    private fun exit(row: JsonObject): FunctionExitCheck? {
        if (!row.has("exit")) return null
        val exit = row.get("exit").objectValue()
        exit.fields(setOf("code", "memory"), emptySet())
        val code = if (exit.has("code")) {
            val value = exit.get("code")
            require(value is JsonPrimitive && value.isNumber && Regex("-?(0|[1-9][0-9]{0,9})").matches(value.asString)) { "Expected a signed exit code" }
            requireNotNull(value.asString.toIntOrNull()) { "Exit code exceeds its 32-bit range" }
        } else 0
        return FunctionExitCheck(code, memory(exit, "memory"))
    }

    private fun callerAccess(call: JsonObject): FunctionCallerAccessCheck? {
        if (!call.has("callerAccess")) return null
        val caller = call.get("callerAccess").objectValue()
        caller.fields(setOf("objects", "readable", "writable", "maxAccesses"), setOf("objects"))
        return FunctionCallerAccessCheck(dataWindows(caller, "objects"), dataWindows(caller, "readable"),
            dataWindows(caller, "writable"), if (caller.has("maxAccesses")) caller.number("maxAccesses") else 65_536)
    }

    private fun accessHistory(call: JsonObject): FunctionAccessHistoryCheck? {
        if (!call.has("accessHistory")) return null
        val history = call.get("accessHistory").objectValue()
        history.fields(setOf("regions", "maxAccesses"), setOf("regions"))
        val regions = history.array("regions", 1, 64).map { value ->
            val region = value.objectValue()
            region.fields(setOf("at", "length"))
            FunctionDataWindow(dataAddress(region.get("at").objectValue()), region.number("length"))
        }
        return FunctionAccessHistoryCheck(copyOf(regions), if (history.has("maxAccesses")) history.number("maxAccesses") else 65_536)
    }

    internal fun fileRead(row: JsonObject, version: String = "13"): FunctionFileReadCheck? {
        if (!row.has("fileRead")) return null
        val fixture = row.get("fileRead").objectValue()
        val required = setOf("path", "bytes", "maxRequest")
        fixture.fields(required + (if (version in setOf("16", "17", "18")) setOf("additionalFiles") else emptySet()) + setOf("maxReadSize", "failAfterBytes", "firstDescriptor", "missing"), required)
        return FunctionFileReadCheck(fixture.string("path", 4096), fixture.string("bytes", MAX_BYTES * 2), fixture.number("maxRequest"),
            if (fixture.has("maxReadSize")) fixture.number("maxReadSize") else MAX_BYTES,
            if (fixture.has("failAfterBytes")) fixture.number("failAfterBytes") else null,
            if (fixture.has("firstDescriptor")) fixture.number("firstDescriptor") else 3, fixture.boolean("missing"), additionalFiles(fixture))
    }

    private fun fileReadInvocation(call: JsonObject): FunctionFileReadInvocation? {
        if (!call.has("fileReadInvocation")) return null
        val invocation = call.get("fileReadInvocation").objectValue()
        invocation.fields(setOf("path", "maxRequest"))
        return FunctionFileReadInvocation(invocation.string("path", 4096), invocation.number("maxRequest"))
    }

    private fun additionalFiles(fixture: JsonObject): Map<String, String> {
        if (!fixture.has("additionalFiles")) return emptyMap()
        val files = fixture.get("additionalFiles").objectValue()
        require(files.size() <= 63) { "Supply at most 63 additional read-only files" }
        return copyMap(files.entrySet().associate { (path, _) -> path to files.string(path, MAX_BYTES * 2) })
    }

    private fun JsonObject.boolean(field: String, default: Boolean = false): Boolean {
        if (!has(field)) return default
        val value = get(field)
        require(value is JsonPrimitive && value.isBoolean) { "Expected a boolean: $field" }
        return value.asBoolean
    }

    private fun intervals(row: JsonObject, field: String): List<FunctionHelperCheck> {
        if (!row.has(field)) return emptyList()
        return copyOf(row.array(field, 0, 32).map {
            val helper = it.objectValue()
            helper.fields(setOf("from", "until"))
            FunctionHelperCheck(helper.string("from", 128), helper.string("until", 128))
        })
    }

    private fun initializedOutput(row: JsonObject): List<FunctionInitializationCheck> {
        if (!row.has("initializedOutput")) return emptyList()
        return copyOf(row.array("initializedOutput", 0, 64).map {
            val range = it.objectValue()
            range.fields(setOf("at", "length"))
            FunctionInitializationCheck(dataAddress(range.get("at")), range.number("length"))
        })
    }

    private fun accessPolicies(row: JsonObject, version: String): List<FunctionAccessPolicyCheck> {
        if (!row.has("accessPolicies")) return emptyList()
        return copyOf(row.array("accessPolicies", 0, 64).map {
            val policy = it.objectValue()
            policy.fields(setOf("from", "until", "readable", "writable") +
                (if (version in setOf("12", "13", "14", "15", "16", "17", "18")) setOf("maxAccesses") else emptySet()) +
                (if (version in setOf("13", "14", "15", "16", "17", "18")) setOf("checkReads") else emptySet()), setOf("from", "until"))
            FunctionAccessPolicyCheck(dataAddress(policy.get("from")), dataAddress(policy.get("until")),
                dataWindows(policy, "readable"), dataWindows(policy, "writable"), if (policy.has("maxAccesses")) policy.number("maxAccesses") else 65_536,
                policy.boolean("checkReads", default = true))
        })
    }

    private fun dataWindows(row: JsonObject, field: String): List<FunctionDataWindow> {
        if (!row.has(field)) return emptyList()
        return copyOf(row.array(field, 0, 64).map {
            val window = it.objectValue()
            window.fields(setOf("at", "length"))
            FunctionDataWindow(dataAddress(window.get("at")), window.number("length"))
        })
    }

    private fun addressMemory(call: JsonObject): List<FunctionAddressMemoryCheck> {
        if (!call.has("addressMemory")) return emptyList()
        return copyOf(call.array("addressMemory", 0, 64).map {
            val region = it.objectValue()
            region.fields(setOf("at", "value"))
            FunctionAddressMemoryCheck(dataAddress(region.get("at")), dataAddress(region.get("value")))
        })
    }

    private fun storeBounds(row: JsonObject): List<FunctionStoreBoundsCheck> {
        if (!row.has("storeBounds")) return emptyList()
        return copyOf(row.array("storeBounds", 0, 64).map {
            val range = it.objectValue()
            val required = setOf("from", "until")
            range.fields(required + setOf("permittedFrom", "permittedLength"), required)
            FunctionStoreBoundsCheck(dataAddress(range.get("from")), dataAddress(range.get("until")),
                if (range.has("permittedFrom")) dataAddress(range.get("permittedFrom")) else null,
                if (range.has("permittedLength")) range.number("permittedLength") else 0)
        })
    }

    private fun transfers(row: JsonObject): List<FunctionTransferCheck> {
        if (!row.has("transfers")) return emptyList()
        return copyOf(row.array("transfers", 0, 32).map {
            val transfer = it.objectValue()
            val required = setOf("source", "destination", "destinationIndices")
            transfer.fields(required + "elementBytes", required)
            val indices = transfer.array("destinationIndices", 0, 65536).map { index ->
                require(index is JsonPrimitive && index.isNumber && integer.matches(index.asString)) {
                    "Expected an exact nonnegative transfer destination index"
                }
                index.asInt
            }
            FunctionTransferCheck(dataAddress(transfer.get("source")), dataAddress(transfer.get("destination")), copyOf(indices),
                if (transfer.has("elementBytes")) transfer.number("elementBytes") else 4)
        })
    }

    private fun dataAddress(value: JsonElement?): FunctionDataAddress {
        val address = value.objectValue()
        address.fields(setOf("label", "offset"), setOf("label"))
        return FunctionDataAddress(address.string("label", 128), address.offset())
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

    private fun addresses(row: JsonObject, field: String = "argumentAddresses"): Map<String, FunctionDataAddress> {
        if (!row.has(field)) return emptyMap()
        val values = row.get(field).objectValue()
        require(values.size() <= 32) { "Declare at most 32 data-address arguments" }
        return copyMap(values.entrySet().associate { (name, value) ->
            require(name.length <= 32) { "Register name is too long" }
            name to dataAddress(value)
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

    // Older formats retain their seven-digit numeric grammar and original limits.
    private fun JsonObject.instructionLimit(version: String): Long {
        if (version !in setOf("17", "18")) return number("instructionLimit").toLong()
        val value = get("instructionLimit")
        require(value is JsonPrimitive && value.isNumber && Regex("0|[1-9][0-9]{0,7}").matches(value.asString)) {
            "Expected an exact bounded instruction limit"
        }
        return value.asLong
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
