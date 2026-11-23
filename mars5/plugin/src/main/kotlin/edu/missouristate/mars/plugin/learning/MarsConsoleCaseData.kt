package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.cli.grading.ConsoleMemoryCheck
import edu.missouristate.mars.cli.grading.ConsoleMarsBotCheck
import edu.missouristate.mars.cli.grading.ConsoleMazeCheck
import edu.missouristate.mars.cli.grading.ConsolePublicationCheck
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
        require(root.keySet() == setOf("formatVersion", "cases") && version in setOf("1", "2", "3", "4", "5", "6", "7")) {
            "Unsupported console-case format or fields"
        }
        val rows = root.get("cases")
        require(rows is JsonArray && rows.size() in 1..256) { "Declare one through 256 console cases" }
        val required = setOf("name", "input", "stdout", "instructionLimit")
        val allowed = required + when (version) {
            "7" -> setOf("memory", "initialMemory", "stores", "reads", "marsBot", "maze", "publication")
            "6" -> setOf("memory", "initialMemory", "stores", "reads", "marsBot", "maze")
            "5" -> setOf("memory", "initialMemory", "stores", "reads", "marsBot")
            "3", "4" -> setOf("memory", "initialMemory", "stores", "reads")
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
            val maximum = if (version in setOf("4", "5", "6", "7")) 3_000_000 else 1_000_000
            require(instructions <= maximum) { "Console-case instruction limit exceeds $maximum" }
            ConsoleCheckCase(name, text(it, "input"), text(it, "stdout"), instructions,
                memory(it, "memory"), memory(it, "initialMemory"), stores(it), reads(it), marsBot(it), maze(it), publication(it))
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

    private fun publication(row: JsonObject): ConsolePublicationCheck? {
        if (!row.has("publication")) return null
        val value = row.get("publication")
        require(value is JsonObject && value.keySet() == setOf("label", "initialMemory", "snapshots", "finalState")) {
            "A publication check needs its marker, setup, snapshots and final state"
        }
        val snapshots = value.get("snapshots")
        require(snapshots is JsonArray && snapshots.size() <= 256) { "Observe at most 256 public marker visits" }
        fun state(raw: JsonElement?): ConsolePublicationCheck.State {
            require(raw is JsonObject && raw.keySet() == setOf("memory", "addressMemory", "accessPolicies")) {
                "A publication state needs memory, relocated pointers and access policies"
            }
            return ConsolePublicationCheck.State(MarsFunctionCaseData.memory(raw, "memory", "22"),
                MarsFunctionCaseData.addressMemory(raw), MarsFunctionCaseData.accessPolicies(raw, "22"))
        }
        require(value.get("initialMemory") is JsonArray) { "Publication setup must be an array" }
        return ConsolePublicationCheck(string(value, "label"), MarsFunctionCaseData.memory(value, "initialMemory", "22"),
            snapshots.map { state(it) }, state(value.get("finalState")))
    }

    private fun marsBot(row: JsonObject): ConsoleMarsBotCheck? {
        if (!row.has("marsBot")) return null
        val bot = row.get("marsBot")
        require(bot is JsonObject && bot.keySet() == setOf("instructionsPerTick", "x", "y", "heading", "moving", "penDown", "trail")) {
            "MarsBot needs its movement rate, final state and complete trail"
        }
        val trail = bot.get("trail")
        require(trail is JsonArray && trail.size() <= ConsoleMarsBotCheck.MAX_VERTICES) { "Declare at most 256 MarsBot trail vertices" }
        val vertices = trail.map {
            require(it is JsonObject && it.keySet() == setOf("x", "y")) { "A MarsBot vertex needs x and y only" }
            integer(it, "x", zero = true) to integer(it, "y", zero = true)
        }
        return ConsoleMarsBotCheck(integer(bot, "instructionsPerTick"), integer(bot, "x", zero = true),
            integer(bot, "y", zero = true), integer(bot, "heading", zero = true), boolean(bot, "moving"),
            boolean(bot, "penDown"), copyOf(vertices))
    }

    private fun boolean(row: JsonObject, field: String): Boolean {
        val value = row.get(field)
        require(value is JsonPrimitive && value.isBoolean) { "Expected a boolean: $field" }
        return value.asBoolean
    }

    private fun maze(row: JsonObject): ConsoleMazeCheck? {
        if (!row.has("maze")) return null
        val value = row.get("maze")
        require(value is JsonObject) { "Expected a maze expectation object" }
        return when (string(value, "kind")) {
            "search" -> {
                require(value.keySet() == setOf("kind", "walls", "start", "goal", "fixtureIndex")) { "A maze search needs walls, endpoints and fixture index" }
                ConsoleMazeCheck.Search(string(value, "walls"), integer(value, "start", zero = true),
                    integer(value, "goal", zero = true), integer(value, "fixtureIndex", zero = true))
            }
            "renderer" -> {
                require(value.keySet() == setOf("kind", "fixture")) { "A maze renderer needs one fixture index" }
                ConsoleMazeCheck.Renderer(integer(value, "fixture", zero = true))
            }
            else -> throw IllegalArgumentException("Unknown maze expectation kind")
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
